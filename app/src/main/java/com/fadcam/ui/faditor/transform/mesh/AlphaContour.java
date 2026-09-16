package com.fadcam.ui.faditor.transform.mesh;

/**
 * Stage 1 of puppeteering: find the OUTLINE of the opaque pixels.
 *
 * <p>JoyRaptor, 2026-09-04 (SPEC_20260904_PUPPET_ARCHITECTURE): <i>"I upload a PNG, it sees a blob
 * of pixels in the shape of a person, puts TRIANGLES AROUND THE SHAPE in a mesh, and anything
 * transparent it cuts off on some threshold."</i> This is the "sees a blob and finds its edge"
 * half; {@code PuppetTriangulator} turns the edge into triangles.
 *
 * <p><b>Takes an alpha array, not a Bitmap.</b> Deliberately: this package has no Android imports
 * and runs on the desktop JVM harness, which is what makes the maths provable off device — and a
 * contour tracer is exactly the kind of code where an off-by-one is invisible on a phone and
 * obvious in a test. The caller extracts alpha from whatever it has.
 *
 * <h3>Why Moore-neighbour tracing and not marching squares</h3>
 * <p>Marching squares emits unordered edge segments that then have to be stitched into a loop, and
 * the stitching is where degenerate cases live. Moore tracing walks the boundary IN ORDER by
 * construction, so the output is a closed ring with no stitching step and no chance of emitting a
 * figure-of-eight. It needs a start pixel and a consistent turn direction, both of which are cheap.
 *
 * <h3>Holes</h3>
 * <p>Traced as well, since 2026-09-16, and the reason was not that a donut looked wrong. An
 * enclosed gap — a hand resting on a hip, an arm against a torso, the inside of a ring — was being
 * FILLED with mesh, and the geodesic weights then measured straight across it. Measured on a
 * frame: 0.718 through the hole against 1.02 around, so dragging the hand dragged the hip through
 * the gap. That is exactly the "through the air" bug the across-the-body measure exists to stop,
 * reintroduced by a hole.
 *
 * <p>A hole comes back as another ring in the same list. Nothing about the shape of the output
 * changed: {@link PuppetTriangulator} works out which rings are holes by NESTING — a ring inside an
 * odd number of others is a hole — so no winding convention has to be agreed with the tracer and
 * the stored format did not need a new field.
 *
 * <h3>What this deliberately does NOT do yet</h3>
 * <ul>
 * </ul>
 *
 * <h3>Multiple blobs: {@link #traceAll}</h3>
 * <p>This file used to keep only the largest connected region and say so. That was wrong for the
 * artwork it was built for — JoyRaptor draws characters as DETACHED LIMBS, so "largest region
 * wins" gave triangles around one arm and nothing around the rest, and every pin on the others
 * moved a dot and no pixels. {@link #traceAll} returns every region worth keeping, largest first,
 * and {@link PuppetTriangulator} concatenates them into one mesh.
 *
 * <p>{@link #trace} is kept and still returns the largest region alone, because a caller that
 * genuinely wants one outline should not have to say "give me all of them and take the first".
 */
public final class AlphaContour {

    /** Below this, a pixel is transparent. 8-bit alpha; the caller may override. */
    public static final int DEFAULT_THRESHOLD = 16;

    private AlphaContour() { }

    /**
     * Trace the outer boundary of the largest opaque region.
     *
     * @param alpha     {@code w*h} alpha values, 0..255, row-major
     * @param w         width in pixels, &gt; 0
     * @param h         height in pixels, &gt; 0
     * @param threshold alpha at or above this is opaque
     * @return a closed ring as interleaved {@code x,y} in UNIT space [0,1], or null when there is
     *         nothing opaque enough to trace. Unit space because everything downstream — the
     *         topology, the pose track, the shader — is already in it, and a contour in pixels
     *         would be the one thing that had to be rescaled when the picture is resized.
     */
    public static float[] trace(int[] alpha, int w, int h, int threshold) {
        float[][] all = traceAll(alpha, w, h, threshold, 1);
        return (all.length == 0) ? null : all[0];
    }

    /**
     * Trace the outer boundary of EVERY opaque region worth keeping.
     *
     * <p>This is the one a puppet wants. A character drawn as a body plus two detached arms is
     * three regions, and a mesh around only the body cannot be posed by the arms' pins.
     *
     * <p><b>Largest first.</b> A caller that cares about the main body — interior seeding density,
     * a "which piece is this" label — gets it at index 0 without sorting.
     *
     * @param minAreaPx regions smaller than this are dropped as specks: anti-aliasing crumbs and
     *                  stray dots. Pass 1 to keep everything. The UI counts islands with the same
     *                  rule, so the number it warns about is the number that gets traced.
     * @return one closed ring per region, interleaved x,y in UNIT space; never null but possibly
     *         empty. A region whose walk degenerates is dropped rather than returned malformed.
     */
    public static float[][] traceAll(int[] alpha, int w, int h, int threshold, int minAreaPx) {
        if (alpha == null || w <= 0 || h <= 0 || alpha.length < w * h) return new float[0][];

        // -- Label every connected opaque region --------------------------------------------
        // A flood fill rather than "first opaque pixel wins": scan order says nothing about which
        // region matters, and the sizes are needed anyway to drop specks and to sort.
        int[] label = new int[w * h];
        int[] stack = new int[w * h];
        java.util.List<int[]> regions = new java.util.ArrayList<>();   // {label, size, startIndex}
        int next = 0;
        for (int i = 0; i < w * h; i++) {
            if (label[i] != 0 || alpha[i] < threshold) continue;
            next++;
            int size = 0, sp = 0;
            stack[sp++] = i;
            label[i] = next;
            while (sp > 0) {
                int p = stack[--sp];
                size++;
                int px = p % w, py = p / w;
                // 4-connectivity: an 8-connected blob can be joined by a single diagonal pixel,
                // and a contour through such a pinch doubles back on itself.
                if (px > 0) sp = push(stack, sp, p - 1, alpha, label, threshold, next);
                if (px < w - 1) sp = push(stack, sp, p + 1, alpha, label, threshold, next);
                if (py > 0) sp = push(stack, sp, p - w, alpha, label, threshold, next);
                if (py < h - 1) sp = push(stack, sp, p + w, alpha, label, threshold, next);
            }
            if (size >= Math.max(1, minAreaPx)) regions.add(new int[]{next, size, i});
        }
        if (regions.isEmpty()) return new float[0][];

        java.util.Collections.sort(regions, new java.util.Comparator<int[]>() {
            @Override public int compare(int[] a, int[] b) { return Integer.compare(b[1], a[1]); }
        });

        java.util.List<float[]> rings = new java.util.ArrayList<>(regions.size());
        for (int[] r : regions) {
            float[] ring = traceRegion(label, w, h, r[0], r[1], r[2]);
            if (ring != null) rings.add(ring);
        }
        traceHoles(alpha, w, h, threshold, Math.max(1, minAreaPx), rings);
        return rings.toArray(new float[rings.size()][]);
    }

    /**
     * Find the ENCLOSED transparent regions and trace them too.
     *
     * <p>A transparent region that reaches the edge of the picture is the background. One that does
     * not is a hole in the artwork, and it must be a hole in the mesh as well or the weights
     * measure across it.
     *
     * <p>Appended to the same list the outer contours went into. Which of them are holes is worked
     * out downstream by nesting, so nothing here has to agree with anything about winding.
     */
    private static void traceHoles(int[] alpha, int w, int h, int threshold, int minAreaPx,
                                   java.util.List<float[]> into) {
        int[] label = new int[w * h];
        int[] stack = new int[w * h];
        int next = 0;
        for (int i = 0; i < w * h; i++) {
            if (label[i] != 0 || alpha[i] >= threshold) continue;
            next++;
            int size = 0, sp = 0, start = i;
            boolean touchesEdge = false;
            stack[sp++] = i;
            label[i] = next;
            while (sp > 0) {
                int p = stack[--sp];
                size++;
                int px = p % w, py = p / w;
                if (px == 0 || py == 0 || px == w - 1 || py == h - 1) touchesEdge = true;
                if (px > 0) sp = pushClear(stack, sp, p - 1, alpha, label, threshold, next);
                if (px < w - 1) sp = pushClear(stack, sp, p + 1, alpha, label, threshold, next);
                if (py > 0) sp = pushClear(stack, sp, p - w, alpha, label, threshold, next);
                if (py < h - 1) sp = pushClear(stack, sp, p + w, alpha, label, threshold, next);
            }
            // The background is not a hole, and neither is a speck of transparency inside a soft
            // edge — that one would put a pinprick in the mesh for every stray pixel.
            if (touchesEdge || size < minAreaPx) continue;
            float[] ring = traceRegion(label, w, h, next, size, start);
            if (ring != null) into.add(ring);
        }
    }

    private static int pushClear(int[] stack, int sp, int p, int[] alpha, int[] label,
                                 int threshold, int lab) {
        if (label[p] == 0 && alpha[p] < threshold) {
            label[p] = lab;
            stack[sp++] = p;
        }
        return sp;
    }

    /** {@link #traceAll} with the default threshold and the default speck floor. */
    public static float[][] traceAll(int[] alpha, int w, int h) {
        return traceAll(alpha, w, h, DEFAULT_THRESHOLD, MIN_REGION_PX);
    }

    /**
     * Pixels below which a connected region is a speck rather than a piece of the character.
     *
     * <p>Matches {@code PuppetMeshBuilder.MIN_ISLAND_PX} deliberately: the UI warns "this artwork
     * is in 3 pieces" using its own count, and a tracer that disagreed would build a mesh with a
     * different number of islands than the warning named.
     */
    public static final int MIN_REGION_PX = 24;

    /** Moore-neighbour walk around ONE labelled region. */
    private static float[] traceRegion(int[] label, int w, int h, int target, int size, int start) {
        // A single opaque pixel has no ring to walk. Emit its own square so downstream code gets
        // a valid (if tiny) polygon rather than null, which would read as "nothing opaque".
        if (size == 1) {
            float x0 = (start % w) / (float) w, y0 = (start / w) / (float) h;
            float x1 = (start % w + 1) / (float) w, y1 = (start / w + 1) / (float) h;
            return new float[]{x0, y0, x1, y0, x1, y1, x0, y1};
        }

        // Eight neighbours clockwise from WEST. Starting at WEST is what makes the first step
        // leave the start pixel along the boundary rather than into the interior.
        final int[] dx = {-1, -1, 0, 1, 1, 1, 0, -1};
        final int[] dy = {0, -1, -1, -1, 0, 1, 1, 1};

        float[] out = new float[256];
        int n = 0;
        int cx = start % w, cy = start / w;
        int backDir = 0;
        final int startX = cx, startY = cy;
        int guard = 8 * (w * h) + 16;   // a bounded walk; a malformed mask must not hang the UI

        do {
            out = append(out, n, (cx + 0.5f) / w, (cy + 0.5f) / h);
            n += 2;
            int found = -1;
            // Resume the scan from the neighbour AFTER the one we came from, which is what keeps
            // the walk hugging the boundary instead of oscillating between two pixels.
            for (int k = 1; k <= 8; k++) {
                int d = (backDir + k) & 7;
                int nx = cx + dx[d], ny = cy + dy[d];
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                if (label[ny * w + nx] != target) continue;
                found = d;
                break;
            }
            if (found < 0) break;                  // isolated after all
            backDir = (found + 4 + 1) & 7;         // point back the way we came, then +1 to resume
            cx += dx[found];
            cy += dy[found];
        } while ((cx != startX || cy != startY) && --guard > 0 && n + 2 < out.length * 2);

        if (n < 6) return null;                    // fewer than 3 points is not a polygon
        float[] ring = new float[n];
        System.arraycopy(out, 0, ring, 0, n);
        return ring;
    }

    /** Convenience: {@link #DEFAULT_THRESHOLD}. */
    public static float[] trace(int[] alpha, int w, int h) {
        return trace(alpha, w, h, DEFAULT_THRESHOLD);
    }

    private static int push(int[] stack, int sp, int p, int[] alpha, int[] label,
                            int threshold, int lab) {
        if (label[p] == 0 && alpha[p] >= threshold) {
            label[p] = lab;
            stack[sp++] = p;
        }
        return sp;
    }

    private static float[] append(float[] a, int n, float x, float y) {
        if (n + 2 > a.length) {
            float[] b = new float[a.length * 2];
            System.arraycopy(a, 0, b, 0, n);
            a = b;
        }
        a[n] = x;
        a[n + 1] = y;
        return a;
    }

    /**
     * Douglas-Peucker: drop points that are within {@code epsilon} of the line they sit on.
     *
     * <p>A raw trace has one point per boundary PIXEL — thousands for a character, and every one
     * would become mesh vertices to solve per frame. The spec's budget is "six, seven or eight"
     * authored pins over a mesh cheap enough to solve on a phone, so the contour has to come down
     * to tens of points before it is triangulated.
     *
     * @param ring    interleaved x,y, closed implicitly
     * @param epsilon in UNIT space. 0.004 is roughly one pixel of a 250px-wide picture.
     * @return the simplified ring; the input when it is already at or below three points
     */
    public static float[] simplify(float[] ring, float epsilon) {
        if (ring == null || ring.length < 8 || !(epsilon > 0f)) return ring;
        int n = ring.length / 2;
        boolean[] keep = new boolean[n];
        keep[0] = true;
        keep[n - 1] = true;
        simplifySegment(ring, 0, n - 1, epsilon, keep);
        int kept = 0;
        for (boolean b : keep) if (b) kept++;
        if (kept < 3) return ring;              // never simplify away the polygon itself
        float[] out = new float[kept * 2];
        int j = 0;
        for (int i = 0; i < n; i++) {
            if (!keep[i]) continue;
            out[j++] = ring[i * 2];
            out[j++] = ring[i * 2 + 1];
        }
        return out;
    }

    /** Iterative rather than recursive: a 50k-point contour would overflow the stack. */
    private static void simplifySegment(float[] r, int lo, int hi, float eps, boolean[] keep) {
        int[] stack = new int[64];
        int sp = 0;
        stack = pushPair(stack, sp, lo, hi);
        sp += 2;
        while (sp > 0) {
            hi = stack[--sp];
            lo = stack[--sp];
            if (hi <= lo + 1) continue;
            float ax = r[lo * 2], ay = r[lo * 2 + 1];
            float bx = r[hi * 2], by = r[hi * 2 + 1];
            float ex = bx - ax, ey = by - ay;
            float len = (float) Math.sqrt(ex * ex + ey * ey);
            int worst = -1;
            float worstD = eps;
            for (int i = lo + 1; i < hi; i++) {
                float px = r[i * 2] - ax, py = r[i * 2 + 1] - ay;
                float d;
                if (len < 1e-9f) {
                    d = (float) Math.sqrt(px * px + py * py);
                } else {
                    // Perpendicular distance to the segment's LINE. The endpoints are already
                    // kept, so a point beyond them still measures honestly against the chord.
                    d = Math.abs(px * ey - py * ex) / len;
                }
                if (d > worstD) { worstD = d; worst = i; }
            }
            if (worst < 0) continue;
            keep[worst] = true;
            stack = pushPair(stack, sp, lo, worst);
            sp += 2;
            stack = pushPair(stack, sp, worst, hi);
            sp += 2;
        }
    }

    private static int[] pushPair(int[] s, int sp, int a, int b) {
        if (sp + 2 > s.length) {
            int[] t = new int[s.length * 2];
            System.arraycopy(s, 0, t, 0, sp);
            s = t;
        }
        s[sp] = a;
        s[sp + 1] = b;
        return s;
    }

    /**
     * Push a ring OUTWARD by {@code amount}, in unit space — the Edge expansion control.
     *
     * <p>A traced contour follows the last pixel above the alpha threshold, which on any drawing
     * with a soft or anti-aliased edge is INSIDE the visible edge of the art. The mesh then stops
     * short, and the outermost sliver of the picture is not carried by any triangle: it stays put
     * while the rest of the limb bends, and the character appears to shed a hairline of itself.
     * Expanding the ring a little takes the fringe inside the mesh.
     *
     * <p>Each point moves along the bisector of its two edges, so a corner moves further than a
     * flat run does — which is what keeps a right angle a right angle instead of rounding it off.
     *
     * <h3>Direction is measured, not assumed</h3>
     * <p>Which way is "out" depends on the ring's winding, and a tracer's winding depends on which
     * way it happened to walk. Rather than reason about it, this offsets one way and checks
     * whether the enclosed area GREW; if it shrank, the whole thing is redone the other way. That
     * is two cheap passes and it cannot be silently backwards — an inward expansion would eat the
     * character's outline, which is the sort of bug that looks like a bad trace.
     *
     * @param amount unit distance to grow by. Clamped to a tenth of the shape's smaller side,
     *               because a large offset on a concave shape self-intersects, and a ring that
     *               crosses itself triangulates into garbage.
     * @return the expanded ring, or the input unchanged for a non-positive amount
     */
    public static float[] expand(float[] ring, float amount) {
        if (ring == null || ring.length < 6 || !(amount > 0f)) return ring;
        int n = ring.length / 2;

        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            minX = Math.min(minX, ring[i * 2]); maxX = Math.max(maxX, ring[i * 2]);
            minY = Math.min(minY, ring[i * 2 + 1]); maxY = Math.max(maxY, ring[i * 2 + 1]);
        }
        float cap = 0.1f * Math.min(maxX - minX, maxY - minY);
        if (!(cap > 0f)) return ring;
        float d = Math.min(amount, cap);

        float before = Math.abs(signedArea2(ring));
        float[] out = offset(ring, d);
        if (Math.abs(signedArea2(out)) < before) out = offset(ring, -d);
        // A degenerate result (a ring that collapsed on itself) is worse than no expansion.
        return Math.abs(signedArea2(out)) >= before ? out : ring;
    }

    /**
     * Expand a whole SET of rings, growing the artwork — which means a HOLE has to shrink.
     *
     * <p>{@link #expand} grows whatever ring it is handed, and for an enclosed gap that is exactly
     * backwards: growing the hole eats the drawing from the inside, and the wider the Edge
     * expansion the more of the character disappears. Which rings are holes is the same nesting
     * rule the triangulator uses — inside an odd number of others — so the two cannot disagree.
     *
     * @return a new array; rings that cannot be expanded are passed through unchanged
     */
    public static float[][] expandAll(float[][] rings, float amount) {
        if (rings == null || rings.length == 0 || !(amount > 0f)) return rings;
        float[][] out = new float[rings.length][];
        for (int i = 0; i < rings.length; i++) {
            float[] r = rings[i];
            if (r == null || r.length < 6) { out[i] = r; continue; }
            int depth = 0;
            for (int j = 0; j < rings.length; j++) {
                if (i == j || rings[j] == null || rings[j].length < 6) continue;
                if (contains(rings[j], r[0], r[1])) depth++;
            }
            // A hole shrinks by the same amount the outline grows, so the ring of artwork between
            // them thickens evenly rather than drifting to one side.
            out[i] = ((depth & 1) == 1) ? shrink(r, amount) : expand(r, amount);
        }
        return out;
    }

    /** {@link #expand} inward: the enclosed area gets SMALLER. Used for holes. */
    private static float[] shrink(float[] ring, float amount) {
        if (ring == null || ring.length < 6 || !(amount > 0f)) return ring;
        float before = Math.abs(signedArea2(ring));
        float[] a = offset(ring, amount);
        float[] b = offset(ring, -amount);
        float aa = Math.abs(signedArea2(a)), ab = Math.abs(signedArea2(b));
        float[] smaller = aa < ab ? a : b;
        // A hole small enough that shrinking would turn it inside out simply closes up, which is
        // the honest answer: at that expansion the gap is not there any more.
        return Math.abs(signedArea2(smaller)) < before ? smaller : ring;
    }

    /** Ray-cast point-in-polygon. Shared by the expander and the nesting test. */
    static boolean contains(float[] ring, float x, float y) {
        if (ring == null || ring.length < 6) return false;
        int n = ring.length / 2;
        boolean in = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            float xi = ring[i * 2], yi = ring[i * 2 + 1];
            float xj = ring[j * 2], yj = ring[j * 2 + 1];
            if (((yi > y) != (yj > y))
                    && (x < (xj - xi) * (y - yi) / (yj - yi + 1e-12f) + xi)) {
                in = !in;
            }
        }
        return in;
    }

    /** One offset pass along each vertex's edge bisector. Sign decides the direction. */
    private static float[] offset(float[] ring, float d) {
        int n = ring.length / 2;
        float[] out = new float[ring.length];
        for (int i = 0; i < n; i++) {
            int prev = (i + n - 1) % n, next = (i + 1) % n;
            float ax = ring[i * 2] - ring[prev * 2], ay = ring[i * 2 + 1] - ring[prev * 2 + 1];
            float bx = ring[next * 2] - ring[i * 2], by = ring[next * 2 + 1] - ring[i * 2 + 1];
            // Each edge's normal, then their sum: the bisector, longer at a sharp corner exactly
            // as a mitre should be.
            float na = (float) Math.sqrt(ax * ax + ay * ay);
            float nb = (float) Math.sqrt(bx * bx + by * by);
            float nx = 0f, ny = 0f;
            if (na > 1e-9f) { nx += ay / na; ny += -ax / na; }
            if (nb > 1e-9f) { nx += by / nb; ny += -bx / nb; }
            float len = (float) Math.sqrt(nx * nx + ny * ny);
            if (len < 1e-9f) {                       // a spike doubling back: leave it where it is
                out[i * 2] = ring[i * 2];
                out[i * 2 + 1] = ring[i * 2 + 1];
                continue;
            }
            // Mitre length, capped: at a needle-sharp corner the true mitre runs away to infinity.
            float scale = Math.min(2f, 2f / len);
            out[i * 2] = ring[i * 2] + nx / len * d * scale;
            out[i * 2 + 1] = ring[i * 2 + 1] + ny / len * d * scale;
        }
        return out;
    }

    /** Signed area * 2. Positive = counter-clockwise in a y-down space. Used to fix winding. */
    public static float signedArea2(float[] ring) {
        if (ring == null || ring.length < 6) return 0f;
        int n = ring.length / 2;
        float s = 0f;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            s += (ring[j * 2] * ring[i * 2 + 1]) - (ring[i * 2] * ring[j * 2 + 1]);
        }
        return s;
    }

    /** Reverse a ring in place so downstream triangulation always sees one winding. */
    public static void reverse(float[] ring) {
        if (ring == null) return;
        int n = ring.length / 2;
        for (int i = 0, j = n - 1; i < j; i++, j--) {
            float x = ring[i * 2], y = ring[i * 2 + 1];
            ring[i * 2] = ring[j * 2];
            ring[i * 2 + 1] = ring[j * 2 + 1];
            ring[j * 2] = x;
            ring[j * 2 + 1] = y;
        }
    }
}

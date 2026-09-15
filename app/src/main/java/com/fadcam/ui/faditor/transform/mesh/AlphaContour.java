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
 * <h3>What this deliberately does NOT do yet</h3>
 * <ul>
 *   <li><b>Holes.</b> Only the outer boundary is traced. A donut gives a disc. Holes need the inner
 *       rings AND a constrained triangulation that honours them, and half of that is worthless —
 *       so it is one decision, later, not a half-feature now.</li>
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
        return rings.toArray(new float[rings.size()][]);
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

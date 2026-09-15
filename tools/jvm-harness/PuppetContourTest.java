import com.fadcam.ui.faditor.transform.mesh.AlphaContour;
import com.fadcam.ui.faditor.transform.mesh.PuppetTriangulator;

/**
 * Stage 1 of puppeteering, proved off device: does the tracer actually find the outline of a blob?
 *
 * <p>This is exactly the kind of code the mesh package's "no Android imports" rule exists for. A
 * contour tracer that is off by one pixel, or that walks into the interior, looks completely
 * plausible on a phone — you get A shape, just not the right one. Here the shapes are synthetic and
 * the right answer is known, so wrong is visible.
 *
 * <p>Run: {@code bash tools/jvm-harness/run-puppet.sh}
 */
public class PuppetContourTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        square();
        circle();
        largestBlobWins();
        emptyIsNull();
        singlePixel();
        simplifyCollapsesAStraightEdge();
        simplifyKeepsCorners();
        windingIsDetectable();
        traceStaysInsideTheBlob();
        // ── stage 2 ──
        triangulatesASquare();
        everyTriangleHasArea();
        trianglesCoverTheShape();
        interiorPointsLandInside();
        concaveShapeIsNotFilledIn();
        windingIsNormalised();
        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    /** A filled axis-aligned rectangle in an otherwise empty field. */
    static int[] rect(int w, int h, int x0, int y0, int x1, int y1) {
        int[] a = new int[w * h];
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) a[y * w + x] = 255;
        }
        return a;
    }

    static int[] disc(int w, int h, float cx, float cy, float r) {
        int[] a = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float dx = x + 0.5f - cx, dy = y + 0.5f - cy;
                if (dx * dx + dy * dy <= r * r) a[y * w + x] = 255;
            }
        }
        return a;
    }

    // ── tests ───────────────────────────────────────────────────────────────────────────────

    static void square() {
        float[] ring = AlphaContour.trace(rect(40, 40, 10, 10, 30, 30), 40, 40);
        check("a square traces to a ring", ring != null && ring.length >= 8);
        if (ring == null) return;
        float minX = 1f, maxX = 0f, minY = 1f, maxY = 0f;
        for (int i = 0; i < ring.length; i += 2) {
            minX = Math.min(minX, ring[i]); maxX = Math.max(maxX, ring[i]);
            minY = Math.min(minY, ring[i + 1]); maxY = Math.max(maxY, ring[i + 1]);
        }
        // The blob spans pixels 10..29, whose CENTRES are 10.5/40 .. 29.5/40.
        check("the ring's bounds match the blob's, not the image's",
                near(minX, 10.5f / 40f, 0.02f) && near(maxX, 29.5f / 40f, 0.02f)
                        && near(minY, 10.5f / 40f, 0.02f) && near(maxY, 29.5f / 40f, 0.02f));
        // A 20x20 square has 76 boundary pixels. Anything far above that means the walk doubled
        // back; far below means it cut a corner.
        int pts = ring.length / 2;
        check("the walk is a boundary, not a fill (" + pts + " points, expect ~76)",
                pts >= 60 && pts <= 100);
    }

    static void circle() {
        float[] ring = AlphaContour.trace(disc(64, 64, 32f, 32f, 20f), 64, 64);
        check("a disc traces", ring != null && ring.length >= 8);
        if (ring == null) return;
        // Every traced point must sit near the rim, never in the middle.
        boolean allOnRim = true;
        for (int i = 0; i < ring.length; i += 2) {
            float dx = ring[i] * 64f - 32f, dy = ring[i + 1] * 64f - 32f;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d < 18f || d > 22f) { allOnRim = false; break; }
        }
        check("every point of a disc's trace lies on its rim", allOnRim);
    }

    static void largestBlobWins() {
        int[] a = rect(60, 60, 5, 5, 9, 9);            // speck: 4x4
        int[] big = rect(60, 60, 20, 20, 50, 50);      // the real subject: 30x30
        for (int i = 0; i < a.length; i++) a[i] = Math.max(a[i], big[i]);
        float[] ring = AlphaContour.trace(a, 60, 60);
        check("a stray speck does not win over the subject", ring != null);
        if (ring == null) return;
        float minX = 1f;
        for (int i = 0; i < ring.length; i += 2) minX = Math.min(minX, ring[i]);
        check("...the trace is the BIG blob (minX " + minX + " should be ~0.34, not ~0.09)",
                minX > 0.25f);
    }

    static void emptyIsNull() {
        check("a fully transparent image traces to null",
                AlphaContour.trace(new int[100], 10, 10) == null);
        check("a blob below the threshold is not opaque",
                AlphaContour.trace(fill(10, 10, 8), 10, 10, 16) == null);
        check("...and the same blob AT the threshold is",
                AlphaContour.trace(fill(10, 10, 16), 10, 10, 16) != null);
    }

    static int[] fill(int w, int h, int v) {
        int[] a = new int[w * h];
        java.util.Arrays.fill(a, v);
        return a;
    }

    static void singlePixel() {
        int[] a = new int[100];
        a[5 * 10 + 5] = 255;
        float[] ring = AlphaContour.trace(a, 10, 10);
        check("one opaque pixel yields a square, not null", ring != null && ring.length == 8);
    }

    static void simplifyCollapsesAStraightEdge() {
        float[] ring = AlphaContour.trace(rect(60, 60, 10, 10, 50, 50), 60, 60);
        int before = ring.length / 2;
        float[] s = AlphaContour.simplify(ring, 0.01f);
        int after = s.length / 2;
        check("simplify collapses a rectangle hard (" + before + " -> " + after + ")",
                after < before / 4);
        check("...but never below a polygon", after >= 3);
    }

    static void simplifyKeepsCorners() {
        float[] ring = AlphaContour.trace(rect(60, 60, 10, 10, 50, 50), 60, 60);
        float[] s = AlphaContour.simplify(ring, 0.01f);
        // The four corners must survive: the simplified bounds must still match the original's.
        float minX = 1f, maxX = 0f, minY = 1f, maxY = 0f;
        for (int i = 0; i < s.length; i += 2) {
            minX = Math.min(minX, s[i]); maxX = Math.max(maxX, s[i]);
            minY = Math.min(minY, s[i + 1]); maxY = Math.max(maxY, s[i + 1]);
        }
        check("simplify keeps the corners (bounds preserved)",
                near(minX, 10.5f / 60f, 0.03f) && near(maxX, 49.5f / 60f, 0.03f)
                        && near(minY, 10.5f / 60f, 0.03f) && near(maxY, 49.5f / 60f, 0.03f));
    }

    static void windingIsDetectable() {
        float[] ring = AlphaContour.trace(rect(40, 40, 10, 10, 30, 30), 40, 40);
        float a1 = AlphaContour.signedArea2(ring);
        check("a traced ring has non-zero signed area", Math.abs(a1) > 1e-4f);
        AlphaContour.reverse(ring);
        float a2 = AlphaContour.signedArea2(ring);
        check("reversing flips the winding sign", a1 * a2 < 0f);
        check("...and preserves the magnitude", near(Math.abs(a1), Math.abs(a2), 1e-4f));
    }

    /** NEGATIVE CONTROL: the walk must never wander into transparent space. */
    static void traceStaysInsideTheBlob() {
        int w = 64, h = 64;
        int[] a = disc(w, h, 32f, 32f, 18f);
        float[] ring = AlphaContour.trace(a, w, h);
        check("disc traced", ring != null);
        if (ring == null) return;
        boolean allOpaque = true;
        for (int i = 0; i < ring.length; i += 2) {
            int px = (int) (ring[i] * w), py = (int) (ring[i + 1] * h);
            if (px < 0 || py < 0 || px >= w || py >= h || a[py * w + px] == 0) {
                allOpaque = false;
                break;
            }
        }
        check("every traced point is an OPAQUE pixel (never walks into empty space)", allOpaque);
    }

    // ── stage 2: triangulation ──────────────────────────────────────────────────────────────

    static float[] simpleRing() {
        float[] r = AlphaContour.trace(rect(60, 60, 10, 10, 50, 50), 60, 60);
        return AlphaContour.simplify(r, 0.01f);
    }

    static void triangulatesASquare() {
        PuppetTriangulator.Mesh m = PuppetTriangulator.triangulate(simpleRing(), 0);
        check("a square triangulates", m != null && m.triangleCount() > 0);
        if (m == null) return;
        // A simple polygon of n points always yields exactly n-2 triangles. Not "about".
        int n = m.contourCount;
        check("n-2 triangles for an n-gon (" + n + " pts -> " + m.triangleCount() + ")",
                m.triangleCount() == n - 2);
        boolean inRange = true;
        for (short i : m.indices) if (i < 0 || i >= m.vertexCount()) inRange = false;
        check("every index is in range", inRange);
    }

    static void everyTriangleHasArea() {
        PuppetTriangulator.Mesh m = PuppetTriangulator.triangulate(simpleRing(), 5);
        check("triangulated with interior points", m != null);
        if (m == null) return;
        float worst = Float.MAX_VALUE;
        for (int t = 0; t < m.indices.length; t += 3) {
            float[] a = v(m, m.indices[t]), b = v(m, m.indices[t + 1]), c = v(m, m.indices[t + 2]);
            float area2 = Math.abs((b[0]-a[0])*(c[1]-a[1]) - (b[1]-a[1])*(c[0]-a[0]));
            worst = Math.min(worst, area2);
        }
        // A zero-area triangle is what a deformer divides by. This is the one that would bite.
        check("no degenerate (zero-area) triangle, worst=" + worst, worst > 1e-9f);
    }

    static void trianglesCoverTheShape() {
        float[] ring = simpleRing();
        PuppetTriangulator.Mesh m = PuppetTriangulator.triangulate(ring, 4);
        if (m == null) { check("covered", false); return; }
        // Total triangle area must equal the polygon's. A gap or an overlap shows up here and
        // nowhere else — a picture with a hole in its mesh still LOOKS fine until it deforms.
        float tri = 0f;
        for (int t = 0; t < m.indices.length; t += 3) {
            float[] a = v(m, m.indices[t]), b = v(m, m.indices[t + 1]), c = v(m, m.indices[t + 2]);
            tri += Math.abs((b[0]-a[0])*(c[1]-a[1]) - (b[1]-a[1])*(c[0]-a[0])) * 0.5f;
        }
        float poly = Math.abs(AlphaContour.signedArea2(ring)) * 0.5f;
        check("triangles tile the polygon exactly (tri=" + tri + " poly=" + poly + ")",
                near(tri, poly, poly * 0.02f));
    }

    static void interiorPointsLandInside() {
        float[] ring = simpleRing();
        PuppetTriangulator.Mesh m = PuppetTriangulator.triangulate(ring, 5);
        if (m == null) { check("interior", false); return; }
        check("interior points were added", m.vertexCount() > m.contourCount);
        boolean allIn = true;
        for (int i = m.contourCount; i < m.vertexCount(); i++) {
            if (!PuppetTriangulator.contains(ring, m.verts[i*2], m.verts[i*2+1])) allIn = false;
        }
        check("every interior point is genuinely inside the contour", allIn);
    }

    /** An L-shape: a grid point in the notch is OUTSIDE and must be rejected. */
    static void concaveShapeIsNotFilledIn() {
        int w = 60, h = 60;
        int[] a = new int[w * h];
        for (int y = 10; y < 50; y++) for (int x = 10; x < 50; x++) a[y*w+x] = 255;
        for (int y = 10; y < 32; y++) for (int x = 32; x < 50; x++) a[y*w+x] = 0;   // notch
        float[] ring = AlphaContour.simplify(AlphaContour.trace(a, w, h), 0.008f);
        check("an L-shape traces", ring != null && ring.length >= 8);
        if (ring == null) return;
        PuppetTriangulator.Mesh m = PuppetTriangulator.triangulate(ring, 6);
        if (m == null) { check("L triangulates", false); return; }
        boolean allIn = true;
        for (int i = m.contourCount; i < m.vertexCount(); i++) {
            if (!PuppetTriangulator.contains(ring, m.verts[i*2], m.verts[i*2+1])) allIn = false;
        }
        check("no interior point lands in the NOTCH of a concave shape", allIn);
    }

    static void windingIsNormalised() {
        float[] ring = simpleRing();
        float[] rev = ring.clone();
        AlphaContour.reverse(rev);
        PuppetTriangulator.Mesh a = PuppetTriangulator.triangulate(ring, 0);
        PuppetTriangulator.Mesh b = PuppetTriangulator.triangulate(rev, 0);
        check("both windings triangulate", a != null && b != null);
        if (a == null || b == null) return;
        check("...to the same triangle count — winding is normalised, not two code paths",
                a.triangleCount() == b.triangleCount());
    }

    static float[] v(PuppetTriangulator.Mesh m, int i) {
        return new float[]{m.verts[i*2], m.verts[i*2+1]};
    }

    // ── plumbing ────────────────────────────────────────────────────────────────────────────

    static boolean near(float a, float b, float eps) { return Math.abs(a - b) <= eps; }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }
}

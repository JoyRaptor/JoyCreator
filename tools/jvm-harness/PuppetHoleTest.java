import com.fadcam.ui.faditor.transform.mesh.*;

/**
 * ENCLOSED HOLES — and the reason is not that a donut should look like a donut.
 *
 * <p>An enclosed gap in the artwork — a hand resting on a hip, an arm against a torso, the inside
 * of a ring — was being FILLED with mesh. The pixels there are transparent either way, so nothing
 * looked wrong. What went wrong was invisible: the geodesic weights measured straight ACROSS the
 * gap, so dragging the hand dragged the hip through it. That is precisely the "through the air"
 * bug the across-the-body measure exists to prevent, walked back in through a hole.
 *
 * <p>Measured on a frame before holes were traced: 0.718 through the hole against 1.02 around it.
 *
 * <p>Run: {@code bash tools/jvm-harness/run-puppet.sh}
 */
public class PuppetHoleTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        theHoleIsTraced();
        noMeshInsideTheHole();
        theWeightsGoAroundNotThrough();
        aSolidShapeIsUnchanged();
        theBackgroundIsNotAHole();
        aSpeckOfTransparencyIsNotAHole();
        expandingGrowsTheArtNotTheHole();
        holesSurviveTheFileFormat();

        System.out.println();
        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    static final int W = 160, H = 160;

    static void rect(int[] a, int x0, int y0, int x1, int y1, int v) {
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) a[y * W + x] = v;
    }

    /** A frame: a solid square with an enclosed transparent square inside it. */
    static int[] frame() {
        int[] a = new int[W * H];
        rect(a, 20, 20, 140, 140, 255);
        rect(a, 50, 50, 110, 110, 0);
        return a;
    }

    static float[][] rings(int[] alpha) {
        float[][] raw = AlphaContour.traceAll(alpha, W, H);
        float[][] out = new float[raw.length][];
        for (int i = 0; i < raw.length; i++) out[i] = AlphaContour.simplify(raw[i], 0.004f);
        return out;
    }

    static PuppetTopology framePuppet() {
        return new PuppetTopology(rings(frame()), 7, new float[]{0.16f, 0.5f, 0.84f, 0.5f});
    }

    // ────────────────────────────────────────────────────────────────────────────────────────

    static void theHoleIsTraced() {
        float[][] r = AlphaContour.traceAll(frame(), W, H);
        check("a frame traces two rings — the outline and the hole (" + r.length + ")",
                r.length == 2);
    }

    static void noMeshInsideTheHole() {
        PuppetTopology t = framePuppet();
        float[] v = new float[t.vertexCount() * 2];
        short[] idx = new short[t.indexCount()];
        t.buildRest(v, null, idx);
        int inside = 0;
        for (int i = 0; i < idx.length; i += 3) {
            float cx = 0, cy = 0;
            for (int k = 0; k < 3; k++) {
                cx += v[(idx[i + k] & 0xFFFF) * 2] / 3f;
                cy += v[(idx[i + k] & 0xFFFF) * 2 + 1] / 3f;
            }
            if (cx > 0.36f && cx < 0.64f && cy > 0.36f && cy < 0.64f) inside++;
        }
        System.out.println("    triangles inside the hole: " + inside + " (was 21 before holes)");
        check("the mesh does not span the hole", inside == 0);
    }

    /** THE point of the whole exercise. */
    static void theWeightsGoAroundNotThrough() {
        PuppetTopology t = framePuppet();
        float[] v = new float[t.vertexCount() * 2];
        short[] idx = new short[t.indexCount()];
        t.buildRest(v, null, idx);
        float[] d = new float[t.vertexCount()];
        check("the distance field builds", PuppetWeights.distancesFrom(v, idx, 0.16f, 0.5f, d));

        int right = -1;
        float best = Float.MAX_VALUE;
        for (int q = 0; q < t.vertexCount(); q++) {
            float dx = v[q * 2] - 0.84f, dy = v[q * 2 + 1] - 0.5f;
            if (dx * dx + dy * dy < best) { best = dx * dx + dy * dy; right = q; }
        }
        System.out.printf("    left bar to right bar: %.3f  (through %.2f, around %.2f)%n",
                d[right], 0.68f, 1.02f);
        // Through the hole is 0.68 and around the frame is 1.02, so anything near 0.68 means the
        // gap is still being treated as solid material.
        check("the distance goes around the frame, not across the gap", d[right] > 0.9f);
    }

    static void aSolidShapeIsUnchanged() {
        int[] a = new int[W * H];
        rect(a, 20, 20, 140, 140, 255);
        float[][] r = AlphaContour.traceAll(a, W, H);
        // Every character without an enclosed gap must trace exactly as it did before holes
        // existed, or this change would have moved every puppet in every project.
        check("a shape with no hole still traces one ring", r.length == 1);
        PuppetTopology t = new PuppetTopology(rings(a), 7, new float[]{0.3f, 0.5f, 0.7f, 0.5f});
        check("and it triangulates normally", t.vertexCount() > 10 && t.indexCount() > 20);
    }

    static void theBackgroundIsNotAHole() {
        int[] a = new int[W * H];
        rect(a, 20, 20, 140, 140, 255);           // transparent all round the outside
        float[][] r = AlphaContour.traceAll(a, W, H);
        // The transparency outside the character reaches the edge of the picture, so it is the
        // background. Tracing it would put a ring around the whole frame and swallow the artwork.
        check("the transparency outside the character is not traced as a hole", r.length == 1);
    }

    static void aSpeckOfTransparencyIsNotAHole() {
        int[] a = new int[W * H];
        rect(a, 20, 20, 140, 140, 255);
        rect(a, 70, 70, 73, 73, 0);               // a 9px pinprick
        float[][] r = AlphaContour.traceAll(a, W, H);
        // A soft edge leaves specks of transparency inside the art. A pinprick hole per speck
        // would shred the mesh.
        check("a transparent speck is not a hole (" + r.length + ")", r.length == 1);
    }

    static void expandingGrowsTheArtNotTheHole() {
        float[][] r = rings(frame());
        float outerBefore = Math.abs(AlphaContour.signedArea2(r[0]));
        float holeBefore = Math.abs(AlphaContour.signedArea2(r[1]));
        float[][] grown = AlphaContour.expandAll(r, 0.02f);
        float outerAfter = Math.abs(AlphaContour.signedArea2(grown[0]));
        float holeAfter = Math.abs(AlphaContour.signedArea2(grown[1]));
        System.out.printf("    outline %.4f -> %.4f, hole %.4f -> %.4f%n",
                outerBefore, outerAfter, holeBefore, holeAfter);
        check("the outline grows", outerAfter > outerBefore);
        // Growing the hole too would eat the character from the inside, and the higher the Edge
        // expansion the more of the drawing would disappear.
        check("and the hole SHRINKS, so the artwork thickens on both edges",
                holeAfter < holeBefore);
    }

    static void holesSurviveTheFileFormat() {
        PuppetTopology t = framePuppet();
        MeshTopology back = MeshTopologies.create("puppet", t.params());
        check("a holed puppet rebuilds from its params", back instanceof PuppetTopology);
        if (!(back instanceof PuppetTopology)) return;
        // Holes ride in the existing ring list and are re-classified by nesting on load, so no
        // field had to be added to the stored format.
        check("with the same vertices", back.vertexCount() == t.vertexCount());
        check("the same triangles", back.indexCount() == t.indexCount());
        check("and the same identity", back.topologyId() == t.topologyId());
    }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }
}

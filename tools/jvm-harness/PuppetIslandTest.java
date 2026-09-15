import com.fadcam.ui.faditor.transform.mesh.*;

/**
 * DETACHED LIMBS — the artwork this feature is actually for.
 *
 * <p>JoyRaptor draws a character as separate pieces: a body, and arms that do not touch it.
 * {@code AlphaContour.trace} kept only the LARGEST connected region and said so in its own doc, so
 * such a character got triangles around one piece and nothing around the rest — and every pin on
 * the others moved a handle and no pixels. That is the blocking case, not an edge case.
 *
 * <p>These prove the four things that have to be true for it:
 * <ul>
 *   <li>every piece worth keeping is traced, and specks are not;</li>
 *   <li>the pieces concatenate into ONE mesh with no triangle spanning the gap;</li>
 *   <li><b>a pin on the left arm moves the left arm and EXACTLY nothing else</b> — zero, not
 *       "less", because the distance across a gap is infinite rather than merely large;</li>
 *   <li>a piece nobody pinned still travels with the character instead of being left behind.</li>
 * </ul>
 *
 * <p>Run: {@code bash tools/jvm-harness/run-puppet.sh}
 */
public class PuppetIslandTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        System.out.println("-- tracing every piece --");
        everyPieceIsTraced();
        specksAreNotPieces();
        biggestPieceComesFirst();
        theOldSinglePieceCallStillWorks();

        System.out.println();
        System.out.println("-- one mesh, still separate --");
        piecesConcatenateIntoOneMesh();
        noTriangleSpansTheGap();
        theSmallPieceIsNotPackedDenser();

        System.out.println();
        System.out.println("-- posing --");
        aPinMovesItsOwnPieceOnly();
        anUnpinnedPieceRidesAlong();
        aPinInTheGapPicksOnePiece();
        everyRowStillSumsToOne();

        System.out.println();
        System.out.println("-- the file format --");
        threeIslandsRoundTrip();
        aPuppetSavedBeforeIslandsStillOpens();
        bindCost();

        System.out.println();
        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    static final int W = 120, H = 120;

    /**
     * A character in three pieces: a tall body down the middle, and an arm either side with a
     * clear gap. Drawn as rectangles because a rectangle's contour is exact — anything subtler
     * would be testing the tracer's fidelity rather than the island handling.
     */
    static int[] threePieceCharacter() {
        int[] a = new int[W * H];
        rect(a, 45, 20, 75, 100);      // body
        rect(a, 10, 35, 35, 65);       // left arm, a 10px gap away
        rect(a, 85, 35, 110, 65);      // right arm
        return a;
    }

    static void rect(int[] a, int x0, int y0, int x1, int y1) {
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) a[y * W + x] = 255;
        }
    }

    static float[][] rings(int[] alpha) {
        float[][] raw = AlphaContour.traceAll(alpha, W, H);
        float[][] out = new float[raw.length][];
        for (int i = 0; i < raw.length; i++) out[i] = AlphaContour.simplify(raw[i], 0.004f);
        return out;
    }

    /** Which island a vertex is in, read back from the topology's own table. */
    static int islandOf(PuppetTopology t, int v) {
        int[] s = t.islandStart();
        for (int i = 0; i + 1 < s.length; i++) if (v >= s[i] && v < s[i + 1]) return i;
        return -1;
    }

    static MeshBuffers solved(PuppetTopology t, float[] pose) {
        MeshBuffers b = new MeshBuffers();
        b.bind(t);
        return new PuppetDeformer().solve(t, b, pose) ? b : null;
    }

    /** The largest distance any vertex of one island moved. */
    static float worstMove(PuppetTopology t, MeshBuffers b, int island) {
        float worst = 0f;
        for (int v = 0; v < t.vertexCount(); v++) {
            if (islandOf(t, v) != island) continue;
            float dx = b.positions[v * 2] - b.rest[v * 2];
            float dy = b.positions[v * 2 + 1] - b.rest[v * 2 + 1];
            worst = Math.max(worst, (float) Math.sqrt(dx * dx + dy * dy));
        }
        return worst;
    }

    // ── tracing ─────────────────────────────────────────────────────────────────────────────

    static void everyPieceIsTraced() {
        float[][] r = AlphaContour.traceAll(threePieceCharacter(), W, H);
        check("all three pieces are traced, not just the biggest (" + r.length + ")",
                r.length == 3);
    }

    static void specksAreNotPieces() {
        int[] a = threePieceCharacter();
        a[3 * W + 3] = 255;                                  // a single stray pixel
        rect(a, 8, 8, 11, 11);                               // and a 9px crumb
        float[][] r = AlphaContour.traceAll(a, W, H);
        check("anti-aliasing crumbs are not limbs (" + r.length + " pieces)", r.length == 3);
        // The floor is a floor, not a filter on everything small: asking for all of them gets
        // all of them, which is what a caller tuning the threshold needs.
        float[][] all = AlphaContour.traceAll(a, W, H, AlphaContour.DEFAULT_THRESHOLD, 1);
        check("...but nothing is hidden from a caller that asks for everything ("
                + all.length + ")", all.length == 5);
    }

    static void biggestPieceComesFirst() {
        float[][] r = AlphaContour.traceAll(threePieceCharacter(), W, H);
        if (r.length < 3) { check("need three pieces to compare", false); return; }
        float a0 = Math.abs(AlphaContour.signedArea2(r[0]));
        boolean first = true;
        for (int i = 1; i < r.length; i++) {
            if (Math.abs(AlphaContour.signedArea2(r[i])) > a0) first = false;
        }
        // The body is what interior density and any "which piece is this" label key off.
        check("the body comes back first", first);
    }

    static void theOldSinglePieceCallStillWorks() {
        float[] one = AlphaContour.trace(threePieceCharacter(), W, H);
        check("trace() still returns exactly one ring", one != null && one.length >= 6);
        float[][] all = AlphaContour.traceAll(threePieceCharacter(), W, H);
        boolean same = one != null && all.length > 0 && java.util.Arrays.equals(one, all[0]);
        check("...and it is the same ring traceAll calls the biggest", same);
    }

    // ── one mesh ────────────────────────────────────────────────────────────────────────────

    static void piecesConcatenateIntoOneMesh() {
        PuppetTopology t = new PuppetTopology(rings(threePieceCharacter()), 5, new float[0]);
        check("the character is one topology with three islands (" + t.islandCount() + ")",
                t.islandCount() == 3);
        int[] s = t.islandStart();
        check("the island table covers every vertex exactly once",
                s.length == 4 && s[0] == 0 && s[3] == t.vertexCount());
        boolean ascending = true;
        for (int i = 1; i < s.length; i++) if (s[i] <= s[i - 1]) ascending = false;
        check("and no island is empty", ascending);
    }

    static void noTriangleSpansTheGap() {
        PuppetTopology t = new PuppetTopology(rings(threePieceCharacter()), 5, new float[0]);
        short[] idx = new short[t.indexCount()];
        float[] rest = new float[t.vertexCount() * 2];
        t.buildRest(rest, null, idx);
        boolean clean = true, inRange = true;
        for (int i = 0; i < idx.length; i += 3) {
            int a = idx[i] & 0xFFFF, b = idx[i + 1] & 0xFFFF, c = idx[i + 2] & 0xFFFF;
            if (a >= t.vertexCount() || b >= t.vertexCount() || c >= t.vertexCount()) {
                inRange = false;
                continue;
            }
            if (islandOf(t, a) != islandOf(t, b) || islandOf(t, b) != islandOf(t, c)) clean = false;
        }
        // If this fails the arms are welded to the body by a triangle, and bending one drags the
        // other through the air — the very thing the gap in the artwork says must not happen.
        check("every index points at a real vertex after the offset rebase", inRange);
        check("no triangle joins two pieces of the artwork", clean);
    }

    static void theSmallPieceIsNotPackedDenser() {
        PuppetTopology t = new PuppetTopology(rings(threePieceCharacter()), 6, new float[0]);
        int[] s = t.islandStart();
        int body = s[1] - s[0], arm = s[2] - s[1];
        System.out.println("    vertices: body " + body + ", arm " + arm);
        // An arm handed the body's density would carry as many vertices in a fifth of the area —
        // more to solve, and geodesic distances measured on two different scales.
        check("the small piece is not denser than the big one", arm <= body);
        check("but it still has enough vertices to bend (" + arm + ")", arm >= 6);
    }

    // ── posing ──────────────────────────────────────────────────────────────────────────────

    /** THE headline. One pin per arm; drag one, and the other must not move AT ALL. */
    static void aPinMovesItsOwnPieceOnly() {
        float[][] r = rings(threePieceCharacter());
        // A pin in each arm and two in the body, placed in unit space over the rectangles above.
        float[] pins = {0.19f, 0.42f, 0.81f, 0.42f, 0.50f, 0.30f, 0.50f, 0.70f};
        PuppetTopology t = new PuppetTopology(r, 5, pins);
        float[] pose = new float[t.handleArity()];
        pose[0] = 0.10f;
        pose[1] = -0.15f;                                  // haul the LEFT arm up and out
        MeshBuffers b = solved(t, pose);
        check("the three-piece character solves", b != null);
        if (b == null) return;

        int left = -1, right = -1;
        for (int v = 0; v < t.vertexCount(); v++) {
            if (b.rest[v * 2] < 0.35f) left = islandOf(t, v);
            if (b.rest[v * 2] > 0.65f) right = islandOf(t, v);
        }
        float movedLeft = worstMove(t, b, left);
        float movedRight = worstMove(t, b, right);
        System.out.printf("    left arm moved %.4f, right arm moved %.4f%n",
                movedLeft, movedRight);
        check("the dragged arm moves (" + fmt(movedLeft) + ")", movedLeft > 0.05f);
        // EXACTLY zero. Not small — the distance across a gap is infinite, so the weight is zero
        // rather than tiny, and there is no threshold here to tune later.
        check("the other arm does not move at all (" + fmt(movedRight) + ")", movedRight == 0f);
    }

    static void anUnpinnedPieceRidesAlong() {
        float[][] r = rings(threePieceCharacter());
        // Pins on the body and the LEFT arm only. Nobody pinned the right arm.
        float[] pins = {0.19f, 0.42f, 0.50f, 0.30f, 0.50f, 0.70f};
        PuppetTopology t = new PuppetTopology(r, 5, pins);
        float[] pose = new float[t.handleArity()];
        pose[2] = 0.12f;                                   // move the body
        pose[4] = 0.12f;
        MeshBuffers b = solved(t, pose);
        check("a partly-pinned character solves", b != null);
        if (b == null) return;
        int right = -1;
        for (int v = 0; v < t.vertexCount(); v++) if (b.rest[v * 2] > 0.65f) right = islandOf(t, v);
        float moved = worstMove(t, b, right);
        // A piece with no influence from anything would sit still while the body walked off,
        // which reads as the character coming apart. It rides instead.
        check("a piece nobody pinned still travels with the body (" + fmt(moved) + ")",
                moved > 0.02f);
    }

    static void aPinInTheGapPicksOnePiece() {
        float[][] r = rings(threePieceCharacter());
        // 0.36 sits in the empty gap between the left arm (ends 0.29) and the body (starts 0.379).
        // Every other piece gets its own pin, so nothing here is riding along as an orphan — if a
        // piece moves, it is because the gap pin reached it.
        float[] pins = {0.36f, 0.50f, 0.19f, 0.42f, 0.50f, 0.70f, 0.81f, 0.42f};
        PuppetTopology t = new PuppetTopology(r, 5, pins);
        float[] pose = new float[t.handleArity()];
        pose[0] = 0.10f;
        MeshBuffers b = solved(t, pose);
        check("a pin dropped in the gap still solves", b != null);
        if (b == null) return;
        int left = -1, body = -1;
        for (int v = 0; v < t.vertexCount(); v++) {
            if (b.rest[v * 2] < 0.35f) left = islandOf(t, v);
            if (b.rest[v * 2] > 0.4f && b.rest[v * 2] < 0.6f) body = islandOf(t, v);
        }
        float ml = worstMove(t, b, left), mb = worstMove(t, b, body);
        System.out.printf("    gap pin moved: left arm %.4f, body %.4f%n", ml, mb);
        // EXACTLY ONE of them. A pin whose three nearest vertices straddle the gap would seed
        // both and weld them together — which is the bug measuring along the mesh exists to
        // prevent, and it would only ever show up on artwork with a narrow gap.
        check("a pin dropped in the gap joins one piece, not both",
                (ml > 0f) != (mb > 0f));
        check("...and it joins the nearer one, which is the body here", mb > 0f && ml == 0f);
    }

    static void everyRowStillSumsToOne() {
        float[] pins = {0.19f, 0.42f, 0.81f, 0.42f, 0.50f, 0.50f};
        PuppetTopology t = new PuppetTopology(rings(threePieceCharacter()), 5, pins);
        PuppetWeights w = t.weights();
        check("a multi-island puppet has a weight table", w != null);
        if (w == null) return;
        float worst = 0f;
        boolean finite = true;
        for (int v = 0; v < w.vertexCount(); v++) {
            float sum = 0f;
            for (int i = 0; i < w.pinCount(); i++) {
                float x = w.weight(v, i);
                if (Float.isNaN(x) || Float.isInfinite(x)) finite = false;
                sum += x;
            }
            worst = Math.max(worst, Math.abs(1f - sum));
        }
        check("every vertex still sums to 1 across islands (worst " + fmt(worst) + ")",
                worst < 1e-4f);
        check("and nothing is NaN", finite);
    }

    // ── the file format ─────────────────────────────────────────────────────────────────────

    static void threeIslandsRoundTrip() {
        float[] pins = {0.19f, 0.42f, 0.81f, 0.42f};
        PuppetTopology t = new PuppetTopology(rings(threePieceCharacter()), 5, pins);
        MeshTopology back = MeshTopologies.create("puppet", t.params());
        check("a three-piece puppet rebuilds from its own params", back instanceof PuppetTopology);
        if (!(back instanceof PuppetTopology)) return;
        PuppetTopology p = (PuppetTopology) back;
        check("with all three pieces (" + p.islandCount() + ")", p.islandCount() == 3);
        check("the same vertices", p.vertexCount() == t.vertexCount());
        check("the same triangles", p.indexCount() == t.indexCount());
        check("the same island boundaries",
                java.util.Arrays.equals(p.islandStart(), t.islandStart()));
        // Same puppet, same id — or every reload silently rebuilds every mesh.
        check("and the same identity", p.topologyId() == t.topologyId());
    }

    static void aPuppetSavedBeforeIslandsStillOpens() {
        // A v1 params array, written by hand exactly as the old code laid it out:
        // [1, interior, pinCount, ringPointCount, pins..., ring...]
        float[] ring = {0.2f, 0.2f, 0.8f, 0.2f, 0.8f, 0.8f, 0.2f, 0.8f};
        float[] pins = {0.35f, 0.5f, 0.65f, 0.5f};
        float[] v1 = new float[4 + pins.length + ring.length];
        v1[0] = 1f;
        v1[1] = 4f;
        v1[2] = pins.length / 2;
        v1[3] = ring.length / 2;
        System.arraycopy(pins, 0, v1, 4, pins.length);
        System.arraycopy(ring, 0, v1, 4 + pins.length, ring.length);

        MeshTopology back = MeshTopologies.create("puppet", v1);
        check("a puppet saved before detached limbs existed still opens",
                back instanceof PuppetTopology);
        if (!(back instanceof PuppetTopology)) return;
        PuppetTopology p = (PuppetTopology) back;
        check("as a one-piece puppet", p.islandCount() == 1);
        check("with its pins intact", p.handleCount() == 2);
        // And it is byte-identical to building it the old way, so nothing about an existing
        // project shifts under the user on the version they upgrade.
        PuppetTopology fresh = new PuppetTopology(ring, 4, pins);
        check("identical to constructing it directly", p.topologyId() == fresh.topologyId());
    }

    static void bindCost() {
        float[] pins = {0.19f, 0.42f, 0.81f, 0.42f, 0.50f, 0.30f, 0.50f, 0.70f};
        float[][] r = rings(threePieceCharacter());
        PuppetTopology t = new PuppetTopology(r, 6, pins);
        float[] verts = new float[t.vertexCount() * 2];
        short[] idx = new short[t.indexCount()];
        t.buildRest(verts, null, idx);
        int[] starts = t.islandStart();
        for (int i = 0; i < 20; i++) PuppetWeights.build(verts, idx, pins, starts);
        long t0 = System.nanoTime();
        int iters = 200;
        for (int i = 0; i < iters; i++) PuppetWeights.build(verts, idx, pins, starts);
        double ms = (System.nanoTime() - t0) / 1e6 / iters;

        MeshBuffers b = new MeshBuffers();
        b.bind(t);
        PuppetDeformer d = new PuppetDeformer();
        float[] pose = new float[t.handleArity()];
        pose[0] = 0.05f;
        for (int i = 0; i < 200; i++) d.solve(t, b, pose);
        long t1 = System.nanoTime();
        for (int i = 0; i < 2000; i++) d.solve(t, b, pose);
        double solveMs = (System.nanoTime() - t1) / 1e6 / 2000;

        System.out.printf("    three-piece character, %d verts: bind %.4f ms, solve %.4f ms%n",
                t.vertexCount(), ms, solveMs);
        check("binding a detached-limb character stays inside the 3 ms budget", ms < 3.0);
        check("and it solves in well under a frame", solveMs < 1.0);
    }

    // ── plumbing ────────────────────────────────────────────────────────────────────────────

    static String fmt(float f) { return String.format("%.4f", f); }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }
}

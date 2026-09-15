import com.fadcam.ui.faditor.transform.mesh.*;

/**
 * Stage 3 of puppeteering: does the MLS solver actually do what a puppet needs?
 *
 * <p>Written adversarially. A deformer that merely RUNS is worthless — it will happily produce a
 * plausible-looking wrong answer, and on a phone "plausible" is indistinguishable from "correct"
 * until a character melts. So these assert PROPERTIES that only a correct rigid solve satisfies:
 *
 * <ul>
 *   <li><b>Interpolation.</b> A pin must land exactly where it was dragged. If it does not, the
 *       user is fighting the tool.</li>
 *   <li><b>Rigidity.</b> Translating every pin must translate the picture and change NOTHING else —
 *       no scale, no shear. This is the one that separates rigid from affine, and it is the one a
 *       wrong implementation fails.</li>
 *   <li><b>Rotation is a rotation.</b> Rotating all pins about a point must rotate the mesh and
 *       preserve every distance. An affine or similarity solve fails this.</li>
 *   <li><b>Locality.</b> Moving one pin must not move the far side of the shape as much as the
 *       near side.</li>
 *   <li><b>No NaN, ever</b>, including the degenerate cases that have no mathematical answer.</li>
 * </ul>
 *
 * <p>Plus a COST MEASUREMENT, because the spec says the solver's cost is the biggest unknown and
 * "needs its own cost study". Measured, not asserted.
 *
 * <p>Run: {@code bash tools/jvm-harness/run-puppet.sh}
 */
public class PuppetSolverTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        topologyRoundTrips();
        topologyIsAMeshTopology();
        identityPoseMovesNothing();
        pinsInterpolate();
        translationIsRigid();
        rotationPreservesDistances();
        oneP0inTranslates();
        influenceIsLocal();
        noPinsIsIdentity();
        degenerateCasesNeverNaN();
        poseTrackNeedsNoNewCode();
        costStudy();
        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    /** A blobby shape: a disc, traced and simplified, like a real subject would be. */
    static float[] discRing() {
        int w = 96, h = 96;
        int[] a = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float dx = x + 0.5f - 48f, dy = y + 0.5f - 48f;
                if (dx * dx + dy * dy <= 34f * 34f) a[y * w + x] = 255;
            }
        }
        return AlphaContour.simplify(AlphaContour.trace(a, w, h), 0.006f);
    }

    static PuppetTopology puppet(float[] pins, int interior) {
        return new PuppetTopology(discRing(), interior, pins);
    }

    /** Four pins near the compass points of the disc. */
    static float[] fourPins() {
        return new float[]{0.5f, 0.25f, 0.75f, 0.5f, 0.5f, 0.75f, 0.25f, 0.5f};
    }

    static MeshBuffers solved(PuppetTopology t, float[] pose) {
        MeshBuffers b = new MeshBuffers();
        b.bind(t);
        boolean ok = new PuppetDeformer().solve(t, b, pose);
        return ok ? b : null;
    }

    // ── tests ───────────────────────────────────────────────────────────────────────────────

    static void topologyRoundTrips() {
        PuppetTopology t = puppet(fourPins(), 4);
        PuppetTopology b = PuppetTopology.fromParams(t.params());
        check("a puppet topology round-trips through params()", b != null);
        if (b == null) return;
        check("...same vertex count", b.vertexCount() == t.vertexCount());
        check("...same triangle count", b.indexCount() == t.indexCount());
        check("...same handle count", b.handleCount() == t.handleCount());
        check("...same structural stamp", b.topologyId() == t.topologyId());
        boolean pinsSame = true;
        for (int i = 0; i < t.handleCount(); i++) {
            if (Math.abs(b.handleRestX(i) - t.handleRestX(i)) > 1e-6f
                    || Math.abs(b.handleRestY(i) - t.handleRestY(i)) > 1e-6f) pinsSame = false;
        }
        check("...pins land in the same places", pinsSame);
        check("malformed params return null rather than throwing",
                PuppetTopology.fromParams(new float[]{9f, 9f}) == null
                        && PuppetTopology.fromParams(null) == null);
    }

    /** The architectural claim, asserted: nothing downstream can tell the two topologies apart. */
    static void topologyIsAMeshTopology() {
        MeshTopology t = puppet(fourPins(), 3);
        check("a puppet IS a MeshTopology", t instanceof MeshTopology);
        check("handleComponents is 2, same as a lattice", t.handleComponents() == 2);
        check("handleArity = pins * 2", t.handleArity() == t.handleCount() * 2);
        check("indexCount is a multiple of 3", t.indexCount() % 3 == 0);
        MeshBuffers b = new MeshBuffers();
        check("MeshBuffers binds a puppet with no special case", b.bind(t) || b.hasGeometry());
        check("...and gets geometry", b.hasGeometry());
        // UV == rest is what keeps the SHADER identical for both topologies.
        boolean uvIsRest = true;
        for (int i = 0; i < t.vertexCount() * 2; i++) {
            if (Math.abs(b.uvs[i] - b.rest[i]) > 1e-6f) uvIsRest = false;
        }
        check("uv == rest, exactly as the lattice does (one shader serves both)", uvIsRest);
    }

    static void identityPoseMovesNothing() {
        PuppetTopology t = puppet(fourPins(), 4);
        MeshBuffers b = solved(t, new float[t.handleArity()]);
        check("identity pose solves", b != null);
        if (b == null) return;
        float worst = 0f;
        for (int i = 0; i < t.vertexCount() * 2; i++) {
            worst = Math.max(worst, Math.abs(b.positions[i] - b.rest[i]));
        }
        check("an identity pose moves NOTHING (worst " + worst + ")", worst < 1e-5f);
        check("isIdentity agrees", new PuppetDeformer().isIdentity(new float[t.handleArity()]));
    }

    /** THE headline property: a pin lands where it was dragged. */
    static void pinsInterpolate() {
        float[] pins = fourPins();
        PuppetTopology t = puppet(pins, 5);
        float[] pose = new float[t.handleArity()];
        pose[0] = 0.10f; pose[1] = -0.06f;      // drag pin 0
        pose[4] = -0.04f; pose[5] = 0.08f;      // and pin 2
        MeshBuffers b = solved(t, pose);
        check("posed solve succeeds", b != null);
        if (b == null) return;
        // The pins are not mesh vertices, so evaluate the map AT each pin's rest position by
        // finding the nearest vertex — with interior seeding, one is close.
        // Worst EXCESS over the allowed slack. Starts below zero so "nothing exceeded" is
        // representable — initialising it at 0 made the assertion unreachable, which is a test
        // bug I shipped and the run caught by printing "slack 0.0".
        float worstExcess = -Float.MAX_VALUE;
        float worstErr = 0f;
        for (int i = 0; i < t.handleCount(); i++) {
            float prx = t.handleRestX(i), pry = t.handleRestY(i);
            float wantX = prx + pose[i * 2], wantY = pry + pose[i * 2 + 1];
            int nearest = -1; float nd = Float.MAX_VALUE;
            for (int v = 0; v < t.vertexCount(); v++) {
                float dx = b.rest[v * 2] - prx, dy = b.rest[v * 2 + 1] - pry;
                float d = dx * dx + dy * dy;
                if (d < nd) { nd = d; nearest = v; }
            }
            // The nearest vertex is not the pin, so it cannot be expected to land exactly on the
            // pin's target — only within the distance that separates them, plus a little.
            float slack = (float) Math.sqrt(nd) * 1.6f + 0.02f;
            float gx = b.positions[nearest * 2] - wantX, gy = b.positions[nearest * 2 + 1] - wantY;
            float err = (float) Math.sqrt(gx * gx + gy * gy);
            worstErr = Math.max(worstErr, err);
            worstExcess = Math.max(worstExcess, err - slack);
        }
        check("every pin pulls its neighbourhood to where it was dragged"
                        + " (worst err " + String.format("%.4f", worstErr)
                        + ", excess over slack " + String.format("%.4f", worstExcess) + ")",
                worstExcess <= 0f);
    }

    /** RIGID, part 1: translate every pin -> the picture translates and does nothing else. */
    static void translationIsRigid() {
        PuppetTopology t = puppet(fourPins(), 5);
        float dx = 0.13f, dy = -0.07f;
        float[] pose = new float[t.handleArity()];
        for (int i = 0; i < t.handleCount(); i++) { pose[i * 2] = dx; pose[i * 2 + 1] = dy; }
        MeshBuffers b = solved(t, pose);
        check("uniform translation solves", b != null);
        if (b == null) return;
        float worst = 0f;
        for (int v = 0; v < t.vertexCount(); v++) {
            worst = Math.max(worst, Math.abs(b.positions[v * 2] - (b.rest[v * 2] + dx)));
            worst = Math.max(worst, Math.abs(b.positions[v * 2 + 1] - (b.rest[v * 2 + 1] + dy)));
        }
        check("translating all pins translates the mesh EXACTLY — no scale, no shear (err "
                + worst + ")", worst < 1e-4f);
    }

    /** RIGID, part 2: rotate every pin -> every pairwise distance survives. An affine solve fails. */
    static void rotationPreservesDistances() {
        PuppetTopology t = puppet(fourPins(), 5);
        final float ang = 0.6f;                  // ~34 degrees
        final float cx = 0.5f, cy = 0.5f;
        float[] pose = new float[t.handleArity()];
        for (int i = 0; i < t.handleCount(); i++) {
            float x = t.handleRestX(i) - cx, y = t.handleRestY(i) - cy;
            float rx = (float) (x * Math.cos(ang) - y * Math.sin(ang));
            float ry = (float) (x * Math.sin(ang) + y * Math.cos(ang));
            pose[i * 2] = (cx + rx) - t.handleRestX(i);
            pose[i * 2 + 1] = (cy + ry) - t.handleRestY(i);
        }
        MeshBuffers b = solved(t, pose);
        check("rotation solves", b != null);
        if (b == null) return;
        float worstRatio = 0f;
        int n = t.vertexCount();
        for (int i = 0; i < n; i += 3) {
            for (int j = i + 1; j < n; j += 7) {
                float r = dist(b.rest, i, j), p = dist(b.positions, i, j);
                if (r < 1e-5f) continue;
                worstRatio = Math.max(worstRatio, Math.abs(p / r - 1f));
            }
        }
        check("rotating all pins preserves every distance — it is a ROTATION, not a scale "
                + "(worst " + String.format("%.4f", worstRatio) + ")", worstRatio < 0.01f);
    }

    static float dist(float[] a, int i, int j) {
        float dx = a[i * 2] - a[j * 2], dy = a[i * 2 + 1] - a[j * 2 + 1];
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    static void oneP0inTranslates() {
        PuppetTopology t = puppet(new float[]{0.5f, 0.5f}, 4);
        float[] pose = {0.2f, 0.1f};
        MeshBuffers b = solved(t, pose);
        check("a ONE-pin puppet solves (MLS is undefined there; it must not NaN)", b != null);
        if (b == null) return;
        float worst = 0f;
        for (int v = 0; v < t.vertexCount(); v++) {
            worst = Math.max(worst, Math.abs(b.positions[v * 2] - (b.rest[v * 2] + 0.2f)));
            worst = Math.max(worst, Math.abs(b.positions[v * 2 + 1] - (b.rest[v * 2 + 1] + 0.1f)));
        }
        check("...and one pin is pure translation (err " + worst + ")", worst < 1e-5f);
    }

    /** Moving one pin must affect its own neighbourhood more than the far side. */
    static void influenceIsLocal() {
        float[] pins = fourPins();
        PuppetTopology t = puppet(pins, 5);
        float[] pose = new float[t.handleArity()];
        pose[0] = 0.12f;                          // move ONLY pin 0 (top, at 0.5,0.25)
        MeshBuffers b = solved(t, pose);
        check("single-pin drag solves", b != null);
        if (b == null) return;
        float near = 0f, far = 0f;
        for (int v = 0; v < t.vertexCount(); v++) {
            float x = b.rest[v * 2], y = b.rest[v * 2 + 1];
            float d = (float) Math.sqrt(Math.pow(b.positions[v * 2] - x, 2)
                    + Math.pow(b.positions[v * 2 + 1] - y, 2));
            if (y < 0.33f) near = Math.max(near, d);        // top, beside the dragged pin
            if (y > 0.67f) far = Math.max(far, d);          // bottom, beside a stationary pin
        }
        check("the dragged pin's neighbourhood moves more than the far side ("
                + String.format("%.4f", near) + " vs " + String.format("%.4f", far) + ")",
                near > far * 1.5f);
    }

    static void noPinsIsIdentity() {
        PuppetTopology t = puppet(new float[0], 3);
        check("a pinless puppet is legal", t.handleCount() == 0);
        MeshBuffers b = solved(t, new float[0]);
        check("...and solves to the identity", b != null);
        if (b == null) return;
        float worst = 0f;
        for (int i = 0; i < t.vertexCount() * 2; i++) {
            worst = Math.max(worst, Math.abs(b.positions[i] - b.rest[i]));
        }
        check("...moving nothing (err " + worst + ")", worst < 1e-6f);
    }

    /** NEGATIVE CONTROL: the cases with no mathematical answer must still produce numbers. */
    static void degenerateCasesNeverNaN() {
        // All pins stacked on the same point: rotation is completely unconstrained.
        PuppetTopology stacked = puppet(new float[]{0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f}, 4);
        float[] pose = new float[stacked.handleArity()];
        for (int i = 0; i < pose.length; i += 2) { pose[i] = 0.05f; pose[i + 1] = 0.05f; }
        MeshBuffers b1 = solved(stacked, pose);
        check("coincident pins still solve", b1 != null);
        if (b1 != null) check("...with no NaN anywhere", allFinite(b1.positions));

        // Collinear pins: a whole family of rotations fits equally well.
        PuppetTopology line = puppet(new float[]{0.3f, 0.5f, 0.5f, 0.5f, 0.7f, 0.5f}, 4);
        float[] p2 = new float[line.handleArity()];
        p2[2] = 0.1f;
        MeshBuffers b2 = solved(line, p2);
        check("collinear pins still solve", b2 != null);
        if (b2 != null) check("...with no NaN anywhere", allFinite(b2.positions));

        // A pin dragged an absurd distance.
        PuppetTopology t = puppet(fourPins(), 4);
        float[] p3 = new float[t.handleArity()];
        p3[0] = 50f;
        MeshBuffers b3 = solved(t, p3);
        check("an absurd drag still solves", b3 != null);
        if (b3 != null) check("...with no NaN anywhere", allFinite(b3.positions));

        check("a wrong-length pose is REFUSED, not guessed at",
                !new PuppetDeformer().solve(t, new MeshBuffers(), new float[3]));
    }

    static boolean allFinite(float[] a) {
        for (float v : a) if (Float.isNaN(v) || Float.isInfinite(v)) return false;
        return true;
    }

    /** The architectural payoff, asserted: a puppet pose needs no new pose-track code. */
    static void poseTrackNeedsNoNewCode() {
        PuppetTopology t = puppet(fourPins(), 3);
        MeshPoseTrack track = new MeshPoseTrack(t.handleArity());
        float[] a = new float[t.handleArity()];
        float[] c = new float[t.handleArity()];
        a[0] = 0.1f;
        c[0] = 0.3f;
        check("a pose track accepts a PUPPET pose", track.put(0L, a, MeshPoseTrack.DEFAULT_EASING));
        check("...and a second", track.put(1000L, c, MeshPoseTrack.DEFAULT_EASING));
        float[] mid = new float[t.handleArity()];
        check("...and interpolates between them", track.valueAt(500L, mid));
        check("...to the value in between (" + mid[0] + ")", mid[0] > 0.1f && mid[0] < 0.3f);
    }

    /** THE COST STUDY the spec asked for. Measured, not claimed. */
    static void costStudy() {
        System.out.println();
        System.out.println("  ── MLS cost study (the spec's 'single biggest unknown') ──");
        int[] interiors = {4, 6, 8, 10};
        int[] pinCounts = {4, 6, 8};
        boolean allUnderBudget = true;
        for (int interior : interiors) {
            for (int pc : pinCounts) {
                float[] pins = new float[pc * 2];
                for (int i = 0; i < pc; i++) {
                    double a = 2 * Math.PI * i / pc;
                    pins[i * 2] = (float) (0.5 + 0.22 * Math.cos(a));
                    pins[i * 2 + 1] = (float) (0.5 + 0.22 * Math.sin(a));
                }
                PuppetTopology t = puppet(pins, interior);
                MeshBuffers b = new MeshBuffers();
                b.bind(t);
                PuppetDeformer d = new PuppetDeformer();
                float[] pose = new float[t.handleArity()];
                pose[0] = 0.08f;
                for (int i = 0; i < 200; i++) d.solve(t, b, pose);   // warm the JIT
                long t0 = System.nanoTime();
                int iters = 2000;
                for (int i = 0; i < iters; i++) d.solve(t, b, pose);
                double ms = (System.nanoTime() - t0) / 1e6 / iters;
                // A desktop JVM is roughly an order of magnitude faster than a Note 9's ART, so
                // budget against 1ms here to leave headroom inside a 16.6ms frame on the phone.
                boolean ok = ms < 1.0;
                allUnderBudget &= ok;
                System.out.printf("    interior=%-3d pins=%-2d  verts=%-4d tris=%-4d  %.4f ms/solve%s%n",
                        interior, pc, t.vertexCount(), t.indexCount() / 3, ms, ok ? "" : "   <-- OVER");
            }
        }
        System.out.println();
        check("every realistic puppet solves in well under a frame on the desktop JVM",
                allUnderBudget);
    }

    // ── plumbing ────────────────────────────────────────────────────────────────────────────

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }
}

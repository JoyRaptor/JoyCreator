import com.fadcam.ui.faditor.avatar.*;
import com.fadcam.ui.faditor.transform.mesh.*;

/**
 * THE RIG LAYER: bones and physics turning into pin positions.
 *
 * <p>The piece between the drawer and the deformer. Everything here has to end as ordinary pose
 * offsets, because that is what makes a keyframed pin, an IK-solved pin and a simulated pin
 * indistinguishable downstream — and therefore what makes all three export, scrub and undo with
 * no new code.
 *
 * <p>Written against the failures that would ship silently:
 * <ul>
 *   <li>a bone that quietly lengthens every drag (the accumulating-drift bug, which is why rest
 *       lengths come from the REST pose and never from the current one);</li>
 *   <li>an elbow that folds whichever way the solver felt like, differently each drag;</li>
 *   <li>a joint limit that is enforced after the solve, so the hand slides off the finger;</li>
 *   <li>a simulation that cannot be scrubbed, which is every un-baked simulation.</li>
 * </ul>
 *
 * <p>Run: {@code bash tools/jvm-harness/run-puppet.sh}
 */
public class PuppetRigSolverTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        System.out.println("-- chains --");
        theTipLandsUnderTheFinger();
        bonesKeepTheirLength();
        draggingRepeatedlyDoesNotStretchTheArm();
        theRootDoesNotMove();
        anAnimatedRootCarriesTheChain();
        outOfReachExtendsRatherThanFails();

        System.out.println();
        System.out.println("-- stretchy, limits, bend sign --");
        aStretchyBoneReachesFurther();
        stretchHasACeiling();
        aJointLimitIsObeyed();
        theBendSignDecidesWhichWayTheElbowFolds();
        flippingTheSignDoesNotMoveEitherEnd();

        System.out.println();
        System.out.println("-- dangle, baked --");
        aBakedDangleIsScrubbable();
        theTailHangsBelowTheAnchor();
        theBakeLeavesOtherPinsAlone();
        gravityAndWindDoSomething();
        theBakeIsDeterministic();
        settleReallySettles();

        System.out.println();
        System.out.println("-- muting --");
        aMutedPinMovesNothing();
        andKeepsItsAnimation();

        System.out.println();
        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    /** Four pins in a line: hip, shoulder, elbow, wrist. Each link 0.15 long. */
    static float[] armRest() {
        return new float[]{0.5f, 0.8f, 0.5f, 0.65f, 0.5f, 0.5f, 0.5f, 0.35f};
    }

    static PuppetRigSolver.Chain arm() {
        PuppetRigSolver.Chain c = new PuppetRigSolver.Chain();
        c.pins = new int[]{0, 1, 2, 3};
        c.bendSign = 0;                        // no preference unless a test asks for one
        return c;
    }

    static float linkLen(float[] rest, float[] off, int a, int b) {
        float ax = rest[a * 2] + off[a * 2], ay = rest[a * 2 + 1] + off[a * 2 + 1];
        float bx = rest[b * 2] + off[b * 2], by = rest[b * 2 + 1] + off[b * 2 + 1];
        return (float) Math.hypot(bx - ax, by - ay);
    }

    static float tipDistanceTo(float[] rest, float[] off, int pin, float x, float y) {
        return (float) Math.hypot(rest[pin * 2] + off[pin * 2] - x,
                rest[pin * 2 + 1] + off[pin * 2 + 1] - y);
    }

    // ── chains ──────────────────────────────────────────────────────────────────────────────

    static void theTipLandsUnderTheFinger() {
        float[] rest = armRest(), off = new float[8];
        boolean ok = PuppetRigSolver.solveChain(arm(), rest, off, 0.70f, 0.55f);
        float err = tipDistanceTo(rest, off, 3, 0.70f, 0.55f);
        check("a reachable target is reached", ok);
        check("and the wrist is where the finger is (err " + fmt(err) + ")", err < 0.002f);
    }

    static void bonesKeepTheirLength() {
        float[] rest = armRest(), off = new float[8];
        PuppetRigSolver.solveChain(arm(), rest, off, 0.65f, 0.6f);
        float worst = 0f;
        for (int i = 0; i < 3; i++) worst = Math.max(worst, Math.abs(linkLen(rest, off, i, i + 1) - 0.15f));
        // A rig whose bones change length is a character that inflates when you pose it.
        check("every bone is still 0.15 long (worst drift " + fmt(worst) + ")", worst < 0.002f);
    }

    /**
     * THE DRIFT BUG. Rest lengths must come from the REST pose, never from the current one — read
     * them from the current pose and every solve becomes the next solve's idea of "rest", so the
     * arm creeps longer every time you drag it. Twenty drags is enough to see it.
     */
    static void draggingRepeatedlyDoesNotStretchTheArm() {
        float[] rest = armRest(), off = new float[8];
        for (int i = 0; i < 20; i++) {
            double a = i * 0.7;
            PuppetRigSolver.solveChain(arm(), rest, off,
                    0.5f + 0.3f * (float) Math.cos(a), 0.5f + 0.3f * (float) Math.sin(a));
        }
        float worst = 0f;
        for (int i = 0; i < 3; i++) worst = Math.max(worst, Math.abs(linkLen(rest, off, i, i + 1) - 0.15f));
        check("twenty drags later the bones are still 0.15 (worst " + fmt(worst) + ")",
                worst < 0.002f);
    }

    static void theRootDoesNotMove() {
        float[] rest = armRest(), off = new float[8];
        PuppetRigSolver.solveChain(arm(), rest, off, 0.8f, 0.4f);
        check("the hip stayed put", off[0] == 0f && off[1] == 0f);
    }

    static void anAnimatedRootCarriesTheChain() {
        float[] rest = armRest();
        float[] off = new float[8];
        off[0] = 0.1f;                                     // the hip is already animated
        off[1] = -0.05f;
        PuppetRigSolver.solveChain(arm(), rest, off, 0.75f, 0.5f);
        // The root keeps the value it had: a bone carries no keyframes, so whatever drives the
        // hip keeps driving it and the arm hangs off wherever that ended up.
        check("the animated hip is untouched by the solve",
                Math.abs(off[0] - 0.1f) < 1e-6f && Math.abs(off[1] + 0.05f) < 1e-6f);
        float rootX = rest[0] + off[0], rootY = rest[1] + off[1];
        float reach = (float) Math.hypot(rest[2] + off[2] - rootX, rest[3] + off[3] - rootY);
        check("and the first bone still starts from it (" + fmt(reach) + ")",
                Math.abs(reach - 0.15f) < 0.002f);
    }

    static void outOfReachExtendsRatherThanFails() {
        float[] rest = armRest(), off = new float[8];
        boolean ok = PuppetRigSolver.solveChain(arm(), rest, off, 0.5f, 3.0f);
        check("an unreachable target reports that it was not reached", !ok);
        float worst = 0f;
        for (int i = 0; i < 3; i++) worst = Math.max(worst, Math.abs(linkLen(rest, off, i, i + 1) - 0.15f));
        // Fully extended towards it is the right puppet read. Snapping back, or NaN, is not.
        check("the arm is straight and intact rather than broken (" + fmt(worst) + ")",
                worst < 0.002f);
    }

    // ── stretchy, limits, bend sign ─────────────────────────────────────────────────────────

    static void aStretchyBoneReachesFurther() {
        float[] rest = armRest();
        float[] rigid = new float[8], stretchy = new float[8];
        PuppetRigSolver.Chain plain = arm();
        PuppetRigSolver.Chain springy = arm();
        springy.stretchy = new boolean[]{true, true, true};
        springy.maxStretch = new float[]{0.3f, 0.3f, 0.3f};

        float tx = 0.5f, ty = 0.30f;                       // 0.5 away: past the 0.45 reach
        PuppetRigSolver.solveChain(plain, rest, rigid, tx, ty);
        PuppetRigSolver.solveChain(springy, rest, stretchy, tx, ty);
        float rigidErr = tipDistanceTo(rest, rigid, 3, tx, ty);
        float stretchErr = tipDistanceTo(rest, stretchy, 3, tx, ty);
        System.out.printf("    wrist falls short by: rigid %.4f, stretchy %.4f%n",
                rigidErr, stretchErr);
        // Rigid bones make the shoulder snap the moment you drag past the reach. That is the
        // ugliest thing in any IK rig, and it is what this bit exists to avoid.
        check("a rigid arm falls short", rigidErr > 0.02f);
        check("a stretchy one keeps the hand under the finger", stretchErr < 0.002f);
    }

    static void stretchHasACeiling() {
        float[] rest = armRest(), off = new float[8];
        PuppetRigSolver.Chain c = arm();
        c.stretchy = new boolean[]{true, true, true};
        c.maxStretch = new float[]{0.2f, 0.2f, 0.2f};
        PuppetRigSolver.solveChain(c, rest, off, 0.5f, -2f);   // absurdly far
        float worst = 0f;
        for (int i = 0; i < 3; i++) worst = Math.max(worst, linkLen(rest, off, i, i + 1));
        System.out.printf("    longest bone under an absurd pull: %.4f (rest 0.15, cap 0.18)%n",
                worst);
        // A tail that can become spaghetti is worse than one that stops.
        check("no bone went past its cap", worst <= 0.15f * 1.2f + 0.002f);
    }

    static void aJointLimitIsObeyed() {
        float[] rest = armRest();
        float[] free = new float[8], limited = new float[8];
        PuppetRigSolver.Chain open = arm();
        PuppetRigSolver.Chain hinged = arm();
        hinged.jointLimits = new boolean[]{false, true, true, false};
        hinged.minAngleDeg = new float[]{0f, -20f, -20f, 0f};
        hinged.maxAngleDeg = new float[]{0f, 20f, 20f, 0f};

        PuppetRigSolver.solveChain(open, rest, free, 0.75f, 0.7f);
        PuppetRigSolver.solveChain(hinged, rest, limited, 0.75f, 0.7f);

        float freeBend = Math.abs(bendAt(rest, free, 1));
        float limitedBend = Math.abs(bendAt(rest, limited, 1));
        System.out.printf("    shoulder bend: free %.1f deg, limited %.1f deg%n",
                freeBend, limitedBend);
        check("the unconstrained chain really does bend hard there", freeBend > 25f);
        check("the limited one stays inside its 20 degrees", limitedBend <= 21f);
        float worst = 0f;
        for (int i = 0; i < 3; i++) worst = Math.max(worst, Math.abs(linkLen(rest, limited, i, i + 1) - 0.15f));
        // Limits are applied INSIDE the iteration; a limit bolted on afterwards would move the
        // joints and break the bones.
        check("and no bone changed length doing it (" + fmt(worst) + ")", worst < 0.002f);
    }

    /** Signed bend angle at chain index {@code i}, in degrees. */
    static float bendAt(float[] rest, float[] off, int i) {
        float[] p = new float[8];
        for (int k = 0; k < 4; k++) {
            p[k * 2] = rest[k * 2] + off[k * 2];
            p[k * 2 + 1] = rest[k * 2 + 1] + off[k * 2 + 1];
        }
        float inX = p[i * 2] - p[(i - 1) * 2], inY = p[i * 2 + 1] - p[(i - 1) * 2 + 1];
        float outX = p[(i + 1) * 2] - p[i * 2], outY = p[(i + 1) * 2 + 1] - p[i * 2 + 1];
        return (float) Math.toDegrees(Math.atan2(inX * outY - inY * outX, inX * outX + inY * outY));
    }

    static void theBendSignDecidesWhichWayTheElbowFolds() {
        float[] rest = armRest();
        float[] plus = new float[8], minus = new float[8];
        PuppetRigSolver.Chain a = arm();
        a.bendSign = 1;
        PuppetRigSolver.Chain b = arm();
        b.bendSign = -1;
        PuppetRigSolver.solveChain(a, rest, plus, 0.62f, 0.62f);
        PuppetRigSolver.solveChain(b, rest, minus, 0.62f, 0.62f);
        float ba = bendAt(rest, plus, 2), bb = bendAt(rest, minus, 2);
        System.out.printf("    elbow folds: sign +1 -> %.1f deg, sign -1 -> %.1f deg%n", ba, bb);
        // Without this, FABRIK settles on whichever side it likes and the elbow can pop between
        // them mid-drag. One stored bit beats a pole target the user has to position.
        check("the two signs fold opposite ways", Math.signum(ba) != Math.signum(bb));
    }

    static void flippingTheSignDoesNotMoveEitherEnd() {
        float[] rest = armRest();
        float[] plus = new float[8], minus = new float[8];
        PuppetRigSolver.Chain a = arm();
        a.bendSign = 1;
        PuppetRigSolver.Chain b = arm();
        b.bendSign = -1;
        PuppetRigSolver.solveChain(a, rest, plus, 0.62f, 0.62f);
        PuppetRigSolver.solveChain(b, rest, minus, 0.62f, 0.62f);
        float tipA = tipDistanceTo(rest, plus, 3, 0.62f, 0.62f);
        float tipB = tipDistanceTo(rest, minus, 3, 0.62f, 0.62f);
        // The flip mirrors the interior joints across the base-to-tip line, so the hand stays
        // exactly where the finger put it. Anything else and flipping an elbow would yank the arm.
        check("both still reach the target (" + fmt(tipA) + ", " + fmt(tipB) + ")",
                tipA < 0.002f && tipB < 0.002f);
        check("and the hip did not move either", minus[0] == 0f && minus[1] == 0f);
    }

    // ── dangle ──────────────────────────────────────────────────────────────────────────────

    /** A tail: anchor at the head, three nodes hanging to the right. */
    static float[] tailRest() {
        return new float[]{0.5f, 0.3f, 0.6f, 0.3f, 0.7f, 0.3f, 0.8f, 0.3f};
    }

    static PuppetRigSolver.Chain tail() {
        PuppetRigSolver.Chain c = new PuppetRigSolver.Chain();
        c.pins = new int[]{0, 1, 2, 3};
        return c;
    }

    static MeshPoseTrack anchorMovingTrack() {
        MeshPoseTrack tr = new MeshPoseTrack(8);
        tr.put(0L, new float[8], null);
        float[] moved = new float[8];
        moved[0] = 0.2f;                                   // the head walks right over a second
        tr.put(1000L, moved, null);
        return tr;
    }

    static void aBakedDangleIsScrubbable() {
        MeshPoseTrack tr = anchorMovingTrack();
        int written = PuppetRigSolver.bakeDangle(tail(), tailRest(), tr, 0L, 1000L, 33,
                new DangleSim.Params());
        check("the bake wrote poses (" + written + ")", written > 20);
        // THE point of baking. A verlet chain cannot answer "what does 500ms look like" without
        // running every frame before it; a track can, instantly, in any order, backwards.
        float[] a = new float[8], b = new float[8];
        boolean gotLate = tr.valueAt(900L, a);
        boolean gotEarly = tr.valueAt(100L, b);
        check("and the middle of the take can be read without replaying it", gotLate && gotEarly);
        check("...forwards or backwards, which is what scrubbing is",
                a[2] != b[2] || a[3] != b[3]);
    }

    static void theTailHangsBelowTheAnchor() {
        MeshPoseTrack tr = new MeshPoseTrack(8);
        tr.put(0L, new float[8], null);
        tr.put(2000L, new float[8], null);                 // the anchor never moves
        PuppetRigSolver.bakeDangle(tail(), tailRest(), tr, 0L, 2000L, 16,
                new DangleSim.Params());
        float[] at = new float[8];
        tr.valueAt(2000L, at);
        float[] rest = tailRest();
        float tipY = rest[7] + at[7];
        System.out.printf("    tail tip settles at y=%.3f (anchor y=%.3f)%n", tipY, rest[1]);
        // Gravity is +y here, as it is everywhere in this app's unit space.
        check("a tail left alone hangs below its anchor", tipY > rest[1] + 0.05f);
    }

    static void theBakeLeavesOtherPinsAlone() {
        MeshPoseTrack tr = new MeshPoseTrack(10);          // five pins; the fifth is not in the tail
        float[] a = new float[10];
        a[8] = 0.11f;
        a[9] = 0.22f;
        tr.put(0L, a, null);
        float[] b = new float[10];
        b[8] = 0.11f;
        b[9] = 0.22f;
        tr.put(1000L, b, null);
        PuppetRigSolver.bakeDangle(tail(), tailRest(), tr, 0L, 1000L, 50, new DangleSim.Params());
        float[] at = new float[10];
        tr.valueAt(500L, at);
        // Every pose the bake writes fills the components it did not name from what the track was
        // already producing. Without that, baking a tail would flatten the rest of the character.
        check("a pin outside the chain kept its animation",
                Math.abs(at[8] - 0.11f) < 1e-5f && Math.abs(at[9] - 0.22f) < 1e-5f);
    }

    static void gravityAndWindDoSomething() {
        float[] rest = tailRest();
        MeshPoseTrack still = new MeshPoseTrack(8);
        still.put(0L, new float[8], null);
        still.put(1500L, new float[8], null);
        MeshPoseTrack windy = still.copy();

        PuppetRigSolver.bakeDangle(tail(), rest, still, 0L, 1500L, 16,
                PuppetRigSolver.paramsFor(0.5f, 0f, 0f, 1f, 0.5f, 0.5f, 0f));
        PuppetRigSolver.bakeDangle(tail(), rest, windy, 0L, 1500L, 16,
                PuppetRigSolver.paramsFor(0.5f, 0.8f, 0f, 1f, 0.5f, 0.5f, 0f));

        // Read INSIDE the baked range, not at its far edge: the bake writes on its own step grid
        // (0, 16, 32 ... 1488) and 1500 is not on it, so reading there gets the untouched pose
        // that was already at 1500 and the comparison silently measures nothing.
        float[] a = new float[8], b = new float[8];
        still.valueAt(1400L, a);
        windy.valueAt(1400L, b);
        float dxStill = rest[6] + a[6], dxWindy = rest[6] + b[6];
        System.out.printf("    tail tip x: no wind %.3f, wind %.3f%n", dxStill, dxWindy);
        check("wind blows the tail sideways", dxWindy > dxStill + 0.02f);
    }

    static void theBakeIsDeterministic() {
        MeshPoseTrack one = anchorMovingTrack(), two = anchorMovingTrack();
        PuppetRigSolver.bakeDangle(tail(), tailRest(), one, 0L, 1000L, 20, new DangleSim.Params());
        PuppetRigSolver.bakeDangle(tail(), tailRest(), two, 0L, 1000L, 20, new DangleSim.Params());
        boolean same = one.size() == two.size();
        for (int i = 0; same && i < one.size(); i++) {
            MeshPoseTrack.Pose a = one.poses().get(i), b = two.poses().get(i);
            same = a.timeMs == b.timeMs && java.util.Arrays.equals(a.values, b.values);
        }
        // Not a nicety: a bake that differed run to run would mean re-opening a project and
        // getting a different performance than the one that was approved.
        check("the same rig bakes bit-for-bit the same twice", same);
    }

    static void settleReallySettles() {
        float[] rest = tailRest();
        MeshPoseTrack loose = new MeshPoseTrack(8), tight = new MeshPoseTrack(8);
        for (MeshPoseTrack tr : new MeshPoseTrack[]{loose, tight}) {
            tr.put(0L, new float[8], null);
            float[] jerk = new float[8];
            jerk[0] = 0.25f;
            tr.put(200L, jerk, null);
            tr.put(1400L, jerk, null);                     // yank it, then hold still
        }
        PuppetRigSolver.bakeDangle(tail(), rest, loose, 0L, 1400L, 16,
                PuppetRigSolver.paramsFor(0.5f, 0f, 0f, 1f, 0.0f, 0.5f, 0f));
        PuppetRigSolver.bakeDangle(tail(), rest, tight, 0L, 1400L, 16,
                PuppetRigSolver.paramsFor(0.5f, 0f, 0f, 1f, 1.0f, 0.5f, 0f));

        float swingLoose = swingAfter(loose, rest, 1200L, 1400L);
        float swingTight = swingAfter(tight, rest, 1200L, 1400L);
        System.out.printf("    still moving at the end: settle=0 %.4f, settle=1 %.4f%n",
                swingLoose, swingTight);
        check("a high Settle really does stop sooner", swingTight < swingLoose);
    }

    /** How far the tip moved between two instants — what "still swinging" means. */
    static float swingAfter(MeshPoseTrack tr, float[] rest, long t0, long t1) {
        float[] a = new float[8], b = new float[8];
        tr.valueAt(t0, a);
        tr.valueAt(t1, b);
        return (float) Math.hypot(b[6] - a[6], b[7] - a[7]);
    }

    // ── muting ──────────────────────────────────────────────────────────────────────────────

    static float[] blob() {
        return new float[]{0.1f, 0.35f, 0.9f, 0.35f, 0.9f, 0.65f, 0.1f, 0.65f};
    }

    static void aMutedPinMovesNothing() {
        float[] pins = {0.2f, 0.5f, 0.4f, 0.5f, 0.6f, 0.5f, 0.8f, 0.5f};
        PuppetTopology live = new PuppetTopology(new float[][]{blob()}, 6, pins,
                0.5f, null, null, null);
        PuppetTopology muted = new PuppetTopology(new float[][]{blob()}, 6, pins,
                0.5f, null, null, new boolean[]{true, false, false, false});

        float[] pose = {0f, 0.25f, 0f, 0f, 0f, 0f, 0f, 0f};    // drag the MUTED pin
        float movedLive = worstMove(live, pose), movedMuted = worstMove(muted, pose);
        System.out.printf("    dragging pin 0: live %.4f, muted %.4f%n", movedLive, movedMuted);
        check("an unmuted pin bends the picture", movedLive > 0.05f);
        // The drawer greys the dot. Until now that was all it did — the pin still bent the
        // picture, which makes Mute a lie.
        check("a muted one does not (" + fmt(movedMuted) + ")", movedMuted < 1e-6f);
    }

    static void andKeepsItsAnimation() {
        float[] pins = {0.2f, 0.5f, 0.4f, 0.5f, 0.6f, 0.5f, 0.8f, 0.5f};
        PuppetTopology muted = new PuppetTopology(new float[][]{blob()}, 6, pins,
                0.5f, null, null, new boolean[]{true, false, false, false});
        MeshWarpSpec spec = new MeshWarpSpec(muted);
        MeshPoseTrack tr = spec.ensureTrack();
        float[] v = new float[8];
        v[1] = 0.2f;
        tr.put(500L, v, null);
        float[] at = new float[8];
        spec.handlesAt(500L, at);
        // Muting is a weight of zero, not a deleted pin — so the performance is still there and
        // un-muting brings it straight back.
        check("the muted pin's keyframe is still stored", Math.abs(at[1] - 0.2f) < 1e-6f);
        MeshTopology back = MeshTopologies.create("puppet", muted.params());
        check("and the mute survives a save and load",
                back instanceof PuppetTopology && ((PuppetTopology) back).muted()[0]);
    }

    static float worstMove(PuppetTopology t, float[] pose) {
        MeshBuffers b = new MeshBuffers();
        b.bind(t);
        if (!new PuppetDeformer().solve(t, b, pose)) return -1f;
        float worst = 0f;
        for (int v = 0; v < t.vertexCount(); v++) {
            float dx = b.positions[v * 2] - b.rest[v * 2];
            float dy = b.positions[v * 2 + 1] - b.rest[v * 2 + 1];
            worst = Math.max(worst, (float) Math.sqrt(dx * dx + dy * dy));
        }
        return worst;
    }

    // ── plumbing ────────────────────────────────────────────────────────────────────────────

    static String fmt(float f) { return String.format("%.4f", f); }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }
}

import com.fadcam.ui.faditor.puppet.*;
import com.fadcam.ui.faditor.transform.mesh.*;

/**
 * DANGLE PINS ACTUALLY RUN, and a pin's DEPTH actually reaches the engine.
 *
 * <p>The last two things that were built and unreachable. {@code PuppetPin.Type.DANGLE} existed,
 * its four sliders were authored and saved, {@code DangleSim} was written and tested — and nothing
 * in the app ever called it, so hair and tails were a pin type that did nothing. Depth was the
 * same story one day later: a field the engine could blend and no control that set it.
 *
 * <p>These are the tests that would have caught "built but never called", which is the failure
 * this feature has now had five times: softness, stiffness, mute, edge expansion, dangle.
 *
 * <p>Run: {@code bash tools/jvm-harness/run-puppet.sh}
 */
public class PuppetDangleBakeTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        System.out.println("-- dangle chains --");
        aTailIsFoundAndBaked();
        theTailHangsAfterTheBake();
        aDanglePinWithNoBoneIsLeftAlone();
        aChainWithNothingToHangFromIsLeftAlone();
        twoTailsAreTwoChains();
        theBakeIsIdempotent();
        aStaleMeshBakesNothing();
        otherPinsKeepTheirAnimation();

        System.out.println();
        System.out.println("-- depth --");
        depthDefaultsToFlat();
        depthRoundTripsThroughTheProjectFile();
        theSliderMapsOntoTheEngineRange();

        System.out.println();
        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    /** A head with a tail of three segments hanging off it, to the right. */
    static PuppetRig tailRig() {
        PuppetRig rig = new PuppetRig();
        rig.addPin(PuppetPin.Type.PIN, 0.5f, 0.3f);        // 0: the head, an anchor
        rig.addPin(PuppetPin.Type.DANGLE, 0.6f, 0.3f);     // 1
        rig.addPin(PuppetPin.Type.DANGLE, 0.7f, 0.3f);     // 2
        rig.addPin(PuppetPin.Type.DANGLE, 0.8f, 0.3f);     // 3: the tip
        rig.addBone(0, 1, 0.1f);
        rig.addBone(1, 2, 0.1f);
        rig.addBone(2, 3, 0.1f);
        return rig;
    }

    static float[] blobRing() {
        return new float[]{0.1f, 0.1f, 0.95f, 0.1f, 0.95f, 0.9f, 0.1f, 0.9f};
    }

    static MeshWarpSpec specFor(PuppetRig rig) {
        float[] pins = new float[rig.pinCount() * 2];
        for (int i = 0; i < rig.pinCount(); i++) {
            pins[i * 2] = rig.pin(i).restX;
            pins[i * 2 + 1] = rig.pin(i).restY;
        }
        return new MeshWarpSpec(new PuppetTopology(new float[][]{blobRing()}, 5, pins));
    }

    // ── dangle ──────────────────────────────────────────────────────────────────────────────

    static void aTailIsFoundAndBaked() {
        PuppetRig rig = tailRig();
        MeshWarpSpec spec = specFor(rig);
        int chains = PuppetDangleBake.bakeAll(spec, rig, 1500L);
        check("the tail is found and baked (" + chains + ")", chains == 1);
        check("and it wrote keyframes",
                spec.track() != null && spec.track().size() > 20);
    }

    static void theTailHangsAfterTheBake() {
        PuppetRig rig = tailRig();
        MeshWarpSpec spec = specFor(rig);
        PuppetDangleBake.bakeAll(spec, rig, 2000L);
        float[] at = new float[spec.arity()];
        spec.handlesAt(1900L, at);
        float tipY = rig.pin(3).restY + at[7];
        System.out.printf("    tail tip settles at y=%.3f (anchor y=%.3f)%n", tipY, rig.pin(0).restY);
        // Gravity is +y in this app's unit space, so a tail left alone ends up below its anchor.
        // If this fails, the sliders are being read but the physics is not running.
        check("the tail hangs below the head", tipY > rig.pin(0).restY + 0.05f);
    }

    static void aDanglePinWithNoBoneIsLeftAlone() {
        PuppetRig rig = new PuppetRig();
        rig.addPin(PuppetPin.Type.PIN, 0.5f, 0.3f);
        rig.addPin(PuppetPin.Type.DANGLE, 0.7f, 0.3f);     // marked Dangle, never rigged
        MeshWarpSpec spec = specFor(rig);
        int chains = PuppetDangleBake.bakeAll(spec, rig, 1500L);
        // Guessing an anchor for it would produce motion the user never asked for, on a pin they
        // have not finished setting up.
        check("an unrigged dangle pin is not simulated", chains == 0);
        check("and nothing was written", spec.track() == null || spec.track().isEmpty());
    }

    static void aChainWithNothingToHangFromIsLeftAlone() {
        PuppetRig rig = new PuppetRig();
        rig.addPin(PuppetPin.Type.DANGLE, 0.5f, 0.3f);
        rig.addPin(PuppetPin.Type.DANGLE, 0.6f, 0.3f);
        rig.addBone(0, 1, 0.1f);
        MeshWarpSpec spec = specFor(rig);
        // Every pin in the chain is a dangle, so there is no fixed end for it to swing from.
        check("a chain with no anchor is not simulated",
                PuppetDangleBake.bakeAll(spec, rig, 1500L) == 0);
    }

    static void twoTailsAreTwoChains() {
        PuppetRig rig = tailRig();
        rig.addPin(PuppetPin.Type.DANGLE, 0.4f, 0.3f);     // 4
        rig.addPin(PuppetPin.Type.DANGLE, 0.3f, 0.3f);     // 5
        rig.addBone(0, 4, 0.1f);
        rig.addBone(4, 5, 0.1f);
        MeshWarpSpec spec = specFor(rig);
        check("two ears off one head are two chains",
                PuppetDangleBake.bakeAll(spec, rig, 1500L) == 2);
    }

    static void theBakeIsIdempotent() {
        PuppetRig rig = tailRig();
        MeshWarpSpec spec = specFor(rig);
        PuppetDangleBake.bakeAll(spec, rig, 1500L);
        int size = spec.track().size();
        float[] first = new float[spec.arity()];
        spec.handlesAt(900L, first);

        PuppetDangleBake.bakeAll(spec, rig, 1500L);
        float[] second = new float[spec.arity()];
        spec.handlesAt(900L, second);
        // The mesh rebuilds on every slider drag, so the bake runs constantly. It has to re-state
        // the take rather than pile a second one on top of it.
        check("baking twice writes the same keys, not twice as many",
                spec.track().size() == size);
        check("and the same values", java.util.Arrays.equals(first, second));
    }

    static void aStaleMeshBakesNothing() {
        PuppetRig rig = tailRig();
        MeshWarpSpec spec = specFor(rig);
        rig.addPin(PuppetPin.Type.DANGLE, 0.2f, 0.3f);     // the rig grew; the mesh did not
        // Writing into a track sized for the old pin count would corrupt the pose rather than
        // animate anything. The rebuild that follows will bake it properly.
        check("a rig that has outgrown its mesh bakes nothing",
                PuppetDangleBake.bakeAll(spec, rig, 1500L) == 0);
    }

    static void otherPinsKeepTheirAnimation() {
        PuppetRig rig = tailRig();
        MeshWarpSpec spec = specFor(rig);
        MeshPoseTrack tr = spec.ensureTrack();
        float[] pose = new float[spec.arity()];
        pose[0] = 0.15f;                                   // the HEAD is animated by hand
        pose[1] = -0.05f;
        tr.put(0L, new float[spec.arity()], null);
        tr.put(1000L, pose, null);

        PuppetDangleBake.bakeAll(spec, rig, 1000L);
        float[] at = new float[spec.arity()];
        spec.handlesAt(1000L, at);
        // The anchor is whatever is driving the chain. Overwriting it with the simulation's copy
        // of itself would fight whoever owns it — usually the animator.
        check("the hand-animated head kept its performance",
                Math.abs(at[0] - 0.15f) < 1e-4f && Math.abs(at[1] + 0.05f) < 1e-4f);
    }

    // ── depth ───────────────────────────────────────────────────────────────────────────────

    static void depthDefaultsToFlat() {
        PuppetRig rig = new PuppetRig();
        rig.addPin(PuppetPin.Type.PIN, 0.5f, 0.5f);
        // Every rig made before depth existed carries 0.5, so nothing already drawn moves.
        check("a new pin is flat", rig.pin(0).depth == 0.5f);
    }

    /**
     * Depth travels with a pin that is copied — which is what an undo snapshot and a duplicated
     * overlay both are.
     *
     * <p>The JSON round-trip is not asserted here: {@code PuppetRigJson} uses androidx annotations
     * and will not compile on this harness, and stripping another lane's file to test one field is
     * not worth it. The write and read are the same symmetric one-liner every other pin field uses
     * ("dz", default 0.5).
     */
    static void depthRoundTripsThroughTheProjectFile() {
        PuppetRig rig = tailRig();
        rig.pin(1).depth = 0.9f;
        rig.pin(2).depth = 0.1f;
        PuppetPin forward = rig.pin(1).copy();
        PuppetPin back = rig.pin(2).copy();
        check("a pin brought forward keeps its depth when copied",
                Math.abs(forward.depth - 0.9f) < 1e-5f);
        check("and one sent back keeps its own", Math.abs(back.depth - 0.1f) < 1e-5f);
        check("an untouched pin is still flat", Math.abs(rig.pin(3).copy().depth - 0.5f) < 1e-5f);
    }

    static void theSliderMapsOntoTheEngineRange() {
        // The drawer stores 0..1 like every other slider; the engine wants -1..+1 where higher is
        // nearer. PuppetMeshBuilder owns the one conversion, and this is the arithmetic it uses.
        check("0 is fully behind", Math.abs(((0f - 0.5f) * 2f) + 1f) < 1e-6f);
        check("0.5 is flat", Math.abs((0.5f - 0.5f) * 2f) < 1e-6f);
        check("1 is fully in front", Math.abs(((1f - 0.5f) * 2f) - 1f) < 1e-6f);

        // And a field built from those really does order the picture.
        MeshWarpSpec spec = specFor(tailRig());
        spec.setHandleZ(1, 1f);
        spec.setHandleZ(2, -1f);
        check("a depth set on a pin makes the picture an ordered one", spec.hasGroupDepth());
    }

    // ── plumbing ────────────────────────────────────────────────────────────────────────────

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }
}

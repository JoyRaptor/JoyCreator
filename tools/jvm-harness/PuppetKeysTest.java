import com.fadcam.ui.faditor.puppet.PuppetKeys;
import com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec;
import com.fadcam.ui.faditor.transform.mesh.PuppetTopology;

/**
 * SPEC_20260915_PUPPET_UI — a pin's KEYS, proved off device.
 *
 * <p>The headline case is a bug I nearly shipped and found only by auditing my own work: a pose
 * holds EVERY pin, so deleting "this pin's key" by dropping the pose took the whole character's
 * key with it. Silently, under one undo press that looked like it did one thing. That is the
 * class of defect this file exists for — no exception, no wrong pixel, just a head that stopped
 * moving three edits ago.
 */
public class PuppetKeysTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        System.out.println("-- keys: which pin owns which --");
        componentsAreTwoPerPin();
        keyTimesReportOnlyTheChangingPin();

        System.out.println();
        System.out.println("-- keys: the delete that used to take everyone with it --");
        deletingOnePinsKeyKeepsTheOthers();
        deletingInterpolatesTheGapItLeaves();
        deletingTheOnlyKeyOfAPinLeavesNothingBehind();

        System.out.println();
        System.out.println("-- keys: static vs animated --");
        noTrackMeansTheDragEditsTheStaticPose();
        aTrackMeansTheDragKeys();
        anchorInReadsWhatTheAnimationWasAlreadyDoing();

        System.out.println();
        System.out.println("-- keys: the blend out --");
        blendEasesBackOntoWhatItInterrupted();

        System.out.println();
        System.out.println(failed == 0 ? "ALL PASS (" + passed + " assertions)"
                : failed + " FAILED of " + (passed + failed));
        if (failed != 0) System.exit(1);
    }

    /** Three pins on a blob — hip, elbow, hand. */
    private static MeshWarpSpec rig3() {
        float[] ring = new float[64 * 2];
        for (int i = 0; i < 64; i++) {
            double t = (i / 64.0) * Math.PI * 2;
            ring[i * 2] = (float) (0.5 + 0.34 * Math.cos(t));
            ring[i * 2 + 1] = (float) (0.5 + 0.34 * Math.sin(t));
        }
        float[] pins = {0.50f, 0.62f, 0.66f, 0.46f, 0.78f, 0.36f};
        return new MeshWarpSpec(new PuppetTopology(ring, 4, pins));
    }

    private static void componentsAreTwoPerPin() {
        check("pin 0", "[0, 1]", java.util.Arrays.toString(PuppetKeys.componentsOf(0)));
        check("pin 2", "[4, 5]", java.util.Arrays.toString(PuppetKeys.componentsOf(2)));
    }

    /**
     * What a pin's tape actually reports, asserted against the engine's stated contract rather
     * than against what I assumed it was.
     *
     * <p>{@code componentTimes} says it plainly: a pose counts for these components when dropping
     * it would MOVE them, <b>and the ends always count — they are where a value starts and stops
     * being held.</b> So a pin that never moved still reports the first and last pose. I expected
     * zero, the suite said two, and the suite was right.
     *
     * <p>That is worth knowing above this file, not just inside it: the drawer's key counter will
     * read "2" for a pin nobody has animated, because two instants really are where its value
     * begins and ends. It is honest; it is just not what a first guess predicts.
     */
    private static void keyTimesReportOnlyTheChangingPin() {
        MeshWarpSpec s = rig3();
        PuppetKeys.writeOffsets(s, PuppetKeys.componentsOf(0), new float[]{0.1f, 0f}, 0L, true);
        PuppetKeys.writeOffsets(s, PuppetKeys.componentsOf(0), new float[]{0.9f, 0f}, 1000L, true);
        PuppetKeys.writeOffsets(s, PuppetKeys.componentsOf(2), new float[]{0.3f, 0f}, 2000L, true);

        int moved = PuppetKeys.keyCount(s, 0);
        int still = PuppetKeys.keyCount(s, 1);
        checkTrue("the pin that MOVED carries more than the ends (" + moved + ")", moved >= 3);
        check("the pin that never moved carries only the two ends", 2, still);
        checkTrue("so a moved pin always out-keys a still one", moved > still);
    }

    /** THE BUG. */
    private static void deletingOnePinsKeyKeepsTheOthers() {
        MeshWarpSpec s = rig3();
        int[] all = {0, 1, 2, 3, 4, 5};
        // Every pin keyed at the same instant, which is what a chain drag produces.
        PuppetKeys.writeOffsets(s, all,
                new float[]{0.1f, 0.1f, 0.2f, 0.2f, 0.3f, 0.3f}, 0L, true);
        // NOT the midpoint of the other two: componentTimes correctly refuses to call a value
        // that merely interpolates its neighbours a key, so linear test data would assert
        // nothing. Found by this test failing, which is the test doing its job.
        PuppetKeys.writeOffsets(s, all,
                new float[]{0.8f, 0.8f, 0.9f, 0.9f, 1.0f, 1.0f}, 1000L, true);
        PuppetKeys.writeOffsets(s, all,
                new float[]{0.9f, 0.9f, 1.0f, 1.0f, 1.1f, 1.1f}, 2000L, true);

        int before1 = PuppetKeys.keyCount(s, 1);
        int before2 = PuppetKeys.keyCount(s, 2);
        check("pin 1 starts with three", 3, before1);

        boolean gone = PuppetKeys.deleteKey(s, 0, 1000L);
        check("the delete reported success", true, gone);
        check("pin 0 lost its middle key", 2, PuppetKeys.keyCount(s, 0));
        check("pin 1 kept ALL of its keys", before1, PuppetKeys.keyCount(s, 1));
        check("pin 2 kept ALL of its keys", before2, PuppetKeys.keyCount(s, 2));
    }

    private static void deletingInterpolatesTheGapItLeaves() {
        MeshWarpSpec s = rig3();
        PuppetKeys.writeOffsets(s, PuppetKeys.componentsOf(0), new float[]{0f, 0f}, 0L, true);
        PuppetKeys.writeOffsets(s, PuppetKeys.componentsOf(0), new float[]{0.9f, 0f}, 1000L, true);
        PuppetKeys.writeOffsets(s, PuppetKeys.componentsOf(0), new float[]{1.0f, 0f}, 2000L, true);
        // Keep the pose alive for somebody else so the cleanup does not simply drop it.
        PuppetKeys.writeOffsets(s, PuppetKeys.componentsOf(2), new float[]{0.4f, 0f}, 1000L, true);

        PuppetKeys.deleteKey(s, 0, 1000L);
        float[] pose = new float[s.arity()];
        PuppetKeys.readPose(s, 1000L, pose);
        // Halfway between 0 and 1.0 — the value it would have had if nobody keyed it there.
        checkNear("pin 0 now reads the interpolation", 0.5f, pose[0], 0.02f);
        checkNear("and pin 2 is untouched", 0.4f, pose[4], 1e-4f);
    }

    private static void deletingTheOnlyKeyOfAPinLeavesNothingBehind() {
        MeshWarpSpec s = rig3();
        PuppetKeys.writeOffsets(s, PuppetKeys.componentsOf(1), new float[]{0.4f, 0.4f}, 500L, true);
        PuppetKeys.deleteKey(s, 1, 500L);
        check("no key left for that pin", 0, PuppetKeys.keyCount(s, 1));
    }

    private static void noTrackMeansTheDragEditsTheStaticPose() {
        MeshWarpSpec s = rig3();
        check("starts un-animated", false, PuppetKeys.isAnimated(s));
        PuppetKeys.writeOffsets(s, PuppetKeys.componentsOf(1), new float[]{0.25f, 0f}, 900L, false);
        check("still un-animated", false, PuppetKeys.isAnimated(s));
        checkNear("the static pose moved", 0.25f, s.handles()[2], 1e-5f);
    }

    private static void aTrackMeansTheDragKeys() {
        MeshWarpSpec s = rig3();
        PuppetKeys.writeOffsets(s, PuppetKeys.componentsOf(1), new float[]{0.1f, 0f}, 0L, true);
        check("now animated", true, PuppetKeys.isAnimated(s));
        // forceKey FALSE from here on — the rule is that an animated spec keys anyway.
        PuppetKeys.writeOffsets(s, PuppetKeys.componentsOf(1), new float[]{0.7f, 0f}, 1000L, false);
        check("the second drag keyed rather than editing statics", 2, PuppetKeys.keyCount(s, 1));
    }

    private static void anchorInReadsWhatTheAnimationWasAlreadyDoing() {
        MeshWarpSpec s = rig3();
        PuppetKeys.writeOffsets(s, PuppetKeys.componentsOf(0), new float[]{0f, 0f}, 0L, true);
        PuppetKeys.writeOffsets(s, PuppetKeys.componentsOf(0), new float[]{1f, 0f}, 1000L, true);
        float[] pose = new float[s.arity()];
        check("reads mid-animation", true, PuppetKeys.readPose(s, 500L, pose));
        checkNear("halfway is halfway, so a punch-in here has nothing to jump from",
                0.5f, pose[0], 0.06f);
    }

    private static void blendEasesBackOntoWhatItInterrupted() {
        MeshWarpSpec s = rig3();
        int[] comps = PuppetKeys.componentsOf(0);
        // The take leaves the pin at 1.0; the old motion wants it back near 0.
        PuppetKeys.writeOffsets(s, comps, new float[]{1f, 0f}, 1000L, true);
        float[] resume = new float[s.arity()];
        resume[0] = 0f;
        boolean wrote = PuppetKeys.blendOut(s, comps, 1000L, 200, resume);
        check("a blend was written", true, wrote);

        float[] mid = new float[s.arity()];
        float[] end = new float[s.arity()];
        PuppetKeys.readPose(s, 1100L, mid);
        PuppetKeys.readPose(s, 1200L, end);
        checkTrue("it is on its way back at the midpoint (" + mid[0] + ")",
                mid[0] < 0.95f && mid[0] > 0.05f);
        checkNear("and has arrived by the end", 0f, end[0], 0.05f);
    }

    private static void check(String what, Object expect, Object got) {
        report(what, String.valueOf(expect).equals(String.valueOf(got)), expect, got);
    }

    private static void checkNear(String what, float expect, float got, float tol) {
        report(what, Math.abs(expect - got) <= tol, expect, got);
    }

    private static void checkTrue(String what, boolean ok) { report(what, ok, true, ok); }

    private static void report(String what, boolean ok, Object e, Object g) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what + " — expected " + e + ", got " + g); }
    }
}

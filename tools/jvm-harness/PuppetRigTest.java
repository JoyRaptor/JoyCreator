import com.fadcam.ui.faditor.puppet.PuppetPin;
import com.fadcam.ui.faditor.puppet.PuppetRig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * SPEC_20260915_PUPPET_UI — the RIG: names, bones, chains.
 *
 * <p>The engine lane proved the maths. This proves the bookkeeping, which is where this feature
 * will actually break, because none of it produces a visible error when it goes wrong:
 *
 * <ul>
 *   <li><b>Index renumbering.</b> Bones address pins by index. Delete pin 2 and every bone that
 *       referred to pin 3 now silently points at a different pin. There is no exception, no log
 *       line, and no wrong pixel until someone drags an arm and a leg moves.</li>
 *   <li><b>The chain walk terminates.</b> {@code chainToRoot} is used by the drag, the
 *       simplification and the blend. A cycle would hang all three.</li>
 *   <li><b>Smart names are unique.</b> Two pins called "L.Hand" are indistinguishable to the
 *       assistant, which is the entire reason names exist.</li>
 *   <li><b>{@code chainComponents} matches what the pose track expects</b> — {@code 2i, 2i+1} per
 *       pin, in chain order. This is the handoff to {@code MeshPoseTrack.putComponents}, and if it
 *       is wrong the wrong pins get keyed.</li>
 * </ul>
 *
 * <p>{@code PuppetPin} and {@code PuppetRig} import nothing at all — no android, no androidx, no
 * FadCam class — which is what makes this runnable off device, and is enforced by run-puppet.sh.
 *
 * <p>Run: {@code bash tools/jvm-harness/run-puppet.sh}
 */
public class PuppetRigTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        System.out.println("-- rig: names --");
        namesAreSuggestedByRegion();
        namesNeverCollide();
        renamingMarksItMine();
        blankNameFallsBackToTheType();

        System.out.println();
        System.out.println("-- rig: bones and chains --");
        aLonePinIsItsOwnChain();
        aChainWalksToItsRoot();
        chainComponentsMatchTheTrackLayout();
        aPinHasAtMostOneParent();
        aCycleIsRefused();
        aLongCycleIsRefused();
        anAnchoredChainIsReported();

        System.out.println();
        System.out.println("-- rig: the silent corruption --");
        deletingAPinRenumbersTheBonesThatSurvive();
        deletingAPinDropsTheBonesThatTouchedIt();
        deletingTheRootOfAChainDoesNotStrandTheTip();

        System.out.println();
        System.out.println("-- rig: undo --");
        copyIsDeepEnoughToUndoAChainDrag();

        System.out.println();
        System.out.println(failed == 0
                ? "ALL PASS (" + passed + " assertions)"
                : failed + " FAILED of " + (passed + failed));
        if (failed != 0) System.exit(1);
    }

    // ── names ────────────────────────────────────────────────────────────

    private static void namesAreSuggestedByRegion() {
        List<String> none = new ArrayList<>();
        check("head is up top",     "Head",   PuppetPin.suggestName(0.50f, 0.10f, none));
        check("left hand is left",  "L.Hand", PuppetPin.suggestName(0.20f, 0.48f, none));
        check("right hand is right","R.Hand", PuppetPin.suggestName(0.80f, 0.48f, none));
        check("hip is centre-low",  "Hip",    PuppetPin.suggestName(0.50f, 0.65f, none));
        check("left foot is bottom","L.Foot", PuppetPin.suggestName(0.20f, 0.90f, none));
    }

    private static void namesNeverCollide() {
        PuppetRig r = new PuppetRig();
        r.addPin(PuppetPin.Type.FREE, 0.20f, 0.48f);
        r.addPin(PuppetPin.Type.FREE, 0.22f, 0.50f);
        r.addPin(PuppetPin.Type.FREE, 0.19f, 0.47f);
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < r.pinCount(); i++) seen.add(r.pin(i).name);
        check("three pins in one region get three names", 3, seen.size());
        check("the first keeps the plain name", "L.Hand", r.pin(0).name);
    }

    private static void renamingMarksItMine() {
        PuppetRig r = new PuppetRig();
        int i = r.addPin(PuppetPin.Type.FREE, 0.8f, 0.48f);
        check("auto names are not mine", false, r.pin(i).nameIsMine);
        r.renamePin(i, "  Wand tip  ");
        check("rename trims", "Wand tip", r.pin(i).name);
        check("rename claims it", true, r.pin(i).nameIsMine);
    }

    private static void blankNameFallsBackToTheType() {
        PuppetRig r = new PuppetRig();
        int i = r.addPin(PuppetPin.Type.DANGLE, 0.5f, 0.1f);
        r.renamePin(i, "   ");
        check("blank falls back", "Dangle", r.pin(i).name);
        check("and is no longer claimed", false, r.pin(i).nameIsMine);
    }

    // ── chains ───────────────────────────────────────────────────────────

    /** hip(0) -> shoulder(1) -> elbow(2) -> wrist(3), the arm from SPEC section 3. */
    private static PuppetRig arm() {
        PuppetRig r = new PuppetRig();
        r.addPin(PuppetPin.Type.PIN,  0.50f, 0.65f);   // 0 hip, the anchor
        r.addPin(PuppetPin.Type.FREE, 0.62f, 0.33f);   // 1 shoulder
        r.addPin(PuppetPin.Type.FREE, 0.74f, 0.42f);   // 2 elbow
        r.addPin(PuppetPin.Type.FREE, 0.84f, 0.50f);   // 3 wrist
        r.addBone(0, 1, 0.32f);
        r.addBone(1, 2, 0.15f);
        r.addBone(2, 3, 0.14f);
        return r;
    }

    private static void aLonePinIsItsOwnChain() {
        PuppetRig r = new PuppetRig();
        int i = r.addPin(PuppetPin.Type.DANGLE, 0.7f, 0.2f);
        check("a lone pin's chain is itself", new int[]{i}, r.chainToRoot(i));
    }

    private static void aChainWalksToItsRoot() {
        PuppetRig r = arm();
        check("wrist walks back to the hip", new int[]{3, 2, 1, 0}, r.chainToRoot(3));
        check("the elbow's chain is shorter", new int[]{2, 1, 0}, r.chainToRoot(2));
        check("the anchor is alone", new int[]{0}, r.chainToRoot(0));
    }

    private static void chainComponentsMatchTheTrackLayout() {
        PuppetRig r = arm();
        // What MeshPoseTrack.putComponents will be handed for a wrist drag.
        check("wrist chain components", new int[]{6, 7, 4, 5, 2, 3, 0, 1}, r.chainComponents(3));
        check("one pin is two components", 2, r.chainComponents(0).length);
    }

    private static void aPinHasAtMostOneParent() {
        PuppetRig r = arm();
        int before = r.boneCount();
        check("a second parent is refused", -1, r.addBone(0, 3, 0.4f));
        check("and nothing was added", before, r.boneCount());
    }

    private static void aCycleIsRefused() {
        PuppetRig r = new PuppetRig();
        r.addPin(PuppetPin.Type.PIN,  0.5f, 0.6f);
        r.addPin(PuppetPin.Type.FREE, 0.6f, 0.4f);
        r.addBone(0, 1, 0.2f);
        check("a bone back to itself is refused", -1, r.addBone(1, 1, 0.2f));
        check("a two-bone loop is refused", -1, r.addBone(1, 0, 0.2f));
    }

    private static void aLongCycleIsRefused() {
        PuppetRig r = arm();
        // 3 -> 0 would close hip->shoulder->elbow->wrist->hip.
        check("a four-bone loop is refused", -1, r.addBone(3, 0, 0.3f));
        // And the walk still terminates afterwards.
        check("the chain still walks", 4, r.chainToRoot(3).length);
    }

    private static void anAnchoredChainIsReported() {
        PuppetRig r = arm();
        check("the arm is anchored at the hip", true, r.chainIsAnchored(3));

        PuppetRig loose = new PuppetRig();
        loose.addPin(PuppetPin.Type.FREE, 0.4f, 0.4f);
        loose.addPin(PuppetPin.Type.FREE, 0.6f, 0.4f);
        loose.addBone(0, 1, 0.2f);
        check("a chain rooted on a Free pin is not anchored", false, loose.chainIsAnchored(1));
    }

    // ── the silent corruption ────────────────────────────────────────────

    private static void deletingAPinRenumbersTheBonesThatSurvive() {
        PuppetRig r = arm();
        // Add a loose pin BEFORE the arm's indices would be disturbed, then delete it.
        int loose = r.addPin(PuppetPin.Type.DANGLE, 0.30f, 0.15f);   // index 4, touches no bone
        check("loose pin landed last", 4, loose);
        r.removePin(0);      // delete the HIP — every bone index above it must shift down

        check("the arm kept two bones", 2, r.boneCount());
        // hip is gone, so the chain now roots at the old shoulder, which is index 0.
        check("wrist still reaches the new root", new int[]{2, 1, 0}, r.chainToRoot(2));
        for (int i = 0; i < r.boneCount(); i++) {
            PuppetRig.Bone b = r.bone(i);
            checkTrue("bone " + i + " root in range", b.rootPin >= 0 && b.rootPin < r.pinCount());
            checkTrue("bone " + i + " tip in range",  b.tipPin  >= 0 && b.tipPin  < r.pinCount());
        }
    }

    private static void deletingAPinDropsTheBonesThatTouchedIt() {
        PuppetRig r = arm();
        r.removePin(2);      // the elbow: both bones touching it must go
        check("two bones survive of three... no — one does", 1, r.boneCount());
        check("the surviving bone is hip->shoulder", 0, r.bone(0).rootPin);
        check("...to the shoulder", 1, r.bone(0).tipPin);
    }

    private static void deletingTheRootOfAChainDoesNotStrandTheTip() {
        PuppetRig r = arm();
        r.removePin(0);
        int[] chain = r.chainToRoot(r.pinCount() - 1);
        checkTrue("every index in the chain is a real pin", allInRange(chain, r.pinCount()));
    }

    // ── undo ─────────────────────────────────────────────────────────────

    private static void copyIsDeepEnoughToUndoAChainDrag() {
        PuppetRig r = arm();
        r.pin(3).scale = 0.5f;
        r.bone(0).stretchy = true;
        PuppetRig snapshot = r.copy();

        // A chain drag mutates several pins. An undo that restored references would restore nothing.
        r.pin(3).scale = 9f;
        r.pin(2).muted = true;
        r.bone(0).stretchy = false;
        r.renamePin(1, "changed");

        check("the snapshot kept the scale",  0.5f, snapshot.pin(3).scale);
        check("the snapshot kept the mute",   false, snapshot.pin(2).muted);
        check("the snapshot kept the bone",   true,  snapshot.bone(0).stretchy);
        check("the snapshot kept the name",   "R.Shoulder", snapshot.pin(1).name);
        check("and the lock travels",         r.locked, snapshot.locked);
    }

    // ── tiny assert kit ──────────────────────────────────────────────────

    private static boolean allInRange(int[] a, int n) {
        for (int v : a) if (v < 0 || v >= n) return false;
        return true;
    }

    private static void check(String what, Object expect, Object got) {
        boolean ok = (expect instanceof int[] && got instanceof int[])
                ? Arrays.equals((int[]) expect, (int[]) got)
                : String.valueOf(expect).equals(String.valueOf(got));
        report(what, ok, show(expect), show(got));
    }

    private static void checkTrue(String what, boolean ok) {
        report(what, ok, "true", String.valueOf(ok));
    }

    private static void report(String what, boolean ok, String expect, String got) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what + " — expected " + expect + ", got " + got); }
    }

    private static String show(Object o) {
        return o instanceof int[] ? Arrays.toString((int[]) o) : String.valueOf(o);
    }
}

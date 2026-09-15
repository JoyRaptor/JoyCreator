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
        System.out.println("-- the mesh has to FIT THE RENDERER --");
        everyDetailSettingFitsTheDrawBudget();

        System.out.println();
        System.out.println("-- rig: undo, and duplicating an overlay --");
        copyIsDeepEnoughToUndoAChainDrag();
        aDuplicateSharesNothingWithItsOriginal();
        deletingAPinInADuplicateLeavesTheOriginalWhole();

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

    /**
     * JoyRaptor, 2026-09-15, on the duplicate path: <i>"this needs to be figured out so it's not
     * something that will break on a user."</i>
     *
     * <p>Duplicating an overlay copies its rig. If that copy SHARED pin objects, renaming one
     * picture's hand would rename the other's, and neither would look wrong until a user noticed
     * weeks later. There is no exception to catch and no pixel to inspect — which is exactly the
     * class of bug that needs a test rather than a careful reading.
     */
    private static void aDuplicateSharesNothingWithItsOriginal() {
        PuppetRig original = arm();
        PuppetRig dupe = original.copy();

        dupe.renamePin(3, "Other hand");
        dupe.pin(3).scale = 0.25f;
        dupe.pin(0).type = PuppetPin.Type.FREE;
        dupe.bone(0).stretchy = true;
        dupe.bone(0).bendSign = -1;
        dupe.softness = 0.9f;
        dupe.locked = true;

        check("the original's name is untouched", "R.Hand", original.pin(3).name);
        check("the original's scale is untouched", 1.0f, original.pin(3).scale);
        check("the original's type is untouched", "PIN", original.pin(0).type.name());
        check("the original's bone is untouched", false, original.bone(0).stretchy);
        check("the original's bend sign is untouched", 1, original.bone(0).bendSign);
        check("the original's softness is untouched", 0.5f, original.softness);
        check("the original is not locked", false, original.locked);

        // And the other way, because a one-directional check would pass on a shallow copy that
        // happened to be written to in only one order.
        original.renamePin(1, "Original shoulder");
        check("the duplicate's name is untouched", "R.Shoulder", dupe.pin(1).name);
    }

    /** The specific corruption: index renumbering must not reach across a duplicate. */
    private static void deletingAPinInADuplicateLeavesTheOriginalWhole() {
        PuppetRig original = arm();
        PuppetRig dupe = original.copy();

        dupe.removePin(1);                       // takes two of the duplicate's bones with it

        check("the duplicate lost a pin", 3, dupe.pinCount());
        check("the original kept all four", 4, original.pinCount());
        check("the original kept all three bones", 3, original.boneCount());
        check("the original's chain still walks to its root",
                new int[]{3, 2, 1, 0}, original.chainToRoot(3));
        for (int i = 0; i < original.boneCount(); i++) {
            PuppetRig.Bone b = original.bone(i);
            checkTrue("original bone " + i + " still in range",
                    b.rootPin >= 0 && b.rootPin < original.pinCount()
                            && b.tipPin >= 0 && b.tipPin < original.pinCount());
        }
    }

    /**
     * The check whose absence cost a whole day of "nothing bends".
     *
     * <p>{@code MeshStampGl} refuses any mesh larger than the coarsest lattice and returns 0 —
     * silently, at draw time, long after the mesh was accepted by the topology and stored on the
     * item. So a builder that asks for too many points produces a picture that simply never
     * bends, with nothing anywhere saying why. PuppetMeshBuilder passed an interior density of
     * 12..160 where the triangulator documents 4-8; at the top of the Mesh detail slider that is
     * a 160x160 grid.
     *
     * <p>This walks the whole slider against a realistic traced outline and asserts every setting
     * lands inside the budget. It cannot import MeshStampGl (Android), so the two numbers are
     * restated here with the reason — and the builder itself reads them from the renderer.
     */
    private static void everyDetailSettingFitsTheDrawBudget() {
        final int MAX_VERTS = 25 * 25;          // MeshStampGl.MAX_VERTS (L3 lattice)
        final int MAX_INDICES = 24 * 24 * 6;    // MeshStampGl.MAX_INDICES
        final int INTERIOR_MIN = 3, INTERIOR_MAX = 10;   // PuppetMeshBuilder's range

        float[] ring = blobRing(96);
        float[] pins = {0.5f, 0.62f, 0.72f, 0.40f, 0.30f, 0.40f};

        for (int step = 0; step <= 10; step++) {
            int interior = INTERIOR_MIN
                    + Math.round((step / 10f) * (INTERIOR_MAX - INTERIOR_MIN));
            com.fadcam.ui.faditor.transform.mesh.PuppetTopology topo =
                    new com.fadcam.ui.faditor.transform.mesh.PuppetTopology(ring, interior, pins);
            checkTrue("detail " + step + "/10 (interior " + interior + "): "
                            + topo.vertexCount() + " verts <= " + MAX_VERTS,
                    topo.vertexCount() <= MAX_VERTS);
            checkTrue("detail " + step + "/10 (interior " + interior + "): "
                            + topo.indexCount() + " indices <= " + MAX_INDICES,
                    topo.indexCount() <= MAX_INDICES);
        }

        // And the setting that broke it, so the regression cannot come back unnoticed.
        com.fadcam.ui.faditor.transform.mesh.PuppetTopology huge =
                new com.fadcam.ui.faditor.transform.mesh.PuppetTopology(ring, 160, pins);
        checkTrue("interior 160 really would have been refused ("
                        + huge.vertexCount() + " verts)",
                huge.vertexCount() > MAX_VERTS || huge.indexCount() > MAX_INDICES);
    }

    /** A rough character-sized blob: a circle with a wobble, which is what a traced PNG looks like. */
    private static float[] blobRing(int n) {
        float[] r = new float[n * 2];
        for (int i = 0; i < n; i++) {
            double t = (i / (double) n) * Math.PI * 2;
            double rad = 0.34 + 0.06 * Math.sin(3 * t) + 0.03 * Math.cos(5 * t);
            r[i * 2] = (float) (0.5 + rad * Math.cos(t));
            r[i * 2 + 1] = (float) (0.5 + rad * Math.sin(t));
        }
        return r;
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

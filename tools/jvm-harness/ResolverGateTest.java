import com.fadcam.ui.faditor.avatar.AvatarRig;
import com.fadcam.ui.faditor.avatar.PuppetPoseResolver;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;

/** Standalone verification of the 2026-07-05 review-gate fixes (runs on the JVM
 *  because the resolver+model are pure Java). Exit 0 = all scenarios pass. */
public class ResolverGateTest {

    static int failures = 0;

    static void check(boolean cond, String name) {
        System.out.println((cond ? "PASS  " : "FAIL  ") + name);
        if (!cond) failures++;
    }

    public static void main(String[] args) {
        finding1_sparseDiscreteInheritance();
        finding2_swappedFiresOnHandoff();
        finding3_tolerantFromJson();
        pinRenormalization();
        hysteresisStillWorks();
        System.out.println(failures == 0 ? "ALL GREEN" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** cols=3 strip; handL cellIndex=5 authored at col0+col2, col1 poses armL only.
     *  Old code: handL reverted to cell 0 across the middle. Fixed: cell 5 everywhere. */
    static void finding1_sparseDiscreteInheritance() {
        AvatarRig rig = new AvatarRig("r", "t");
        rig.getParts().add(new AvatarRig.Part("handL", "s"));
        rig.getParts().add(new AvatarRig.Part("armL", "s"));
        AvatarRig.PoseDomain d = new AvatarRig.PoseDomain("armL_strip");
        d.driverX = "angle";
        d.cols = 3; d.rows = 1;
        d.cells.add(cell(0, 0, pose("handL", 5), pose("armL", 1)));
        d.cells.add(cell(1, 0, pose("armL", 2)));                 // no handL here
        d.cells.add(cell(2, 0, pose("handL", 5), pose("armL", 3)));
        rig.getDomains().add(d);

        PuppetPoseResolver.DiscreteState st = new PuppetPoseResolver.DiscreteState();
        boolean allFive = true;
        for (float dx = -1f; dx <= 1.001f; dx += 0.125f) {
            Map<String, Float> p = new HashMap<>();
            p.put("angle", dx);
            PuppetPoseResolver.PartState hand =
                    PuppetPoseResolver.resolve(rig, p, st).get("handL");
            if (hand.cellIndex != 5) { allFive = false; break; }
        }
        check(allFive, "F1: sparse-authored handL keeps cell 5 across the whole strip");
    }

    /** prev cell poses {mouth:3, tuft:7}; new cell poses {mouth:4} only.
     *  tuft's source hands off to wherever still poses it → swapped must fire
     *  when its committed source changes, and it must NOT revert to cell 0. */
    static void finding2_swappedFiresOnHandoff() {
        AvatarRig rig = new AvatarRig("r", "t");
        rig.getParts().add(new AvatarRig.Part("mouth", "s"));
        rig.getParts().add(new AvatarRig.Part("tuft", "s"));
        AvatarRig.PoseDomain d = new AvatarRig.PoseDomain("head");
        d.driverX = "yaw";
        d.cols = 2; d.rows = 1;
        d.cells.add(cell(0, 0, pose("mouth", 3), pose("tuft", 7)));
        d.cells.add(cell(1, 0, pose("mouth", 4)));                // no tuft
        rig.getDomains().add(d);

        PuppetPoseResolver.DiscreteState st = new PuppetPoseResolver.DiscreteState();
        Map<String, Float> p = new HashMap<>();
        p.put("yaw", -1f);
        PuppetPoseResolver.resolve(rig, p, st);                    // seed at left
        p.put("yaw", 1f);                                          // hard swing right
        Map<String, PuppetPoseResolver.PartState> out = PuppetPoseResolver.resolve(rig, p, st);
        check(out.get("mouth").cellIndex == 4 && out.get("mouth").swapped,
                "F2a: mouth swaps 3->4 with swapped=true");
        check(out.get("tuft").cellIndex == 7 && !out.get("tuft").swapped,
                "F2b: tuft (unposed on the right) STICKS at 7, no phantom swap");
    }

    /** Malformed AI JSON: one pose missing partId + one bad pin — the rig must
     *  survive with everything else intact (old code: NPE, whole rig dropped). */
    static void finding3_tolerantFromJson() {
        String json = "{\"id\":\"r1\",\"name\":\"n\",\"parts\":[" +
                "{\"id\":\"head\",\"sheetId\":\"s\"}," +
                "{\"sheetId\":\"orphan-no-id\"}]," +               // malformed part
                "\"domains\":[{\"id\":\"head\",\"cols\":2,\"rows\":1,\"cells\":[" +
                "{\"col\":0,\"row\":0,\"poses\":[" +
                "{\"partId\":\"head\",\"cell\":2,\"pins\":[[0.1,0.2],[0.5],\"junk\"]}," +
                "{\"x\":0.3}" +                                    // malformed pose
                "]}]}]}";
        try {
            AvatarRig rig = AvatarRig.fromJson(JsonParser.parseString(json).getAsJsonObject());
            boolean partOk = rig.getParts().size() == 1 && rig.getParts().get(0).id.equals("head");
            AvatarRig.Cell c = rig.getDomains().get(0).cellAt(0, 0);
            boolean poseOk = c.poses.size() == 1 && c.poseFor("head").cellIndex == 2;
            boolean pinsOk = c.poseFor("head").pins.size() == 1;   // only the valid [x,y]
            check(partOk && poseOk && pinsOk,
                    "F3: malformed part/pose/pins skip THEMSELVES, rig survives");
        } catch (Exception e) {
            check(false, "F3: fromJson must not throw (" + e + ")");
        }
    }

    /** Pinned cell blended with an authored pinless cell: pins must hold their
     *  positions (renormalized over pin carriers), not collapse toward 0. */
    static void pinRenormalization() {
        AvatarRig rig = new AvatarRig("r", "t");
        rig.getParts().add(new AvatarRig.Part("armL", "s"));
        AvatarRig.PoseDomain d = new AvatarRig.PoseDomain("armL_strip");
        d.driverX = "angle";
        d.cols = 2; d.rows = 1;
        AvatarRig.PartPose withPins = pose("armL", 1);
        withPins.pins.add(new float[]{0.8f, 0.9f});
        d.cells.add(cell(0, 0, withPins));
        d.cells.add(cell(1, 0, pose("armL", 1)));                  // authored, NO pins
        rig.getDomains().add(d);

        Map<String, Float> p = new HashMap<>();
        p.put("angle", 0f);                                        // 50/50 blend
        PuppetPoseResolver.PartState arm = PuppetPoseResolver
                .resolve(rig, p, new PuppetPoseResolver.DiscreteState()).get("armL");
        boolean ok = arm.pins.size() == 1
                && Math.abs(arm.pins.get(0)[0] - 0.8f) < 1e-4
                && Math.abs(arm.pins.get(0)[1] - 0.9f) < 1e-4;
        check(ok, "PIN: pinless neighbor abstains — pin stays at (0.8,0.9), not halved");
    }

    /** The per-part machine must still debounce at the midline (the original
     *  hysteresis contract): tiny oscillation around 0 must not flip cells. */
    static void hysteresisStillWorks() {
        AvatarRig rig = new AvatarRig("r", "t");
        rig.getParts().add(new AvatarRig.Part("head", "s"));
        AvatarRig.PoseDomain d = new AvatarRig.PoseDomain("head");
        d.driverX = "yaw";
        d.cols = 2; d.rows = 1;
        d.cells.add(cell(0, 0, pose("head", 1)));
        d.cells.add(cell(1, 0, pose("head", 2)));
        rig.getDomains().add(d);

        PuppetPoseResolver.DiscreteState st = new PuppetPoseResolver.DiscreteState();
        Map<String, Float> p = new HashMap<>();
        p.put("yaw", -1f);
        PuppetPoseResolver.resolve(rig, p, st);                    // committed to cell 0
        p.put("yaw", 0.05f);                                       // just past midline
        PuppetPoseResolver.PartState h1 = PuppetPoseResolver.resolve(rig, p, st).get("head");
        p.put("yaw", 0.4f);                                        // decisively past margin
        PuppetPoseResolver.PartState h2 = PuppetPoseResolver.resolve(rig, p, st).get("head");
        check(h1.cellIndex == 1 && !h1.swapped, "HYS: midline wobble holds cell 1");
        check(h2.cellIndex == 2 && h2.swapped, "HYS: decisive move commits + swapped=true");
    }

    // ── helpers ──
    static AvatarRig.Cell cell(int col, int row, AvatarRig.PartPose... poses) {
        AvatarRig.Cell c = new AvatarRig.Cell();
        c.col = col;
        c.row = row;
        for (AvatarRig.PartPose p : poses) c.poses.add(p);
        return c;
    }

    static AvatarRig.PartPose pose(String partId, int cellIndex) {
        AvatarRig.PartPose p = new AvatarRig.PartPose(partId);
        p.cellIndex = cellIndex;
        return p;
    }
}

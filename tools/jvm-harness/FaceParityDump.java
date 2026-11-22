import com.fadcam.ui.faditor.avatar.face.FaceCalibration;
import com.fadcam.ui.faditor.avatar.face.FaceMapper;
import com.fadcam.ui.faditor.avatar.face.FaceStage;
import com.fadcam.ui.faditor.avatar.face.FaceTuning;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * FACE-1: the phone's face engine on a fixed set of faces, written out so face_parity.mjs can run the web
 * pilot's al-face.js on the same faces and say whether the two agree. Usage: FaceParityDump <out.json>
 */
public class FaceParityDump {
    public static void main(String[] a) throws Exception {
        FaceCalibration cal = FaceEngineTest.neutral();
        Gson gson = new Gson();
        JsonObject root = new JsonObject();
        root.add("cal", cal.toJson());
        Object[][] faces = {
                {}, {"eyeBlinkLeft", 0.9f}, {"eyeBlinkLeft", 0.45f, "eyeBlinkRight", 0.45f},
                {"eyeBlinkLeft", 0.62f, "eyeBlinkRight", 0.5f}, {"eyeBlinkLeft", 0.35f, "eyeBlinkRight", 0.35f, "eyeLookDownLeft", 0.8f, "eyeLookDownRight", 0.8f},
                {"browOuterUpLeft", 0.9f}, {"browDownLeft", 1f, "browDownRight", 1f, "browInnerUp", 0.4f},
                {"jawOpen", 0.6f, "mouthClose", 0.6f}, {"jawOpen", 0.4f, "mouthFunnel", 0.5f}, {"mouthPucker", 0.8f},
                {"mouthUpperUpLeft", 0.7f, "mouthLowerDownRight", 0.5f}, {"mouthLeft", 0.6f, "jawLeft", 0.5f, "jawForward", 0.3f},
                {"mouthSmileRight", 0.8f, "mouthFrownLeft", 0.4f, "cheekPuff", 0.5f, "noseSneerLeft", 0.6f},
                {"mouthStretchLeft", 0.5f, "mouthStretchRight", 0.5f, "jawOpen", 0.3f, "mouthPressLeft", 0.2f},
                {"eyeLookOutLeft", 0.5f, "eyeLookInRight", 0.5f, "eyeLookUpLeft", 0.3f, "eyeLookUpRight", 0.3f},
                {"eyeSquintLeft", 0.7f, "cheekSquintLeft", 0.5f, "eyeWideRight", 0.6f},
                {FaceCalibration.HEAD_YAW, 19f, FaceCalibration.HEAD_PITCH, 20f, FaceCalibration.HEAD_ROLL, -12f},
                {FaceCalibration.FACE_X, 0.44f, FaceCalibration.FACE_Y, 0.5f, FaceCalibration.FACE_SIZE, 0.36f},
        };
        FaceTuning def = new FaceTuning(), mir = new FaceTuning(), gains = new FaceTuning();
        mir.mirror = true;
        gains.head = 1.5f; gains.eyes = 0.7f; gains.brows = 1.4f; gains.mouth = 0.8f; gains.blinkSync = false;
        FaceTuning[] tunings = {def, mir, gains};
        JsonArray cases = new JsonArray();
        for (Object[] f : faces) {
            for (FaceTuning tun : tunings) {
                Map<String, Float> raw = FaceEngineTest.face(f);
                JsonObject c = new JsonObject();
                c.add("raw", gson.toJsonTree(raw));
                c.add("tun", tun.toJson());
                c.add("out", gson.toJsonTree(FaceMapper.map(raw, cal, tun)));
                cases.add(c);
            }
        }
        root.add("cases", cases);

        // a stage run: the first neutral, a face, a loss, a return
        FaceStage st = new FaceStage();
        JsonArray frames = new JsonArray();
        double t = 0;
        for (int i = 0; i < 90; i++, t += 1 / 15.0) {
            Map<String, Float> raw = i < 30 ? FaceEngineTest.face("jawOpen", i < 12 ? 0f : 0.4f)
                    : i < 55 ? null
                    : FaceEngineTest.face(FaceCalibration.HEAD_YAW, 3f + 20f, "eyeBlinkLeft", (i % 9) < 2 ? 0.9f : 0.08f, "mouthPucker", (i % 7) < 3 ? 0.8f : 0f);
            JsonObject fr = new JsonObject();
            fr.addProperty("t", t);
            fr.add("raw", raw == null ? null : gson.toJsonTree(raw));
            fr.add("out", gson.toJsonTree(st.process(raw, t)));
            frames.add(fr);
        }
        root.add("stage", frames);

        // face pins: the same made-up face, posed and with a smile, through both engines
        float[] base = FaceEngineTest.cloud();
        float[] smiling = base.clone();
        smiling[61 * 3] -= 0.01f;
        smiling[61 * 3 + 1] -= 0.006f;
        smiling[17 * 3 + 1] += 0.008f;
        int[] chosen = {61, 291, 0, 17, 159, 386, 205};
        JsonArray pcases = new JsonArray();
        float[][] poses = {{0, 0, 0, 1, 0, 0}, {28, -12, 15, 1.35f, 0.07f, -0.04f}, {-20, 8, -10, 0.9f, -0.05f, 0.02f}};
        for (float[] ps : poses) {
            for (float[] face : new float[][]{base, smiling}) {
                float[] now = FaceEngineTest.posed(face, ps[0], ps[1], ps[2], ps[3], ps[4], ps[5]);
                JsonObject c = new JsonObject();
                c.add("rest", gson.toJsonTree(base));
                c.add("now", gson.toJsonTree(now));
                c.add("chosen", gson.toJsonTree(chosen));
                float[] off = com.fadcam.ui.faditor.avatar.face.FacePoints.offsets(
                        com.fadcam.ui.faditor.avatar.face.FacePoints.normalize(base),
                        com.fadcam.ui.faditor.avatar.face.FacePoints.normalize(now), chosen, -1);
                c.add("out", gson.toJsonTree(off));
                pcases.add(c);
            }
        }
        root.add("points", pcases);
        // a stage run that takes its neutral with points, then watches two of them
        FaceStage ps = new FaceStage();
        ps.setWatchedPoints(new int[]{61, 17});
        JsonArray pframes = new JsonArray();
        t = 0;
        for (int i = 0; i < 40; i++, t += 1 / 15.0) {
            float[] xyz = i < 20 ? base : i < 30 ? FaceEngineTest.posed(smiling, 15, 5, 8, 1.1, 0.02, 0) : null;
            Map<String, Float> raw = xyz == null ? null : FaceEngineTest.face();
            JsonObject fr = new JsonObject();
            fr.addProperty("t", t);
            fr.add("raw", raw == null ? null : gson.toJsonTree(raw));
            fr.add("xyz", xyz == null ? null : gson.toJsonTree(xyz));
            fr.add("out", gson.toJsonTree(ps.process(raw, xyz, t)));
            pframes.add(fr);
        }
        root.add("pointStage", pframes);

        // the lids (11-22): the owner's eye-test frames against a neutral with the gap, both side namings
        FaceCalibration.Capture lc = new FaceCalibration.Capture(FaceCalibration.none(), 4);
        for (int i = 0; i < 4; i++) lc.add(FaceEngineTest.eyes(0.15f, 0.18f, 0.26f, 0.32f, 0.30f));
        FaceCalibration lidCal = lc.result();
        float[][] eyeFrames = {{0.66f, 0.82f, 0.75f, 0.03f, 0.046f}, {0.54f, 0.58f, 0.57f, 0.097f, 0.095f},
                {0.62f, 0.29f, 0.47f, 0.105f, 0.24f}, {0.20f, 0.26f, 0.42f, 0.257f, 0.279f}, {0.3f, 0.3f, 0.3f, 0.16f, 0.15f}};
        JsonArray lcases = new JsonArray();
        for (FaceCalibration lcal : new FaceCalibration[]{lidCal, lidCal.withSwapSides(true)}) {
            for (float[] e : eyeFrames) {
                for (FaceTuning tun : tunings) {
                    Map<String, Float> raw = FaceEngineTest.eyes(e[0], e[1], e[2], e[3], e[4]);
                    JsonObject c = new JsonObject();
                    c.add("cal", lcal.toJson());
                    c.add("raw", gson.toJsonTree(raw));
                    c.add("tun", tun.toJson());
                    c.add("out", gson.toJsonTree(FaceMapper.map(raw, lcal, tun)));
                    lcases.add(c);
                }
            }
        }
        root.add("lidCases", lcases);
        // a stage on drawn eyes: an old neutral without lids (taken again), then a blink, a wink, a loss, a return
        FaceStage ls = new FaceStage(cal, new FaceTuning());
        root.add("lidStageCal", cal.toJson());
        JsonArray lframes = new JsonArray();
        t = 0;
        for (int i = 0; i < 50; i++, t += 1 / 15.0) {
            float[] xyz = i < 14 ? FaceEngineTest.eyeCloud(1f, 1f) : i < 20 ? FaceEngineTest.eyeCloud(0.05f, 0.05f)
                    : i < 26 ? FaceEngineTest.eyeCloud(0.1f, 0.9f) : i < 34 ? null : FaceEngineTest.eyeCloud(0.5f, 0.5f);
            Map<String, Float> raw = xyz == null ? null : FaceEngineTest.face();
            JsonObject fr = new JsonObject();
            fr.addProperty("t", t);
            fr.add("raw", raw == null ? null : gson.toJsonTree(raw));
            fr.add("xyz", xyz == null ? null : gson.toJsonTree(xyz));
            fr.add("out", gson.toJsonTree(ls.process(raw, xyz, t)));
            lframes.add(fr);
        }
        root.add("lidStage", lframes);

        // shoulders (SHRUG-1): a body through the shoulder stage, both Mirror settings: rest, shrugs, a tilt, closer, lost, back
        float[][] bodies = new float[60][];
        for (int i = 0; i < 60; i++) {
            bodies[i] = i < 8 ? FaceEngineTest.rest()
                    : i < 16 ? FaceEngineTest.body(0.1f, 0.1f, 0, 0, 1, 0, 0.95f)
                    : i < 22 ? FaceEngineTest.body(0.06f, 0, 0, 0, 1, 0, 0.95f)
                    : i < 28 ? FaceEngineTest.body(0, 0.03f, 18, 0.005f, 1.2f, 0.03f, 0.95f)
                    : i < 40 ? FaceEngineTest.body(0.1f, 0.1f, 0, 0, 1, 0, 0.2f)
                    : FaceEngineTest.body(0.045f, 0.1f, -10, 0, 0.9f, -0.04f, 0.95f);
        }
        JsonArray sframes = new JsonArray();
        for (boolean mirror : new boolean[]{false, true}) {
            com.fadcam.ui.faditor.avatar.face.ShoulderStage ss = new com.fadcam.ui.faditor.avatar.face.ShoulderStage();
            t = 0;
            for (int i = 0; i < bodies.length; i++, t += 0.1) {
                if (i == 50) ss.requestNeutral();
                JsonObject fr = new JsonObject();
                fr.addProperty("t", t);
                fr.addProperty("mirror", mirror);
                fr.addProperty("recentre", i == 50);
                fr.add("xyv", gson.toJsonTree(bodies[i]));
                float[] d = com.fadcam.ui.faditor.avatar.face.ShoulderStage.drops(bodies[i], FaceEngineTest.TALL);
                fr.add("drops", d == null ? null : gson.toJsonTree(d));
                fr.add("out", gson.toJsonTree(ss.process(d, t, mirror)));
                sframes.add(fr);
            }
        }
        root.add("shoulders", sframes);
        root.addProperty("tall", FaceEngineTest.TALL);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(a[0]), StandardCharsets.UTF_8)) {
            gson.toJson(root, w);
        }
        System.out.println("wrote " + cases.size() + " faces and " + frames.size() + " stage frames");
    }
}

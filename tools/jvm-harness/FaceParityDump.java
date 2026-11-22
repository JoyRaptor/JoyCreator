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
        try (Writer w = new OutputStreamWriter(new FileOutputStream(a[0]), StandardCharsets.UTF_8)) {
            gson.toJson(root, w);
        }
        System.out.println("wrote " + cases.size() + " faces and " + frames.size() + " stage frames");
    }
}

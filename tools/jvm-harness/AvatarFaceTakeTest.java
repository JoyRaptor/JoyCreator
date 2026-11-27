import cc.joycreator.avatar.face.FaceDrive;
import cc.joycreator.avatar.face.FaceWalk;
import cc.joycreator.avatar.face.MirrorChip;
import cc.joycreator.avatar.perform.Inputs;
import com.fadcam.ui.faditor.avatar.face.FaceStage;
import com.fadcam.ui.faditor.avatar.face.FaceTuning;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The phone room's face against the owner's REAL face: his own take on the Note 9 (11-27), kept as numbers only in
 * tasks/avatar/golden/face-take-20261127.track.json (the tracker's raw face and 58 face points a frame, no picture). Every frame
 * goes through the phone's own face engine (FaceStage), the room's hands (Inputs with its FaceDrive) and walking with your face
 * (FaceWalk), as the room runs them, and each move he made is checked: turning, nodding, tilting, each eye on its own, leaning,
 * coming closer, the mouth and the brows. Mirror on (the default) means the character moves like your reflection; Topps's head
 * drawings face the screen's right at +1 on the turn pad. Run: bash tools/jvm-harness/run-avatar-face.sh
 */
public class AvatarFaceTakeTest {
    static int fails;
    static final String TRACK = "tasks/avatar/golden/face-take-20261127.track.json";
    static final double H = 400, LIM = 1e9;            // a body 400 tall; no wall to walk into (the walk is measured, not capped)

    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    static final class Frame {
        double t;
        Map<String, Float> raw;
        float[] xyz;
    }

    /** The track, frame by frame: the raw face and the face's points (the ones not kept stay 0; the engine does not read them). */
    static List<Frame> load(JsonObject tr) {
        JsonArray keys = tr.getAsJsonArray("rawKeys"), scale = tr.getAsJsonArray("rawScale"), keep = tr.getAsJsonArray("keep");
        double fps = tr.get("fps").getAsDouble(), ps = tr.get("pointScale").getAsDouble();
        List<Frame> out = new ArrayList<>();
        for (JsonElement e : tr.getAsJsonArray("frames")) {
            JsonArray a = e.getAsJsonArray();
            Frame f = new Frame();
            f.t = a.get(0).getAsInt() / fps;
            if (!a.get(1).isJsonNull()) {
                JsonArray r = a.get(1).getAsJsonArray();
                f.raw = new HashMap<>();
                for (int i = 0; i < keys.size(); i++) f.raw.put(keys.get(i).getAsString(), (float) (r.get(i).getAsDouble() / scale.get(i).getAsDouble()));
            }
            if (!a.get(2).isJsonNull()) {
                JsonArray p = a.get(2).getAsJsonArray();
                f.xyz = new float[478 * 3];
                for (int k = 0; k < keep.size(); k++) {
                    int i = keep.get(k).getAsInt();
                    for (int c = 0; c < 3; c++) f.xyz[i * 3 + c] = (float) (p.get(k * 3 + c).getAsDouble() / ps);
                }
            }
            out.add(f);
        }
        return out;
    }

    /** A walker as FaceWalk needs it: sideways, and toward the camera (its ground line follows straight away). */
    static final class Legs implements FaceWalk.Legs {
        double tx, base;
        Double tz;

        public boolean travels() { return true; }
        public boolean held() { return false; }
        public double tx() { return tx; }
        public void setTx(double v) { tx = v; }
        public boolean depth() { return true; }
        public double[] depthLimits() { return new double[]{-140, 180}; }
        public Double tz() { return tz; }
        public void setTz(double v) { tz = v; base = v; }
        public double base() { return base; }
    }

    /** What Topps did, frame by frame. */
    static final class Run {
        final List<Double> t = new ArrayList<>(), tx = new ArrayList<>(), tz = new ArrayList<>();
        final List<Map<String, Object>> sig = new ArrayList<>();

        double at(int i, String k) {
            Object v = sig.get(i).get(k);
            return v instanceof Number ? ((Number) v).doubleValue() : Double.NaN;
        }

        int from(double s) { int i = 0; while (i < t.size() && t.get(i) < s) i++; return i; }

        int to(double s) { int i = t.size() - 1; while (i > 0 && t.get(i) > s) i--; return i; }

        double max(String k, double a, double b) { double m = -9; for (int i = from(a); i <= to(b); i++) m = Math.max(m, at(i, k)); return m; }

        double min(String k, double a, double b) { double m = 9; for (int i = from(a); i <= to(b); i++) m = Math.min(m, at(i, k)); return m; }

        /** How far it walked sideways from a to b. */
        double walked(double a, double b) { return tx.get(to(b)) - tx.get(Math.max(0, from(a) - 1)); }

        /** The most it walked sideways at any moment between a and b (back and forth counts). */
        double strayed(double a, double b) {
            double x0 = tx.get(Math.max(0, from(a) - 1)), m = 0;
            for (int i = from(a); i <= to(b); i++) m = Math.max(m, Math.abs(tx.get(i) - x0));
            return m;
        }
    }

    static Run run(List<Frame> frames, boolean mirrorChip, double fps) throws Exception {
        return run(frames, mirrorChip, fps, null);
    }

    /** {@code maskSet}: a head wearing the face mask, or null. */
    static Run run(List<Frame> frames, boolean mirrorChip, double fps, org.json.JSONObject maskSet) throws Exception {
        FaceStage st = new FaceStage();
        FaceTuning tu = new FaceTuning();
        MirrorChip.set(tu, mirrorChip);
        st.setTuning(tu);
        Inputs in = new Inputs();
        in.setSettings(new org.json.JSONObject("{\"idle\":false,\"blinkAuto\":false,\"breathe\":false}"));   // (as the pilot run: the face alone)
        in.face(true);
        FaceWalk fw = new FaceWalk();
        fw.setLeftRight(true);
        fw.setCloser(true);
        Legs legs = new Legs();
        Run r = new Run();
        double dt = 1 / fps;
        long now = 0;
        for (Frame f : frames) {
            if (maskSet != null) {
                st.setWatchedPoints(cc.joycreator.avatar.face.MaskDrive.watch(maskSet));
                in.faceDrive().mask(maskSet, mirrorChip, st.calibration().restPoints());
            }
            Map<String, Float> face = st.process(f.raw, f.xyz, f.t);
            in.faceSaw(face);
            Map<String, Object> m = in.frame(dt, now += Math.round(dt * 1000));
            fw.step(dt, face, legs, H, LIM, false);
            r.t.add(f.t);
            r.sig.add(m);
            r.tx.add(legs.tx);
            r.tz.add(legs.tz == null ? 0 : legs.tz);
        }
        return r;
    }

    static org.json.JSONObject byId(org.json.JSONObject n, String id) throws Exception {
        if (id.equals(n.optString("id"))) return n;
        org.json.JSONArray k = n.optJSONArray("kids");
        for (int i = 0; k != null && i < k.length(); i++) {
            org.json.JSONObject r = byId(k.getJSONObject(i), id);
            if (r != null) return r;
        }
        return null;
    }

    static String n2(double v) {
        return String.valueOf(Math.round(v * 100) / 100.0);
    }

    public static void main(String[] a) throws Exception {
        JsonObject tr = JsonParser.parseString(new String(Files.readAllBytes(Paths.get(TRACK)), StandardCharsets.UTF_8)).getAsJsonObject();
        double fps = tr.get("fps").getAsDouble();
        List<Frame> frames = load(tr);
        int seen = 0;
        for (Frame f : frames) if (f.raw != null) seen++;
        check(frames.size() > 600 && seen > frames.size() * 0.85, "his take: " + frames.size() + " frames, his face found in " + seen);

        Run r = run(frames, true, fps);                    // Mirror on, the default: like your reflection
        double walk = r.strayed(2.0, 3.1);
        check(r.max("turnX", 2.0, 3.1) > 0.8 && walk < 0.04 * H,
                "he turns his head to his right: Topps turns to the screen's right like his reflection (" + n2(r.max("turnX", 2.0, 3.1)) + "), and does not walk (" + n2(walk) + ")");
        walk = r.strayed(9.6, 10.6);
        check(r.min("turnX", 9.6, 10.6) < -0.8 && walk < 0.04 * H,
                "he turns to his left: Topps turns to the screen's left (" + n2(r.min("turnX", 9.6, 10.6)) + "), and does not walk (" + n2(walk) + ")");
        check(r.max("roll", 0.7, 1.5) > 0.4 && r.min("roll", 7.0, 7.7) < -0.25,
                "he tilts his head to his right, later to his left: Topps tilts with him (" + n2(r.max("roll", 0.7, 1.5)) + ", " + n2(r.min("roll", 7.0, 7.7)) + ")");
        check(r.max("blinkL", 3.9, 4.7) >= 0.9 && r.max("blinkR", 4.1, 4.6) <= 0.35 && r.max("blinkR", 5.9, 6.6) >= 0.9 && r.max("blinkL", 6.1, 6.5) <= 0.35,     // (winking his left he half-shut his right too: its lids show it)
                "his right eye, then his left, each on its own: Topps's eye on the same side of the screen, the other stays open ("
                        + n2(r.max("blinkL", 3.9, 4.7)) + " " + n2(r.max("blinkR", 4.1, 4.6)) + ", " + n2(r.max("blinkR", 5.9, 6.6)) + " " + n2(r.max("blinkL", 6.1, 6.5)) + ")");
        double ll = r.walked(11.5, 12.3), lr = r.walked(12.6, 13.4);
        check(ll < -0.1 * H && lr > 0.1 * H, "he leans to his left, then his right: Topps walks to the screen's left, then its right (" + n2(ll) + ", " + n2(lr) + ")");
        walk = r.strayed(3.2, 9.5);
        check(walk < 0.04 * H, "winking and tilting do not walk him (" + n2(walk) + ")");
        check(r.max("turnY", 14.8, 15.3) > 0.6 && r.min("turnY", 17.2, 18.0) < -0.3,
                "he nods down, later looks up: Topps looks down, then up (" + n2(r.max("turnY", 14.8, 15.3)) + ", " + n2(r.min("turnY", 17.2, 18.0)) + ")");
        double tzMax = 0;
        for (int i = r.from(29.2); i <= r.to(30.0); i++) tzMax = Math.max(tzMax, r.tz.get(i));
        check(tzMax > 0.7 * 180, "he comes close to the phone: Topps walks toward the camera (" + n2(tzMax) + " of 180)");
        check(Math.min(r.max("browL", 13.3, 14.0), r.max("browR", 13.3, 14.0)) > 0.7, "he raises his brows: both of Topps's rise");
        double nodBrow = Math.min(r.min("browL", 33.6, 34.2), r.min("browR", 33.6, 34.2));
        check(nodBrow > -0.2, "he nods 25 degrees down at the phone: Topps's brows stay put (the tracker sees brows from below as lowered) (" + n2(nodBrow) + ")");
        check(Math.min(r.max("browL", 28.95, 29.2), r.max("browR", 28.95, 29.2)) > 0.6, "brows raised while looking down still rise (" + n2(Math.min(r.max("browL", 28.95, 29.2), r.max("browR", 28.95, 29.2))) + ")");
        // his puff: no tracker value tells it from his resting face (its puff value never passes 0.003; his lips read rolled in at
        // rest, 0.89, and a little less while puffing, 0.75), so nothing puffs Topps from the camera: the Puff button does
        check(r.max("face.cheekPuff", 38.3, 42.5) < 0.05, "his puff: the tracker does not see it, so the camera puffs nothing by mistake (" + n2(r.max("face.cheekPuff", 38.3, 42.5)) + ")");
        boolean aa = false;
        for (int i = r.from(15.5); i <= r.to(16.0); i++) aa |= "Aa".equals(r.sig.get(i).get("vis"));
        check(r.max("mouth", 15.5, 16.0) > 0.9 && aa, "he opens his mouth: Topps's mouth opens (Aa)");
        check(r.max("face.pucker", 15.0, 16.3) > 0.8, "he puckers: the pucker lane a part can follow rises (" + n2(r.max("face.pucker", 15.0, 16.3)) + ")");
        check(r.max("face.smile", 21.9, 22.4) > 0.6, "he smiles: the smile lane rises (" + n2(r.max("face.smile", 21.9, 22.4)) + ")");

        // the face mask (FACE-2) on his face: Topps wearing the pilot's mask; its lower lip follows his mouth opening
        org.json.JSONObject topps = new org.json.JSONObject(new String(Files.readAllBytes(Paths.get("tools/avatarlab/characters/topps.character.json")), StandardCharsets.UTF_8));
        org.json.JSONObject patch = new org.json.JSONObject(new String(Files.readAllBytes(Paths.get("tasks/avatar/golden/topps-mask.patch.json")), StandardCharsets.UTF_8));
        org.json.JSONObject head = byId(topps.getJSONObject("root"), patch.getString("head"));
        head.put("marks", new org.json.JSONObject().put("", patch.getJSONObject("marks")));
        head.put("face", patch.getJSONObject("face"));
        Run mk = run(frames, true, fps, head);
        double lipOpen = mk.max("mk.7.y", 15.5, 16.0), lipRest = Math.max(Math.abs(mk.max("mk.7.y", 0.2, 0.6)), Math.abs(mk.min("mk.7.y", 0.2, 0.6)));
        check(lipOpen > 0.05 && lipRest < 0.02, "the face mask on his face: its lower lip drops as he opens his mouth (" + n2(lipOpen) + "), and keeps still at rest (" + n2(lipRest) + ")");

        Run off = run(frames, false, fps);                 // Mirror off: like someone facing you
        check(off.max("turnX", 2.0, 3.1) < 0.2 && off.min("turnX", 2.0, 3.1) < -0.8 && off.max("blinkR", 3.9, 4.7) >= 0.9,
                "Mirror off: the same turn and wink land on the other side, like someone facing him");
        FaceTuning fresh = new FaceTuning();
        check(MirrorChip.on(fresh), "the Mirror chip is on by default, as the pilot's tick (the engine's own default moves like a reflection)");

        System.out.println(fails == 0 ? "PASSED" : "FAILED " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}

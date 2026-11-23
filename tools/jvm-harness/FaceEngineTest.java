import com.fadcam.ui.faditor.avatar.AvatarParamTrack;
import com.fadcam.ui.faditor.avatar.DriverParamSmoother;
import com.fadcam.ui.faditor.avatar.OneEuroFilter;
import com.fadcam.ui.faditor.avatar.TrackingFrame;
import com.fadcam.ui.faditor.avatar.TrackingParamPipeline;
import com.fadcam.ui.faditor.avatar.face.FaceCalibration;
import com.fadcam.ui.faditor.avatar.face.FaceFollower;
import com.fadcam.ui.faditor.avatar.face.FaceMapper;
import com.fadcam.ui.faditor.avatar.face.FacePass;
import com.fadcam.ui.faditor.avatar.face.FacePoints;
import com.fadcam.ui.faditor.avatar.face.FaceSignals;
import com.fadcam.ui.faditor.avatar.face.FaceStage;
import com.fadcam.ui.faditor.avatar.face.FaceTuning;
import com.fadcam.ui.faditor.avatar.face.ShoulderStage;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * FACE-1: the face engine, off the phone. Each check is named for what the owner would see go wrong.
 * run-face.sh runs it, and with --proof plants each fault in a copy of the engine and expects a FAIL.
 */
public class FaceEngineTest {
    static int fails = 0;

    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    /** A resting face: a little squint and frown, the phone held low (head reads 8° down). */
    static Map<String, Float> face(Object... kv) {
        Map<String, Float> m = new HashMap<>();
        m.put("eyeBlinkLeft", 0.08f); m.put("eyeBlinkRight", 0.08f);
        m.put("browDownLeft", 0.25f); m.put("browDownRight", 0.25f);
        m.put("eyeSquintLeft", 0.15f); m.put("eyeSquintRight", 0.15f);
        m.put(FaceCalibration.HEAD_YAW, 3f); m.put(FaceCalibration.HEAD_PITCH, 8f); m.put(FaceCalibration.HEAD_ROLL, 0f);
        m.put(FaceCalibration.FACE_X, 0.5f); m.put(FaceCalibration.FACE_Y, 0.45f); m.put(FaceCalibration.FACE_SIZE, 0.3f);
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], ((Number) kv[i + 1]).floatValue());
        return m;
    }

    static float g(Map<String, Float> m, String k) {
        Float v = m.get(k);
        return v == null ? Float.NaN : v;
    }

    /** A calibration whose neutral is {@link #face()} with no extras. */
    static FaceCalibration neutral() {
        FaceCalibration.Capture c = new FaceCalibration.Capture(FaceCalibration.none(), 4);
        for (int i = 0; i < 4; i++) c.add(face());
        return c.result();
    }

    public static void main(String[] a) {
        FaceCalibration cal = neutral();
        FaceTuning tun = new FaceTuning();

        // ── expression mapping ──
        Map<String, Float> rest = FaceMapper.map(face(), cal, tun);
        check(Math.abs(g(rest, FaceSignals.BROW_L)) < 0.02f && Math.abs(g(rest, FaceSignals.TURN_Y)) < 0.02f
                        && g(rest, FaceSignals.SQUINT_L) < 0.02f,
                "neutral: a resting frown, squint and a low-held phone read as zero after one tap");
        Map<String, Float> raw = FaceMapper.map(face(), FaceCalibration.none(), tun);
        check(g(raw, FaceSignals.BROW_L) < -0.2f, "without a neutral the same resting face reads as a frown (the fault neutral fixes)");
        Map<String, Float> frown = FaceMapper.map(face("browDownLeft", 1f, "browDownRight", 1f), cal, tun);
        check(g(frown, FaceSignals.BROW_L) <= -0.95f, "neutral: a full frown still reaches the end of its range");

        Map<String, Float> wink = FaceMapper.map(face("eyeBlinkLeft", 0.9f), cal, tun);
        check(g(wink, FaceSignals.BLINK_L) > 0.9f && g(wink, FaceSignals.BLINK_R) < 0.1f,
                "a wink closes one eye only (reflection: the tracker's Left is the person's right, the character's left)");
        FaceTuning mir = new FaceTuning(); mir.mirror = true;
        Map<String, Float> winkM = FaceMapper.map(face("eyeBlinkLeft", 0.9f), cal, mir);
        check(g(winkM, FaceSignals.BLINK_R) > 0.9f && g(winkM, FaceSignals.BLINK_L) < 0.1f, "Mirror swaps which eye winks");
        Map<String, Float> half = FaceMapper.map(face("eyeBlinkLeft", 0.45f, "eyeBlinkRight", 0.45f), cal, tun);
        check(g(half, FaceSignals.BLINK_L) > 0.15f && g(half, FaceSignals.BLINK_L) < 0.85f,
                "a half-closed lid is half closed (continuous, not on or off)");
        Map<String, Float> uneven = FaceMapper.map(face("eyeBlinkLeft", 0.62f, "eyeBlinkRight", 0.5f), cal, tun);
        check(Math.abs(g(uneven, FaceSignals.BLINK_L) - g(uneven, FaceSignals.BLINK_R)) < 0.03f,
                "eyes the tracker reads a little unevenly blink together (no lopsided look)");
        Map<String, Float> downLook = FaceMapper.map(face("eyeBlinkLeft", 0.35f, "eyeBlinkRight", 0.35f,
                "eyeLookDownLeft", 0.8f, "eyeLookDownRight", 0.8f), cal, tun);
        check(g(downLook, FaceSignals.BLINK_L) < 0.1f, "looking down at the phone is not a half blink");

        Map<String, Float> brow = FaceMapper.map(face("browOuterUpLeft", 0.9f, "browDownLeft", 0.25f), cal, tun);
        check(g(brow, FaceSignals.BROW_L) > 0.5f && g(brow, FaceSignals.BROW_R) < 0.15f, "one raised brow is one raised brow");

        Map<String, Float> teeth = FaceMapper.map(face("jawOpen", 0.6f, "mouthClose", 0.6f), cal, tun);
        check(g(teeth, FaceSignals.JAW) > 0.9f && g(teeth, FaceSignals.MOUTH) < 0.05f,
                "the jaw drops behind closed lips: teeth open, the opening stays shut");
        Map<String, Float> talk = FaceMapper.map(face("jawOpen", 0.4f), cal, tun);
        check(g(talk, FaceSignals.MOUTH) > 0.6f && g(talk, FaceSignals.JAW) > 0.6f, "an open mouth opens lips and jaw together");
        Map<String, Float> sneer = FaceMapper.map(face("mouthUpperUpLeft", 0.7f), cal, tun);
        check(g(sneer, FaceSignals.LIP_UP_L) > 0.8f && g(sneer, FaceSignals.LIP_UP_R) < 0.01f,
                "the upper lip lifts on one side only (a sneer shows teeth on that side)");
        Map<String, Float> side = FaceMapper.map(face("mouthLeft", 0.6f, "jawLeft", 0.5f), cal, tun);
        check(g(side, FaceSignals.MOUTH_X) > 0.5f && g(side, FaceSignals.JAW_X) > 0.5f,
                "lips and jaw sliding to the person's right go to the screen's right (reflection)");
        check(g(FaceMapper.map(face("mouthLeft", 0.6f), cal, mir), FaceSignals.MOUTH_X) < -0.5f, "Mirror turns the slide round");
        Map<String, Float> smile = FaceMapper.map(face("mouthSmileRight", 0.8f), cal, tun);
        check(g(smile, FaceSignals.SMILE_R) > 0.8f && g(smile, FaceSignals.SMILE_L) < 0.05f, "a half smile is a half smile");

        Map<String, Float> turned = FaceMapper.map(face(FaceCalibration.HEAD_YAW, 3f + 16f), cal, tun);
        check(Math.abs(g(turned, FaceSignals.TURN_X) - 0.5f) < 0.02f, "head turn is measured from neutral (16° of 32° = half)");
        check(g(turned, FaceSignals.LOOK_X) == 0f, "turning the head alone does not move the eyes");
        Map<String, Float> look = FaceMapper.map(face("eyeLookOutLeft", 0.5f, "eyeLookInRight", 0.5f), cal, tun);
        check(g(look, FaceSignals.LOOK_X) > 0.6f, "the eyes read from both eyes together");

        Map<String, Float> lean = FaceMapper.map(face(FaceCalibration.FACE_X, 0.44f), cal, tun);
        check(g(lean, FaceSignals.LEAN_X) > 0.2f, "leaning to your right (the picture's left) leans the bust to the screen's right");
        Map<String, Float> closer = FaceMapper.map(face(FaceCalibration.FACE_SIZE, 0.36f), cal, tun);
        check(g(closer, FaceSignals.NEAR) > 0.4f, "coming closer to the phone reads as near");

        // ── directions, settled per phone ──
        FaceCalibration.DirectionCheck dc = new FaceCalibration.DirectionCheck(FaceCalibration.DirectionCheck.YAW, cal);
        dc.add(face(FaceCalibration.HEAD_YAW, -2f));
        boolean answered = dc.add(face(FaceCalibration.HEAD_YAW, -15f));
        FaceCalibration fixed = dc.apply();
        check(answered && dc.answer() == -1 && g(FaceMapper.map(face(FaceCalibration.HEAD_YAW, -15f), fixed, tun), FaceSignals.TURN_X) > 0.4f,
                "direction check: 'turn to your right' that reads backwards is turned round for good");
        FaceCalibration.DirectionCheck ec = new FaceCalibration.DirectionCheck(FaceCalibration.DirectionCheck.EYE, cal);
        ec.add(face("eyeBlinkRight", 0.9f));
        FaceCalibration eyeFixed = ec.apply();
        check(ec.answer() == -1 && eyeFixed.swapSides()
                        && g(FaceMapper.map(face("eyeBlinkRight", 0.9f), eyeFixed, tun), FaceSignals.BLINK_L) > 0.9f,
                "direction check: 'close your right eye' fixes which side the tracker calls left");
        FaceCalibration back = FaceCalibration.fromJson(fixed.withSwapSides(true).toJson());
        check(back.signYaw() == -1 && back.swapSides() && back.isNeutralSet()
                        && Math.abs(back.rest("browDownLeft") - 0.25f) < 1e-6, "calibration survives being saved and read back");
        // The owner's 11-22 find: the outline sat a quarter turn off his face. Points come back in the
        // camera's sideways frame and must turn exactly as the picture turns (clockwise by the rotation).
        float[] r90 = com.fadcam.ui.faditor.avatar.face.FaceFrame.upright(0f, 0f, 90);
        float[] r270 = com.fadcam.ui.faditor.avatar.face.FaceFrame.upright(0f, 0f, 270);
        float[] r180 = com.fadcam.ui.faditor.avatar.face.FaceFrame.upright(0.2f, 0.3f, 180);
        float[] mid = com.fadcam.ui.faditor.avatar.face.FaceFrame.upright(0.25f, 0.5f, 270);
        check(r90[0] == 1f && r90[1] == 0f && r270[0] == 0f && r270[1] == 1f
                        && Math.abs(r180[0] - 0.8f) < 1e-6 && Math.abs(r180[1] - 0.7f) < 1e-6
                        && Math.abs(mid[0] - 0.5f) < 1e-6 && Math.abs(mid[1] - 0.75f) < 1e-6,
                "the face's points turn upright exactly as the camera picture does (the outline sits on the face)");
        com.google.gson.JsonObject saved1 = back.toJson();
        saved1.addProperty("v", 1);
        saved1.getAsJsonObject("sign").addProperty("lean", 1);
        FaceCalibration old = FaceCalibration.fromJson(saved1);
        check(!old.isNeutralSet() && old.rest(FaceCalibration.FACE_SIZE) == 0f && old.signLean() == -1,
                "a neutral saved before the upright fix is re-taken by itself, and its sideways lean direction dropped");
        com.google.gson.JsonObject saved2 = back.toJson();
        saved2.addProperty("v", 2);
        FaceCalibration two = FaceCalibration.fromJson(saved2);
        check(!two.isNeutralSet() && two.signYaw() == 1 && !two.swapSides() && two.rest("browDownLeft") == 0f,
                "a neutral saved before the phone's own directions is re-taken, its answers back to the new defaults");
        FaceTuning t2 = FaceTuning.fromJson(mir.toJson());
        check(t2.mirror && FaceTuning.fromJson(null).blinkSync, "settings survive being saved and read back");

        // ── the stage: neutral, lost and found, steady shapes ──
        FaceStage fresh = new FaceStage();
        double t = 0;
        float waited = 0, firstAfter = -1;
        for (int i = 0; i < 12; i++, t += 1 / 15.0) {
            Map<String, Float> fo = fresh.process(face(FaceCalibration.HEAD_YAW, 3f + 16f), t);
            if (!fresh.calibration().isNeutralSet()) waited = Math.max(waited, Math.abs(g(fo, FaceSignals.TURN_X)) + g(fo, FaceSignals.FACE));
            else if (firstAfter < 0) firstAfter = g(fo, FaceSignals.FACE);
        }
        check(waited == 0f && firstAfter >= 0f && firstAfter < 1f,
                "before the first neutral lands the character waits at rest, then eases in (no jump as it lands)");

        FaceStage st = new FaceStage();
        t = 0;
        for (int i = 0; i < 12; i++, t += 1 / 15.0) st.process(face(FaceCalibration.HEAD_YAW, i % 2 == 0 ? 0f : 10f), t);
        check(!st.calibration().isNeutralSet(), "a head on the move is not taken as the neutral");
        for (int i = 0; i < 10; i++, t += 1 / 15.0) st.process(face(), t);
        check(st.calibration().isNeutralSet(), "the first still half-second becomes the neutral by itself");
        st.requestNeutral();
        for (int i = 0; i < 10; i++, t += 1 / 15.0) st.process(face("browDownLeft", 0.5f, "browDownRight", 0.5f), t);
        check(Math.abs(st.calibration().rest("browDownLeft") - 0.5f) < 1e-4, "one tap on Neutral re-takes the neutral");
        st.setCalibration(cal);

        FaceStage s2 = new FaceStage(cal, tun);
        t = 0;
        for (int i = 0; i < 15; i++, t += 1 / 15.0) s2.process(face("jawOpen", 0.4f), t);
        Map<String, Float> o = null;
        float atHold = 0, mouthMax = 0;
        boolean lifeBlink = false;
        double lostAt = t;
        for (int i = 0; i < 15 * 14; i++, t += 1 / 15.0) {
            o = s2.process(null, t);
            Map<String, Float> life = new HashMap<>();
            life.put("life_blinkL", (i % 60) < 3 ? 1f : 0f);
            life.put("life_blinkR", (i % 60) < 3 ? 1f : 0f);
            s2.mergeLife(o, life);
            if (t - lostAt < 0.3) atHold = g(o, FaceSignals.MOUTH);
            if (t - lostAt > 1.4) {
                mouthMax = Math.max(mouthMax, g(o, FaceSignals.MOUTH));
                if (g(o, FaceSignals.BLINK_L) > 0.5f) lifeBlink = true;
            }
        }
        check(atHold > 0.6f, "a face lost for a moment holds (a flicker of tracking is not a loss)");
        check(mouthMax < 0.05f && g(o, FaceSignals.FACE) < 0.05f, "a face gone for good eases to a relaxed rest");
        check(lifeBlink, "while the face is gone the character keeps blinking (never a dead stare)");

        float prev = g(o, FaceSignals.TURN_X), maxStep = 0;
        for (int i = 0; i < 15; i++, t += 1 / 15.0) {
            o = s2.process(face(FaceCalibration.HEAD_YAW, 3f + 32f), t);
            maxStep = Math.max(maxStep, Math.abs(g(o, FaceSignals.TURN_X) - prev));
            prev = g(o, FaceSignals.TURN_X);
        }
        check(maxStep < 0.45f && prev > 0.95f, "a face coming back crosses over to where it is, no jump");

        FaceStage s3 = new FaceStage(cal, tun);
        t = 0;
        s3.process(face("eyeLookOutLeft", 0.5f, "eyeLookInRight", 0.5f), t += 1 / 15.0);
        Map<String, Float> b = s3.process(face("eyeBlinkLeft", 0.95f, "eyeBlinkRight", 0.95f), t += 1 / 15.0);
        check(g(b, FaceSignals.LOOK_X) > 0.6f, "the eyes keep their gaze through a blink (no roll of closed eyes)");
        float v0 = g(s3.process(face(), t += 1 / 15.0), FaceSignals.VIS);
        float v1 = g(s3.process(face("mouthPucker", 0.8f), t += 1 / 15.0), FaceSignals.VIS);
        float v2 = g(s3.process(face(), t += 1 / 15.0), FaceSignals.VIS);
        check(v0 == v1 && v1 == v2, "a one-frame flicker does not change a sheet mouth's shape");
        s3.process(face("mouthPucker", 0.8f), t += 1 / 15.0);
        float v4 = g(s3.process(face("mouthPucker", 0.8f), t += 1 / 15.0), FaceSignals.VIS);
        check(v4 == FaceSignals.shapeIndex("W-Oo"), "a shape that holds does change it");

        // ── smoothing in the pipeline: each signal at its own speed ──
        TrackingParamPipeline pl = new TrackingParamPipeline(5, new FaceStage(cal, tun));
        t = 0;
        for (int i = 0; i < 10; i++, t += 1 / 15.0) pl.process(faceFrame(t, face()));
        // (the loop already stepped t to the next frame: the blink arrives exactly one frame later)
        float blink1 = g(pl.process(faceFrame(t, face("eyeBlinkLeft", 0.95f, "eyeBlinkRight", 0.95f))), FaceSignals.BLINK_L);
        t += 1 / 15.0;
        check(blink1 > 0.8f, "a blink lands in one tracker frame (blinks are barely smoothed)");
        DriverParamSmoother generic = new DriverParamSmoother(OneEuroFilter.DEFAULT_MIN_CUTOFF, OneEuroFilter.DEFAULT_BETA);
        generic.smooth(Collections.singletonMap("b", 0f), 0);
        check(generic.smooth(Collections.singletonMap("b", 1f), 1 / 15.0).get("b") < 0.6f,
                "(the old one-setting smoothing would have softened that blink)");
        long seed = 99;
        double rawVar = 0, outVar = 0;
        float pr = 0, po = 0;
        for (int i = 0; i < 120; i++, t += 1 / 15.0) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            float noise = (float) ((seed >> 33) / (double) (1L << 30) - 1.0) * 1.2f; // ±1.2° jitter
            Map<String, Float> out = pl.process(faceFrame(t, face(FaceCalibration.HEAD_YAW, 3f + noise)));
            float tx = g(out, FaceSignals.TURN_X), rx = noise / 32f;
            if (i > 10) { rawVar += (rx - pr) * (rx - pr); outVar += (tx - po) * (tx - po); }
            pr = rx; po = tx;
        }
        check(outVar < rawVar * 0.25, "a still head does not jitter (" + String.format("%.0f", 100 * outVar / rawVar) + "% of the raw jitter)");

        // ── the screen side: no stair steps, no lag ──
        FaceFollower fol = new FaceFollower();
        long now = 0, delivered = 0;
        float last = 0, maxD = 0, maxLag = 0;
        Map<String, Float> tgt = new HashMap<>();
        for (int f = 0; f < 180; f++) {
            now = f * 16_666_667L;
            if (now - delivered >= 66_666_667L || f == 0) {
                delivered = now;
                tgt = new HashMap<>(Collections.singletonMap(FaceSignals.TURN_X, (float) (0.8 * Math.sin(now / 1e9 * 2.2))));
            }
            float x = fol.sample(tgt, delivered, now).get(FaceSignals.TURN_X);
            float truth = (float) (0.8 * Math.sin(now / 1e9 * 2.2));
            if (f > 30) { maxD = Math.max(maxD, Math.abs(x - last)); maxLag = Math.max(maxLag, Math.abs(x - truth)); }
            last = x;
        }
        check(maxLag < 0.06f, "between tracker frames the character keeps up with the face (off by at most "
                + String.format("%.3f", maxLag) + ")");
        check(maxD < 0.045f, "and moves in a smooth line, not 15 little steps a second");
        FaceFollower stop = new FaceFollower();
        float peak = 0, settled = 0;
        delivered = 0;
        for (int f = 0; f < 120; f++) {
            now = f * 16_666_667L;
            if (now - delivered >= 66_666_667L || f == 0) {
                delivered = now;
                tgt = Collections.singletonMap(FaceSignals.TURN_X, (float) Math.min(0.6, now / 1e9)); // 1/s, then stops
            }
            float x = stop.sample(tgt, delivered, now).get(FaceSignals.TURN_X);
            peak = Math.max(peak, x);
            settled = x;
        }
        check(peak < 0.6f + 0.08f && Math.abs(settled - 0.6f) < 0.01f,
                "a head that stops runs past by at most a hair and settles (peak " + String.format("%.3f", peak) + ")");

        // ── takes in passes, editable afterwards ──
        AvatarParamTrack first = new AvatarParamTrack();
        for (int ms = 0; ms <= 3000; ms += 33) {
            Map<String, Float> s = new HashMap<>();
            s.put(FaceSignals.TURN_X, (float) Math.sin(ms / 400.0));
            s.put(FaceSignals.MOUTH, 0.2f);
            s.put(FaceSignals.SMILE_L, 0.4f);
            first.add(ms, s);
        }
        FacePass pass = new FacePass(first, Collections.singleton(FaceSignals.LANE_MOUTH));
        for (int ms = 1000; ms <= 2000; ms += 33) {
            Map<String, Float> live = new HashMap<>();
            live.put(FaceSignals.MOUTH, 0.9f);
            live.put(FaceSignals.SMILE_L, 0.4f);
            live.put(FaceSignals.TURN_X, -1f); // the head moved during the mouth pass: must not be written
            pass.add(ms, live);
        }
        AvatarParamTrack after = pass.finish();
        boolean headSame = true;
        for (int i = 0; i < first.size(); i++) {
            long ms = first.timeMsAt(i);
            if (Math.abs(after.sampleAt(ms).get(FaceSignals.TURN_X) - first.sampleAt(ms).get(FaceSignals.TURN_X)) > 1e-6) headSame = false;
        }
        check(headSame, "a mouth pass leaves the head exactly as it was, sample for sample");
        check(Math.abs(after.sampleAt(1500).get(FaceSignals.MOUTH) - 0.9f) < 1e-4
                        && Math.abs(after.sampleAt(500).get(FaceSignals.MOUTH) - 0.2f) < 1e-4
                        && Math.abs(after.sampleAt(2600).get(FaceSignals.MOUTH) - 0.2f) < 1e-4,
                "the pass writes its own lane, only while it ran");
        float edge = after.sampleAt(2000 + FacePass.FADE_MS / 2).get(FaceSignals.MOUTH);
        check(edge > 0.25f && edge < 0.85f, "and hands back to the old take over a quarter second (no snap)");
        AvatarParamTrack louder = FacePass.scaleLane(after, FaceSignals.LANE_MOUTH, 2f);
        check(Math.abs(louder.sampleAt(500).get(FaceSignals.SMILE_L) - 0.8f) < 1e-4
                        && Math.abs(louder.sampleAt(500).get(FaceSignals.TURN_X) - after.sampleAt(500).get(FaceSignals.TURN_X)) < 1e-6,
                "afterwards: a stronger smile without recording again, the head untouched");
        AvatarParamTrack cleared = FacePass.clearLane(after, FaceSignals.LANE_MOUTH);
        check(cleared.sampleAt(500).get(FaceSignals.MOUTH) == null && cleared.sampleAt(500).get(FaceSignals.TURN_X) != null,
                "afterwards: one lane cleared, the rest kept");
        AvatarParamTrack later = FacePass.shiftLane(after, FaceSignals.LANE_MOUTH, 100);
        check(Math.abs(later.sampleAt(1050).get(FaceSignals.MOUTH) - after.sampleAt(950).get(FaceSignals.MOUTH)) < 0.02f,
                "afterwards: the mouth nudged later against the voice");
        AvatarParamTrack shapes = new AvatarParamTrack();
        shapes.add(0, Collections.singletonMap(FaceSignals.VIS, 1f));
        shapes.add(100, Collections.singletonMap(FaceSignals.VIS, 4f));
        check(shapes.sampleAt(50).get(FaceSignals.VIS) == 1f,
                "on playback a sheet mouth steps from shape to shape (never flashes the shapes between)");

        facePins();
        lids();
        leanTilt();
        near();
        shoulders();

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    // ── face pins (the owner's idea, 11-22): a point of the face drives a pin of the character ──

    /** A made-up face of 478 points (a lumpy oval, depth on a dome), deterministic. */
    static float[] cloud() {
        float[] p = new float[com.fadcam.ui.faditor.avatar.face.FacePoints.COUNT * 3];
        long seed = 12345;
        for (int i = 0; i < p.length / 3; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            double a = ((seed >>> 11) & 0xFFFFF) / (double) 0xFFFFF * Math.PI * 2;
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            double r = 0.2 + 0.8 * ((seed >>> 11) & 0xFFFFF) / (double) 0xFFFFF;
            double x = Math.cos(a) * r * 0.08, y = Math.sin(a) * r * 0.11;
            p[i * 3] = (float) (0.5 + x);
            p[i * 3 + 1] = (float) (0.6 + y);
            p[i * 3 + 2] = (float) (-0.06 * (1 - r * r));
        }
        return p;
    }

    /** The cloud turned (yaw, pitch, roll in degrees) about its middle, scaled and moved, like a head. */
    static float[] posed(float[] p, double yaw, double pitch, double roll, double scale, double dx, double dy) {
        double cy = Math.cos(Math.toRadians(yaw)), sy = Math.sin(Math.toRadians(yaw));
        double cp = Math.cos(Math.toRadians(pitch)), sp = Math.sin(Math.toRadians(pitch));
        double cr = Math.cos(Math.toRadians(roll)), sr = Math.sin(Math.toRadians(roll));
        float[] o = new float[p.length];
        for (int i = 0; i < p.length / 3; i++) {
            double x = p[i * 3] - 0.5, y = p[i * 3 + 1] - 0.6, z = p[i * 3 + 2];
            double x1 = cy * x + sy * z, z1 = -sy * x + cy * z;            // yaw about y
            double y1 = cp * y - sp * z1, z2 = sp * y + cp * z1;           // pitch about x
            double x2 = cr * x1 - sr * y1, y2 = sr * x1 + cr * y1;         // roll about z
            o[i * 3] = (float) (0.5 + dx + x2 * scale);
            o[i * 3 + 1] = (float) (0.6 + dy + y2 * scale);
            o[i * 3 + 2] = (float) (z2 * scale);
        }
        return o;
    }

    static void facePins() {
        int corner = 61;                                   // the person's right mouth corner
        int[] chosen = {corner, 291, 0, 159};
        float[] base = cloud();
        float[] rest = com.fadcam.ui.faditor.avatar.face.FacePoints.normalize(base);
        float[] turned = com.fadcam.ui.faditor.avatar.face.FacePoints.normalize(posed(base, 28, -12, 15, 1.35, 0.07, -0.04));
        float[] off = com.fadcam.ui.faditor.avatar.face.FacePoints.offsets(rest, turned, chosen, -1);
        float worst = 0;
        for (float v : off) worst = Math.max(worst, Math.abs(v));
        check(worst < 0.002f, "face pins: turning, tilting, nodding and coming closer move no pin (worst "
                + String.format("%.4f", worst) + ")");

        float[] smiling = base.clone();
        smiling[corner * 3] -= 0.01f;                      // toward the picture's left = the person's right
        smiling[corner * 3 + 1] -= 0.006f;                 // and up: a smile
        float[] smileTurned = com.fadcam.ui.faditor.avatar.face.FacePoints.normalize(posed(smiling, 20, 8, -10, 0.9, -0.05, 0.02));
        float[] so = com.fadcam.ui.faditor.avatar.face.FacePoints.offsets(rest, smileTurned, chosen, -1);
        float steadySpread = spread(base);
        boolean right = so[0] > 0 && Math.abs(so[0] - 0.01f / steadySpread) < 0.01f / steadySpread * 0.1f;
        boolean up = so[1] < 0 && Math.abs(so[1] + 0.006f / steadySpread) < 0.006f / steadySpread * 0.1f;
        boolean others = Math.abs(so[2]) < 0.002f && Math.abs(so[3]) < 0.002f && Math.abs(so[6]) < 0.002f;
        check(right && up && others, "face pins: a smile moves its mouth-corner pin out and up (to the screen's right, "
                + "like a reflection), even with the head turned, and nothing else");

        // through the stage: a pin is put out only once it is watched, eases away with a lost face
        FaceCalibration.Capture cap = new FaceCalibration.Capture(FaceCalibration.none(), 4);
        for (int i = 0; i < 4; i++) cap.add(face(), rest);
        FaceCalibration cal = cap.result();
        check(cal.restPoints() != null && FaceCalibration.fromJson(cal.toJson()).restPoints() != null,
                "face pins: the neutral keeps where every point rests (and that is saved)");
        FaceStage st = new FaceStage(cal, new FaceTuning());
        double t = 0;
        Map<String, Float> o = st.process(face(), smiling, t);
        check(!o.containsKey(FaceSignals.pointX(corner)), "face pins: an unwatched point costs nothing and puts out nothing");
        st.setWatchedPoints(new int[]{corner});
        o = st.process(face(), smiling, t += 1 / 15.0);
        check(Math.abs(g(o, FaceSignals.pointX(corner)) - so[0]) < 0.002f, "face pins: a watched point is put out with the face");
        for (int i = 0; i < 40; i++) o = st.process(null, null, t += 1 / 15.0);
        check(Math.abs(g(o, FaceSignals.pointX(corner))) < 0.002f, "face pins: a lost face lets its pins settle back to rest");
    }

    // ── the lids (the owner, 11-22): eyes held shut showed a third closed, blinks barely registered ──

    /** The owner's eye test on the Note 9 (Chrome, 11-22), frame values as the tracker gave them. */
    static Map<String, Float> eyes(float blinkL, float blinkR, float lookDown, float gapL, float gapR) {
        return face("eyeBlinkLeft", blinkL, "eyeBlinkRight", blinkR, "eyeLookDownLeft", lookDown, "eyeLookDownRight", lookDown,
                "eyeGapLeft", gapL, "eyeGapRight", gapR);
    }

    static void lids() {
        FaceCalibration.Capture c = new FaceCalibration.Capture(FaceCalibration.none(), 4);
        for (int i = 0; i < 4; i++) c.add(eyes(0.15f, 0.18f, 0.26f, 0.32f, 0.30f));
        FaceCalibration cal = c.result();
        FaceTuning tun = new FaceTuning();
        Map<String, Float> shut = FaceMapper.map(eyes(0.66f, 0.82f, 0.75f, 0.03f, 0.046f), cal, tun);
        check(g(shut, FaceSignals.BLINK_L) > 0.95f && g(shut, FaceSignals.BLINK_R) > 0.95f,
                "lids: eyes held shut close all the way (the tracker's own blink value stops near two thirds, and calls it looking down)");
        Map<String, Float> quick = FaceMapper.map(eyes(0.54f, 0.58f, 0.57f, 0.097f, 0.095f), cal, tun);
        check(g(quick, FaceSignals.BLINK_L) > 0.85f && g(quick, FaceSignals.BLINK_R) > 0.85f,
                "lids: a quick blink lands (it showed 30% before)");
        Map<String, Float> wink = FaceMapper.map(eyes(0.62f, 0.29f, 0.47f, 0.105f, 0.24f), cal, tun);
        check(g(wink, FaceSignals.BLINK_L) > 0.85f && g(wink, FaceSignals.BLINK_R) < 0.1f,
                "lids: a wink of the right eye closes the character's left, like a reflection, and only that one");
        FaceTuning mir = new FaceTuning(); mir.mirror = true;
        Map<String, Float> winkM = FaceMapper.map(eyes(0.62f, 0.29f, 0.47f, 0.105f, 0.24f), cal, mir);
        check(g(winkM, FaceSignals.BLINK_R) > 0.85f && g(winkM, FaceSignals.BLINK_L) < 0.1f, "lids: Mirror off swaps the winking eye");
        Map<String, Float> smile = FaceMapper.map(eyes(0.20f, 0.26f, 0.42f, 0.257f, 0.279f), cal, tun);
        check(g(smile, FaceSignals.BLINK_L) < 0.1f && g(smile, FaceSignals.BLINK_R) < 0.1f, "lids: a big smile's narrowed eyes stay open");
        Map<String, Float> down = FaceMapper.map(eyes(0.35f, 0.38f, 0.8f, 0.24f, 0.225f), cal, tun);
        check(g(down, FaceSignals.BLINK_L) < 0.1f, "lids: looking down (the lids follow the eyes a quarter of the way) is not a blink");
        Map<String, Float> half = FaceMapper.map(eyes(0.3f, 0.3f, 0.3f, 0.16f, 0.15f), cal, tun);
        check(g(half, FaceSignals.BLINK_L) > 0.15f && g(half, FaceSignals.BLINK_L) < 0.85f, "lids: a half-closed lid is half closed");

        // through the stage: the gap measured on the face's points, its neutral kept with the rest
        float[] open = eyeCloud(1f, 1f), closed = eyeCloud(0.044f, 0.044f);
        FaceStage st = new FaceStage(FaceCalibration.none(), new FaceTuning());
        double t = 0;
        for (int i = 0; i < 12; i++) st.process(face(), open, t += 1 / 15.0);
        Map<String, Float> o = null;
        for (int i = 0; i < 6; i++) o = st.process(face("eyeBlinkLeft", 0.66f, "eyeBlinkRight", 0.66f,
                "eyeLookDownLeft", 0.75f, "eyeLookDownRight", 0.75f), closed, t += 1 / 15.0);
        check(g(o, FaceSignals.BLINK_L) > 0.9f && g(o, FaceSignals.BLINK_R) > 0.9f,
                "lids: the stage measures the lids on the face's points, from the neutral it took itself");
        FaceStage wk = new FaceStage(FaceCalibration.none(), new FaceTuning());
        t = 0;
        for (int i = 0; i < 12; i++) wk.process(face(), open, t += 1 / 15.0);
        for (int i = 0; i < 6; i++) o = wk.process(face(), eyeCloud(0.05f, 1f), t += 1 / 15.0);
        check(g(o, FaceSignals.BLINK_L) > 0.9f && g(o, FaceSignals.BLINK_R) < 0.1f,
                "lids: a wink measured on the points closes the right eye's side (the character's left, like a reflection)");

        // a neutral saved before the lids were measured: taken again by itself, the direction answers kept
        FaceCalibration before = neutral().withSign(FaceCalibration.HEAD_ROLL, -1);
        FaceStage again = new FaceStage(before, new FaceTuning());
        t = 0;
        for (int i = 0; i < 12; i++) again.process(face(), open, t += 1 / 15.0);
        FaceCalibration now = again.calibration();
        check(now.rest(FacePoints.GAP_LEFT) > 0.1f && now.signRoll() == -1,
                "lids: a neutral saved before (no lids in it) is taken again by itself, the direction answers kept");
    }

    // ── lean apart from a head tilt (the owner's first test, 11-22: a 15° tilt moved lean more than his lean did) ──
    static void leanTilt() {
        FaceCalibration cal = neutral();
        FaceTuning tun = new FaceTuning();
        // his right-shoulder tilt: about 15°, the face 0.95 x sin(15°) of its width toward his right (the picture's left)
        float swing = (float) (0.95 * Math.sin(Math.toRadians(15.3))) * 0.3f;
        Map<String, Float> tilt = FaceMapper.map(face(FaceCalibration.HEAD_ROLL, 15.3f, FaceCalibration.FACE_X, 0.5f - swing), cal, tun);
        check(Math.abs(g(tilt, FaceSignals.LEAN_X)) < 0.05f && g(tilt, FaceSignals.ROLL) > 0.3f,
                "lean: tilting the head toward a shoulder is a tilt, not a lean (the swing about the neck is taken out)");
        Map<String, Float> lean = FaceMapper.map(face(FaceCalibration.FACE_X, 0.5f - swing), cal, tun);
        check(g(lean, FaceSignals.LEAN_X) > 0.3f, "lean: the same move with the head upright is a lean to the screen's right, like a reflection");
    }

    // ── closer and further (the owner's first test, 11-22: a nod or a tilt moved "near" as much as coming closer) ──
    static void near() {
        float[] base = cloud();
        FaceStage st = new FaceStage(FaceCalibration.none(), new FaceTuning());
        double t = 0;
        for (int i = 0; i < 12; i++) st.process(face(), base, t += 1 / 15.0);
        Map<String, Float> o = null;
        // the outline reads a quarter wider when the head tips (his numbers); the face has not come any closer
        for (int i = 0; i < 8; i++) o = st.process(face(FaceCalibration.FACE_SIZE, 0.38f), posed(base, 25, 15, 20, 1, 0, 0), t += 1 / 15.0);
        check(Math.abs(g(o, FaceSignals.NEAR)) < 0.05f, "near: turning, nodding or tilting the head is not coming closer (read on the face's points)");
        for (int i = 0; i < 8; i++) o = st.process(face(), posed(base, 0, 0, 0, 1.2, 0, 0), t += 1 / 15.0);
        check(g(o, FaceSignals.NEAR) > 0.4f, "near: coming closer is closer");
        for (int i = 0; i < 8; i++) o = st.process(face(), posed(base, 0, 0, 0, 0.85, 0, 0), t += 1 / 15.0);
        check(g(o, FaceSignals.NEAR) < -0.3f, "near: going further is further");
        FaceCalibration.Capture c = new FaceCalibration.Capture(FaceCalibration.none(), 4);
        for (int i = 0; i < 4; i++) c.add(eyes(0.15f, 0.18f, 0.26f, 0.32f, 0.30f));
        FaceStage again = new FaceStage(c.result(), new FaceTuning());
        for (int i = 0; i < 12; i++) again.process(face(), base, t += 1 / 15.0);
        check(again.calibration().rest(FaceCalibration.FACE_SPREAD) > 0f,
                "near: a neutral saved with the lids but without the face's size is taken again by itself");
    }

    // ── shoulders (SHRUG-1, the owner, 11-22: "get the shoulders animated for shoulder shrugs") ──

    static final float TALL = 0.75f;

    /**
     * A body as the tracker gives it (33 points: x, y, visibility, 0..1 of the picture), the person facing an unmirrored
     * front camera: their right shoulder on the picture's left. {@code liftR}, {@code liftL}: the person's right and left
     * shoulder raised by this much of the picture's height; the ears tipped {@code tiltDeg} about their middle and moved
     * {@code earDy}; the whole body scaled by {@code scale} and moved by {@code dx} about the picture's middle.
     */
    static float[] body(float liftR, float liftL, float tiltDeg, float earDy, float scale, float dx, float vis) {
        float[] p = new float[ShoulderStage.COUNT * 3];
        for (int i = 0; i < ShoulderStage.COUNT; i++) { p[i * 3] = 0.5f; p[i * 3 + 1] = 0.5f; p[i * 3 + 2] = 0.9f; }
        double a = Math.toRadians(tiltDeg), ca = Math.cos(a), sa = Math.sin(a);
        float emx = 0.5f, emy = 0.30f + earDy, half = 0.05f;
        // ears: the person's left ear (7) on the picture's right; tipped about their middle (y in width units for the turn)
        float[][] ears = {{ShoulderStage.EAR_L, half}, {ShoulderStage.EAR_R, -half}};
        for (float[] e : ears) {
            int i = (int) e[0];
            p[i * 3] = (float) (emx + e[1] * ca);
            p[i * 3 + 1] = (float) (emy + e[1] * sa / TALL);
        }
        p[ShoulderStage.SHOULDER_L * 3] = 0.72f; p[ShoulderStage.SHOULDER_L * 3 + 1] = 0.62f - liftL;
        p[ShoulderStage.SHOULDER_R * 3] = 0.28f; p[ShoulderStage.SHOULDER_R * 3 + 1] = 0.62f - liftR;
        p[ShoulderStage.SHOULDER_L * 3 + 2] = vis; p[ShoulderStage.SHOULDER_R * 3 + 2] = vis;
        for (int i = 0; i < ShoulderStage.COUNT; i++) {
            p[i * 3] = 0.5f + (p[i * 3] - 0.5f) * scale + dx;
            p[i * 3 + 1] = 0.5f + (p[i * 3 + 1] - 0.5f) * scale;
        }
        return p;
    }

    static float[] rest() { return body(0, 0, 0, 0, 1, 0, 0.95f); }

    /** A stage that has taken its rest, fed {@code then} for a second at ten readings a second; returns the last output. */
    static Map<String, Float> settle(float[] then, boolean mirror) {
        ShoulderStage st = new ShoulderStage();
        double t = 0;
        for (int i = 0; i < 8; i++) st.process(ShoulderStage.drops(rest(), TALL), t += 0.1, mirror);
        Map<String, Float> o = null;
        for (int i = 0; i < 10; i++) o = st.process(ShoulderStage.drops(then, TALL), t += 0.1, mirror);
        return o;
    }

    static void shoulders() {
        String L = ShoulderStage.SHRUG_L, R = ShoulderStage.SHRUG_R;
        Map<String, Float> both = settle(body(0.1f, 0.1f, 0, 0, 1, 0, 0.95f), false);
        check(g(both, L) > 0.95f && g(both, R) > 0.95f && g(both, ShoulderStage.SHRUG) > 0.95f, "shoulders: a full shrug lifts both sides all the way");
        Map<String, Float> half = settle(body(0.045f, 0.045f, 0, 0, 1, 0, 0.95f), false);
        check(g(half, L) > 0.2f && g(half, L) < 0.8f, "shoulders: half a shrug is half (continuous)");
        Map<String, Float> right = settle(body(0.1f, 0, 0, 0, 1, 0, 0.95f), false);
        check(g(right, L) > 0.95f && g(right, R) < 0.02f,
                "shoulders: the right shoulder alone lifts the character's left, like a reflection, and only that one");
        Map<String, Float> rightM = settle(body(0.1f, 0, 0, 0, 1, 0, 0.95f), true);
        check(g(rightM, R) > 0.95f && g(rightM, L) < 0.02f, "shoulders: Mirror off swaps the side");
        Map<String, Float> tilt = settle(body(0, 0, 22, 0, 1, 0, 0.95f), false);
        check(g(tilt, L) < 0.02f && g(tilt, R) < 0.02f, "shoulders: tilting the head toward a shoulder is no shrug");
        Map<String, Float> nod = settle(body(0, 0, 0, 0.01f, 1, 0, 0.95f), false);
        check(g(nod, L) < 0.02f && g(nod, R) < 0.02f, "shoulders: a nod is no shrug");
        Map<String, Float> near = settle(body(0, 0, 0, 0, 1.35f, 0.06f, 0.95f), false);
        check(g(near, L) < 0.02f && g(near, R) < 0.02f, "shoulders: coming closer or leaning aside is no shrug");
        Map<String, Float> nearShrug = settle(body(0.1f, 0.1f, 0, 0, 1.35f, 0, 0.95f), false);
        check(g(nearShrug, L) > 0.95f, "shoulders: closer to the camera, a shrug is still a shrug");

        // the rest, jitter, lost and found
        ShoulderStage st = new ShoulderStage();
        double t = 0;
        Map<String, Float> o = st.process(ShoulderStage.drops(body(0.1f, 0.1f, 0, 0, 1, 0, 0.95f), TALL), t += 0.1, false);
        check(g(o, L) == 0f && !st.hasNeutral(), "shoulders: nothing moves until a rest has been taken");
        for (int i = 0; i < 8; i++) st.process(ShoulderStage.drops(rest(), TALL), t += 0.1, false);
        check(st.hasNeutral(), "shoulders: the first still half-second becomes the rest by itself");
        ShoulderStage mv = new ShoulderStage();
        double tm = 0;
        for (int i = 0; i < 12; i++) mv.process(ShoulderStage.drops(body(i % 2 == 0 ? 0.1f : 0f, 0, 0, 0, 1, 0, 0.95f), TALL), tm += 0.1, false);
        check(!mv.hasNeutral(), "shoulders: a body that keeps moving is never taken as the rest");
        java.util.Random rnd = new java.util.Random(7);
        float worst = 0;
        for (int i = 0; i < 40; i++) {
            float j = (rnd.nextFloat() - 0.5f) * 0.012f;
            o = st.process(ShoulderStage.drops(body(j, -j, 0, 0, 1, 0, 0.95f), TALL), t += 0.1, false);
            worst = Math.max(worst, Math.max(g(o, L), g(o, R)));
        }
        check(worst < 0.02f, "shoulders: the tracker's jitter and breathing make no shrug");
        for (int i = 0; i < 10; i++) o = st.process(ShoulderStage.drops(body(0.1f, 0.1f, 0, 0, 1, 0, 0.95f), TALL), t += 0.1, false);
        float held = 0, gone = 1;
        for (int i = 0; i < 15; i++) {
            o = st.process(ShoulderStage.drops(body(0.1f, 0.1f, 0, 0, 1, 0, 0.2f), TALL), t += 0.1, false);
            if (i == 2) held = g(o, L);
            gone = g(o, L);
        }
        check(held > 0.9f && gone < 0.02f, "shoulders: out of the picture they hold a moment, then ease to rest");
        o = st.process(ShoulderStage.drops(body(0.1f, 0.1f, 0, 0, 1, 0, 0.95f), TALL), t += 0.1, false);
        float back1 = g(o, L);
        for (int i = 0; i < 6; i++) o = st.process(ShoulderStage.drops(body(0.1f, 0.1f, 0, 0, 1, 0, 0.95f), TALL), t += 0.1, false);
        check(back1 < 0.5f && g(o, L) > 0.9f, "shoulders: back in the picture they ease in (no jump)");
        st.requestNeutral();
        for (int i = 0; i < 8; i++) o = st.process(ShoulderStage.drops(body(0.1f, 0.1f, 0, 0, 1, 0, 0.95f), TALL), t += 0.1, false);
        check(g(o, L) < 0.05f, "shoulders: Re-centre takes the rest again");

        // sensitivity (the owner, 11-22: "Shoulder sensitivity should be on a slider")
        ShoulderStage keen = new ShoulderStage(), dull = new ShoulderStage();
        keen.setSensitivity(2f); dull.setSensitivity(0.5f);
        double tk = 0;
        Map<String, Float> ok = null, od = null;
        for (int i = 0; i < 8; i++) { keen.process(ShoulderStage.drops(rest(), TALL), tk += 0.1, false); dull.process(ShoulderStage.drops(rest(), TALL), tk, false); }
        for (int i = 0; i < 10; i++) { ok = keen.process(ShoulderStage.drops(body(0.045f, 0.045f, 0, 0, 1, 0, 0.95f), TALL), tk += 0.1, false); od = dull.process(ShoulderStage.drops(body(0.1f, 0.1f, 0, 0, 1, 0, 0.95f), TALL), tk, false); }
        check(g(ok, L) > 0.7f && g(od, L) < 0.8f, "shoulders: sensitivity up, half a shrug reads nearly full; down, a full shrug reads less");
        float worstKeen = 0;
        for (int i = 0; i < 30; i++) { float j = (rnd.nextFloat() - 0.5f) * 0.036f; ok = keen.process(ShoulderStage.drops(body(j, -j, 0, 0, 1, 0, 0.95f), TALL), tk += 0.1, false); worstKeen = Math.max(worstKeen, g(ok, L)); }
        check(worstKeen < 0.02f, "shoulders: sensitivity up does not turn jitter into shrugs");
    }

    /** {@link #cloud()} with two eyes drawn in, each lid gap a share of open (1 open, 0 shut): the person's right eye first. */
    static float[] eyeCloud(float right, float left) {
        float[] p = cloud();
        int[][] lid = {{159, 145}, {160, 144}, {158, 153}, {386, 374}, {385, 380}, {387, 373}};
        int[][] corners = {{33, 133}, {263, 362}};
        for (int e = 0; e < 2; e++) {
            int a = corners[e][0], b = corners[e][1];
            float x0 = e == 0 ? 0.455f : 0.545f, w = 0.03f, y = 0.57f, half = 0.0045f * (e == 0 ? right : left);
            p[a * 3] = x0 - w / 2; p[a * 3 + 1] = y; p[b * 3] = x0 + w / 2; p[b * 3 + 1] = y;
            p[a * 3 + 2] = p[b * 3 + 2] = 0f;
            for (int k = 0; k < 3; k++) {
                int up = lid[e * 3 + k][0], lo = lid[e * 3 + k][1];
                float x = x0 + (k - 1) * w / 4;
                p[up * 3] = x; p[up * 3 + 1] = y - half; p[lo * 3] = x; p[lo * 3 + 1] = y + half;
                p[up * 3 + 2] = p[lo * 3 + 2] = 0f;
            }
        }
        return p;
    }

    static float spread(float[] p) {
        int[] steady = {10, 151, 9, 8, 168, 6, 197, 195, 5, 4, 1, 33, 263, 133, 362, 234, 454, 127, 356, 21, 251, 54, 284};
        double cx = 0, cy = 0, cz = 0;
        for (int i : steady) { cx += p[i * 3]; cy += p[i * 3 + 1]; cz += p[i * 3 + 2]; }
        cx /= steady.length; cy /= steady.length; cz /= steady.length;
        double ss = 0;
        for (int i : steady) ss += Math.pow(p[i * 3] - cx, 2) + Math.pow(p[i * 3 + 1] - cy, 2) + Math.pow(p[i * 3 + 2] - cz, 2);
        return (float) Math.sqrt(ss / steady.length);
    }

    static TrackingFrame faceFrame(double t, Map<String, Float> raw) {
        return new TrackingFrame(t, raw, Float.NaN, TrackingFrame.NO_VISEME, TrackingFrame.FACE_FOUND, -1L);
    }
}

import com.fadcam.ui.faditor.avatar.AvatarParamTrack;
import com.fadcam.ui.faditor.avatar.DriverParamSmoother;
import com.fadcam.ui.faditor.avatar.OneEuroFilter;
import com.fadcam.ui.faditor.avatar.TrackingFrame;
import com.fadcam.ui.faditor.avatar.TrackingParamPipeline;
import com.fadcam.ui.faditor.avatar.face.FaceCalibration;
import com.fadcam.ui.faditor.avatar.face.FaceFollower;
import com.fadcam.ui.faditor.avatar.face.FaceMapper;
import com.fadcam.ui.faditor.avatar.face.FacePass;
import com.fadcam.ui.faditor.avatar.face.FaceSignals;
import com.fadcam.ui.faditor.avatar.face.FaceStage;
import com.fadcam.ui.faditor.avatar.face.FaceTuning;

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
        FaceTuning t2 = FaceTuning.fromJson(mir.toJson());
        check(t2.mirror && FaceTuning.fromJson(null).blinkSync, "settings survive being saved and read back");

        // ── the stage: neutral, lost and found, steady shapes ──
        FaceStage st = new FaceStage();
        double t = 0;
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

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static TrackingFrame faceFrame(double t, Map<String, Float> raw) {
        return new TrackingFrame(t, raw, Float.NaN, TrackingFrame.NO_VISEME, TrackingFrame.FACE_FOUND, -1L);
    }
}

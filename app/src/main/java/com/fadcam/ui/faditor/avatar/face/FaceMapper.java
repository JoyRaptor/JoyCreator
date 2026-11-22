package com.fadcam.ui.faditor.avatar.face;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

/**
 * FACE-1: one tracked face (the 52 MediaPipe expression values and three head angles) to the
 * continuous face signals of {@link FaceSignals}. Pure and stateless: the same numbers in give the
 * same face out, on the phone and in the web pilot ({@code al-face.js} is this, line for line).
 *
 * <p>What makes it more than a pass-through:</p>
 * <ul>
 *   <li>Everything is measured from this person's neutral ({@link FaceCalibration}).</li>
 *   <li>Two sides stay two sides: a wink, one raised brow, a lopsided smile. Mirroring is decided
 *       once here: by default the character moves like your reflection (your right eye closes the
 *       eye on the screen's right, which is a character's own left).</li>
 *   <li>Blinks are continuous with a firm end (a slow lid is a slow lid, a blink still lands), the
 *       false blink of looking down is taken out, and the two eyes blink together unless one
 *       clearly winks ({@link FaceTuning#blinkSync}).</li>
 *   <li>Eyes read from both eyes; brows from the outer, inner and lowering muscles; the mouth as
 *       opening, smile, frown, pucker, "oh", wide, pressed, sideways, puffed cheeks and sneer.</li>
 * </ul>
 */
public final class FaceMapper {

    private FaceMapper() {}

    /** Degrees for a full -1..1 swing of the new head signals (a face looking at a phone). */
    static final float TURN_DEG = 32f, NOD_DEG = 24f, ROLL_DEG = 35f;
    /** The old rigs' scale, kept so their head grids feel exactly as before. */
    static final float LEGACY_YAW_DEG = 45f, LEGACY_PITCH_DEG = 30f;

    @NonNull
    public static Map<String, Float> map(@NonNull Map<String, Float> raw, @NonNull FaceCalibration cal,
                                         @NonNull FaceTuning tun) {
        Side s = new Side(raw, cal, tun.mirror);
        Map<String, Float> o = new HashMap<>();
        float flip = tun.mirror ? -1f : 1f;

        // ── head ── (+ = the person's right / down / right-shoulder tilt after calibration)
        float yawR = cal.headDeg(FaceCalibration.HEAD_YAW, s.raw(FaceCalibration.HEAD_YAW));
        float down = cal.headDeg(FaceCalibration.HEAD_PITCH, s.raw(FaceCalibration.HEAD_PITCH));
        float tiltR = cal.headDeg(FaceCalibration.HEAD_ROLL, s.raw(FaceCalibration.HEAD_ROLL));
        o.put(FaceSignals.TURN_X, sgn(flip * yawR / TURN_DEG * tun.head));
        o.put(FaceSignals.TURN_Y, sgn(down / NOD_DEG * tun.head));
        o.put(FaceSignals.ROLL, sgn(flip * tiltR / ROLL_DEG * tun.head));
        o.put(FaceSignals.YAW, sgn(flip * yawR / LEGACY_YAW_DEG * tun.head));
        o.put(FaceSignals.PITCH, sgn(-down / LEGACY_PITCH_DEG * tun.head));

        // ── lean: the face's place in the picture against neutral, in face widths ──
        float[] lean = raw.containsKey(FaceCalibration.FACE_SIZE)
                ? cal.lean(s.raw(FaceCalibration.FACE_X), s.raw(FaceCalibration.FACE_Y), s.raw(FaceCalibration.FACE_SIZE))
                : null;
        o.put(FaceSignals.LEAN_X, lean == null ? 0f : sgn(flip * lean[0] * 1.5f * tun.head));
        o.put(FaceSignals.LEAN_Y, lean == null ? 0f : sgn(lean[1] * 1.5f * tun.head));
        o.put(FaceSignals.NEAR, lean == null ? 0f : sgn(lean[2] * 2.5f * tun.head));

        // ── eyes ──
        float lookDown = (s.amt("eyeLookDownLeft") + s.amt("eyeLookDownRight")) / 2f;
        float lookUp = (s.amt("eyeLookUpLeft") + s.amt("eyeLookUpRight")) / 2f;
        // toward the person's right: the right eye looks out, the left eye looks in
        float towardR = ((s.personR("eyeLookOut") - s.personR("eyeLookIn"))
                + (s.personL("eyeLookIn") - s.personL("eyeLookOut"))) / 2f;
        o.put(FaceSignals.LOOK_X, sgn(flip * towardR * 1.6f * tun.eyes));
        o.put(FaceSignals.LOOK_Y, sgn((lookDown - lookUp) * 1.6f * tun.eyes));

        float cl = s.charL("eyeBlink"), cr = s.charR("eyeBlink");
        if (tun.blinkSync) { // on the lids' own closure, before the blink curve sharpens any difference
            float avg = (cl + cr) / 2f, w = smoothstep(0.15f, 0.35f, Math.abs(cl - cr));
            cl = avg + (cl - avg) * w;
            cr = avg + (cr - avg) * w;
        }
        float bl = blink(cl, lookDown), br = blink(cr, lookDown);
        o.put(FaceSignals.BLINK_L, amt(bl));
        o.put(FaceSignals.BLINK_R, amt(br));
        o.put(FaceSignals.SQUINT_L, amt((s.charL("eyeSquint") + 0.5f * s.charL("cheekSquint")) * tun.eyes));
        o.put(FaceSignals.SQUINT_R, amt((s.charR("eyeSquint") + 0.5f * s.charR("cheekSquint")) * tun.eyes));
        o.put(FaceSignals.WIDE_L, amt(s.charL("eyeWide") * 1.3f * tun.eyes));
        o.put(FaceSignals.WIDE_R, amt(s.charR("eyeWide") * 1.3f * tun.eyes));

        // ── brows ──
        float inner = s.amt("browInnerUp");
        o.put(FaceSignals.BROW_L, sgn((0.7f * s.charL("browOuterUp") + 0.5f * inner - 1.2f * s.charL("browDown")) * tun.brows));
        o.put(FaceSignals.BROW_R, sgn((0.7f * s.charR("browOuterUp") + 0.5f * inner - 1.2f * s.charR("browDown")) * tun.brows));
        o.put(FaceSignals.BROW_INNER, amt(inner * tun.brows));
        o.put(FaceSignals.SNEER, amt((s.amt("noseSneerLeft") + s.amt("noseSneerRight")) / 2f * 1.4f * tun.brows));

        // ── the pilot's mood lids (same recipe as al-inputs.js, from the calibrated values) ──
        float bothShut = Math.min(s.amt("eyeBlinkLeft"), s.amt("eyeBlinkRight"));
        o.put(FaceSignals.LID_UP, clamp(bothShut * 1.5f * tun.brows, 0f, 0.8f));
        o.put(FaceSignals.LID_LOW, amt(((s.amt("eyeSquintLeft") + s.amt("eyeSquintRight")) * 0.9f
                + (s.amt("cheekSquintLeft") + s.amt("cheekSquintRight")) * 0.6f) * tun.brows));
        o.put(FaceSignals.LID_TILT, sgn(((s.amt("browDownLeft") + s.amt("browDownRight")) * 1.2f
                - inner * 1.4f) * tun.brows));

        // ── jaw (the teeth) apart from the lips (the opening you see) ──
        float jaw = amt(s.amt("jawOpen") * 1.7f * tun.mouth);
        o.put(FaceSignals.JAW, jaw);
        // "jawLeft" slides toward the picture's left, the person's right (default)
        o.put(FaceSignals.JAW_X, sgn(flip * (s.personR("jaw") - s.personL("jaw")) * 1.5f * tun.mouth));
        o.put(FaceSignals.JAW_FWD, amt(s.amt("jawForward") * 1.5f * tun.mouth));
        // lips held together while the jaw drops ("mouthClose") keep the opening shut over open teeth
        float m = amt((s.amt("jawOpen") - s.amt("mouthClose")) * 1.7f * tun.mouth);
        o.put(FaceSignals.MOUTH, m);
        o.put(FaceSignals.JAW_OPEN, m);
        o.put(FaceSignals.LIP_UP_L, amt(s.charL("mouthUpperUp") * 1.4f * tun.mouth));
        o.put(FaceSignals.LIP_UP_R, amt(s.charR("mouthUpperUp") * 1.4f * tun.mouth));
        o.put(FaceSignals.LIP_DOWN_L, amt(s.charL("mouthLowerDown") * 1.4f * tun.mouth));
        o.put(FaceSignals.LIP_DOWN_R, amt(s.charR("mouthLowerDown") * 1.4f * tun.mouth));
        float smL = amt(s.charL("mouthSmile") * 1.2f * tun.mouth), smR = amt(s.charR("mouthSmile") * 1.2f * tun.mouth);
        o.put(FaceSignals.SMILE_L, smL);
        o.put(FaceSignals.SMILE_R, smR);
        o.put(FaceSignals.FROWN_L, amt(s.charL("mouthFrown") * 1.5f * tun.mouth));
        o.put(FaceSignals.FROWN_R, amt(s.charR("mouthFrown") * 1.5f * tun.mouth));
        float pucker = amt(s.amt("mouthPucker") * 1.2f * tun.mouth);
        float funnel = amt(s.amt("mouthFunnel") * 1.4f * tun.mouth);
        float wide = amt((s.amt("mouthStretchLeft") + s.amt("mouthStretchRight")) / 2f * 1.5f * tun.mouth);
        float press = amt(((s.amt("mouthPressLeft") + s.amt("mouthPressRight")) / 2f * 1.2f
                + Math.max(s.amt("mouthRollLower"), s.amt("mouthRollUpper")) * 0.8f) * tun.mouth);
        o.put(FaceSignals.PUCKER, pucker);
        o.put(FaceSignals.FUNNEL, funnel);
        o.put(FaceSignals.WIDE, wide);
        o.put(FaceSignals.PRESS, press);
        // "mouthLeft" moves the mouth toward the picture's left, i.e. the person's right (default)
        float mouthTowardR = s.personR("mouth") - s.personL("mouth");
        o.put(FaceSignals.MOUTH_X, sgn(flip * mouthTowardR * 1.5f * tun.mouth));
        o.put(FaceSignals.CHEEK_PUFF, amt(s.amt("cheekPuff") * 1.3f * tun.mouth));

        o.put(FaceSignals.VIS, (float) FaceSignals.shapeIndex(shape(m, (smL + smR) / 2f, pucker, funnel, wide,
                press, inner)));
        o.put(FaceSignals.FACE, 1f);
        return o;
    }

    /** The mouth shape a sheet mouth should show (the stage holds it steady for two frames). */
    @NonNull
    static String shape(float open, float smile, float pucker, float funnel, float wide, float press, float inner) {
        if (open < 0.1f) {
            if (press > 0.45f) return "M";
            if (smile > 0.5f) return "Smile";
            if (pucker > 0.5f) return "W-Oo";
            return "Neutral";
        }
        if (pucker > 0.45f) return "W-Oo";
        if (funnel > 0.4f) return "Oh";
        if (open > 0.55f && inner > 0.5f) return "Surprised";
        if (wide > 0.35f || smile > 0.4f) return "Ee";
        return "Aa";
    }

    /** A calibrated 0..1 closure to a blink: continuous, firm at both ends, minus the look-down droop. */
    static float blink(float closure, float lookDown) {
        return smoothstep(0.12f, 0.8f, closure - 0.4f * lookDown);
    }

    static float smoothstep(float a, float b, float x) {
        float t = clamp((x - a) / (b - a), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float amt(float v) { return clamp(v, 0f, 1f); }
    private static float sgn(float v) { return clamp(v, -1f, 1f); }

    /** Reads raw values by side: the person's own sides, and the character's (after mirroring). */
    private static final class Side {
        private final Map<String, Float> raw;
        private final FaceCalibration cal;
        private final boolean mirror;

        Side(Map<String, Float> raw, FaceCalibration cal, boolean mirror) {
            this.raw = raw;
            this.cal = cal;
            this.mirror = mirror;
        }

        float raw(String k) {
            Float v = raw.get(k);
            return v == null || v.isNaN() ? 0f : v;
        }

        /** Calibrated 0..1 amount of one expression value. */
        float amt(String k) {
            return cal.amount(k, raw(k));
        }

        /** The person's right side of a two-sided value ("eyeBlink" -> eyeBlinkLeft by default). */
        float personR(String base) {
            return amt(base + (cal.swapSides() ? "Right" : "Left"));
        }

        float personL(String base) {
            return amt(base + (cal.swapSides() ? "Left" : "Right"));
        }

        /** The character's left: the person's right like a reflection, their left when mirrored. */
        float charL(String base) {
            return mirror ? personL(base) : personR(base);
        }

        float charR(String base) {
            return mirror ? personR(base) : personL(base);
        }
    }
}

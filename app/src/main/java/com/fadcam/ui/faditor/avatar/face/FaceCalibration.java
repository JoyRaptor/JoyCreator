package com.fadcam.ui.faditor.avatar.face;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/**
 * FACE-1: what this person's face looks like at rest, on this phone, plus which way is which.
 *
 * <p><b>Neutral.</b> Everyone's resting face reads differently to a tracker: a resting frown of 0.2,
 * eyes that read a little squinted, a phone held below the chin so the head reads as nodding
 * down. One tap ({@link Capture}) averages a still half-second of the face and from then on that
 * face is zero: v' = (v - rest) / (1 - rest), so the rest reads 0 and a full expression still
 * reaches 1. Head angles simply subtract.</p>
 *
 * <p><b>Directions.</b> Which way the tracker's head angles and its left/right expression names
 * point depends on the camera and the library; neither the phone nor the web pilot has ever been
 * checked against a real face. {@link DirectionCheck} settles it per phone in one guided go
 * ("turn your head to your right", "look up", "tilt toward your right shoulder", "close your
 * right eye"), so the character moves like your reflection; the person's taste on top of that is
 * {@link FaceTuning#mirror}.</p>
 *
 * <p>Raw inputs are the MediaPipe blendshape names plus {@link #HEAD_YAW}, {@link #HEAD_PITCH} and
 * {@link #HEAD_ROLL} in degrees. Saved as small hand-editable JSON by the host.</p>
 */
public final class FaceCalibration {

    /** Raw head angles in degrees, as the tracker reports them (before any sign or neutral). */
    public static final String HEAD_YAW = "headYawDeg", HEAD_PITCH = "headPitchDeg", HEAD_ROLL = "headRollDeg";
    /**
     * Where the face sits in the upright camera picture: its centre (0..1 across, 0..1 down) and its
     * width (0..1 of the picture's width). Lean is measured from the neutral's place, in face widths.
     */
    public static final String FACE_X = "faceX", FACE_Y = "faceY", FACE_SIZE = "faceSize";

    /** 2: face position measured upright (FaceFrame); a version 1 neutral is re-taken. */
    public static final int VERSION = 2;

    /** The smallest span left above a resting value (a face resting at 0.95 still gets a range). */
    private static final float MIN_SPAN = 0.25f;

    private final Map<String, Float> rest = new HashMap<>();
    /** +1 or -1 per head axis: which raw direction is "turn right / look down / tilt right". */
    private int signYaw = 1, signPitch = 1, signRoll = 1;
    /**
     * Which way across the picture is "leaning to your right". -1 by default: a front camera's
     * picture is not mirrored, so the person's right is the picture's left.
     */
    private int signLean = -1;
    /**
     * False (the default): the tracker names sides as the picture shows them, so its "Left" is the
     * person's right (what the web pilot and the old phone code assumed). True: its names are the
     * person's own sides.
     */
    private boolean swapSides = false;
    /** True once a neutral has been captured (a fresh calibration is captured automatically). */
    private boolean neutralSet = false;

    @NonNull
    public static FaceCalibration none() {
        return new FaceCalibration();
    }

    public boolean isNeutralSet() { return neutralSet; }
    public int signYaw() { return signYaw; }
    public int signPitch() { return signPitch; }
    public int signRoll() { return signRoll; }
    public int signLean() { return signLean; }
    public boolean swapSides() { return swapSides; }

    /**
     * Lean from neutral in face widths: {x + = the person's right, y + = down, near + = closer},
     * or null when there is no neutral place yet or the frame carries no face position.
     */
    @Nullable
    public float[] lean(float faceX, float faceY, float faceSize) {
        float size0 = rest(FACE_SIZE);
        if (!neutralSet || size0 <= 0.02f || faceSize <= 0.02f) return null;
        return new float[]{
                (faceX - rest(FACE_X)) * signLean / size0,
                (faceY - rest(FACE_Y)) / size0,
                faceSize / size0 - 1f};
    }

    public float rest(@NonNull String raw) {
        Float r = rest.get(raw);
        return r == null ? 0f : r;
    }

    /** A 0..1 expression value measured from this face's rest: rest reads 0, full still reaches 1. */
    public float amount(@NonNull String raw, float v) {
        float r = rest(raw);
        float span = Math.max(MIN_SPAN, 1f - r);
        float out = (v - r) / span;
        return out < 0f ? 0f : (out > 1f ? 1f : out);
    }

    /** Head angle in degrees from neutral, signed so + is the person's right / down / right tilt. */
    public float headDeg(@NonNull String axis, float deg) {
        int s = HEAD_YAW.equals(axis) ? signYaw : HEAD_PITCH.equals(axis) ? signPitch : signRoll;
        return (deg - rest(axis)) * s;
    }

    @NonNull
    public FaceCalibration copy() {
        FaceCalibration c = new FaceCalibration();
        c.rest.putAll(rest);
        c.signYaw = signYaw;
        c.signPitch = signPitch;
        c.signRoll = signRoll;
        c.signLean = signLean;
        c.swapSides = swapSides;
        c.neutralSet = neutralSet;
        return c;
    }

    /** The same directions with a new sign on one axis (from {@link DirectionCheck}). */
    @NonNull
    public FaceCalibration withSign(@NonNull String axis, int sign) {
        FaceCalibration c = copy();
        int s = sign < 0 ? -1 : 1;
        if (HEAD_YAW.equals(axis)) c.signYaw = s;
        else if (HEAD_PITCH.equals(axis)) c.signPitch = s;
        else if (HEAD_ROLL.equals(axis)) c.signRoll = s;
        else if (FACE_X.equals(axis)) c.signLean = s;
        return c;
    }

    @NonNull
    public FaceCalibration withSwapSides(boolean swap) {
        FaceCalibration c = copy();
        c.swapSides = swap;
        return c;
    }

    // ── JSON (hand-editable; tolerant) ──

    @NonNull
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("v", VERSION);
        o.addProperty("neutralSet", neutralSet);
        JsonObject r = new JsonObject();
        for (Map.Entry<String, Float> e : rest.entrySet()) r.addProperty(e.getKey(), e.getValue());
        o.add("rest", r);
        JsonObject s = new JsonObject();
        s.addProperty("yaw", signYaw);
        s.addProperty("pitch", signPitch);
        s.addProperty("roll", signRoll);
        s.addProperty("lean", signLean);
        o.add("sign", s);
        o.addProperty("swapSides", swapSides);
        return o;
    }

    @NonNull
    public static FaceCalibration fromJson(@Nullable JsonObject o) {
        FaceCalibration c = new FaceCalibration();
        if (o == null) return c;
        try {
            if (o.has("rest") && o.get("rest").isJsonObject()) {
                for (Map.Entry<String, com.google.gson.JsonElement> e : o.getAsJsonObject("rest").entrySet()) {
                    float v = e.getValue().getAsFloat();
                    if (!Float.isNaN(v) && !Float.isInfinite(v)) c.rest.put(e.getKey(), v);
                }
            }
            if (o.has("sign") && o.get("sign").isJsonObject()) {
                JsonObject s = o.getAsJsonObject("sign");
                if (s.has("yaw")) c.signYaw = s.get("yaw").getAsInt() < 0 ? -1 : 1;
                if (s.has("pitch")) c.signPitch = s.get("pitch").getAsInt() < 0 ? -1 : 1;
                if (s.has("roll")) c.signRoll = s.get("roll").getAsInt() < 0 ? -1 : 1;
                if (s.has("lean")) c.signLean = s.get("lean").getAsInt() < 0 ? -1 : 1;
            }
            if (o.has("swapSides")) c.swapSides = o.get("swapSides").getAsBoolean();
            c.neutralSet = o.has("neutralSet") ? o.get("neutralSet").getAsBoolean() : !c.rest.isEmpty();
            int v = o.has("v") ? o.get("v").getAsInt() : 1;
            if (v < 2) {
                // Version 1 (11-22, before FaceFrame) measured where the face sits in the camera's
                // sideways frame: that part of the neutral and the lean direction are wrong. The head
                // and eye directions stay; the next still half-second re-takes the neutral by itself.
                c.rest.remove(FACE_X);
                c.rest.remove(FACE_Y);
                c.rest.remove(FACE_SIZE);
                c.signLean = -1;
                c.neutralSet = false;
            }
        } catch (RuntimeException ignored) {
            // keep what was read
        }
        return c;
    }

    // ── One tap: capture a neutral face ──

    /**
     * Averages a still stretch of face into a new neutral. The face must hold still (the head may
     * not wander more than {@link #MAX_SPREAD_DEG}) for {@code frames} tracked frames in a row; a
     * wander or a lost face starts the count again. Directions are kept from the calibration it
     * started from.
     */
    public static final class Capture {
        public static final float MAX_SPREAD_DEG = 6f;
        private final int need;
        private final FaceCalibration base;
        private final Map<String, Double> sum = new HashMap<>();
        private int n = 0;
        private float minYaw, maxYaw, minPitch, maxPitch;

        public Capture(@NonNull FaceCalibration base, int frames) {
            this.base = base;
            this.need = Math.max(1, frames);
        }

        /** Restart the count (the face was lost). */
        public void restart() {
            sum.clear();
            n = 0;
        }

        /** Feed one tracked frame's raw values; true when the neutral is complete. */
        public boolean add(@NonNull Map<String, Float> raw) {
            float yaw = val(raw, HEAD_YAW), pitch = val(raw, HEAD_PITCH);
            if (n == 0) {
                minYaw = maxYaw = yaw;
                minPitch = maxPitch = pitch;
            } else {
                minYaw = Math.min(minYaw, yaw); maxYaw = Math.max(maxYaw, yaw);
                minPitch = Math.min(minPitch, pitch); maxPitch = Math.max(maxPitch, pitch);
                if (maxYaw - minYaw > MAX_SPREAD_DEG || maxPitch - minPitch > MAX_SPREAD_DEG) {
                    restart();
                    return add(raw);
                }
            }
            for (Map.Entry<String, Float> e : raw.entrySet()) {
                Float v = e.getValue();
                if (v == null || v.isNaN()) continue;
                Double s = sum.get(e.getKey());
                sum.put(e.getKey(), (s == null ? 0.0 : s) + v);
            }
            n++;
            return n >= need;
        }

        public int count() { return n; }
        public int needed() { return need; }

        @NonNull
        public FaceCalibration result() {
            FaceCalibration c = base.copy();
            c.rest.clear();
            if (n > 0) for (Map.Entry<String, Double> e : sum.entrySet()) {
                c.rest.put(e.getKey(), (float) (e.getValue() / n));
            }
            c.neutralSet = true;
            return c;
        }

        private static float val(Map<String, Float> m, String k) {
            Float v = m.get(k);
            return v == null || v.isNaN() ? 0f : v;
        }
    }

    // ── Guided: which way is which ──

    /**
     * One prompt of the direction check. The person is asked to do one thing ("turn your head to
     * your right"); frames are fed until the movement is plainly big, then the answer is the raw
     * direction it went. {@link #answer} is +1/-1 for a head axis and, for {@link #EYE}, +1 when the
     * tracker's "Left" is the person's right (the default) and -1 when its names are the person's own.
     */
    public static final class DirectionCheck {
        /**
         * Prompts: turn to your right, look down, tilt toward your right shoulder, lean to your right,
         * close your right eye.
         */
        public static final String YAW = HEAD_YAW, PITCH = HEAD_PITCH, ROLL = HEAD_ROLL, LEAN = FACE_X,
                EYE = "eye";
        private static final float HEAD_DEG = 12f, LEAN_MOVE = 0.05f, EYE_DIFF = 0.35f;

        private final String what;
        private final FaceCalibration cal;
        private int answer = 0;

        public DirectionCheck(@NonNull String what, @NonNull FaceCalibration cal) {
            this.what = what;
            this.cal = cal;
        }

        @NonNull public String what() { return what; }

        /** Feed a raw frame; true once the answer is known. */
        public boolean add(@NonNull Map<String, Float> raw) {
            if (answer != 0) return true;
            if (EYE.equals(what)) {
                float l = cal.amount("eyeBlinkLeft", val(raw, "eyeBlinkLeft"));
                float r = cal.amount("eyeBlinkRight", val(raw, "eyeBlinkRight"));
                // The person closed their RIGHT eye. The default (the web pilot's and the old phone
                // code's) is that the tracker names sides as the picture shows them, so the person's
                // right eye is its "Left": +1 keeps that, -1 swaps.
                if (l - r > EYE_DIFF) answer = 1;
                else if (r - l > EYE_DIFF) answer = -1;
            } else {
                if (!raw.containsKey(what)) return false;
                float moved = val(raw, what) - cal.rest(what);
                if (Math.abs(moved) >= (LEAN.equals(what) ? LEAN_MOVE : HEAD_DEG)) answer = moved > 0 ? 1 : -1;
            }
            return answer != 0;
        }

        public int answer() { return answer; }

        /** The calibration with this answer applied (unchanged until answered). */
        @NonNull
        public FaceCalibration apply() {
            if (answer == 0) return cal;
            return EYE.equals(what) ? cal.withSwapSides(answer < 0) : cal.withSign(what, answer);
        }

        private static float val(Map<String, Float> m, String k) {
            Float v = m.get(k);
            return v == null || v.isNaN() ? 0f : v;
        }
    }
}

package com.fadcam.ui.faditor.avatar.face;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * FACE-1: the one vocabulary a face speaks in, on the phone and in the web pilot alike
 * ({@code tools/avatarlab/al-face.js} uses the same names, ranges and lanes).
 *
 * <p>Every signal is continuous: ranges are 0..1 (amounts) or -1..1 (directions). Sides are the
 * character's own (blinkL is the character's left eye) with mirroring decided once, in
 * {@link FaceMapper}. The only discrete signal is {@link #VIS}, an index into {@link #SHAPES}
 * for characters drawn with a mouth sheet; everything continuous stays continuous.</p>
 *
 * <p>Lanes are how a take is recorded in passes (the pilot's owners): a pass writes only the lanes
 * it is recording and hands the rest back untouched.</p>
 */
public final class FaceSignals {

    private FaceSignals() {}

    // ── Head: -1..1, the same signs as the web pilot's al-inputs.js (lookY + = looking down).
    //    Which way is which on a real face is settled per phone by FaceCalibration's direction check. ──
    public static final String TURN_X = "turnX", TURN_Y = "turnY", ROLL = "roll";
    /** Old rigs' head grid drivers: the same 45° / 30° scale they always had, now calibrated. */
    public static final String YAW = "yaw", PITCH = "pitch";
    // ── Eyes ──
    public static final String LOOK_X = "lookX", LOOK_Y = "lookY";
    public static final String BLINK_L = "blinkL", BLINK_R = "blinkR";
    public static final String SQUINT_L = "squintL", SQUINT_R = "squintR";
    public static final String WIDE_L = "wideL", WIDE_R = "wideR";
    // ── Brows: -1 (down, a frown) .. 1 (raised); browInner 0..1 (the worried middle) ──
    public static final String BROW_L = "browL", BROW_R = "browR", BROW_INNER = "browInner";
    // ── Lean: where the face sits in the picture against neutral (a bust leaning, ducking, coming
    //    closer). -1..1; leanX + = toward the screen's right like a reflection, leanY + = down,
    //    near + = closer. Its own lane, so a face pass never fights the joystick's body lane. ──
    public static final String LEAN_X = "leanX", LEAN_Y = "leanY", NEAR = "near";
    // ── Jaw, apart from the lips: teeth can open and slide behind lips that stay put ──
    /** The jaw's opening 0..1 (the bone: teeth), whether or not the lips part. */
    public static final String JAW = "jaw";
    /** The jaw sliding sideways -1..1 (+ = screen right like a reflection); forward 0..1. */
    public static final String JAW_X = "jawX", JAW_FWD = "jawFwd";
    // ── Mouth ──
    /** The lips apart 0..1 (the opening you see): the jaw's opening less lips held closed. */
    public static final String MOUTH = "mouth";
    /** The old name for the mouth opening (the mic fallback and old takes use it). */
    public static final String JAW_OPEN = "jawOpen";
    /** The upper lip lifted on one side (a sneer, a wince: the top teeth show there), 0..1. */
    public static final String LIP_UP_L = "lipUpL", LIP_UP_R = "lipUpR";
    /** The lower lip pulled down on one side (the bottom teeth show there), 0..1. */
    public static final String LIP_DOWN_L = "lipDownL", LIP_DOWN_R = "lipDownR";
    public static final String SMILE_L = "smileL", SMILE_R = "smileR";
    public static final String FROWN_L = "frownL", FROWN_R = "frownR";
    public static final String PUCKER = "pucker", FUNNEL = "funnel", WIDE = "mouthWide";
    public static final String PRESS = "press", MOUTH_X = "mouthX", CHEEK_PUFF = "cheekPuff", SNEER = "sneer";
    // ── The pilot's mood lids (relaxed, cheery, the lid's slant) ──
    public static final String LID_UP = "lidUp", LID_LOW = "lidLow", LID_TILT = "lidTilt";
    /** Mouth shape for sheet mouths: a float index into {@link #SHAPES} (steps, never blends). */
    public static final String VIS = "vis";
    /** 0..1: how much the live face is driving right now (0 while the face is lost). */
    public static final String FACE = "face";

    /** The pilot's mouth shapes (al-model.js AL.SHAPES), same order: VIS indexes this. */
    public static final String[] SHAPES = {"Neutral", "Aa", "Ee", "Oh", "W-Oo", "M", "F", "L", "S",
            "D", "R", "Uh", "Smile", "Surprised"};

    public static int shapeIndex(@NonNull String name) {
        for (int i = 0; i < SHAPES.length; i++) if (SHAPES[i].equals(name)) return i;
        return 0;
    }

    // ── Lanes (a take is recorded lane by lane) ──
    public static final String LANE_HEAD = "head", LANE_MOUTH = "mouth", LANE_MOOD = "mood",
            LANE_BLINK = "blink", LANE_LEAN = "lean";
    public static final String[] LANES = {LANE_HEAD, LANE_MOUTH, LANE_MOOD, LANE_BLINK, LANE_LEAN};

    private static final Map<String, String> LANE_OF = new HashMap<>();
    private static final Map<String, String> GROUP_OF = new HashMap<>();
    /** Smoothing groups: each moves at its own natural speed. */
    public static final String G_HEAD = "head", G_GAZE = "gaze", G_BLINK = "blink", G_MOUTH = "mouth",
            G_MOOD = "mood", G_STEP = "step";

    static {
        lane(LANE_HEAD, G_HEAD, TURN_X, TURN_Y, ROLL, YAW, PITCH);
        lane(LANE_HEAD, G_GAZE, LOOK_X, LOOK_Y);
        lane(LANE_BLINK, G_BLINK, BLINK_L, BLINK_R);
        lane(LANE_MOOD, G_MOOD, SQUINT_L, SQUINT_R, WIDE_L, WIDE_R, BROW_L, BROW_R, BROW_INNER, SNEER,
                LID_UP, LID_LOW, LID_TILT);
        lane(LANE_MOUTH, G_MOUTH, MOUTH, JAW_OPEN, JAW, JAW_X, JAW_FWD, SMILE_L, SMILE_R, FROWN_L, FROWN_R,
                LIP_UP_L, LIP_UP_R, LIP_DOWN_L, LIP_DOWN_R, PUCKER, FUNNEL, WIDE, PRESS, MOUTH_X, CHEEK_PUFF);
        lane(LANE_MOUTH, G_STEP, VIS);
        lane(LANE_LEAN, G_HEAD, LEAN_X, LEAN_Y, NEAR);
        GROUP_OF.put(FACE, G_STEP);
    }

    private static void lane(String lane, String group, String... names) {
        for (String n : names) {
            LANE_OF.put(n, lane);
            GROUP_OF.put(n, group);
        }
    }

    /** Every signal this engine writes, in a stable order. */
    public static final Set<String> ALL;
    static {
        Set<String> s = new LinkedHashSet<>(Arrays.asList(TURN_X, TURN_Y, ROLL, YAW, PITCH, LOOK_X, LOOK_Y,
                BLINK_L, BLINK_R, SQUINT_L, SQUINT_R, WIDE_L, WIDE_R, BROW_L, BROW_R, BROW_INNER, SNEER,
                LID_UP, LID_LOW, LID_TILT, MOUTH, JAW_OPEN, JAW, JAW_X, JAW_FWD, SMILE_L, SMILE_R, FROWN_L,
                FROWN_R, LIP_UP_L, LIP_UP_R, LIP_DOWN_L, LIP_DOWN_R, PUCKER, FUNNEL, WIDE, PRESS, MOUTH_X,
                CHEEK_PUFF, LEAN_X, LEAN_Y, NEAR, VIS, FACE));
        ALL = Collections.unmodifiableSet(s);
    }

    // ── Face points (face pins): "pt.<mesh index>.x" / ".y", the point's movement from neutral ──
    public static final String POINT_PREFIX = "pt.", LANE_POINTS = "points", G_POINT = "point";

    @NonNull public static String pointX(int index) { return POINT_PREFIX + index + ".x"; }
    @NonNull public static String pointY(int index) { return POINT_PREFIX + index + ".y"; }

    /** True for a face point's signal ({@link #pointX}, {@link #pointY}). */
    public static boolean isPoint(@NonNull String name) {
        return name.startsWith(POINT_PREFIX);
    }

    /** The lane a signal is recorded in, or null for names that are not face signals. */
    @Nullable
    public static String laneOf(@NonNull String name) {
        return isPoint(name) ? LANE_POINTS : LANE_OF.get(name);
    }

    public static boolean isFace(@NonNull String name) {
        return GROUP_OF.containsKey(name) || isPoint(name);
    }

    /** True for the signals that step instead of blending (mouth shape index, presence). */
    public static boolean isStep(@NonNull String name) {
        return G_STEP.equals(GROUP_OF.get(name));
    }

    @Nullable
    private static String groupOf(@NonNull String name) {
        return isPoint(name) ? G_POINT : GROUP_OF.get(name);
    }

    /** -1..1 signals (directions); everything else is 0..1. */
    public static boolean isSigned(@NonNull String name) {
        if (isPoint(name)) return true;
        switch (name) {
            case TURN_X: case TURN_Y: case ROLL: case YAW: case PITCH: case LOOK_X: case LOOK_Y:
            case BROW_L: case BROW_R: case MOUTH_X: case LID_TILT: case JAW_X: case LEAN_X: case LEAN_Y:
            case NEAR:
                return true;
            default:
                return false;
        }
    }

    /**
     * One-Euro settings per smoothing group: {minCutoff Hz, beta}. Values move in 0..1 or -1..1
     * units, so beta is per unit-per-second. Null = pass through unsmoothed (steps).
     * Blinks barely smoothed (a blink lasts two tracker frames and must stay a blink); the head
     * smoothed most at rest (that is where jitter shows) and opened up as soon as it moves.
     */
    @Nullable
    public static float[] smoothing(@NonNull String name) {
        String g = groupOf(name);
        if (g == null) return null;
        switch (g) {
            case G_HEAD:  return new float[]{1.0f, 1.6f};
            case G_GAZE:  return new float[]{1.6f, 2.5f};
            case G_BLINK: return new float[]{6.0f, 4.0f};
            case G_MOUTH: return new float[]{3.0f, 3.0f};
            case G_POINT: return new float[]{3.0f, 3.0f};
            case G_MOOD:  return new float[]{1.2f, 1.5f};
            default:      return null;
        }
    }

    /** The pipeline's smoother settings for a face: each signal at its own speed, steps untouched. */
    public static final com.fadcam.ui.faditor.avatar.DriverParamSmoother.Tuner TUNER = name -> {
        if (isStep(name)) return com.fadcam.ui.faditor.avatar.DriverParamSmoother.Tuner.PASS;
        return smoothing(name);
    };

    /** Render-side follow speed (rad/s) per group; see {@link FaceFollower}. 0 = snap. */
    public static float followOmega(@NonNull String name) {
        String g = groupOf(name);
        if (g == null) return 30f;
        switch (g) {
            case G_HEAD:  return 30f;
            case G_GAZE:  return 45f;
            case G_BLINK: return 80f;
            case G_MOUTH: return 55f;
            case G_POINT: return 55f;
            case G_MOOD:  return 25f;
            default:      return 0f;
        }
    }

    /** The face at rest: every signal 0, the neutral mouth shape. */
    @NonNull
    public static Map<String, Float> rest() {
        Map<String, Float> m = new HashMap<>();
        for (String n : ALL) m.put(n, 0f);
        return m;
    }
}

package com.fadcam.ui.faditor.avatar;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;
import com.fadcam.ui.faditor.Studio;

/**
 * A-tier "life package" (MINED, binding: near-zero cost, huge feel): the idle
 * micro-motion generator that keeps a puppet alive when tracking is quiet —
 * breathing sine on the body, eye saccade micro-darts every 3–5s, and
 * periodic ASYMMETRIC blinks (one eye a frame late — the detail that reads as
 * organic). Also owns the idle GATE: life fades in only after the audio level
 * stays below the threshold for the hold time (−45dB / 2s per MINED), and
 * fades out immediately when the user speaks or tracking gets loud.
 *
 * <p>Deterministic: all "randomness" comes from an internal LCG seeded at
 * construction, advanced only by {@link #update} — the same seed + the same
 * (t, audioDb) sequence reproduces the same motion, so baked parameter tracks
 * replay exactly (bake-to-param-track doctrine).</p>
 *
 * <p>Output params (all additive deltas / absolute 0..1 signals the resolver's
 * driver map understands; the caller MERGES them under live tracking values):
 * {@code life_breath} (scaleY delta ±1 normalized), {@code life_eyeX/eyeY}
 * (saccade offset, ±1), {@code life_blinkL/blinkR} (0=open, 1=closed).</p>
 */
public class LifeSignals {

    public static final String PARAM_BREATH = "life_breath";
    public static final String PARAM_EYE_X = "life_eyeX";
    public static final String PARAM_EYE_Y = "life_eyeY";
    public static final String PARAM_BLINK_L = "life_blinkL";
    public static final String PARAM_BLINK_R = "life_blinkR";

    /** Idle gate (MINED): quieter than this... */
    private static final float GATE_DB = -45f;
    /** ...for this long → idle-loop fallback engages. */
    private static final double GATE_HOLD_S = 2.0;
    /** Life weight ramp in/out time. */
    private static final double RAMP_S = 0.6;

    private static final double BREATH_HZ = 0.25;      // calm 15/min
    private static final double BLINK_MIN_S = 2.8, BLINK_MAX_S = 6.0;
    private static final double BLINK_CLOSE_S = 0.06, BLINK_OPEN_S = 0.12;
    /** The asymmetry: the lagging eye starts this much later. */
    private static final double BLINK_LAG_S = 0.033;
    private static final double SACCADE_MIN_S = 3.0, SACCADE_MAX_S = 5.0;

    private long lcg;
    private double quietSince = -1;
    private double weight = 0;              // 0..1 life blend
    private double lastT = Double.NaN;
    private double nextBlinkAt;
    private double nextSaccadeAt;
    private float saccadeX, saccadeY;
    private boolean blinkLeftLags;

    public LifeSignals(long seed) {
        this.lcg = seed == 0 ? 1 : seed;
        this.nextBlinkAt = 1.0 + rand() * 2.0;
        this.nextSaccadeAt = 1.5 + rand() * 2.0;
    }

    /** Current life blend weight (0 = fully live-driven, 1 = fully idle). */
    public double getWeight() { return weight; }

    /**
     * Advance to time {@code tSeconds} with the current input level and get
     * the life params. Callers apply them scaled by {@link #getWeight()} —
     * or just add them; params are already weight-scaled in the output.
     */
    @NonNull
    public Map<String, Float> update(double tSeconds, float audioDb) {
        double dt = Double.isNaN(lastT) ? 0 : Math.max(0, tSeconds - lastT);
        lastT = tSeconds;

        // ── Gate + ramp ──
        if (audioDb < GATE_DB) {
            if (quietSince < 0) quietSince = tSeconds;
        } else {
            quietSince = -1;
        }
        boolean idle = quietSince >= 0 && (tSeconds - quietSince) >= GATE_HOLD_S;
        double target = idle ? 1 : 0;
        double step = dt / RAMP_S;
        if (weight < target) weight = Math.min(target, weight + step);
        else if (weight > target) weight = Math.max(target, weight - step);

        Map<String, Float> out = new HashMap<>();
        float w = (float) weight;

        // ── Breathing (always computed; weight gates it) ──
        out.put(PARAM_BREATH, w * (float) Math.sin(2 * Math.PI * BREATH_HZ * tSeconds));

        // ── Saccades: pick a new micro-target on schedule ──
        if (tSeconds >= nextSaccadeAt) {
            saccadeX = (float) (rand() * 2 - 1) * 0.6f;
            saccadeY = (float) (rand() * 2 - 1) * 0.35f;
            nextSaccadeAt = tSeconds + SACCADE_MIN_S + rand() * (SACCADE_MAX_S - SACCADE_MIN_S);
        }
        out.put(PARAM_EYE_X, w * saccadeX);
        out.put(PARAM_EYE_Y, w * saccadeY);

        // ── Blink envelope (runs even at weight 0 so schedules stay warm;
        //     output is weight-gated like everything else) ──
        double sinceBlink = tSeconds - nextBlinkAt;
        float blinkLead = blinkEnvelope(sinceBlink);
        float blinkLag = blinkEnvelope(sinceBlink - BLINK_LAG_S);
        if (sinceBlink > BLINK_CLOSE_S + BLINK_OPEN_S + BLINK_LAG_S) {
            nextBlinkAt = tSeconds + BLINK_MIN_S + rand() * (BLINK_MAX_S - BLINK_MIN_S);
            blinkLeftLags = rand() < 0.5;
        }
        out.put(PARAM_BLINK_L, w * (blinkLeftLags ? blinkLag : blinkLead));
        out.put(PARAM_BLINK_R, w * (blinkLeftLags ? blinkLead : blinkLag));
        return out;
    }

    /** 0→1→0 close/open envelope; x = seconds since blink start (may be <0). */
    private static float blinkEnvelope(double x) {
        if (x < 0) return 0f;
        if (x < BLINK_CLOSE_S) return (float) (x / BLINK_CLOSE_S);
        double open = x - BLINK_CLOSE_S;
        if (open < BLINK_OPEN_S) return (float) (1.0 - open / BLINK_OPEN_S);
        return 0f;
    }

    /** Deterministic LCG in [0,1) — java.util.Random constants, own state. */
    private double rand() {
        lcg = lcg * 0x5DEECE66DL + 0xBL;
        return ((lcg >>> 17) & Studio.alpha(Studio.INK, 0x7F)) / (double) (1L << 31);
    }
}

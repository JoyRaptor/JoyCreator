package com.fadcam.ui.faditor.avatar.face;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.avatar.SpectralVisemeAnalyzer;

import java.util.HashMap;
import java.util.Map;

/**
 * FACE-1: the screen side of face capture. The tracker delivers about 15 faces a second and the
 * screen draws 60; showing the newest face as it lands makes the character move in little steps.
 * This follows each signal to the newest face at the screen's rate, and because it carries on
 * along the face's own motion between deliveries (for at most one delivery), it adds no lag
 * while the face moves steadily: a critically damped follow of the motion, never an overshoot
 * spring.
 *
 * <p>Each signal follows at its own speed ({@link FaceSignals#followOmega}): blinks and the mouth
 * fast, brows and lids slower. Steps (the sheet mouth's shape, presence) jump. Pure Java; one per
 * screen that draws a face.</p>
 */
public final class FaceFollower {

    /**
     * Carry on along the motion for at most this much of the gap between deliveries: one whole gap,
     * so a steady movement is continuous across deliveries; when the face stops, the most it can
     * run past is one delivery's worth of motion, which the follow then eases back.
     */
    private static final double PREDICT_FRACTION = 1.0;
    /** A delivery gap longer than this is a pause, not a rhythm: no prediction across it. */
    private static final double MAX_GAP_S = 0.25;

    private final Map<String, float[]> state = new HashMap<>(); // name -> {x, v}
    private final Map<String, float[]> targets = new HashMap<>(); // name -> {value, slope}
    private long targetNanos = Long.MIN_VALUE, prevTargetNanos = Long.MIN_VALUE;
    private long lastNanos = Long.MIN_VALUE;
    private double gapS = 1.0 / 15.0;

    /** Forget everything (a new camera session): the next face is shown as it is. */
    public void reset() {
        state.clear();
        targets.clear();
        targetNanos = prevTargetNanos = lastNanos = Long.MIN_VALUE;
    }

    /**
     * One screen frame. {@code latest} is the newest face from the tracker and {@code latestNanos}
     * when it arrived; {@code nowNanos} is this frame's time (same clock). Returns the face to draw.
     */
    @NonNull
    public Map<String, Float> sample(@NonNull Map<String, Float> latest, long latestNanos, long nowNanos) {
        if (latestNanos != targetNanos) acceptDelivery(latest, latestNanos);
        double dt = lastNanos == Long.MIN_VALUE ? 0 : Math.max(0, (nowNanos - lastNanos) / 1e9);
        dt = Math.min(dt, 0.1);
        lastNanos = nowNanos;
        double since = Math.max(0, (nowNanos - targetNanos) / 1e9);
        double ahead = gapS <= MAX_GAP_S ? Math.min(since, gapS * PREDICT_FRACTION) : 0;

        Map<String, Float> out = new HashMap<>();
        for (Map.Entry<String, float[]> e : targets.entrySet()) {
            String n = e.getKey();
            float target = e.getValue()[0], slope = e.getValue()[1];
            float omega = FaceSignals.followOmega(n);
            float[] s = state.get(n);
            if (s == null || omega <= 0f || isStep(n)) {
                state.put(n, new float[]{target, 0f});
                out.put(n, target);
                continue;
            }
            // Where the face is heading now, and how fast.
            float p = (float) (target + slope * ahead);
            float vp = ahead < gapS * PREDICT_FRACTION ? slope : 0f;
            // Critically damped follow of the error (exact for any dt, so it never blows up). The
            // error is taken against where the target was at the START of this step (it moves at vp
            // through the step), or a moving target is always one step ahead of the follow.
            double e0 = s[0] - (p - vp * dt), ev0 = s[1] - vp, w = omega;
            double c2 = ev0 + w * e0, ex = Math.exp(-w * dt);
            double e1 = (e0 + c2 * dt) * ex;
            double ev1 = (c2 - w * (e0 + c2 * dt)) * ex;
            s[0] = (float) (p + e1);
            s[1] = (float) (vp + ev1);
            if (FaceSignals.isFace(n)) { // other names (idle life, pins) keep their own ranges
                float lo = FaceSignals.isSigned(n) ? -1f : 0f;
                s[0] = Math.max(lo, Math.min(1f, s[0]));
            }
            out.put(n, s[0]);
        }
        return out;
    }

    private void acceptDelivery(@NonNull Map<String, Float> latest, long nanos) {
        prevTargetNanos = targetNanos;
        targetNanos = nanos;
        if (prevTargetNanos != Long.MIN_VALUE) {
            double g = (nanos - prevTargetNanos) / 1e9;
            if (g > 0) gapS = g;
        }
        boolean rhythm = prevTargetNanos != Long.MIN_VALUE && gapS <= MAX_GAP_S;
        for (Map.Entry<String, Float> e : latest.entrySet()) {
            Float v = e.getValue();
            if (v == null || v.isNaN()) continue;
            float[] old = targets.get(e.getKey());
            float slope = 0f;
            if (rhythm && old != null && !isStep(e.getKey())) slope = (float) ((v - old[0]) / gapS);
            targets.put(e.getKey(), new float[]{v, slope});
        }
    }

    /** Values that step: the face's own steps and the mic's mouth class (a class index never blends). */
    private static boolean isStep(@NonNull String n) {
        return FaceSignals.isStep(n) || SpectralVisemeAnalyzer.PARAM_VISEME.equals(n);
    }
}

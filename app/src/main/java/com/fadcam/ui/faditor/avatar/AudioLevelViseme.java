package com.fadcam.ui.faditor.avatar;

/**
 * A3 v1 (PLAN_AVATAR_STUDIO): amplitude → jawOpen — the cheapest lip tier.
 * dB level maps through a floor/ceiling window onto 0..1 jaw opening with
 * fast attack / slower release (mouths snap open and ease closed — the
 * asymmetry that makes amplitude lips read as speech instead of flapping).
 * v2 (TarsosDSP formants → 5–8 viseme classes) replaces the SHAPE while this
 * keeps driving the OPENING; both feed the resolver's driver params.
 *
 * <p>Deterministic per (t, dB) sequence — bake-replay safe. Output param name
 * is the convention the viseme map + A2 driver share: {@code jawOpen}.</p>
 */
public class AudioLevelViseme {

    public static final String PARAM_JAW_OPEN = "jawOpen";

    /** Silence floor: at/below this the jaw is fully closed. */
    private static final float FLOOR_DB = -50f;
    /** Loud ceiling: at/above this the jaw is fully open. */
    private static final float CEIL_DB = -12f;
    /** Attack/release time constants (seconds). */
    private static final double ATTACK_S = 0.03;
    private static final double RELEASE_S = 0.12;

    private float jaw = 0f;
    private double lastT = Double.NaN;

    /** Advance to {@code tSeconds} with the current input level. */
    public float update(double tSeconds, float audioDb) {
        float target = (audioDb - FLOOR_DB) / (CEIL_DB - FLOOR_DB);
        target = Math.max(0f, Math.min(1f, target));
        double dt = Double.isNaN(lastT) ? 0 : Math.max(0, tSeconds - lastT);
        lastT = tSeconds;
        double tau = target > jaw ? ATTACK_S : RELEASE_S;
        double a = tau <= 0 ? 1.0 : 1.0 - Math.exp(-dt / tau);
        jaw += (float) ((target - jaw) * a);
        return jaw;
    }

    public float current() { return jaw; }

    public void reset() {
        jaw = 0f;
        lastT = Double.NaN;
    }
}

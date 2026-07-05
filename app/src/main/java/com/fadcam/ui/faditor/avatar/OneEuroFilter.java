package com.fadcam.ui.faditor.avatar;

/**
 * A2 (PLAN_AVATAR_STUDIO): the One-Euro filter — Casiez, Roussel & Vogel,
 * CHI 2012 — implemented from the paper per the MINED decision (tiny,
 * standard, no dependency). Adaptive first-order low-pass: at low speeds the
 * cutoff drops to kill jitter; at high speeds it rises to kill lag. Runs on
 * EVERY continuous tracking parameter before it reaches
 * {@link PuppetPoseResolver} (yaw/pitch/blendshapes/pin drivers).
 *
 * <p>Pure per-instance state machine — deterministic for a given sample
 * sequence, so baked parameter tracks replay identically (the
 * bake-to-param-track doctrine smooths BEFORE baking; export replays the
 * already-smoothed values and never re-filters).</p>
 */
public class OneEuroFilter {

    /** Paper defaults, sane for head tracking at 15–60 Hz. */
    public static final float DEFAULT_MIN_CUTOFF = 1.0f;  // Hz
    public static final float DEFAULT_BETA = 0.3f;        // speed coefficient
    public static final float DEFAULT_D_CUTOFF = 1.0f;    // derivative cutoff, Hz

    private final float minCutoff;
    private final float beta;
    private final float dCutoff;

    private boolean initialized = false;
    private float xPrev;
    private float dxPrev;
    private double tPrevSeconds;

    public OneEuroFilter() {
        this(DEFAULT_MIN_CUTOFF, DEFAULT_BETA, DEFAULT_D_CUTOFF);
    }

    public OneEuroFilter(float minCutoff, float beta, float dCutoff) {
        this.minCutoff = Math.max(1e-3f, minCutoff);
        this.beta = Math.max(0f, beta);
        this.dCutoff = Math.max(1e-3f, dCutoff);
    }

    /** Forget history (e.g. tracking lost then re-acquired — do NOT glide). */
    public void reset() {
        initialized = false;
    }

    /**
     * Filter one sample. {@code tSeconds} is the sample's timestamp; callers
     * pass the tracker frame time (monotonic). Non-advancing timestamps reuse
     * a nominal 60 Hz step rather than dividing by zero.
     */
    public float filter(float x, double tSeconds) {
        if (!initialized) {
            initialized = true;
            xPrev = x;
            dxPrev = 0f;
            tPrevSeconds = tSeconds;
            return x;
        }
        double dt = tSeconds - tPrevSeconds;
        if (dt <= 0) dt = 1.0 / 60.0;
        tPrevSeconds = tSeconds;

        float dx = (float) ((x - xPrev) / dt);
        float edx = lowpass(dx, alpha(dCutoff, dt), dxPrev);
        dxPrev = edx;

        float cutoff = minCutoff + beta * Math.abs(edx);
        float result = lowpass(x, alpha(cutoff, dt), xPrev);
        xPrev = result;
        return result;
    }

    private static float alpha(float cutoffHz, double dtSeconds) {
        double tau = 1.0 / (2.0 * Math.PI * cutoffHz);
        return (float) (1.0 / (1.0 + tau / dtSeconds));
    }

    private static float lowpass(float x, float a, float prev) {
        return a * x + (1f - a) * prev;
    }
}

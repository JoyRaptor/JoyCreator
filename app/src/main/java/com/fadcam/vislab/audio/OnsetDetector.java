package com.fadcam.vislab.audio;

/**
 * Spots hits (kick, snare, hi-hat) in one frequency range: spectral flux against an adaptive
 * threshold (mean + 1.4 standard deviations of the last 32 frames), with a refractory gap so
 * one drum is one hit. Same constants as the web pilot's {@code OnsetDetector}.
 */
final class OnsetDetector {

    private final int lo, hi;
    private final double minFlux, refractory, decay;
    private final float[] hist = new float[32];   // float storage, as in the pilot
    private int hiIdx, n;
    private double since = 1;

    /** Current envelope, 1 on the hit and decaying. */
    double value;
    /** Strength of a hit that landed this frame, else 0. */
    double fired;
    /** Raw flux this frame (feeds the tempo estimate). */
    double flux;

    OnsetDetector(int lo, int hi, double minFlux, double refractory, double decay) {
        this.lo = lo;
        this.hi = hi;
        this.minFlux = minFlux;
        this.refractory = refractory;
        this.decay = decay;
    }

    double update(float[] raw, float[] prev, double dt) {
        double f = 0;
        for (int k = lo; k < hi; k++) {
            double d = raw[k] - prev[k];
            if (d > 0) f += d;
        }
        f /= Math.max(1, hi - lo);
        int cnt = Math.min(n, hist.length);
        double m = 0, s = 0;
        for (int i = 0; i < cnt; i++) m += hist[i];
        m = cnt > 0 ? m / cnt : 0;
        for (int i = 0; i < cnt; i++) s += (hist[i] - m) * (hist[i] - m);
        s = cnt > 0 ? Math.sqrt(s / cnt) : 0;
        hist[hiIdx] = (float) f;
        hiIdx = (hiIdx + 1) % hist.length;
        n++;
        double th = m + 1.4 * s + minFlux;
        since += dt;
        value *= Math.exp(-dt / decay);
        fired = 0;
        if (f > th && since > refractory) {
            double strength = clamp(0.5 + (f - th) / (th + 1e-4), 0.5, 1);
            value = Math.max(value, strength);
            fired = strength;
            since = 0;
        }
        flux = f;
        return fired;
    }

    static double clamp(double v, double a, double b) {
        return v < a ? a : (v > b ? b : v);
    }
}

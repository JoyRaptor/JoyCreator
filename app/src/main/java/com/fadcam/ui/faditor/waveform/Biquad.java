package com.fadcam.ui.faditor.waveform;

/**
 * A single RBJ-cookbook biquad filter (Direct Form I), the building block of the quad-band
 * tape waveform's frequency split. The HTML prototype used WebAudio {@code BiquadFilter}
 * nodes (Q=0.707, doubled per crossover for a 4th-order slope); this is the equivalent pure
 * time-domain IIR so the same split runs inside our PCM decode loop with no WebAudio.
 *
 * <p>One instance is one 2nd-order stage; cascade two ({@link #process} in series) to match
 * the prototype's doubled filters. Not thread-safe — each stage carries its own 2-sample
 * history, so a filter chain belongs to exactly one decode pass.</p>
 */
public final class Biquad {

    /** Butterworth-ish Q the prototype uses for every crossover stage. */
    public static final double DEFAULT_Q = 0.707;

    private final float b0, b1, b2, a1, a2;
    // Direct Form I history (input x[n-1], x[n-2]; output y[n-1], y[n-2]).
    private float x1, x2, y1, y2;

    private Biquad(double b0, double b1, double b2, double a0, double a1, double a2) {
        // Normalize by a0 up front so process() is a few mults + adds.
        this.b0 = (float) (b0 / a0);
        this.b1 = (float) (b1 / a0);
        this.b2 = (float) (b2 / a0);
        this.a1 = (float) (a1 / a0);
        this.a2 = (float) (a2 / a0);
    }

    /** Low-pass at {@code freqHz} for the given sample rate (RBJ cookbook, {@link #DEFAULT_Q}). */
    public static Biquad lowpass(int sampleRate, double freqHz) {
        double w0 = 2 * Math.PI * clampFreq(freqHz, sampleRate) / sampleRate;
        double cos = Math.cos(w0), sin = Math.sin(w0);
        double alpha = sin / (2 * DEFAULT_Q);
        double b1 = 1 - cos;
        double b0 = b1 / 2, b2 = b1 / 2;
        return new Biquad(b0, b1, b2, 1 + alpha, -2 * cos, 1 - alpha);
    }

    /** High-pass at {@code freqHz} for the given sample rate (RBJ cookbook, {@link #DEFAULT_Q}). */
    public static Biquad highpass(int sampleRate, double freqHz) {
        double w0 = 2 * Math.PI * clampFreq(freqHz, sampleRate) / sampleRate;
        double cos = Math.cos(w0), sin = Math.sin(w0);
        double alpha = sin / (2 * DEFAULT_Q);
        double b0 = (1 + cos) / 2, b2 = (1 + cos) / 2, b1 = -(1 + cos);
        return new Biquad(b0, b1, b2, 1 + alpha, -2 * cos, 1 - alpha);
    }

    /** One sample through the filter, advancing history. */
    public float process(float x) {
        float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
        x2 = x1;
        x1 = x;
        y2 = y1;
        y1 = y;
        return y;
    }

    /** Zero the history (reuse the coefficients for a fresh signal). */
    public void reset() {
        x1 = x2 = y1 = y2 = 0f;
    }

    /** Keep the corner strictly inside (0, Nyquist) so coefficients stay stable. */
    private static double clampFreq(double freqHz, int sampleRate) {
        double nyq = sampleRate / 2.0;
        return Math.max(1.0, Math.min(freqHz, nyq - 1.0));
    }

    /**
     * Build a cascaded band filter chain for one of the four bands, mirroring the prototype's
     * {@code renderBand}: each crossover is a DOUBLED stage (4th-order). {@code kind}:
     * {@code 0}=bass (LP low ×2), {@code 1}=voice (HP low ×2 + LP midTop ×2),
     * {@code 2}=presence (HP pres ×2 + LP high ×2), {@code 3}=highs (HP high ×2).
     * {@code midTopHz} is {@code presHz} when the presence band is on, else {@code highHz}.
     */
    public static Biquad[] bandChain(int kind, int sampleRate,
                                     double lowHz, double presHz, double highHz, double midTopHz) {
        switch (kind) {
            case 0: // bass
                return new Biquad[]{lowpass(sampleRate, lowHz), lowpass(sampleRate, lowHz)};
            case 1: // voice
                return new Biquad[]{highpass(sampleRate, lowHz), highpass(sampleRate, lowHz),
                        lowpass(sampleRate, midTopHz), lowpass(sampleRate, midTopHz)};
            case 2: // presence (upper voice)
                return new Biquad[]{highpass(sampleRate, presHz), highpass(sampleRate, presHz),
                        lowpass(sampleRate, highHz), lowpass(sampleRate, highHz)};
            case 3: // highs
                return new Biquad[]{highpass(sampleRate, highHz), highpass(sampleRate, highHz)};
            default:
                return new Biquad[0];
        }
    }

    /** RBJ peaking EQ at {@code freqHz} with {@code gainDb} and {@code q}. */
    public static Biquad peaking(int sampleRate, double freqHz, double gainDb, double q) {
        double A = Math.pow(10, gainDb / 40.0);
        double w0 = 2 * Math.PI * clampFreq(freqHz, sampleRate) / sampleRate;
        double cos = Math.cos(w0), sin = Math.sin(w0);
        double alpha = sin / (2 * Math.max(0.1, q));
        double b0 = 1 + alpha * A;
        double b1 = -2 * cos;
        double b2 = 1 - alpha * A;
        double a0 = 1 + alpha / A;
        double a1 = -2 * cos;
        double a2 = 1 - alpha / A;
        return new Biquad(b0, b1, b2, a0, a1, a2);
    }

    /** Run one sample through a whole cascade in series. */
    public static float processChain(Biquad[] chain, float x) {
        float v = x;
        for (Biquad b : chain) v = b.process(v);
        return v;
    }
}

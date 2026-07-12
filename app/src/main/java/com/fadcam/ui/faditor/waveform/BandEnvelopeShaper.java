package com.fadcam.ui.faditor.waveform;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.BandedWaveformData;

/**
 * Turns raw per-band RMS ({@link BandedWaveformData}) into shaped 0..1 profiles the tape
 * renderer draws — a 1:1 port of the prototype's {@code shapeEnvelopes}: dB-map into a
 * perceptual window, gate the floor and rescale, gamma for contrast, then per-band
 * attack/release ballistics so you see the PROFILE of the sound (no hair, no blobs).
 *
 * <p>This is the CHEAP half of the pipeline — it runs on every settings change (contrast,
 * smooth, per-band normalize) without re-decoding or re-filtering. Only a crossover change
 * forces a fresh {@link BandWaveformExtractor} pass.</p>
 */
public final class BandEnvelopeShaper {

    /** Per-band tuning [attack ms, release ms, dB range, gate (0..1 of range), gamma]. */
    private static final float[][] TUNE = {
            //  atk  rel  range  gate  gamma
            {10f, 85f, 32f, 0.06f, 1.00f}, // bass — slow lobes, beat profile
            {6f, 55f, 40f, 0.10f, 1.10f},  // voice — syllable profile
            {3f, 45f, 42f, 0.12f, 1.15f},  // presence — upper-vocal clarity
            {2f, 35f, 42f, 0.15f, 1.25f},  // highs — crisp consonants, hiss gated
    };

    private BandEnvelopeShaper() {}

    /**
     * Shape all bands of {@code data}.
     *
     * @param smoothMul   ballistics multiplier (prototype "Smooth" slider; 0.05 ≈ raw, 0.2 default).
     * @param contrastMul gamma multiplier (prototype "Contrast" slider; 1.15 default).
     * @param perBandNormalize {@code true} → each band normalized to its own peak (lively);
     *                    {@code false} → all bands normalized to the global peak (true relative
     *                    loudness). Mirrors the prototype's {@code perBand} toggle.
     * @return {@code shaped[band][frame]} in 0..1; a band is {@code null} where the source was.
     */
    @NonNull
    public static float[][] shape(@NonNull BandedWaveformData data, float smoothMul,
                                  float contrastMul, boolean perBandNormalize) {
        smoothMul = Math.max(0.05f, smoothMul);
        contrastMul = Math.max(0.01f, contrastMul);
        float dt = 1f / data.envRate;

        float[] peaks = new float[BandedWaveformData.BAND_COUNT];
        float globalPeak = 1e-9f;
        for (int b = 0; b < BandedWaveformData.BAND_COUNT; b++) {
            float m = 1e-9f;
            float[] e = data.band(b);
            if (e != null) {
                for (float v : e) if (v > m) m = v;
            }
            peaks[b] = m;
            if (m > globalPeak) globalPeak = m;
        }

        float[][] out = new float[BandedWaveformData.BAND_COUNT][];
        for (int b = 0; b < BandedWaveformData.BAND_COUNT; b++) {
            float[] e = data.band(b);
            if (e == null) {
                out[b] = null;
                continue;
            }
            float ref = perBandNormalize ? peaks[b] : globalPeak;
            // TRUE SILENCE: a band with no real signal must draw a FLAT BASELINE, not a
            // full-height slab. Without this, normalize-by-own-peak turns digital silence
            // (peak≈1e-9) into dB≈0 → v≈1 everywhere — the "solid gradient" bug on a
            // silent-audio-track source (found on a muxed-silence AI clip, 2026-07-11).
            if (ref < TRUE_SILENCE_RMS) {
                out[b] = new float[e.length]; // all zeros
                continue;
            }
            out[b] = shapeBand(e, ref, TUNE[b], dt, smoothMul, contrastMul);
        }
        return out;
    }

    /**
     * RMS below this is treated as no signal at all (≈ −100 dBFS; true digital silence is
     * exactly 0, real recordings' noise floors sit orders of magnitude above this).
     */
    private static final float TRUE_SILENCE_RMS = 1e-5f;

    @NonNull
    private static float[] shapeBand(@NonNull float[] e, float ref, @NonNull float[] tune,
                                     float dt, float smoothMul, float contrastMul) {
        float atkMs = tune[0], relMs = tune[1], range = tune[2], gate = tune[3], gamma = tune[4];
        // Attack/release smoothing coefficients (one-pole, ballistics on the dB-mapped value).
        double aAtk = 1 - Math.exp(-dt / (atkMs / 1000.0 * smoothMul));
        double aRel = 1 - Math.exp(-dt / (relMs / 1000.0 * smoothMul));
        float safeRef = Math.max(ref, 1e-9f);
        float[] out = new float[e.length];
        float s = 0f;
        for (int i = 0; i < e.length; i++) {
            // dB map first (profile lives in perceptual space), then ballistics on that.
            double db = 20 * Math.log10(Math.max(e[i], 1e-9f) / safeRef);
            float v = (float) Math.max(0, (db + range) / range);
            // Gate: cut the floor, rescale so surviving shapes still reach full height.
            v = Math.max(0f, (v - gate) / (1 - gate));
            v = (float) Math.pow(v, gamma * contrastMul);
            double a = v > s ? aAtk : aRel;
            s += (v - s) * a;
            out[i] = s;
        }
        return out;
    }

    /** Default smooth (prototype "Smooth" slider default). */
    public static final float DEFAULT_SMOOTH = 0.2f;
    /** Default contrast (prototype "Contrast" slider default). */
    public static final float DEFAULT_CONTRAST = 1.15f;

    /** Convenience overload using the locked-in defaults. */
    @NonNull
    public static float[][] shapeDefault(@NonNull BandedWaveformData data,
                                         boolean perBandNormalize) {
        return shape(data, DEFAULT_SMOOTH, DEFAULT_CONTRAST, perBandNormalize);
    }

    /**
     * A1 + A2: shape, then build the quantized max-pool mip pyramid the renderer reads from.
     * The pyramid halves the CPU of {@code columns()} when zoomed out and stores the shaped
     * product as unsigned bytes (~4x memory cut). Raw RMS is untouched.
     */
    @NonNull
    public static ShapedTape shapeToTape(@NonNull BandedWaveformData data, float smoothMul,
                                         float contrastMul, boolean perBandNormalize) {
        return ShapedTape.build(shape(data, smoothMul, contrastMul, perBandNormalize));
    }

    /** Null-safe frame lookup helper for a shaped band (clamped). */
    public static float at(@Nullable float[] band, int frame) {
        if (band == null || band.length == 0) return 0f;
        return band[Math.max(0, Math.min(frame, band.length - 1))];
    }
}

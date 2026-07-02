package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;

/**
 * Extracted, cacheable audio analysis driving a {@link WaveformOverlayInstance}.
 *
 * <p>Holds two parallel representations sampled into fixed-length time buckets:
 * <ul>
 *   <li>{@link #amplitudes} — peak amplitude (0..1) per bucket, for bar/line/wave looks.</li>
 *   <li>{@link #spectrum} — FFT magnitude (0..1) per bucket per frequency band, for the
 *       classic spectrum-analyzer look.</li>
 * </ul>
 * This is the single thing the extractor produces and the cache stores; rendering is cheap
 * and computed per frame from it (never cached as a single output bitmap).</p>
 */
public class WaveformData {

    /** Peak amplitude per time bucket, normalized 0..1. */
    @NonNull
    public final float[] amplitudes;

    /** FFT magnitude per time bucket per band: {@code spectrum[bucket][band]}, normalized 0..1. */
    @NonNull
    public final float[][] spectrum;

    /** Source audio duration in milliseconds (full source, even when only a span was extracted). */
    public final long durationMs;

    /** Milliseconds covered by one bucket. */
    public final long bucketMs;

    /**
     * Source time (ms) of the first bucket. 0 for a full-source extraction; non-zero when only the
     * span covered by a trimmed clip was extracted, so {@link #bucketAt(long)} can offset correctly.
     */
    public final long startOffsetMs;

    public WaveformData(@NonNull float[] amplitudes, @NonNull float[][] spectrum,
                        long durationMs) {
        this(amplitudes, spectrum, durationMs, 0L,
                Math.max(1, amplitudes.length > 0
                        ? Math.max(1, durationMs) / amplitudes.length : Math.max(1, durationMs)));
    }

    public WaveformData(@NonNull float[] amplitudes, @NonNull float[][] spectrum,
                        long durationMs, long startOffsetMs, long bucketMs) {
        this.amplitudes = amplitudes;
        this.spectrum = spectrum;
        this.durationMs = Math.max(1, durationMs);
        this.startOffsetMs = Math.max(0, startOffsetMs);
        this.bucketMs = Math.max(1, bucketMs);
    }

    public int bucketCount() {
        return amplitudes.length;
    }

    public int bandCount() {
        return spectrum.length > 0 ? spectrum[0].length : 0;
    }

    /** Bucket index covering the given source timestamp, clamped to valid range. */
    public int bucketAt(long atMs) {
        if (amplitudes.length == 0) return 0;
        int idx = (int) ((atMs - startOffsetMs) / bucketMs);
        return Math.max(0, Math.min(idx, amplitudes.length - 1));
    }

    /** Amplitude (0..1) at a timestamp. */
    public float amplitudeAt(long atMs) {
        return amplitudes.length == 0 ? 0f : amplitudes[bucketAt(atMs)];
    }

    /** Spectrum band magnitudes (0..1) at a timestamp; empty array if no spectrum. */
    @NonNull
    public float[] spectrumAt(long atMs) {
        if (spectrum.length == 0) return new float[0];
        return spectrum[bucketAt(atMs)];
    }
}

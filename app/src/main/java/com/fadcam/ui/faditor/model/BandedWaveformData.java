package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Quad-band tape-waveform analysis: the RAW (linear) RMS envelope of each frequency band,
 * before any perceptual shaping. This is the expensive-to-produce, cacheable artifact — the
 * crossover filtering happened at extraction time, so changing crossovers means re-extracting,
 * but every cheap knob (contrast, smooth, gate, per-band normalize, color, lane, FX) is applied
 * later by {@code BandEnvelopeShaper} + the renderer straight off this data. Mirrors the HTML
 * prototype's {@code rawEnvs}.
 *
 * <p>Band order is fixed: {@code 0}=bass, {@code 1}=voice, {@code 2}=presence, {@code 3}=highs.
 * A band's envelope is {@code null} only when it was intentionally not computed (presence off).</p>
 */
public class BandedWaveformData {

    public static final int BAND_BASS = 0;
    public static final int BAND_VOICE = 1;
    public static final int BAND_PRESENCE = 2;
    public static final int BAND_HIGHS = 3;
    public static final int BAND_COUNT = 4;

    /** Raw linear RMS per band per frame: {@code rms[band][frame]}; a band may be {@code null}. */
    @NonNull
    public final float[][] rms;

    /** Envelope frames per second (== sampleRate / hop). */
    public final float envRate;

    /** Full source duration in ms (even when only a span was extracted). */
    public final long durationMs;

    /** Source time (ms) of frame 0 — non-zero when only a trimmed clip's span was extracted. */
    public final long startOffsetMs;

    /** The three crossover frequencies (Hz) the split used: {@code [low, presence, high]}. */
    @NonNull
    public final int[] crossoversHz;

    /** Whether the presence band was computed (false → {@code rms[2]} is null, voice spans to high). */
    public final boolean presenceOn;

    public BandedWaveformData(@NonNull float[][] rms, float envRate, long durationMs,
                              long startOffsetMs, @NonNull int[] crossoversHz, boolean presenceOn) {
        this.rms = rms;
        this.envRate = Math.max(1f, envRate);
        this.durationMs = Math.max(1, durationMs);
        this.startOffsetMs = Math.max(0, startOffsetMs);
        this.crossoversHz = crossoversHz;
        this.presenceOn = presenceOn;
    }

    /** Frame count of the first non-null band (0 if none). */
    public int frameCount() {
        for (float[] band : rms) {
            if (band != null) return band.length;
        }
        return 0;
    }

    @Nullable
    public float[] band(int index) {
        return (index >= 0 && index < rms.length) ? rms[index] : null;
    }

    /** Frame index covering an absolute source timestamp, clamped. */
    public int frameAt(long atMs) {
        int frames = frameCount();
        if (frames == 0) return 0;
        int idx = (int) ((atMs - startOffsetMs) * envRate / 1000f);
        return Math.max(0, Math.min(idx, frames - 1));
    }
}

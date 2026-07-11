package com.fadcam.ui.faditor.waveform;

import com.fadcam.ui.faditor.model.BandedWaveformData;

/**
 * The global look of the quad-band tape waveform — the knobs the prototype exposed in its
 * control panel, with JoyRaptor's locked-in defaults. One instance is shared by every audio
 * preview in a project (AV4 will make it user-editable under Settings → "Waveform visualizer"
 * and persist it; for now the defaults are baked so AV2 can render).
 *
 * <p>Lane values: {@code -1} = above the baseline (top lane), {@code +1} = below. Within a lane
 * bands always layer low→high (lowest = solid back, higher = translucent overlays).</p>
 */
public final class TapeWaveformStyle {

    // ── Crossovers (Hz) ──
    public int lowHz = BandWaveformExtractor.DEFAULT_LOW;   // 110
    public int presHz = BandWaveformExtractor.DEFAULT_PRES; // 950
    public int highHz = BandWaveformExtractor.DEFAULT_HIGH; // 6300
    public boolean presenceOn = true;

    // ── Per-band colors (bass, voice, presence, highs) ──
    public final int[] bandColor = {
            0xFFFF4D42, // bass  — red
            0xFF57A8FF, // voice — sky blue
            0xFF3EE06E, // presence — green
            0xFFF5D442, // highs — yellow
    };

    // ── Per-band lane (-1 top / +1 below): vocals+presence up, bass+highs down ──
    public final int[] bandLane = {+1, -1, -1, +1};

    // ── Enable per band ──
    public final boolean[] bandOn = {true, true, true, true};

    // ── Shaping ──
    public float smooth = BandEnvelopeShaper.DEFAULT_SMOOTH;     // 0.2
    public float contrast = BandEnvelopeShaper.DEFAULT_CONTRAST; // 1.15
    public boolean perBandNormalize = true;

    // ── FX toggles ──
    public boolean fxGlow = true;    // baseline colored bloom
    public boolean fxRound = true;   // smooth quadratic profile edge
    public boolean fxSparks = true;  // glowing dots on local maxima
    public boolean fxTint = false;   // lift overlay where it crosses the solid band

    /** midTop crossover the voice band ends at: presence-start when on, else highs-start. */
    public int midTopHz() {
        return presenceOn ? presHz : highHz;
    }

    /** True if band index is enabled AND (for presence) computed. */
    public boolean bandVisible(int band) {
        if (band < 0 || band >= BandedWaveformData.BAND_COUNT) return false;
        if (!bandOn[band]) return false;
        return band != BandedWaveformData.BAND_PRESENCE || presenceOn;
    }
}

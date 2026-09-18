package com.fadcam.ui.faditor.waveform;

import com.fadcam.ui.faditor.model.BandedWaveformData;
import com.fadcam.ui.faditor.Studio;

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
            Studio.GO, // presence — green
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

    // ── Analysis timing ──
    /** false = lazy (analyze the waveform the first time a clip's audio is opened);
     *  true = eager (analyze immediately at import). Persisted, not used by the renderer. */
    public boolean analyzeEager = false;

    // ── Persistence (SharedPreferences round-trip) ──
    /** Stable key prefix for every persisted field. */
    private static final String K = "wave_viz_";

    /**
     * Overwrite every field from {@code p}, falling back to the current (default) value of each
     * field when its key is absent — so a partially-written store still yields a coherent style.
     */
    public void loadFrom(android.content.SharedPreferences p) {
        if (p == null) return;
        lowHz = p.getInt(K + "low", lowHz);
        presHz = p.getInt(K + "pres", presHz);
        highHz = p.getInt(K + "high", highHz);
        presenceOn = p.getBoolean(K + "presenceOn", presenceOn);

        for (int i = 0; i < bandColor.length; i++) {
            bandColor[i] = p.getInt(K + "bandColor" + i, bandColor[i]);
            bandLane[i] = p.getInt(K + "bandLane" + i, bandLane[i]);
            bandOn[i] = p.getBoolean(K + "bandOn" + i, bandOn[i]);
        }

        smooth = p.getFloat(K + "smooth", smooth);
        contrast = p.getFloat(K + "contrast", contrast);
        perBandNormalize = p.getBoolean(K + "perBandNormalize", perBandNormalize);

        fxGlow = p.getBoolean(K + "fxGlow", fxGlow);
        fxRound = p.getBoolean(K + "fxRound", fxRound);
        fxSparks = p.getBoolean(K + "fxSparks", fxSparks);
        fxTint = p.getBoolean(K + "fxTint", fxTint);

        analyzeEager = p.getBoolean(K + "analyzeEager", analyzeEager);
    }

    /** Write every field to {@code p} under the stable {@code wave_viz_} keys. */
    public void saveTo(android.content.SharedPreferences p) {
        if (p == null) return;
        android.content.SharedPreferences.Editor e = p.edit();
        e.putInt(K + "low", lowHz);
        e.putInt(K + "pres", presHz);
        e.putInt(K + "high", highHz);
        e.putBoolean(K + "presenceOn", presenceOn);

        for (int i = 0; i < bandColor.length; i++) {
            e.putInt(K + "bandColor" + i, bandColor[i]);
            e.putInt(K + "bandLane" + i, bandLane[i]);
            e.putBoolean(K + "bandOn" + i, bandOn[i]);
        }

        e.putFloat(K + "smooth", smooth);
        e.putFloat(K + "contrast", contrast);
        e.putBoolean(K + "perBandNormalize", perBandNormalize);

        e.putBoolean(K + "fxGlow", fxGlow);
        e.putBoolean(K + "fxRound", fxRound);
        e.putBoolean(K + "fxSparks", fxSparks);
        e.putBoolean(K + "fxTint", fxTint);

        e.putBoolean(K + "analyzeEager", analyzeEager);
        e.apply();
    }

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

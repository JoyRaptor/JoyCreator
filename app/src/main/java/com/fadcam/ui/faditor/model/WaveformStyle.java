package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Visual style for a waveform/spectrum visualizer. Plain Gson POJO so it round-trips to the
 * built-in {@code assets/waveform_styles/*.json} presets and to user-imported/exported style
 * files (SAF), mirroring the text-style pattern.
 *
 * <p>Defaults are chosen so a partially-specified JSON still renders something sane.</p>
 */
public class WaveformStyle {

    /** Render shapes. */
    public static final String TYPE_BARS = "bars";
    public static final String TYPE_LINE = "line";
    public static final String TYPE_MIRROR_BARS = "mirror_bars";
    public static final String TYPE_FILLED_WAVE = "filled_wave";
    public static final String TYPE_SPECTRUM_BARS = "spectrum_bars";
    /** Spectrum laid out symmetrically: low frequencies in the CENTER, mirrored outward. */
    public static final String TYPE_SPECTRUM_MIRROR = "spectrum_mirror";

    public String id = "untitled";
    public String displayName = "Untitled";
    public String type = TYPE_BARS;

    /** Primary color (hex, e.g. "#00E676"). */
    public String color = "#00E676";
    /** Optional vertical gradient endpoints; fall back to {@link #color} when null. */
    @Nullable public String gradientStart;
    @Nullable public String gradientEnd;

    public float barWidthDp = 3f;
    public float barGapDp = 1f;
    public float cornerRadiusDp = 2f;

    /** Optional glow; ignored when {@link #glowRadiusDp} <= 0. */
    @Nullable public String glowColor;
    public float glowRadiusDp = 0f;

    /** Number of bars / spectrum bands to draw across the width. */
    public int bandCount = 48;
    /** Multiplies amplitude before drawing (visual gain). */
    public float sensitivity = 1.0f;
    /** When true (or type == spectrum_bars), draw from the FFT spectrum instead of amplitude. */
    public boolean useSpectrum = false;

    /**
     * Joy Viz Engine (SPEC_VIZ_ENGINE §4): an ordered stack of {@link VizLayer}s. {@code null}
     * (NOT empty) = legacy single-shape — the renderer auto-wraps the fields above into a
     * transient one-layer stack, and the legacy JSON stays byte-for-byte untouched (this field is
     * {@code transient} so Gson never emits it; {@link com.fadcam.ui.faditor.waveform.WaveformStyleIO}
     * serializes the stack by hand when present).
     */
    @Nullable public transient java.util.List<VizLayer> layers;

    public boolean drawsSpectrum() {
        return useSpectrum || TYPE_SPECTRUM_BARS.equals(type) || TYPE_SPECTRUM_MIRROR.equals(type);
    }

    /** Shallow copy, so a per-visualizer override (e.g. a custom colour) doesn't mutate the shared preset. */
    @NonNull
    public WaveformStyle copy() {
        WaveformStyle s = new WaveformStyle();
        s.id = id;
        s.displayName = displayName;
        s.type = type;
        s.color = color;
        s.gradientStart = gradientStart;
        s.gradientEnd = gradientEnd;
        s.barWidthDp = barWidthDp;
        s.barGapDp = barGapDp;
        s.cornerRadiusDp = cornerRadiusDp;
        s.glowColor = glowColor;
        s.glowRadiusDp = glowRadiusDp;
        s.bandCount = bandCount;
        s.sensitivity = sensitivity;
        s.useSpectrum = useSpectrum;
        if (layers != null) {
            s.layers = new java.util.ArrayList<>(layers.size());
            for (VizLayer l : layers) s.layers.add(l.copy());
        }
        return s;
    }

    /**
     * Returns a copy with a solid colour override applied (gradient + glow cleared so the chosen
     * colour wins), or {@code this} when {@code hex} is null/empty.
     */
    @NonNull
    public WaveformStyle withColorOverride(@Nullable String hex) {
        if (hex == null || hex.isEmpty()) return this;
        WaveformStyle s = copy();
        s.color = hex;
        s.gradientStart = null;
        s.gradientEnd = null;
        s.glowColor = hex;
        // Layered rendering (SPEC_VIZ_ENGINE §4) ignores the top-level style.color; push the override
        // onto layer 0 so the chosen colour actually shows. Clear that layer's gradient/multi-stop so
        // the solid colour wins (copy() already deep-copied the stack, so the preset is untouched).
        if (s.layers != null && !s.layers.isEmpty()) {
            VizLayer l0 = s.layers.get(0);
            l0.color = hex;
            l0.gradientStart = null;
            l0.gradientEnd = null;
            l0.gradientStops = null;
        }
        return s;
    }

    /** Returns a copy with a vertical gradient applied (clears glow so the gradient reads cleanly). */
    @NonNull
    public WaveformStyle withGradientOverride(@Nullable String start, @Nullable String end) {
        if (start == null || start.isEmpty() || end == null || end.isEmpty()) return this;
        WaveformStyle s = copy();
        s.gradientStart = start;
        s.gradientEnd = end;
        s.color = start;
        s.glowColor = null;
        s.glowRadiusDp = 0f;
        // Same as withColorOverride: layered rendering ignores the top-level fields, so mirror the
        // gradient onto layer 0 (its amplitude-axis 2-stop). A multi-stop on layer 0 is superseded.
        if (s.layers != null && !s.layers.isEmpty()) {
            VizLayer l0 = s.layers.get(0);
            l0.gradientStart = start;
            l0.gradientEnd = end;
            l0.gradientStops = null;
            l0.color = start;
        }
        return s;
    }

    /**
     * Returns a copy with the visual gain overridden, or {@code this} when {@code gain} is &lt;= 0.
     * No per-layer handling needed: {@code sensitivity} is read once in the renderer's shared
     * {@code sampleHeights}, so it already scales the band energies every layer draws from.
     */
    @NonNull
    public WaveformStyle withSensitivity(float gain) {
        if (gain <= 0f) return this;
        WaveformStyle s = copy();
        s.sensitivity = gain;
        return s;
    }

    @NonNull
    @Override
    public String toString() {
        return "WaveformStyle{" + id + " " + type + "}";
    }
}

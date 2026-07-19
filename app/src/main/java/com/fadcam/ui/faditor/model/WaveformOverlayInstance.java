package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.UUID;

/**
 * A placed waveform/spectrum visualizer on the timeline. Mirrors {@link TextOverlayItem}'s
 * placement model (normalized center + size + rotation + time range) and references a
 * {@link WaveformStyle} by id plus the clip/audio whose {@link WaveformData} drives it.
 *
 * <p>The extracted {@link WaveformData} is NOT stored here — it's cached on disk per source and
 * recomputed by {@code WaveformExtractor}; this instance only carries the placement + style.</p>
 */
public class WaveformOverlayInstance {

    // §4.5 per-OBJECT visibility/lock (LANE_BADGES spec, built 2026-07-19) — see
    // TextOverlayItem's twin fields. Tolerant storage: absent = false.
    private boolean hidden;
    private boolean locked;

    public boolean isHidden() { return hidden; }
    public void setHidden(boolean hidden) { this.hidden = hidden; }
    public boolean isLocked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }

    @NonNull
    private final String id;

    /** Built-in or user style id (see {@link WaveformStyle#id}). */
    @NonNull
    private String styleId;

    /** Id of the {@link Clip} (or AudioClip) whose audio drives this visualizer. */
    @Nullable
    private String audioSourceRef;

    /** Visible time range on the timeline (ms). */
    private long startMs = 0;
    private long endMs = Long.MAX_VALUE;

    // ── G5 attach/detach (gesture contract §4, Axis 1) ─────────────────────────
    // When attached, this visualizer TIME-RIDES its host master clip: [startMs,endMs]
    // remain the storage-of-record every consumer reads, but they are RE-DERIVED from
    // the host's CURRENT on-timeline span by Timeline#resyncAttachedVisualizers() (the
    // single write point — runs after every edit/load and before export) using the
    // host-relative fields below. Detached (attachedClipId == null) instances keep
    // their absolute window untouched — exactly the pre-G5 behavior.
    /** Id of the MASTER clip this visualizer is tethered to, or null = detached. */
    @Nullable
    private String attachedClipId;
    /** Start offset (ms) within the host clip's on-timeline span. Attached only. */
    private long attachOffsetMs = 0;
    /** Window length (ms) while attached; {@link Long#MAX_VALUE} = ride to the host's end. */
    private long attachDurationMs = Long.MAX_VALUE;
    /**
     * Contract §4 Axis-2 (visual tether, meaningful only while attached): false = PIGGYBACK
     * (default — rides the host in time AND inherits host looks, e.g. fades), true = STRATIFIED
     * (rides in time only, floats above host compositing). Added ahead of its own toggle UI so
     * G9's preset link groups have a real field to discriminate on (PLAN_G9_LINK_ENGINE.md §4.3;
     * flagged to JoyRaptor as open question §8.2). Looks-inheritance itself is the G5 compositing
     * fast-follow — this field only STORES the choice.
     */
    private boolean stratified = false;

    // ── Orthogonal architecture overrides (decoupled from the gradient/look "style") ──
    // Each is quick-cycled by a toggle button so a user can keep a gradient they like and only change
    // the aspect that bothers them. -1 / default means "use the style's own default".
    /** Vertical anchoring: -1=style default, 0=bottom, 1=center (mirror), 2=top. */
    private int justify = -1;
    /** Data/behaviour: -1=style default, 0=amplitude wave-flow, 1=static frequency spectrum. */
    private int dataMode = -1;
    /** Flip the bands/wave left↔right (e.g. bass↔treble for spectrum). */
    private boolean horizontalMirror = false;

    /** Frequency centre mode: -1=off (linear), 0=centre-low (bass at centre, treble at edges), 1=centre-high (treble at centre, bass at edges). */
    private int centerMode = -1;
    /** Render shape: 0=linear bars/wave, 1=radial (bars shoot outward from a centre ring). */
    private int renderMode = 0;
    /** Size of the centre ring as a fraction [0,1] of the minimum canvas dimension. Only used when renderMode == 1. */
    private float radialRingSize = 0.35f;

    /** Optional per-visualizer colour override (hex). Null = use the style preset's own colour. */
    @Nullable
    private String colorOverride;

    /** Optional per-visualizer sensitivity (visual gain). 0 = use the style preset's own value. */
    private float sensitivityOverride = 0f;

    /** Optional per-visualizer gradient override (hex start/end). Null = use the preset / solid colour. */
    @Nullable private String gradientStartOverride;
    @Nullable private String gradientEndOverride;

    /**
     * Joy Viz Engine (SPEC_VIZ_ENGINE §4, Layers UI lane): a full {@link WaveformStyle} JSON —
     * WITH its layer stack — that this instance carries inline. When non-null it WINS over the
     * {@link #styleId} preset lookup everywhere the effective style is resolved (preview, export,
     * Save/Export-style) via
     * {@link com.fadcam.ui.faditor.waveform.WaveformStyleIO#resolveEffectiveStyle}. The
     * {@code styleId} stays underneath as the fallback if the JSON fails to parse, and picking a new
     * preset from the Rolodex clears this (back to preset + scalar overrides). Persisted verbatim by
     * {@code ProjectStorage} (absent = null, so every pre-Layers-UI project loads unchanged).
     */
    @Nullable private String customStyleJson;

    /** Normalized placement in canvas coords [0,1]. */
    private float centerX = 0.5f;
    private float centerY = 0.5f;
    private float widthFraction = 0.8f;
    private float heightFraction = 0.25f;
    private float rotationDeg = 0f;

    /**
     * Runtime-only mapping from output (edited) timeline time to the driving clip's SOURCE audio
     * time, so the visualizer reads the right part of the waveform when the clip is trimmed or
     * sped up. NOT persisted — the host refreshes it from the bound clip (inPoint + speed). Defaults
     * (in=0, speed=1) reproduce the old naive {@code playhead - start} mapping.
     */
    private transient long runtimeSourceInMs = 0;
    private transient float runtimeSpeed = 1f;
    /** If true, the source time wraps modulo runtimeTrimDurationMs (for looped clips). */
    private transient boolean runtimeHasLoopExtension = false;
    /** Trimmed duration of the driving clip (for loop wrapping). */
    private transient long runtimeTrimDurationMs = 0;

    public WaveformOverlayInstance(@NonNull String styleId) {
        this(UUID.randomUUID().toString(), styleId);
    }

    public WaveformOverlayInstance(@NonNull String id, @NonNull String styleId) {
        this.id = id;
        this.styleId = styleId;
    }

    @NonNull public String getId() { return id; }

    @NonNull public String getStyleId() { return styleId; }
    public void setStyleId(@NonNull String styleId) { this.styleId = styleId; }

    @Nullable public String getAudioSourceRef() { return audioSourceRef; }
    public void setAudioSourceRef(@Nullable String ref) { this.audioSourceRef = ref; }

    public long getStartMs() { return startMs; }
    public long getEndMs() { return endMs; }
    public void setTimeRange(long startMs, long endMs) {
        this.startMs = Math.max(0, startMs);
        this.endMs = Math.max(this.startMs + 1, endMs);
    }

    @Nullable public String getAttachedClipId() { return attachedClipId; }
    public void setAttachedClipId(@Nullable String clipId) { this.attachedClipId = clipId; }
    public boolean isAttached() { return attachedClipId != null; }
    public long getAttachOffsetMs() { return attachOffsetMs; }
    public void setAttachOffsetMs(long v) { this.attachOffsetMs = Math.max(0, v); }
    public long getAttachDurationMs() { return attachDurationMs; }
    public void setAttachDurationMs(long v) { this.attachDurationMs = Math.max(1, v); }
    public boolean isStratified() { return stratified; }
    public void setStratified(boolean v) { this.stratified = v; }

    public float getCenterX() { return centerX; }
    public float getCenterY() { return centerY; }
    public void setCenter(float x, float y) {
        this.centerX = clamp01(x);
        this.centerY = clamp01(y);
    }

    public float getWidthFraction() { return widthFraction; }
    public float getHeightFraction() { return heightFraction; }
    public void setSize(float widthFraction, float heightFraction) {
        this.widthFraction = Math.max(0.05f, Math.min(1f, widthFraction));
        this.heightFraction = Math.max(0.05f, Math.min(1f, heightFraction));
    }

    public float getRotationDeg() { return rotationDeg; }
    public void setRotationDeg(float deg) { this.rotationDeg = deg; }

    public int getJustify() { return justify; }
    public void setJustify(int justify) { this.justify = justify; }
    /** Cycle bottom → center → top. (Auto-snap-to-wall will later make Top mostly redundant.) */
    public void cycleJustify() { this.justify = ((justify < 0 ? 0 : justify) + 1) % 3; }

    public int getDataMode() { return dataMode; }
    public void setDataMode(int dataMode) { this.dataMode = dataMode; }
    /** Cycle amplitude wave ↔ frequency spectrum. */
    public void cycleDataMode() { this.dataMode = ((dataMode < 0 ? 0 : dataMode) + 1) % 2; }

    public boolean isHorizontalMirror() { return horizontalMirror; }
    public void setHorizontalMirror(boolean v) { this.horizontalMirror = v; }
    public void toggleHorizontalMirror() { this.horizontalMirror = !this.horizontalMirror; }

    public int getCenterMode() { return centerMode; }
    public void setCenterMode(int centerMode) { this.centerMode = centerMode; }
    /** Cycle off → centre-low → centre-high. */
    public void cycleCenterMode() {
        int v = centerMode < 0 ? 0 : centerMode + 1;
        if (v > 1) v = -1;
        this.centerMode = v;
    }

    public int getRenderMode() { return renderMode; }
    public void setRenderMode(int renderMode) { this.renderMode = renderMode; }
    /** Toggle 0↔1. */
    public void toggleRenderMode() { this.renderMode = this.renderMode == 0 ? 1 : 0; }

    public float getRadialRingSize() { return radialRingSize; }
    public void setRadialRingSize(float v) { this.radialRingSize = Math.max(0.05f, Math.min(0.95f, v)); }

    /** Lowest frequency to display (Hz). */
    private int frequencyRangeLowHz = 20;
    /** Highest frequency to display (Hz). */
    private int frequencyRangeHighHz = 20000;
    /** Override number of bars/bands (0 = use the style preset's bandCount). */
    private int bandCountOverride = 0;

    public int getFrequencyRangeLowHz() { return frequencyRangeLowHz; }
    public void setFrequencyRangeLowHz(int hz) { this.frequencyRangeLowHz = Math.max(1, Math.min(22000, hz)); }
    public int getFrequencyRangeHighHz() { return frequencyRangeHighHz; }
    public void setFrequencyRangeHighHz(int hz) { this.frequencyRangeHighHz = Math.max(frequencyRangeLowHz + 1, Math.min(22000, hz)); }
    public int getBandCountOverride() { return bandCountOverride; }
    public void setBandCountOverride(int v) { this.bandCountOverride = Math.max(0, Math.min(256, v)); }

    /** Visualizer-studio Phase 3: per-instance bar width/gap overrides (0 = use preset). */
    private float barWidthOverrideDp = 0f;
    private float barGapOverrideDp = 0f;

    public float getBarWidthOverrideDp() { return barWidthOverrideDp; }
    public void setBarWidthOverrideDp(float dp) { this.barWidthOverrideDp = Math.max(0f, Math.min(48f, dp)); }
    public float getBarGapOverrideDp() { return barGapOverrideDp; }
    public void setBarGapOverrideDp(float dp) { this.barGapOverrideDp = Math.max(0f, Math.min(24f, dp)); }

    @Nullable public String getColorOverride() { return colorOverride; }
    /** Setting a solid colour clears any gradient override (the two are mutually exclusive). */
    public void setColorOverride(@Nullable String hex) {
        this.colorOverride = (hex == null || hex.isEmpty()) ? null : hex;
        if (this.colorOverride != null) {
            this.gradientStartOverride = null;
            this.gradientEndOverride = null;
        }
    }

    public float getSensitivityOverride() { return sensitivityOverride; }
    public void setSensitivityOverride(float s) { this.sensitivityOverride = Math.max(0f, s); }

    @Nullable public String getCustomStyleJson() { return customStyleJson; }
    /** Set (or clear, when null/empty) the inline custom-layer-stack JSON — see {@link #customStyleJson}. */
    public void setCustomStyleJson(@Nullable String json) {
        this.customStyleJson = (json == null || json.isEmpty()) ? null : json;
    }

    @Nullable public String getGradientStartOverride() { return gradientStartOverride; }
    @Nullable public String getGradientEndOverride() { return gradientEndOverride; }
    /** Setting a gradient clears any solid colour override (mutually exclusive). Null pair clears it. */
    public void setGradientOverride(@Nullable String start, @Nullable String end) {
        if (start == null || start.isEmpty() || end == null || end.isEmpty()) {
            this.gradientStartOverride = null;
            this.gradientEndOverride = null;
            return;
        }
        this.gradientStartOverride = start;
        this.gradientEndOverride = end;
        this.colorOverride = null;
    }

    /** Apply this instance's per-visualizer overrides onto a base preset style (preview + export share this). */
    @NonNull
    public WaveformStyle applyOverrides(@NonNull WaveformStyle base) {
        WaveformStyle s = base;
        if (gradientStartOverride != null && gradientEndOverride != null) {
            s = s.withGradientOverride(gradientStartOverride, gradientEndOverride);
        } else if (colorOverride != null) {
            s = s.withColorOverride(colorOverride);
        }
        if (sensitivityOverride > 0f) s = s.withSensitivity(sensitivityOverride);
        if (barWidthOverrideDp > 0f || barGapOverrideDp > 0f) {
            // The with-* helpers above return copies; if none applied we must copy
            // before mutating so the shared preset object is never written through.
            if (s == base) s = base.copy();
            if (barWidthOverrideDp > 0f) s.barWidthDp = barWidthOverrideDp;
            if (barGapOverrideDp > 0f) s.barGapDp = barGapOverrideDp;
        }
        return s;
    }

    /** Set the source-time mapping from the bound clip (runtime only, not persisted). */
    public void setSourceMapping(long sourceInMs, float speed) {
        this.runtimeSourceInMs = Math.max(0, sourceInMs);
        this.runtimeSpeed = speed <= 0 ? 1f : speed;
    }

    public long getRuntimeSourceInMs() { return runtimeSourceInMs; }
    public float getRuntimeSpeed() { return runtimeSpeed; }

    /** Enable loop extension wrapping so the visualizer repeats its animation
     *  through the extension region. */
    public void setLoopExtension(long trimDurationMs) {
        this.runtimeHasLoopExtension = trimDurationMs > 0;
        this.runtimeTrimDurationMs = Math.max(1, trimDurationMs);
    }

    /**
     * Map an output (edited) timeline timestamp to the driving clip's absolute SOURCE audio time,
     * accounting for the clip's trim in-point and speed: {@code in + (outputMs - start) * speed}.
     * For clips with loop extension, the output-time position wraps modulo the trimmed duration
     * so the visualizer continues to animate through the extension region; speed is applied
     * AFTER wrapping to avoid shortening the source cycle.
     */
    public long mapToSourceMs(long outputMs) {
        long local = Math.max(0, outputMs - startMs);
        if (!runtimeHasLoopExtension || runtimeTrimDurationMs <= 0) {
            return runtimeSourceInMs + (long) (local * runtimeSpeed);
        }
        long offsetInCycle = local % runtimeTrimDurationMs;
        return runtimeSourceInMs + (long) (offsetInCycle * runtimeSpeed);
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}

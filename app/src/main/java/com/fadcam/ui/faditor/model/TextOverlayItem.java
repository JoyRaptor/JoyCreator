package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.graphics.Color;

import java.util.UUID;

/**
 * A text overlay rendered on top of the whole timeline (CapCut-style title/caption).
 *
 * <p>Position is stored as the overlay's CENTER in normalised video-content
 * coordinates ([0,1] where 0,0 is the top-left of the visible video and 1,1 is
 * the bottom-right). Size is stored as a fraction of the video height so the
 * overlay scales identically in the on-screen preview and in the exported file.</p>
 *
 * <p>For v1 the overlay spans the entire timeline. Time-range trimming and image
 * (PNG) overlays reuse this same model and the same export path.</p>
 */
public class TextOverlayItem {

    // §4.5 per-OBJECT visibility/lock (LANE_BADGES spec, built 2026-07-19): the eye/lock
    // moved off the row gutter onto the object itself. Hidden = excluded from preview AND
    // export via LayerPreviewController's single-authority filters; locked = selectable
    // but never trims/moves/deletes. Tolerant storage: absent = false.
    private boolean hidden;
    private boolean locked;

    public boolean isHidden() { return hidden; }
    public void setHidden(boolean hidden) { this.hidden = hidden; }
    public boolean isLocked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }

    @NonNull
    private final String id;

    @NonNull
    private String text;

    /** ARGB colour of the text. */
    private int colorInt;

    private int strokeColorInt = Color.TRANSPARENT;

    private float strokeWidthPx;

    private int shadowColorInt = 0xCC000000;

    private float shadowRadiusPx;

    private int glowColorInt = Color.TRANSPARENT;

    private float glowRadiusPx;

    private int backgroundColorInt = Color.TRANSPARENT;

    /** Centre X in normalised video-content coords [0,1]. */
    private float centerX;

    /** Centre Y in normalised video-content coords [0,1]. */
    private float centerY;

    /** Text height as a fraction of the video height (e.g. 0.08 = 8%). */
    private float sizeFraction;

    /** Clockwise rotation in degrees. */
    private float rotationDeg;

    /**
     * Static opacity [0,1] used when the overlay has no OPACITY keyframes (the
     * fallback for {@link #animatedOpacity(long)}). 1 = fully opaque.
     */
    private float opacity = 1f;

    /** Font family key for text overlays (e.g. "default", "serif", "mono", "dramatic"). */
    @NonNull
    private String fontFamily = "default";

    /**
     * When non-null this overlay is an IMAGE (PNG/sticker) loaded from this URI,
     * and {@link #text}/{@link #colorInt} are ignored. Reuses the same position,
     * size, rotation, drag/pinch and export path as text overlays.
     */
    @Nullable
    private String imageUri;

    /** Visible range in timeline ms. endMs == Long.MAX_VALUE means "to the end". */
    private long startMs = 0;
    private long endMs = Long.MAX_VALUE;

    // ── Entrance / exit animation (SPEC_TEXT_ANIMATION, text-box half) ───────────────
    //
    // The same four values a captioned Clip carries, evaluated by the same
    // CaptionAnimator. A text box is the EASY case of the user's model: captions had to
    // invent a "line" because a clip holds many phrases, while a text box IS one line —
    // its own startMs…endMs span. So the carets he asked for map here directly, and this
    // is the object he reserved them for.

    // ── Rider attachment (PLAN_TIMELINE_MANIPULATION_V1 §2.0, addendum §4A) ──────────────
    // This overlay's tether to a master clip. Its policy is SHIFT_ONLY: it travels when its
    // host moves and its DURATION never changes, because the user chose that duration and a
    // ripple is not an edit to it. The visualizer's equivalent pair lives on
    // WaveformOverlayInstance (attachedClipId/attachOffsetMs) under SHIFT_TRUNCATE — same
    // concept, different policy; converge the naming only alongside a behaviour test, since
    // that one ships today.

    /** Host master-clip id, or null = unanchored (absolute time). */
    @Nullable
    private String hostClipId;

    /**
     * Offset from the host clip's START. Stored rather than derived so a save/load cycle cannot
     * re-derive a different host: {@code AnchorMath.offsetWithinHost} clamps it to
     * {@code span - 1}, which is what keeps an attachment from walking forward one clip each time.
     */
    private long hostOffsetMs;

    /** @see #hostClipId */
    @Nullable
    public String getHostClipId() { return hostClipId; }

    /** @see #hostOffsetMs */
    public long getHostOffsetMs() { return hostOffsetMs; }

    /** Attach to {@code clipId} at {@code offsetMs}, or pass null to detach (absolute time). */
    public void setHostAnchor(@Nullable String clipId, long offsetMs) {
        this.hostClipId = clipId;
        this.hostOffsetMs = clipId == null ? 0L : Math.max(0L, offsetMs);
    }

    /** {@code CaptionAnimator.Preset} name. "NONE" = no entrance/exit. */
    @NonNull
    private String textAnimPreset = "NONE";

    /**
     * {@code CaptionAnimator.Granularity} name. **BLOCK is the only honest value here —
     * see {@link #textAnimGranularitySupported}.**
     */
    @NonNull
    private String textAnimGranularity = "BLOCK";

    /** Entrance zone as a fraction of this box's own visible span, 0…0.5. */
    private float textAnimInPct = 0f;

    /** Exit zone, same units. */
    private float textAnimOutPct = 0f;

    /**
     * Persistent home for WHICH layer track this item belongs to (M10; PLAN Part 7
     * row M10 track-membership design). {@code null} = the default/auto-migrated
     * single TEXT track (id {@code "text"}) — this is the ONLY value every project
     * saved before M10 can have, since the field did not exist, so
     * {@code Timeline.getLayers()} grouping every item with a null/"text" layerId
     * into ONE track reproduces exactly today's single-TEXT-layer behavior. A
     * non-null value (a {@link com.fadcam.ui.faditor.layers.Track#getId()} minted by
     * M10's "new layer" flow) routes this item into a user-created layer track
     * instead. See {@code Timeline#getLayers()} for the grouping logic.
     */
    @Nullable
    private String layerId;

    /** Optional per-property animation (position/scale/opacity/rotation over time). */
    @NonNull
    private final com.fadcam.ui.faditor.keyframe.KeyframeSet keyframes =
            new com.fadcam.ui.faditor.keyframe.KeyframeSet();

    public TextOverlayItem(@NonNull String text, int colorInt,
                           float centerX, float centerY,
                           float sizeFraction, float rotationDeg) {
        this.id = UUID.randomUUID().toString();
        this.text = text;
        this.colorInt = colorInt;
        this.centerX = centerX;
        this.centerY = centerY;
        this.sizeFraction = sizeFraction;
        this.rotationDeg = rotationDeg;
    }

    /** Deserialisation / cloning constructor (keeps the supplied id). */
    public TextOverlayItem(@NonNull String id, @NonNull String text, int colorInt,
                           float centerX, float centerY,
                           float sizeFraction, float rotationDeg) {
        this.id = id;
        this.text = text;
        this.colorInt = colorInt;
        this.centerX = centerX;
        this.centerY = centerY;
        this.sizeFraction = sizeFraction;
        this.rotationDeg = rotationDeg;
    }

    @NonNull
    public String getId() { return id; }

    @NonNull
    public String getText() { return text; }

    public void setText(@NonNull String text) { this.text = text; }

    public int getColorInt() { return colorInt; }

    public void setColorInt(int colorInt) { this.colorInt = colorInt; }

    public int getStrokeColorInt() { return strokeColorInt; }
    public void setStrokeColorInt(int strokeColorInt) { this.strokeColorInt = strokeColorInt; }

    public float getStrokeWidthPx() { return strokeWidthPx; }
    public void setStrokeWidthPx(float strokeWidthPx) { this.strokeWidthPx = Math.max(0f, strokeWidthPx); }

    public int getShadowColorInt() { return shadowColorInt; }
    public void setShadowColorInt(int shadowColorInt) { this.shadowColorInt = shadowColorInt; }

    public float getShadowRadiusPx() { return shadowRadiusPx; }
    public void setShadowRadiusPx(float shadowRadiusPx) { this.shadowRadiusPx = Math.max(0f, shadowRadiusPx); }

    public int getGlowColorInt() { return glowColorInt; }
    public void setGlowColorInt(int glowColorInt) { this.glowColorInt = glowColorInt; }

    public float getGlowRadiusPx() { return glowRadiusPx; }
    public void setGlowRadiusPx(float glowRadiusPx) { this.glowRadiusPx = Math.max(0f, glowRadiusPx); }

    public int getBackgroundColorInt() { return backgroundColorInt; }
    public void setBackgroundColorInt(int backgroundColorInt) { this.backgroundColorInt = backgroundColorInt; }

    public float getCenterX() { return centerX; }

    public float getCenterY() { return centerY; }

    public void setCenter(float x, float y) {
        this.centerX = Math.max(0f, Math.min(1f, x));
        this.centerY = Math.max(0f, Math.min(1f, y));
    }

    public float getSizeFraction() { return sizeFraction; }

    public void setSizeFraction(float sizeFraction) {
        this.sizeFraction = Math.max(0.02f, Math.min(0.6f, sizeFraction));
    }

    public float getRotationDeg() { return rotationDeg; }

    public void setRotationDeg(float rotationDeg) {
        this.rotationDeg = rotationDeg % 360f;
    }

    /** Static opacity [0,1] used when there are no OPACITY keyframes. */
    public float getOpacity() { return opacity; }

    public void setOpacity(float opacity) {
        this.opacity = Math.max(0f, Math.min(1f, opacity));
    }

    @NonNull
    public String getFontFamily() { return fontFamily; }

    public void setFontFamily(@NonNull String fontFamily) { this.fontFamily = fontFamily; }

    /** Get the Android Typeface for this overlay's font family. */
    @NonNull
    public android.graphics.Typeface getTypeface() {
        // Custom font file (loaded from storage)
        if (fontFamily.startsWith("file:")) {
            try {
                return android.graphics.Typeface.createFromFile(fontFamily.substring(5));
            } catch (Exception e) {
                return android.graphics.Typeface.DEFAULT_BOLD;
            }
        }
        switch (fontFamily) {
            case "serif": return android.graphics.Typeface.SERIF;
            case "serif_italic": return android.graphics.Typeface.create(
                    android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC);
            case "mono": return android.graphics.Typeface.MONOSPACE;
            case "mono_bold": return android.graphics.Typeface.create(
                    android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            case "dramatic": return android.graphics.Typeface.create(
                    android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD_ITALIC);
            case "techie": return android.graphics.Typeface.create(
                    android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            case "designer": return android.graphics.Typeface.create(
                    android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL);
            case "classy": return android.graphics.Typeface.create(
                    android.graphics.Typeface.SERIF, android.graphics.Typeface.NORMAL);
            case "classy_italic": return android.graphics.Typeface.create(
                    android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC);
            case "trendy": return android.graphics.Typeface.create(
                    android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL);
            case "country": return android.graphics.Typeface.create(
                    android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD);
            case "popular": return android.graphics.Typeface.create(
                    android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD);
            case "popular_italic": return android.graphics.Typeface.create(
                    android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD_ITALIC);
            case "light": return android.graphics.Typeface.create(
                    android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL);
            case "condensed": return android.graphics.Typeface.create(
                    "sans-serif-condensed", android.graphics.Typeface.NORMAL);
            case "condensed_bold": return android.graphics.Typeface.create(
                    "sans-serif-condensed", android.graphics.Typeface.BOLD);
            case "casual": return android.graphics.Typeface.create(
                    "casual", android.graphics.Typeface.NORMAL);
            case "cursive": return android.graphics.Typeface.create(
                    "cursive", android.graphics.Typeface.NORMAL);
            case "serif_bold": return android.graphics.Typeface.create(
                    android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD);
            case "sans_light": return android.graphics.Typeface.create(
                    "sans-serif-light", android.graphics.Typeface.NORMAL);
            case "sans_thin": return android.graphics.Typeface.create(
                    "sans-serif-thin", android.graphics.Typeface.NORMAL);
            case "sans_medium": return android.graphics.Typeface.create(
                    "sans-serif-medium", android.graphics.Typeface.NORMAL);
            case "sans_black": return android.graphics.Typeface.create(
                    "sans-serif-black", android.graphics.Typeface.NORMAL);
            default: return android.graphics.Typeface.DEFAULT_BOLD;
        }
    }

    @Nullable
    public String getImageUri() { return imageUri; }

    public void setImageUri(@Nullable String imageUri) { this.imageUri = imageUri; }

    /** True if this overlay is an image/PNG rather than text. */
    public boolean isImage() { return imageUri != null; }

    // ── AI-authored animated overlay slide (spec Phase 4) ───────────────

    /** Recipe for an AI-authored transparent overlay slide, or null. */
    @Nullable
    private GeneratedSource generatedSource;

    @Nullable
    public GeneratedSource getGeneratedSource() { return generatedSource; }

    public void setGeneratedSource(@Nullable GeneratedSource gs) {
        this.generatedSource = gs;
    }

    /** True if this overlay renders from an AI-authored PNG frame sequence. */
    public boolean isGeneratedSlide() { return generatedSource != null; }

    // ── Countdown / count-up timer (SPEC_TIMER_OBJECT) ──────────────────

    /**
     * Turns this overlay into a live clock, or {@code null} for ordinary text.
     * Only the displayed STRING changes — every style field above still applies, so a
     * timer inherits the caption look for free. See {@link TimerText}, which is the one
     * authority preview and export both read.
     */
    @Nullable
    private TimerSpec timerSpec;

    @Nullable
    public TimerSpec getTimerSpec() { return timerSpec; }

    public void setTimerSpec(@Nullable TimerSpec spec) { this.timerSpec = spec; }

    /** True if this overlay displays a computed time rather than its authored text. */
    public boolean isTimer() { return timerSpec != null; }

    // ── Layer-track membership (M10) ────────────────────────────────────

    /** Stable id of the layer track this item belongs to, or {@code null} for the default TEXT track. */
    @Nullable
    public String getLayerId() { return layerId; }

    public void setLayerId(@Nullable String layerId) { this.layerId = layerId; }

    // ── Time range ───────────────────────────────────────────────────

    public long getStartMs() { return startMs; }
    public long getEndMs() { return endMs; }

    // ── Entrance / exit animation ───────────────────────────────────────────────────

    @NonNull
    public String getTextAnimPreset() { return textAnimPreset; }

    public void setTextAnimPreset(@NonNull String presetName) {
        this.textAnimPreset = presetName;
    }

    @NonNull
    public String getTextAnimGranularity() { return textAnimGranularity; }

    public void setTextAnimGranularity(@NonNull String granularityName) {
        this.textAnimGranularity = granularityName;
    }

    public float getTextAnimInPct() { return textAnimInPct; }

    public float getTextAnimOutPct() { return textAnimOutPct; }

    /** True when either zone would animate anything. */
    public boolean hasTextAnim() { return textAnimInPct > 0f || textAnimOutPct > 0f; }

    /**
     * Set both zones at once, each 0…0.5 of this box's visible span.
     *
     * <p>One setter, because the constraint is on their SUM — the same funnel rule the
     * caption zones use, so the picker, a caret drag and an AI edit all inherit the clamp
     * rather than each remembering it. At 0.5/0.5 the entrance ends exactly where the exit
     * begins, which is the user's stated model and holds at every span length because the
     * zones are fractions.</p>
     *
     * <p>Excess comes off the EXIT zone: one control moves at a time, and the one being
     * moved keeps the value that was asked for.</p>
     */
    public void setTextAnimZonePct(float inPct, float outPct) {
        float in = clampTextZonePct(inPct);
        float out = clampTextZonePct(outPct);
        if (in + out > 1f) out = 1f - in;
        this.textAnimInPct = in;
        this.textAnimOutPct = out;
    }

    private static float clampTextZonePct(float v) {
        if (Float.isNaN(v)) return 0f;
        return Math.max(0f, Math.min(0.5f, v));
    }

    /**
     * The span the zones are fractions OF, resolved against the timeline.
     *
     * <p>{@link #endMs} defaults to {@code Long.MAX_VALUE} ("to the end"), and a fraction of
     * an unbounded span is not a duration — it would overflow before it animated. So an open
     * end resolves to the timeline's total, which is what "to the end" already means
     * everywhere else. Returns 0 when there is nothing to animate over, and every caller
     * treats 0 as "no animation" rather than dividing by it.</p>
     */
    public long animSpanMs(long timelineDurationMs) {
        long end = (endMs == Long.MAX_VALUE || endMs <= 0) ? timelineDurationMs : endMs;
        return Math.max(0L, end - startMs);
    }

    /**
     * Whether a granularity can actually be honoured for a TEXT BOX.
     *
     * <p><b>ALL of them, since 2026-07-30.</b> Both surfaces now draw a text box glyph by glyph
     * through ONE shared renderer, {@code TextBoxRenderer} — the preview via
     * {@code TextBoxView.onDraw} and the export via {@code CompositeExportOverlay}, which no
     * longer rasterises the box to a bitmap first. There is one layout and one set of per-unit
     * transforms, so a granularity cannot mean different things on the two sides.</p>
     *
     * <p><b>What this used to say, and why the correction matters more than the fix.</b> It read:
     * the preview draws a {@code TextView} which cannot transform individual characters, while the
     * export "draws the same overlay with {@code canvas.drawText}, where per-glyph work IS
     * reachable" — i.e. one side to build. That was true of the primitive and false about the
     * state: the export rasterised the whole box and animated the bitmap, so it was every bit as
     * BLOCK-only as the preview. Anyone planning from the old wording would have estimated half
     * the work and discovered the other half at the end. <b>Do not describe a capability by the
     * API that could provide it; describe it by what the code does.</b></p>
     *
     * <p>Kept as a method rather than deleted because it is the gate the picker asks, and a
     * future surface that genuinely cannot do per-unit work (a widget, a thumbnail) should have
     * somewhere to say so.</p>
     */
    public static boolean textAnimGranularitySupported(@NonNull String granularityName) {
        return true;
    }

    public void setTimeRange(long startMs, long endMs) {
        this.startMs = Math.max(0, startMs);
        // Guard against a degenerate range (end at/before start) that would make
        // the overlay invisible everywhere — treat it as "visible to the end".
        this.endMs = (endMs <= this.startMs) ? Long.MAX_VALUE : endMs;
    }

    /** Whether this overlay should be drawn at the given timeline time. */
    public boolean isVisibleAt(long timelineMs) {
        return timelineMs >= startMs && timelineMs <= endMs;
    }

    // ── Keyframe animation ───────────────────────────────────────────

    @NonNull
    public com.fadcam.ui.faditor.keyframe.KeyframeSet getKeyframes() {
        return keyframes;
    }

    public boolean isAnimated() {
        return keyframes.isAnimated();
    }

    /**
     * "Armed" = has at least one keyframe. Once armed, dragging the overlay at a
     * new playhead time should record a keyframe (and the preview/export read the
     * keyframed value rather than the static one).
     */
    public boolean isArmed() {
        return !keyframes.isEmpty();
    }

    /** Local time (ms from this overlay's start) used as the keyframe time base. */
    private long localTime(long timelineMs) {
        return Math.max(0, timelineMs - startMs);
    }

    public float animatedCenterX(long timelineMs) {
        return keyframes.valueAt(com.fadcam.ui.faditor.keyframe.KeyframeSet.X,
                localTime(timelineMs), centerX);
    }

    public float animatedCenterY(long timelineMs) {
        return keyframes.valueAt(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y,
                localTime(timelineMs), centerY);
    }

    /** Animated size fraction (the scale track stores the absolute fraction). */
    public float animatedSizeFraction(long timelineMs) {
        return keyframes.valueAt(com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE,
                localTime(timelineMs), sizeFraction);
    }

    /**
     * Record the overlay's current static transform as a keyframe at the given
     * timeline time (position, size, rotation, and opacity). Repeating this at
     * different times with different transforms produces animation.
     */
    public void addKeyframeAt(long timelineMs) {
        long t = localTime(timelineMs);
        com.fadcam.ui.faditor.keyframe.Easing ease =
                com.fadcam.ui.faditor.keyframe.Easing.EASE_IN_OUT;
        keyframes.getOrCreate(com.fadcam.ui.faditor.keyframe.KeyframeSet.X).put(t, centerX, ease);
        keyframes.getOrCreate(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y).put(t, centerY, ease);
        keyframes.getOrCreate(com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE)
                .put(t, sizeFraction, ease);
        keyframes.getOrCreate(com.fadcam.ui.faditor.keyframe.KeyframeSet.ROTATION)
                .put(t, rotationDeg, ease);
        keyframes.getOrCreate(com.fadcam.ui.faditor.keyframe.KeyframeSet.OPACITY)
                .put(t, opacity, ease);
    }

    /**
     * Add a keyframe with an explicit opacity value (for fade in/out animation).
     */
    public void addOpacityKeyframeAt(long timelineMs, float opacity) {
        addPropertyKeyframeAt(com.fadcam.ui.faditor.keyframe.KeyframeSet.OPACITY,
                timelineMs, opacity);
    }

    /**
     * G2 (gesture contract §2): keyframe-aware single-property write — drop/update
     * a keyframe for ONE property at the given timeline time with an explicit
     * value, anchoring the shared X/Y/SCALE pose tracks at that time exactly like
     * {@link #addOpacityKeyframeAt} always has (the timeline uses X as the
     * canonical key-time list). Values are clamped to the same ranges as the
     * static setters.
     */
    public void addPropertyKeyframeAt(@NonNull String property, long timelineMs, float value) {
        long t = localTime(timelineMs);
        com.fadcam.ui.faditor.keyframe.Easing ease =
                com.fadcam.ui.faditor.keyframe.Easing.EASE_IN_OUT;
        // Ensure X/Y/SCALE tracks exist so the keyframe time is consistent
        // across all tracks (the timeline uses X as canonical).
        keyframes.getOrCreate(com.fadcam.ui.faditor.keyframe.KeyframeSet.X).put(t, centerX, ease);
        keyframes.getOrCreate(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y).put(t, centerY, ease);
        keyframes.getOrCreate(com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE)
                .put(t, sizeFraction, ease);
        float v = value;
        switch (property) {
            case com.fadcam.ui.faditor.keyframe.KeyframeSet.X:
            case com.fadcam.ui.faditor.keyframe.KeyframeSet.Y:
            case com.fadcam.ui.faditor.keyframe.KeyframeSet.OPACITY:
                v = Math.max(0f, Math.min(1f, value));
                break;
            case com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE:
                v = Math.max(0.02f, Math.min(0.6f, value));
                break;
            default:
                break; // rotation is unclamped (degrees)
        }
        keyframes.getOrCreate(property).put(t, v, ease);
    }

    /**
     * Move a shared transform keyframe to a new local time on every keyed
     * property. Timeline diamonds use the X track as the canonical time list,
     * but the user's intent is to move the whole pose at that moment.
     */
    public boolean moveKeyframeLocalTime(long oldLocalMs, long newLocalMs) {
        newLocalMs = Math.max(0, newLocalMs);
        boolean moved = false;
        for (com.fadcam.ui.faditor.keyframe.KeyframeTrack track : keyframes.tracks()) {
            com.fadcam.ui.faditor.keyframe.Keyframe found = null;
            for (com.fadcam.ui.faditor.keyframe.Keyframe keyframe : track.keyframes) {
                if (keyframe.timeMs == oldLocalMs) {
                    found = keyframe.copy();
                    break;
                }
            }
            if (found != null) {
                track.removeAt(oldLocalMs);
                track.put(newLocalMs, found.value, found.easing);
                moved = true;
            }
        }
        return moved;
    }

    /** Remove all animation, leaving the static transform. */
    public void clearKeyframes() {
        for (String p : new String[]{
                com.fadcam.ui.faditor.keyframe.KeyframeSet.X,
                com.fadcam.ui.faditor.keyframe.KeyframeSet.Y,
                com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE,
                com.fadcam.ui.faditor.keyframe.KeyframeSet.OPACITY,
                com.fadcam.ui.faditor.keyframe.KeyframeSet.ROTATION}) {
            com.fadcam.ui.faditor.keyframe.KeyframeTrack tr = keyframes.get(p);
            if (tr != null) {
                while (!tr.keyframes.isEmpty()) {
                    keyframes.removeKey(p, tr.keyframes.get(0).timeMs);
                }
            }
        }
    }

    public float animatedOpacity(long timelineMs) {
        return keyframes.valueAt(com.fadcam.ui.faditor.keyframe.KeyframeSet.OPACITY,
                localTime(timelineMs), opacity);
    }

    public float animatedRotation(long timelineMs) {
        return keyframes.valueAt(com.fadcam.ui.faditor.keyframe.KeyframeSet.ROTATION,
                localTime(timelineMs), rotationDeg);
    }

    // ── Undo snapshot ────────────────────────────────────────────────

    /**
     * Immutable deep copy of the mutable transform/time/keyframe state of an
     * overlay, used by undo/redo to capture a before/after of a drag gesture
     * (canvas move/scale/rotate, timeline range edge drag, or keyframe move).
     */
    public static final class TransformSnapshot {
        private final float centerX, centerY, sizeFraction, rotationDeg, opacity;
        private final long startMs, endMs;
        @NonNull private final com.fadcam.ui.faditor.keyframe.KeyframeSet keyframes;

        private TransformSnapshot(@NonNull TextOverlayItem o) {
            this.centerX = o.centerX;
            this.centerY = o.centerY;
            this.sizeFraction = o.sizeFraction;
            this.rotationDeg = o.rotationDeg;
            this.opacity = o.opacity;
            this.startMs = o.startMs;
            this.endMs = o.endMs;
            this.keyframes = o.keyframes.copy();
        }

        /** True when this snapshot is value-identical to {@code other}. */
        public boolean matches(@NonNull TransformSnapshot other) {
            return centerX == other.centerX
                    && centerY == other.centerY
                    && sizeFraction == other.sizeFraction
                    && rotationDeg == other.rotationDeg
                    && opacity == other.opacity
                    && startMs == other.startMs
                    && endMs == other.endMs
                    && keyframesEqual(keyframes, other.keyframes);
        }

        private static boolean keyframesEqual(
                @NonNull com.fadcam.ui.faditor.keyframe.KeyframeSet a,
                @NonNull com.fadcam.ui.faditor.keyframe.KeyframeSet b) {
            java.util.List<com.fadcam.ui.faditor.keyframe.KeyframeTrack> ta = trackList(a);
            java.util.List<com.fadcam.ui.faditor.keyframe.KeyframeTrack> tb = trackList(b);
            if (ta.size() != tb.size()) return false;
            for (int i = 0; i < ta.size(); i++) {
                com.fadcam.ui.faditor.keyframe.KeyframeTrack x = ta.get(i), y = tb.get(i);
                if (!x.property.equals(y.property)) return false;
                if (x.keyframes.size() != y.keyframes.size()) return false;
                for (int j = 0; j < x.keyframes.size(); j++) {
                    com.fadcam.ui.faditor.keyframe.Keyframe kx = x.keyframes.get(j);
                    com.fadcam.ui.faditor.keyframe.Keyframe ky = y.keyframes.get(j);
                    if (kx.timeMs != ky.timeMs || kx.value != ky.value
                            || kx.easing != ky.easing) return false;
                }
            }
            return true;
        }

        private static java.util.List<com.fadcam.ui.faditor.keyframe.KeyframeTrack> trackList(
                @NonNull com.fadcam.ui.faditor.keyframe.KeyframeSet s) {
            java.util.List<com.fadcam.ui.faditor.keyframe.KeyframeTrack> out = new java.util.ArrayList<>();
            for (com.fadcam.ui.faditor.keyframe.KeyframeTrack t : s.tracks()) out.add(t);
            return out;
        }
    }

    /** Capture a deep snapshot of this overlay's transform/time/keyframe state. */
    @NonNull
    public TransformSnapshot snapshotTransform() {
        return new TransformSnapshot(this);
    }

    /** Restore a previously captured {@link TransformSnapshot} (for undo/redo). */
    public void restoreTransform(@NonNull TransformSnapshot s) {
        this.centerX = s.centerX;
        this.centerY = s.centerY;
        this.sizeFraction = s.sizeFraction;
        this.rotationDeg = s.rotationDeg;
        this.opacity = s.opacity;
        this.startMs = s.startMs;
        this.endMs = s.endMs;
        this.keyframes.copyFrom(s.keyframes);
    }

    /**
     * Convenience factory for an image overlay centred on the video.
     */
    @NonNull
    public static TextOverlayItem createImage(@NonNull String imageUri,
                                              float centerX, float centerY,
                                              float sizeFraction) {
        TextOverlayItem item = new TextOverlayItem("", 0xFFFFFFFF,
                centerX, centerY, sizeFraction, 0f);
        item.setImageUri(imageUri);
        return item;
    }
}

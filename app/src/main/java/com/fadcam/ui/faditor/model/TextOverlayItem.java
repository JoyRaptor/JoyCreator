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

    // ── Layer-track membership (M10) ────────────────────────────────────

    /** Stable id of the layer track this item belongs to, or {@code null} for the default TEXT track. */
    @Nullable
    public String getLayerId() { return layerId; }

    public void setLayerId(@Nullable String layerId) { this.layerId = layerId; }

    // ── Time range ───────────────────────────────────────────────────

    public long getStartMs() { return startMs; }
    public long getEndMs() { return endMs; }

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

package com.fadcam.ui.faditor.layers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.TextOverlayItem;

/**
 * A time-placed item inside a {@link Track} (PLAN Part 2, §2.1).
 *
 * <p>Carries exactly ONE payload, reusing the existing model classes unchanged:
 * a {@link Clip} (MASTER / VIDEO / IMAGE items — {@code Clip} already covers still
 * images via {@code imageClip}), a {@link TextOverlayItem} (TEXT / STICKER items —
 * {@code TextOverlayItem} already covers PNG via {@code imageUri}), or an
 * {@link AudioClip} (AUDIO items). A SPRITE payload is planned but not yet
 * implemented (see the commented placeholder below).</p>
 *
 * <p>M5 note: this is a lightweight <em>view</em> over the model objects that
 * remain stored in {@code Timeline}'s flat lists. Constructing a {@code TimedItem}
 * does not copy or reshape its payload — it references the same live object, so
 * mutations through the existing flat APIs and through the Track model never
 * diverge (see {@code Timeline} shim documentation).</p>
 */
public class TimedItem {

    @NonNull
    private final String id;

    /**
     * Absolute position on the timeline (ms). For MASTER items this is derived by
     * summing prior clip durations (PLAN §2.2); for floating items it is free.
     */
    private long timelineStartMs;

    /** Stable z within a track for multi-item overlap (sprite plan requirement). */
    private int zHint;

    /** Compositing blend mode; only NORMAL is functional in M5. Never null. */
    @NonNull
    private BlendMode blendMode = BlendMode.NORMAL;

    // ── Exactly one payload (the discriminator is which of these is non-null) ──

    @Nullable
    private final Clip clip;

    @Nullable
    private final TextOverlayItem textOverlay;

    @Nullable
    private final AudioClip audioClip;

    /** SPRITE payload (S1, PLAN_SPRITE_ANIMATION 2026-07-03 amendment). */
    @Nullable
    private final com.fadcam.ui.faditor.sprite.SpriteOverlayItem sprite;

    /** VISUALIZER payload (layers-UX Slice A) — a placed audio waveform/spectrum visualizer. */
    @Nullable
    private final com.fadcam.ui.faditor.model.WaveformOverlayInstance waveform;

    /**
     * CAPTION payload (layers-UX Slice A) — a read-only VIEW of a clip's caption span. Captions
     * stay {@code Clip}-owned; this ref never becomes a second source of truth (see
     * {@link CaptionSpanRef}).
     */
    @Nullable
    private final CaptionSpanRef captionSpan;

    /**
     * ADJUSTMENT payload (SPEC_ADJUSTMENT_LAYERS_FX M3) — a layer that TRANSFORMS everything
     * beneath it in z rather than compositing over it. It is a first-class timeline item so it
     * can be selected, moved, trimmed and keyed like any other, which is the whole reason it
     * gets a payload slot rather than living outside the lane model.
     */
    @Nullable
    private final com.fadcam.ui.faditor.model.AdjustmentLayer adjustment;

    /**
     * Optional free-transform envelope (X/Y/SCALE/ROTATION/OPACITY) for an overlay
     * video/image, reusing the same {@link KeyframeSet} primitive {@link TextOverlayItem}
     * uses. {@code null} = use the payload's own transform or identity. Text/sprite
     * payloads already carry their own KeyframeSet, so this stays null for them.
     */
    @Nullable
    private KeyframeSet transform;

    private TimedItem(@NonNull String id, @Nullable Clip clip,
                      @Nullable TextOverlayItem textOverlay, @Nullable AudioClip audioClip,
                      @Nullable com.fadcam.ui.faditor.sprite.SpriteOverlayItem sprite,
                      @Nullable com.fadcam.ui.faditor.model.WaveformOverlayInstance waveform,
                      @Nullable CaptionSpanRef captionSpan,
                      @Nullable com.fadcam.ui.faditor.model.AdjustmentLayer adjustment) {
        this.id = id;
        this.clip = clip;
        this.textOverlay = textOverlay;
        this.audioClip = audioClip;
        this.sprite = sprite;
        this.waveform = waveform;
        this.captionSpan = captionSpan;
        this.adjustment = adjustment;
    }

    // ── Factories (one per payload kind) ─────────────────────────────

    /** Wrap a {@link Clip} (MASTER / VIDEO / IMAGE item). */
    @NonNull
    public static TimedItem ofClip(@NonNull Clip clip, long timelineStartMs) {
        TimedItem t = new TimedItem(clip.getId(), clip, null, null, null, null, null, null);
        t.timelineStartMs = timelineStartMs;
        return t;
    }

    /** Wrap a {@link TextOverlayItem} (TEXT / STICKER item). */
    @NonNull
    public static TimedItem ofTextOverlay(@NonNull TextOverlayItem overlay) {
        TimedItem t = new TimedItem(overlay.getId(), null, overlay, null, null, null, null, null);
        t.timelineStartMs = overlay.getStartMs();
        return t;
    }

    /** Wrap an {@link AudioClip} (AUDIO item). Its offset is the timeline start. */
    @NonNull
    public static TimedItem ofAudioClip(@NonNull AudioClip audioClip) {
        TimedItem t = new TimedItem(audioClip.getId(), null, null, audioClip, null, null, null, null);
        t.timelineStartMs = audioClip.getOffsetMs();
        return t;
    }

    /** Wrap a {@link com.fadcam.ui.faditor.sprite.SpriteOverlayItem} (SPRITE item). */
    @NonNull
    public static TimedItem ofSprite(@NonNull com.fadcam.ui.faditor.sprite.SpriteOverlayItem sprite) {
        TimedItem t = new TimedItem(sprite.getId(), null, null, null, sprite, null, null, null);
        t.timelineStartMs = sprite.getStartMs();
        return t;
    }

    /**
     * Wrap a {@link com.fadcam.ui.faditor.model.WaveformOverlayInstance} (VISUALIZER item,
     * layers-UX Slice A). Its {@code startMs} is the timeline start.
     */
    @NonNull
    public static TimedItem ofWaveform(
            @NonNull com.fadcam.ui.faditor.model.WaveformOverlayInstance waveform) {
        TimedItem t = new TimedItem(waveform.getId(), null, null, null, null, waveform, null, null);
        t.timelineStartMs = waveform.getStartMs();
        return t;
    }

    /**
     * Wrap a {@link CaptionSpanRef} (CAPTION item, layers-UX Slice A). The ref's clip id is the
     * item id (one caption span per clip); its start is the timeline start. Read-only view.
     */
    @NonNull
    public static TimedItem ofCaptionSpan(@NonNull CaptionSpanRef captionSpan) {
        TimedItem t = new TimedItem(captionSpan.getClip().getId(), null, null, null, null, null,
                captionSpan, null);
        t.timelineStartMs = Math.max(0, captionSpan.getStartMs());
        return t;
    }

    /**
     * Wrap an {@link com.fadcam.ui.faditor.model.AdjustmentLayer}. Its {@code startMs} is the
     * timeline start, in the same editor-time base every other floating payload uses.
     */
    @NonNull
    public static TimedItem ofAdjustment(
            @NonNull com.fadcam.ui.faditor.model.AdjustmentLayer adjustment) {
        TimedItem t = new TimedItem(adjustment.getId(), null, null, null, null, null, null,
                adjustment);
        t.timelineStartMs = adjustment.getStartMs();
        return t;
    }

    // ── Getters / setters ────────────────────────────────────────────

    @NonNull
    public String getId() { return id; }

    public long getTimelineStartMs() { return timelineStartMs; }

    public void setTimelineStartMs(long ms) { this.timelineStartMs = Math.max(0, ms); }

    public int getZHint() { return zHint; }

    public void setZHint(int zHint) { this.zHint = zHint; }

    @NonNull
    public BlendMode getBlendMode() { return blendMode; }

    public void setBlendMode(@NonNull BlendMode blendMode) { this.blendMode = blendMode; }

    @Nullable
    public Clip getClip() { return clip; }

    @Nullable
    public TextOverlayItem getTextOverlay() { return textOverlay; }

    @Nullable
    public AudioClip getAudioClip() { return audioClip; }

    @Nullable
    public com.fadcam.ui.faditor.sprite.SpriteOverlayItem getSprite() { return sprite; }

    @Nullable
    public com.fadcam.ui.faditor.model.WaveformOverlayInstance getWaveform() { return waveform; }

    @Nullable
    public CaptionSpanRef getCaptionSpan() { return captionSpan; }

    /** @see #ofAdjustment */
    @Nullable
    public com.fadcam.ui.faditor.model.AdjustmentLayer getAdjustment() { return adjustment; }

    /**
     * Whether the PAYLOAD this item wraps is locked.
     *
     * <p>Every payload type has its own {@code isLocked()} (a {@code Clip}'s is
     * {@code isLockedObject()}), and every caller that wants to respect a lock previously had to
     * remember all six and pick the right one. A batch verb that forgets one deletes an object
     * the user locked on purpose — which is not a lock at all — so the switch lives here, once.</p>
     *
     * <p>A caption span has no lock: it is clip-owned rather than an object in its own right, and
     * reporting it unlocked is honest because the verbs skip it anyway.</p>
     */
    public boolean isPayloadLocked() {
        if (textOverlay != null) return textOverlay.isLocked();
        if (sprite != null) return sprite.isLocked();
        if (waveform != null) return waveform.isLocked();
        if (adjustment != null) return adjustment.isLocked();
        if (audioClip != null) return audioClip.isLocked();
        if (clip != null) return clip.isLockedObject();
        return false;
    }

    @Nullable
    public KeyframeSet getTransform() { return transform; }

    public void setTransform(@Nullable KeyframeSet transform) { this.transform = transform; }

    /** True when this item carries a free-transform envelope (a non-empty KeyframeSet). */
    public boolean hasTransform() {
        return transform != null && !transform.isEmpty();
    }

    // ── Payload discriminator ────────────────────────────────────────

    /** Stable string tag identifying which payload this item carries (for serialization). */
    @NonNull
    public String payloadKind() {
        if (clip != null) return "clip";
        if (textOverlay != null) return "textOverlay";
        if (audioClip != null) return "audioClip";
        if (sprite != null) return "sprite";
        if (waveform != null) return "waveform";
        if (captionSpan != null) return "captionSpan";
        if (adjustment != null) return "adjustment";
        return "none";
    }

    /**
     * Duration in ms of this item's payload on the timeline, for row rendering
     * (M6). {@code fallbackMs} (typically the timeline's total duration) is used
     * when a payload's own end is unbounded (e.g. a text overlay spanning "the
     * rest of the timeline", {@code Long.MAX_VALUE}) — mirrors the existing
     * read-only layer-row logic in {@code EditorTimelineView#displayEndMs}.
     */
    public long getDisplayDurationMs(long fallbackMs) {
        if (clip != null) {
            return clip.hasLoopExtension() ? clip.getVisualDurationMs() : clip.getTrimmedDurationMs();
        }
        if (audioClip != null) {
            return audioClip.getTrimmedDurationMs();
        }
        if (textOverlay != null) {
            long end = textOverlay.getEndMs();
            long start = Math.max(0, textOverlay.getStartMs());
            if (end == Long.MAX_VALUE || end <= start) return Math.max(0, fallbackMs - start);
            return end - start;
        }
        if (sprite != null) {
            // Same open-end semantics as text overlays.
            long end = sprite.getEndMs();
            long start = Math.max(0, sprite.getStartMs());
            if (end == Long.MAX_VALUE || end <= start) return Math.max(0, fallbackMs - start);
            return end - start;
        }
        if (waveform != null) {
            // Same open-end semantics as text overlays.
            long end = waveform.getEndMs();
            long start = Math.max(0, waveform.getStartMs());
            if (end == Long.MAX_VALUE || end <= start) return Math.max(0, fallbackMs - start);
            return end - start;
        }
        if (captionSpan != null) {
            // Read-only view of a clip's caption span; open-end (last clip) runs to the fallback.
            long end = captionSpan.getEndMs();
            long start = Math.max(0, captionSpan.getStartMs());
            if (end == Long.MAX_VALUE || end <= start) return Math.max(0, fallbackMs - start);
            return end - start;
        }
        if (adjustment != null) {
            // WITHOUT THIS the method fell through to 0, and everything an adjustment layer
            // could not do followed from it: LayerRowRenderer floors a zero-width body at 2dp,
            // and a 2dp bar cannot be tapped, dragged, trimmed or hit-tested. The layer was a
            // real payload on a real lane the whole time — it just had no width, which is
            // indistinguishable from "this is not a real layer" at the only place the user
            // looks. Same open-end semantics as text and sprites: a layer with no duration set
            // runs to the end of the timeline, which is what "grade everything" means.
            long dur = adjustment.getDurationMs();
            long start = Math.max(0, adjustment.getStartMs());
            if (dur <= 0) return Math.max(0, fallbackMs - start);
            return dur;
        }
        return 0;
    }
}

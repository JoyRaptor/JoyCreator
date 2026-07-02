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

    // SPRITE payload placeholder — SpriteOverlayItem does not exist yet (sprite plan).
    // When it lands, add: @Nullable private final SpriteOverlayItem sprite;
    // and a corresponding factory / discriminator branch. Until then a SPRITE track
    // simply holds no items.

    /**
     * Optional free-transform envelope (X/Y/SCALE/ROTATION/OPACITY) for an overlay
     * video/image, reusing the same {@link KeyframeSet} primitive {@link TextOverlayItem}
     * uses. {@code null} = use the payload's own transform or identity. Text/sprite
     * payloads already carry their own KeyframeSet, so this stays null for them.
     */
    @Nullable
    private KeyframeSet transform;

    private TimedItem(@NonNull String id, @Nullable Clip clip,
                      @Nullable TextOverlayItem textOverlay, @Nullable AudioClip audioClip) {
        this.id = id;
        this.clip = clip;
        this.textOverlay = textOverlay;
        this.audioClip = audioClip;
    }

    // ── Factories (one per payload kind) ─────────────────────────────

    /** Wrap a {@link Clip} (MASTER / VIDEO / IMAGE item). */
    @NonNull
    public static TimedItem ofClip(@NonNull Clip clip, long timelineStartMs) {
        TimedItem t = new TimedItem(clip.getId(), clip, null, null);
        t.timelineStartMs = timelineStartMs;
        return t;
    }

    /** Wrap a {@link TextOverlayItem} (TEXT / STICKER item). */
    @NonNull
    public static TimedItem ofTextOverlay(@NonNull TextOverlayItem overlay) {
        TimedItem t = new TimedItem(overlay.getId(), null, overlay, null);
        t.timelineStartMs = overlay.getStartMs();
        return t;
    }

    /** Wrap an {@link AudioClip} (AUDIO item). Its offset is the timeline start. */
    @NonNull
    public static TimedItem ofAudioClip(@NonNull AudioClip audioClip) {
        TimedItem t = new TimedItem(audioClip.getId(), null, null, audioClip);
        t.timelineStartMs = audioClip.getOffsetMs();
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
        return 0;
    }
}

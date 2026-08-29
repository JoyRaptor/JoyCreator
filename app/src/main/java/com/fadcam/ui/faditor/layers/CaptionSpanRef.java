package com.fadcam.ui.faditor.layers;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.Clip;

/**
 * A read-only VIEW of ONE clip's caption span placed on the timeline (layers-UX Slice A,
 * 2026-07-06). Captions are {@code Clip}-owned (per-clip enable flag + transcript + per-keyframe
 * style); this ref carries only the placement so a {@link TimedItem} can surface a caption as a
 * headered CAPTION Track row without the Track ever becoming a second source of truth
 * (single-authority rule, matching the sprite/text Track views in {@code Timeline}).
 *
 * <p>The referenced {@link Clip} is the live model object — the renderer reads its caption
 * keyframes/style straight through (no copy), so mutations via the existing clip APIs and via the
 * Track model never diverge.</p>
 */
public final class CaptionSpanRef {

    @NonNull
    private final Clip clip;

    /** Index of the source clip on the master track (for tap → select, mirrors the old capSpans[2]). */
    private final int clipIndex;

    /** Absolute start on the timeline (ms). */
    private final long startMs;

    /**
     * Absolute end on the timeline (ms). {@code Long.MAX_VALUE} for the last clip (runs to the end),
     * matching the old {@code capSpans} open-end convention.
     */
    private final long endMs;

    /** Which caption binding of the clip this span represents (0..2). 0 = legacy single. */
    private final int bindingIndex;

    public CaptionSpanRef(@NonNull Clip clip, int clipIndex, long startMs, long endMs) {
        this(clip, clipIndex, startMs, endMs, 0);
    }

    public CaptionSpanRef(@NonNull Clip clip, int clipIndex, long startMs, long endMs, int bindingIndex) {
        this.clip = clip;
        this.clipIndex = clipIndex;
        this.startMs = startMs;
        this.endMs = endMs;
        this.bindingIndex = Math.max(0, bindingIndex);
    }

    @NonNull
    public Clip getClip() { return clip; }

    public int getClipIndex() { return clipIndex; }

    public long getStartMs() { return startMs; }

    public long getEndMs() { return endMs; }

    public int getBindingIndex() { return bindingIndex; }

    /** The binding itself, or null if the index is out of range (stale view). */
    @androidx.annotation.Nullable
    public Clip.CaptionBinding getBinding() {
        java.util.List<Clip.CaptionBinding> bs = clip.getCaptionBindings();
        return (bindingIndex >= 0 && bindingIndex < bs.size()) ? bs.get(bindingIndex) : null;
    }
}

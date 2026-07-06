package com.fadcam.ui.faditor.layers;

import androidx.annotation.NonNull;

/**
 * The kind of a {@link Track} in the schema-v8 layer model (PLAN Part 2).
 *
 * <ul>
 *   <li>{@link #MASTER} — the spine (gapless in ripple mode); wraps {@code timeline.clips}.</li>
 *   <li>{@link #VIDEO} — a floating overlay-video layer (above the master).</li>
 *   <li>{@link #IMAGE} — a floating still-image layer.</li>
 *   <li>{@link #TEXT} — a text-overlay layer; wraps {@code timeline.textOverlays}.</li>
 *   <li>{@link #STICKER} — a PNG/sticker layer (also backed by TextOverlayItem's imageUri).</li>
 *   <li>{@link #SPRITE} — a keyframed sprite layer (payload not yet implemented — see sprite plan).</li>
 *   <li>{@link #CAPTION} — a caption/CC row; a read-only VIEW over clip-owned caption spans
 *       (captions stay {@code Clip}-owned — the Track never becomes a second source of truth).</li>
 *   <li>{@link #VISUALIZER} — an audio-waveform/spectrum visualizer; wraps
 *       {@code timeline.waveformOverlays}.</li>
 *   <li>{@link #AUDIO} — an audio track (below the master); wraps {@code timeline.audioClips}.</li>
 * </ul>
 *
 * <p>Only {@code MASTER}, {@code TEXT} and {@code AUDIO} are produced by the M5 auto-migration
 * (PLAN §2.2). The remaining kinds exist so later milestones can add layers without a schema bump.
 * {@code CAPTION}/{@code VISUALIZER} were added by the layers-UX renderer-consolidation (Slice A,
 * 2026-07-06) so captions and visualizers become headered Track rows in {@code LayerRowRenderer}.</p>
 */
public enum TrackKind {
    MASTER,
    VIDEO,
    IMAGE,
    TEXT,
    STICKER,
    SPRITE,
    CAPTION,
    VISUALIZER,
    AUDIO;

    /** Parse a persisted name, defaulting to {@link #VIDEO} for an unknown value. */
    @NonNull
    public static TrackKind fromName(@NonNull String name) {
        try {
            return TrackKind.valueOf(name);
        } catch (IllegalArgumentException e) {
            return VIDEO;
        }
    }
}

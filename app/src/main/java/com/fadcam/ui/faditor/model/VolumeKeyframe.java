package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;

/**
 * Shared volume keyframe class — used by both {@link Clip} and {@link AudioClip}
 * via the {@link AudioParams} interface (A7 shared carrier).
 */
public class VolumeKeyframe {

    /** Clip-local time in ms (0 = clip start on the timeline). */
    public long timeMs;
    /** Volume/gain 0.0–2.0 (0 = silent, 1 = original, 2 = 200%). */
    public float volume;

    public VolumeKeyframe(long timeMs, float volume) {
        this.timeMs = timeMs;
        this.volume = volume;
    }

    public VolumeKeyframe(@NonNull VolumeKeyframe other) {
        this.timeMs = other.timeMs;
        this.volume = other.volume;
    }
}
package com.fadcam.ui.faditor.keyframe;

import androidx.annotation.NonNull;

/**
 * A single keyframe: a property value at a point in time, plus the easing used
 * to interpolate from this keyframe toward the next one.
 *
 * <p>{@code timeMs} is measured along the element's own visible range (for an
 * overlay) or the clip's source time (for a clip-level property) — the consumer
 * decides the time base. Values are plain floats so any scalar property
 * (x, y, scale, opacity, rotation, zoom…) can reuse the same machinery.</p>
 */
public class Keyframe {

    public long timeMs;
    public float value;
    @NonNull public Easing easing;
    /** True while this key is maintained by an image animation preset (drawn amber). Cleared permanently the first time the user drags it in the TIMELINE. */
    public boolean presetOwned = false;

    public Keyframe(long timeMs, float value, @NonNull Easing easing) {
        this.timeMs = timeMs;
        this.value = value;
        this.easing = easing;
    }

    public Keyframe(long timeMs, float value) {
        this(timeMs, value, Easing.EASE_IN_OUT);
    }

    @NonNull
    public Keyframe copy() {
        Keyframe c = new Keyframe(timeMs, value, easing);
        c.presetOwned = presetOwned;
        return c;
    }
}

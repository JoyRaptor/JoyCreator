package com.fadcam.ui.faditor.keyframe;

import androidx.annotation.NonNull;

/**
 * Interpolation curve used between one keyframe and the next. Kept as a small
 * enum (rather than arbitrary bezier handles) so the project file stays simple
 * and an AI or script can author keyframes without understanding curves.
 */
public enum Easing {
    /** Constant speed. */
    LINEAR,
    /** Slow start. */
    EASE_IN,
    /** Slow end. */
    EASE_OUT,
    /** Slow start and end (the natural-looking default). */
    EASE_IN_OUT,
    /** No interpolation — hold the value until the next keyframe (step). */
    HOLD;

    /**
     * Remap a normalised progress {@code t} in [0,1] through this curve.
     *
     * @param t linear progress between the two keyframes
     * @return eased progress in [0,1]
     */
    public float apply(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        switch (this) {
            case EASE_IN:
                return t * t;
            case EASE_OUT:
                return 1f - (1f - t) * (1f - t);
            case EASE_IN_OUT:
                return t < 0.5f
                        ? 2f * t * t
                        : 1f - (float) Math.pow(-2f * t + 2f, 2) / 2f;
            case HOLD:
                return 0f; // value stays at the left keyframe until the next one
            case LINEAR:
            default:
                return t;
        }
    }

    @NonNull
    public static Easing fromName(@NonNull String name) {
        try {
            return Easing.valueOf(name);
        } catch (IllegalArgumentException e) {
            return EASE_IN_OUT;
        }
    }
}

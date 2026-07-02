package com.fadcam.ui.faditor.layers;

import androidx.annotation.NonNull;

/**
 * Per-{@link TimedItem} compositing blend mode (PLAN Part 2, §1.3).
 *
 * <p>{@link #NORMAL} (alpha-over) is the only functional mode in M5 and remains the
 * only one honoured until the blend-mode {@code GlEffect} milestone (M-EXPORT-2).
 * The other values are reserved so the model and serializer already round-trip a
 * blend choice; they are treated as NORMAL by preview/export for now.</p>
 */
public enum BlendMode {
    NORMAL,
    MULTIPLY,
    SCREEN,
    OVERLAY,
    ADD;

    /** Parse a persisted name, defaulting to {@link #NORMAL} for an unknown value. */
    @NonNull
    public static BlendMode fromName(@NonNull String name) {
        try {
            return BlendMode.valueOf(name);
        } catch (IllegalArgumentException e) {
            return NORMAL;
        }
    }
}

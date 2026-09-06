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
    ADD,
    // Kept in the SAME ORDER as BlendModes.ALL. The two vocabularies are separate types but one
    // wire format -- this enum's name() is what is persisted and what BlendModes.modeCode() reads
    // -- so a mode present in one and missing from the other is a mode the picker cannot offer or
    // the shader cannot draw.
    DIFFERENCE,
    COLOR,
    // The rest of the Photoshop/W3C set (2026-09-04). Still the SAME ORDER as BlendModes.ALL.
    // There is no LINEAR_DODGE: it is the same equation as ADD, and a second wire value for one
    // equation is a project that round-trips into the "other" one.
    DARKEN,
    LIGHTEN,
    COLOR_DODGE,
    COLOR_BURN,
    LINEAR_BURN,
    HARD_LIGHT,
    SOFT_LIGHT,
    VIVID_LIGHT,
    LINEAR_LIGHT,
    PIN_LIGHT,
    HARD_MIX,
    EXCLUSION,
    SUBTRACT,
    DIVIDE,
    DARKER_COLOR,
    LIGHTER_COLOR,
    HUE,
    SATURATION,
    LUMINOSITY;

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

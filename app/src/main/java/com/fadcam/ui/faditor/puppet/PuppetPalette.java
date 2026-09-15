package com.fadcam.ui.faditor.puppet;

import com.fadcam.ui.faditor.transform.HandleModel;

/**
 * THE ONE PLACE a puppet thing gets a colour.
 *
 * <p>Separate from {@link PuppetPin} so that class and {@link PuppetRig} can stay import-free and
 * harness-testable. Everything that draws a pin — the preview dots, the chips above the tape, the
 * swatch in the drawer, and the KEYFRAMES on the tape — asks here, so a pin and its keys can never
 * drift apart in colour. That drift is the whole risk: the tape only gets away with showing one
 * pin at a time because its colour says what kind of pin it is.
 *
 * <h3>Two of these are not new colours</h3>
 * <p>{@link #FREE} is {@link HandleModel#COLOR_GUIDE} and {@link #BONE} is
 * {@link HandleModel#COLOR_BEND}, taken rather than matched. A Free pin wears the transform tool's
 * own rotate arc and scale square — the amber {@link HandleModel#COLOR_SCALE} — so if it wore a
 * different violet from the rest of the app, two things that behave identically would look
 * unrelated. JoyRaptor, 2026-09-15: <i>"it should look like they are part of the same app. Or
 * variations of the same thing."</i>
 *
 * <p>Referencing the constants rather than copying the hex is what keeps that true after somebody
 * retunes the transform palette.
 */
public final class PuppetPalette {

    private PuppetPalette() {}

    /** Anchor. Amber-gold, distinct from the transform tool's structural amber. */
    public static final int PIN = 0xFFF2B138;

    /** Resists deformation. Rose — a warning hue, because a stiff patch refuses to move. */
    public static final int STIFF = 0xFFE5556B;

    /** Simulated. Cyan, and the only type whose tape shows no keys at all. */
    public static final int DANGLE = 0xFF39C5D8;

    /** Move, turn, scale — the app's existing guide violet. */
    public static final int FREE = HandleModel.COLOR_GUIDE;

    /** A bone. The app's existing bend blue: structural, but fluid. */
    public static final int BONE = HandleModel.COLOR_BEND;

    /**
     * Rotate and scale on a Free pin. The transform tool's structural amber, unchanged, because
     * it is the same gesture on a different shape.
     */
    public static final int HELPER = HandleModel.COLOR_SCALE;

    /** Everything greys to this when the rig is locked from the puppet badge. */
    public static final int LOCKED = 0xFF5A616B;

    /** The colour for a pin of this type, or {@link #LOCKED} when the rig is shut off. */
    public static int of(PuppetPin.Type type, boolean locked) {
        if (locked) return LOCKED;
        return of(type);
    }

    public static int of(PuppetPin.Type type) {
        switch (type) {
            case PIN: return PIN;
            case STIFF: return STIFF;
            case DANGLE: return DANGLE;
            default: return FREE;
        }
    }
}

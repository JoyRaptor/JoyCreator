package com.fadcam.ui.faditor.motion;

import androidx.annotation.NonNull;

/**
 * STU-7 §K.3: one object's Motion Blur display state, as one undo step holds it.
 *
 * <p>Three flags, read and written together so a tap is one step and its undo restores exactly
 * what was there: {@code enabled} (the existing "rigidMotionBlur" switch every renderer reads),
 * {@code off} (the card is kept, nothing drawn) and {@code exportOnly} (exported, never drawn in
 * the editor). Off always means not enabled; the constructor keeps that true.</p>
 */
public record MotionBlurModes(boolean enabled, boolean off, boolean exportOnly) {

    /** No card at all: the state before "Add effect" and after ✕. */
    public static final MotionBlurModes NONE = new MotionBlurModes(false, false, false);

    /** A freshly added card: on, drawn live. */
    public static final MotionBlurModes LIVE = new MotionBlurModes(true, false, false);

    public MotionBlurModes {
        if (off) enabled = false;
    }

    @NonNull
    public static MotionBlurModes of(@NonNull MotionBlurItem item) {
        return new MotionBlurModes(item.isRigidMotionBlurEnabled(), item.isMotionBlurOff(),
                item.isMotionBlurExportOnly());
    }

    public void applyTo(@NonNull MotionBlurItem item) {
        item.setRigidMotionBlurEnabled(enabled);
        item.setMotionBlurOff(off);
        item.setMotionBlurExportOnly(exportOnly);
    }

    /** The icon tapped: on keeps Export only as it was; off keeps every setting. */
    @NonNull
    public MotionBlurModes switched(boolean on) {
        return new MotionBlurModes(on, !on, exportOnly);
    }

    @NonNull
    public MotionBlurModes withExportOnly(boolean on) {
        return new MotionBlurModes(enabled, off, on);
    }

    public boolean hasCard() { return enabled || off; }

    public boolean inPreview() { return enabled && !exportOnly; }
}

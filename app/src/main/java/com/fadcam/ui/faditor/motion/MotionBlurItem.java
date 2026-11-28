package com.fadcam.ui.faditor.motion;

import androidx.annotation.NonNull;

/** Timeline payloads that can author rigid transform blur. */
public interface MotionBlurItem {
    @NonNull String getId();
    boolean isRigidMotionBlurEnabled();
    void setRigidMotionBlurEnabled(boolean enabled);
    @NonNull MotionShutter.Controls getRigidMotionShutter();
    void setRigidMotionShutter(@NonNull MotionShutter.Controls controls);

    /** STU-7 §K: the card's Shutter and Mix keyframes (absolute timeline ms). */
    @NonNull ShutterKeys getRigidMotionShutterKeys();

    void setRigidMotionShutterKeys(@NonNull ShutterKeys keys);

    /** The shutter as it is at {@code timelineMs}: the fixed setting with any keys applied. */
    @NonNull
    default MotionShutter.Controls rigidMotionShutterAt(long timelineMs) {
        return getRigidMotionShutterKeys().at(timelineMs, getRigidMotionShutter());
    }

    // ── STU-7 §K.3: the three display states (owner, 11-28) ──
    //
    // Off is stored as "blur not enabled" plus this marker, so every renderer that already asks
    // isRigidMotionBlurEnabled() (export, preview, Joybot's frame) draws nothing without being
    // touched, and the marker only keeps the card and its settings. Export only keeps the blur
    // enabled for export; the editor asks isMotionBlurInPreview() instead.

    /** Off: the card is kept with its settings, and nothing is drawn. Implies not enabled. */
    boolean isMotionBlurOff();

    void setMotionBlurOff(boolean off);

    /** Export only: the blur is exported, never drawn in the editor. */
    boolean isMotionBlurExportOnly();

    void setMotionBlurExportOnly(boolean exportOnly);

    /** The object carries a Motion Blur card: on, export only, or off. */
    default boolean hasMotionBlurCard() {
        return isRigidMotionBlurEnabled() || isMotionBlurOff();
    }

    /** The editor draws this object's blur (on, and not export only). */
    default boolean isMotionBlurInPreview() {
        return isRigidMotionBlurEnabled() && !isMotionBlurExportOnly();
    }
}

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
}

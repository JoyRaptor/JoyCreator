package com.fadcam.ui.faditor.motion;

import androidx.annotation.NonNull;

/** Timeline payloads that can author rigid transform blur. */
public interface MotionBlurItem {
    @NonNull String getId();
    boolean isRigidMotionBlurEnabled();
    void setRigidMotionBlurEnabled(boolean enabled);
    @NonNull MotionShutter.Controls getRigidMotionShutter();
    void setRigidMotionShutter(@NonNull MotionShutter.Controls controls);
}

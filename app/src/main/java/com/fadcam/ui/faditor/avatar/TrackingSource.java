package com.fadcam.ui.faditor.avatar;

import androidx.annotation.NonNull;

/**
 * A2 (PLAN_AVATAR_STUDIO): a mountable tracker. Implementations own their
 * capture machinery (camera + MediaPipe, mic, a scripted clock) and push
 * {@link TrackingFrame}s from THEIR thread; the {@link TrackingDriverBus}
 * smooths and republishes for the render thread.
 *
 * <p>CAMERA SINGLE-OWNER (plan gotcha, binding): a camera-backed source must
 * be mounted by exactly ONE host at a time — Avatar Studio while editing,
 * FloatingWebcamService while recording. This interface is the component both
 * hosts mount exclusively; the bus enforces one active source.</p>
 *
 * <p>The MediaPipe FaceLandmarker source (USER-GATED on the gradle dep) drops
 * in behind this interface with no other code aware — see the A2 spec in
 * tasks/ for its exact param mapping.</p>
 */
public interface TrackingSource {

    interface FrameListener {
        /** Called from the source's thread for every sample. */
        void onFrame(@NonNull TrackingFrame frame);
    }

    /** Begin emitting frames. Never called twice without an intervening stop. */
    void start(@NonNull FrameListener listener);

    /** Stop emitting and release capture resources. Idempotent. */
    void stop();
}

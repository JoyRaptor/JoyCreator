package com.fadcam.ui.faditor.model;

/**
 * Anything another object can FOLLOW in the picture (SPEC_20260924_LINKING §12): its world pose at
 * a timeline time. Text, images and sprites already answer these questions for their renderers,
 * under these names, so a parent is simply "an object the renderers already know how to place".
 */
public interface LinkPose {
    String getId();
    float animatedCenterX(long timelineMs);
    float animatedCenterY(long timelineMs);
    float animatedSizeFraction(long timelineMs);
    float animatedRotation(long timelineMs);
    float animatedOpacity(long timelineMs);

    /** X, Y, size, rotation for shutter sampling. Non-rigid parents use the existing pose clock. */
    default void motionPlacementAt(double timelineMs, float[] placement) {
        long ms = (long) Math.floor(timelineMs);
        placement[0] = animatedCenterX(ms);
        placement[1] = animatedCenterY(ms);
        placement[2] = animatedSizeFraction(ms);
        placement[3] = animatedRotation(ms);
    }
}

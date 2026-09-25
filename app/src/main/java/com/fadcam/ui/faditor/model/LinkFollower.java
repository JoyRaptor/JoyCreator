package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Anything that can FOLLOW a parent in the picture (SPEC_20260924_LINKING): text, pictures and
 * sprites. The animated* getters (from {@link LinkPose}) are the WORLD pose every renderer draws;
 * the plain getters and setters are the object's OWN values, which is what a follower stores.
 */
public interface LinkFollower extends LinkPose {
    @Nullable SpaceLink getSpaceLink();
    void setSpaceLink(@Nullable SpaceLink l);

    /** Drawn through a live parent. */
    default boolean isLinked() {
        SpaceLink l = getSpaceLink();
        return l != null && l.active();
    }

    float getCenterX();
    float getCenterY();
    float getSizeFraction();
    float getRotationDeg();
    float getOpacity();
    void setCenter(float x, float y);
    void setSizeFraction(float f);
    void setRotationDeg(float deg);
    void setOpacity(float o);

    @NonNull com.fadcam.ui.faditor.keyframe.KeyframeSet getKeyframes();
    long getStartMs();
    boolean isVisibleAt(long timelineMs);

    /** An opaque copy of the pose (statics + keys) for undo; restored by {@link #restorePose}. */
    @NonNull Object snapshotPose();
    void restorePose(@NonNull Object snapshot);

    /** A WORLD position (where a finger lands) as this object's OWN values at {@code t}. */
    default void worldToOwn(float worldX, float worldY, long t, @NonNull float[] out) {
        SpaceLink l = getSpaceLink();
        if (l != null && l.active()) l.toOwn(worldX, worldY, t, out);
        else { out[0] = worldX; out[1] = worldY; }
    }

    default float worldSizeToOwn(float world, long t) {
        SpaceLink l = getSpaceLink();
        return l != null && l.active() ? l.sizeToOwn(world, t) : world;
    }

    default float worldRotationToOwn(float world, long t) {
        SpaceLink l = getSpaceLink();
        return l != null && l.active() ? l.rotToOwn(world, t) : world;
    }
}

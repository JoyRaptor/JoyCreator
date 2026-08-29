package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Descriptor for an image animation preset (SPEC 20260829 IMAGE_ANIM_PRESETS §3.1).
 * <p>
 * The preset is NOT a second source of truth — the {@link com.fadcam.ui.faditor.keyframe.KeyframeSet}
 * on the owning {@link TextOverlayItem} is. The preset exists to answer ONE question: when the
 * clip length changes, where do my two owned keys go? Answer: the ends.
 * </p>
 * <p>
 * Stored alongside keyframes; written only when kind != NONE so old projects stay byte-identical.
 * </p>
 */
public class ImageAnimPreset {

    public enum Kind {
        NONE,
        PAN_LEFT, PAN_RIGHT, PAN_UP, PAN_DOWN,
        ZOOM_IN, ZOOM_OUT,
        SLIDE_IN_LEFT, SLIDE_IN_RIGHT, SLIDE_IN_TOP, SLIDE_IN_BOTTOM,
        SLIDE_OUT_LEFT, SLIDE_OUT_RIGHT, SLIDE_OUT_TOP, SLIDE_OUT_BOTTOM
    }

    @NonNull public Kind kind = Kind.NONE;

    /**
     * For ZOOM_IN / ZOOM_OUT: the focused region's centre (0..1 canvas-normalised) and scale.
     * Default ~70% centred. User drags in preview to tweak — updates these fields and rewrites
     * owned keys WITHOUT converting (spec §3.2 preview stickiness).
     */
    public float zoomCenterX = 0.5f;
    public float zoomCenterY = 0.5f;
    /** Region scale as fraction of cover scale. 0.68 ~70%. >1 would be zoomed out beyond fill. */
    public float zoomRegionScale = 0.68f;

    /**
     * For PAN / ZOOM: the rotation at the region end (degrees). Preview rotation tweak updates this.
     * 0 = no rotation change. Used to allow "adjustable in preview to tweak zoom or position rotation
     * doesn't convert" (spec §3.2).
     */
    public float rotationDelta = 0f;

    public ImageAnimPreset() {}

    public ImageAnimPreset(@NonNull Kind kind) {
        this.kind = kind;
    }

    @NonNull
    public ImageAnimPreset copy() {
        ImageAnimPreset c = new ImageAnimPreset();
        c.kind = kind;
        c.zoomCenterX = zoomCenterX;
        c.zoomCenterY = zoomCenterY;
        c.zoomRegionScale = zoomRegionScale;
        c.rotationDelta = rotationDelta;
        return c;
    }

    @NonNull
    public static Kind kindFromName(@Nullable String name) {
        if (name == null) return Kind.NONE;
        try { return Kind.valueOf(name); } catch (IllegalArgumentException e) { return Kind.NONE; }
    }

    public boolean isActive() { return kind != Kind.NONE; }
}

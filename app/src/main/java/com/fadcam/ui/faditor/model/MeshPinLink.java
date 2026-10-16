package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.transform.mesh.MeshPinPose;

/**
 * One bend or puppet pin of a picture, as something another object can follow. Its id is the
 * owner's id, "#pin", and the pin's number, so a saved link finds it again (SpaceLink stores only
 * the parent id). It is built on demand by {@link Timeline}, never stored.
 *
 * <p>Position is the pin's; size, rotation and opacity are the OWNER's, so a prop riding a hand
 * pin turns and scales with the picture. If the pin is gone (the bend was removed) it reports the
 * picture's centre rather than throwing, like any missing parent.
 */
public final class MeshPinLink implements LinkPose {

    public static final String MARK = "#pin";

    /** Where this pin is at a time, in frame fractions; false when the pin no longer exists. */
    private interface PinAt { boolean at(long t, @NonNull float[] out); }

    @NonNull private final LinkPose owner;
    private final int pin;
    @NonNull private final PinAt where;

    /** A pin of a bent or puppeted picture. */
    public MeshPinLink(@NonNull TextOverlayItem owner, int pin, float frameAspect) {
        this.owner = owner;
        this.pin = pin;
        this.where = (t, out) -> MeshPinPose.pin(owner, pin, t, frameAspect, owner.getImageAspect(), out);
    }

    /** A pin of a bent or puppeted sprite. */
    public MeshPinLink(@NonNull com.fadcam.ui.faditor.sprite.SpriteOverlayItem owner, int pin,
                       float frameAspect) {
        this.owner = owner;
        this.pin = pin;
        this.where = (t, out) -> MeshPinPose.pin(owner, pin, t, frameAspect, out);
    }

    /** The picture or sprite this pin belongs to. */
    @NonNull public LinkPose owner() { return owner; }
    public int pin() { return pin; }

    @NonNull @Override public String getId() { return owner.getId() + MARK + pin; }

    @Override public float animatedCenterX(long t) {
        float[] p = new float[2];
        return where.at(t, p) ? p[0] : owner.animatedCenterX(t);
    }

    @Override public float animatedCenterY(long t) {
        float[] p = new float[2];
        return where.at(t, p) ? p[1] : owner.animatedCenterY(t);
    }

    @Override public float animatedSizeFraction(long t) { return owner.animatedSizeFraction(t); }
    @Override public float animatedRotation(long t) { return owner.animatedRotation(t); }
    @Override public float animatedOpacity(long t) { return owner.animatedOpacity(t); }
}

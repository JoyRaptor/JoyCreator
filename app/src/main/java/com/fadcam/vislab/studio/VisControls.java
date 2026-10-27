package com.fadcam.vislab.studio;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.VisLabKeys;
import com.fadcam.ui.faditor.model.WaveformOverlayInstance;

/**
 * A Studio visualizer's VisLab values at one moment (all keyframable, VisLabKeys): React (how
 * strongly the look hears the music) and the look-wide key knobs Bright, Colour, Size and Speed.
 * Fade is not here: it is the picture's opacity, applied where the picture is drawn.
 */
public final class VisControls {

    public final float react, bright, hue, size, speed;
    /** Studio objects the look's layers follow (R8): 4 floats a layer, see layerFollowAt. */
    @NonNull public final float[] follow;

    public VisControls(float react, float bright, float hue, float size, float speed) {
        this(react, bright, hue, size, speed, null);
    }

    public VisControls(float react, float bright, float hue, float size, float speed, @Nullable float[] follow) {
        this.follow = follow != null ? follow : new float[0];
        this.react = react;
        this.bright = bright;
        this.hue = hue;
        this.size = size;
        this.speed = speed;
    }

    /** Item {@code o}'s values at timeline time {@code timelineMs}, its layers still where the look put them. */
    @NonNull
    public static VisControls at(@NonNull WaveformOverlayInstance o, long timelineMs) {
        return at(o, timelineMs, 0, 0);
    }

    /**
     * As above, plus the layers that follow a Studio object, in the item's box of
     * {@code boxW x boxH} pixels (the size the look is drawn at).
     */
    @NonNull
    public static VisControls at(@NonNull WaveformOverlayInstance o, long timelineMs, int boxW, int boxH) {
        float[] fol = null;
        if (!o.getVisKeys().follows().isEmpty() && boxW > 0 && boxH > 0) {
            int layers = o.getVisKeys().follows().lastKey() + 1;
            fol = o.layerFollowAt(timelineMs, layers, boxW / (float) boxH);
        }
        return new VisControls(
                o.visValueAt(VisLabKeys.REACT, timelineMs),
                o.visValueAt(VisLabKeys.BRIGHT, timelineMs),
                o.visValueAt(VisLabKeys.HUE, timelineMs),
                o.visValueAt(VisLabKeys.SIZE, timelineMs),
                o.visValueAt(VisLabKeys.SPEED, timelineMs), fol);
    }

    boolean same(@Nullable VisControls o) {
        return o != null && react == o.react && bright == o.bright && hue == o.hue
                && size == o.size && speed == o.speed && java.util.Arrays.equals(follow, o.follow);
    }
}

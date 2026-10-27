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
    /** Look layers following other Studio objects at this moment (VisFollow), or null. */
    @Nullable public final float[] follow;
    /** Look picture layers showing a Studio picture or title (VisShow), or null. */
    @Nullable public final VisShow show;

    public VisControls(float react, float bright, float hue, float size, float speed) {
        this(react, bright, hue, size, speed, null);
    }

    public VisControls(float react, float bright, float hue, float size, float speed,
                       @Nullable float[] follow) {
        this(react, bright, hue, size, speed, follow, null);
    }

    public VisControls(float react, float bright, float hue, float size, float speed,
                       @Nullable float[] follow, @Nullable VisShow show) {
        this.react = react;
        this.bright = bright;
        this.hue = hue;
        this.size = size;
        this.speed = speed;
        this.follow = follow;
        this.show = show;
    }

    /** Item {@code o}'s values at timeline time {@code timelineMs}. */
    @NonNull
    public static VisControls at(@NonNull WaveformOverlayInstance o, long timelineMs) {
        return new VisControls(
                o.visValueAt(VisLabKeys.REACT, timelineMs),
                o.visValueAt(VisLabKeys.BRIGHT, timelineMs),
                o.visValueAt(VisLabKeys.HUE, timelineMs),
                o.visValueAt(VisLabKeys.SIZE, timelineMs),
                o.visValueAt(VisLabKeys.SPEED, timelineMs));
    }

    /**
     * The same, plus where its look layers that follow other objects sit ({@link VisFollow}),
     * which needs the frame's shape: {@code frameAspect} = frame width / height.
     */
    @NonNull
    public static VisControls at(@NonNull WaveformOverlayInstance o, long timelineMs, float frameAspect) {
        VisControls c = at(o, timelineMs);
        return new VisControls(c.react, c.bright, c.hue, c.size, c.speed,
                VisFollow.at(o, timelineMs, frameAspect), VisShow.at(o, timelineMs));
    }

    boolean same(@Nullable VisControls o) {
        return o != null && react == o.react && bright == o.bright && hue == o.hue
                && size == o.size && speed == o.speed && java.util.Arrays.equals(follow, o.follow)
                && java.util.Arrays.equals(show != null ? show.pairs : null, o.show != null ? o.show.pairs : null);
    }
}

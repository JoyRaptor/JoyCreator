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

    public VisControls(float react, float bright, float hue, float size, float speed) {
        this.react = react;
        this.bright = bright;
        this.hue = hue;
        this.size = size;
        this.speed = speed;
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

    boolean same(@Nullable VisControls o) {
        return o != null && react == o.react && bright == o.bright && hue == o.hue
                && size == o.size && speed == o.speed;
    }
}

package com.fadcam.vislab.room;

import androidx.annotation.NonNull;

import com.fadcam.vislab.model.VisKnob;

/**
 * The soft magnet on a knob's slider, made when a drag starts. It sticks the value to a handful
 * of places worth landing on exactly: zero, the knob's default, the angle marks (45 degrees) and
 * where the slider was when the finger came down. Positions are shares of the track, 0..1.
 */
final class SnapSlider {

    /** The finger is pulled in when it comes this close to a magnet (a share of the track)... */
    private static final float ENTER = 0.025f;
    /** ...and let go a little further out, so a shaky finger at the edge neither jumps nor buzzes. */
    private static final float LEAVE = 0.032f;
    /** Two magnets closer than this are one (the earlier in the list wins). */
    private static final float APART = 0.005f;

    private final float[] at;
    private final float[] value;
    private int n;
    private final float startAt;
    private int held = -1;
    private boolean entered;

    /** {@code start}: the knob's value at touch-down. */
    SnapSlider(@NonNull VisKnob k, float start) {
        float[] marks = AngleMarks.of(k);
        at = new float[marks.length + 3];
        value = new float[at.length];
        // Priority order, for the rare magnets that fall together: zero, angle marks, default, start.
        if (k.min <= 0 && k.max >= 0) add(k, 0f);
        for (float m : marks) add(k, m);
        add(k, k.def);
        add(k, start);
        float s = KnobPanel.toSlider(k, start);
        startAt = Float.isNaN(s) ? 0f : Math.max(0f, Math.min(1f, s));
    }

    private void add(@NonNull VisKnob k, float v) {
        float p = KnobPanel.toSlider(k, v);
        if (Float.isNaN(p) || p < -1e-4f || p > 1f + 1e-4f) return;   // not on this track
        p = Math.max(0f, Math.min(1f, p));
        for (int i = 0; i < n; i++) if (Math.abs(at[i] - p) < APART) return;
        at[n] = p;
        value[n] = v;
        n++;
    }

    /** Where the slider stood when the drag began, for the "where it was" dot. */
    float startPos() {
        return startAt;
    }

    /**
     * The finger is at {@code pos}. Returns the magnet holding it (index for {@link #pos} and
     * {@link #value}), or -1 when it is free. A magnet already holding keeps its grip a bit longer.
     */
    int pull(float pos) {
        int hit = -1;
        float near = ENTER;
        for (int i = 0; i < n; i++) {
            float d = Math.abs(at[i] - pos);
            if (d <= near) {
                near = d;
                hit = i;
            }
        }
        if (hit < 0 && held >= 0 && Math.abs(at[held] - pos) <= LEAVE) hit = held;
        entered = hit >= 0 && hit != held;
        held = hit;
        return hit;
    }

    /** True when the last {@link #pull} just caught a magnet it was not already on (one haptic tick). */
    boolean entered() {
        return entered;
    }

    /** The exact knob value of magnet {@code i}. */
    float value(int i) {
        return value[i];
    }

    /** The track position of magnet {@code i}. */
    float pos(int i) {
        return at[i];
    }
}

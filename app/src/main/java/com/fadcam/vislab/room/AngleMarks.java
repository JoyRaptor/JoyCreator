package com.fadcam.vislab.room;

import androidx.annotation.NonNull;

import com.fadcam.vislab.model.VisKnob;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * The marks an angle knob's slider sticks to: every eighth of a turn (45 degrees), so 0, 45,
 * 90... are easy to land on. Some angle knobs are in degrees (-180..180), some in turns
 * (-0.5..0.5); the range tells which. Only knobs that swing both ways around zero count: a
 * 0..1 "tilt" is an amount, not an angle.
 */
final class AngleMarks {

    private static final Set<String> IDS = new HashSet<>(Arrays.asList(
            "turn", "rotation", "roll", "lean", "aim", "dir", "tilt", "pitch", "yaw", "spin"));
    private static final float[] NONE = new float[0];
    /** Marks never sit closer than this share of the track, or the magnets would run together. */
    private static final float MIN_GAP = 0.125f - 1e-4f;

    private AngleMarks() {
    }

    /** The knob values of its marks, or none when it is not an angle. */
    @NonNull
    static float[] of(@NonNull VisKnob k) {
        if (!IDS.contains(k.id) || k.log || k.min >= 0 || k.max <= 0) return NONE;
        float span = k.max - k.min;
        // A span of tens of units is degrees; a span of a turn or two is turns.
        float step = (span > 10f ? 360f : 1f) / 8f;
        // A wide range (-1..1 turns) would pack the eighths together: use quarters there.
        while (step / span < MIN_GAP) step *= 2f;
        int first = (int) Math.ceil(k.min / step - 1e-4);
        int last = (int) Math.floor(k.max / step + 1e-4);
        if (last < first) return NONE;
        float[] marks = new float[last - first + 1];
        for (int i = first; i <= last; i++) marks[i - first] = i * step;
        return marks;
    }
}

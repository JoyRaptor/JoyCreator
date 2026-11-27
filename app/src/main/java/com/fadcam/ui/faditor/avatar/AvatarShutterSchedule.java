package com.fadcam.ui.faditor.avatar;

import com.fadcam.ui.faditor.motion.MotionBreaks;
import com.fadcam.ui.faditor.motion.MotionShutter;

import java.util.Arrays;

/** Shared editor-clock exposure for the recorded avatar preview and export renderers. */
public final class AvatarShutterSchedule {
    private static final int TAPS = 6;

    private AvatarShutterSchedule() {}

    /**
     * Intersects the half-open decoder piece with the avatar's half-open visible span.
     * Pose/placement breaks are on the editor clock. A null result uses the sharp route.
     */
    public static MotionShutter.Schedule plan(MotionShutter.Controls controls, long editorUs,
            long periodUs, long itemStartUs, long itemEndUs, long pieceStartUs, long pieceEndUs,
            MotionBreaks localBreaks) {
        long start = Math.max(itemStartUs, pieceStartUs);
        long end = Math.min(itemEndUs, pieceEndUs);
        if (editorUs < start || editorUs >= end || periodUs <= 0 || periodUs > Integer.MAX_VALUE) {
            return null;
        }
        var span = new MotionBreaks.Builder().add(start).add(end).build();
        var shutter = controls.schedule(editorUs, periodUs, TAPS, span, localBreaks);
        long[] times = shutter.timesUs();
        double[] weights = shutter.weights();
        // The exposure taps are ascending. Partial Mix may append an earlier sharp centre.
        int last = times.length - 1;
        long finalTime = times[last];
        double finalWeight = weights[last];
        int insert = last;
        while (insert > 0 && times[insert - 1] > finalTime) {
            times[insert] = times[insert - 1];
            weights[insert] = weights[insert - 1];
            insert--;
        }
        times[insert] = finalTime;
        weights[insert] = finalWeight;

        int distinct = 0;
        for (int i = 0; i < times.length; i++) {
            if (distinct > 0 && times[i] == times[distinct - 1]) {
                weights[distinct - 1] += weights[i];
            } else {
                times[distinct] = times[i];
                weights[distinct++] = weights[i];
            }
        }
        if (distinct < 2) return null;
        return distinct == times.length ? shutter
                : new MotionShutter.Schedule(Arrays.copyOf(times, distinct),
                        Arrays.copyOf(weights, distinct));
    }
}

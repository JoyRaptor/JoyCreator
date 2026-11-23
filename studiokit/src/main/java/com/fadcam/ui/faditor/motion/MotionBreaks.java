package com.fadcam.ui.faditor.motion;

import com.fadcam.ui.faditor.keyframe.Easing;
import com.fadcam.ui.faditor.keyframe.Keyframe;
import com.fadcam.ui.faditor.keyframe.KeyframeTrack;

import java.util.Arrays;
import java.util.TreeSet;

/** Discontinuities and unavailable ranges on the same clock as the shutter. */
public final class MotionBreaks {
    private static final MotionBreaks NONE = new MotionBreaks(new long[0], new long[0]);

    private final long[] pointsUs;
    // Alternating inclusive starts and exclusive ends. Overlaps are coalesced by the builder.
    private final long[] gapsUs;

    private MotionBreaks(long[] pointsUs, long[] gapsUs) {
        this.pointsUs = pointsUs;
        this.gapsUs = gapsUs;
    }

    public static MotionBreaks none() { return NONE; }

    /** The portion of the exposure in the centre timestamp's continuous segment. */
    public record Window(double openUs, double closeUs, long firstValidUs, long lastValidUs) {
        public boolean hasSamples() { return openUs < closeUs && firstValidUs <= lastValidUs; }
    }

    public Window splitWindow(long centerUs, double openUs, double closeUs) {
        int at = Arrays.binarySearch(pointsUs, centerUs);
        int next = at >= 0 ? at + 1 : -at - 1;
        long lower = next == 0 ? Long.MIN_VALUE : pointsUs[next - 1];
        long upper = next == pointsUs.length ? Long.MAX_VALUE : pointsUs[next];

        int gap = Arrays.binarySearch(gapsUs, centerUs);
        int following = gap >= 0 ? gap + 1 : -gap - 1;
        if ((following & 1) != 0) return new Window(centerUs, centerUs, centerUs, centerUs);
        if (following > 0) lower = Math.max(lower, gapsUs[following - 1]);
        if (following < gapsUs.length) upper = Math.min(upper, gapsUs[following]);

        double start = Math.max(openUs, lower);
        double end = Math.min(closeUs, upper);
        long first = (long) Math.ceil(start);
        long last = (long) Math.ceil(end) - 1;
        return new Window(start, end, first, last);
    }

    public static final class Builder {
        private final TreeSet<Long> points = new TreeSet<>();
        private final java.util.TreeMap<Long, Long> gaps = new java.util.TreeMap<>();

        /** At the boundary itself, the new segment owns the frame. */
        public Builder add(long atUs) {
            points.add(atUs);
            return this;
        }

        public Builder exclude(long startUs, long endUs) {
            if (endUs <= startUs) return this;
            var previous = gaps.floorEntry(startUs);
            if (previous != null && previous.getValue() >= startUs) {
                startUs = previous.getKey();
                endUs = Math.max(endUs, previous.getValue());
                gaps.remove(startUs);
            }
            var next = gaps.ceilingEntry(startUs);
            while (next != null && next.getKey() <= endUs) {
                endUs = Math.max(endUs, next.getValue());
                gaps.remove(next.getKey());
                next = gaps.ceilingEntry(startUs);
            }
            gaps.put(startUs, endUs);
            return this;
        }

        /** HOLD changes at the next key. STAIRS_4 also jumps inside its key span. */
        public Builder addSteps(KeyframeTrack track, long itemStartUs) {
            for (int i = 1; i < track.keyframes.size(); i++) {
                Keyframe a = track.keyframes.get(i - 1);
                Keyframe b = track.keyframes.get(i);
                if (a.value == b.value || b.timeMs <= a.timeMs) continue;
                if (a.easing == Easing.HOLD || a.easing == Easing.STAIRS_4) {
                    add(itemStartUs + b.timeMs * 1000L);
                }
                if (a.easing == Easing.STAIRS_4) {
                    long begin = itemStartUs + a.timeMs * 1000L;
                    long span = (b.timeMs - a.timeMs) * 1000L;
                    for (int step = 1; step < 4; step++) add(begin + span / 4 * step);
                }
            }
            return this;
        }

        public MotionBreaks build() {
            if (points.isEmpty() && gaps.isEmpty()) return NONE;
            long[] boundaries = new long[points.size()];
            int index = 0;
            for (long point : points) boundaries[index++] = point;
            long[] excluded = new long[gaps.size() * 2];
            index = 0;
            for (var gap : gaps.entrySet()) {
                excluded[index++] = gap.getKey();
                excluded[index++] = gap.getValue();
            }
            return new MotionBreaks(boundaries, excluded);
        }
    }
}

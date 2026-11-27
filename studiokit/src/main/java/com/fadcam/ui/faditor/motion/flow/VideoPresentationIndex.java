package com.fadcam.ui.faditor.motion.flow;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

/** Exact presentation neighbours from one complete, unambiguous video track. */
public final class VideoPresentationIndex {
    public static final int MAX_SAMPLES = 216_000;

    public record Pair(long fromPtsUs, long toPtsUs) {}

    private final long[] times;

    private VideoPresentationIndex(long[] times) {
        this.times = times;
    }

    public int size() { return times.length; }

    /** Bounded pairs intersecting the viewport, ordered outwards from the source playhead. */
    List<Pair> visiblePairs(long playheadUs, long visibleStartUs, long visibleEndUs,
                            long segmentStartUs, long segmentEndUs, long maxGapUs, int limit) {
        var pairs = new ArrayList<Pair>();
        long start = Math.max(visibleStartUs, segmentStartUs);
        long end = Math.min(visibleEndUs, segmentEndUs);
        if (start >= end || limit <= 0) return pairs;
        long anchor = Math.max(start, Math.min(playheadUs, end - 1));
        int first = Math.max(lowerBound(segmentStartUs), lowerBound(start) - 1);
        int last = Math.min(lowerBound(end), lowerBound(segmentEndUs) - 1) - 1;
        int split = lowerBound(anchor);
        int left = Math.min(last, split - 1);
        int right = Math.max(first, split);
        while (pairs.size() < limit && (left >= first || right <= last)) {
            int i;
            if (right > last || (left >= first && anchor - times[left] <= times[right] - anchor)) {
                i = left--;
            } else {
                i = right++;
            }
            if (times[i + 1] > start && times[i + 1] - times[i] <= maxGapUs) {
                pairs.add(new Pair(times[i], times[i + 1]));
            }
        }
        return pairs;
    }

    private int lowerBound(long ptsUs) {
        int index = Arrays.binarySearch(times, ptsUs);
        return index >= 0 ? index : -index - 1;
    }

    /**
     * Find the next display frame after an exact timestamp. A missing centre, trim boundary,
     * or gap beyond the caller's continuity limit leaves the frame sharp.
     */
    public Pair next(long fromPtsUs, long startUs, long endUs, long maximumGapUs) {
        if (fromPtsUs < startUs || fromPtsUs >= endUs || maximumGapUs <= 0) return null;
        int i = Arrays.binarySearch(times, fromPtsUs);
        if (i < 0 || i == times.length - 1) return null;
        long next = times[i + 1];
        return next < endUs && next - fromPtsUs <= maximumGapUs
                ? new Pair(fromPtsUs, next) : null;
    }

    /** Find the previous display frame within the same trim and continuity limit. */
    public Pair previous(long toPtsUs, long startUs, long endUs, long maximumGapUs) {
        if (toPtsUs < startUs || toPtsUs >= endUs || maximumGapUs <= 0) return null;
        int i = Arrays.binarySearch(times, toPtsUs);
        if (i <= 0) return null;
        long previous = times[i - 1];
        return previous >= startUs && toPtsUs - previous <= maximumGapUs
                ? new Pair(previous, toPtsUs) : null;
    }

    public static final class Builder {
        private long[] times = new long[1024];
        private int size;
        private boolean unsupported;

        /** Input is in extractor order, which need not be presentation order for B frames. */
        public boolean add(long ptsUs) {
            if (unsupported || ptsUs < 0 || size == MAX_SAMPLES) {
                unsupported = true;
                return false;
            }
            if (size == times.length) times = Arrays.copyOf(times, Math.min(MAX_SAMPLES, size * 2));
            times[size++] = ptsUs;
            return true;
        }

        /** Never publish a partial track or choose between samples sharing a PTS. */
        public VideoPresentationIndex finish() {
            if (unsupported || size < 2) return null;
            long[] sorted = Arrays.copyOf(times, size);
            Arrays.sort(sorted);
            for (int i = 1; i < sorted.length; i++) {
                if (sorted[i - 1] == sorted[i]) return null;
            }
            return new VideoPresentationIndex(sorted);
        }
    }
}

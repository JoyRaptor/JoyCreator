package com.fadcam.ui.faditor.motion.flow;

import java.util.Arrays;

/** Exact presentation neighbours from one complete, unambiguous video track. */
public final class VideoPresentationIndex {
    public static final int MAX_SAMPLES = 216_000;

    public record Pair(long fromPtsUs, long toPtsUs) {}

    private final long[] times;

    private VideoPresentationIndex(long[] times) {
        this.times = times;
    }

    public int size() { return times.length; }

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

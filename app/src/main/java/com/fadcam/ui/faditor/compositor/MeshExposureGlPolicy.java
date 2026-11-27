package com.fadcam.ui.faditor.compositor;

/** GL-free admission rules for one isolated exact mesh exposure. */
final class MeshExposureGlPolicy {
    static final int MAX_TAPS = 32;
    // Mesh stamp + sample are RGBA8; both accumulation targets are RGBA16F.
    static final int WORKING_BYTES_PER_PIXEL = 24;
    static final long WORKING_LIMIT_BYTES = 48L * 1024L * 1024L;

    private MeshExposureGlPolicy() {}

    /**
     * True only when the schedule needs more than one authored pose and fits the bounded targets.
     * Invalid schedules are programmer errors; ordinary sharp schedules and large frames decline.
     */
    static boolean admits(int width, int height, long[] timesUs, double[] weights) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid mesh exposure size");
        }
        if (timesUs == null || weights == null || timesUs.length != weights.length
                || timesUs.length == 0) {
            throw new IllegalArgumentException("Invalid mesh exposure schedule");
        }
        if (timesUs.length > MAX_TAPS) {
            throw new IllegalArgumentException("Too many mesh exposure taps");
        }

        int firstPositive = -1;
        boolean distinctTimes = false;
        double totalWeight = 0.0;
        for (int i = 0; i < timesUs.length; i++) {
            if (i > 0 && timesUs[i] < timesUs[i - 1]) {
                throw new IllegalArgumentException("Mesh exposure taps are not ordered");
            }
            double weight = weights[i];
            if (!Double.isFinite(weight) || weight < 0.0) {
                throw new IllegalArgumentException("Invalid mesh exposure weight");
            }
            if (weight == 0.0) continue;
            if (firstPositive < 0) firstPositive = i;
            else distinctTimes |= timesUs[i] != timesUs[firstPositive];
            totalWeight += weight;
        }
        if (firstPositive < 0 || !Double.isFinite(totalWeight)) {
            throw new IllegalArgumentException("Invalid total mesh exposure weight");
        }

        long pixels = (long) width * height;
        return distinctTimes && pixels <= WORKING_LIMIT_BYTES / WORKING_BYTES_PER_PIXEL;
    }
}

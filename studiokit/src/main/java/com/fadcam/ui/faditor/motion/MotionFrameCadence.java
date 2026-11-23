package com.fadcam.ui.faditor.motion;

/** Measures the local period from the presentation timestamps received by an export pass. */
public final class MotionFrameCadence {
    private static final long NO_TIMESTAMP = Long.MIN_VALUE;
    private static final long MAX_PERIOD_US = 1_000_000L;
    private long previousUs = NO_TIMESTAMP;

    /**
     * Use the interval ending at this frame. Zero means the clock has
     * no usable history. Repeated timestamps, backwards seeks and long gaps start a new run.
     */
    public long observe(long presentationTimeUs) {
        long prior = previousUs;
        previousUs = presentationTimeUs;
        if (prior == NO_TIMESTAMP || presentationTimeUs <= prior) return 0L;
        long periodUs = presentationTimeUs - prior;
        return periodUs > 0 && periodUs <= MAX_PERIOD_US ? periodUs : 0L;
    }

    public void reset() {
        previousUs = NO_TIMESTAMP;
    }
}

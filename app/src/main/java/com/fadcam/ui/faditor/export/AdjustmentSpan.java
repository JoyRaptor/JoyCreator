package com.fadcam.ui.faditor.export;

/** Conservative build-time rejection. AdjustmentLayer.activeAt remains the exact per-frame gate. */
final class AdjustmentSpan {
    private AdjustmentSpan() {}

    static boolean overlaps(long start, long duration, long cursor, long offset, long pieceDuration) {
        if (pieceDuration <= 0 || start < 0 || duration < 0) return true;
        try {
            // Match the five-second margin used for export overlay admission. Container timestamps
            // and the nominal composition cursor need not agree at a cut or a rounded speed change.
            long begin = Math.addExact(cursor, offset);
            long low = Math.subtractExact(begin, 5000L);
            long high = Math.addExact(Math.addExact(begin, pieceDuration), 5000L);
            return start < high && (duration == 0 || Math.addExact(start, duration) > low);
        } catch (ArithmeticException ambiguousClock) {
            return true;
        }
    }
}

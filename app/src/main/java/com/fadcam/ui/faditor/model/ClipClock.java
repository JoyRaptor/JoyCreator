package com.fadcam.ui.faditor.model;

/**
 * The ONE rounding rule between a clip's source clock and its timeline clock (job STU-5, D9).
 *
 * <p>Timeline ms from a clip's own start = source ms / speed. Some places cut the quotient off
 * to whole milliseconds and others rounded it (as {@link TimeMap#clipLocalMsAt} does), so each
 * split of a speeded clip could lose 1 ms and two readers could disagree by a frame at a cut.
 * Every conversion in the editor now goes through here and rounds.</p>
 *
 * <p>Pure: no Android.</p>
 */
public final class ClipClock {

    private ClipClock() {}

    /** Source ms (an offset or a length) to timeline ms at {@code speed}, rounded. */
    public static long sourceToLocalMs(long sourceMs, float speed) {
        if (!(speed > 0f)) return sourceMs;
        return Math.round(sourceMs / (double) speed);
    }

    /** Timeline ms (from the clip's own start) to a source offset at {@code speed}, rounded. */
    public static long localToSourceMs(long localMs, float speed) {
        if (!(speed > 0f)) return localMs;
        return Math.round(localMs * (double) speed);
    }

    /**
     * Where to cut so the two halves add up to the whole. Rounding each half on its own can be
     * 1 ms more or less than rounding the whole ({@code round(a/s) + round(b/s)} vs
     * {@code round((a+b)/s)}), so a clip split many times drifted. This looks within 3 source ms
     * of the wanted cut for a point where the halves' timeline lengths sum exactly to the clip's,
     * nearest first, and returns the wanted cut if none does. At normal speed every point
     * qualifies, so the cut never moves.
     */
    public static long snapCut(long inPointMs, long outPointMs, float speed, long wantedMs) {
        long total = sourceToLocalMs(outPointMs - inPointMs, speed);
        for (int d = 0; d <= 3; d++) {
            for (int sign = 1; sign >= -1; sign -= 2) {
                if (d == 0 && sign < 0) continue;
                long cut = wantedMs + sign * d;
                if (cut <= inPointMs || cut >= outPointMs) continue;
                long a = sourceToLocalMs(cut - inPointMs, speed);
                long b = sourceToLocalMs(outPointMs - cut, speed);
                if (a + b == total) return cut;
            }
        }
        return wantedMs;
    }

    /**
     * Which of consecutive spans owns timeline time {@code t}: the LATER span owns a cut point
     * (a span owns its start and not its end), and the last span also owns the very end. One rule
     * for the seek and for the selection, which used to disagree by one frame at every cut (D7).
     * Returns -1 for no spans.
     */
    public static int spanIndexAt(long[] spanLengthsMs, long t) {
        if (spanLengthsMs.length == 0) return -1;
        long cursor = 0L;
        for (int i = 0; i < spanLengthsMs.length; i++) {
            long end = cursor + spanLengthsMs[i];
            if (t < end) return i;
            cursor = end;
        }
        return spanLengthsMs.length - 1;
    }
}

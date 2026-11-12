package com.fadcam.ui.faditor.compositor;

/**
 * SEEK UNITS INSIDE A LOOPED CLIP OF THE GAPLESS PLAYER (code review 2026-11-11, row D10).
 *
 * <p>The player holds one window per loop repeat. A window's length on the timeline
 * ({@code visualLen}) is already divided by the clip's speed, while the position the player
 * reports and accepts inside a window is in the SOURCE's own time, which runs {@code speed} times
 * faster. {@code seekInClip} and {@code getCurrentPositionInWindow} are documented as source ms,
 * continuous across the repeats, so a clip's position {@code M} is
 * {@code visualStart * speed + (source ms into the window)}. The old code picked the window by
 * comparing {@code M} with timeline-length ranges and clamped the position inside it to the
 * timeline length, which at any speed other than normal chose the wrong repeat and, at 2x,
 * clamped every seek in the second half of a window to its middle. At normal speed the two units
 * are the same number, so nothing changes there.</p>
 *
 * <p>Pure: no Android, no player.</p>
 */
public final class WindowSeekMap {

    private WindowSeekMap() { }

    /** Source ms that a window of {@code visualLenMs} timeline ms plays at {@code speed}. */
    public static long mediaLen(long visualLenMs, float speed) {
        return speed <= 0f || speed == 1f ? visualLenMs : Math.round(visualLenMs * (double) speed);
    }

    /** Where a window starts on the clip's continuous source clock. */
    public static long mediaStart(long visualStartMs, float speed) {
        return speed <= 0f || speed == 1f ? visualStartMs : Math.round(visualStartMs * (double) speed);
    }

    /** The clip's continuous position of {@code localMs} (source ms) into a window. */
    public static long positionOf(long visualStartMs, float speed, long localMs) {
        return mediaStart(visualStartMs, speed) + localMs;
    }

    /**
     * Resolve a continuous position to {@code {windowIndex, localMs}} among one clip's windows
     * ({@code visualStart[i]}, {@code visualLen[i]}, in order). Clamped to the clip's ends.
     */
    public static long[] resolve(long[] visualStart, long[] visualLen, float speed, long positionMs) {
        long m = Math.max(0L, positionMs);
        int last = visualStart.length - 1;
        for (int i = 0; i <= last; i++) {
            long from = mediaStart(visualStart[i], speed);
            long len = mediaLen(visualLen[i], speed);
            if (m < from + len || i == last) {
                long local = Math.max(0L, Math.min(m - from, len));
                return new long[]{i, local};
            }
        }
        return new long[]{0L, 0L};
    }
}

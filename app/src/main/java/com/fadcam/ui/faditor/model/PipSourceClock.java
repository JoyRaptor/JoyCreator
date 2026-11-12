package com.fadcam.ui.faditor.model;

/**
 * WHICH MOMENT OF A PICTURE-IN-PICTURE'S SOURCE SHOWS AT A GIVEN MOMENT OF THE TIMELINE
 * (code review 2026-11-11, row D3).
 *
 * <p>A picture-in-picture's window on the timeline is its trimmed length divided by its speed
 * ({@code Clip.getTrimmedDurationMs}), and its sound is stretched to fit that window. Its picture
 * was read one-to-one from the source ({@code in + (t - start)}), so a 2x picture-in-picture
 * showed only the first half of its footage in a window the sound filled with all of it: picture
 * and sound of the same clip showed different moments. The source clock runs {@code speed} times
 * faster than the timeline clock; every reader of a picture-in-picture's picture (the export's
 * two frame sources and the editor's live preview) asks this class so they cannot disagree again.</p>
 *
 * <p>Pure: no Android, no media.</p>
 */
public final class PipSourceClock {

    private PipSourceClock() { }

    /**
     * @param inPointMs  the clip's in-point in its source
     * @param outPointMs the clip's out-point in its source
     * @param startMs    where the clip's window starts on the timeline
     * @param speed      the clip's speed multiplier (1 = normal)
     * @param timelineMs a moment on the timeline (inside the window, or before it)
     * @return the source time to show, never before the in-point and never past the last frame
     */
    public static long sourceMsAt(long inPointMs, long outPointMs, long startMs,
                                  float speed, long timelineMs) {
        float s = speed > 0f ? speed : 1f;
        long into = Math.max(0L, timelineMs - startMs);
        long source = inPointMs + Math.round(into * (double) s);
        long last = Math.max(inPointMs, outPointMs - 1);
        return Math.min(source, last);
    }

    /** The same, for a clip. */
    public static long sourceMsAt(Clip clip, long timelineMs) {
        return sourceMsAt(clip.getInPointMs(), clip.getOutPointMs(), clip.getOverlayStartMs(),
                clip.getSpeedMultiplier(), timelineMs);
    }
}

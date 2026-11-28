package com.fadcam.ui.faditor.export;

/**
 * Where picture-in-picture sound goes in the export (STU-F6, 11-28): which sound sequence ("lane") each PiP is laid on,
 * and how much of a piece fits before the video ends. Pure, for the harness.
 */
public final class PipAudioLanes {
    private PipAudioLanes() {}

    /**
     * The lane of each PiP, given their file-time spans sorted by start. Today's rule: all on one lane, end to end.
     */
    public static int[] assign(long[] starts, long[] ends) {
        return new int[starts.length];
    }

    /** How many ms of a piece starting at [startMs] and [playedMs] long fit before [endMs]. Today: all of it. */
    public static long fits(long startMs, long playedMs, long endMs) {
        return playedMs;
    }
}

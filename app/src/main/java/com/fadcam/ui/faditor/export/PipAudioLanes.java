package com.fadcam.ui.faditor.export;

/**
 * Where picture-in-picture sound goes in the export (STU-F6, 11-28): which sound sequence ("lane") each PiP is laid on,
 * and how much of a piece fits before the video ends. Pure, for the harness.
 */
public final class PipAudioLanes {
    private PipAudioLanes() {}

    /**
     * The lane of each PiP, given their file-time spans sorted by start: the first lane whose last PiP has ended,
     * else a new one. One lane was laid end to end with one running cursor, so a PiP that started while another was
     * still playing was queued after it instead of sounding with it.
     */
    public static int[] assign(long[] starts, long[] ends) {
        int[] lane = new int[starts.length];
        java.util.List<Long> laneEnds = new java.util.ArrayList<>();
        for (int i = 0; i < starts.length; i++) {
            int found = -1;
            for (int l = 0; l < laneEnds.size(); l++) {
                if (laneEnds.get(l) <= starts[i]) { found = l; break; }
            }
            if (found < 0) { found = laneEnds.size(); laneEnds.add(ends[i]); } else laneEnds.set(found, ends[i]);
            lane[i] = found;
        }
        return lane;
    }

    /** How many lanes [assign] used. */
    public static int count(int[] lanes) {
        int n = 0;
        for (int l : lanes) n = Math.max(n, l + 1);
        return n;
    }

    /**
     * How many ms of a piece starting at [startMs] and [playedMs] long fit before the video ends at [endMs]: a PiP's
     * sound never runs past the picture (a 6 s video exported 16 s of sound).
     */
    public static long fits(long startMs, long playedMs, long endMs) {
        return Math.max(0L, Math.min(playedMs, endMs - startMs));
    }
}

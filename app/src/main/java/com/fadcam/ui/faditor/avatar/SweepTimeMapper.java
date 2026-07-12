package com.fadcam.ui.faditor.avatar;

/**
 * Point-at-video (PLAN_AVATAR_STUDIO A4-NEXT): the pure timeline→clip walk the
 * {@link VideoFaceSweeper} uses to find, for each swept timeline instant, WHICH
 * master clip is under it and WHERE inside that clip (clip-local visual ms).
 * The second hop — clip-local visual ms → absolute source ms — is NOT here on
 * purpose: {@code Clip.mapToSourceMs} is the existing single authority for that
 * (thumbnails, playback seeking and export frame mapping already ride it), and
 * the sweep must consume it, not re-derive it.
 *
 * <p>The span walk mirrors {@code Timeline.segmentStartMs} exactly: a clip's
 * on-timeline span is its visual duration when loop-extended, else its trimmed
 * duration — the caller precomputes those spans into a plain {@code long[]} so
 * this class stays pure Java (JVM-harness testable, tools/jvm-harness).</p>
 */
public final class SweepTimeMapper {

    private SweepTimeMapper() {}

    /**
     * Index of the clip whose on-timeline window {@code [start, start+span)}
     * contains {@code timelineMs}, or -1 when the time falls before 0, past the
     * last clip, or inside a degenerate (≤0-span) clip's zero-width window.
     */
    public static int clipIndexAt(long[] spansMs, long timelineMs) {
        if (timelineMs < 0) return -1;
        long start = 0;
        for (int i = 0; i < spansMs.length; i++) {
            long span = Math.max(0, spansMs[i]);
            if (timelineMs < start + span) return i;
            start += span;
        }
        return -1;
    }

    /**
     * Clip-local visual ms of {@code timelineMs} inside the clip at
     * {@code index} — the value {@code Clip.mapToSourceMs} takes. The caller
     * must pass an index obtained from {@link #clipIndexAt} for the same spans;
     * out-of-range indices return -1.
     */
    public static long clipLocalMs(long[] spansMs, int index, long timelineMs) {
        if (index < 0 || index >= spansMs.length) return -1;
        long start = 0;
        for (int i = 0; i < index; i++) {
            start += Math.max(0, spansMs[i]);
        }
        return timelineMs - start;
    }
}

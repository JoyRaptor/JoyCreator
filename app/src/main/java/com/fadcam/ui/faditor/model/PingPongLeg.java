package com.fadcam.ui.faditor.model;

/**
 * One repeat ("leg") of a looped clip's extension, before or after its own pass: whether it plays backward, and the
 * window it plays. ONE rule for the preview (MasterPlaybackEngine.addLoopReps) and the export
 * (ExportManager.buildLoopExtensionItem), so the two land on the same frames (STU-F7, 11-27).
 *
 * <p>A forward leg's window is in the clip's source ({@code [startMs, endMs)} of the file). A backward leg's window is
 * in the baked reversed copy of {@code [inMs, outMs)}, whose time t is source time {@code outMs - t}.</p>
 */
public final class PingPongLeg {
    public final boolean reverse;
    public final long startMs;
    public final long endMs;

    private PingPongLeg(boolean reverse, long startMs, long endMs) {
        this.reverse = reverse;
        this.startMs = startMs;
        this.endMs = endMs;
    }

    /**
     * Leg {@code r} of {@code reps} (in the order they are laid), {@code spanMs} of source long.
     * {@code pingPong}: the clip ping-pongs and its reversed copy exists; otherwise every leg replays forward.
     */
    public static PingPongLeg of(boolean isBefore, int reps, int r, long inMs, long outMs, long spanMs, boolean pingPong) {
        boolean reverse = pingPong && ((isBefore ? reps - 1 - r : r) % 2 == 1);
        if (reverse) {
            long revLen = outMs - inMs;
            return new PingPongLeg(true, Math.max(0L, revLen - spanMs), revLen);
        }
        return new PingPongLeg(false, inMs, Math.min(outMs, inMs + spanMs));
    }
}

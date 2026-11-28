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
        // How far this leg is from the clip's own (forward) pass: the nearest one is 0. It plays backward, and from there
        // the legs alternate, so each starts where its neighbour ended (STU-F7: the nearest played forward, a plain repeat).
        int fromPass = isBefore ? reps - 1 - r : r;
        boolean reverse = pingPong && fromPass % 2 == 0;
        long revLen = outMs - inMs;
        long span = Math.max(0L, Math.min(spanMs, revLen));
        if (reverse) {
            // After the clip a backward leg starts at its end (reversed copy from 0); before it, a backward leg ends at
            // its start (the reversed copy's end).
            return isBefore ? new PingPongLeg(true, revLen - span, revLen) : new PingPongLeg(true, 0L, span);
        }
        // A forward leg after the clip starts at its start; before the clip (ping-pong) it ends at its end.
        return isBefore && pingPong ? new PingPongLeg(false, outMs - span, outMs) : new PingPongLeg(false, inMs, inMs + span);
    }
}

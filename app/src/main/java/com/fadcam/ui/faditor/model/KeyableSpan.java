package com.fadcam.ui.faditor.model;

/**
 * THE single authority for "may a keyframe be dropped here at all" — is the playhead over the
 * object's own span?
 *
 * <p><b>Why this exists.</b> Measured on device 2026-08-05: tapping the Pos X diamond with the
 * playhead at 4973ms wrote a key into a PiP whose span is [5501, 13629] — 528ms BEFORE the clip
 * exists. The key was real (it round-tripped to {@code project.json}) and it was useless: the
 * evaluator clamps flat outside the span, so the user had authored a value that can never be
 * seen. The user's rule (2026-08-05): <i>"dropping a keyframe should only happen when the
 * playhead is actually over the object's span. If the playhead is outside it, do not drop —
 * there is nowhere to put it."</i></p>
 *
 * <p><b>Both ends are CLOSED, not half-open.</b> The last frame of an object is a pose people
 * key constantly ("end here"), and a half-open end would refuse exactly that tap while accepting
 * the one a millisecond earlier — a rule the user cannot see is a rule that reads as a bug.</p>
 *
 * <p><b>The slop is the same tolerance the on-key test uses.</b> The playhead lands on arbitrary
 * milliseconds while a span boundary is an exact number; without slop, "park the playhead at the
 * very start of the clip" fails about as often as it succeeds, and the failure looks random.</p>
 *
 * <p>Android-free on purpose so the JVM harness can reach it, and shared rather than inlined for
 * the {@code ChromaKey}/{@code VolumeEnvelope} reason: the UI's refusal and any future writer's
 * refusal must be the SAME predicate, or a control will refuse a tap that another path accepts.
 * </p>
 */
public final class KeyableSpan {

    private KeyableSpan() {}

    /**
     * The tolerance a playhead is allowed to miss a boundary by, in ms — ~1 frame at 24fps.
     * Matches the slop the on-key / delete-key tests already use, deliberately: a tap that is
     * "on the first key" must also be "inside the span", or the diamond would show a key it
     * refuses to replace.
     */
    public static final long DEFAULT_SLOP_MS = 40L;

    /**
     * Is {@code playheadMs} over the span {@code [startMs, endMs]} (inclusive), allowing
     * {@code slopMs} either side?
     *
     * <p>An empty or inverted span ({@code endMs <= startMs}) returns {@code false}: an object
     * with no duration has no time to hold a value at, and answering "yes" there would put the
     * caller straight back into the bug this class exists to stop. Negative slop is treated as
     * zero rather than shrinking the span, so a bad argument can never make a legal tap illegal.
     */
    public static boolean contains(long startMs, long endMs, long playheadMs, long slopMs) {
        if (endMs <= startMs) return false;
        long slop = Math.max(0L, slopMs);
        return playheadMs >= startMs - slop && playheadMs <= endMs + slop;
    }

    /** {@link #contains(long, long, long, long)} with {@link #DEFAULT_SLOP_MS}. */
    public static boolean contains(long startMs, long endMs, long playheadMs) {
        return contains(startMs, endMs, playheadMs, DEFAULT_SLOP_MS);
    }
}

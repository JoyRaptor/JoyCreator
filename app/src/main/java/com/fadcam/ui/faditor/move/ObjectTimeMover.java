package com.fadcam.ui.faditor.move;

import java.util.List;

/**
 * Pure, Android-free engine for the object time-scrubber's collision + push-through
 * relayering (see {@code tasks/SPEC_OBJECT_TIME_SCRUBBER.md} §2).
 *
 * <p>Given where the user WANTS the selected object's start (driven by the variable-speed
 * shuttle or a jump-to-time), plus the occupancy of the object's ORIGIN (home) lane and the
 * lane immediately ABOVE it, {@link #resolve} returns the resolved start (possibly clamped)
 * and which lane the object lands on. It is <b>stateless per call</b>: the scrub-back-and-forth
 * and the "return to the origin lane past the obstacle" behaviours emerge naturally from
 * re-resolving as {@code desiredStartMs} changes — the caller does not track pass state.</p>
 *
 * <p><b>Load-bearing invariant that keeps the lock math simple:</b> a COMMITTED object position
 * never overlaps another object on its lane, so every same-lane obstacle is entirely to the
 * left ({@code endMs <= currentStartMs}) or entirely to the right
 * ({@code startMs >= currentStartMs + durMs}) of the object. Callers must only ever commit
 * non-overlapping positions (the engine only returns such positions), so the invariant holds
 * across a scrub gesture.</p>
 *
 * <p>Rules, matching the spec and the user's brief:</p>
 * <ol>
 *   <li>If the desired span is free on the ORIGIN lane, stay on ORIGIN (this is also the
 *       return-to-origin case once the object has slid past an obstacle).</li>
 *   <li>Otherwise the object <em>locks</em> flush against the obstacle it ran into, in the
 *       travel direction.</li>
 *   <li>If push-through is OFF, that lock is final — the object never leaves its lane.</li>
 *   <li>If push-through is ON and the user keeps driving PAST the lock by at least
 *       {@code breakthroughMs}, the object breaks through and relayers <em>one</em> step:
 *       to the lane ABOVE if that lane exists and is open across the span, else to a NEW lane.
 *       There is no chain — never a staircase up through multiple lanes.</li>
 * </ol>
 */
public final class ObjectTimeMover {

    private ObjectTimeMover() {}

    /** A half-open occupied interval {@code [startMs, endMs)} belonging to some OTHER object. */
    public static final class Span {
        public final long startMs;
        public final long endMs;
        public Span(long startMs, long endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    /** Which lane the object lands on relative to its home lane. */
    public enum Lane { ORIGIN, ABOVE, NEW }

    public static final class Result {
        /** Resolved start (ms), always {@code >= 0}. */
        public final long startMs;
        /** Which lane the object lands on. */
        public final Lane lane;
        public Result(long startMs, Lane lane) {
            this.startMs = startMs;
            this.lane = lane;
        }
        @Override public String toString() { return lane + "@" + startMs; }
    }

    /**
     * Resolve where the object actually lands given where the scrubber wants it.
     *
     * @param durMs           object duration on the timeline (ms); must be {@code > 0}
     * @param currentStartMs  the object's current committed start (ms), used only to know the
     *                        travel direction when locking; assumed non-overlapping on ORIGIN
     * @param desiredStartMs  where the scrubber wants the start (ms); clamped to {@code >= 0}
     * @param pushThrough     the toggle: {@code true} = may break through and relayer;
     *                        {@code false} = always lock flush, never leave the lane
     * @param breakthroughMs  how far PAST the lock the user must drive before a relayer fires
     *                        (feel tunable; ignored when {@code pushThrough} is false)
     * @param originOthers    OTHER objects' spans on the ORIGIN (home) lane, any order (never null)
     * @param aboveOthers     OTHER objects' spans on the lane immediately ABOVE, or {@code null}
     *                        if no such lane exists (topmost) — then a blocked push goes to NEW
     * @return the resolved start + lane
     */
    public static Result resolve(long durMs, long currentStartMs, long desiredStartMs,
                                 boolean pushThrough, long breakthroughMs,
                                 List<Span> originOthers, List<Span> aboveOthers) {
        long s = Math.max(0, desiredStartMs);

        // (1) Origin lane free at the desired position → stay home (also return-to-origin).
        if (!overlaps(s, s + durMs, originOthers)) {
            return new Result(s, Lane.ORIGIN);
        }

        // (2) Blocked on origin → compute the flush-lock position in the travel direction.
        long locked = lockFlush(durMs, currentStartMs, s, originOthers);

        // (3) Push-through off → the lock is final.
        if (!pushThrough) {
            return new Result(locked, Lane.ORIGIN);
        }

        // (4) Push-through on → relayer only once the user drives PAST the lock by the
        //     breakthrough distance. Below that, it still just locks.
        long overshoot = Math.abs(s - locked);
        if (overshoot < Math.max(0, breakthroughMs)) {
            return new Result(locked, Lane.ORIGIN);
        }

        // Break through: one step up — the lane above if open across the span, else a new lane.
        if (aboveOthers != null && !overlaps(s, s + durMs, aboveOthers)) {
            return new Result(s, Lane.ABOVE);
        }
        return new Result(s, Lane.NEW);
    }

    /** Half-open overlap test: {@code [start,end)} vs any span in {@code others}. */
    private static boolean overlaps(long start, long end, List<Span> others) {
        if (others == null) return false;
        for (Span o : others) {
            if (start < o.endMs && o.startMs < end) return true;
        }
        return false;
    }

    /**
     * Slide the object as far as it can go toward {@code desiredStartMs} without overlapping an
     * origin-lane obstacle, then stop flush against the first one it meets. Relies on the
     * committed-position invariant: every obstacle is wholly left or wholly right of the object
     * at {@code currentStartMs}.
     */
    private static long lockFlush(long durMs, long currentStartMs, long desiredStartMs,
                                  List<Span> originOthers) {
        boolean movingRight = desiredStartMs >= currentStartMs;
        if (movingRight) {
            // Nearest obstacle ahead: smallest startMs that is >= current right edge.
            long wall = Long.MAX_VALUE;
            long rightEdge = currentStartMs + durMs;
            for (Span o : originOthers) {
                if (o.startMs >= rightEdge && o.startMs < wall) wall = o.startMs;
            }
            if (wall == Long.MAX_VALUE) return Math.max(0, desiredStartMs); // nothing ahead
            long allowed = wall - durMs;                 // right edge sits flush on the wall
            long best = Math.min(desiredStartMs, allowed);
            best = Math.max(currentStartMs, best);       // never slide backward while going right
            return Math.max(0, best);
        } else {
            // Nearest obstacle behind: largest endMs that is <= current left edge.
            long wall = Long.MIN_VALUE;
            for (Span o : originOthers) {
                if (o.endMs <= currentStartMs && o.endMs > wall) wall = o.endMs;
            }
            if (wall == Long.MIN_VALUE) return Math.max(0, desiredStartMs); // nothing behind
            long allowed = wall;                         // left edge sits flush on the wall
            long best = Math.max(desiredStartMs, allowed);
            best = Math.min(currentStartMs, best);       // never slide forward while going left
            return Math.max(0, best);
        }
    }
}

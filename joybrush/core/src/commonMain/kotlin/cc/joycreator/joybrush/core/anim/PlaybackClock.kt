package cc.joycreator.joybrush.core.anim

import cc.joycreator.joybrush.core.doc.AnimOps
import cc.joycreator.joybrush.core.doc.Board

/** How a board plays once it reaches the end of its range. */
enum class PlayMode { LOOP, PING_PONG, ONCE }

/**
 * Playback as a pure function of elapsed time — never a counter that adds frame lengths, which is
 * the one thing that makes frames slowly slide away from the sound (JB-3.05a, blueprint §7).
 *
 * Every answer here is computed from the elapsed time alone, so the same inputs give the same frame
 * an hour in as they did a millisecond in, and nothing in this file reads a clock.
 *
 * IMMUTABLE, and deliberately: a player that mutates its clock is a player that can drift. Change the
 * board, the mode, the range or the speed and you build another one, which restarts playback at 0.
 *
 * @param firstFrame, lastFrame the play range, as frame INDICES into [board].frames, inclusive.
 *   Clamped into the board, and swapped if given the wrong way round.
 * @param speed 1 = the board's own fps, 0.5 = half speed. Clamped to 0.1..4; a non-finite speed
 *   plays at 1.
 */
class PlaybackClock(
    board: Board,
    val mode: PlayMode = PlayMode.LOOP,
    firstFrame: Int = 0,
    lastFrame: Int = board.frames.size - 1,
    speed: Float = 1f,
) {

    private val first: Int
    private val last: Int
    private val count: Int
    private val rate: Double
    private val rangeStarts: List<Double>
    private val rangeStartMs: Double
    private val backSpanMs: Double
    private val cycleMs: Double

    /** Length of one pass through the range at speed 1, ms. */
    val rangeMs: Double

    init {
        val frames = board.frames.size
        // A board with no frames is a legal document (a person made the board and stopped) and an
        // illegal thing to play. Refused rather than answered with a 0-length range, which would make
        // every later question modulo zero.
        require(frames > 0) {
            "board \"${board.id}\" has no frames, so there is nothing to play"
        }
        val lo = minOf(firstFrame, lastFrame).coerceIn(0, frames - 1)
        val hi = maxOf(firstFrame, lastFrame).coerceIn(0, frames - 1)
        first = lo
        last = hi
        count = hi - lo + 1

        rate = if (speed.isFinite()) speed.coerceIn(MIN_SPEED, MAX_SPEED).toDouble() else 1.0

        // Every frame length in this class comes from AnimOps, which is where the 1..60 fps rule
        // lives and which is what a frame's length is DEFINED to be. Re-deriving holdFrames * 1000 /
        // fps here would be a second definition that could one day disagree with the first.
        //
        // `frameStartsMs` returns one start per frame and NO trailing end, so the end of the range is
        // the next start when there is one and the board's own `totalDurationMs` when the range runs
        // to the last frame. That is why the range's starts are assembled with the end appended
        // rather than read straight out: the last start then IS the range length, exactly, and the
        // index that finds a frame is the same one that finds the end.
        val boardStarts = AnimOps.frameStartsMs(board)
        rangeStartMs = boardStarts[lo]
        val rangeEndMs = if (hi < frames - 1) boardStarts[hi + 1] else AnimOps.totalDurationMs(board)
        val starts = ArrayList<Double>(count + 1)
        for (k in lo..hi) {
            starts.add(boardStarts[k] - rangeStartMs)
        }
        starts.add(rangeEndMs - rangeStartMs)
        rangeStarts = starts
        rangeMs = starts[count]

        // The backward leg plays the range's interior in reverse: it starts at the LAST frame's start
        // — so the seam still shows that frame, exactly as the ruling says — and walks back to the
        // SECOND frame's start. On four frames that is D (for one instant, the seam) then C then B,
        // which is the spec's `A B C D C B`. Reaching only as far back as the second-to-last frame's
        // start drops a whole frame and gives `A B C D B`.
        if (count >= 3) {
            backSpanMs = rangeStarts[count - 1] - rangeStarts[1]
            cycleMs = rangeMs + backSpanMs
        } else {
            backSpanMs = 0.0
            cycleMs = rangeMs
        }
    }

    /**
     * The period the range repeats on: [cycleMs] under PING_PONG, and [rangeMs] under the other two.
     *
     * They are the same number for LOOP and ONCE only because their ranges are too short to ping-pong;
     * for a four-frame range the cycle is a range and a half. Using [cycleMs] for LOOP would wrap the
     * picture once per ping-pong cycle, which is a different animation from the one that was asked for
     * and looks correct for the first three frames.
     */
    private val periodMs: Double get() = if (mode == PlayMode.PING_PONG) cycleMs else rangeMs

    /**
     * The frame index to show [elapsedMs] after play was pressed.
     *
     * [elapsedMs] is WALL time since play: it is multiplied by the speed to get board time, so a
     * half-speed board reaches a frame boundary at twice the wall time.
     */
    fun frameIndexAt(elapsedMs: Double): Int = first + slotAt(positionOf(elapsedMs))

    /** ONCE: true once the last frame's time has passed. LOOP and PING_PONG never finish. */
    fun isFinished(elapsedMs: Double): Boolean =
        mode == PlayMode.ONCE && boardTimeOf(elapsedMs) >= rangeMs

    /**
     * Where the sound should be, in ms of the BOARD's own timeline (0 = board frame 0), or `null`
     * when it must be silent.
     *
     * The sound follows the PICTURE, which is the entire point: under PING_PONG the forward leg's
     * position is `rangeStartMs + cyclePos` and NOT `rangeStartMs + (elapsed * speed mod rangeMs)`,
     * because those agree only inside the first cycle and would drift apart after it. The backward
     * leg is silent, because sound played backwards is never wanted.
     */
    fun audioPositionMs(elapsedMs: Double): Double? {
        if (isFinished(elapsedMs)) return null
        val cyclePos = boardTimeOf(elapsedMs) % periodMs
        if (mode == PlayMode.PING_PONG && cyclePos >= rangeMs) return null
        return rangeStartMs + cyclePos % rangeMs
    }

    /**
     * When the next frame change happens after [elapsedMs], in wall ms — the player sleeps until
     * then. [Double.POSITIVE_INFINITY] when the frame will not change again: a one-frame range, or
     * an ONCE clock that has finished.
     *
     * AT A BACKWARD BOUNDARY THIS IS THE INFIMUM, not a minimum, and that is the whole subtlety.
     * Ask from inside a falling edge and the answer is the boundary ahead of you. Ask *at* it and
     * you are already in the frame that is going away, so the frame differs at every instant after
     * you and the set of change times has no smallest member. The boundary itself is then its
     * infimum, and the infimum is what a player must sleep until: returning the boundary plus an
     * epsilon would let a player that wakes on it draw the OUTGOING frame and sleep again straight
     * past the incoming one, skipping a frame of a ping-pong every time it turned.
     *
     * "A backward boundary" is every falling edge of the leg, not only the wrap seam — see
     * [nextBackwardBoundaryAtOrAfter], where the seam is simply the first of them.
     *
     * **NO EPSILON IS APPLIED TO THE BOUNDARY, AND NONE SHOULD BE.** The boundary is taken from the
     * same [rangeStarts] the frame lookup uses and compared exactly, and the answer is rebuilt as
     * `elapsed + (boundary - position) / speed`. Asked exactly at a boundary that difference is
     * exactly zero, so the returned wall time is the elapsed that was passed in, bit for bit — there
     * is no `seam - 1e-12` to snap in the first place. And where the caller's own arithmetic lands a
     * ulp off the boundary (only reachable at a speed that is not a power of two, since dividing and
     * re-multiplying by one is exact), the honest answer is the boundary it is genuinely inside or
     * genuinely past: a tolerance here would turn a correct boundary into a slightly wrong one, and
     * it would do it silently, in the one function whose entire claim is that it never drifts.
     */
    fun nextChangeMs(elapsedMs: Double): Double {
        // One frame cannot change. The contract asks for "the smallest wall time at which
        // frameIndexAt changes", and for this range that set is empty rather than merely far away.
        if (count == 1) return Double.POSITIVE_INFINITY
        val clean = cleanElapsed(elapsedMs)
        val board = clean * rate
        if (mode == PlayMode.ONCE && board >= rangeMs) return Double.POSITIVE_INFINITY
        val cyclePos = board % periodMs
        // ONE RULE, two comparisons. A FORWARD boundary is a frame START, so the incoming frame is
        // already up at it and the next change is the start after it — strictly greater. A BACKWARD
        // boundary is a frame END in cycle time, so the outgoing frame is the one up at it and the
        // change is immediately after — at or after, which is the infimum above.
        val boundary = if (cyclePos < rangeMs) {
            nextForwardBoundaryAfter(cyclePos)
        } else {
            nextBackwardBoundaryAtOrAfter(cyclePos)
        }
        return clean + (boundary - cyclePos) / rate
    }

    /**
     * The first frame start strictly after [position] inside the range, or [rangeMs] itself, which is
     * the range's end and the start of whatever comes next in every mode.
     */
    private fun nextForwardBoundaryAfter(position: Double): Double {
        for (k in 1..count) {
            if (rangeStarts[k] > position) return rangeStarts[k]
        }
        // Only reachable if a position ever reached the range's end, where this is the right answer
        // anyway. `rangeStarts[count]` IS rangeMs, so the loop above already returns that value for
        // every position the caller can actually hand it.
        return rangeMs
    }

    /**
     * The first boundary of the backward leg at or after [cyclePos].
     *
     * The leg replays the range's interior in reverse, so its boundaries are the interior starts
     * measured from the seam: k = [count - 1] is the seam itself and k = 1 is the end of the cycle,
     * because the leg never reaches back past the second frame's start. Walking k downwards
     * therefore visits them in increasing cycle time and the first one at or after [cyclePos] is the
     * answer — which is what makes the interior falling edges (`C` to `B` on four frames) come out
     * right instead of only the seam.
     */
    private fun nextBackwardBoundaryAtOrAfter(cyclePos: Double): Double {
        val lastStart = rangeStarts[count - 1]
        for (k in count - 1 downTo 1) {
            val at = rangeMs + (lastStart - rangeStarts[k])
            if (at >= cyclePos) return at
        }
        // k = 1 is cycleMs, and cyclePos < cycleMs, so the loop returns. The value is the right
        // answer for a position past every boundary in the leg regardless.
        return cycleMs
    }

    /**
     * The position within the range's own timeline, always in `[0, rangeMs]`.
     *
     * ONCE clamps to just inside the end rather than past it, so the last frame stays up forever
     * instead of wrapping or disappearing. The epsilon is far below the shortest possible frame
     * (1000/60 = 16.7 ms) and far above the ulp of any range a board can hold, so it can never move
     * a boundary — it can only make "the end" a number strictly inside the last frame.
     */
    private fun positionOf(elapsedMs: Double): Double {
        val board = boardTimeOf(elapsedMs)
        if (mode == PlayMode.ONCE) {
            return if (board >= rangeMs) rangeMs - END_EPSILON_MS else board
        }
        val cyclePos = board % periodMs
        if (cyclePos < rangeMs) return cyclePos
        return rangeStarts[slotOnTheBackwardLeg(cyclePos)]
    }

    /**
     * The slot to show while the position is FALLING, read off the falling edges themselves.
     *
     * The obvious spelling of this — turn [cyclePos] back into a forward position and look that up —
     * cannot be done exactly, and the error is not cosmetic. `rangeMs + (last - rangeStarts[k])` is
     * a rounded sum, so `last - (cyclePos - rangeMs)` comes back a hair either side of
     * `rangeStarts[k]` even when [cyclePos] is EXACTLY that edge, and an ulp out at an edge is the
     * wrong frame on the wrong side of the only comparison in this class that decides anything: the
     * seam would show the incoming frame instead of the outgoing one, and `nextChangeMs` — which
     * compares the very same edge — would then hand out a boundary at which the frame had already
     * changed. So the edges are compared where they are, and the slot follows from the comparison.
     *
     * The edges are `u[k] = rangeMs + (last - rangeStarts[k])`, strictly DECREASING in `k` from the
     * turn at `k = 1` to the seam at `k = count - 1`, which is `rangeMs` itself. The first one at or
     * below [cyclePos] is the edge being stood on: frame `k` AT it, frame `k - 1` immediately after.
     */
    private fun slotOnTheBackwardLeg(cyclePos: Double): Int {
        val lastStart = rangeStarts[count - 1]
        for (k in 1 until count) {
            val edge = rangeMs + (lastStart - rangeStarts[k])
            if (edge <= cyclePos) return if (edge == cyclePos) k else k - 1
        }
        // Only reachable below the seam, which the caller has already excluded. The last forward frame
        // is the right answer there in any case: it is what the leg is walking away from.
        return count - 1
    }

    /** The frame slot a position falls in: the largest [k] with `rangeStarts[k] <= position`. */
    private fun slotAt(position: Double): Int {
        var slot = 0
        for (k in 1 until count) {
            if (rangeStarts[k] > position) break
            slot = k
        }
        return slot
    }

    private fun boardTimeOf(elapsedMs: Double): Double = cleanElapsed(elapsedMs) * rate

    /**
     * Elapsed time, with the values that cannot be placed on a timeline put at zero.
     *
     * A negative elapsed time is a player that started before play, and NaN is a caller that
     * multiplied by something it did not have — both mean "the beginning". Positive infinity is not
     * listed in the contract and is answered the same way, deliberately: it is the one input that
     * would otherwise poison everything downstream, because `Infinity % rangeMs` is NaN, and a NaN
     * position silently reads as "before the first frame" in every comparison rather than as the
     * broken number it is.
     */
    private fun cleanElapsed(elapsedMs: Double): Double =
        if (!elapsedMs.isFinite() || elapsedMs < 0.0) 0.0 else elapsedMs

    private companion object {
        const val MIN_SPEED = 0.1f
        const val MAX_SPEED = 4f

        /** How far inside the end an ONCE clock parks, in ms. See [positionOf]. */
        const val END_EPSILON_MS = 1.0e-6
    }
}

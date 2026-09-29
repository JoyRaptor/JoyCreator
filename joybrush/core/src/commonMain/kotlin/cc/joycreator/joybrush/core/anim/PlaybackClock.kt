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
        // drops a whole frame and gives `A B C D B`.
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
     * AT THE PING-PONG SEAM THIS IS THE INFIMUM, not a minimum. At exactly `rangeMs` the index is
     * still the last forward frame, and it differs immediately afterwards, so the change has no
     * smallest time. `rangeMs` is where the player must wake, and it is what this returns.
     */
    fun nextChangeMs(elapsedMs: Double): Double {
        // One frame cannot change. The contract asks for "the smallest wall time at which
        // frameIndexAt changes", and for this range that set is empty rather than merely far away.
        if (count == 1) return Double.POSITIVE_INFINITY
        val clean = cleanElapsed(elapsedMs)
        val board = clean * rate
        if (mode == PlayMode.ONCE && board >= rangeMs) return Double.POSITIVE_INFINITY
        val cyclePos = board % periodMs
        val boundary = if (cyclePos < rangeMs) {
            // Forward leg, or either non-ping-pong mode: the next start in the range. For the last
            // frame that is `rangeMs` itself, which is the wrap — and under PING_PONG it is also the
            // seam, where the change lands immediately afterwards.
            rangeStarts[slotAt(cyclePos) + 1]
        } else {
            nextBoundaryOnTheBackwardLeg(cyclePos)
        }
        return clean + (boundary - cyclePos) / rate
    }

    /** The next frame boundary, as a position inside the current cycle, while on the backward leg. */
    private fun nextBoundaryOnTheBackwardLeg(cyclePos: Double): Double {
        val backPos = rangeStarts[count - 1] - (cyclePos - rangeMs)
        // The position is falling, so the next change is the last start strictly below it, or the end
        // of the cycle when there is none inside the backward leg.
        var k = 0
        // The interior starts are 0 .. count - 2 INCLUSIVE. Stopping one short of that skips the
        // C-to-B change on a four-frame range and reports the turn instead — a player that then
        // sleeps through a whole frame of a ping-pong.
        for (i in 0 until count - 1) {
            if (rangeStarts[i] < backPos) k = i else break
        }
        if (k == 0) return cycleMs
        return rangeMs + (rangeStarts[count - 1] - rangeStarts[k])
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
        return rangeStarts[count - 1] - (cyclePos - rangeMs)
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

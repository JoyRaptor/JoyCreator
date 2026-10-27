package cc.joycreator.joybrush.core.anim

import cc.joycreator.joybrush.core.doc.AnimOps
import cc.joycreator.joybrush.core.doc.Board

/** How a board plays once it reaches the end of its range. */
enum class PlayMode { LOOP, PING_PONG, ONCE }

/** One integer tween exposure and the wall time at which its next exposure tick begins. */
data class PlaybackExposureSample(
    /** Null after a BLANK clip reaches its end. */
    val sample: TweenFrameSample?,
    /** Absolute wall time for the next tick exposure, or infinity when this sample is settled. */
    val nextWakeupMs: Double,
    val isBlank: Boolean,
    /** True when [sample] is on the interior PING_PONG return leg. */
    val isReverse: Boolean,
    /** True when the selected range wraps after its final frame. */
    val isLoop: Boolean,
)

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
    private val fps: Double
    private val rangeStarts: List<Double>
    private val rangeTickStarts: List<Long>
    private val rangeTicks: Long
    private val currentFrames = board.frames
    private val rangeStartMs: Double
    private val backSpanMs: Double
    private val cycleMs: Double
    private val backTicks: Long
    private val cycleTicks: Long

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
        fps = board.fps.toDouble()

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
        val tickStarts = ArrayList<Long>(count + 1)
        var ticks = 0L
        for (k in lo..hi) {
            starts.add(boardStarts[k] - rangeStartMs)
            tickStarts.add(ticks)
            ticks += board.frames[k].holdFrames.toLong()
        }
        starts.add(rangeEndMs - rangeStartMs)
        tickStarts.add(ticks)
        rangeStarts = starts
        rangeTickStarts = tickStarts
        rangeTicks = ticks
        rangeMs = starts[count]

        // The backward leg plays the range's interior in reverse: it starts at the LAST frame's start
        // — so the seam still shows that frame, exactly as the ruling says — and walks back to the
        // SECOND frame's start. On four frames that is D (for one instant, the seam) then C then B,
        // which is the spec's `A B C D C B`. Reaching only as far back as the second-to-last frame's
        // start drops a whole frame and gives `A B C D B`.
        if (count >= 3) {
            backSpanMs = rangeStarts[count - 1] - rangeStarts[1]
            backTicks = rangeTickStarts[count - 1] - rangeTickStarts[1]
            cycleMs = rangeMs + backSpanMs
            cycleTicks = rangeTicks + backTicks
        } else {
            backSpanMs = 0.0
            backTicks = 0L
            cycleMs = rangeMs
            cycleTicks = rangeTicks
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

    /**
     * Samples one integer exposure on this same clock. Unlike [frameIndexAt], this API is tick
     * quantized: each exposure is selected by cumulative integer tick boundaries, then converted to
     * ms as `ticks * 1000 / fps`. This keeps tween fractions stable between polls and avoids the
     * millisecond-position rounding that the legacy frame clock intentionally preserves.
     *
     * On a PING_PONG return leg, the forward source frames are replayed in reverse order, excluding
     * the two endpoints: for `A B C D`, one integer cycle is `A B ... D C ... B`, then it wraps to
     * `A`. A reverse
     * sample keeps its normal forward successor and counts its within-hold offset down from `n-1`
     * through `0`; it does not invent a reverse tween target. A range's last source never targets a
     * frame outside that range, except LOOP, where it targets the range's first frame.
     *
     * `nextWakeupMs` is the next tick boundary at which this integer sample may change. ONCE
     * settles on the last occupied tick. Saved clip end modes are a separate integration slice.
     */
    fun exposureAt(elapsedMs: Double): PlaybackExposureSample {
        val elapsed = cleanElapsed(elapsedMs)
        val finiteEnd = mode == PlayMode.ONCE
        val rawBoardTime = elapsed * rate
        // A cyclic clock's unplaceable infinite position restarts at zero, as the legacy modulo
        // path does. A finite-end clock treats overflow as having passed its end.
        val boardTime = when {
            rawBoardTime.isFinite() -> rawBoardTime
            finiteEnd && rawBoardTime > 0.0 -> Double.POSITIVE_INFINITY
            else -> 0.0
        }
        val absoluteTick = tickAtOrBefore(boardTime)
        val slot: Int
        val offset: Long
        val reverse: Boolean
        when (mode) {
            PlayMode.PING_PONG -> {
                if (count < 3) {
                    val phase = absoluteTick % rangeTicks
                    slot = slotForTick(phase)
                    offset = phase - rangeTickStarts[slot]
                    reverse = false
                } else {
                    val phase = absoluteTick % cycleTicks
                    if (phase < rangeTicks) {
                        slot = slotForTick(phase)
                        offset = phase - rangeTickStarts[slot]
                        reverse = false
                    } else {
                        var remaining = phase - rangeTicks
                        var found = 1
                        for (k in count - 2 downTo 1) {
                            val hold = rangeTickStarts[k + 1] - rangeTickStarts[k]
                            if (remaining < hold) {
                                found = k
                                break
                            }
                            remaining -= hold
                        }
                        slot = found
                        val hold = rangeTickStarts[slot + 1] - rangeTickStarts[slot]
                        offset = hold - 1L - remaining
                        reverse = true
                    }
                }
            }
            PlayMode.LOOP -> {
                val phase = absoluteTick % rangeTicks
                slot = slotForTick(phase)
                offset = phase - rangeTickStarts[slot]
                reverse = false
            }
            else -> {
                val phase = absoluteTick.coerceAtMost(rangeTicks - 1L)
                slot = slotForTick(phase)
                offset = phase - rangeTickStarts[slot]
                reverse = false
            }
        }

        val index = first + slot
        val frame = currentFrames[index]
        val hold = frame.holdFrames.toLong()
        val targetIndex = when {
            !frame.tween || hold <= 1L -> null
            index < last -> index + 1
            mode == PlayMode.LOOP -> first
            else -> null
        }
        val sample = TweenFrameSample(
            frameId = frame.id,
            frameIndex = index,
            nextFrameId = targetIndex?.let(currentFrames::get)?.id,
            nextFrameIndex = targetIndex,
            tickOffset = offset,
            fraction = if (targetIndex == null) 0.0 else offset.toDouble() / hold,
            rangeFirstFrameIndex = first,
            rangeLastFrameIndex = if (last == currentFrames.lastIndex) null else last,
            loops = mode == PlayMode.LOOP,
        )

        val nextWakeup = when {
            finiteEnd && absoluteTick >= rangeTicks - 1L -> Double.POSITIVE_INFINITY
            else -> exposureWakeupAt(absoluteTick, elapsed, boardTime)
        }
        return PlaybackExposureSample(sample, nextWakeup, false, reverse, mode == PlayMode.LOOP)
    }

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

    /** The selected frame slot containing this integer tick, using cumulative holds. */
    private fun slotForTick(tick: Long): Int {
        var slot = 0
        for (k in 1 until count) {
            if (rangeTickStarts[k] > tick) break
            slot = k
        }
        return slot
    }

    private fun exposureWakeupAt(absoluteTick: Long, elapsed: Double, boardTime: Double): Double {
        if (absoluteTick >= Long.MAX_VALUE - 1L) return Double.POSITIVE_INFINITY
        val boundaryMs = (absoluteTick + 1L).toDouble() * 1000.0 / fps
        val next = elapsed + (boundaryMs - boardTime) / rate
        // At enormous elapsed values Double may no longer distinguish adjacent ticks. Never
        // return a past/current wakeup that would spin the UI scheduler without advancing.
        return if (next > elapsed) next else Double.POSITIVE_INFINITY
    }

    /** Greatest integer board tick whose cumulative boundary is no later than [timeMs]. */
    private fun tickAtOrBefore(timeMs: Double): Long {
        if (timeMs <= 0.0) return 0L
        val maximum = Long.MAX_VALUE - 1L
        if (tickBoundaryMs(maximum) <= timeMs) return maximum
        var low = 0L // tick zero's boundary is at or before every positive time
        var high = maximum // known to be after timeMs
        while (low + 1L < high) {
            val middle = low + (high - low) / 2L
            if (tickBoundaryMs(middle) <= timeMs) low = middle else high = middle
        }
        return low
    }

    private fun tickBoundaryMs(tick: Long): Double = tick.toDouble() * 1000.0 / fps

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

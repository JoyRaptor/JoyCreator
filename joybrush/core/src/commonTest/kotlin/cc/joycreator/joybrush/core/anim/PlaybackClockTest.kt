package cc.joycreator.joybrush.core.anim

import cc.joycreator.joybrush.core.doc.AnimOps
import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.Frame
import cc.joycreator.joybrush.core.doc.RectPx
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Playback clock, checked against boundaries worked out from the board rather than from the clock.
 *
 * THE TWO THINGS TO KNOW BEFORE WRITING A TEST HERE.
 *
 * 1. **Every boundary is derived by indexing `AnimOps`, never by accumulating `hold * 1000 / fps` a
 *    second time.** `2 * (1000.0 / 12.0)` is not bit-identical to `2000.0 / 12.0`, and a boundary one
 *    ulp out lands on the wrong side of the `start <= position < start + duration` comparison, which
 *    makes the frame index wrong by one and the failure look like an off-by-one in the arithmetic.
 * 2. **`frameStartsMs` returns one start per frame and no trailing end.** A test that indexes
 *    `starts[frameCount]` — which is the obvious thing to write, and which this suite did — gets an
 *    IndexOutOfBounds rather than a wrong answer, which is the good case. The end of a board is its
 *    `totalDurationMs`, and [edge] is the accessor that knows that.
 *
 * The values that CAN be pinned independently are pinned: 3000.0/12.0 is exactly 250.0 and
 * 1000.0/12.0 is the first boundary, both asserted against the spec's own arithmetic.
 */
class PlaybackClockTest {

    // ── the board: 12 fps, holds 1, 2, 1, 3 ────────────────────────────────────

    private fun boardOf(vararg holds: Int, fps: Float = 12f, id: String = "b"): Board = Board(
        id = id,
        name = "Anim",
        kind = BoardKind.ANIMATION,
        rect = RectPx(0, 0, 256, 256),
        fps = fps,
        frames = holds.mapIndexed { i, hold -> Frame("f$i", holdFrames = hold) },
    )

    private val board = boardOf(1, 2, 1, 3)

    /** Boundary [k] of [board]: a frame start, or the board's end when [k] is one past the last. */
    private fun edge(of: Board, k: Int): Double {
        val s = AnimOps.frameStartsMs(of)
        return if (k < s.size) s[k] else AnimOps.totalDurationMs(of)
    }

    private fun b(k: Int): Double = edge(board, k)

    private fun clock(
        mode: PlayMode = PlayMode.LOOP,
        first: Int = 0,
        last: Int = 3,
        speed: Float = 1f,
    ) = PlaybackClock(board, mode = mode, firstFrame = first, lastFrame = last, speed = speed)

    // ── 1. LOOP ────────────────────────────────────────────────────────────────

    /**
     * LOOP steps 0, 1, 1, 2, 3, 3, 3 and wraps at the range length — the spec's test 1, with every
     * boundary taken from the board and every frame sampled just inside its right-hand edge as well
     * as exactly on its left, because a boundary only ever tested from one side is a boundary that
     * can be off by one in the direction nobody looked.
     */
    @Test
    fun loopStepsThroughEveryFrameAndWraps() {
        val c = clock()
        assertEquals(0, c.frameIndexAt(0.0), "at the start")
        assertEquals(0, c.frameIndexAt(b(1) - 0.001), "frame 0 runs to its own end")

        assertEquals(1, c.frameIndexAt(b(1)), "frame 1 begins on its boundary")
        assertEquals(1, c.frameIndexAt(b(2) - 0.001), "and runs to the next")

        assertEquals(2, c.frameIndexAt(b(2)), "frame 2 begins on its boundary")
        assertEquals(2, c.frameIndexAt(b(3) - 0.001))

        assertEquals(3, c.frameIndexAt(b(3)), "frame 3 begins on its boundary")
        assertEquals(3, c.frameIndexAt(c.rangeMs - 0.001), "and holds for its whole length")

        assertEquals(0, c.frameIndexAt(c.rangeMs), "and the range wraps at its length")
        assertEquals(1, c.frameIndexAt(c.rangeMs + b(1)), "one more frame in")
    }

    /** The range length is the board's own length, and the two are the same number. */
    @Test
    fun theRangeLengthIsTheBoardsOwn() {
        assertEquals(b(4) - b(0), clock().rangeMs)
        assertEquals(AnimOps.totalDurationMs(board), clock().rangeMs)
        // And the two absolute boundaries the spec names, against arithmetic rather than the clock.
        assertEquals(1000.0 / 12.0, b(1), "one tick of a 12 fps board")
        assertEquals(250.0, 3000.0 / 12.0, "three ticks is exactly a quarter second")
        assertEquals(250.0, b(2), "which is where frame 2 begins")
    }

    // ── 2. a sub-range ─────────────────────────────────────────────────────────

    /**
     * Frames 1..2 only, and the sound starts at frame 1's own start in the BOARD's timeline — not at
     * zero, because zero is frame 0 and the sound has to line up with what is on screen.
     */
    @Test
    fun aSubRangePlaysOnlyItsFramesAndTheSoundStartsWhereTheFramesDo() {
        val c = clock(first = 1, last = 2)
        assertEquals(b(3) - b(1), c.rangeMs, "two frames, not the whole board")
        assertEquals(1000.0 / 12.0, c.audioPositionMs(0.0) ?: -1.0, "frame 1 starts one tick in")

        assertEquals(1, c.frameIndexAt(0.0))
        assertEquals(2, c.frameIndexAt(c.rangeMs - 0.001), "frame 2 runs to the end of the range")
        assertEquals(2, c.frameIndexAt(b(2) - b(1)), "frame 2 begins on its own boundary")
        assertEquals(1, c.frameIndexAt(c.rangeMs), "and the sub-range wraps too")
        // Every frame this range can show is 1 or 2, at any time at all.
        for (i in 0..40) {
            val t = c.rangeMs * i / 40.0
            val f = c.frameIndexAt(t)
            assertTrue(f == 1 || f == 2, "frame $f at $t")
        }
    }

    // ── 3. ONCE ────────────────────────────────────────────────────────────────

    /** ONCE parks on the last frame and says so, at exactly the range length and not before. */
    @Test
    fun onceHoldsTheLastFrameForeverAndFinishesExactlyAtTheEnd() {
        val c = clock(mode = PlayMode.ONCE)
        assertEquals(3, c.frameIndexAt(c.rangeMs - 0.001), "the last frame while it is playing")
        assertEquals(3, c.frameIndexAt(c.rangeMs), "and still at the end")
        assertEquals(3, c.frameIndexAt(c.rangeMs * 1000.0), "and an hour later, and no wrap")

        assertEquals(false, c.isFinished(0.0))
        assertEquals(false, c.isFinished(c.rangeMs - 0.001), "a thousandth of a ms before")
        assertEquals(true, c.isFinished(c.rangeMs), "exactly at the range length")
        assertEquals(true, c.isFinished(c.rangeMs + 1.0))

        assertEquals(0.0, c.audioPositionMs(0.0) ?: -1.0, "the sound starts with the board")
        assertEquals(c.rangeMs - 0.001, c.audioPositionMs(c.rangeMs - 0.001) ?: -1.0)
        assertNull(c.audioPositionMs(c.rangeMs), "and is silent once it has finished")
        assertNull(c.audioPositionMs(c.rangeMs + 500.0))

        // The other two modes never finish, however long they run.
        assertEquals(false, clock(mode = PlayMode.LOOP).isFinished(c.rangeMs * 1000.0))
        assertEquals(false, clock(mode = PlayMode.PING_PONG).isFinished(c.rangeMs * 1000.0))
    }

    // ── 4. PING_PONG ───────────────────────────────────────────────────────────

    /**
     * The spec's sequence, on four EQUAL frames so the boundaries are legible: `0 1 2 3 2 1`, then
     * round again, with the sound silent on the way back.
     *
     * Each of the six slots is sampled at its MIDPOINT, which is the only place in a slot where no
     * rounding can be argued about; the seams are sampled separately below.
     */
    @Test
    fun pingPongPlaysForwardThenBackWithTheEndsOnlyOnce() {
        val even = boardOf(1, 1, 1, 1, id = "even")
        val c = PlaybackClock(even, mode = PlayMode.PING_PONG)
        val d = edge(even, 1) - edge(even, 0)
        val total = edge(even, 4) - edge(even, 0)
        assertEquals(total, c.rangeMs, "four frames forward")

        val expected = listOf(0, 1, 2, 3, 2, 1)
        for (slot in expected.indices) {
            val mid = (slot + 0.5) * d
            assertEquals(expected[slot], c.frameIndexAt(mid), "slot $slot at $mid")
        }
        // And the cycle turns over: slot 6 of one cycle is slot 0 of the next.
        assertEquals(0, c.frameIndexAt(6.0 * d + 0.5 * d), "the seventh slot is the first again")
        assertEquals(1, c.frameIndexAt(6.0 * d + 1.5 * d))

        // The sound is out on the way forward and silent on the way back, from the seam onward.
        assertEquals(0.5 * d, c.audioPositionMs(0.5 * d) ?: -1.0)
        assertEquals(3.5 * d, c.audioPositionMs(3.5 * d) ?: -1.0)
        assertNull(c.audioPositionMs(4.0 * d), "silent from the seam")
        assertNull(c.audioPositionMs(5.0 * d), "all the way back")
        // And out again, a full cycle later. Compared with a tolerance of a nanosecond on purpose:
        // this value has been through a modulo, which is allowed to be one ulp out, and one ulp of
        // ~42 ms is 7e-15. What is being claimed is that nothing ACCUMULATED, and an exact equality
        // here would be claiming more than a modulo can promise.
        assertEquals(0.5 * d, c.audioPositionMs(6.0 * d + 0.5 * d) ?: -1.0, 1.0e-9, "and out again")
    }

    /** The seam itself, which is the whole reason the orchestrator ruling exists. */
    @Test
    fun thePingPongSeamShowsTheLastForwardFrameAndNothingTwice() {
        val even = boardOf(1, 1, 1, 1, id = "even")
        val c = PlaybackClock(even, mode = PlayMode.PING_PONG)
        val d = edge(even, 1) - edge(even, 0)

        // At the seam the last forward frame is still up; the change happens immediately after.
        assertEquals(3, c.frameIndexAt(4.0 * d), "the seam belongs to the forward leg")
        assertEquals(2, c.frameIndexAt(4.0 * d + 0.001), "and then the backward leg is going")
        // From inside the last forward frame the next change is the SEAM; from inside the first
        // backward frame it is the C-to-B change one frame later. Both, because getting only one of
        // them right is exactly how a seam convention goes unnoticed.
        assertEquals(4.0 * d, c.nextChangeMs(4.0 * d - 0.5 * d), "up to the seam")
        assertEquals(5.0 * d, c.nextChangeMs(4.0 * d + 0.5 * d), "and on through the backward leg")
        assertEquals(6.0 * d, c.nextChangeMs(5.0 * d + 0.5 * d), "and on to the turn")

        // Every frame is shown for exactly one frame length per cycle and none is shown twice in a
        // row: sampled densely enough that a doubled frame would be caught.
        // Sampled densely enough that a doubled frame would be caught, and over the whole cycle
        // INCLUDING the turn — the trailing 0 is the interesting one, because a clock that reached the
        // end of its cycle and stopped there would otherwise look identical.
        val seen = ArrayList<Int>()
        var last = -1
        for (i in 0..600) {
            val t = 6.0 * d * i / 600.0
            val f = c.frameIndexAt(t)
            if (f != last) {
                assertTrue(f != last, "frame $f shown twice in a row at $t")
                seen.add(f)
                last = f
            }
        }
        assertEquals(listOf(0, 1, 2, 3, 2, 1, 0), seen, "one cycle and the turn, with no repeats")
    }

    /** A range too short to ping-pong simply plays forwards. */
    @Test
    fun oneAndTwoFrameRangesHaveNoBackwardLeg() {
        for (pair in listOf(0 to 0, 0 to 1, 2 to 3)) {
            val c = PlaybackClock(
                board,
                mode = PlayMode.PING_PONG,
                firstFrame = pair.first,
                lastFrame = pair.second,
            )
            assertEquals(b(pair.second + 1) - b(pair.first), c.rangeMs, "range $pair")
            for (i in 0..30) {
                val f = c.frameIndexAt(c.rangeMs * i / 30.0)
                assertTrue(f in pair.first..pair.second, "range $pair showed frame $f")
            }
            if (pair.first == pair.second) {
                // One frame cannot change, so the player is told never to wake up.
                assertEquals(Double.POSITIVE_INFINITY, c.nextChangeMs(0.0), "range $pair never changes")
            }
        }
    }

    // ── 5. speed ───────────────────────────────────────────────────────────────

    /**
     * Double speed reaches every boundary in half the wall time, and the clamped and non-finite
     * speeds are asserted by COMPARING CLOCKS rather than by predicting a float, because
     * `s1 / 0.1f` is not a number anyone can hold in their head and a range assertion would prove
     * nothing at all.
     */
    @Test
    fun speedHalvesEveryBoundaryAndTheEndsAreClamped() {
        val full = clock()
        val half = clock(speed = 0.5f)
        val doubled = clock(speed = 2f)
        val slow = clock(speed = 0.1f)
        val clampedLow = clock(speed = 0f)
        val clampedHigh = clock(speed = 99f)
        val top = clock(speed = 4f)
        val notANumber = clock(speed = Float.NaN)

        assertEquals(1, doubled.frameIndexAt(b(1) / 2.0), "frame 1 at half the wall time")
        assertEquals(2, doubled.frameIndexAt(b(2) / 2.0))
        assertEquals(3, doubled.frameIndexAt(b(3) / 2.0))
        assertEquals(b(1) / 2.0, doubled.nextChangeMs(0.0), "and awake twice as early")
        assertEquals(full.nextChangeMs(0.0) / 2.0, doubled.nextChangeMs(0.0))
        assertEquals(2, half.frameIndexAt(b(2) * 2.0), "half speed takes twice as long")

        // The clamped ends behave EXACTLY like the boundary value, not approximately.
        assertEquals(slow.nextChangeMs(0.0), clampedLow.nextChangeMs(0.0), "speed 0 is speed 0.1")
        assertEquals(slow.frameIndexAt(5000.0), clampedLow.frameIndexAt(5000.0))
        assertEquals(top.nextChangeMs(0.0), clampedHigh.nextChangeMs(0.0), "speed 99 is speed 4")
        assertEquals(top.frameIndexAt(5000.0), clampedHigh.frameIndexAt(5000.0))
        // And a tenth speed really is a tenth speed: 5000 wall ms is 500 board ms, and 500 ms lies in
        // the LAST frame (333.33 … 583.33), not the middle one.
        assertEquals(3, clampedLow.frameIndexAt(5000.0), "5000 wall ms at a tenth speed is 500 board ms")

        // NaN plays at 1 — exactly, not nearly.
        assertEquals(full.nextChangeMs(0.0), notANumber.nextChangeMs(0.0))
        for (t in listOf(0.0, 100.0, 250.0, 400.0, 1000.0)) {
            assertEquals(full.frameIndexAt(t), notANumber.frameIndexAt(t), "at $t")
        }
    }

    // ── 6. no drift ────────────────────────────────────────────────────────────

    /**
     * An hour in, the frame is the frame the arithmetic says it is — and the arithmetic is done here,
     * in whole numbers, rather than by trusting a modulo.
     *
     * The range is `hold * 1000 / fps` for holds 1, 2, 1, 3 at 12 fps, which is 1750/3 ms exactly in
     * real arithmetic. So 6171 whole passes are 6171 * 1750/3 = 3 599 750 ms exactly, and
     * 3 600 001 − 3 599 750 = 251 ms, which lies inside frame 2 (250 … 333.33). A clock that
     * accumulated frame lengths instead would be somewhere else entirely by now.
     */
    @Test
    fun anHourInTheFrameIsTheOneTheArithmeticSays() {
        val c = clock()
        val hourPlusOne = 3_600_001.0
        assertEquals(3_599_750.0, 6171.0 * 1750.0 / 3.0, "the whole passes, exactly")
        assertEquals(251.0, hourPlusOne - 3_599_750.0, "and what is left over")
        assertEquals(2, c.frameIndexAt(251.0), "251 ms is inside frame 2")
        assertEquals(2, c.frameIndexAt(hourPlusOne), "so an hour in, we are in frame 2")
        assertEquals(c.frameIndexAt(251.0), c.frameIndexAt(hourPlusOne), "and the same, exactly")

        // ONCE: an hour in it is finished, still on the last frame, and silent.
        val once = clock(mode = PlayMode.ONCE)
        assertEquals(true, once.isFinished(hourPlusOne))
        assertEquals(3, once.frameIndexAt(hourPlusOne))
        assertNull(once.audioPositionMs(hourPlusOne))

        // PING_PONG: a cycle is the range plus its interior, 1750/3 + 750/3 = 2500/3 ms, so 4320 whole
        // cycles are 4320 * 2500/3 = 3 600 000 ms exactly, leaving 1 ms — inside frame 0.
        val pong = clock(mode = PlayMode.PING_PONG)
        assertEquals(3_600_000.0, 4320.0 * 2500.0 / 3.0, "the whole cycles, exactly")
        assertEquals(0, pong.frameIndexAt(hourPlusOne), "1 ms past a cycle boundary is frame 0")
    }

    // ── 7. nextChangeMs ────────────────────────────────────────────────────────

    /**
     * The player sleeps until the next change, and — the stronger half — nothing changes before then.
     * A test that only checked the returned value would pass for a clock that returned a plausible
     * number and then changed the frame at some other moment.
     */
    @Test
    fun theNextChangeIsTheFirstMomentTheFrameDiffers() {
        val c = clock()
        assertEquals(b(1), c.nextChangeMs(0.0), "the first boundary")

        // From inside the 3-hold frame, the change is that frame's own end.
        val inside = 350.0
        assertEquals(3, c.frameIndexAt(inside))
        assertEquals(c.rangeMs, c.nextChangeMs(inside), "the end of the frame we are in")
        assertTrue(c.nextChangeMs(inside) > inside, "and it is a time in the future")

        val wake = c.nextChangeMs(inside)
        var t = inside
        while (t < wake - 0.0001) {
            assertEquals(3, c.frameIndexAt(t), "the frame must hold until $wake, but changed at $t")
            t += 1.0
        }
        assertTrue(c.frameIndexAt(wake) != 3, "and must differ at $wake")

        // Walking every boundary: from each, the next is the one after it.
        for (k in 1..3) {
            assertEquals(b(k + 1), c.nextChangeMs(b(k)), "from boundary $k")
            assertEquals(k, c.frameIndexAt(b(k)), "boundary $k shows frame $k")
        }

        // A finished ONCE clock, and a one-frame range, both say "never again".
        val once = clock(mode = PlayMode.ONCE)
        assertEquals(Double.POSITIVE_INFINITY, once.nextChangeMs(once.rangeMs), "once, finished")
        assertEquals(Double.POSITIVE_INFINITY, once.nextChangeMs(once.rangeMs + 10.0))
        assertEquals(Double.POSITIVE_INFINITY, clock(first = 2, last = 2).nextChangeMs(0.0), "one frame")
    }

    // ── 8. the range, and the refusals ─────────────────────────────────────────

    /** A range given backwards is swapped, a range past the end is clamped, and no board is refused. */
    @Test
    fun rangesAreSwappedClampedAndAnEmptyBoardIsRefused() {
        val seven = boardOf(1, 1, 1, 1, 1, 1, 1, id = "seven")

        val swapped = PlaybackClock(seven, mode = PlayMode.LOOP, firstFrame = 5, lastFrame = 2)
        // 2..5 is FOUR frames, not one: the range runs from frame 2's start to frame 5's END.
        assertEquals(edge(seven, 6) - edge(seven, 2), swapped.rangeMs, "5..2 became 2..5, four frames")
        assertEquals(2, swapped.frameIndexAt(0.0))
        assertEquals(3, swapped.frameIndexAt(edge(seven, 3) - edge(seven, 2)))
        assertEquals(5, swapped.frameIndexAt(edge(seven, 6) - edge(seven, 2) - 0.001))
        assertEquals(2, swapped.frameIndexAt(edge(seven, 6) - edge(seven, 2)), "and it wraps")
        assertEquals(edge(seven, 2), swapped.audioPositionMs(0.0) ?: -1.0, "the sound starts with frame 2")

        val clamped = PlaybackClock(seven, mode = PlayMode.LOOP, firstFrame = 0, lastFrame = 99)
        val whole = PlaybackClock(seven, mode = PlayMode.LOOP)
        assertEquals(whole.rangeMs, clamped.rangeMs, "0..99 is the whole board")
        for (i in 0..20) {
            val t = whole.rangeMs * i / 20.0
            assertEquals(whole.frameIndexAt(t), clamped.frameIndexAt(t), "at $t")
        }
        val negative = PlaybackClock(seven, mode = PlayMode.LOOP, firstFrame = -3, lastFrame = -9)
        assertEquals(0, negative.frameIndexAt(0.0), "a wholly negative range is frame 0")
        assertEquals(Double.POSITIVE_INFINITY, negative.nextChangeMs(0.0))

        assertFailsWith<IllegalArgumentException> { PlaybackClock(boardOf(id = "empty")) }
    }

    // ── and the times that cannot be placed on a timeline ──────────────────────

    /**
     * Negative, NaN and infinite elapsed times all read as the beginning rather than poisoning
     * everything downstream. Infinity is the one that matters: `Infinity % rangeMs` is NaN, and a NaN
     * position compares as "before the first frame" in every comparison, so an unguarded clock would
     * answer 0 forever instead of refusing to answer.
     */
    @Test
    fun impossibleElapsedTimesReadAsTheBeginning() {
        val c = clock()
        for (t in listOf(-1.0, -1_000_000.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(0, c.frameIndexAt(t), "at $t")
            assertEquals(b(1), c.nextChangeMs(t), "next change at $t")
            assertEquals(0.0, c.audioPositionMs(t) ?: -1.0, "sound at $t")
        }
        assertEquals(false, c.isFinished(Double.NaN), "and NaN has not finished")
    }
}

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

    /**
     * How far inside a slot a probe may sit and still be unambiguously inside it: a thousandth of a
     * millisecond against a shortest possible frame of 16.7 ms. Chosen to be far larger than the ulp
     * of any boundary a board can hold — so no rounding can be argued about — and far smaller than
     * any frame — so a probe can never skip over a boundary.
     */
    private val AWAY = 0.001

    /**
     * Tolerance for a value that has been through `elapsed + (boundary - position) / speed` and is
     * therefore only promised to be *one ulp* from the boundary. Boundaries themselves are asserted
     * with no tolerance at all; see [everyBackwardBoundaryIsExactlyItsOwnNextChange].
     */
    private val RECONSTRUCTED = 1.0e-9

    /**
     * The range's own starts, `first`..`first + count - 1` plus its end, indexed exactly as the
     * clock indexes them: board edges, made relative by subtracting the range's first edge.
     *
     * A table derived from the same arithmetic the clock uses would prove nothing, which is why
     * nothing in this file is written as `hold * 1000 / fps`.
     */
    private fun rangeStarts(of: Board, first: Int, count: Int): List<Double> =
        (0..count).map { edge(of, first + it) - edge(of, first) }

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

    // ── 4b. nextChangeMs AT a backward boundary ──────────────────────────────────────────────────
    //
    // Everything below is about one number. At a falling edge of the backward leg the frame on screen
    // AT the edge is the frame on its way OUT, so the set of times at which the frame differs is an
    // open interval with no smallest member: its infimum is the edge itself, and the contract asks
    // for the infimum. A clock that answers `edge + ε` — or, worse, and this is what this file used
    // to contain, the NEXT edge along, a whole frame away — makes a player sleep straight through
    // the incoming frame every time it turns.

    /**
     * THE TIMELINE, BY HAND, for six EQUAL frames at 12 fps. `d` is one tick and the table is written
     * in multiples of `d` only to be legible, because every assertion below indexes the board's own
     * starts instead: `6 * (1000.0 / 12.0)` and the sixth ACCUMULATED start are not the same double,
     * and a boundary an ulp out is a boundary on the wrong side of the question.
     *
     * ```
     * starts    0    d    2d    3d    4d    5d       rangeMs = 6d  (an end, not a start)
     * cycleMs  10d = 6d + (5d - d)                   the leg replays starts[5] back to starts[1]
     *
     *  cycle slot        frame   at its right end      nextChangeMs AT that right end
     *    [0,     d)         0      1                            d        (a frame start)
     *    [d,    2d)         1      2                           2d
     *    [2d,   3d)         2      3                           3d
     *    [3d,   4d)         3      4                           4d
     *    [4d,   5d)         4      5                           5d
     *    [5d,   6d]         5      5                           6d     <- the SEAM, still frame 5
     *    (6d,   7d]         4      4                           7d     <- falling edge #1, still 4
     *    (7d,   8d]         3      3                           8d     <- #2
     *    (8d,   9d]         2      2                           9d     <- #3
     *    (9d,  10d)         1      0                          10d + d <- the turn; the frame IS 0 at 10d
     * ```
     *
     * Every `]` is the content of the ruling: the right end belongs to the slot, the change lands
     * immediately after it, and `nextChangeMs` asked there must answer with that end. The last row is
     * the one that stops this being a rule about "the seam": at the turn the frame really does change
     * AT the boundary, so the turn answers 11d like any other forward start. A backward boundary is
     * not a special time; it is every time the position is falling, and there are four of them here.
     */
    @Test
    fun everyBackwardBoundaryIsExactlyItsOwnNextChange() {
        val even = boardOf(1, 1, 1, 1, 1, 1, id = "six")
        val c = PlaybackClock(even, mode = PlayMode.PING_PONG)
        val n = 6
        val s = rangeStarts(even, 0, n)

        // The falling edges of the leg, seam first and turn last, in cycle time.
        val u = DoubleArray(n) { c.rangeMs + (s[n - 1] - s[it]) }
        assertEquals(c.rangeMs, u[n - 1], "u[5] is the seam, and it is rangeMs")
        assertEquals(c.rangeMs + (s[n - 1] - s[1]), u[1], "u[1] is the turn")

        // The seam and THREE interior falling edges. One board with several of them is the only way
        // to catch a fix that has understood the seam and stopped there — which is precisely the shape
        // of the bug this file was found with.
        val selfAnswering = (2..n - 1).map { u[it] }
        assertEquals(4, selfAnswering.size, "the seam plus three interior edges")

        for (speed in listOf(1f, 2f, 0.5f, 4f)) {
            val rate = speed.toDouble()
            val clock = PlaybackClock(even, mode = PlayMode.PING_PONG, speed = speed)
            for (at in selfAnswering) {
                val asked = at / rate
                assertEquals(
                    asked,
                    clock.nextChangeMs(asked),
                    "at speed $speed the boundary $at is its OWN next change, exactly",
                )
            }
            // The turn is not one of them, and that is the point of checking it here: at the turn the
            // frame HAS changed, so the boundary is a forward one and answers the boundary after it.
            val turn = u[1] / rate
            assertEquals(0, clock.frameIndexAt(turn), "at the turn the cycle has wrapped to frame 0")
            assertEquals(turn + s[1] / rate, clock.nextChangeMs(turn), "so the turn answers forwards")
        }
    }

    /**
     * The same claim where a table written for equal frames quietly stops holding: a range that does
     * not start at frame 0, whose frames are not all the same length, and which has four interior
     * falling edges rather than three.
     */
    @Test
    fun interiorFallingEdgesAreTheirOwnNextChangeOnAnUnevenSubRange() {
        // holds 3, 1, 2, 1, 3, 2, 1 — ranged 1..6 that is six frames of six different lengths.
        val bd = boardOf(3, 1, 2, 1, 3, 2, 1, id = "seven")
        val c = PlaybackClock(bd, mode = PlayMode.PING_PONG, firstFrame = 1, lastFrame = 6)
        val n = 6
        val s = rangeStarts(bd, 1, n)
        val u = DoubleArray(n) { c.rangeMs + (s[n - 1] - s[it]) }

        for (k in 2..n - 1) {
            val at = u[k]
            assertEquals(at, c.nextChangeMs(at), "falling edge $k at $at is its own next change")
            // The frame on screen AT the edge is the one going out and the one a hair later is not, so
            // the answer is an infimum and not merely the far side of a slot.
            assertTrue(
                c.frameIndexAt(at + AWAY) != c.frameIndexAt(at),
                "at $at the change is immediately after, not a frame later",
            )
        }
        assertEquals(c.rangeMs, u[n - 1], "the seam of a sub-range is still its own rangeMs")
        assertEquals(3, (2..n - 2).count(), "three interior falling edges plus the seam")
        // They are four different numbers, not one number counted four times.
        assertEquals(4, selfDistinct(u.copyOfRange(2, n)).size, "four distinct falling edges")
    }

    /**
     * THE WHOLE TIMELINE, SLOT BY SLOT, on four boards — four equal frames, six equal frames, six
     * uneven frames and seven uneven frames — walking three cycles of each.
     *
     * For every slot: the frame a hair inside it, at its midpoint and a hair before its right end;
     * the frame AT its right end if the slot owns that end and a different one if it does not; and
     * that `nextChangeMs` from inside the slot is that right end. Those last two cannot both be true
     * of a clock that is wrong in either direction — reported early and the frame has already changed
     * inside the slot, reported late and the slot's own end is reached with the frame unchanged while
     * the answer is still a whole frame off.
     *
     * The EXACT-at-the-boundary assertions run in the first cycle only. Adding a cycle offset in
     * floating point moves the probe by up to an ulp of the sum, which at a boundary is the difference
     * between the frame on either side of it; the later cycles are walked with the same probes but the
     * ones that carry a tolerance, plus the interval invariant in
     * [nextChangeMsIsTheInfimumOfWhenTheFrameActuallyChanges], which has no tolerance in it at all.
     */
    @Test
    fun theWholePingPongTimelineIsPinnedSlotBySlot() {
        for (holds in listOf(
            intArrayOf(1, 1, 1, 1),
            intArrayOf(1, 1, 1, 1, 1, 1),
            intArrayOf(1, 2, 1, 3, 2, 1),
            intArrayOf(3, 1, 2, 1, 3, 2, 1),
        )) {
            val bd = boardOf(*holds, id = "p${holds.size}")
            val c = PlaybackClock(bd, mode = PlayMode.PING_PONG)
            val starts = rangeStarts(bd, 0, holds.size)
            val slots = cycleSlots(c, starts)
            val label = holds.joinToString(",")
            assertEquals(2 * holds.size - 2, slots.size, "$label frames, ${slots.size} slots")
            // The shape of the ruling, counted rather than described: `count` forward slots, all
            // closed on the left; the seam plus every interior falling edge closed on the right; and
            // exactly one slot — the last — that runs into the turn and is closed on neither end.
            assertEquals(holds.size, slots.count { it.closedLeft }, "$label: forward slots")
            assertEquals(holds.size - 2, slots.count { it.closedRight }, "$label: the falling edges")
            assertEquals(false, slots.last().closedRight, "$label: the turn ends no slot")
            assertTrue(slots.last().to > slots[slots.size - 2].to, "$label: it is the last one")

            // The RIGHT-HAND COLUMN OF THE HAND TABLE, asserted rather than described. Asked AT a
            // boundary, `nextChangeMs` is that boundary itself where the position is falling and the
            // boundary after it where it is rising — one rule, two comparisons, and the only part of
            // this test that carries no tolerance of any kind. This is the assertion the bug got past.
            for (i in slots.indices) {
                val expected = if (slots[i].closedRight) {
                    slots[i].to
                } else if (i + 1 < slots.size) {
                    slots[i + 1].to
                } else {
                    // The turn is not the end of anything: the range starts again, at its first frame.
                    slots[i].to + starts[1]
                }
                assertEquals(
                    expected,
                    c.nextChangeMs(slots[i].to),
                    "$label, asked at the end of frame ${slots[i].frame}'s slot",
                )
            }

            var elapsed = 0.0
            for (cycle in 0 until 3) {
                for (slot in slots) {
                    val at = "$label, cycle $cycle, frame ${slot.frame} in [${slot.from}, ${slot.to}]"
                    val from = elapsed + slot.from
                    val to = elapsed + slot.to
                    // Cycle 0's probes land EXACTLY on the table, because nothing has been added to
                    // them. Later cycles carry an ulp of accumulated offset, so they step AWAY inside
                    // the slot instead — a probe that means to be AT a boundary and lands on the far
                    // side of the one comparison that decides which frame that is would be reporting
                    // the clock's rounding rather than its rule.
                    val inside = if (slot.closedLeft && cycle == 0) from else from + AWAY
                    assertEquals(slot.frame, c.frameIndexAt(inside), "$at, a hair inside")
                    assertEquals(slot.frame, c.frameIndexAt((from + to) / 2.0), "$at, midway")
                    assertEquals(slot.frame, c.frameIndexAt(to - AWAY), "$at, just before its end")
                    if (cycle == 0) {
                        if (slot.closedRight) {
                            assertEquals(slot.frame, c.frameIndexAt(to), "$at, AT its own right end")
                        } else {
                            assertTrue(
                                c.frameIndexAt(to) != slot.frame,
                                "$at, its right end belongs to the next slot, not this one",
                            )
                        }
                    }
                    // From inside the slot, the next change is that slot's right end. Tolerated only
                    // because the answer is REBUILT as elapsed + (boundary - position) / speed.
                    assertEquals(to, c.nextChangeMs(inside), RECONSTRUCTED, "$at, awake at its end")
                    assertEquals(to, c.nextChangeMs(to - AWAY), RECONSTRUCTED, "$at, from inside it")
                    assertTrue(
                        c.frameIndexAt(c.nextChangeMs(inside) + AWAY) != slot.frame,
                        "$at, the frame HAS changed a hair after the answer",
                    )
                }
                elapsed += slots.last().to
            }
        }
    }

    /**
     * The same statement as [theWholePingPongTimelineIsPinnedSlotBySlot] asked the one way that needs
     * no table at all, so it cannot inherit a mistake from one: everything here compares
     * `nextChangeMs` against `frameIndexAt` and nothing else.
     *
     * `nextChangeMs(t)` is the infimum of the times at which the frame differs from the frame at `t`,
     * which is exactly these two statements:
     *
     *  - the frame is UNCHANGED everywhere in `[t, nextChangeMs(t))`. This is what fails for an answer
     *    a hair late, and a whole frame late — the bug this file was found with — fails it loudly.
     *  - the frame HAS changed at `nextChangeMs(t) + ε`. This is what fails for an answer that is
     *    early, which is what an epsilon "safety margin" on a boundary would buy.
     *
     * Neither tolerance appears anywhere below. Over four cycles at a thousand probes each, on six
     * clocks covering both legs, three speeds, a sub-range and all three modes.
     *
     * ONCE is the one mode exempt from the second clause, and deliberately so. ONCE does not wrap:
     * once its last frame is up, `frameIndexAt` parks there and the set of times at which it differs
     * is empty, so there is no "and it HAS changed just after" to assert from inside that frame. What
     * `nextChangeMs` should answer there instead is a question this spec has not yet ruled on — see
     * the Questions section of the spec — and pinning either answer here would smuggle a decision in
     * through a test written to prove something else. The first clause still applies to ONCE, and the
     * ONCE answers this file already pinned are untouched.
     */
    @Test
    fun nextChangeMsIsTheInfimumOfWhenTheFrameActuallyChanges() {
        val bd = boardOf(1, 2, 1, 3, 2, 1, 2, 1, id = "eight")
        val full = PlaybackClock(bd, mode = PlayMode.PING_PONG)
        val mid = PlaybackClock(bd, mode = PlayMode.PING_PONG, firstFrame = 2, lastFrame = 5)
        val clocks = listOf(
            Triple("pong", full, full.rangeMs + (edge(bd, 7) - edge(bd, 1))),
            Triple("pong x2", PlaybackClock(bd, mode = PlayMode.PING_PONG, speed = 2f), 0.0),
            Triple("pong 0.5", PlaybackClock(bd, mode = PlayMode.PING_PONG, speed = 0.5f), 0.0),
            Triple("pong 2..5", mid, mid.rangeMs + (edge(bd, 5) - edge(bd, 3))),
            Triple("loop", PlaybackClock(bd, mode = PlayMode.LOOP), 0.0),
            Triple("once", PlaybackClock(bd, mode = PlayMode.ONCE), 0.0),
        )
        for ((name, c, span) in clocks) {
            val period = if (span > 0.0) span else c.rangeMs
            val probes = ArrayList<Double>()
            for (i in 0..1000) probes.add(period * 4.0 * i / 1000.0)
            // And every frame edge of the board, which the grid above lands on only by luck.
            for (k in 1..bd.frames.size) probes.add(edge(bd, k))
            var checked = 0
            var silent = 0
            for (t in probes) {
                val frame = c.frameIndexAt(t)
                val wake = c.nextChangeMs(t)
                if (wake.isInfinite()) {
                    // Only ONCE does this, and only past its end — see the note on the class above.
                    silent++
                    continue
                }
                assertTrue(wake >= t, "$name: the answer at $t is not before the question")
                if (wake - t > 1.0e-6) {
                    // Nine probes across the window, so a boundary an eighth of the way in cannot
                    // hide between two of them. Skipped when the window is thinner than a
                    // microsecond: that is a sliver 16 000 times narrower than the shortest possible
                    // frame, so there is genuinely nothing inside it to find — and a probe placed by
                    // interpolation across a one-ulp window can round up ONTO the boundary, which
                    // would be the test failing where no assertion is even meaningful.
                    for (k in 0 until 9) {
                        val inside = t + (wake - t) * k / 9.0
                        assertEquals(
                            frame,
                            c.frameIndexAt(inside),
                            "$name: frame $frame changed inside its own window: asked at $t, " +
                                "told $wake, found ${c.frameIndexAt(inside)} at $inside",
                        )
                    }
                }
                if (c.mode != PlayMode.ONCE) {
                    assertTrue(
                        c.frameIndexAt(wake + AWAY) != frame,
                        "$name: frame $frame is still up at ${wake + AWAY}, so the answer $wake asked " +
                            "at $t is early",
                    )
                }
                checked++
            }
            assertEquals(probes.size, checked + silent, "$name: every probe answered one way or the other")
            assertTrue(checked > 200, "$name: only $checked probes had a finite answer")
        }
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

    // ── the timeline, worked out from the board rather than from the clock ───────────────────────

    /**
     * A ping-pong cycle split into the slots its frames actually occupy, in cycle time.
     *
     * Built from [starts] — the board's own edges, made range-relative — by the ruling's own recipe,
     * and deliberately NOT by asking the clock: a table derived from the function it is meant to
     * check proves nothing at all.
     *
     * Forward: `[starts[k], starts[k + 1])`, and the last one `[starts[n-1], rangeMs]` because the
     * seam still shows the last forward frame. Backward: the leg walks `starts[n-1]` back to
     * `starts[1]`, so its edges are `u[k] = rangeMs + (starts[n-1] - starts[k])` for `k` from `n - 1`
     * down to 1 — the seam first, the turn last — and the slot showing frame `k` is `(u[k+1], u[k]]`.
     * The turn itself belongs to no slot: at `cycleMs` the range has started again. The backward slots
     * are walked from `n - 2` down to 1 so that the list comes out in increasing cycle time, which is
     * the order a player meets them in.
     */
    private fun cycleSlots(c: PlaybackClock, starts: List<Double>): List<Slot> {
        val n = starts.size - 1
        val last = starts[n - 1]
        val u = DoubleArray(n) { c.rangeMs + (last - starts[it]) }
        val slots = ArrayList<Slot>(2 * n - 2)
        for (k in 0 until n) {
            slots.add(Slot(starts[k], starts[k + 1], k, closedLeft = true, closedRight = k == n - 1))
        }
        for (k in n - 2 downTo 1) {
            // Frame 1's slot also stops at the turn, where the frame is no longer 1.
            slots.add(Slot(u[k + 1], u[k], k, closedLeft = false, closedRight = k != 1))
        }
        return slots
    }

    /** How many of [these] are distinct — used to prove four edges are four numbers and not one. */
    private fun selfDistinct(these: DoubleArray): List<Double> = these.distinct()

    /** One frame's occupancy of one cycle: `frame` is shown over `[from, to]`, ends per the flags. */
    private data class Slot(
        val from: Double,
        val to: Double,
        val frame: Int,
        val closedLeft: Boolean,
        val closedRight: Boolean,
    )
}

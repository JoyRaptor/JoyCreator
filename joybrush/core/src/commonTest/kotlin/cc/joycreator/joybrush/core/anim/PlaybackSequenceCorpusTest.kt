package cc.joycreator.joybrush.core.anim

import cc.joycreator.joybrush.core.doc.AnimOps
import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.DocException
import cc.joycreator.joybrush.core.doc.Frame
import cc.joycreator.joybrush.core.doc.RectPx
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Playback clock/step sequence oracle on schedules the existing suites never use.
 *
 * [PlaybackClockTest] pins holds [1, 2, 1, 3] at 12 fps and even six-frame boards;
 * [FrameStepperTest] pins the six-equal-frames board at 10 fps. Every schedule here is a
 * NEW small board — three uneven frames [2, 1, 3] at 10 fps, so one tick is exactly 100 ms
 * and every boundary below is a whole millisecond derived from [AnimOps], never accumulated
 * a second time:
 *
 * ```
 * frameStartsMs      0    200    300
 * totalDurationMs    600.0                    rangeMs = 600.0
 * backSpanMs         starts[2] - starts[1] = 300 - 200 = 100
 * cycleMs            600 + 100 = 700          (PING_PONG; the minimal backward leg: seam + turn)
 * falling edges      u[2] = 600  THE SEAM, still frame 2
 *                    u[1] = 700  THE TURN, and the frame really IS 0 there
 * ```
 *
 * What this file adds over the existing suites:
 * - full Step emission sequences on the new schedule in all three modes;
 * - SKIPPED polls (370 ms steps over ~100 ms frames; jumps across the seam and the turn),
 *   proving a coarse player lands on the clock's answer with a single instruction;
 * - a nextWakeMs CHAIN oracle: following wakes from 0 visits every slot, strictly increasing;
 * - audio COMMAND vs sound playback: [Step.Audio] carries a board-timeline position (or the
 *   silence command null), provably derived from the schedule alone — two boards with the same
 *   schedule but different ids agree everywhere, and there are no bytes, no track and no player
 *   anywhere in this file. An empty list means "no command"; `Audio(null)` means "go silent".
 * - invalid schedules refused with [DocException] (the schedule's own refusal, surfacing through
 *   the clock), the empty board refused with [IllegalArgumentException] (the clock's own rule);
 * - non-finite and negative speeds follow the documented clamp rule, including the cases the
 *   existing suite never pairs up (negative, infinite);
 * - one AMBIGUITY REPORT ([documentedAmbiguityOnceLastFrameNextChange]): JB-3.05a Questions §3
 *   leaves ONCE's last-frame nextChangeMs unresolved, so this file documents the current answer
 *   without endorsing it.
 *
 * BOUNDS: every elapsed literal in this file is within [0, 3_600_001] ms (about an hour); no
 * probe exceeds it. No timing thresholds, no randomness, no smoothness or device claim, no clock
 * change. A `Step.Audio` value is a number for a future transport to act on, never a claim that
 * sound was played.
 */
class PlaybackSequenceCorpusTest {

    private fun boardOf(vararg holds: Int, fps: Float = 12f, id: String = "c"): Board = Board(
        id = id,
        name = "Anim",
        kind = BoardKind.ANIMATION,
        rect = RectPx(0, 0, 256, 256),
        fps = fps,
        frames = holds.mapIndexed { i, hold -> Frame("f$i", holdFrames = hold) },
    )

    /** The corpus board: three uneven frames [2, 1, 3] at 10 fps. Boundaries 0, 200, 300, 600. */
    private val boardC = boardOf(2, 1, 3, fps = 10f, id = "c")

    /** Boundary [k] of [of]: a frame start, or the board's end when past the last frame. */
    private fun edge(of: Board, k: Int): Double {
        val s = AnimOps.frameStartsMs(of)
        return if (k < s.size) s[k] else AnimOps.totalDurationMs(of)
    }

    private fun e(k: Int): Double = edge(boardC, k)

    /**
     * A probe offset far larger than the ulp of any boundary here and far smaller than any
     * frame (shortest frame on this board is 100 ms).
     */
    private val AWAY = 0.001

    // ── 1. LOOP on the new schedule ──────────────────────────────────────────

    /**
     * The whole forward timeline on holds [2, 1, 3]: frames 0, 0, 1, 2, 2, 2 per 100 ms tick,
     * wrapping at exactly 600 ms, with the sound at the board time and a one-hour spot check
     * against whole-cycle arithmetic (3 600 000 = 6000 × 600 exactly, leaving 1 ms: frame 0).
     */
    @Test
    fun loopSequenceOnUnevenThreeFrameSchedule() {
        val c = PlaybackClock(boardC, mode = PlayMode.LOOP)
        assertEquals(600.0, c.rangeMs, "200 + 100 + 300, out of the board's own starts")
        assertEquals(200.0, e(1), "two ticks at 10 fps")
        assertEquals(300.0, e(2), "plus one tick")
        assertEquals(600.0, e(3), "plus three ticks: the end")

        assertEquals(listOf(0, 0, 1, 2, 2, 2), (0..5).map { c.frameIndexAt(it * 100.0 + 50.0) })
        assertEquals(0, c.frameIndexAt(0.0))
        assertEquals(0, c.frameIndexAt(199.999))
        assertEquals(1, c.frameIndexAt(200.0), "frame 1 begins on its boundary")
        assertEquals(1, c.frameIndexAt(299.999))
        assertEquals(2, c.frameIndexAt(300.0), "frame 2 begins on its boundary")
        assertEquals(2, c.frameIndexAt(599.999))
        assertEquals(0, c.frameIndexAt(600.0), "and the range wraps at its length")
        assertEquals(0, c.frameIndexAt(700.0), "100 ms into the next pass")

        assertEquals(0.0, c.audioPositionMs(0.0))
        assertEquals(599.999, c.audioPositionMs(599.999))
        assertEquals(0.0, c.audioPositionMs(600.0), "the sound wraps with the picture")

        assertEquals(200.0, c.nextChangeMs(0.0))
        assertEquals(300.0, c.nextChangeMs(250.0))
        assertEquals(600.0, c.nextChangeMs(350.0))
        assertEquals(800.0, c.nextChangeMs(600.0), "past the wrap, the boundary after it")

        assertEquals(0, c.frameIndexAt(3_600_001.0), "an hour and a millisecond in: frame 0")
        assertEquals(c.frameIndexAt(1.0), c.frameIndexAt(3_600_001.0), "and the same, exactly")
        assertEquals(false, c.isFinished(3_600_001.0), "LOOP never finishes")
    }

    // ── 2. PING_PONG on the minimal three-frame cycle ────────────────────────

    /**
     * The smallest range with a backward leg: forward 0 1 2, seam at 600 still showing 2,
     * one interior slot showing 1 over (600, 700], turn at 700 showing 0. The 1 ms walk from
     * 0 to 700 carries picture and sound like a player and pins every emission, including the
     * 602 sound commands (600 forward milliseconds, the seam going silent, the turn coming back).
     */
    @Test
    fun pingPongThreeFrameMinimalCycle() {
        val c = PlaybackClock(boardC, mode = PlayMode.PING_PONG)
        val s = FrameStepper(c)
        val span = e(2) - e(1)
        assertEquals(100.0, span, "the interior replayed backwards")
        assertEquals(700.0, c.rangeMs + span, "rangeMs + backSpanMs, out of the board's own starts")

        assertEquals(listOf(0, 1, 2, 1), listOf(100.0, 250.0, 450.0, 650.0).map { c.frameIndexAt(it) })
        assertEquals(2, c.frameIndexAt(600.0), "the seam belongs to the forward leg")
        assertEquals(1, c.frameIndexAt(600.0 + AWAY), "and the backward leg is going just after")
        assertEquals(0, c.frameIndexAt(700.0), "the turn wraps to frame 0")
        assertEquals(1, c.frameIndexAt(950.0), "250 ms into the next cycle: 950 % 700 = 250, frame 1")

        // The falling edge set of this cycle is exactly the seam: 200 and 300 are rising
        // (the frame changed AT them), 700 is the turn (changed AT it), only 600 is falling.
        assertTrue(c.frameIndexAt(200.0) != c.frameIndexAt(200.0 - AWAY), "200 is rising")
        assertTrue(c.frameIndexAt(300.0) != c.frameIndexAt(300.0 - AWAY), "300 is rising")
        assertEquals(c.frameIndexAt(600.0), c.frameIndexAt(600.0 - AWAY), "600 is falling")
        assertTrue(c.frameIndexAt(700.0) != c.frameIndexAt(700.0 - AWAY), "700 is the turn, not an edge")

        var shown = 0
        var audio: Double? = null
        val shownEmissions = ArrayList<Pair<Int, Double>>()
        val audioEmissions = ArrayList<Pair<Double?, Double>>()
        for (ms in 0..700) {
            val t = ms.toDouble()
            for (step in s.step(t, shown, audio)) {
                when (step) {
                    is Step.ShowFrame -> {
                        shown = step.index
                        shownEmissions.add(step.index to t)
                    }
                    is Step.Audio -> {
                        audio = step.boardMs
                        audioEmissions.add(step.boardMs to t)
                    }
                    is Step.Finish -> assertTrue(false, "PING_PONG must never finish, at $t")
                }
            }
            assertEquals(c.frameIndexAt(t), shown, "the player shows the clock's frame at $t")
            assertTrue(s.nextWakeMs(t) > t, "the wake at $t is strictly after now")
        }
        assertEquals(
            listOf(1 to 200.0, 2 to 300.0, 1 to 601.0, 0 to 700.0),
            shownEmissions,
            "2n - 2 = 4 emissions for n = 3: rising edges draw AT them, the falling edge one step after",
        )
        assertEquals(602, audioEmissions.size, "600 forward ms, the seam going silent, the turn coming back")
        assertEquals(0.0 to 0.0, audioEmissions[0])
        assertEquals(599.0 to 599.0, audioEmissions[599])
        assertEquals(null to 600.0, audioEmissions[600], "the seam is where the sound goes out")
        assertEquals(0.0 to 700.0, audioEmissions.last(), "the turn is where it comes back in")
        assertEquals(600.0 + FrameStepper.MIN_WAKE_MS, s.nextWakeMs(600.0), "the 1 ms floor at the seam")
    }

    // ── 3. ONCE on the new schedule, as a Step sequence ──────────────────────

    /**
     * ONCE parks on frame 2 at exactly 600 ms with a standing Finish. The final assertion is
     * the skipped-poll form: asked 10 seconds past the end with a stale picture AND a stale
     * (non-silent) sound, the answer names the last frame, orders the silence command before
     * the standing fact, and nothing else.
     */
    @Test
    fun onceSequenceParksWithStandingFinish() {
        val c = PlaybackClock(boardC, mode = PlayMode.ONCE)
        val s = FrameStepper(c)
        assertEquals(0, c.frameIndexAt(0.0))
        assertEquals(1, c.frameIndexAt(200.0))
        assertEquals(2, c.frameIndexAt(599.999))
        assertEquals(2, c.frameIndexAt(600.0), "the last frame at the end")
        assertEquals(2, c.frameIndexAt(600.0 * 1000.0), "and far past it, with no wrap")

        assertEquals(false, c.isFinished(599.999))
        assertEquals(true, c.isFinished(600.0), "finished exactly at the range length")
        assertNull(c.audioPositionMs(600.0), "silent once finished")
        assertNull(c.audioPositionMs(1100.0))

        assertEquals(listOf(Step.Finish), s.step(600.0, 2, null))
        assertEquals(listOf(Step.Finish), s.step(601.0, 2, null), "a standing fact, not an edge")
        assertEquals(listOf(Step.ShowFrame(2), Step.Finish), s.step(600.0, 0, null))
        assertEquals(
            listOf(Step.ShowFrame(2), Step.Audio(null), Step.Finish),
            s.step(10_000.0, 0, 0.0),
            "a poll skipped ten seconds past the end: picture, silence command, standing fact",
        )
        assertEquals(Double.POSITIVE_INFINITY, c.nextChangeMs(600.0), "finished: never again")
        assertEquals(600.0 + FrameStepper.MAX_SLEEP_MS, s.nextWakeMs(600.0), "capped, exactly")
    }

    // ── 4. skipped polls ─────────────────────────────────────────────────────

    /**
     * A player that polls every 370 ms over ~100 ms frames skips whole frames every time — and
     * each answer is still exactly one ShowFrame naming the clock's own frame. Hand-enumerated:
     * 0 → 0, 370 → 2, 740 (140 in) → 0, 1110 (510 in) → 2, 1480 (280 in) → 1, 1850 (50 in) → 0.
     */
    @Test
    fun skippedPollsEmitOnlyTheClockAnswer() {
        val c = PlaybackClock(boardC, mode = PlayMode.LOOP)
        val s = FrameStepper(c)
        val polls = listOf(0.0, 370.0, 740.0, 1110.0, 1480.0, 1850.0)
        assertEquals(listOf(0, 2, 0, 2, 1, 0), polls.map { c.frameIndexAt(it) }, "independently enumerated")

        var shown = c.frameIndexAt(0.0)
        var audio = c.audioPositionMs(0.0)
        assertEquals(emptyList(), s.step(0.0, shown, audio), "at the start, nothing to do")
        for (t in polls.drop(1)) {
            val truth = c.frameIndexAt(t)
            val truthAudio = c.audioPositionMs(t)
            assertEquals(listOf(Step.ShowFrame(truth)), s.step(t, shown, truthAudio), "at $t")
            shown = truth
            audio = truthAudio
        }
    }

    /**
     * Skips across the two PING_PONG discontinuities: over the seam (550 → 750 lands on frame 0
     * of the next leg with the sound back in at 50 ms) and over the turn (650 → 1050 lands on
     * frame 2 with the sound at 350 ms). One ShowFrame plus one Audio each — no intermediate
     * frame is ever named.
     */
    @Test
    fun skippedPollsAcrossSeamAndTurn() {
        val c = PlaybackClock(boardC, mode = PlayMode.PING_PONG)
        val s = FrameStepper(c)
        assertEquals(2, c.frameIndexAt(550.0))
        assertEquals(550.0, c.audioPositionMs(550.0))
        assertEquals(
            listOf(Step.ShowFrame(0), Step.Audio(50.0)),
            s.step(750.0, 2, 550.0),
            "across the seam in one jump",
        )
        assertEquals(1, c.frameIndexAt(650.0))
        assertEquals(
            listOf(Step.ShowFrame(2), Step.Audio(350.0)),
            s.step(1050.0, 1, null),
            "across the turn in one jump",
        )
    }

    // ── 5. the wake chain ────────────────────────────────────────────────────

    /**
     * Following nextWakeMs from 0 visits every slot of the first cycle plus the turn into the
     * next: times [0, 200, 300, 550, 600, 601, 700, 900] showing [0, 1, 2, 2, 2, 1, 0, 1].
     * The 550 wake is FrameStepper.MAX_SLEEP_MS (250) capping the 300->600 hop: min(600, 300+250).
     * Strictly increasing, all finite, eight hops — a player that sleeps on wakes alone still
     * shows every frame, including the one-frame backward slot.
     */
    @Test
    fun nextWakeChainVisitsEverySlot() {
        val c = PlaybackClock(boardC, mode = PlayMode.PING_PONG)
        val s = FrameStepper(c)
        val times = ArrayList<Double>()
        times.add(0.0)
        repeat(7) { times.add(s.nextWakeMs(times.last())) }
        assertEquals(listOf(0.0, 200.0, 300.0, 550.0, 600.0, 601.0, 700.0, 900.0), times)
        assertEquals(listOf(0, 1, 2, 2, 2, 1, 0, 1), times.map { s.frameOnScreen(it) })
        for (i in 1 until times.size) {
            assertTrue(times[i] > times[i - 1], "the chain never stalls")
            assertTrue(times[i].isFinite(), "and never parks")
        }
    }

    // ── 6. audio commands, not sound ─────────────────────────────────────────

    /**
     * A Step.Audio is a command ("the sound must be HERE, or silent"), not playback: no bytes
     * are opened, no player is touched — Board carries no track at all. Proof in three parts:
     * twin schedules agree everywhere; a sub-range's commands sit at board-absolute positions;
     * and Audio(null) is a silence COMMAND distinct from the no-command empty list.
     */
    @Test
    fun audioCommandsCarrySchedulePositionsNotSound() {
        val c = PlaybackClock(boardC, mode = PlayMode.PING_PONG)
        val twin = PlaybackClock(boardOf(2, 1, 3, fps = 10f, id = "twin"), mode = PlayMode.PING_PONG)
        for (ms in 0..1400 step 37) {
            val t = ms.toDouble()
            assertEquals(twin.audioPositionMs(t), c.audioPositionMs(t), "same schedule, different board id, at $t")
        }

        val r = PlaybackClock(boardC, mode = PlayMode.LOOP, firstFrame = 1, lastFrame = 2)
        assertEquals(400.0, r.rangeMs, "frames 1..2 run 100 + 300 ms")
        assertEquals(200.0, r.audioPositionMs(0.0), "board-absolute: frame 1 starts 200 ms in")
        assertEquals(599.999, r.audioPositionMs(399.999))
        for (ms in 0..800 step 13) {
            val t = ms.toDouble()
            val f = r.frameIndexAt(t)
            assertTrue(f == 1 || f == 2, "sub-range shows only its own frames, at $t got $f")
            val a = r.audioPositionMs(t) ?: -1.0
            assertTrue(a >= 200.0 && a < 600.0, "commands stay inside the range's board span, at $t got $a")
        }

        val s = FrameStepper(c)
        assertEquals(
            listOf(Step.Audio(null)),
            s.step(600.0, 2, 599.0),
            "at the seam the picture is unchanged and going silent IS the command",
        )
        assertEquals(
            emptyList(),
            s.step(600.0, 2, null),
            "already silent at the seam: no command at all",
        )
        for (ms in 601..699 step 17) {
            assertNull(c.audioPositionMs(ms.toDouble()), "silent all the way back, at $ms")
        }
    }

    // ── 7. invalid inputs ────────────────────────────────────────────────────

    /**
     * Unplayable schedules are refused with AnimOps' own [DocException] surfacing through the
     * clock — no new exception type is invented here. Only the empty board is the clock's own
     * refusal ([IllegalArgumentException] from its require): a board with no frames is a legal
     * document and an illegal thing to play.
     */
    @Test
    fun invalidSchedulesAreRefusedWithTheScheduleException() {
        val canvas = boardC.copy(kind = BoardKind.CANVAS)
        assertFailsWith<DocException> { PlaybackClock(canvas) }
        assertFailsWith<DocException> { PlaybackClock(boardC.copy(fps = 0f)) }
        assertFailsWith<DocException> { PlaybackClock(boardC.copy(fps = 99f)) }
        assertFailsWith<DocException> { PlaybackClock(boardC.copy(fps = Float.NaN)) }
        assertFailsWith<DocException> { PlaybackClock(boardC.copy(fps = Float.POSITIVE_INFINITY)) }
        val zeroHold = boardC.copy(frames = listOf(Frame("f0", holdFrames = 0)))
        assertFailsWith<DocException> { PlaybackClock(zeroHold) }
        assertFailsWith<IllegalArgumentException> { PlaybackClock(boardC.copy(frames = emptyList())) }
    }

    // ── 8. speeds ────────────────────────────────────────────────────────────

    /**
     * The documented speed rule (KDoc: clamped to 0.1..4, non-finite plays at 1) on the new
     * schedule, pairing up the cases the existing suite never compares: negative speeds clamp
     * like zero, and both infinities play at 1 — positive infinity does NOT clamp to 4.
     */
    @Test
    fun nonFiniteAndNegativeSpeedsFollowTheDocumentedRule() {
        fun clock(speed: Float) = PlaybackClock(boardC, mode = PlayMode.LOOP, speed = speed)
        val one = clock(1f)
        val tenth = clock(0.1f)
        val four = clock(4f)
        val probes = listOf(0.0, 100.0, 250.0, 400.0, 1000.0, 3599.0)
        for (t in probes) {
            for (weird in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                assertEquals(one.frameIndexAt(t), clock(weird).frameIndexAt(t), "speed $weird at $t plays at 1")
                assertEquals(one.nextChangeMs(t), clock(weird).nextChangeMs(t), "speed $weird wake at $t plays at 1")
            }
            for (slow in listOf(0f, -2f)) {
                assertEquals(tenth.frameIndexAt(t), clock(slow).frameIndexAt(t), "speed $slow at $t plays at 0.1")
            }
            assertEquals(four.frameIndexAt(t), clock(99f).frameIndexAt(t), "speed 99 at $t plays at 4")
        }
        assertEquals(1, tenth.frameIndexAt(2000.0), "2000 wall ms at a tenth speed is 200 board ms: frame 1")
        assertEquals(1, four.frameIndexAt(50.0), "50 wall ms at quadruple speed is 200 board ms: frame 1")
    }

    // ── 9. ambiguity report ──────────────────────────────────────────────────

    /**
     * AMBIGUITY REPORT — JB-3.05a Questions §3, UNRESOLVED: inside an ONCE clock's last frame,
     * nextChangeMs answers the range end (600.0) although the frame is already the last one and
     * is unchanged AT that end. By the ruling's own one-frame-range logic the answer could
     * equally be positive infinity; the frontier has not ruled. This test DOCUMENTS the current
     * observable pair — answer 600.0, no change there — and must be revised, not preserved, when
     * the ruling lands. It does not endorse either answer.
     *
     * The LOOP half is decided and contrasted in the same test: every nextChangeMs there lands
     * on a genuinely different frame.
     */
    @Test
    fun documentedAmbiguityOnceLastFrameNextChange() {
        val o = PlaybackClock(boardC, mode = PlayMode.ONCE)
        val s = FrameStepper(o)
        assertEquals(600.0, o.nextChangeMs(350.0), "current answer from inside the last frame")
        assertEquals(2, o.frameIndexAt(350.0))
        assertEquals(2, o.frameIndexAt(600.0), "…where the frame is unchanged")
        assertEquals(600.0, s.nextWakeMs(350.0), "the stepper repeats the clock's answer uncapped")

        val c = PlaybackClock(boardC, mode = PlayMode.LOOP)
        for (t in listOf(100.0, 250.0, 450.0)) {
            val w = c.nextChangeMs(t)
            assertTrue(
                c.frameIndexAt(w) != c.frameIndexAt(t),
                "LOOP at $t wakes at $w on a genuinely different frame",
            )
        }
    }
}

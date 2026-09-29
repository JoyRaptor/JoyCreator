package cc.joycreator.joybrush.core.anim

import cc.joycreator.joybrush.core.doc.AnimOps
import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.Frame
import cc.joycreator.joybrush.core.doc.RectPx
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The frame stepper, checked against boundaries worked out from the BOARD rather than from the
 * clock, and asked at every boundary three ways: AT it, a hair before it and a hair after it.
 *
 * THE THREE THINGS TO KNOW BEFORE WRITING A TEST HERE.
 *
 * 1. **Every boundary is derived by indexing `AnimOps`, never by accumulating `hold * 1000 / fps` a
 *    second time.** `2 * (1000.0 / 12.0)` is not bit-identical to `2000.0 / 12.0`, and a boundary one
 *    ulp out lands on the wrong side of the only comparison that decides anything.
 * 2. **`frameStartsMs` returns one start per frame and no trailing end.** The end of a board is
 *    `totalDurationMs`, and [edge] is the accessor that knows that. A test that indexes
 *    `starts[frameCount]` gets an IndexOutOfBounds rather than a wrong answer, which is the good case.
 * 3. **A test that asserts a frame is never emitted at a backward boundary is worthless unless it
 *    also asks just AFTER that boundary**, because a stepper that draws the wrong frame a
 *    thousandth of a second late passes the first assertion perfectly. That is why
 *    [atABoundaryJustBeforeItAndJustAfterIt] is the test that carries the most weight in this file.
 *
 * NO TOLERANCE APPEARS ON ANY EMITTED VALUE, and the absence is deliberate and checked: every
 * comparison the stepper makes is against a number the clock produced, exactly.
 */
class FrameStepperTest {

    // ── the two boards ──────────────────────────────────────────────────────────

    private fun boardOf(vararg holds: Int, fps: Float = 12f, id: String = "b"): Board = Board(
        id = id,
        name = "Anim",
        kind = BoardKind.ANIMATION,
        rect = RectPx(0, 0, 256, 256),
        fps = fps,
        frames = holds.mapIndexed { i, hold -> Frame("f$i", holdFrames = hold) },
    )

    /** A: the four-frame board `PlaybackClockTest` uses — 12 fps, holds 1, 2, 1, 3. */
    private val boardA = boardOf(1, 2, 1, 3)

    /**
     * B: six EQUAL frames at **10** fps, and 10 is the whole point of the choice.
     *
     * One tick is `1 * 1000.0 / 10.0` = exactly 100.0 ms, so every boundary in a cycle is a WHOLE
     * millisecond and a 1 ms walk steps exactly onto all of them rather than landing an ulp to one
     * side. Everything below is derived from `AnimOps` and is exact:
     *
     * ```
     * frameStartsMs      0  100  200  300  400  500
     * totalDurationMs    600.0                              so rangeMs = 600.0
     * backSpanMs         rangeStarts[5] - rangeStarts[1] = 500 - 100 = 400
     * cycleMs            rangeMs + backSpanMs = 600 + 400 = 1000
     * falling edges      u[k] = rangeMs + (s[5] - s[k]):
     *                      u[5] = 600   THE SEAM, still frame 5
     *                      u[4] = 700   interior falling edge #1, still frame 4
     *                      u[3] = 800   #2, still frame 3
     *                      u[2] = 900   #3, still frame 2
     *                      u[1] = 1000  THE TURN, and the frame really IS 0 there
     * ```
     *
     * So one cycle has ten boundaries — five rising and four falling — and the four falling ones
     * are the only instants in the cycle at which `nextChangeMs(t)` answers `t` bit for bit.
     */
    private val boardB = boardOf(1, 1, 1, 1, 1, 1, fps = 10f, id = "six")

    /** Boundary [k] of [of]: a frame start, or the board's end when [k] is one past the last. */
    private fun edge(of: Board, k: Int): Double {
        val s = AnimOps.frameStartsMs(of)
        return if (k < s.size) s[k] else AnimOps.totalDurationMs(of)
    }

    private fun bA(k: Int): Double = edge(boardA, k)

    private fun bB(k: Int): Double = edge(boardB, k)

    private fun clockA(
        mode: PlayMode = PlayMode.LOOP,
        first: Int = 0,
        last: Int = 3,
        speed: Float = 1f,
    ) = PlaybackClock(boardA, mode = mode, firstFrame = first, lastFrame = last, speed = speed)

    private fun clockB(mode: PlayMode = PlayMode.LOOP, speed: Float = 1f) =
        PlaybackClock(boardB, mode = mode, speed = speed)

    private fun clockOf(
        of: Board,
        mode: PlayMode = PlayMode.LOOP,
        first: Int = 0,
        last: Int = of.frames.size - 1,
        speed: Float = 1f,
    ) = PlaybackClock(of, mode = mode, firstFrame = first, lastFrame = last, speed = speed)

    /**
     * How far inside a slot a probe may sit and still be unambiguously inside it: a thousandth of a
     * millisecond against a shortest possible frame of 16.7 ms, and against a shortest frame in
     * THIS file of 83.3 ms (board A's one-tick frame). Far larger than the ulp of any boundary here
     * (1.1e-13 at 833 ms), far smaller than any frame.
     */
    private val AWAY = 0.001

    // ── 1. the frame it was told is up is never emitted ─────────────────────────

    /**
     * Structure, not a count: at every edge of board A — all four starts AND the board's own end,
     * which `frameStartsMs` does not hold — and for every frame of the board, the step is asked
     * with each of those frames claimed to be on screen, and never comes back asking to draw it.
     *
     * Asked twice per pair, once with the sound silent and once with it somewhere, so the two
     * arguments are independent in what they can suppress.
     */
    @Test
    fun aFrameAlreadyOnScreenIsNeverEmittedAgain() {
        val c = clockA()
        val s = FrameStepper(c)
        var pairs = 0
        for (k in 0..boardA.frames.size) {
            val e = bA(k)
            val truth = c.frameIndexAt(e)
            for (i in 0..boardA.frames.size - 1) {
                for (at in listOf(null, 0.0)) {
                    for (step in s.step(e, i, at)) {
                        if (step is Step.ShowFrame) {
                            assertTrue(step.index != i, "at edge $e with frame $i up it said ShowFrame($i)")
                            // And when it does speak, it names the CLOCK's answer, not one of its own.
                            assertEquals(truth, step.index, "at edge $e the clock says frame $truth")
                        }
                    }
                }
                pairs++
            }
        }
        assertEquals(20, pairs, "five edges times four frames")
    }

    /**
     * The same claim over 2 000 pseudo-random triples, with HALF of them claiming the frame the
     * clock's own answer would put up. See the comment inside: the random half alone cannot bite.
     */
    @Test
    fun theStepperCannotEmitTheFrameItWasToldIsUp() {
        val rng = Lcg(0x5EED_0003L)
        val clocks = listOf(
            clockA(),
            clockA(PlayMode.PING_PONG),
            clockB(PlayMode.PING_PONG),
            clockB(PlayMode.ONCE),
        )
        var triples = 0
        var coincident = 0
        for (round in 0 until 500) {
            for (c in clocks) {
                val s = FrameStepper(c)
                val t = rng.between(0.0, 2_000.0)
                val a = rng.audio()
                // Every fourth probe claims the frame the clock would put up, and that is the ONLY
                // way this assertion can bite at all. A stepper that emits unconditionally is then
                // asked to re-emit the very frame it was told is already there.
                //
                // It was built from purely random indices first, and the mutation proof is what found
                // it: deleting the guard in Decision 1 left THIS test green. A random index almost
                // never coincides with the clock's own answer — four frames, four million probes, a
                // coin with a quarter in it — so a test made only of random indices would have
                // passed against the exact bug it exists to catch.
                val i = if (round % 4 == 0) c.frameIndexAt(t) else rng.int()
                if (i == c.frameIndexAt(t)) coincident++
                for (step in s.step(t, i, a)) {
                    if (step is Step.ShowFrame) {
                        assertTrue(step.index != i, "asked at $t with frame $i up it said ShowFrame($i)")
                    }
                }
                triples++
            }
        }
        assertEquals(2_000, triples, "500 rounds of four clocks")
        assertEquals(500, coincident, "one coincidence in four, by construction")
    }

    // ── 2. the test this row exists for ─────────────────────────────────────────

    /**
     * A whole ping-pong cycle walked in 1 ms steps, carrying the picture and the sound forward the
     * way a player does, and pinning four separate things.
     *
     * ## Why ten emissions, and why 601 sound instructions
     *
     * `n = 6` frames, one cycle `cycleMs = rangeMs + backSpanMs = 600 + (500 - 100) = 1000` ms, and
     * the walk is every whole millisecond from 0 to 1000 inclusive — 1 001 steps.
     *
     *  - **The forward leg shows all `n = 6` frames**, in the slots starting at `0, 100, 200, 300,
     *    400, 500`, and a rising boundary draws AT the boundary: emissions at `100 … 500`.
     *  - **The backward leg replays the range's INTERIOR in reverse**, so it shows `n - 2 = 4` new
     *    frames over the half-open slots `(600, 700]`, `(700, 800]`, `(800, 900]`, `(900, 1000]`.
     *    Half-open on the left because at a falling edge the OUTGOING frame is still the one up, so
     *    the change lands **one step after** the edge: emissions at `601, 701, 801, 901`, and nothing
     *    at all at `600, 700, 800, 900`.
     *  - **The turn at 1000 wraps to frame 0**, and that is the eleventh emission — the leading `0`
     *    of `0 1 2 3 4 5 4 3 2 1 0 …` and the last entry of the list below, which is the first
     *    emission of the NEXT cycle.
     *
     * So a full cycle emits `6 + 4 = 10` times, which is `2n - 2`, and **not once per step**: 1 001
     * steps, 10 of which draw. A stepper that emitted on every call would pass a test that only
     * looked at the middle of a frame, and its symptom in the field is a phone at 100 % battery
     * playing a two-frame animation.
     *
     * The sound is carried the same way and is loud by design: the position is a DIFFERENT number at
     * every whole millisecond of the forward leg, so it moves once per step, 600 times, then once
     * more at the seam to go silent, and once more at the turn — which the walk reaches, because the
     * walk ends ON `cycleMs` — to come back in at the board's own zero. `600 + 1 + 1 = 602`. That
     * is arithmetic about "the sound moves continuously", not a fact about this stepper, and it is
     * asserted so that the sound half cannot quietly stop being emitted at all.
     */
    @Test
    fun theStepperDoesNotSpinAtABackwardBoundary() {
        val c = clockB(PlayMode.PING_PONG)
        val s = FrameStepper(c)
        val cycle = c.rangeMs + (bB(5) - bB(1))
        assertEquals(1000.0, cycle, "rangeMs + backSpanMs, out of the board's own starts")

        // The four falling edges, derived: u[k] = rangeMs + (s[n - 1] - s[k]) for k = n - 1 down to
        // 2. u[1] is the turn and is NOT one of them, because at the turn the frame HAS changed.
        val falling = (2..5).map { c.rangeMs + (bB(5) - bB(it)) }.sorted()
        assertEquals(listOf(600.0, 700.0, 800.0, 900.0), falling, "the seam and three interior edges")
        assertEquals(4, falling.size, "one seam and three interior falling edges, not one number")

        var shown = 0
        var audio: Double? = null
        val shownEmissions = ArrayList<Pair<Int, Double>>()
        val audioEmissions = ArrayList<Pair<Double?, Double>>()
        var steps = 0

        for (ms in 0..1000) {
            val t = ms.toDouble()
            steps++
            val out = s.step(t, shown, audio)
            for (step in out) {
                when (step) {
                    is Step.ShowFrame -> {
                        shown = step.index
                        shownEmissions.add(step.index to t)
                    }
                    is Step.Audio -> {
                        audio = step.boardMs
                        audioEmissions.add(step.boardMs to t)
                    }
                    is Step.Finish -> fail("PING_PONG must never finish, and $t asked")
                }
            }
            // The stepper's whole claim, restated: whatever it emitted, the picture is now the
            // clock's own answer. Emit too little and this is where it goes red; emit too much and
            // shownEmissions below is the wrong length.
            assertEquals(c.frameIndexAt(t), shown, "the player is showing the wrong frame at $t")
        }
        assertEquals(1_001, steps, "one whole cycle in 1 ms steps, both ends included")

        // 1. THE EMISSION LIST — the number the review corrected, with its arithmetic in the header.
        assertEquals(listOf(1, 2, 3, 4, 5, 4, 3, 2, 1, 0), shownEmissions.map { it.first }, "2n - 2 of them")
        assertEquals(10, shownEmissions.size, "2n - 2 for n = 6")
        assertEquals(
            listOf(100.0, 200.0, 300.0, 400.0, 500.0, 601.0, 701.0, 801.0, 901.0, 1000.0),
            shownEmissions.map { it.second },
            "a rising edge draws AT it and a falling edge draws one step after it",
        )

        // 2. THE VISIBLE SEQUENCE, sampled at slot MIDPOINTS — the other spelling of the same
        // fact, and the only place in a slot where no rounding can be argued about.
        val midpoints = (0..9).map { it * 100.0 + 50.0 }
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 4, 3, 2, 1), midpoints.map { c.frameIndexAt(it) }, "ten slots")

        // 3. THE SURPRISING HALF, asserted positively: at the four exact falling edges the frame has
        // NOT changed yet, so with that frame on screen the stepper says nothing at all.
        for (u in falling) {
            assertEquals(c.frameIndexAt(u), c.frameIndexAt(u - AWAY), "the outgoing frame at $u")
            val out = s.step(u, c.frameIndexAt(u), c.audioPositionMs(u))
            assertTrue(out.isEmpty(), "at the falling edge $u nothing has changed yet, but it said $out")
        }

        // 4. THE ONE THAT FAILS WITHOUT DECISION 7. At every one of the 1 001 steps the answer is
        // strictly later than now; at the four falling edges the clock's own answer IS the elapsed,
        // bit for bit, so a `min(next, t + MAX)` alone hands the player its own timestamp and a
        // millisecond clock loops on it. The floor is exactly there and nowhere else.
        for (ms in 0..1000) {
            val t = ms.toDouble()
            assertTrue(s.nextWakeMs(t) > t, "at $t it said wake at ${s.nextWakeMs(t)}")
        }
        for (u in falling) {
            assertEquals(u + FrameStepper.MIN_WAKE_MS, s.nextWakeMs(u), "the floor at the edge $u")
        }
        for (v in listOf(0.0, 100.0, 500.0, 1000.0)) {
            assertEquals(c.nextChangeMs(v), s.nextWakeMs(v), "a rising edge is the clock's own answer")
        }

        // 5. AND THE SOUND, carried the same way, with its own arithmetic in the header and one
        //    extra instruction here that the header's count does not include: the walk ENDS on the
        //    turn at `cycleMs = 1000`, and the turn is the first instant of the next forward leg,
        //    so the sound comes back IN at the board's own zero. Silence at the seam is therefore
        //    not the last thing that happens in a cycle, and a test that said it was would be
        //    describing a cycle that never turns.
        assertEquals(
            (0..600).map { it.toDouble() } + 1000.0,
            audioEmissions.map { it.second },
            "once per ms, then the seam, then the turn",
        )
        assertEquals(
            602,
            audioEmissions.size,
            "600 forward-leg ms, the seam going silent, and the turn bringing it back: 602",
        )
        for (i in 0..599) {
            assertEquals(i.toDouble(), audioEmissions[i].first, "the sound is at the board time on the way out")
        }
        assertEquals(null, audioEmissions[600].first, "the seam at 600 is exactly where it goes out")
        assertEquals(0.0, audioEmissions[601].first, "and the turn at 1000 is where it comes back in")
        assertEquals(0.0, audio, "which is where the walk ends, having passed the turn")
        // And the whole of the silent stretch in between: nothing about the sound between 600 and
        // 1000, which is where the spec's own list of "silent instants" has to stop.
        assertEquals(null, c.audioPositionMs(650.0), "out on the way back")
        assertEquals(0.0, c.audioPositionMs(1000.0) ?: -1.0, "and back in at the turn, at the board's zero")
    }

    // ── 3. purity ──────────────────────────────────────────────────────────────

    /**
     * No hidden state. 5 000 triples across six clocks — including a finished ONCE, which is the
     * clock a `private var finished` flag would corrupt — each asked twice, and the two lists equal.
     *
     * A second, freshly built stepper over the same clock is asked as well, because "this object
     * holds no field" is a statement about fields rather than about answers, and an object that had
     * been asked already would answer differently from one that had not.
     */
    @Test
    fun stepIsAFunctionOfItsArgumentsAlone() {
        val rng = Lcg(0x5EED_0004L)
        val clocks = listOf(
            clockA(),
            clockA(PlayMode.PING_PONG),
            clockA(PlayMode.ONCE, speed = 0.5f),
            clockB(PlayMode.PING_PONG),
            clockB(PlayMode.ONCE, speed = 2.5f),
            clockA(first = 1, last = 2),
        )
        var triples = 0
        while (triples < 5_000) {
            for (c in clocks) {
                val s = FrameStepper(c)
                val t = rng.between(0.0, 3_000.0)
                val i = rng.int()
                val a = rng.audio()
                val once = s.step(t, i, a)
                val twice = s.step(t, i, a)
                assertEquals(once, twice, "step($t, $i, $a) said $once then $twice")
                assertEquals(once, FrameStepper(c).step(t, i, a), "a brand new stepper disagreed")
                triples++
            }
        }
        assertTrue(triples >= 5_000, "only $triples triples")
        // And the flag Decision 2 forbids is asserted to be impossible to smuggle in: on a finished
        // ONCE clock the SAME call is the SAME list, which a `finished` field cannot manage, because
        // the second call would return an empty list where the first returned [Finish].
        val done = FrameStepper(clockA(PlayMode.ONCE))
        val past = done.clock.rangeMs
        assertEquals(listOf<Step>(Step.Finish), done.step(past, 3, null))
        assertEquals(listOf<Step>(Step.Finish), done.step(past, 3, null), "asked twice, and it is a standing fact")
    }

    // ── 4. order, and the empty list ───────────────────────────────────────────

    /**
     * The picture is drawn before the sound is moved, asserted as an INDEX comparison rather than
     * by writing the list out as a string, so that a reordered pair fails and a reshuffled spelling
     * of the same order does not. 100.0 is a rising boundary on board B, where the frame AND the
     * sound both change: the frame to 1, the sound to 100.0, the board time at that instant.
     */
    @Test
    fun thePictureIsDrawnBeforeTheSoundIsMoved() {
        val c = clockB()
        val s = FrameStepper(c)
        val out = s.step(100.0, 0, 99.0)
        assertEquals(2, out.size, "a frame and a sound and nothing else, but got $out")
        val frame = out.indexOfFirst { it is Step.ShowFrame }
        val sound = out.indexOfFirst { it is Step.Audio }
        assertTrue(frame >= 0 && sound >= 0, "both must fire at 100.0, but got $out")
        assertTrue(frame < sound, "the picture must be drawn before the sound is moved, but got $out")
        assertEquals(Step.ShowFrame(1), out[frame])
        assertEquals(Step.Audio(100.0), out[sound])
    }

    /**
     * 50.0 is the middle of frame 0's 100 ms slot and the sound is already where the clock says it
     * should be: the overwhelmingly common case, met on nearly every Handler tick of a board of
     * long holds. It is an EMPTY LIST.
     *
     * `Step.Nothing` is DELETED, not deprecated, and this file cannot name it — the type does not
     * exist in the package for the reference to resolve, so a reference to it is a compile error and
     * that is the proof. There is no reflection in `commonMain` to ask the question any other way.
     */
    @Test
    fun anUnchangedMomentReturnsAnEmptyList() {
        val s = FrameStepper(clockB())
        val out = s.step(50.0, 0, 50.0)
        assertEquals(emptyList(), out, "nothing changed, so there is nothing to do")
        assertEquals(0, out.size, "an empty list, not a singleton saying so")
        // And the same for a PING_PONG on its backward leg, where the sound is out and the frame is
        // where the caller believes it to be — the second commonest answer in the whole contract.
        val pong = FrameStepper(clockB(PlayMode.PING_PONG))
        assertEquals(emptyList(), pong.step(650.0, pong.frameOnScreen(650.0), null))
    }

    // ── 5. the sound: the corrected formula, and no epsilon ────────────────────

    /**
     * On the forward leg the emitted position is `rangeStartMs + cyclePos` and NOT
     * `rangeStartMs + (elapsed * speed mod rangeMs)`, which was Decision 5 as first written in
     * JB-3.05a and was wrong.
     *
     * The expected value is worked out from the **BOARD**, not read out of the clock: `rangeStartMs`
     * is a private field of `PlaybackClock`, and a test that read it would be asserting the clock
     * against itself. On this board `edge(boardB, 0)` is 0.0, so the expectation reduces to
     * `t mod cycleMs` — and the cycle is the one number both formulas have to agree on to differ.
     *
     * The two agree EXACTLY on the first cycle's forward leg and nowhere else: with `rangeMs = 600`
     * and `cycleMs = 1000`, at `t = 1100` the right answer is `1100 mod 1000 = 100.0` and the wrong
     * one is `1100 mod 600 = 500.0` — half a second of picture and half a second of sound apart,
     * which is why the desync only shows up after the first loop.
     */
    @Test
    fun theSoundIsTheCorrectedFormulaAndNotTheModulo() {
        val c = clockB(PlayMode.PING_PONG)
        val s = FrameStepper(c)
        val cycle = c.rangeMs + (bB(5) - bB(1))
        assertEquals(1000.0, cycle)

        // The second cycle, whole milliseconds only, and EXACTLY: every one of them is a whole
        // number that `fmod` returns exactly, so there is no tolerance anywhere on this assertion.
        for (ms in 1000 until 1600) {
            val t = ms.toDouble()
            val out = s.step(t, -1, null).filterIsInstance<Step.Audio>()
            assertEquals(1, out.size, "the forward leg is never silent, at $t")
            assertEquals(edge(boardB, 0) + (t % cycle), out[0].boardMs, "at $t, exactly")
            assertTrue(
                edge(boardB, 0) + (t % cycle) != edge(boardB, 0) + (t % c.rangeMs),
                "the two formulas agree at $t, which is the bug this test exists for",
            )
        }
        assertEquals(100.0, s.step(1100.0, -1, null).filterIsInstance<Step.Audio>()[0].boardMs, "at 1100")
        assertEquals(500.0, edge(boardB, 0) + (1100.0 % c.rangeMs), "and the wrong formula, spelled out")
        for (ms in 0 until 600) {
            val t = ms.toDouble()
            assertEquals(t, s.step(t, -1, null).filterIsInstance<Step.Audio>()[0].boardMs, "first cycle, where both agree")
        }

        // On the backward leg the sound is OUT, from the seam onward, and stays out until the TURN.
        // Asked with the sound already silent, so "no instruction" is what is pinned.
        //
        // NOTE WHAT IS NOT IN THIS LIST: `t = 1000`. The turn is not on the backward leg — it is the
        // first instant of the NEXT forward leg, where `cyclePos` wraps to 0 and the sound comes back
        // in at the board's own zero. The spec's test 7 lists 1000 among the silent instants; the
        // landed clock answers 0.0 there, and `PlaybackClock.audioPositionMs` is pinned by
        // `PlaybackClockTest` for the same reason it is here. The derivation is one line:
        // `cyclePos = 1000 % 1000 = 0`, and `0 >= rangeMs` is false, so the sound is out.
        for (t in listOf(600.0, 650.0, 900.0, 999.0)) {
            assertEquals(null, c.audioPositionMs(t), "the clock is silent at $t")
            assertTrue(
                s.step(t, c.frameIndexAt(t), null).none { it is Step.Audio },
                "the stepper must say nothing about a sound that is already out at $t",
            )
        }
        // And the turn brings it back, which is an INSTRUCTION when the sound was out.
        assertEquals(Step.Audio(0.0), s.step(1000.0, 0, null).single(), "the turn is where it comes back")
        // Going silent at the seam is an instruction too, which is the half that would otherwise let
        // a player keep the last sound playing for ever.
        assertEquals(listOf<Step>(Step.Audio(null)), s.step(600.0, 5, 599.0), "the seam is an instruction too")
    }

    /** No `Audio` at all when the sound is already where the clock says, across both legs and three speeds. */
    @Test
    fun anUnchangedSoundPositionEmitsNothing() {
        for (speed in listOf(0.5f, 1f, 2.5f)) {
            for (mode in listOf(PlayMode.LOOP, PlayMode.PING_PONG, PlayMode.ONCE)) {
                val c = clockB(mode, speed)
                val s = FrameStepper(c)
                for (ms in 0 until 1000) {
                    val t = ms * 0.5
                    val out = s.step(t, c.frameIndexAt(t), c.audioPositionMs(t))
                    assertTrue(out.none { it is Step.Audio }, "$mode at speed $speed, t=$t: the sound was already right, but it said $out")
                    // The list is empty outright until the ONCE clock runs out, and after that it
                    // carries the standing `Finish` and nothing else — the sound is silent then
                    // because the sound is silent, and saying so again would be noise.
                    if (!c.isFinished(t)) {
                        assertTrue(out.isEmpty(), "$mode at speed $speed, t=$t: nothing happened at all, but it said $out")
                    } else {
                        assertEquals(listOf<Step>(Step.Finish), out, "$mode at speed $speed, t=$t: finished and nothing else")
                    }
                }
            }
        }
        // The comparison is `==` and NOT a tolerance, and this is the half that says so. A sound
        // position a BILLIONTH of a millisecond from the clock's answer is a real difference and is
        // still emitted. 1e-9 ms is a nanosecond, a million times finer than the millisecond a
        // MediaPlayer can seek to, so no device could act on it — which is exactly the point:
        // whether a 0.4 ms difference is worth a `seekTo` is the DEVICE's decision, and the only way
        // to be wrong about it HERE is to swallow a difference that is really there.
        val s = FrameStepper(clockB())
        assertEquals(listOf<Step>(Step.Audio(50.0)), s.step(50.0, 0, 50.0 + 1.0e-9), "a nanosecond is a change")
    }

    /**
     * Every emitted sound position IS the clock's own double — no epsilon, no smoothing, no
     * interpolation anywhere in the path, across both legs, three speeds, a sub-range and ONCE.
     *
     * There is **no tolerance argument on the assertion below**, and that is the assertion. Put one
     * in and this stops being a claim about identity and becomes a claim about a bound.
     */
    @Test
    fun theEmittedAudioIsBitIdenticalToTheClock() {
        var carried = 0
        for (speed in listOf(0.5f, 1f, 2.5f)) {
            val clocks = listOf(
                clockB(PlayMode.PING_PONG, speed),
                clockB(PlayMode.LOOP, speed),
                clockA(PlayMode.ONCE, 1, 3, speed),
                clockA(PlayMode.PING_PONG, 2, 3, speed),
            )
            for (c in clocks) {
                val s = FrameStepper(c)
                for (ms in 0 until 750) {
                    val t = ms * 0.5
                    for (step in s.step(t, -1, null)) {
                        if (step is Step.Audio) {
                            assertEquals(c.audioPositionMs(t), step.boardMs, "at $t, speed $speed, exactly")
                            carried++
                        }
                    }
                }
            }
        }
        assertTrue(carried > 3_000, "only $carried probes carried a sound instruction")
    }

    // ── 6. Finish is level-triggered ───────────────────────────────────────────

    /**
     * `Finish` is a STANDING FACT, not an edge, and this row does not make it fire once.
     * `PlaybackClock.isFinished(t)` is a pure function of `t`, so asking past the end of an ONCE
     * clock returns `[Finish]` on **every** call, for ever. "Stop on the first `Finish`" is the
     * transport's job, and it is not this row's and not in its owner area.
     *
     * This test therefore does NOT assert that a second call comes back empty, because no pure
     * function of `(t, i, a)` can manage that: the only way to pass such a test is a hidden field,
     * which Decision 2 forbids and which `stepIsAFunctionOfItsArgumentsAlone` would catch.
     */
    @Test
    fun finishIsAStandingFactAndOnlyForOnce() {
        val c = clockA(PlayMode.ONCE)
        val s = FrameStepper(c)
        val past = c.rangeMs
        assertEquals(true, c.isFinished(past), "the end of the range is finished")
        assertEquals(false, c.isFinished(past - 1.0), "and a millisecond before it is not")

        // With the last frame already up, the standing fact is ALL of it.
        assertEquals(listOf<Step>(Step.Finish), s.step(past, 3, null))
        // A second call at a DIFFERENT instant says exactly the same thing. That is the whole of
        // Decision 4, and it is why this is a list that repeats rather than an edge.
        assertEquals(listOf<Step>(Step.Finish), s.step(past + 1.0, 3, null))
        // A caller whose picture is not the last yet is told BOTH, in that order: the picture
        // before the fact that there is no more picture.
        assertEquals(listOf<Step>(Step.ShowFrame(3), Step.Finish), s.step(past, 0, null))
        // An hour past the end is the same fact.
        assertEquals(listOf<Step>(Step.Finish), s.step(past * 1000.0, 3, null))
        // A time that cannot be placed on a timeline is the BEGINNING, not the end, and says so:
        // `cleanElapsed` puts NaN at 0.0, where the clock shows frame 0 and has not finished. So
        // the answer is the step at zero, `Finish` included in the negative.
        assertEquals(s.step(0.0, 3, null), s.step(Double.NaN, 3, null), "NaN is the beginning, not the end")
        assertEquals(listOf<Step>(Step.ShowFrame(0), Step.Audio(0.0)), s.step(Double.NaN, 3, null))
        // The sound goes out on the way, and that instruction is emitted too.
        assertEquals(listOf<Step>(Step.Audio(null), Step.Finish), s.step(past, 3, 10.0))

        // LOOP and PING_PONG never finish, an hour in or otherwise.
        for (mode in listOf(PlayMode.LOOP, PlayMode.PING_PONG)) {
            val other = FrameStepper(clockA(mode))
            for (t in listOf(0.0, 500.0, 583.0, 3_600_001.0)) {
                val out = other.step(t, other.frameOnScreen(t), other.clock.audioPositionMs(t))
                assertTrue(out.none { it is Step.Finish }, "$mode finished at $t, which is $out")
            }
        }
    }

    /** Past the end of an ONCE clock the last frame stays up, and stays up, and never wraps. */
    @Test
    fun afterFinishOnlyTheLastFrameIsUp() {
        val c = clockA(PlayMode.ONCE)
        val s = FrameStepper(c)
        val past = c.rangeMs
        for (k in 0..1000) {
            val t = past * (1 + k) // the end, and a thousand multiples of it out
            assertEquals(3, s.frameOnScreen(t), "at $t")
            assertEquals(c.frameIndexAt(t), s.frameOnScreen(t), "and it is the clock's own answer")
        }
    }

    // ── 7. nextWakeMs ──────────────────────────────────────────────────────────

    /**
     * THE INVARIANT: `nextWakeMs(t) > t` at every `t` that can be put on a timeline, and `100.0`
     * at every `t` that cannot.
     *
     * Three clocks, a 1 ms walk to 3 000 ms (which on board B's ping-pong is three whole cycles and
     * therefore TWELVE exact falling edges), 2 000 pseudo-random `t`, and the three values
     * `cleanElapsed` puts at zero.
     *
     * The last of those is the one that must be there. Every comparison against `NaN` is false, so a
     * `nextWakeMs` that compared before normalising would answer `NaN` — and `NaN > t` is false, so
     * the invariant above would fail SILENTLY on a value that looks like a comparison working. The
     * VALUE is asserted, not merely that the answer is finite.
     *
     * Non-vacuous: delete the `else -> t + MIN_WAKE_MS` branch and this is the test that goes red,
     * at 600, 700, 800 and 900 in each PING_PONG cycle, and nowhere else.
     */
    @Test
    fun nextWakeIsAlwaysStrictlyAfterNow() {
        val rng = Lcg(0x5EED_000CL)
        for (mode in listOf(PlayMode.LOOP, PlayMode.PING_PONG, PlayMode.ONCE)) {
            val s = FrameStepper(clockB(mode))
            for (ms in 0..3000) {
                val t = ms.toDouble()
                val wake = s.nextWakeMs(t)
                assertTrue(wake > t, "$mode at $t it said wake at $wake")
                assertTrue(wake.isFinite(), "$mode at $t it said wake at $wake")
            }
            for (k in 0 until 2000) {
                val t = rng.between(0.0, 4_000.0)
                val wake = s.nextWakeMs(t)
                assertTrue(wake > t, "$mode at a random $t it said wake at $wake")
                assertTrue(!wake.isNaN(), "$mode at a random $t it said wake at NaN")
            }
        }
        // The three values that cannot be placed on a timeline. The clock's own private
        // `cleanElapsed` turns every one of them into 0.0, so on board B the answer is the first
        // boundary from zero — one whole tick, 100.0 — for each of them, in all three modes.
        for (mode in listOf(PlayMode.LOOP, PlayMode.PING_PONG, PlayMode.ONCE)) {
            val s = FrameStepper(clockB(mode))
            for (impossible in listOf(Double.NaN, -1.0, Double.POSITIVE_INFINITY)) {
                val wake = s.nextWakeMs(impossible)
                assertEquals(100.0, wake, "$mode at $impossible")
                assertTrue(!wake.isNaN(), "$mode at $impossible is NaN, which compares false to everything")
            }
            // And the same three asked of `step` are the step at t = 0, said out loud rather than
            // left implied: NaN, a negative and an infinite elapsed are all the beginning.
            val atZero = s.step(0.0, 1, null)
            assertEquals(listOf<Step>(Step.ShowFrame(0), Step.Audio(0.0)), atZero, "the step at zero")
            for (impossible in listOf(Double.NaN, -1.0, Double.POSITIVE_INFINITY)) {
                assertEquals(atZero, s.step(impossible, 1, null), "$mode at $impossible")
            }
        }
        // The floor itself, and the shape of the answer: nowhere does the floor exceed the cap, and
        // nowhere does the cap exceed the floor, so the two can never be confused for one another.
        assertTrue(FrameStepper.MIN_WAKE_MS < FrameStepper.MAX_SLEEP_MS, "1 ms and 250 ms")
    }

    /**
     * A one-frame range cannot change, and a finished ONCE clock will not: both answer
     * `Double.POSITIVE_INFINITY`, and a player handed `Infinity` as a Handler delay would sleep for
     * ever — the one thing a stop button has to survive. Both are answered `t + MAX_SLEEP_MS`
     * exactly, not merely "in finite time".
     */
    @Test
    fun nextWakeNeverParksTheHandlerForever() {
        val one = FrameStepper(clockA(first = 2, last = 2))
        assertEquals(2, one.clock.frameIndexAt(0.0), "the one frame of the range")
        for (t in listOf(0.0, 1.0, 83.5, 583.0, 3_600_000.0)) {
            assertEquals(Double.POSITIVE_INFINITY, one.clock.nextChangeMs(t), "a one-frame range at $t")
            assertEquals(t + FrameStepper.MAX_SLEEP_MS, one.nextWakeMs(t), "a one-frame range at $t")
        }
        val done = FrameStepper(clockA(PlayMode.ONCE))
        val past = done.clock.rangeMs
        for (t in listOf(past, past + 1.0, past * 1000.0)) {
            assertEquals(Double.POSITIVE_INFINITY, done.clock.nextChangeMs(t), "a finished ONCE at $t")
            assertEquals(t + FrameStepper.MAX_SLEEP_MS, done.nextWakeMs(t), "a finished ONCE at $t")
        }
        // 250 is `CanvasGestures.TAP_MS`, the app's own tap threshold, in an androidkit class a
        // commonTest cannot import — so the number is asserted here and the link is the comment.
        assertEquals(250.0, FrameStepper.MAX_SLEEP_MS, "the app's own tap threshold")
        assertEquals(1.0, FrameStepper.MIN_WAKE_MS, "one tick of the clock a Handler delay is measured in")
    }

    /**
     * Inside a short frame the answer IS the clock's answer, the same double, and inside a frame
     * LONGER than 250 ms it is exactly `t + 250`.
     *
     * Board A cannot show the cap at all: its longest frame is three ticks at 12 fps, which is
     * exactly 250 ms, so from anywhere inside that frame the next change is never more than 250 ms
     * away. The cap is therefore exercised on a board with a ten-tick frame, and its boundary is
     * derived the same way as everything else: ten ticks at 12 fps is `10 * 1000.0 / 12.0`, and
     * frame 1 of that board begins at `1000.0 / 12.0` and ends at `11000.0 / 12.0`.
     */
    @Test
    fun nextWakeIsTheClocksAnswerCappedAt250() {
        val c = clockA()
        val s = FrameStepper(c)
        // Uncapped: frame 0 and frame 1 of board A are 83.33 ms and 166.67 ms long, so from anywhere
        // inside either the next change is well under 250 ms away and the answer is the clock's own.
        for (t in listOf(0.0, 40.0, 100.0, 250.0)) {
            val next = c.nextChangeMs(t)
            assertTrue(next - t < FrameStepper.MAX_SLEEP_MS, "the probe $t is not inside a short frame")
            // The answer is the clock's own double, NOT an independent claim that it is a board
            // edge: `nextChangeMs` REBUILDS it as `elapsed + (boundary - position) / speed`, which is
            // allowed to be an ulp off the edge it came from. `PlaybackClockTest` carries the
            // tolerance for that reconstruction; here the claim is only that nothing was added to it.
            assertEquals(next, s.nextWakeMs(t), "at $t, uncapped and the same double")
        }
        // 250.0 is board A's second boundary, so the answer is the boundary AFTER it, not the one
        // asked at — and it is strictly inside the uncapped window, which is the whole of the claim.
        assertTrue(
            s.nextWakeMs(250.0) in (250.0..(250.0 + FrameStepper.MAX_SLEEP_MS)),
            "at a rising boundary the answer is the NEXT change, ${s.nextWakeMs(250.0)}",
        )

        val slow = boardOf(1, 10, 1, 1, id = "slow")
        val sc = clockOf(slow)
        val ss = FrameStepper(sc)
        val far = 100.0
        assertEquals(1, sc.frameIndexAt(far), "the probe is inside the ten-tick frame")
        assertTrue(
            c.nextChangeMs(far) - far < FrameStepper.MAX_SLEEP_MS,
            "board A's probe, which is inside a short frame",
        )
        assertTrue(
            sc.nextChangeMs(far) - far > FrameStepper.MAX_SLEEP_MS,
            "and the long board really is further than 250 ms away: ${sc.nextChangeMs(far) - far}",
        )
        assertEquals(far + FrameStepper.MAX_SLEEP_MS, ss.nextWakeMs(far), "capped, exactly")
        // The same clock, near the end of that long frame, where the cap no longer bites.
        val near = sc.nextChangeMs(far) - 1.0
        assertTrue(sc.nextChangeMs(near) - near < FrameStepper.MAX_SLEEP_MS, "a millisecond from the change")
        assertEquals(sc.nextChangeMs(near), ss.nextWakeMs(near), "uncapped again, and the same double")
    }

    // ── 8. the arguments the stepper does not police ───────────────────────────

    /**
     * `frameIndex` is not range-checked and a frame outside the range is not an error.
     *
     * `PlaybackClock` keeps `first`, `last` and `count` private and exposes no getter for any of
     * them, so the stepper **cannot** validate the argument without editing a built, reviewed file
     * that is not in this row's owner area. It does not try. An exception here would crash a live
     * animation over a mistake the very next emitted `ShowFrame` repairs — so the honest answer is
     * the clock's, and the caller's belief is what was wrong.
     */
    @Test
    fun aFrameTheClockCouldNeverShowIsNotAnError() {
        val c = clockA()
        val s = FrameStepper(c)
        val out = s.step(0.0, 99, null)
        assertEquals(listOf<Step>(Step.ShowFrame(0), Step.Audio(0.0)), out, "the clock's own answer, not a refusal")
        assertEquals(c.frameIndexAt(0.0), out.filterIsInstance<Step.ShowFrame>()[0].index)

        for (bogus in listOf(-1, -999, 99, Int.MAX_VALUE, Int.MIN_VALUE)) {
            val truth = c.frameIndexAt(250.0)
            val answer = s.step(250.0, bogus, null)
            val shown = answer.filterIsInstance<Step.ShowFrame>()
            assertEquals(1, shown.size, "bogus $bogus still gets one instruction")
            assertEquals(truth, shown[0].index, "bogus $bogus is repaired at once")
        }
        // And the repair holds on the very next call, with the frame the stepper just named.
        assertEquals(
            emptyList(),
            s.step(250.0, c.frameIndexAt(250.0), c.audioPositionMs(250.0)),
            "a caller that believes the stepper is up to date hears nothing",
        )
    }

    /** A reading, not a change, and it is the clock's own answer with no tolerance on it. */
    @Test
    fun frameOnScreenIsTheClocksOwnAnswer() {
        val clocks = listOf(
            clockA(),
            clockA(PlayMode.PING_PONG),
            clockA(PlayMode.ONCE),
            clockA(speed = 2.5f),
            clockA(PlayMode.PING_PONG, 1, 2),
            clockA(PlayMode.LOOP, 0, 2, 0.5f),
        )
        var probes = 0
        for (c in clocks) {
            val s = FrameStepper(c)
            for (ms in 0 until 500) {
                val t = ms * 0.5
                assertEquals(c.frameIndexAt(t), s.frameOnScreen(t), "at $t")
                probes++
            }
        }
        assertEquals(3_000, probes, "three modes, two sub-ranges and three speeds")
    }

    /**
     * Frames 1..2 of board A only. A stepper that forgot the range and answered with the board's own
     * frames would show 0 or 3 here, and the walk is long enough — two whole cycles of a two-frame
     * range — that every slot is visited.
     */
    @Test
    fun aSubRangeStepsOverItsOwnFramesOnly() {
        for (mode in listOf(PlayMode.PING_PONG, PlayMode.LOOP)) {
            val c = clockA(mode, 1, 2)
            val s = FrameStepper(c)
            var shown = 0
            val emitted = ArrayList<Int>()
            for (ms in 0..500) {
                val t = ms.toDouble()
                for (step in s.step(t, shown, null)) {
                    if (step is Step.ShowFrame) {
                        shown = step.index
                        emitted.add(step.index)
                    }
                }
                assertTrue(shown == 1 || shown == 2, "$mode showed frame $shown at $t, which is not in 1..2")
                assertEquals(c.frameIndexAt(t), shown, "$mode at $t is not the clock's own frame")
            }
            // Two frames and two whole cycles means at least four changes, whatever the ulps of the
            // range do to where a 1 ms walk lands. The first emission is the walk correcting the
            // player from frame 0, which is not a frame of this range.
            assertTrue(emitted.size >= 4, "$mode emitted only ${emitted.size} frames in two cycles")
            assertEquals(listOf(1, 2), emitted.distinct(), "$mode only ever showed its own two frames")
            for (i in 1 until emitted.size) {
                assertTrue(emitted[i] != emitted[i - 1], "$mode drew frame ${emitted[i]} twice in a row")
            }
        }
    }

    // ── 9. THE PROPERTY: at a boundary, just before it, and just after it ───────

    /**
     * The test that catches the classic failure, which is invisible in any sampled test: a stepper
     * that is right in the middle of a frame and wrong AT the frame boundary.
     *
     * Every boundary of three clocks is asked three ways — a thousandth of a millisecond before it,
     * AT it, and a thousandth after — and at each one the clock is asked the same three questions so
     * that which kind of edge it is NEVER assumed:
     *
     *  - a **FALLING** edge (`frameIndexAt` has not changed at it): the frame on screen AT the edge
     *    is the one going OUT, so nothing has happened yet and the stepper must say NOTHING when
     *    told that frame is up. Saying anything here is what a millisecond clock turns into a spin.
     *  - a **RISING** edge (`frameIndexAt` has changed at it): the incoming frame is already up AT
     *    the boundary, so a player carrying the old frame MUST be told to draw, and a player already
     *    carrying the new one must hear nothing.
     *
     * So the failing direction is caught on both sides: a stepper that draws a millisecond EARLY is
     * red at the falling edges above, and one that draws a millisecond LATE is red at the rising
     * edges below. A stepper that draws correctly but ALSO re-draws a frame already on screen is red
     * at every one of them.
     *
     * Six boundaries per cycle on board A's uneven four frames and ten on board B's equal six, two
     * cycles of each, and one more clock with no falling edges at all — a LOOP cannot have one, and
     * a test that only ever walked a PING_PONG would never notice a seam convention that had been
     * written for the wrong mode.
     *
     * The COUNT of falling edges is taken in the first cycle alone, and the reason is written where
     * it is asserted: a second cycle's boundary is `b + period`, and that sum rounds.
     */
    @Test
    fun atABoundaryJustBeforeItAndJustAfterIt() {
        val pongB = clockB(PlayMode.PING_PONG)
        val pongA = clockA(PlayMode.PING_PONG)
        val loopB = clockB(PlayMode.LOOP)
        val cases = listOf(
            Case("pong six equal", pongB, boardB, 1000.0),
            Case("pong four uneven", pongA, boardA, pongA.rangeMs + (bA(3) - bA(1))),
            Case("loop six equal", loopB, boardB, loopB.rangeMs),
        )
        for ((name, c, board, period) in cases) {
            val s = FrameStepper(c)
            val edges = boundaries(board, c, 2, period)
            val firstCycle = boundaries(board, c, 1, period)
            val fellFirstCycle = ArrayList<Double>()
            for (b in edges) {
                val before = c.frameIndexAt(b - AWAY)
                val at = c.frameIndexAt(b)
                val after = c.frameIndexAt(b + AWAY)
                if (at == before) {
                    if (b < period) fellFirstCycle.add(b)
                    assertTrue(after != at, "$name: the edge $b changes BEFORE it rather than after it")
                    val out = s.step(b, at, c.audioPositionMs(b))
                    assertTrue(out.isEmpty(), "$name: at the falling edge $b nothing has changed yet, but it said $out")
                } else {
                    assertTrue(after == at, "$name: the rising edge $b changed again just after it")
                    assertEquals(
                        listOf<Step>(Step.ShowFrame(at)),
                        s.step(b, before, c.audioPositionMs(b)),
                        "$name: crossing the rising edge $b",
                    )
                    val quiet = s.step(b, at, c.audioPositionMs(b))
                    assertTrue(quiet.isEmpty(), "$name: asked at $b with the new frame up, it said $quiet")
                }
                // And whatever the edge did, the stepper's own READING agrees with the clock exactly.
                assertEquals(at, s.frameOnScreen(b), "$name: at $b")
            }
            // How many falling edges a cycle has is derived, not observed: the backward leg replays
            // the range's INTERIOR, so a cycle of `n` frames has `n - 2` falling edges and the turn
            // is not one of them. A LOOP has no backward leg and therefore none at all.
            //
            // COUNTED IN THE FIRST CYCLE ONLY, and that is not timidity. A boundary of the second
            // cycle is `b + period`, and adding the period rounds: on board A the third forward
            // boundary of cycle 1 lands a whole ulp BELOW the clock's own `rangeStarts[3]`, so the
            // frame at that double is still the outgoing one and the entry reads as a falling edge
            // that is not one. The clock's own answer is not wrong — a real boundary is probed by
            // the same three questions either way and passes them — but a COUNT taken from an
            // offset sum would be. `PlaybackClockTest.theWholePingPongTimelineIsPinnedSlotBySlot`
            // restricts its exact-at-the-boundary assertions to cycle 0 for this same reason.
            val expectFalling = if (c.mode == PlayMode.PING_PONG) board.frames.size - 2 else 0
            assertEquals(expectFalling, fellFirstCycle.size, "$name: falling edges in one cycle: $fellFirstCycle")
            assertTrue(edges.size >= 12, "$name: only ${edges.size} boundaries were walked")
        }

        // The one clock whose whole two cycles are EXACT — every boundary a whole millisecond and
        // the period a whole thousand — so the falling edges can be pinned as a set rather than
        // counted, and the count is then the length of a list a reader can check by hand.
        val sB = FrameStepper(pongB)
        val exact = ArrayList<Double>()
        for (b in boundaries(boardB, pongB, 2, 1000.0)) {
            if (pongB.frameIndexAt(b) == pongB.frameIndexAt(b - AWAY)) {
                exact.add(b)
                assertTrue(
                    sB.step(b, pongB.frameIndexAt(b), pongB.audioPositionMs(b)).isEmpty(),
                    "nothing has changed at the falling edge $b",
                )
            }
        }
        assertEquals(
            listOf(600.0, 700.0, 800.0, 900.0, 1600.0, 1700.0, 1800.0, 1900.0),
            exact,
            "the seam and three interior edges, in each of two cycles: 4 x 2",
        )
    }

    // ── the small parts ────────────────────────────────────────────────────────

    /**
     * One clock to walk: its name for the failure message, the clock itself, the board its
     * boundaries are derived from, and the length of one cycle.
     *
     * NESTED rather than a top-level class in this package, deliberately: a class's simple name
     * resolves by name across the whole package rather than per file, and `core/anim/` is a
     * directory other rows are adding to. A name declared here cannot be anything but this one.
     */
    private data class Case(
        val name: String,
        val clock: PlaybackClock,
        val board: Board,
        val period: Double,
    )

    /**
     * Every boundary of [cycles] whole cycles, worked out from the board and the clock's own two
     * public facts (`rangeMs` and the mode), never asked of the clock.
     *
     * A PING_PONG cycle's boundaries are every frame start, plus the falling edges of the backward
     * leg `u[k] = rangeMs + (s[n - 1] - s[k])` for `k = n - 1 … 1` — whose first member, at
     * `k = n - 1`, IS `rangeMs`, so the seam is in the list and the turn is its last member. A LOOP
     * has no backward leg, so its only extra boundary is the wrap, which is `rangeMs`.
     */
    private fun boundaries(of: Board, c: PlaybackClock, cycles: Int, period: Double): List<Double> {
        val starts = AnimOps.frameStartsMs(of)
        val one = ArrayList<Double>()
        for (k in 1 until starts.size) one.add(starts[k])
        if (c.mode == PlayMode.PING_PONG) {
            val last = starts.last()
            for (k in 1 until starts.size) one.add(c.rangeMs + (last - starts[k]))
        } else {
            one.add(c.rangeMs)
        }
        val out = ArrayList<Double>()
        for (cyc in 0 until cycles) for (b in one) out.add(b + cyc * period)
        return out.sorted()
    }

    /**
     * A fixed linear congruential generator, because `kotlin.random` promises nothing about its
     * algorithm across versions and a test whose failures cannot be reproduced is a rumour.
     *
     * Numerical Recipes' constants. `between` takes 53 bits of the high word, which is every bit a
     * `Double` can hold, and maps them onto `[lo, hi)`; `audio` returns null a quarter of the time
     * so that both arms of the sound comparison are walked.
     */
    private class Lcg(seed: Long) {
        private var s: Long = seed

        private fun next(): Long {
            s = s * 6364136223846793005L + 1442695040888963407L
            return s
        }

        fun int(): Int = (next() ushr 32).toInt()

        fun between(lo: Double, hi: Double): Double =
            lo + (next() ushr 11).toDouble() / 9007199254740992.0 * (hi - lo)

        fun audio(): Double? = when (int() and 3) {
            0 -> null
            1 -> between(0.0, 700.0)
            else -> between(-5.0, 700.0)
        }
    }
}

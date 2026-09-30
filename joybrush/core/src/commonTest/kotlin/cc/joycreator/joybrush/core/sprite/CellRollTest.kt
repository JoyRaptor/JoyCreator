package cc.joycreator.joybrush.core.sprite

import cc.joycreator.joybrush.core.anim.FrameStepper
import cc.joycreator.joybrush.core.anim.PlayMode
import cc.joycreator.joybrush.core.anim.PlaybackClock
import cc.joycreator.joybrush.core.anim.Step
import cc.joycreator.joybrush.core.doc.AnimOps
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.RectPx
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The roll's grammar, its holds and its playback — the core half of JB-4.02, which is SpriteLab's
 * chip mechanic (R23: reuse it, don't rebuild it) played through the project's one reviewed clock.
 *
 * ## THE FOUR THINGS TO KNOW BEFORE WRITING A TEST HERE
 *
 * 1. **THE GRID IS A CELL COUNT AND NOTHING ELSE.** A 4 x 2 sheet is the number `8` here. No test in
 *    this file builds a sprite board, and `CellRollShapeTest` is what says the roll cannot reach one
 *    — so a test that quietly started passing a grid would be a test that had stopped testing the
 *    thing this row owns.
 * 2. **A HOLD IS PER ENTRY, NEVER PER CELL** (Decision 4), which is why several tests build a roll
 *    containing the same cell twice on purpose. A per-cell hold cannot express `0 1 2 1`.
 * 3. **EVERY BOUNDARY IS DERIVED BY INDEXING `AnimOps`, NEVER BY ACCUMULATING `hold * 1000 / fps`.**
 *    `2 * (1000.0 / 12.0)` is not bit-identical to `2000.0 / 12.0`, and a boundary an ulp out lands
 *    on the wrong side of the only comparison that decides anything. Where a test needs a number it
 *    cannot index (a cycle), it takes it from the clock itself.
 * 4. **NO TOLERANCE ON ANY EMITTED VALUE, AND THE ONE PLACE ONE APPEARS IS NAMED.** The preview is
 *    a `FrameStepper` and `FrameStepper` compares `==` on the clock's own double; a tolerance here
 *    would stop the test being a claim about identity and turn it into a claim about a bound.
 */

// ── the fixture ──────────────────────────────────────────────────────────────

/** A 4 x 2 sheet. The count is the whole of what this row knows about a grid. */
private const val COLS = 4
private const val ROWS = 2
private const val CELLS = COLS * ROWS

/** Where the preview happens, and a number the tests never depend on. */
private val RECT = RectPx(0, 0, 256, 256)

/** One entry. [hold] defaults to 1 because a tap is always one tick (Decision 1). */
private fun e(cell: Int, hold: Int = 1): CellRoll.Entry = CellRoll.Entry(cell = cell, hold = hold)

/** A roll built out of entries; the cursor is last because most cases do not set it. */
private fun rollOf(vararg es: CellRoll.Entry, cursor: Int = 0): CellRoll =
    CellRoll(entries = es.toList(), cursor = cursor)

/** `(cell, hold)` per entry, which is the shape almost every assertion here wants. */
private fun pairsOf(of: CellRoll): List<Pair<Int, Int>> = of.entries.map { it.cell to it.hold }

/** Just the cells, for the assertions that are about ORDER and not about holds. */
private fun cellsOf(of: CellRoll): List<Int> = of.entries.map { it.cell }

/**
 * How far off a boundary a probe may sit and still be unambiguously off it: a thousandth of a
 * millisecond, against a shortest possible frame of 16.7 ms and a shortest frame in this file of
 * 250 ms. Far larger than the ulp of any boundary here (about 2.3e-13 at 1500 ms) and far smaller
 * than any frame. The same number and the same reason as `FrameStepperTest`'s.
 */
private val AWAY = 0.001

/**
 * Every roll shape this file builds, in ONE list, so that "every roll the suite builds" (tests 14
 * and 15) cannot be a phrase that quietly stops being true when a test is added. A roll here that
 * is not reachable from a gesture in this file would be a lie, so each line names where it is used.
 */
private val suiteRolls: List<CellRoll> = listOf(
    CellRoll(),                                                            // the roll as it starts
    rollOf(e(7, 3)),                                                        // test 7
    rollOf(e(0), e(1), e(2), e(1), cursor = 3),                             // tests 5, 13
    rollOf(e(3), e(1), e(3), cursor = 2),                                   // tests 2, 13
    rollOf(e(3), e(1), e(3), e(3), cursor = 3),                             // test 3
    rollOf(e(3), e(3), cursor = 0),                                         // test 4
    rollOf(e(0), e(1), e(2), e(3), e(4), cursor = 4),                       // test 8
    rollOf(e(0, 3), e(1), e(5, 16), e(2, 16), cursor = 2),                   // test 14, both ends
    rollOf(e(0, 3), e(1, 3), e(2, 3), e(3, 3), e(4, 3), e(5, 3)),            // tests 9 and 11
    CellRoll().playAll(CELLS),                                              // test 7
    rollOf(e(6), e(7)).prunedTo(2).roll,                                     // tests 14, 17
    rollOf(e(0, 5), e(0)),                                                  // test 1
)

class CellRollTest {

    // ── 1. a tap is one tick, always ───────────────────────────────────────────

    /**
     * Decision 1, and the bug it prevents is a subtle one: a tap that inherited "the cell's current
     * hold" would make the FOURTH tap of cell 3 behave differently from the first three, and the
     * only way anybody would ever see it is to tap a cell four times.
     */
    @Test
    fun aTapAlwaysAppendsHoldOneEvenAfterTheCellHasBeenHeld() {
        val tapped = CellRoll().tapped(0, CELLS)
        val held = tapped.focused(0).holdBumped(4)
        assertEquals(listOf(0 to 5), pairsOf(held), "the bump landed on the entry under the cursor")

        val again = held.tapped(0, CELLS)
        assertEquals(listOf(0 to 5, 0 to 1), pairsOf(again), "the second tap is ONE tick, not five")
        assertEquals(1, again.cursor, "and the cursor is on the entry it just made")
        // The two halves said separately, because "the second tap is one tick" and "the first entry
        // kept its five" are different claims and a test that only made the first would pass against
        // a roll that had REWRITTEN the earlier hold on the way.
        assertEquals(5, again.entries[0].hold, "the first entry kept the hold a person gave it")
        assertEquals(1, again.entries[1].hold, "and the new one is a tick")
    }

    // ── 2. a badge tap takes the LAST use ──────────────────────────────────────

    /**
     * Lines 4131-4133 walk down and `break` on the first match they meet, which is the LAST use.
     *
     * **The assertion is on the ORDER and not on the set**, and that is the whole test: a
     * first-match removal would leave `[1, 3]`, which is a different animation rather than an undo.
     *
     * The cursor half is half the test. A builder who left `cursor = 2` on a two-entry roll has
     * shipped a `focused(2)` that every reader will clamp for it and that points at nothing.
     */
    @Test
    fun aBadgeTapTakesTheLastUseNotTheFirst() {
        val roll = rollOf(e(3), e(1), e(3), cursor = 2)
        val after = roll.untapped(3)
        assertEquals(listOf(3 to 1, 1 to 1), pairsOf(after), "the LAST use went, not the first")
        assertEquals(1, CellRollGrammar.removedBy(roll, CellGesture.BadgeTapped(3), CELLS))
        assertEquals(1, after.cursor, "Decision 17: the last entry is gone, so the cursor clamps to 1")
        assertEquals(2, after.size, "and the roll is two entries, not three")
    }

    // ── 3. a badge long-press takes EVERY use, and says how many ───────────────

    @Test
    fun aBadgeHoldRemovesEveryUseAndReportsHowMany() {
        val roll = rollOf(e(3), e(1), e(3), e(3), cursor = 3)
        val prune = roll.untappedAll(3)
        assertEquals(listOf(1 to 1), pairsOf(prune.roll))
        assertEquals(3, prune.dropped, "three uses of cell 3 went, and the caller can SAY so")
        assertEquals(0, prune.roll.cursor, "one entry left, so clamping down to size - 1 is a no-op")
        assertEquals(3, CellRollGrammar.removedBy(roll, CellGesture.BadgeHeld(3), CELLS))

        // A cell that is not in the roll is the same answer as a cell that is: nothing, and the
        // roll handed back is the one that was given.
        assertEquals(roll, roll.untappedAll(5).roll, "cell 5 is not in this roll")
        assertEquals(0, roll.untappedAll(5).dropped)
        assertEquals(roll, CellRollGrammar.apply(roll, CellGesture.BadgeHeld(5), CELLS))
    }

    // ── 4. a hold is per ENTRY, and clamps at 1 and 16 ─────────────────────────

    @Test
    fun aHoldBelongsToTheEntryAndNotToTheCell() {
        val roll = rollOf(e(3), e(3), cursor = 0)
        val bumped = roll.holdBumped(1)
        assertEquals(listOf(3 to 2, 3 to 1), pairsOf(bumped), "entry 0 took the bump and entry 1 did not")
        assertTrue(bumped.entries[0].hold != bumped.entries[1].hold, "one cell, two different holds")

        val other = bumped.focused(1).holdBumped(1)
        assertEquals(listOf(3 to 2, 3 to 2), pairsOf(other), "and the other entry can be caught up")
        // THE POINT, said as a claim about what a per-CELL hold could not do: it would have moved
        // BOTH entries when either was touched, and `0 1 2 1` needs the two `1`s to differ.
        assertNotEquals(pairsOf(other), pairsOf(bumped), "the two entries of ONE cell are independent")
    }

    @Test
    fun aHoldStopsAtSixteenAndAtOne() {
        var roll = rollOf(e(3), e(3), cursor = 0)
        roll = roll.holdBumped(99)
        assertEquals(CellRoll.MAX_HOLD, roll.entries[0].hold, "the app's Math.min(16, …), line 2210")
        assertEquals(1, roll.entries[1].hold, "and the clamp is on the ENTRY, not on the cell")
        roll = roll.holdBumped(-99)
        assertEquals(1, roll.entries[0].hold, "the app's Math.max(1, …), the same line")
        assertEquals(1, CellRoll.MIN_HOLD, "which is the number the app's own clamp starts from")
        // The ends are the ends even when the delta is a number no slider could produce. The
        // arithmetic is done in Long for exactly this reason: `Int + Int` that wraps would put a
        // bump of +2147483647 at the WRONG end, silently.
        assertEquals(16, rollOf(e(3)).holdBumped(Int.MAX_VALUE).entries[0].hold)
        assertEquals(1, rollOf(e(3)).holdBumped(Int.MIN_VALUE).entries[0].hold)
        assertEquals(CellRoll(), CellRoll().holdBumped(5), "an empty roll has no entry to hold")
    }

    // ── 5. duplicates are legal, and order is the taps ─────────────────────────

    @Test
    fun theSameCellCanBeInTheRollThreeTimes() {
        val roll = rollOf(e(0), e(1), e(2), e(1))
        assertEquals(listOf(0, 1, 2, 1), cellsOf(roll), "duplicates are how a ping-pong is written")
        assertEquals(4, roll.size)

        val three = CellRoll().tapped(2, CELLS).tapped(2, CELLS).tapped(2, CELLS)
        assertEquals(listOf(2, 2, 2), cellsOf(three), "three taps of one cell, the way a person does it")

        // A **USE** is a tapped ENTRY, not a cell. Three taps of cell 2 is three uses of cell 2, and
        // a badge tap takes the LAST ONE back — so `untapped(2)` on `[(2,1),(2,1),(2,1)]` leaves
        // `[(2,1),(2,1)]`: two entries, and BOTH of them are still cell 2.
        //
        // The count of surviving USES is 2 and the count of surviving distinct CELLS is 1, and this
        // test once asserted 1 against the former. Both are written out because the difference IS
        // the claim: a badge tap removes one entry, and saying "one cell" would describe a long-press.
        assertEquals(2, three.untapped(2).size, "a badge tap takes ONE of the three uses")
        assertEquals(2, three.untapped(2).entries.count { it.cell == 2 }, "and both survivors are still cell 2")
        assertEquals(1, three.untapped(2).entries.distinct().size, "which is one CELL — the long-press")
        assertEquals(3, three.untappedAll(2).dropped, "a long-press takes all three")
        assertTrue(three.untappedAll(2).roll.isEmpty)
    }

    @Test
    fun aSequenceOfTapsIsExactlyTheTapsInOrder() {
        var roll = CellRoll()
        for (cell in listOf(0, 1, 2, 1)) roll = roll.tapped(cell, CELLS)
        assertEquals(listOf(0, 1, 2, 1), cellsOf(roll), "order preserved, duplicates kept")
        assertEquals(listOf(1, 1, 1, 1), roll.entries.map { it.hold }, "every tap is one tick")
        assertEquals(3, roll.cursor, "the cursor is on the last tap")
    }

    // ── 6. outside the grid is refused, not answered ───────────────────────────

    @Test
    fun aTapOutsideTheGridChangesNothing() {
        val before = rollOf(e(0), e(7))
        var roll = before
        for (outside in listOf(CELLS, -1, 99, Int.MIN_VALUE, Int.MAX_VALUE)) {
            roll = roll.tapped(outside, CELLS)
            assertEquals(before, roll, "cell $outside is not on a $COLS x $ROWS sheet, so nothing was added")
        }
        // And the three readers agree with each other, which is the part a "silently added -1" bug
        // would break in three different places.
        assertEquals(0, CellRollGrammar.removedBy(before, CellGesture.BadgeTapped(4), CELLS),
            "a badge tap on a cell that is not in the roll removes nothing")
        assertEquals(before, before.untapped(4), "and leaves the roll exactly as it was")
        assertEquals(0, before.untappedAll(4).dropped)
        assertEquals(before, before.prunedTo(CELLS).roll, "cells 0 and 7 are both still on the sheet")
    }

    // ── 7. "Play all" REPLACES ─────────────────────────────────────────────────

    /**
     * The app's `playAll` calls `labSeq.clear()` (line 2134) before it adds anything (line 2138).
     * The alternative — appending — turns "play all" into "play all, again", every time it is
     * pressed, and the difference is one line of `clear()`.
     *
     * Every cell and not only the used ones is this row's Q3, and the assertion is the whole eight
     * so that a later row reversing it cannot do it quietly.
     */
    @Test
    fun playAllReplacesTheRollAndNotAppendsToIt() {
        val roll = rollOf(e(7, 3))
        val all = CellRollGrammar.apply(roll, CellGesture.PlayAll, CELLS)
        assertEquals((0 until CELLS).toList(), cellsOf(all), "every cell, in reading order")
        assertEquals(List(CELLS) { 1 }, all.entries.map { it.hold }, "every hold 1")
        assertEquals(CELLS, all.size)
        assertEquals(0, all.cursor, "the app's focusRoll(0, true) on line 2141")
        assertFalse(all.entries.contains(e(7, 3)), "the old entry, hold 3 and all, is GONE")

        val fromEmpty = CellRollGrammar.apply(CellRoll(), CellGesture.PlayAll, CELLS)
        assertEquals(all, fromEmpty, "and from an empty roll it is the same eight, which is the proof")
        assertEquals(true, CellRollGrammar.isBulk(CellGesture.PlayAll), "so a caller can ask first")
        // A grid of nothing is an empty roll, not a crash and not a one-entry roll.
        assertEquals(0, roll.playAll(0).size)
    }

    // ── 8. "Clear" empties it, and the cursor goes to 0 ────────────────────────

    @Test
    fun clearingEmptiesTheRollAndResetsTheCursor() {
        val roll = rollOf(e(0), e(1), e(2), e(3), e(4), cursor = 4)
        val cleared = CellRollGrammar.apply(roll, CellGesture.Clear, CELLS)
        assertTrue(cleared.isEmpty, "isEmpty, not a roll of zeroes")
        assertEquals(0, cleared.cursor, "0, and not clamped down onto nothing")
        assertEquals(emptyList<CellRoll.Entry>(), cleared.entries)

        val twice = CellRollGrammar.apply(cleared, CellGesture.Clear, CELLS)
        assertEquals(cleared, twice, "a second Clear changes nothing")
        assertEquals(true, CellRollGrammar.isBulk(CellGesture.Clear))
        // `focused` clamps rather than refuses, so a caller that hands over a stale index still
        // gets a cursor that points at something.
        assertEquals(0, rollOf().focused(99).cursor)
        assertEquals(0, rollOf().focused(-5).cursor)
    }

    // ── 9. THE TEST THIS ROW EXISTS FOR: no spin at a backward boundary ─────────

    /**
     * The port of `FrameStepperTest.theStepperDoesNotSpinAtABackwardBoundary` onto cells, with
     * **every number derived** rather than transcribed.
     *
     * ```
     * frameStartsMs      0  250  500  750  1000  1250        (3 ticks x 1000 / 12 = 250.0 exactly)
     * totalDurationMs    1500.0                                so rangeMs = 1500.0
     * backSpanMs         rangeStarts[5] - rangeStarts[1] = 1250 - 250 = 1000
     * cycleMs            rangeMs + backSpanMs = 1500 + 1000 = 2500.0
     * falling edges      u[k] = rangeMs + (s[5] - s[k]), k = 5 down to 2:
     *                      u[5] = 1500  THE SEAM, still frame 5
     *                      u[4] = 1750  interior falling edge #1, still frame 4
     *                      u[3] = 2000  #2, still frame 3
     *                      u[2] = 2250  #3, still frame 2
     *                      u[1] = 2500  THE TURN, and the frame really IS 0 there
     * ```
     *
     * Holds of 3 are the whole reason this test has no rounding in it: 250.0 ms is EXACT, so a 1 ms
     * walk steps exactly onto every boundary rather than landing an ulp to one side of it.
     *
     * ## Why ten emissions, and the times they happen at
     *
     * A **rising** edge draws AT it — a frame start is the instant the incoming frame is already up.
     * A **falling** edge draws **one step after** it, because the boundary is the INFIMUM of the
     * change times and the outgoing frame is still the one on screen at the edge itself. The turn
     * draws AT the cycle, because the frame really has changed there.
     *
     * So one cycle emits `6 + 4 = 10` times, which is `2n - 2` for `n = 6`, and **not once per step**:
     * 2 501 steps, ten of which draw. A preview that emitted on every call would pass a test that
     * only looked at the middle of a frame, and its symptom in the field is a phone at 100 % battery
     * playing a two-frame animation.
     *
     * The SOUND is passed in already correct on every call — a sprite preview has no sound, and a
     * sprite preview is not this row's business — so the emission list below is the picture and
     * nothing else.
     */
    @Test
    fun thePreviewDoesNotSpinAtABackwardBoundary() {
        val roll = rollOf(e(0, 3), e(1, 3), e(2, 3), e(3, 3), e(4, 3), e(5, 3))
        val board = roll.asBoard(12f, RECT)
        val clock = PlaybackClock(board, mode = PlayMode.PING_PONG)
        val stepper = FrameStepper(clock)

        // 1. The range and the cycle, from the CLOCK and the BOARD'S OWN STARTS — never from a sum
        //    written out here, which is the one number a second definition could disagree about.
        val starts = AnimOps.frameStartsMs(board)
        assertEquals(6, starts.size, "six entries, so six frame starts and no trailing end")
        assertEquals(1500.0, clock.rangeMs, "six frames of 3 ticks at 12 fps is 18 ticks = 1500 ms")
        val cycle = clock.rangeMs + (starts[5] - starts[1])
        assertEquals(2500.0, cycle, "rangeMs + (s[5] - s[1]) = 1500 + (1250 - 250)")
        assertEquals(cycle, periodTheClockItselfRepeatsOn(clock), "and the clock really repeats on that")

        // The four falling edges, derived the way the header derives them, and asserted as a SET so
        // that a count alone could not be satisfied by the wrong four numbers.
        val falling = (2..5).map { clock.rangeMs + (starts[5] - starts[it]) }.sorted()
        assertEquals(listOf(1500.0, 1750.0, 2000.0, 2250.0), falling, "the seam and three interior edges")
        assertEquals(4, falling.size, "n - 2 falling edges for n = 6, and the turn is not one of them")

        // 2. THE WALK. 2 501 whole milliseconds, carrying the picture forward exactly as a player
        //    would, and asserting at EVERY step that what is on screen is the clock's own answer.
        //    Emit too little and the test goes red here; emit too much and the list below is wrong.
        var shown = 0
        val emissions = ArrayList<Pair<Int, Double>>()
        var steps = 0
        for (ms in 0..2500) {
            val t = ms.toDouble()
            steps++
            for (step in stepper.step(t, shown, clock.audioPositionMs(t))) {
                when (step) {
                    is Step.ShowFrame -> {
                        shown = step.index
                        emissions.add(step.index to t)
                    }
                    // The sound was already where the clock says, so an instruction about it here
                    // would mean the comparison had stopped being a comparison.
                    is Step.Audio -> fail("at $t the sound was already right, but it said ${step.boardMs}")
                    is Step.Finish -> fail("PING_PONG must never finish, and $t asked")
                }
            }
            assertEquals(clock.frameIndexAt(t), shown, "the player is showing the wrong frame at $t")
        }
        assertEquals(2_501, steps, "one whole cycle in 1 ms steps, both ends included")

        // 3. THE EMISSION LIST — the number this whole test is about.
        assertEquals(listOf(1, 2, 3, 4, 5, 4, 3, 2, 1, 0), emissions.map { it.first }, "2n - 2 of them")
        assertEquals(10, emissions.size, "2n - 2 for n = 6, and not once per step")
        assertEquals(
            listOf(250.0, 500.0, 750.0, 1000.0, 1250.0, 1501.0, 1751.0, 2001.0, 2251.0, 2500.0),
            emissions.map { it.second },
            "a rising edge draws AT it, a falling edge one step after it, and the turn at the cycle",
        )

        // 4. THE VISIBLE SEQUENCE at slot midpoints — the other spelling of the same fact, and the
        //    only place in a slot where no rounding can be argued about.
        val midpoints = (0..9).map { it * 250.0 + 125.0 }
        val seen = midpoints.map { clock.frameIndexAt(it) }
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 4, 3, 2, 1), seen, "ten slots across one cycle")
        for (i in 1 until seen.size) {
            assertTrue(seen[i] != seen[i - 1], "frame ${seen[i]} is showing twice in a row at sample $i")
        }

        // 5. THE INVARIANT THAT MAKES THE SPIN IMPOSSIBLE. At every one of the 2 501 steps the answer
        //    is STRICTLY later than now — including a millisecond before each falling edge and at the
        //    edge itself, where the clock's own `nextChangeMs` answers `t` bit for bit and only the
        //    floor in `FrameStepper` saves a millisecond clock from being handed back its own
        //    timestamp. This is `FrameStepper.nextWakeMs`'s own pinned contract, checked here through
        //    the sprite roll, which is the whole of Decision 10.
        for (ms in 0..2500) {
            val t = ms.toDouble()
            val wake = stepper.nextWakeMs(t)
            assertTrue(wake > t, "at $t it said wake at $wake, which is a spin")
        }
        for (u in falling) {
            assertTrue(stepper.nextWakeMs(u - 1.0) > u - 1.0, "a millisecond before the falling edge $u")
            assertEquals(u + FrameStepper.MIN_WAKE_MS, stepper.nextWakeMs(u), "the floor AT the edge $u")
        }
        // And the floor is exactly there and nowhere else: inside a frame the answer is the clock's.
        for (v in listOf(0.0, 250.0, 1250.0, 2500.0)) {
            assertEquals(clock.nextChangeMs(v), stepper.nextWakeMs(v), "a rising edge is the clock's own answer")
        }
    }

    /**
     * The smallest whole-millisecond shift the clock's own answers survive, found by ASKING THE
     * CLOCK and nothing else. 21 probes a quarter of a cycle apart, so a shift that is not the real
     * period has to agree on every one of them to be accepted.
     *
     * This is how test 9's cycle is asserted "against the clock's own numbers" as well as against
     * the board's own starts: the two derivations are independent, and a `asBoard` that built the
     * wrong board would have to be wrong identically in both to get past them.
     */
    private fun periodTheClockItselfRepeatsOn(clock: PlaybackClock, upToMs: Int = 4_000): Double {
        val probes = (0..20).map { it * 125.0 }
        for (ms in 1..upToMs) {
            val shift = ms.toDouble()
            if (probes.all { clock.frameIndexAt(shift + it) == clock.frameIndexAt(it) }) return shift
        }
        fail("the clock has no period under $upToMs ms, so the test's cycle of 2500 ms is not its own")
    }

    // ── 10. the roll IS a schedule the reviewed clock can play ──────────────────

    /**
     * Decision 9, and what proves it is the SAME code: a real [PlaybackClock] over the board
     * `asBoard` built, with the cell asserted at every millisecond against the board's own frame
     * starts. Nothing here re-implements the timing, and a roll that were not playable could not
     * produce a `frameIndexAt` at all.
     *
     * The cells are **7, 3, 5** and the frame indices are **0, 1, 2**, deliberately: if `cellAtIndex`
     * were the identity — or off by one — every assertion below would name the wrong sprite.
     */
    @Test
    fun theRollIsPlayableByTheReviewedClock() {
        val roll = rollOf(e(7, 3), e(3, 6), e(5, 9))
        val board = roll.asBoard(12f, RECT)
        val clock = PlaybackClock(board, mode = PlayMode.LOOP)

        assertEquals(1500.0, clock.rangeMs, 1e-9, "3 + 6 + 9 ticks at 12 fps is 18 ticks, from the clock")
        assertEquals(BoardKind.ANIMATION, board.kind, "and ANIMATION, or the clock would have refused it")
        assertEquals(listOf("r0", "r1", "r2"), board.frames.map { it.id }, "one frame per entry, in order")
        assertEquals(listOf(3, 6, 9), board.frames.map { it.holdFrames }, "each frame holds what its entry held")
        assertEquals(12f, board.fps, "and the rate is the one the caller passed, not a default of its own")

        // The frame starts come from `AnimOps`, never from here, and the cell each slot shows is
        // pinned to them at EVERY millisecond rather than at three chosen ones.
        //
        // **THE ELAPSED TIME IS REDUCED INTO THE RANGE FIRST, and that reduction IS the clock's
        // seam, not a convenience.** A LOOP clock at exactly `rangeMs` has already wrapped: its
        // `cyclePos` is `rangeMs mod rangeMs` = 0, so the frame is 0 again and the cell is the FIRST
        // entry's. Comparing raw `t` against the board's starts reads that instant as the last
        // frame and names cell 5 where the clock says cell 7 — which is exactly what this table did
        // until the suite caught it. The two lines below say so out loud, because "the cell at the
        // end of the range" is the one answer a roll player will see and the one a table gets wrong.
        val starts = AnimOps.frameStartsMs(board)
        val second = starts[1]
        val third = starts[2]
        for (ms in 0..1500) {
            val t = ms.toDouble()
            val pos = t % clock.rangeMs
            val expected = when {
                pos < second -> 7
                pos < third -> 3
                else -> 5
            }
            val frame = clock.frameIndexAt(t)
            assertTrue(frame in board.frames.indices, "at $t the clock named frame $frame of a three-frame board")
            assertEquals(expected, roll.cellAtIndex(frame), "the cell showing at $t")
        }
        assertEquals(7, roll.cellAtIndex(clock.frameIndexAt(0.0)), "the first slot is cell 7")
        assertEquals(3, roll.cellAtIndex(clock.frameIndexAt(second + 0.5)), "the second is cell 3")
        assertEquals(5, roll.cellAtIndex(clock.frameIndexAt(third + 0.5)), "the third is cell 5")

        // THE SEAM, three ways. A LOOP is on its LAST frame a thousandth before `rangeMs` and on its
        // FIRST frame AT it, and 7 is cell 7 on a sheet of 0..7 — the answer is the first entry's own
        // cell, and it is the one a hand-written table gets wrong.
        assertEquals(2, clock.frameIndexAt(clock.rangeMs - AWAY), "a thousandth before the end, the LAST frame")
        assertEquals(5, roll.cellAtIndex(clock.frameIndexAt(clock.rangeMs - AWAY)), "and cell 5")
        assertEquals(0, clock.frameIndexAt(clock.rangeMs), "AT rangeMs the LOOP has already wrapped to frame 0")
        assertEquals(7, roll.cellAtIndex(clock.frameIndexAt(clock.rangeMs)), "and cell 7 — the FIRST entry's cell")
        assertEquals(0, clock.frameIndexAt(clock.rangeMs + AWAY), "a thousandth after, still frame 0")
        assertEquals(7, roll.cellAtIndex(clock.frameIndexAt(clock.rangeMs + AWAY)), "and still cell 7")

        // ── THE BOUNDARY SWEEP ──
        //
        // One probe at one boundary is a thin margin, and this project has been burned by exactly
        // that (JB-2.12a's perspective test passed against broken code because the deciding sample
        // was 0.057 px out). So every falling edge and the turn is asked three ways: AT it, a
        // thousandth before it and a thousandth after.
        //
        // **A LOOP HAS NO BACKWARD LEG**, so the edges above cannot be swept on the board the rest
        // of this test plays: it is three frames, and its only edge is the wrap. This builds the
        // PING_PONG the sweep needs — six entries of three ticks at 12 fps, so the same exact 250 ms
        // slots, the same `rangeMs` and the same turn as test 9 — and picks a roll whose **CELLS are
        // not its INDICES**, so every probe below can tell the two apart. A roll of `(0,1,2,3,4,5)`
        // would sweep a cell column identical to the frame column and could not fail on a mapping.
        val pong = rollOf(e(7, 3), e(3, 3), e(5, 3), e(1, 3), e(0, 3), e(4, 3))
        val pongCells = cellsOf(pong)
        assertEquals(6, pongCells.size)
        assertTrue(
            pongCells.indices.none { pongCells[it] == it },
            "the cells must differ from the indices or the sweep below proves nothing: $pongCells",
        )
        for (cell in pongCells) {
            assertTrue(cell in 0 until CELLS, "cell $cell is not on the fixture sheet")
        }

        val pongBoard = pong.asBoard(12f, RECT)
        val pongStarts = AnimOps.frameStartsMs(pongBoard)
        val pongClock = PlaybackClock(pongBoard, mode = PlayMode.PING_PONG)
        assertEquals(clock.rangeMs, pongClock.rangeMs, 1e-9, "six three-tick entries are the same 1500 ms")
        val turn = pongClock.rangeMs + (pongStarts[5] - pongStarts[1])
        assertEquals(2500.0, turn, "and the same turn, so the edges below are test 9's edges")
        val edges = (2..5).map { pongClock.rangeMs + (pongStarts[5] - pongStarts[it]) }.sorted()
        assertEquals(listOf(1500.0, 1750.0, 2000.0, 2250.0), edges, "the seam and three interior falling edges")

        // The rule that fills the table in, which is the clock's own: a **rising** edge is a frame
        // START, so the incoming frame is already up AT it; a **falling** edge is a frame END, so
        // the OUTGOING frame is still up AT it and the change lands one step after; the **turn** is
        // the one instant of the cycle where the frame really HAS changed, because the leg restarts.
        val sweep = listOf(
            edges[0] - AWAY to 5, edges[0] to 5, edges[0] + AWAY to 4,
            edges[1] - AWAY to 4, edges[1] to 4, edges[1] + AWAY to 3,
            edges[2] - AWAY to 3, edges[2] to 3, edges[2] + AWAY to 2,
            edges[3] - AWAY to 2, edges[3] to 2, edges[3] + AWAY to 1,
            turn - AWAY to 1, turn to 0, turn + AWAY to 0,
        )
        assertEquals(15, sweep.size, "four falling edges times three probes, plus the turn's three")
        for ((at, frame) in sweep) {
            assertEquals(frame, pongClock.frameIndexAt(at), "the frame at $at")
            assertEquals(pongCells[frame], pong.cellAtIndex(pongClock.frameIndexAt(at)), "and the CELL at $at")
        }
        // The cells the sweep saw, written out, so the table above can be checked by eye: the seam
        // still shows the LAST cell, every interior edge still shows the cell going OUT, and cell 7
        // — the first entry's — appears nowhere except at the turn, which is the one place the
        // answer differs from a forward-leg reading. That asymmetry is what gives the sweep teeth.
        assertEquals(
            listOf(4, 4, 0, 0, 0, 1, 1, 1, 5, 5, 5, 3, 3, 7, 7),
            sweep.map { pongCells[it.second] },
            "the cells the sweep saw, in order",
        )
        // ── NOTHING IS ASSERTED ABOUT WHICH PROBES AGREE, and that is a decision, not an omission ──
        //
        // Two attempts at a "repeats" rule were written here and both were FALSE; the suite caught
        // the first, and the second was caught by hand before it ever ran. Recorded so that nobody
        // adds a third, because both failures had the same shape: a claim about probe instants that
        // only looks plausible until you ask where the two instants sit relative to a slot boundary.
        //
        //  1. "No frame twice in a row" across the sweep. A frame is on screen for its whole 250 ms
        //     slot, so two instants inside a slot agree BY DEFINITION and this can only ever be a
        //     claim about an EMISSION LIST.
        //  2. "A repeat is allowed at a boundary and nowhere else." Tempting, and worse than the
        //     first: (1500.001, 1749.999) are two probes inside ONE slot — the frame that starts at
        //     1501 runs to 1750 — so they agree PRECISELY BECAUSE one frame occupies the whole slot.
        //     Four such pairs exist, one on each interior leg, plus the five at the edges.
        //
        // The replacement is not a third rule, it is the absence of one: the per-probe assertions
        // above already pin the shown frame AND the shown cell at all fifteen instants, before, at
        // and after every boundary, so "which pairs agree" is fully determined by them. Restating it
        // in a second hand-maintained vocabulary is a second copy of a claim, and a second copy is
        // the drift this project keeps paying for.
        //
        // WHERE THE SEAM-HOLD IS VISIBLE, which is where it belongs — not as a probe coincidence but
        // as what a player is told to draw. The backward leg starts at `s[5]`, so the last frame is
        // still up AT the seam and the change lands one step after it: the sweep's `1500.0 → 5` and
        // `1500.001 → 4` rows, and the emission list's `5` at 1250 and `4` at 1501. That is
        // `PlaybackClock.kt:80-84` in its own words — "the seam still shows that frame, exactly as
        // the ruling says" — and it is why the seam emits nothing at all.

        // ── THE EMISSIONS, because a probe sweep cannot see them ──
        //
        // This is where the row's real invariant lives, and it is the one JB-3.05a already pins: a
        // player redraws once per CHANGE, so the claim is that consecutive emissions never name the
        // same frame, and that every emission is the clock's own answer AT the instant it is emitted.
        // The roll is walked a millisecond at a time carrying `shown` forward exactly as a player
        // would, and only the changes are collected — 2 501 steps, ten of which draw.
        val stepper = FrameStepper(pongClock)
        val emitted = ArrayList<Pair<Int, Double>>()
        var shown = 0
        for (ms in 0..2500) {
            val t = ms.toDouble()
            for (step in stepper.step(t, shown, pongClock.audioPositionMs(t))) {
                if (step is Step.ShowFrame) {
                    shown = step.index
                    assertEquals(pongClock.frameIndexAt(t), shown, "an emission at $t names the clock's own frame")
                    emitted.add(shown to t)
                }
            }
            assertEquals(pongClock.frameIndexAt(t), shown, "the player is showing the wrong frame at $t")
        }
        assertEquals(2 * 6 - 2, emitted.size, "2n - 2 for n = 6, and not once per step")
        assertEquals(
            listOf(1, 2, 3, 4, 5, 4, 3, 2, 1, 0),
            emitted.map { it.first },
            "the same ten frames as test 9 on a different roll: the schedule is the clock's, not the roll's",
        )
        assertEquals(
            listOf(250.0, 500.0, 750.0, 1000.0, 1250.0, 1501.0, 1751.0, 2001.0, 2251.0, 2500.0),
            emitted.map { it.second },
            "a rising edge draws AT it, a falling edge one step after it, and the turn AT the cycle",
        )
        // AND THE CELLS, which is the whole of the mapping and is emphatically not `[0..5]`. Asserted
        // separately from the frames because a cell column identical to the frame column would let a
        // swapped or offset mapping through on a roll of `(0,1,2,3,4,5)` — and this roll is not one.
        val emittedCells = emitted.map { pongCells[it.first] }
        assertEquals(
            listOf(3, 5, 1, 0, 4, 0, 1, 5, 3, 7),
            emittedCells,
            "the cells those ten frames name, in order",
        )
        for (i in 1 until emitted.size) {
            assertTrue(
                emitted[i].first != emitted[i - 1].first,
                "frame ${emitted[i].first} was EMITTED twice in a row, at ${emitted[i].second}",
            )
            assertTrue(
                emittedCells[i] != emittedCells[i - 1],
                "and cell ${emittedCells[i]} was drawn twice in a row, at ${emitted[i].second}",
            )
        }

        // `asBoard` READS the roll and hands back a value. It writes nothing — there is no document
        // to write to, which is Decision 15 and the reason a preview is free of the model.
        val before = roll
        roll.asBoard(12f, RECT)
        assertEquals(before, roll, "asBoard must not touch the roll it was asked to play")
        // And a bad index is refused rather than answered: a caller holding one has a bug, and a
        // wrong cell is a wrong picture rather than a wrong number.
        assertFailsWith<IllegalArgumentException> { roll.cellAtIndex(-1) }
        assertFailsWith<IllegalArgumentException> { roll.cellAtIndex(3) }
        assertFailsWith<IllegalArgumentException> { roll.cellAtIndex(Int.MAX_VALUE) }
    }

    // ── 11. three words, three modes, one for one ──────────────────────────────

    /**
     * Decision 11, on both halves: the VOCABULARY (three words, closed, refused in words when
     * unknown — an unknown word stored verbatim is a preset that plays as a loop) and the PLAYBACK
     * that vocabulary means, sampled at slot midpoints on the same six-entry roll as test 9.
     *
     * The eleventh sample is the first midpoint of the SECOND cycle, and it is the only way to see
     * what a mode does at its wrap: the first cycle of a PING_PONG and of a LOOP are identical by
     * construction, and a test that stopped at `rangeMs` would not have told them apart at all.
     */
    @Test
    fun theThreeWrapModesMapOneForOneOntoTheAppsOwnWords() {
        assertEquals(PlayMode.LOOP, CellRollGrammar.modeOf("loop"))
        assertEquals(PlayMode.PING_PONG, CellRollGrammar.modeOf("pingpong"))
        assertEquals(PlayMode.ONCE, CellRollGrammar.modeOf("once"))

        val refused = assertFailsWith<IllegalArgumentException> { CellRollGrammar.modeOf("bounce") }
        for (word in listOf("loop", "pingpong", "once")) {
            assertTrue(refused.message!!.contains(word), "the refusal must name \"$word\": ${refused.message}")
        }
        // The vocabulary is CLOSED, so a near miss is refused too rather than folded onto the nearest
        // word: two vocabularies for three values is how an export stops opening in the app at all.
        for (bad in listOf("Loop", "PINGPONG", "ping-pong", "pingPong", "", " loop", "loop ", "boomerang")) {
            assertFailsWith<IllegalArgumentException>("\"$bad\" must be refused") { CellRollGrammar.modeOf(bad) }
        }

        val board = rollOf(e(0, 3), e(1, 3), e(2, 3), e(3, 3), e(4, 3), e(5, 3)).asBoard(12f, RECT)
        val midpoints = (0..10).map { it * 250.0 + 125.0 }

        val loop = PlaybackClock(board, mode = CellRollGrammar.modeOf("loop"))
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5, 0, 1, 2, 3, 4),
            midpoints.map { loop.frameIndexAt(it) },
            "LOOP wraps at rangeMs, so the sample after 5 is 0 and not 4",
        )

        val pong = PlaybackClock(board, mode = CellRollGrammar.modeOf("pingpong"))
        val pongSeen = midpoints.map { pong.frameIndexAt(it) }
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5, 4, 3, 2, 1, 0),
            pongSeen,
            "PING_PONG turns at rangeMs, walks the interior back, and is on 0 again at the cycle",
        )
        for (i in 1 until pongSeen.size) {
            assertTrue(pongSeen[i] != pongSeen[i - 1], "frame ${pongSeen[i]} is showing twice in a row at $i")
        }

        val once = PlaybackClock(board, mode = CellRollGrammar.modeOf("once"))
        assertEquals(1500.0, once.rangeMs, 1e-9, "rangeMs is a range in every mode")
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5, 5, 5, 5, 5, 5),
            midpoints.map { once.frameIndexAt(it) },
            "ONCE parks on the LAST frame from the end of the range onwards and never wraps",
        )
        // `Finish` is LEVEL triggered, not once: `isFinished` is a pure function of the elapsed time,
        // so asking past the end returns the same answer for ever and a player must treat it as a
        // standing fact. See `Step.Finish`'s own KDoc.
        assertEquals(false, once.isFinished(once.rangeMs - 1.0), "a millisecond before the end is not finished")
        assertEquals(true, once.isFinished(once.rangeMs), "the level flips AT rangeMs")
        assertEquals(true, once.isFinished(once.rangeMs + 1.0), "and STAYS flipped")
        assertEquals(true, once.isFinished(once.rangeMs * 1_000.0), "an hour later too")
        val stepper = FrameStepper(once)
        assertEquals(
            listOf<Step>(Step.Finish),
            stepper.step(once.rangeMs, 5, once.audioPositionMs(once.rangeMs)),
            "with the last frame already up, the standing fact is ALL of the step",
        )
        assertEquals(
            listOf<Step>(Step.Finish),
            stepper.step(once.rangeMs * 1_000.0, 5, once.audioPositionMs(once.rangeMs * 1_000.0)),
            "and asked an hour later it is the same list, which is what level-triggered means",
        )
        // And the other two never finish, in any of the three modes' own words.
        for (mode in listOf(PlayMode.LOOP, PlayMode.PING_PONG)) {
            val other = PlaybackClock(board, mode = mode)
            for (t in listOf(0.0, 1_500.0, 2_500.0, 3_600_000.0)) {
                assertEquals(false, other.isFinished(t), "$mode finished at $t")
            }
        }
    }

    // ── 12. the preview runs at the BOARD's rate and not at its own ─────────────

    /**
     * Decision 12. A sprite preview that quietly ran at a different speed from the animation board is
     * exactly the "preview is not the export" bug this project guards against in six other places,
     * and the only way it can happen is a second cadence setting nobody can see.
     *
     * **The tolerance is 1e-9 ms — a nanosecond** — and it is named rather than hidden. `1000 / 24` is
     * not a whole number of milliseconds, so at 24 fps the range cannot be an exact comparison. The
     * tolerance is on the RANGE only; the frame boundaries below are divided out of the clock's own
     * `rangeMs` so that **no per-frame duration is written out by hand anywhere in this test**.
     */
    @Test
    fun thePreviewPlaysAtTheBoardsOwnFpsAndNotAtItsOwn() {
        val roll = rollOf(e(0), e(1), e(2))
        val atTwelve = roll.asBoard(12f, RECT)
        val atTwentyFour = roll.asBoard(24f, RECT)
        assertEquals(12f, atTwelve.fps, "the rate is the CALLER's, read from the board, not a second setting")
        assertEquals(24f, atTwentyFour.fps, "and 24 is not the model's own default of 12")

        val twelve = PlaybackClock(atTwelve)
        val twentyFour = PlaybackClock(atTwentyFour)
        assertEquals(250.0, twelve.rangeMs, 1e-9, "three one-tick frames at 12 fps is 250 ms")
        assertEquals(125.0, twentyFour.rangeMs, 1e-9, "the same three frames at 24 fps are half as long")
        assertEquals(twelve.rangeMs, twentyFour.rangeMs * 2.0, 1e-9, "and exactly twice, as a rate is")

        // The per-frame boundary, derived from each clock's own range rather than written out, and
        // asked a thousandth of a millisecond either side of it so that a boundary off by a rounding
        // could not pass by landing between two samples.
        for (clock in listOf(twelve, twentyFour)) {
            for (k in 1..2) {
                val boundary = clock.rangeMs * k / 3.0
                assertEquals(k, clock.frameIndexAt(boundary), "at the boundary $k of a three-frame range")
                assertEquals(k - 1, clock.frameIndexAt(boundary - 0.001), "just before that boundary")
            }
        }
    }

    // ── 13. the order badge ────────────────────────────────────────────────────

    /**
     * A cell in the roll three times shows the order of its **LAST** entry, and the tie to the
     * removal is the non-vacuity: what the badge names is exactly what a badge tap takes back
     * (Decision 2), so a badge that pointed at a first use would point at an entry the finger
     * cannot reach.
     */
    @Test
    fun aCellInTheRollThreeTimesShowsItsLastOrdersBadge() {
        val roll = rollOf(e(3), e(1), e(3))
        assertEquals(3, roll.lastOrderOf(3), "the LAST entry of cell 3 is the third")
        assertEquals(2, roll.lastOrderOf(1), "and cell 1's only entry is the second")
        assertNull(roll.lastOrderOf(0), "a cell that is not in the roll has no badge at all")
        assertNull(roll.lastOrderOf(99), "and neither has one past the end of the sheet")
        assertNull(CellRoll().lastOrderOf(0), "nor has anything on an empty roll")

        // The badge is the index a badge tap removes, 1-based.
        val after = roll.untapped(3)
        assertEquals(listOf(3, 1), cellsOf(after), "the last use went")
        assertEquals(1, after.lastOrderOf(3), "and the badge now names the only use that is left")
        for (cell in 0 until CELLS) {
            assertNull(roll.cleared().lastOrderOf(cell), "a cleared roll badges nothing at all")
        }
    }

    // ── 14. every hold is inside the range the app reads ───────────────────────

    /**
     * The outer range a hold must fit inside comes from the app, not from this file: the app's
     * `SequenceTiming.MIN_WEIGHT` / `MAX_WEIGHT` = 1..9999, read at `SpriteSheet.java:651` and
     * enforced by the packer, which **refuses** a weight it would have to change on read.
     *
     * 1..16 is far inside that, which is the point: a roll can therefore never hand the exporter a
     * weight the app would silently alter — the failure mode the app's own `SequenceTiming.fit`
     * calls out, and the one R36's ruling was about. And the two lists are of the same length
     * because they are per ENTRY, which is the other half of it.
     */
    @Test
    fun theHoldsAreAlwaysInsideTheRangeTheAppReads() {
        for (roll in suiteRolls) assertReadable(roll, "a roll the suite builds")
        assertEquals(12, suiteRolls.size, "so the list really is walked and not empty")

        // 200 pseudo-random moves — taps, bumps in both directions, focus moves and badge taps —
        // each one checked. A range that holds for the hand-built rolls and not for a sequence of
        // moves is a range nobody has actually proved.
        val rng = Lcg(0x5EED_4B02L)
        var roll = rollOf(e(0), e(1), e(2), e(3))
        var moves = 0
        var bumps = 0
        while (moves < 200) {
            val cell = rng.int() and (CELLS - 1)
            roll = when (rng.int() and 3) {
                0 -> roll.tapped(cell, CELLS)
                1 -> {
                    bumps++
                    roll.holdBumped(rng.int() % 121 - 60)
                }
                2 -> roll.focused(rng.int() % 9 - 4)
                else -> roll.untapped(cell)
            }
            assertReadable(roll, "after move $moves")
            moves++
        }
        assertEquals(200, moves)
        assertTrue(bumps > 0, "the walk did $bumps hold bumps, so the range was exercised and not just tapped")
    }

    private fun assertReadable(roll: CellRoll, what: String) {
        val (cells, holds) = roll.cellsAndHolds()
        assertEquals(roll.entries.size, cells.size, "$what: the cell list is one per entry")
        assertEquals(roll.entries.size, holds.size, "$what: the hold list is one per entry, in parallel")
        for (entry in roll.entries) {
            assertTrue(
                entry.hold in CellRoll.MIN_HOLD..CellRoll.MAX_HOLD,
                "$what: entry ${entry.cell} is held for ${entry.hold}, outside 1..16",
            )
        }
    }

    // ── 15. two parallel lists, never repeated indices ──────────────────────────

    /**
     * Decision 13, and the whole of it. A hold of 3 on cell 0 followed by cell 1 is
     * `cells = [0, 1]`, `holds = [3, 1]` — **two entries, not four** — and emphatically not
     * `cells = [0, 1, 1, 1]`, `holds = []`.
     *
     * Both spell the same animation and only one of them is a hold a person can still change: by the
     * time the repeats are in the list the length is gone, and the app reads a per-frame weight
     * beside its frame rather than a frame that happens to be repeated. The old Q2 was the story of
     * what happens when the second spelling is written.
     */
    @Test
    fun cellsAndHoldsIsTwoParallelListsAndNeverRepeatedIndices() {
        val roll = rollOf(e(0, 3), e(1))
        val (cells, holds) = roll.cellsAndHolds()
        assertEquals(listOf(0, 1), cells, "two entries, not four")
        assertEquals(listOf(3, 1), holds, "and the holds beside them, in the same order")
        assertEquals(2, cells.size, "TWO entries, not four")
        assertEquals(2, holds.size, "and two holds, not an empty list of them")
        assertEquals(cells.size, holds.size, "parallel means the same length")
        assertNotEquals(listOf(0, 1, 1, 1), cells, "a hold of 3 is a weight, not three more cells")
        assertTrue(holds.isNotEmpty(), "and emphatically not the empty list the repeats spelling needs")

        // And for EVERY roll in the suite, so the invariant is not a claim about one hand-built pair.
        for (one in suiteRolls) {
            val (c, h) = one.cellsAndHolds()
            assertEquals(one.entries.size, c.size, "${pairsOf(one)}: one cell per entry")
            assertEquals(one.entries.size, h.size, "${pairsOf(one)}: one hold per entry, in parallel")
            assertEquals(one.entries.map { it.cell }, c, "${pairsOf(one)}: the cells, in order")
            assertEquals(one.entries.map { it.hold }, h, "${pairsOf(one)}: the holds, in the same order")
        }
    }

    // ── 16. a second finger ends the gesture; a long-press is not also a tap ────

    @Test
    fun aSecondFingerEndsARollGestureWithNothingApplied() {
        val roll = rollOf(e(3), e(1), e(3))
        assertEquals(roll, CellRollGrammar.apply(roll, CellGesture.Cancel, CELLS), "Decision 14: nothing applied")
        assertEquals(false, CellRollGrammar.isBulk(CellGesture.Cancel), "and it is not a bulk gesture")

        assertEquals(true, CellRollGrammar.isBulk(CellGesture.PlayAll), "PlayAll replaces the whole roll")
        assertEquals(true, CellRollGrammar.isBulk(CellGesture.Clear), "Clear empties the whole roll")
        for (gesture in listOf(
            CellGesture.Tapped(0), CellGesture.BadgeTapped(0), CellGesture.BadgeHeld(0),
            CellGesture.HoldBumped(1), CellGesture.Focused(0), CellGesture.Cancel,
        )) {
            assertEquals(false, CellRollGrammar.isBulk(gesture), "$gesture touches one entry, not the roll")
        }

        // The INT-ness of the count is itself the assertion, and the only honest way to say it in
        // Kotlin: a `Int?` return would not assign to an `Int` without a `!!` nobody wrote.
        val removed: Int = CellRollGrammar.removedBy(roll, CellGesture.Cancel, CELLS)
        assertEquals(0, removed, "a second finger removes nothing")
        assertEquals(0, CellRollGrammar.removedBy(roll, CellGesture.Tapped(3), CELLS), "nor does a tap")
        assertEquals(0, CellRollGrammar.removedBy(roll, CellGesture.HoldBumped(1), CELLS), "nor a bump")
        assertEquals(0, CellRollGrammar.removedBy(roll, CellGesture.Focused(2), CELLS), "nor a focus move")
        assertEquals(0, CellRollGrammar.removedBy(roll, CellGesture.PlayAll, CELLS), "nor PlayAll")
        assertEquals(0, CellRollGrammar.removedBy(roll, CellGesture.Clear, CELLS), "nor Clear")
        // And the two that do remove are counted, not guessed at.
        assertEquals(1, CellRollGrammar.removedBy(roll, CellGesture.BadgeTapped(3), CELLS))
        assertEquals(2, CellRollGrammar.removedBy(roll, CellGesture.BadgeHeld(3), CELLS))
        assertEquals(0, CellRollGrammar.removedBy(roll, CellGesture.BadgeTapped(5), CELLS), "and 0 is a number")
    }

    /**
     * Decision 16: a long-press on a cell BODY opens the view half's per-cell menu, and a long-press
     * that ALSO fired a tap would put a cell in the roll twice by accident.
     *
     * There is no `CellGesture.CellLongPressed` to test, and that is the claim: the type does not
     * exist, so `CellRollShapeTest` says so **by reflection** — a `commonTest` cannot open
     * `::class.java`, which is the Lead's slip 3.
     */
    @Test
    fun aLongPressFiresNoTap() {
        val roll = rollOf(e(1))
        val afterTap = CellRollGrammar.apply(roll, CellGesture.Tapped(4), CELLS)
        assertEquals(listOf(1, 4), cellsOf(afterTap), "the tap put the cell in ONCE")
        assertEquals(1, CellRollGrammar.removedBy(afterTap, CellGesture.BadgeHeld(4), CELLS), "once, not twice")

        val afterHold = CellRollGrammar.apply(afterTap, CellGesture.BadgeHeld(4), CELLS)
        assertEquals(listOf(1), cellsOf(afterHold), "and the long-press takes that one entry back, all of it")
        assertEquals(1, afterHold.size, "so a double-fire cannot leave the cell in twice")
        // The tap and the long-press are the two halves of one gesture and the second one undoes the
        // first, which is the only arrangement in which "a long-press never also fires a tap" is
        // visible at all from here.
        assertEquals(roll, afterHold, "a tap followed by its own long-press is a no-op overall")
    }

    // ── 17. the cursor after a removal ─────────────────────────────────────────

    /**
     * Decision 17, which the draft did not decide and a builder would otherwise have had to invent.
     *
     * `unAddCell` line 4137 is `if (labCur >= labSeq.size()) labCur = Math.max(0, labSeq.size() - 1);`
     * and lines 4139-4141 put it back to 0 when the roll empties; `pruneDeadFrames` line 4229 says
     * the same thing again. The two obvious answers are both wrong: leaving the cursor where it was
     * points at a DIFFERENT entry, and resetting it to 0 loses the person's place in a long roll
     * because they took one chip back.
     */
    @Test
    fun theCursorIsClampedDownAfterEveryRemoval() {
        // 1. A badge tap at the end. `unAddCell` :4137
        val three = rollOf(e(0), e(1), e(2), cursor = 2)
        val afterBadge = three.untapped(2)
        assertEquals(listOf(0, 1), cellsOf(afterBadge), "the last entry is gone")
        assertEquals(1, afterBadge.cursor, "and the cursor is on the last SURVIVING entry, not left at 2")

        // 2. The same roll pruned rather than badge-tapped. `pruneDeadFrames` :4229
        val pruned = three.prunedTo(2)
        assertEquals(listOf(0, 1), cellsOf(pruned.roll), "cell 2 is not on a two-cell grid")
        assertEquals(1, pruned.dropped, "and the count says so")
        assertEquals(1, pruned.roll.cursor, "which clamps the cursor the same way")

        // 3. The last entry of a one-entry roll. :4139-4141
        val emptied = rollOf(e(0), cursor = 0).untapped(0)
        assertTrue(emptied.isEmpty, "nothing is left")
        assertEquals(0, emptied.cursor, "and 0 is what an empty roll's cursor is, not -1")

        // 4. THE CASE THAT CATCHES A CLAMP TO `size` INSTEAD OF `size - 1`. The cursor was 0, it
        //    is still 0, and a clamp to `size` would leave it at 1 — one past the end of a
        //    one-entry roll, which every reader would then have to clamp again.
        val kept = rollOf(e(0), e(1), cursor = 0).untapped(0)
        assertEquals(listOf(1), cellsOf(kept), "one entry left")
        assertEquals(0, kept.cursor, "0 is still in range, and clamping DOWN never moves a legal index")

        // And the two exceptions the decision names, so the clamp cannot be tightened into them.
        assertEquals(0, rollOf(e(0), e(1), e(2), cursor = 2).cleared().cursor, "Clear puts it at 0")
        assertEquals(0, rollOf(e(0), e(1), e(2), cursor = 2).playAll(CELLS).cursor, "and so does PlayAll")
    }

    // ── the small parts ────────────────────────────────────────────────────────

    /**
     * A fixed linear congruential generator, because `kotlin.random` promises nothing about its
     * algorithm across versions and a test whose failures cannot be reproduced is a rumour.
     * Numerical Recipes' constants; the high word is the entropy.
     */
    private class Lcg(seed: Long) {
        private var s: Long = seed

        fun int(): Int {
            s = s * 6364136223846793005L + 1442695040888963407L
            return (s ushr 32).toInt()
        }
    }
}

package cc.joycreator.joybrush.core.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * JB-0.10, the harness half. These tests are the reason [Bench] owns no clock: [measure]'s samples,
 * [median], [p95], the three verdicts and the whole of [report] are checked against numbers this file
 * chose, so a change to the arithmetic is a red test rather than a different number on a phone.
 *
 * NOT ONE TEST HERE ASSERTS A WALL CLOCK. Every timing figure in this file is produced by the fake
 * clock below. A benchmark test that asserts milliseconds is flaky by construction -- JB-5.10's was,
 * and it was removed for exactly that reason (R28) -- and in a harness the timing IS the output, so
 * the millisecond figures are REPORTED and never asserted. What IS asserted is everything that does
 * not vary with machine load: the sample count, the bracket, the order statistics, the verdicts, the
 * checksum and the exact text of the report.
 *
 * This is a CLASS on purpose: JUnit runs methods on an instance, so a file of top-level `@Test`
 * functions compiles, reports green and executes nothing.
 */
class BenchTest {

    // ---------------------------------------------------------------- the fake clock

    /**
     * A clock this file scripts. [step] is how far it jumps on every reading, so the samples are
     * exactly the gaps between consecutive calls and nothing depends on how fast the machine is.
     */
    private class FakeClock(private val step: Long) {
        var reads = 0
            private set
        private var t = 0L

        fun now(): Long {
            reads++
            t += step
            return t
        }
    }

    private fun case(name: String, body: () -> Long) = BenchCase(name, body)

    private fun measured(
        name: String = "case",
        samples: List<Double> = listOf(100.0),
        budgetMs: Double = 100.0,
        checksum: Long = 1L,
    ) = BenchOutcome.Measured(name, samples, budgetMs, checksum)

    // ---------------------------------------------------------------- 1. the bracket and the count

    @Test
    fun measureKeepsExactlyRepeatsSamplesAndTheWarmUpsAreNotAmongThem() {
        // warmup 2 + repeats 5 = 7 calls to run(), 5 of them kept. If the warm-ups leaked into the
        // samples the count would be 7; if they were skipped the counter would reach 5.
        var ran = 0L
        val out = Bench.measure(
            case = case("warmups") { ++ran },
            warmup = 2,
            repeats = 5,
            nowMs = { 0L },
        )
        assertEquals(7L, ran, "run() must be called warmup + repeats times and no more")
        assertEquals(5, out.samplesMs.size, "exactly repeats samples are kept")
    }

    @Test
    fun theTwoClockCallsAreTheBracketAroundTheRunAndNothingElse() {
        // THE BRACKET TEST. The clock jumps 10 per reading, so each sample is 10 (before/after of
        // one iteration) and the clock is read 2 * repeats times in total. If the bracket were drawn
        // before the warm-ups, or if a third call crept in per iteration, these four numbers move.
val clock = FakeClock(step = 10L)
        val out = Bench.measure(
            case = case("bracketed") { 0L },
            warmup = 3,
            repeats = 4,
            nowMs = clock::now,
        )
        assertEquals(listOf(10.0, 10.0, 10.0, 10.0), out.samplesMs, "each sample is one before/after gap")
        assertEquals(2 * 4, clock.reads, "exactly two clock readings per kept run")
    }

    @Test
    fun aBackwardsClockGivesANegativeSampleThatIsKept() {
        // Decision 1. The clock alternates 5, 1, 1, 5, 5, 1, 1, 5, so every bracket runs backwards on the
        // odd sample and forwards on the even one. The negative samples are a fact about the run;
        // dropping them would let a broken clock improve the median.
        val ticks = longArrayOf(5L, 1L, 1L, 5L, 5L, 1L, 1L, 5L)
        var i = 0
        val out = Bench.measure(
            case = case("backwards") { 0L },
            warmup = 1,
            repeats = 4,
            nowMs = { ticks[i++] },
        )
        assertEquals(listOf(-4.0, 4.0, -4.0, 4.0), out.samplesMs)
        assertEquals(0.0, out.medianMs, "the middle two of -4, -4, 4, 4 average to zero")
        assertEquals(2, out.samplesMs.count { it < 0.0 }, "both negative samples are in the list, not filtered out")
    }

    // ---------------------------------------------------------------- 2. refusals, in words

    @Test
    fun aWarmupOrRepeatsBelowOneIsRefusedInWordsNamingTheCaseAndTheNumber() {
        val c = case("psd-write") { 0L }
        val noWarmup = assertFailsWith<IllegalArgumentException> {
            Bench.measure(c, warmup = 0, repeats = 3, nowMs = { 0L })
        }
        assertTrue(noWarmup.message!!.contains("psd-write"), "the message names the case: ${noWarmup.message}")
        assertTrue(noWarmup.message!!.contains("0"), "the message names the offending number: ${noWarmup.message}")

        val noRepeats = assertFailsWith<IllegalArgumentException> {
            Bench.measure(c, warmup = 3, repeats = -1, nowMs = { 0L })
        }
        assertTrue(noRepeats.message!!.contains("psd-write"), "the message names the case: ${noRepeats.message}")
        assertTrue(noRepeats.message!!.contains("-1"), "the message names the offending number: ${noRepeats.message}")
    }

    @Test
    fun oneRepeatIsAllowedAndGivesExactlyOneSample() {
        // The floor is 1 and not 2: one measured run is a worse median than nine but it is still a
        // measurement, and refusing it would make "how expensive is this once" unaskable.
        val out = Bench.measure(case("once") { 7L }, warmup = 1, repeats = 1, nowMs = { 0L })
        assertEquals(1, out.samplesMs.size)
        assertEquals(7L, out.checksum)
    }

    @Test
    fun anEmptySampleListHasNoMedianAndNoP95AndSaysSo() {
        val e = assertFailsWith<IllegalArgumentException> { Bench.median(emptyList()) }
        assertTrue(e.message!!.contains("median"), e.message!!)
        val p = assertFailsWith<IllegalArgumentException> { Bench.p95(emptyList()) }
        assertTrue(p.message!!.contains("p95"), p.message!!)
    }

    // ---------------------------------------------------------------- 3. the order statistics

    @Test
    fun medianIsTheMiddleAndTheMeanOfTheTwoMiddles() {
        assertEquals(2.0, Bench.median(listOf(1.0, 2.0, 3.0)))
        assertEquals(2.5, Bench.median(listOf(1.0, 2.0, 3.0, 4.0)))
        assertEquals(7.0, Bench.median(listOf(7.0)))
        // Unsorted input is sorted first, so the median is of the values and not of their positions.
        assertEquals(2.0, Bench.median(listOf(3.0, 1.0, 2.0)))
        assertEquals(0.0, Bench.median(listOf(-1.0, 1.0)))
    }

    @Test
    fun p95IsTheNearestRankAndIsNeverInterpolated() {
        assertEquals(5.0, Bench.p95(listOf(5.0)))
        // n = 2: ceil(0.95 * 2) = 2, so the 2nd smallest, which is a sample and not their mean.
        assertEquals(2.0, Bench.p95(listOf(1.0, 2.0)))
        assertNotEquals(1.5, Bench.p95(listOf(1.0, 2.0)), "an interpolated p95 would be the mean here")
        // n = 20: ceil(0.95 * 20) = 19, so the sample at index 18 -- the 19th smallest of 1..20.
        assertEquals(19.0, Bench.p95((1..20).map { it.toDouble() }))
        assertEquals(19.0, Bench.p95((20 downTo 1).map { it.toDouble() }), "and sorting is internal")
    }

    // ---------------------------------------------------------------- 4. the verdicts

    @Test
    fun theVerdictIsTheMedianAgainstTheBudgetAndEqualityPasses() {
        assertEquals(Verdict.WITHIN_BUDGET, measured(samples = listOf(100.0), budgetMs = 100.0).verdict())
        assertEquals(Verdict.OVER_BUDGET, measured(samples = listOf(100.1), budgetMs = 100.0).verdict())
        // The comparison is `>` on purpose: a case landing exactly on its budget has not missed it.
        assertEquals(Verdict.WITHIN_BUDGET, measured(samples = listOf(100.0, 50.0, 50.0), budgetMs = 100.0).verdict())
    }

    @Test
    fun aBudgetThatIsNotAPositiveFiniteNumberIsNoBudgetAndNeverASilentPass() {
        // THE TEST THAT HAS TO EXIST. `medianMs > NaN` is false, so an implementation that only wrote
        // `if (median > budget) OVER else WITHIN` calls all four of these "within budget" -- a green
        // tick on a case nobody ever set a target for. NaN, 0, a negative and both infinities are
        // "the table has no entry", and a missing budget is printed, not laundered into a pass.
        val fast = listOf(1.0)
        assertEquals(Verdict.NO_BUDGET, measured(samples = fast, budgetMs = Double.NaN).verdict())
        assertEquals(Verdict.NO_BUDGET, measured(samples = fast, budgetMs = 0.0).verdict())
        assertEquals(Verdict.NO_BUDGET, measured(samples = fast, budgetMs = -5.0).verdict())
        assertEquals(Verdict.NO_BUDGET, measured(samples = fast, budgetMs = Double.POSITIVE_INFINITY).verdict())
        assertEquals(Verdict.NO_BUDGET, measured(samples = fast, budgetMs = Double.NEGATIVE_INFINITY).verdict())
        // And the contrast that proves the guard is not simply "everything is NO_BUDGET".
        assertEquals(Verdict.WITHIN_BUDGET, measured(samples = fast, budgetMs = 2.0).verdict())
    }

    @Test
    fun theMedianIsWhatIsJudgedAndNotTheWorstSample() {
        // Decision 3: one 900 ms sample in five does not move a 500 ms median, and a person feels the
        // median. The p95 is still there for whoever wants the tail.
        val samples = listOf(500.0, 500.0, 500.0, 500.0, 900.0)
        val m = measured(samples = samples, budgetMs = 600.0)
        assertEquals(500.0, m.medianMs)
        assertEquals(900.0, m.worstMs)
        assertEquals(Verdict.WITHIN_BUDGET, m.verdict())
    }

    // ---------------------------------------------------------------- 5. the checksum

    @Test
    fun theChecksumIsTheFoldOfTheKeptRunsAndNothingElse() {
        // A case returning a fresh counter gives the same XOR whichever order the readings arrived
        // in, which is what makes a checksum comparable between two runs of the harness.
        var counter = 0L
        val a = Bench.measure(case("counter") { ++counter }, warmup = 1, repeats = 4, nowMs = { 0L })
        counter = 0
        val b = Bench.measure(case("counter") { ++counter }, warmup = 1, repeats = 4, nowMs = { 1L })
        assertEquals(a.checksum, b.checksum, "timings changed, the work did not, so the checksum must not move")

        // And it is the KEPT runs only: the warm-ups are discarded, not folded.
        var ran = 0L
        val c = Bench.measure(case("counted") { ++ran }, warmup = 2, repeats = 3, nowMs = { 0L })
        // 3 ^ 4 ^ 5 == 2 -- the two warm-up results (1, 2) are not in it.
        assertEquals(2L, c.checksum)
    }

    @Test
    fun aCaseThatReturnsZeroChecksumsToZeroSoDidNothingIsLegible() {
        val out = Bench.measure(case("nothing") { 0L }, warmup = 2, repeats = 5, nowMs = { 0L })
        assertEquals(0L, out.checksum, "\"it returned nothing\" has to show up as a 0 on the report line")
    }

    // ---------------------------------------------------------------- 6. the report, pinned exactly

    @Test
    fun theReportIsPinnedToTheCharacterForOneWithinOneOverAndOneUnavailable() {
        // The exact layout, because this text is pasted into a review file by hand and a test that
        // only checked for substrings would let a mangled figure through.
        val text = Bench.report(
            listOf(
                measured(name = "flood-fill", samples = listOf(10.0, 20.0, 30.0), budgetMs = 400.0, checksum = -2L),
                measured(name = "tile-deflate-6", samples = listOf(90.0, 90.0, 110.0), budgetMs = 80.0, checksum = 7L),
                BenchOutcome.Unavailable("psd-write", "the PSD writer is JB-2.14c and is not built"),
            ),
            device = "SM-N960F Android 10",
        )
        val expected = listOf(
            "device: SM-N960F Android 10",
            // Median of 10, 20, 30 is 20; p95 is ceil(0.95*3) = 3rd = 30; worst 30; 20 <= 400 so WITHIN.
            "flood-fill      median 20.0 ms  p95 30.0 ms  worst 30.0 ms  budget 400.0 ms  WITHIN  checksum -2",
            // Median of 90, 90, 110 is 90; 90 > 80 so OVER.
            "tile-deflate-6  median 90.0 ms  p95 110.0 ms  worst 110.0 ms  budget 80.0 ms  OVER  checksum 7",
            "psd-write       NOT MEASURED  the PSD writer is JB-2.14c and is not built",
            "2 measured, 1 over budget, 1 not measured",
            "not measured: psd-write (the PSD writer is JB-2.14c and is not built)",
        ).joinToString("\n")
        assertEquals(expected, text)
    }

    @Test
    fun aReportOfNothingSaysSoInWordsAndIsNotABareHeader() {
        // The failure this guards: an empty report printing just `device: X`, which a reader skims
        // past as "everything was fine and there was nothing to print".
        val text = Bench.report(emptyList(), device = "SM-N960F Android 10")
        assertEquals(
            listOf(
                "device: SM-N960F Android 10",
                "nothing to report: no cases were measured",
            ).joinToString("\n"),
            text,
        )
        assertTrue(!text.contains("measured,"), "an empty report must not print a summary that reads like a result")
    }

    @Test
    fun aBlankDevicePrintsNotStatedAndTheCallersStringOtherwise() {
        // The device string is never guessed. A number measured on an unnamed device is not a
        // measurement of the Note 9 whatever the file is filed under.
        assertTrue(
            Bench.report(listOf(measured()), device = "").startsWith("device: (not stated)\n"),
            "an empty device string",
        )
        assertTrue(
            Bench.report(listOf(measured()), device = "   ").startsWith("device: (not stated)\n"),
            "a blank device string",
        )
        assertTrue(
            Bench.report(listOf(measured()), device = "Pixel 8a Android 14").startsWith("device: Pixel 8a Android 14\n"),
            "the caller's own string is printed unchanged",
        )
    }

    @Test
    fun theSummaryCountsTheThreeKindsSeparatelyAndNamesWhatWasNotMeasured() {
        val text = Bench.report(
            listOf(
                measured(name = "a", samples = listOf(1.0), budgetMs = 10.0),
                measured(name = "b", samples = listOf(1.0), budgetMs = 10.0),
                BenchOutcome.Unavailable("psd-write", "the PSD writer is JB-2.14c and is not built"),
            ),
            device = "test",
        )
        // A summary reading "2 cases, 0 failures" for a run that measured two of three is the lie
        // Decision 6 exists to prevent, so the third kind is counted in its own right...
        assertTrue(text.contains("2 measured, 0 over budget, 1 not measured"), text)
        // ...and named, so a reader who reads only the last two lines still knows.
        assertTrue(text.contains("not measured: psd-write"), text)
    }

    @Test
    fun everyMillisecondFigureCarriesExactlyOneDecimalPlace() {
        // 100.0 must not print as "100" and 1.25 as "1.3" on one machine and "1.2" on another:
        // the format is fixed here rather than left to a platform formatter, because this file is
        // commonMain and String.format is not.
        val text = Bench.report(
            listOf(measured(name = "x", samples = listOf(1.25, 1.25, 1.25), budgetMs = 100.0)),
            device = "d",
        )
        assertTrue(text.contains("median 1.3 ms"), text)
        assertTrue(text.contains("p95 1.3 ms"), text)
        assertTrue(text.contains("worst 1.3 ms"), text)
        assertTrue(text.contains("budget 100.0 ms"), text)
    }

    @Test
    fun aCaseWithNoBudgetPrintsTheFigureAndSaysSoInTheVerdictColumn() {
        // The number is still printed (NaN, because the table has no entry) rather than a blank, so
        // the line cannot be read as a case that measured 0 ms against a budget of nothing.
        val text = Bench.report(
            listOf(measured(name = "x", samples = listOf(12.0), budgetMs = Double.NaN)),
            device = "d",
        )
        assertTrue(text.contains("budget NaN ms  no budget set"), text)
        assertTrue(text.contains("1 measured, 0 over budget, 0 not measured"), text)
        assertTrue(!text.contains("not measured: x"), "NO_BUDGET is not the same as NOT MEASURED")
    }

    @Test
    fun anOverBudgetCaseIsReportedAndNeverThrown() {
        // Decision 9: the harness has no failure path, because "this moves to C++" is the Lead's call
        // and a throwing harness would have the numbers re-run until they looked good.
        val out = measured(name = "slow", samples = listOf(5000.0), budgetMs = 10.0)
        assertEquals(Verdict.OVER_BUDGET, out.verdict())
        val text = Bench.report(listOf(out), device = "d")
        assertTrue(text.contains("OVER"), text)
        assertTrue(text.contains("1 measured, 1 over budget, 0 not measured"), text)
    }
}

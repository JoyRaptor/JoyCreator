package cc.joycreator.joybrush.core.bench

import kotlin.math.ceil
import kotlin.math.floor

/**
 * One measured job. [run] does the work and returns a number derived from it.
 *
 * WHAT [run] MUST AND MUST NOT DO, because every way of getting this wrong is invisible in the
 * output unless the harness is written to catch it:
 *
 *  - It must do the WHOLE job. Building the input inside [run] means the reported number is the
 *    cost of constructing a fixture plus the job, which is a number about nothing.
 *  - It must return a value DERIVED FROM ITS OWN OUTPUT, not a constant and not a counter the case
 *    owns. A constant survives a refactor that deleted the work; the derived value does not, and
 *    it is what [BenchOutcome.Measured.checksum] prints.
 *  - It must not read a clock. Time comes from `Bench.measure`'s `nowMs`, so that the median, the
 *    p95 and the verdict can be tested without a wall.
 */
class BenchCase(val name: String, val run: () -> Long)

/** How a measured case stands against its budget. `NO_BUDGET` is neither a pass nor a fail. */
enum class Verdict { WITHIN_BUDGET, OVER_BUDGET, NO_BUDGET }

/** What one row of a report is: a number, or the words saying why there is not one. */
sealed class BenchOutcome {

    /**
     * A case that ran. [samplesMs] holds [Bench.measure]'s [repeats] kept samples, warm-ups already
     * discarded, and [checksum] is the fold of every `run()` result so "it did nothing" is legible
     * on the line.
     */
    data class Measured(
        val name: String,
        val samplesMs: List<Double>,
        val budgetMs: Double,
        val checksum: Long,
    ) : BenchOutcome() {

        /** The middle of the samples. This is the figure the verdict is taken from. */
        val medianMs: Double get() = Bench.median(samplesMs)

        /** The tail. Printed because it is information, not because it decides anything. */
        val p95Ms: Double get() = Bench.p95(samplesMs)

        /** The slowest sample. */
        val worstMs: Double get() = samplesMs.max()

        /**
         * [budgetMs] against [medianMs], `>` so landing exactly on the budget passes.
         *
         * THE VALUE IS GUARDED, NOT THE COMPARISON. `medianMs > Double.NaN` is `false`, so a
         * naive `if (medianMs > budgetMs) OVER else WITHIN` calls a case with no budget "within
         * budget" and a benchmark with no target quietly reports a pass. A budget that is not a
         * positive finite number is therefore `NO_BUDGET`: NaN, zero, a negative number and both
         * infinities all mean the table has no entry, which is a fact to print rather than a
         * result to launder into a green tick.
         */
        fun verdict(): Verdict = when {
            !budgetMs.isFinite() || budgetMs <= 0.0 -> Verdict.NO_BUDGET
            medianMs > budgetMs -> Verdict.OVER_BUDGET
            else -> Verdict.WITHIN_BUDGET
        }
    }

    /** The work exists in the plan but not in the tree yet. NOT a pass and NOT a fail. */
    data class Unavailable(val name: String, val reason: String) : BenchOutcome()
}

/**
 * The CPU benchmark harness (JB-0.10): run a case N times, keep the timings, compare the median
 * with a budget, print it in a form a person can paste into a review.
 *
 * THE HARNESS OWNS NO CLOCK. `nowMs` is a parameter and [measure] calls nothing else for time --
 * no `currentTimeMillis`, no `nanoTime`, no sleep, not even a warm-up sleep. Three reasons, in the
 * order they bite: the median, the p95 and the verdict are the part most worth testing and they
 * are untestable behind a wall clock; a sleep in a benchmark is a lie about a phone, since the
 * phone would never sleep before a fill; and a harness that reads its own clock is a harness whose
 * numbers nobody can reproduce on a desk.
 *
 * A MISSED BUDGET IS REPORTED, NEVER ENFORCED. There is no exit code and no exception for
 * `OVER_BUDGET`. "This moves to C++" is the Lead's decision made on the numbers, a builder cannot
 * make it, and a harness that threw would turn every device run into a failure to be re-run until
 * the numbers looked good.
 */
object Bench {

    /** How many runs are thrown away first, so the JIT is not part of the measurement. */
    const val DEFAULT_WARMUP = 3

    /** How many runs are kept. Nine, so the median is the 5th of 9 and a single spike cannot move it. */
    const val DEFAULT_REPEATS = 9

    /**
     * Runs [case] [warmup] times discarding the result, then [repeats] times keeping it.
     *
     * Each kept run is bracketed by EXACTLY two `nowMs` calls and the sample is `(after - before)`.
     * A `before` that is not strictly less than its `after` yields a NEGATIVE sample and the
     * negative sample is KEPT: a clock that went backwards is a fact about the run, and dropping it
     * would let a broken clock make the median look better than the machine is. The caller is
     * expected to pass a monotonic counter; the harness does not enforce that, because refusing to
     * report a run is worse than reporting it.
     *
     * The warm-ups' results are discarded ENTIRELY, not folded into [BenchOutcome.Measured.checksum]:
     * the checksum is the fold of the [repeats] kept results, so it is a property of the kept runs
     * alone and does not change when somebody tunes [DEFAULT_WARMUP].
     *
     * The returned [BenchOutcome.Measured] carries [Double.NaN] as its budget, because a budget is a
     * fact about the PLAN and the plan lives in `androidkit`'s `BenchCases`. `NO_BUDGET` is the
     * honest answer for a case measured with no table, and the caller attaches the table with a
     * `copy`.
     *
     * @throws IllegalArgumentException if [warmup] or [repeats] is below 1, in words naming the case
     *   and the offending number. A `repeats` of 0 would make the median of nothing, and the natural
     *   way to render that is `0.0` -- a benchmark that reports 0 ms for a job it never ran is the
     *   most expensive bug this file could have.
     */
    fun measure(
        case: BenchCase,
        warmup: Int = DEFAULT_WARMUP,
        repeats: Int = DEFAULT_REPEATS,
        nowMs: () -> Long,
    ): BenchOutcome.Measured {
        require(warmup >= 1) {
            "bench \"${case.name}\": warmup must be at least 1, was $warmup"
        }
        require(repeats >= 1) {
            "bench \"${case.name}\": repeats must be at least 1, was $repeats"
        }

        // The warm-ups RUN and are thrown away: their result is not kept, not sampled and not folded
        // into the checksum. They exist so the first few milliseconds of JIT and first-touch page
        // faults are not part of what is reported. They read the clock ZERO times, which is why
        // `theTwoClockCallsAreTheBracketAroundTheRunAndNothingElse` can assert 2 * repeats readings.
        repeat(warmup) { case.run() }

        var checksum = 0L
        val samples = ArrayList<Double>(repeats)
        repeat(repeats) {
            val before = nowMs()
            val value = case.run()
            val after = nowMs()
            // XOR, not sum: a fold, so the checksum does not depend on the order the runs happened
            // in, and so a case whose run() returns a fresh counter every time still gives a stable
            // number to compare between two runs of the harness.
            checksum = checksum xor value
            samples.add((after - before).toDouble())
        }
        return BenchOutcome.Measured(case.name, samples, Double.NaN, checksum)
    }

    /**
     * Middle of the sorted samples; the mean of the two middle ones when the count is even.
     *
     * @throws IllegalArgumentException in words if [samples] is empty. There is no median of no
     *   samples, and the number this file must never print for one is `0.0`.
     */
    fun median(samples: List<Double>): Double {
        require(samples.isNotEmpty()) {
            "bench: a median of no samples is not a number, so this harness does not report one"
        }
        val s = samples.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /**
     * Nearest-rank: the sorted sample at index `ceil(0.95 * n) - 1`. Never interpolated.
     *
     * Nearest-rank rather than interpolated because every consumer of this number is a person
     * reading a pasted line, and the nearest-rank figure is one of the samples they could have
     * measured themselves. Nothing is invented.
     *
     * @throws IllegalArgumentException in words if [samples] is empty, for the same reason as [median].
     */
    fun p95(samples: List<Double>): Double {
        require(samples.isNotEmpty()) {
            "bench: a p95 of no samples is not a number, so this harness does not report one"
        }
        val s = samples.sorted()
        // n >= 1 here, so 0.95 * n >= 0.95 and the rank is at least 1. coerceIn is belt and braces
        // for the day the constant above becomes an expression.
        val rank = ceil(0.95 * s.size).toInt().coerceIn(1, s.size)
        return s[rank - 1]
    }

    /**
     * The whole run as plain text: a `device:` header, one line per outcome, a one-line summary and,
     * when something was not measured, a second line naming it.
     *
     * DETERMINISTIC BY CONSTRUCTION -- names padded to the longest name in the report, every
     * millisecond figure through [oneDecimal], no colour and no box drawing -- because this text
     * gets pasted into a review file by hand and a test pins it as a string equality. [device] is
     * the caller's string (the phone's own model and Android version) and is NEVER guessed here; an
     * empty one prints `device: (not stated)`, because a number measured on an unnamed device is
     * not a measurement of the Note 9 whatever the file says it is for.
     */
    fun report(outcomes: List<BenchOutcome>, device: String): String {
        val stated = device.trim()
        val header = if (stated.isEmpty()) "device: (not stated)" else "device: $stated"
        if (outcomes.isEmpty()) {
            // Not a bare header and not a summary reading "0 measured": a report of nothing that
            // looks like a report of a very fast machine is the worst output this file can have.
            return "$header\nnothing to report: no cases were measured"
        }

        val width = outcomes.fold(0) { acc, outcome -> maxOf(acc, nameOf(outcome).length) }
        val lines = ArrayList<String>(outcomes.size + 4)
        lines.add(header)
        var measured = 0
        var overBudget = 0
        val notMeasured = ArrayList<String>()

        for (outcome in outcomes) {
            when (outcome) {
                is BenchOutcome.Measured -> {
                    measured++
                    val verdict = outcome.verdict()
                    if (verdict == Verdict.OVER_BUDGET) overBudget++
                    val word = when (verdict) {
                        Verdict.WITHIN_BUDGET -> "WITHIN"
                        Verdict.OVER_BUDGET -> "OVER"
                        Verdict.NO_BUDGET -> "no budget set"
                    }
                    lines.add(
                        outcome.name.padEnd(width) +
                            "  median " + oneDecimal(outcome.medianMs) +
                            " ms  p95 " + oneDecimal(outcome.p95Ms) +
                            " ms  worst " + oneDecimal(outcome.worstMs) +
                            " ms  budget " + oneDecimal(outcome.budgetMs) +
                            " ms  " + word +
                            "  checksum " + outcome.checksum
                    )
                }

                is BenchOutcome.Unavailable -> {
                    notMeasured.add(outcome.name + " (" + outcome.reason + ")")
                    lines.add(outcome.name.padEnd(width) + "  NOT MEASURED  " + outcome.reason)
                }
            }
        }

        lines.add("$measured measured, $overBudget over budget, ${notMeasured.size} not measured")
        if (notMeasured.isNotEmpty()) {
            // The whole point of Decision 6 in JB-0.10: a reader who reads only the summary must
            // still know a number is missing, so the names are here rather than only on their lines.
            lines.add("not measured: " + notMeasured.joinToString("; "))
        }
        return lines.joinToString("\n")
    }

    /**
     * The outcome's own name. [BenchOutcome] is a sealed class whose two kinds each carry one, and
     * this is the one place that has to know which is which.
     */
    private fun nameOf(outcome: BenchOutcome): String = when (outcome) {
        is BenchOutcome.Measured -> outcome.name
        is BenchOutcome.Unavailable -> outcome.name
    }

    /**
     * One decimal place, written out rather than delegated to a platform formatter.
     *
     * `String.format` is JVM-only and this file is `commonMain` (blueprint §3.1's iOS door), so the
     * report's one formatting rule lives here instead of in a `java.util` call a later target
     * cannot compile. The value is rounded to tenths of a millisecond as an INTEGER, so no binary
     * fraction ever reaches the text.
     *
     * THE ROUNDING IS HALF-UP, and `kotlin.math.round` could NOT be used: it is `Math.rint`, which
     * rounds half to EVEN, so 12.5 would come back as 12 and a 1.25 ms sample would print `1.2` --
     * a number no reader of a pasted benchmark line expects, and not what `%.1f` means. So it is
     * `floor(a * 10 + 0.5)`, which is the same half-up `%.1f` does and is exact for every value a
     * millisecond figure can take. The guard above 1e15 exists because the multiplication is by 10
     * and a millisecond figure of 1e15 is thirty-one million years, so falling back to the whole
     * number is not a loss.
     */
    private fun oneDecimal(v: Double): String {
        if (v.isNaN()) return "NaN"
        if (v == Double.POSITIVE_INFINITY) return "inf"
        if (v == Double.NEGATIVE_INFINITY) return "-inf"
        val sign = if (v < 0.0) "-" else ""
        val a = if (v < 0.0) -v else v
        if (a >= 1e15) return sign + a.toLong().toString()
        // TENTHS of a millisecond, held as an integer.
        val tenths = floor(a * 10.0 + 0.5).toLong()
        val whole = tenths / 10
        val frac = tenths % 10
        return "$sign$whole.$frac"
    }
}

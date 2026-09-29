package cc.joycreator.joybrush.core.vector

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * JB-5.10 — the vector eraser.
 *
 * Straight test lines are sampled every 2 doc px, so a parameter is x/2 on a horizontal line, and
 * half-widths are 1.0 unless a test says otherwise: a dot eraser of radius r therefore reaches
 * r + 1.0 doc px either side of its centre.
 */
class VectorEraserTest {

    /** Parameter slack: 1e-6 of an index is 2e-6 doc px, far inside the spec's 0.1 doc px. */
    private val tol = 1e-6

    /**
     * The adversarial layout's work ceiling — the number that replaced the deleted `< 2 500 ms`
     * assertion. 59 513 734 pairs are measured today, a whole-line walk is 1 220 104 900 and the
     * pre-fix code spent 6.1e8, so this sits 3.4x above the first and 6.1x below the second; the
     * arithmetic is in [toIntersectionOverFiftyOverlappingLinesComparesBoundedWork]. It is a named
     * constant so the bound is one line to read, and so changing the data has to say out loud that
     * it is moving the goalposts.
     */
    private companion object {
        const val ADVERSARIAL_PAIR_CEILING = 200_000_000L
    }

    private fun line(
        id: String,
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        step: Double = 2.0,
        halfWidth: Double = 1.0,
    ): InkLine {
        val n = max(2, floor(len(x1 - x0, y1 - y0) / step).toInt() + 1)
        val xs = DoubleArray(n) { x0 + (x1 - x0) * it / (n - 1) }
        val ys = DoubleArray(n) { y0 + (y1 - y0) * it / (n - 1) }
        return InkLine(id, xs, ys, DoubleArray(n) { halfWidth })
    }

    /** An eraser that never moved: one sample is a dot. */
    private fun dot(x: Double, y: Double, radius: Double) =
        EraserPath(doubleArrayOf(x), doubleArrayOf(y), radius)

    private fun erase(
        lines: List<InkLine>,
        eraser: EraserPath,
        mode: EraseMode = EraseMode.PARTIAL,
    ) = VectorEraser.erase(lines, eraser, mode)

    // ---- 1. partial middle cut -------------------------------------------------------------------

    @Test
    fun partialCutsOnlyTheStretchUnderTheEraser() {
        val a = line("A", 0.0, 0.0, 100.0, 0.0)
        val pieces = assertNotNull(erase(listOf(a), dot(50.0, 0.0, 5.0)).survivors["A"])

        assertEquals(2, pieces.size, "the cut must split the line in two")
        // The dot reaches 5 + 1 = 6 doc px, so the cut edges sit at x = 44 and x = 56.
        assertEquals(0.0, pieces[0].from, tol)
        assertEquals(22.0, pieces[0].to, tol)
        assertEquals(28.0, pieces[1].from, tol)
        assertEquals(50.0, pieces[1].to, tol)
    }

    // ---- 2. partial miss -------------------------------------------------------------------------

    @Test
    fun partialMissLeavesEveryLineUntouched() {
        val a = line("A", 0.0, 0.0, 100.0, 0.0)
        val result = erase(listOf(a), dot(50.0, 20.0, 5.0))

        assertTrue(result.survivors.isEmpty(), "20 doc px away is not within 6: nothing changes")
    }

    // ---- 3. partial at an end --------------------------------------------------------------------

    @Test
    fun partialAtAnEndLeavesOneSurvivorStartingAtTheErasersEdge() {
        val a = line("A", 0.0, 0.0, 100.0, 0.0)
        val pieces = assertNotNull(erase(listOf(a), dot(0.0, 0.0, 5.0)).survivors["A"])

        assertEquals(1, pieces.size)
        assertEquals(3.0, pieces[0].from, tol, "x = 6, the dot's reach")
        assertEquals(50.0, pieces[0].to, tol)
    }

    // ---- 4. whole stroke -------------------------------------------------------------------------

    @Test
    fun wholeStrokeRemovesTheTouchedLineAndMentionsNoOther() {
        val a = line("A", 0.0, 0.0, 100.0, 0.0)
        val b = line("B", 0.0, 50.0, 100.0, 50.0)
        val result = erase(listOf(a, b), dot(50.0, 0.0, 5.0), EraseMode.WHOLE_STROKE)

        assertEquals(setOf("A"), result.survivors.keys)
        assertEquals(emptyList(), result.survivors["A"], "an empty list means the line is gone")
    }

    // ---- 5. to intersection, overhang -------------------------------------------------------------

    @Test
    fun toIntersectionTrimsAnOverhangBackToTheCrossing() {
        val a = line("A", 0.0, 0.0, 120.0, 0.0)
        val b = line("B", 100.0, -20.0, 100.0, 100.0)
        val result = erase(listOf(a, b), dot(112.0, 0.0, 3.0), EraseMode.TO_INTERSECTION)

        val pieces = assertNotNull(result.survivors["A"])
        assertEquals(1, pieces.size)
        assertEquals(0.0, pieces[0].from, tol)
        assertEquals(50.0, pieces[0].to, tol, "the overhang stops at x = 100, where B crosses")
        assertTrue(!result.survivors.containsKey("B"), "the eraser never touched B")
    }

    // ---- 6. to intersection, between two crossings ------------------------------------------------

    @Test
    fun toIntersectionBetweenTwoCrossingsKeepsBothOuterStretches() {
        val a = line("A", 0.0, 0.0, 200.0, 0.0)
        val b = line("B", 50.0, -20.0, 50.0, 100.0)
        val c = line("C", 150.0, -20.0, 150.0, 100.0)
        val pieces = assertNotNull(
            erase(listOf(a, b, c), dot(100.0, 0.0, 3.0), EraseMode.TO_INTERSECTION).survivors["A"],
        )

        assertEquals(2, pieces.size)
        assertEquals(0.0, pieces[0].from, tol)
        assertEquals(25.0, pieces[0].to, tol, "x = 50")
        assertEquals(75.0, pieces[1].from, tol, "x = 150")
        assertEquals(100.0, pieces[1].to, tol, "x = 200")
    }

    // ---- 7. to intersection, no crossings --------------------------------------------------------

    @Test
    fun toIntersectionWithNoCrossingsRemovesTheWholeLine() {
        val a = line("A", 0.0, 0.0, 100.0, 0.0)
        val result = erase(listOf(a), dot(50.0, 0.0, 3.0), EraseMode.TO_INTERSECTION)

        assertEquals(emptyList(), result.survivors["A"])
    }

    // ---- 8. two stretches in one gesture ----------------------------------------------------------

    @Test
    fun twoTouchedStretchesTrimToTheCrossingsAroundThem() {
        val a = line("A", 0.0, 0.0, 200.0, 0.0)
        val b = line("B", 50.0, -20.0, 50.0, 20.0)
        val c = line("C", 100.0, -20.0, 100.0, 20.0)
        val d = line("D", 150.0, -20.0, 150.0, 20.0)
        // Up from A at x = 25, over at y = 20, back down onto A at x = 125.
        val eraser = EraserPath(
            doubleArrayOf(25.0, 25.0, 125.0, 125.0),
            doubleArrayOf(0.0, 20.0, 20.0, 0.0),
            3.0,
        )
        val pieces = assertNotNull(
            erase(listOf(a, b, c, d), eraser, EraseMode.TO_INTERSECTION).survivors["A"],
        )

        assertEquals(2, pieces.size)
        assertEquals(25.0, pieces[0].from, tol, "x = 50, the first crossing after x = 25")
        assertEquals(50.0, pieces[0].to, tol, "x = 100, the crossing before x = 125")
        assertEquals(75.0, pieces[1].from, tol, "x = 150")
        assertEquals(100.0, pieces[1].to, tol, "x = 200")
    }

    // ---- 9. zig-zag eraser path ------------------------------------------------------------------

    @Test
    fun aZigZagEraserPathCutsOneLineInTwoPlaces() {
        val a = line("A", 0.0, 0.0, 100.0, 0.0)
        val eraser = EraserPath(
            doubleArrayOf(20.0, 20.0, 80.0, 80.0),
            doubleArrayOf(-20.0, 20.0, 20.0, -20.0),
            3.0,
        )
        val pieces = assertNotNull(erase(listOf(a), eraser).survivors["A"])

        assertEquals(3, pieces.size, "two crossings of the eraser path leave three survivors")
        assertEquals(0.0, pieces[0].from, tol)
        assertEquals(8.0, pieces[0].to, tol, "x = 16")
        assertEquals(12.0, pieces[1].from, tol, "x = 24")
        assertEquals(38.0, pieces[1].to, tol, "x = 76")
        assertEquals(42.0, pieces[2].from, tol, "x = 84")
        assertEquals(50.0, pieces[2].to, tol)
    }

    // ---- 10. work, not wall-clock time -------------------------------------------------------------

    /**
     * The work counters themselves, pinned to exact numbers on a geometry small enough to count by
     * hand. Without this the bounds below could sit there counting nothing and still pass — a
     * counter that is never incremented is a test that never fails.
     *
     * A is (0,0)..(20,0) sampled every 2 doc px, so ten 2-doc-px segments, and B is the same line
     * 10 doc px above it. A dot of radius 1 with half-width 1 reaches pad = 2 doc px, and x = 11 is
     * within that of exactly three of A's segments — the ones covering x 8..10, 10..12 and 12..14 —
     * so the touch test compares 3 pairs. B is 10 doc px away, its box is rejected outright, and it
     * costs nothing.
     */
    @Test
    fun theTouchWorkCounterCountsEveryPairItCompares() {
        val a = line("A", 0.0, 0.0, 20.0, 0.0)
        val b = line("B", 0.0, 10.0, 20.0, 10.0)

        VectorEraser.resetWorkCounters()
        erase(listOf(a, b), dot(11.0, 0.0, 1.0))
        assertEquals(3L, VectorEraser.touchPairTests, "the three segments within 2 doc px of x = 11")
        assertEquals(0L, VectorEraser.crossingPairTests, "PARTIAL never looks for crossings")

        // An eraser 50 doc px away compares nothing at all: the bound must be zero here, not "at
        // most 3", because a bound that passes for the wrong reason is not a bound.
        VectorEraser.resetWorkCounters()
        erase(listOf(a, b), dot(-50.0, -50.0, 0.0))
        assertEquals(0L, VectorEraser.touchPairTests, "an eraser 50 doc px away compares nothing")
    }

    /**
     * The crossing counter, again on a geometry countable by hand. A is one 10-doc-px segment and
     * B is one 2-doc-px vertical segment through (5, 0); the dot touches both.
     *
     * For each of the two lines the walk visits its single segment, and that segment's box holds
     * only the other line's single segment, so it compares 1 pair. It does so once per direction —
     * the crossing at (5, 0) lies INSIDE both touched stretches, so neither pass is ever allowed to
     * use it and both walks run to the end of the line asking and finding nothing: 2 pairs per line,
     * 4 in all. The touch test runs first and costs 1 pair per line: 2.
     */
    @Test
    fun theCrossingWorkCounterCountsEveryPairItCompares() {
        val a = InkLine("A", doubleArrayOf(0.0, 10.0), doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 1.0))
        val b = InkLine("B", doubleArrayOf(5.0, 5.0), doubleArrayOf(-1.0, 1.0), doubleArrayOf(1.0, 1.0))

        VectorEraser.resetWorkCounters()
        erase(listOf(a, b), dot(5.0, 0.0, 1.0), EraseMode.TO_INTERSECTION)
        assertEquals(4L, VectorEraser.crossingPairTests, "1 pair per line, asked once per direction")
        assertEquals(2L, VectorEraser.touchPairTests, "1 line segment against the dot's 1 segment, twice")
    }

    /**
     * Decision 5's promise for the two modes that touch every line, kept as a DETERMINISTIC work
     * bound instead of a wall clock (JB-5.10 R28: the old `< 500 ms` here was flaky by construction).
     *
     * Layout: 50 lines of 500 points, every one of them walking inside ONE 400 doc px square, and a
     * 199-segment eraser path straight through it. The eraser really does cross all 50 lines, which
     * is the case decision 5 is about.
     *
     * THE ARITHMETIC BEHIND N. The touch test's unit of work is one comparison of a line segment
     * against an eraser segment, and nothing may compare the same pair twice. The whole of this
     * erase is therefore at most the complete double loop and nothing else:
     *
     *     50 lines x 499 line segments x 199 eraser segments = 4 970 050 pairs
     *
     * which is the ceiling, and it is the number a regression in the prefilter costs. What the
     * prefilter buys on this input is the gap between that and the measurement: 14 567 pairs, so
     * 341x. N is set at
     *
     *     50 000 = 3.4x the measured 14 567, and 99x below the 4 970 050 full double loop
     *
     * The measurement is not a number to tune until it passes. The data is a seeded
     * `Random(20260928)` walk, and every comparison the counter tallies is an exact min/max on
     * doubles, so 14 567 is the same integer on every machine and every run — which is the whole
     * reason R28 replaced the clock. What this bound does NOT catch is stated here rather than
     * left for someone to discover: a change that makes each PAIR more expensive, rather than more
     * numerous, is invisible to it. The deleted millisecond assertion would have caught that, on a
     * quiet machine.
     */
    @Test
    fun fiftyLinesOfFiveHundredPointsCompareBoundedWork() {
        val n = 50_000L
        val rng = Random(20260928)
        val lines = (0 until 50).map { randomWalkLine("L$it", rng, 500, 200.0, 200.0, cell = 400.0) }
        val eraser = EraserPath(DoubleArray(200) { it * 4.0 }, DoubleArray(200) { 200.0 }, 6.0)

        VectorEraser.resetWorkCounters()
        val partial = VectorEraser.erase(lines, eraser, EraseMode.PARTIAL)
        val partialPairs = VectorEraser.touchPairTests

        VectorEraser.resetWorkCounters()
        VectorEraser.erase(lines, eraser, EraseMode.WHOLE_STROKE)
        val wholePairs = VectorEraser.touchPairTests

        VectorEraser.resetWorkCounters()
        val again = VectorEraser.erase(lines, eraser, EraseMode.PARTIAL)
        val repeatPairs = VectorEraser.touchPairTests

        assertTrue(partial.survivors.isNotEmpty(), "the eraser must actually cross some lines")
        assertEquals(partial.survivors.keys, again.survivors.keys, "the same input must give the same answer")
        assertEquals(partialPairs, repeatPairs, "the same input must cost the same work")
        assertTrue(partialPairs > 0L, "a bound of 0 would pass this test without testing anything")
        assertTrue(
            partialPairs <= n,
            "50 x 500 PARTIAL compared $partialPairs segment pairs, over the $n bound; the full " +
                "double loop of 50 x 499 x 199 = 4970050 is the ceiling this is well under",
        )
        assertTrue(
            wholePairs <= n,
            "50 x 500 WHOLE_STROKE compared $wholePairs segment pairs, over the $n bound; removing " +
                "a whole stroke must not cost more work than cutting a piece of it",
        )
    }

    /**
     * Fifty strokes in fifty separate cells, and the guarantee is ZERO: not "few", not "under a
     * bound" — none of them is compared with any other, anywhere.
     *
     * This is the layout the Lead's ruling R28 credits the eraser for: one stroke per cell of a
     * 10 x 5 grid of 400 doc px cells, each walk 300 doc px wide inside its cell, so consecutive
     * cells have 100 doc px of clear air between their boxes. A crossing needs two boxes to meet, so
     * no pair of strokes here can cross; what is being tested is not the geometry but the PREFILTER
     * — the left-to-right sweep's `break` plus the line-box test, which together reject all 49 other
     * strokes for every one of this line's 499 segments in both walk directions, 50 x 2 x 499 x 49
     * times over. Every one of those rejections is a segment pair NOT compared.
     *
     * So the counter must read exactly 0, and that is a much stronger claim than a ceiling. Measured
     * by mutation: deleting the line-box prefilter — and nothing else — takes this from 0 to
     * 637 365 714 pairs and this test says so in one number. (That is short of the 1 220 104 900 a
     * fully unpruned search would cost only because the sweep's `break` survives the mutation and
     * still stops at the columns to the left; the box test is the last line of defence, and it is
     * the one that was removed.) The previous version of this test held the same layout to a wall
     * clock, which is why it was rewritten rather than kept.
     */
    @Test
    fun toIntersectionOnFiftySeparateCellsComparesNoPairAtAll() {
        val rng = Random(20260930)
        val lines = (0 until 50).map { i ->
            val col = i % 10
            val row = i / 10
            randomWalkLine("L$i", rng, 500, col * 400.0 + 50.0, row * 400.0 + 50.0, cell = 300.0)
        }
        // A boustrophedon that dips through the middle of every row of cells.
        val eraser = EraserPath(
            DoubleArray(200) { (it % 40) * 100.0 },
            DoubleArray(200) { 200.0 + (it / 40) * 400.0 },
            4.0,
        )

        VectorEraser.resetWorkCounters()
        val result = VectorEraser.erase(lines, eraser, EraseMode.TO_INTERSECTION)
        val pairs = VectorEraser.crossingPairTests

        assertTrue(result.survivors.isNotEmpty(), "the eraser must actually cross some strokes")
        assertEquals(
            0L,
            pairs,
            "strokes in separate cells have boxes 100 doc px apart, so the prefilter must reject " +
                "every pair; it examined $pairs. Deleting the line-box test takes this to " +
                "637365714",
        )
    }

    /**
     * Fifty strokes spread over the document — what a real layer looks like, and where decision 5
     * means what it says. Two strokes per cell of a 5 x 5 grid of 400 doc px cells, each walking
     * inside its own cell, and a 225-point eraser path sweeping the middle of every row of cells.
     * Every stroke is touched and every stroke's cell-mate crosses it constantly, so the crossing
     * search really runs: this is the layout where the bounding boxes are supposed to be earning
     * their keep.
     *
     * (The layout this replaced had one stroke per cell with 100 doc px of clear air between cells,
     * so NO pair of strokes' boxes could ever overlap. The work counter read exactly 0 there, which
     * would have made any bound over it pass for the wrong reason — a bound of zero is not a test.)
     *
     * THE ARITHMETIC BEHIND N. The counter is charged one pair per segment-pair the search
     * examines, and two prefilters stand between a segment and that charge: a line whose box misses
     * the segment's box, and the left-to-right sweep's `break` on the first line that starts past
     * the segment's right edge. Cells are 400 doc px apart and the walks are 360 wide inside them,
     * so a walk's box ([col*400+20, col*400+380]) never reaches its neighbour's, which starts at
     * (col+1)*400+20 — 40 doc px clear. The number of lines that can be admitted for a given
     * segment is therefore 1, the cell-mate, and the count is bounded by the walk rather than by
     * the boxes:
     *
     *   - today: 3 822 340 pairs, i.e. the walk goes 76.6 of the 499 segments
     *     (3 822 340 / (50 x 2 x 1 x 499)) before the first crossing turns up;
     *   - a walk that ran to the end of every line in both directions, still admitting only the
     *     cell-mate: 50 x 2 x 499 x 1 x 499 = 24 900 100;
     *   - deleting the line-box prefilter, with the sweep's `break` left in place, admits every
     *     line in the columns at or left of this one and measures 119 435 151 (mutation-measured,
     *     not derived); losing the `break` as well admits all 49 and costs
     *     50 x 2 x 499 x 49 x 499 = 1 220 104 900.
     *
     * N is 10 000 000: 2.6x the measurement, 2.5x below the whole-line-walk regression, 12x below
     * the line-box regression and 122x below the last. The count is reproducible rather than merely
     * repeatable — a seeded `Random(20260929)` and box tests that are exact min/max comparisons —
     * so the 2.6x is slack against a future change to the data, not against run-to-run noise.
     */
    @Test
    fun toIntersectionOnFiftySpreadStrokesComparesBoundedWork() {
        val n = 10_000_000L
        val rng = Random(20260929)
        val lines = (0 until 50).map { i ->
            val cell = i / 2 // two strokes per cell, so they cross each other
            val col = cell % 5
            val row = cell / 5
            randomWalkLine("L$i", rng, 500, col * 400.0 + 20.0, row * 400.0 + 20.0, cell = 360.0)
        }
        // A boustrophedon straight through the middle of every row of cells.
        val eraser = EraserPath(
            DoubleArray(225) { (it % 45) * 40.0 + 200.0 },
            DoubleArray(225) { 200.0 + (it / 45) * 400.0 },
            4.0,
        )

        VectorEraser.resetWorkCounters()
        val result = VectorEraser.erase(lines, eraser, EraseMode.TO_INTERSECTION)
        val pairs = VectorEraser.crossingPairTests

        VectorEraser.resetWorkCounters()
        val again = VectorEraser.erase(lines, eraser, EraseMode.TO_INTERSECTION)
        val repeatPairs = VectorEraser.crossingPairTests

        assertTrue(result.survivors.isNotEmpty(), "the eraser must actually cross some lines")
        assertEquals(result.survivors, again.survivors, "the same input must give the same answer")
        assertEquals(pairs, repeatPairs, "the same input must cost the same work")
        assertTrue(pairs > 0L, "a bound of 0 would pass this test without testing anything")
        assertTrue(
            pairs <= n,
            "50 x 500 TO_INTERSECTION on a spread layer compared $pairs segment pairs, over the " +
                "$n bound; scanning whole lines would be 24900100 and no pruning at all 1220104900",
        )
    }

    /**
     * The layout the left-to-right sweep cannot prune, and the one Q6 admitted was never covered
     * (review Finding 1): fifty 500-point lines all scribbling inside ONE 200x200 doc px region, so
     * every line's bounding box is the whole region — no box lies left of another, no box lies
     * right of another, and the sweep rejects nothing at all. A dot eraser sits in the middle of it.
     *
     * This is adversarial by construction; no real layer looks like this, and decision 5's promise
     * is not made about it (see the spec, decision 5). The Lead's ruling R28: no spatial index, and
     * no milliseconds here either — the guarantee is a work count.
     *
     * What it cost before `crossingTrims`: every pair of segments of every pair of lines, which is
     * 50 x 49 x 499 x 499 = 6.1e8 pair tests, and then — the part that actually dominated — a list
     * of every crossing of each line, which for mutually-overlapping scribbles runs to millions of
     * entries per line, sorted, then scanned linearly once per touched stretch. It is now asked
     * only for the NEAREST crossing beside each touched stretch, which is a segment or two of
     * walking outward.
     *
     * THE ARITHMETIC BEHIND N. Neither prefilter prunes anything here, so the count is set by the
     * walk alone, and the walk's length is a property of the DATA (how soon the first crossing
     * turns up in a scribble this dense), not of the layout. So N is bracketed by two numbers
     * rather than asserted against a hope:
     *
     *   - today: 59 513 734 pairs, i.e. the walk goes 24.3 of the 499 segments
     *     (59 513 734 / (50 x 2 x 49 x 499)) before the first crossing turns up;
     *   - a walk that scanned the whole line in both directions would cost
     *     50 x 2 x 499 x 49 x 499 = 1 220 104 900, which is twice what the pre-fix code spent.
     *
     * N is 200 000 000: 3.4x the measurement, 6.1x below the whole-line regression and 3x below
     * the pre-fix code's 6.1e8. So a regression that stops the walk from stopping — which is exactly
     * what the deleted `< 2 500 ms` assertion existed to notice — is caught on every run, on every
     * machine, instead of on the days the build is quiet.
     *
     * What this layout cannot catch, stated rather than left to be found out: the line-box
     * prefilter is inert here, because every line's box is the whole 200x200 region and so no box
     * ever rejects another. Deleting it changes nothing on this test — which is exactly why
     * `toIntersectionOnFiftySeparateCellsComparesNoPairAtAll` exists and why the prefilter is
     * guarded over there rather than here.
     *
     * The data comes from a fixed 48-bit LCG rather than kotlin.random, so the geometry is the same
     * on every platform and every run and the count is comparable run to run. That matters twice
     * over now: a seeded input is what makes a work count a reproducible number at all.
     */
    @Test
    fun toIntersectionOverFiftyOverlappingLinesComparesBoundedWork() {
        val lines = (0 until 50).map { scribbleThroughTheMiddle("W$it", 500, it) }
        val eraser = dot(100.0, 100.0, 3.0)

        VectorEraser.resetWorkCounters()
        val result = VectorEraser.erase(lines, eraser, EraseMode.TO_INTERSECTION)
        val pairs = VectorEraser.crossingPairTests

        VectorEraser.resetWorkCounters()
        val again = VectorEraser.erase(lines, eraser, EraseMode.TO_INTERSECTION)
        val repeatPairs = VectorEraser.crossingPairTests

        // Every line is forced through the eraser, so every one of the 50 is cut, and each is cut
        // only as far as its nearest crossing: a line with no crossings would be gone entirely.
        assertEquals(50, result.survivors.size, "all 50 lines pass through the eraser, so all 50 are cut")
        assertTrue(result.survivors.values.any { it.isNotEmpty() }, "crossings everywhere leave survivors")
        assertEquals(result.survivors, again.survivors, "the same input must give the same answer")
        assertEquals(pairs, repeatPairs, "the same input must cost the same work")
        assertTrue(pairs > 0L, "a bound of 0 would pass this test without testing anything")
        assertTrue(
            pairs <= ADVERSARIAL_PAIR_CEILING,
            "50 x 500 fully overlapping TO_INTERSECTION compared $pairs segment pairs, over the " +
                "$ADVERSARIAL_PAIR_CEILING bound; the walk is no longer stopping at the first " +
                "crossing, and scanning whole lines would be 1220104900",
        )
    }

    /**
     * One [points]-point scribble inside a 200x200 doc px square, forced through (100, 100) at its
     * middle point so a dot eraser there certainly touches it. Positions come from a fixed LCG, so
     * this line is identical on every platform; the seed is offset per line so no two coincide.
     */
    private fun scribbleThroughTheMiddle(id: String, points: Int, seedIndex: Int): InkLine {
        var state = 0x2545F4914F6CDD1DL xor (seedIndex * 0x100000001B3L)
        val xs = DoubleArray(points)
        val ys = DoubleArray(points)
        for (i in 0 until points) {
            state = state * 6364136223846793005L + 1442695040888963407L
            xs[i] = ((state ushr 11) % 200_000L) / 1000.0
            state = state * 6364136223846793005L + 1442695040888963407L
            ys[i] = ((state ushr 11) % 200_000L) / 1000.0
        }
        xs[points / 2] = 100.0
        ys[points / 2] = 100.0
        return InkLine(id, xs, ys, DoubleArray(points) { 1.0 })
    }

    private fun randomWalkLine(
        id: String,
        rng: Random,
        points: Int,
        startX: Double = rng.nextDouble(0.0, 2000.0),
        startY: Double = rng.nextDouble(0.0, 2000.0),
        cell: Double = 0.0,
        step: Double = 60.0,
    ): InkLine {
        val xs = DoubleArray(points)
        val ys = DoubleArray(points)
        val hw = DoubleArray(points)
        var x = startX
        var y = startY
        for (i in 0 until points) {
            xs[i] = x
            ys[i] = y
            hw[i] = 0.5 + rng.nextDouble() * 2.5
            x = bounce(x + rng.nextDouble(-step, step), startX, cell)
            y = bounce(y + rng.nextDouble(-step, step), startY, cell)
        }
        return InkLine(id, xs, ys, hw)
    }

    /** Keeps a random walk inside [origin, origin + cell] by reflecting it; cell 0 means free. */
    private fun bounce(v: Double, origin: Double, cell: Double): Double {
        if (cell <= 0.0) return v
        val period = 2.0 * cell
        var u = (v - origin) % period
        if (u < 0.0) u += period
        return if (u <= cell) origin + u else origin + (period - u)
    }

    // ---- degenerate input -------------------------------------------------------------------------

    @Test
    fun noLinesAndNoEraserBothEraseToNothing() {
        val a = line("A", 0.0, 0.0, 100.0, 0.0)
        assertTrue(erase(emptyList(), dot(50.0, 0.0, 5.0)).survivors.isEmpty())
        assertTrue(erase(listOf(a), EraserPath(DoubleArray(0), DoubleArray(0), 5.0)).survivors.isEmpty())
    }

    @Test
    fun aSinglePointLineIsAllOrNothing() {
        val dab = InkLine("dab", doubleArrayOf(5.0), doubleArrayOf(5.0), doubleArrayOf(2.0))
        assertEquals(emptyList(), erase(listOf(dab), dot(5.0, 5.0, 1.0)).survivors["dab"])
        assertTrue(erase(listOf(dab), dot(5.0, 50.0, 1.0)).survivors.isEmpty())
    }

    @Test
    fun aZeroLengthSegmentStillSplitsTheLine() {
        // A line that doubles back on itself: (0,0), (10,0), (10,0), (20,0) — points 1 and 2 are
        // the same place under two different parameters. A dot eraser of radius 5 on top of them,
        // with half-width 1, reaches 5 + 1 = 6 doc px, so it cuts x = 4 to x = 16.
        //
        // Both duplicate points are inside that cut, so the survivor restarts past parameter 2, not
        // at parameter 1: the erased stretch is one run from 0.4 to 2.6 even though the piece in
        // between covers no distance at all.
        val a = InkLine(
            "A",
            doubleArrayOf(0.0, 10.0, 10.0, 20.0),
            doubleArrayOf(0.0, 0.0, 0.0, 0.0),
            doubleArrayOf(1.0, 1.0, 1.0, 1.0),
        )
        val reach = 5.0 + 1.0 // radius + half-width
        val cutFromX = 10.0 - reach // x = 4, on the point 0 -> 1 segment, which runs x 0 to 10
        val cutToX = 10.0 + reach // x = 16, on the point 2 -> 3 segment, which runs x 10 to 20
        // Both real segments are 10 doc px long, so one parameter step is 10 doc px.
        val cutFrom = cutFromX / 10.0
        val cutTo = 2.0 + (cutToX - 10.0) / 10.0

        val pieces = assertNotNull(erase(listOf(a), dot(10.0, 0.0, 5.0)).survivors["A"])

        assertEquals(2, pieces.size)
        assertEquals(0.0, pieces[0].from, tol)
        assertEquals(cutFrom, pieces[0].to, tol)
        assertEquals(cutTo, pieces[1].from, tol)
        assertEquals(3.0, pieces[1].to, tol)
    }

    @Test
    fun anEraserWithTwoIdenticalSamplesIsStillADot() {
        val a = line("A", 0.0, 0.0, 100.0, 0.0)
        val eraser = EraserPath(doubleArrayOf(50.0, 50.0), doubleArrayOf(0.0, 0.0), 5.0)
        val pieces = assertNotNull(erase(listOf(a), eraser).survivors["A"])

        assertEquals(2, pieces.size)
        assertEquals(22.0, pieces[0].to, tol)
        assertEquals(28.0, pieces[1].from, tol)
    }

    @Test
    fun aStrokeThatLoopsOverItselfIsNotItsOwnCrossing() {
        // A figure eight: the centreline crosses itself at (20, 20), and that must not count.
        val xs = doubleArrayOf(-40.0, -20.0, 0.0, 20.0, 40.0, 10.0, 20.0, 30.0, 40.0)
        val ys = doubleArrayOf(-40.0, -20.0, 0.0, 20.0, 40.0, 30.0, 20.0, 10.0, 0.0)
        val eight = InkLine("A", xs, ys, DoubleArray(9) { 1.0 })
        val result = erase(listOf(eight), dot(10.0, 10.0, 3.0), EraseMode.TO_INTERSECTION)

        assertEquals(emptyList(), result.survivors["A"], "with no other line, nothing is a crossing")
    }

    @Test
    fun aCollinearNeighbourIsACrossingAtBothEndsOfTheOverlap() {
        val a = line("A", 0.0, 0.0, 40.0, 0.0)
        val b = line("B", 20.0, 0.0, 60.0, 0.0)
        val pieces = assertNotNull(
            erase(listOf(a, b), dot(2.0, 0.0, 3.0), EraseMode.TO_INTERSECTION).survivors["A"],
        )

        assertEquals(1, pieces.size)
        assertEquals(10.0, pieces[0].from, tol, "x = 20, where B starts")
        assertEquals(20.0, pieces[0].to, tol, "x = 40, where B's overlap with A ends")
    }

    @Test
    fun exactlyEqualDistanceAtTheBoundaryCounts() {
        val a = line("A", 0.0, 0.0, 20.0, 0.0)
        val touching = assertNotNull(erase(listOf(a), dot(0.0, 0.0, 5.0)).survivors["A"])
        assertEquals(1, touching.size)
        assertEquals(3.0, touching[0].from, tol, "x = 6 is exactly radius + halfWidth")

        // One hundredth of a doc px further out, the same line is not touched there.
        val almost = assertNotNull(erase(listOf(a), dot(0.0, 0.0, 4.9)).survivors["A"])
        assertEquals(2.95, almost[0].from, tol, "x = 5.9")
    }

    @Test
    fun theHalfWidthIsWhatTheEraserIsMeasuredAgainst() {
        val thin = line("thin", 0.0, 0.0, 100.0, 0.0, halfWidth = 1.0)
        val fat = line("fat", 0.0, 0.0, 100.0, 0.0, halfWidth = 10.0)
        val eraser = dot(50.0, 8.0, 1.0)

        assertTrue(erase(listOf(thin), eraser).survivors.isEmpty(), "1 + 1 = 2 < 8: too thin to touch")
        val pieces = assertNotNull(erase(listOf(fat), eraser).survivors["fat"])
        // sqrt((x - 50)^2 + 8^2) <= 1 + 10
        val cut = sqrt(11.0 * 11.0 - 8.0 * 8.0)
        assertEquals(2, pieces.size)
        assertEquals(0.0, pieces[0].from, tol)
        assertEquals((50.0 - cut) / 2.0, pieces[0].to, tol)
        assertEquals((50.0 + cut) / 2.0, pieces[1].from, tol)
        assertEquals(50.0, pieces[1].to, tol)
    }

    @Test
    fun anEraserThatNeverLeavesItsOwnRadiusStillErasesTheWholeLine() {
        val a = line("A", 0.0, 0.0, 4.0, 0.0) // 2 doc px long, radius 20 all round it
        val pieces = assertNotNull(erase(listOf(a), dot(2.0, 0.0, 20.0)).survivors["A"])

        assertEquals(emptyList(), pieces)
    }

    @Test
    fun survivorsAreOrderedDisjointAndInsideTheLine() {
        val a = line("A", 0.0, 0.0, 100.0, 0.0)
        val eraser = EraserPath(
            doubleArrayOf(20.0, 20.0, 60.0, 60.0),
            doubleArrayOf(-20.0, 20.0, 20.0, -20.0),
            3.0,
        )
        val pieces = assertNotNull(erase(listOf(a), eraser).survivors["A"])

        var previousTo = -1.0
        for (p in pieces) {
            assertTrue(p.from > previousTo, "survivors must not overlap and must be in order")
            assertTrue(p.to <= a.lastParam, "a piece can never name a point past the line's end")
            assertEquals("A", p.sourceId)
            previousTo = p.to
        }
    }

    @Test
    fun specksShorterThanHalfAPixelAreDropped() {
        // Two touches a fifth of a doc px apart leave a 0.2 doc px survivor: not worth keeping.
        val a = line("A", 0.0, 0.0, 100.0, 0.0, halfWidth = 0.5)
        val eraser = EraserPath(
            doubleArrayOf(1.0, 1.0, 3.0, 3.0),
            doubleArrayOf(0.0, 10.0, 10.0, 0.0),
            0.4,
        )
        val pieces = assertNotNull(erase(listOf(a), eraser).survivors["A"])

        assertEquals(1, pieces.size, "the 0.1 and 0.2 doc px stubs go")
        assertEquals(1.95, pieces[0].from, tol, "x = 3.9, the far edge of the second touch")
        assertEquals(50.0, pieces[0].to, tol)
    }

    // ---- the speck rule in the other two modes (JB-5.10 R28, Q1) ----------------------------------

    /**
     * A trim to a crossing can leave a sliver too, and it must go: a 0.3 doc px stub of ink is
     * noise on the canvas and a nightmare for whoever rebuilds strokes from these pieces.
     *
     * The geometry is the natural one. A runs (0, 0) to (100, 0), sampled every 2 doc px, so
     * lastParam = 50. A single other line crosses A a third of a doc px in from one end — 0.3 doc px
     * at x = 0.3, which is parameter 0.15 — and the eraser (radius 3 + half-width 1 = 4 of reach)
     * sits in the middle of A. Trimming to the crossing removes everything from parameter 0.15 to
     * the end of A, which leaves exactly one survivor: the 0.3 doc px of A before the crossing.
     * Both modes see the same geometry, so the test can say what the difference is.
     */
    @Test
    fun toIntersectionDropsASpeckLeftBeforeACrossing() {
        val a = line("A", 0.0, 0.0, 100.0, 0.0)
        val b = line("B", 0.3, -20.0, 0.3, 100.0)

        val partial = assertNotNull(erase(listOf(a, b), dot(10.0, 0.0, 3.0)).survivors["A"])
        assertEquals(2, partial.size, "a partial cut of x 6 to x 14 leaves two long pieces")
        assertEquals(0.15, 0.3 / 2.0, tol, "x = 0.3 is parameter 0.15, the 0.3 doc px sliver")

        val pieces = assertNotNull(
            erase(listOf(a, b), dot(10.0, 0.0, 3.0), EraseMode.TO_INTERSECTION).survivors["A"],
        )
        assertEquals(
            emptyList(),
            pieces,
            "trimming to the crossing at x = 0.3 leaves only a 0.3 doc px speck, and specks go " +
                "in every mode, not just PARTIAL",
        )
    }

    /** The same rule at the other end of the line: a crossing 0.3 doc px short of the end. */
    @Test
    fun toIntersectionDropsASpeckLeftAfterACrossing() {
        val a = line("A", 0.0, 0.0, 100.0, 0.0)
        val b = line("B", 99.7, -20.0, 99.7, 100.0)
        val pieces = assertNotNull(
            erase(listOf(a, b), dot(90.0, 0.0, 3.0), EraseMode.TO_INTERSECTION).survivors["A"],
        )

        assertEquals(
            emptyList(),
            pieces,
            "trimming to the crossing at x = 99.7 leaves only the 0.3 doc px after it, and " +
                "WHOLE_STROKE's empty answer must not be the only way an answer comes back empty",
        )
    }

    /**
     * The bound is a threshold, not a cliff: a survivor of 0.6 doc px survives, so the rule is
     * doing the measuring rather than emptying every TO_INTERSECTION answer.
     */
    @Test
    fun toIntersectionKeepsASurvivorJustOverHalfAPixel() {
        val a = line("A", 0.0, 0.0, 100.0, 0.0)
        val b = line("B", 99.4, -20.0, 99.4, 100.0)
        val pieces = assertNotNull(
            erase(listOf(a, b), dot(90.0, 0.0, 3.0), EraseMode.TO_INTERSECTION).survivors["A"],
        )

        assertEquals(1, pieces.size)
        assertEquals(49.7, pieces[0].from, tol, "x = 99.4")
        assertEquals(50.0, pieces[0].to, tol, "0.6 doc px is over the 0.5 doc px floor, so it stays")
    }
}

package cc.joycreator.joybrush.core.vector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * JB-5.02 — picking the line a tap meant.
 *
 * The two workhorse lines are `lower` at y = 10 and `upper` at y = 7, both half-width 1, 3 doc px
 * apart, listed in drawing order so that `upper` is the more recent of the two. At zoom 1 the
 * candidate slop is 12 doc px, the tie window 1 and the double-tap reach 6, so a tap at y = 7 sees
 * upper at score -1 and lower at score 2 (not tied, 3 apart) and picks upper, while a tap at
 * y = 8.5 sees both at score 0.5 — an exact tie, so the more recent one, upper, wins.
 */
class StrokePickerTest {

    private val lower = hLine("lower", 10.0, 1.0)
    private val upper = hLine("upper", 7.0, 1.0)
    private val pair = listOf(lower, upper)

    // ---- 1. the two parallel lines of the spec ------------------------------------------------------

    @Test
    fun aTapOnTheUpperOfTwoParallelLinesPicksTheUpper() {
        val picker = StrokePicker()
        // On upper: distance 0, half-width 1, score -1. On lower: distance 3, score 2. The 3 px
        // gap is wider than the 1 px tie window, so the ranking is upper then lower.
        assertEquals("upper", picker.pick(pair, 50.0, 7.0, 0.0, 1.0))
    }

    @Test
    fun aTapExactlyBetweenTwoParallelLinesPicksTheMoreRecentOne() {
        // 1.5 from each centreline, both half-width 1, so both score exactly 0.5.
        assertEquals("upper", StrokePicker().pick(pair, 50.0, 8.5, 0.0, 1.0))
        // Same geometry, drawn in the other order: now `lower` is on top and is what a tap means.
        assertEquals("lower", StrokePicker().pick(pair.reversed(), 50.0, 8.5, 0.0, 1.0))
    }

    @Test
    fun aSecondQuickTapCyclesToTheOtherAndTheThirdGoesBack() {
        // 10 ms and 20 ms after the first tap: inside the 400 ms window, at the same point, so each
        // returns the next candidate of the same ranking and wraps after the last one.
        val got = tapSeries(StrokePicker(), pair, 50.0, 8.5, 4)
        assertEquals(listOf("upper", "lower", "upper", "lower"), got)
    }

    @Test
    fun aTapAfterTheCycleWindowStartsAtTheFirstCandidateAgain() {
        val picker = StrokePicker()
        assertEquals("upper", picker.pick(pair, 50.0, 8.5, 0.0, 1.0))
        // 500 ms on, so this is a new tap, not the second half of a double-tap.
        assertEquals("upper", picker.pick(pair, 50.0, 8.5, 500.0, 1.0))
    }

    @Test
    fun aTapFarFromEveryLinePicksNothing() {
        val picker = StrokePicker()
        assertNull(picker.pick(pair, 50.0, 1000.0, 0.0, 1.0))
        assertNull(picker.pick(emptyList(), 50.0, 50.0, 0.0, 1.0))
    }

    @Test
    fun theTwelvePixelToleranceShrinksToOneAndAHalfDocPixelsWhenZoomedIn() {
        // `far` is 11 doc px below the tap, outside its own 0.2 half-width by 10.8 doc px: inside
        // the 12 doc px slop at zoom 1 (so it is a candidate and can be cycled to), and outside the
        // 1.5 doc px slop at zoom 8 (so it is not a candidate at all).
        val lines = listOf(hLine("near", 0.0, 1.0), hLine("far", 11.0, 0.2))

        val zoomedOut = tapSeries(StrokePicker(), lines, 50.0, 0.0, 2, zoom = 1.0)
        assertEquals(listOf("near", "far"), zoomedOut)

        val zoomedIn = tapSeries(StrokePicker(), lines, 50.0, 0.0, 2, zoom = 8.0)
        // The second tap has nowhere to cycle to and must stay put rather than return null.
        assertEquals(listOf("near", "near"), zoomedIn)
    }

    // ---- the ribbon, not the centreline ------------------------------------------------------------

    @Test
    fun theHalfWidthDecidesWhichLineAFatLineUnderTheTapMeant() {
        // `thin` is drawn first at y = 1, half-width 0.2. The tap is ON it, so it scores -0.2.
        // `fat` is drawn second at y = 4 with half-width 3: the tap is 3 away, which is exactly the
        // edge of its ink, so it scores 0.0. 0.2 is inside the 1 px tie window, so recency decides
        // and the fat line on top wins — which is what the user can see under their finger.
        val fat = hLine("fat", 4.0, 3.0)
        val thin = hLine("thin", 1.0, 0.2)
        assertEquals("fat", StrokePicker().pick(listOf(thin, fat), 50.0, 1.0, 0.0, 1.0))
        // Drawn the other way round the same tap means `thin`, even though its centreline is nearer.
        assertEquals("thin", StrokePicker().pick(listOf(fat, thin), 50.0, 1.0, 0.0, 1.0))
    }

    @Test
    fun theHalfWidthIsInterpolatedAtTheNearestPointNotAtAVertex() {
        // A stroke that tapers from half-width 1 at x = 0 to 5 at x = 100. At x = 90 the nearest
        // point is at parameter 0.9, so the half-width there is 1 + 0.9 * 4 = 4.6: a tap 3 above it
        // is inside the ink. At zoom 8 the slop is 1.5, and 3 - 4.6 = -1.6 is inside it too.
        val taper = InkLine(
            "taper",
            doubleArrayOf(0.0, 100.0),
            doubleArrayOf(0.0, 0.0),
            doubleArrayOf(1.0, 5.0),
        )
        assertEquals("taper", StrokePicker().pick(listOf(taper), 90.0, 3.0, 0.0, 8.0))
        // The same tap against a stroke that is 1 wide all the way is 2 doc px outside the ink,
        // which is past the 1.5 doc px slop: nothing there.
        val uniform = InkLine(
            "uniform",
            doubleArrayOf(0.0, 100.0),
            doubleArrayOf(0.0, 0.0),
            doubleArrayOf(1.0, 1.0),
        )
        assertNull(StrokePicker().pick(listOf(uniform), 90.0, 3.0, 0.0, 8.0))
    }

    // ---- ties --------------------------------------------------------------------------------------

    @Test
    fun anExactlyEqualTieAlwaysResolvesTheSameWay() {
        // The same tap on a fresh picker, five times over: a tie may not depend on anything that
        // changes between calls, and it does not.
        for (i in 0 until 5) {
            assertEquals("upper", StrokePicker().pick(pair, 50.0, 8.5, 0.0, 1.0), "attempt $i")
        }
        // Three lines, one tap at y = 2, all half-width 1: `a` is 1 away and scores 0, `b` is 1.5
        // away and scores 0.5, `c` is 2.5 away and scores 1.5. a and b are half a pixel apart, so
        // they tie and the more recent b leads; c is more than a pixel from both, so it follows.
        val a = hLine("a", 3.0, 1.0)
        val b = hLine("b", 3.5, 1.0)
        val c = hLine("c", 4.5, 1.0)
        val lines = listOf(a, b, c)
        assertEquals(listOf("b", "a", "c"), tapSeries(StrokePicker(), lines, 50.0, 2.0, 3))
    }

    // ---- crossings, ends, dots ---------------------------------------------------------------------

    @Test
    fun aTapExactlyOnACrossingPicksTheMoreRecentLineAndCycles() {
        // A plus sign: `h` along y = 0 and `v` up x = 50, both half-width 1. The tap at (50, 0) is
        // 0 from both centrelines, so both score -1 and the more recent `v` wins — and the crossing
        // needs no special case, because each line's nearest point is simply the crossing.
        val h = hLine("h", 0.0, 1.0)
        val v = vLine("v", 50.0, 1.0, 0.0, 100.0)
        val lines = listOf(h, v)
        assertEquals(listOf("v", "h", "v"), tapSeries(StrokePicker(), lines, 50.0, 0.0, 3))
    }

    @Test
    fun aTapOnAnEndpointUsesTheEndOfTheStrokeNotAnInfiniteLine() {
        val line = hLine("a", 0.0, 1.0, 0.0, 100.0)
        assertEquals("a", StrokePicker().pick(listOf(line), 100.0, 0.0, 0.0, 1.0))
        // 3 past the end: 3 doc px from the nearest point of the stroke (its last point), inside
        // the 1 + 12 = 13 doc px reach at zoom 1 and outside the 1 + 1.5 = 2.5 doc px reach at 8.
        assertEquals("a", StrokePicker().pick(listOf(line), 103.0, 0.0, 0.0, 1.0))
        assertNull(StrokePicker().pick(listOf(line), 103.0, 0.0, 0.0, 8.0))
        // 100 past the end would be dead centre if the stroke were an infinite line: it is not.
        assertNull(StrokePicker().pick(listOf(line), 200.0, 0.0, 0.0, 1.0))
    }

    @Test
    fun aCrossingIsRankedByHowFarInsideTheInkTheTapLands() {
        val fat = hLine("fat", 0.0, 2.0)
        val thin = vLine("thin", 50.0, 0.2, 0.0, 100.0)
        // Both centrelines pass through (50, 0), so both are 0 away from the tap; the fat line is 2
        // inside its own ink there and the thin one 0.2, and 1.8 is wider than the 1 px tie window,
        // so the fat line wins even though `thin` is drawn on top of it. That is Decision 2 read
        // literally, pinned here so it is a decision and not a surprise.
        assertEquals("fat", StrokePicker().pick(listOf(fat, thin), 50.0, 0.0, 0.0, 1.0))
        // Give them the same half-width and nothing separates them, so recency decides and the line
        // on top wins — which is the case that matters in cross-hatching.
        val even = vLine("even", 50.0, 2.0, 0.0, 100.0)
        assertEquals("even", StrokePicker().pick(listOf(fat, even), 50.0, 0.0, 0.0, 1.0))
    }

    @Test
    fun aStrokeOfOnePointIsADotAndIsPickedLikeAnyOther() {
        val d = dot("d", 50.0, 5.0, 1.0)
        assertEquals("d", StrokePicker().pick(listOf(d), 50.0, 5.0, 0.0, 1.0))
        // 5 away is inside 1 + 12, so the dot is still a candidate a finger can mean.
        assertEquals("d", StrokePicker().pick(listOf(d), 50.0, 10.0, 0.0, 1.0))
        // 25 away is not.
        assertNull(StrokePicker().pick(listOf(d), 50.0, 30.0, 0.0, 1.0))
        // And a dot under a line is ranked by the same score as any other line: the line the tap is
        // on scores -1, the dot 5 - 1 = 4, and cycling reaches the dot.
        val line = hLine("line", 0.0, 1.0)
        val got = tapSeries(StrokePicker(), listOf(line, d), 50.0, 0.0, 2)
        assertEquals(listOf("line", "d"), got)
    }

    @Test
    fun twoStrokesWithIdenticalGeometryAreResolvedByRecency() {
        // Byte-for-byte the same ribbon, drawn twice. Every distance is equal, so only recency can
        // separate them, and the cycle still walks both.
        val a = hLine("a", 0.0, 1.0)
        val b = hLine("b", 0.0, 1.0)
        assertEquals(listOf("b", "a", "b"), tapSeries(StrokePicker(), listOf(a, b), 50.0, 0.0, 3))
    }

    // ---- the cycle itself --------------------------------------------------------------------------

    @Test
    fun aCycleWalksEveryOverlappingStrokeAndComesBackToTheFirst() {
        // Five identical lines under one tap: the ranking is the drawing order, newest first, and
        // the sixth tap is back at the first.
        val lines = (0 until 5).map { hLine("L$it", 0.0, 1.0) }
        val got = tapSeries(StrokePicker(), lines, 50.0, 0.0, 7)
        assertEquals(listOf("L4", "L3", "L2", "L1", "L0", "L4", "L3"), got)
    }

    @Test
    fun cyclingMoreTimesThanThereAreCandidatesNeverReturnsNull() {
        val lines = (0 until 3).map { hLine("L$it", 0.0, 1.0) }
        val got = tapSeries(StrokePicker(), lines, 50.0, 0.0, 12)
        // Twelve taps, three lines: exactly four rounds, and the tenth is the first one again.
        val oneRound = listOf("L2", "L1", "L0")
        val expected = ArrayList<String>(12)
        for (i in 0 until 12) expected.add(oneRound[i % 3])
        assertEquals(expected, got)
    }

    @Test
    fun aSecondTapTooFarAwayStartsTheCycleAgain() {
        // Both lines are horizontal, so sliding the tap along them changes no score: the ranking
        // under x = 56 is exactly the ranking under x = 50 and the only thing that differs is how
        // far the taps are from each other.
        val within = StrokePicker()
        assertEquals("upper", within.pick(pair, 50.0, 8.5, 0.0, 1.0))
        // 6 doc px is the whole double-tap reach, so this is still the second half of one tap.
        assertEquals("lower", within.pick(pair, 56.0, 8.5, 50.0, 1.0))

        val beyond = StrokePicker()
        assertEquals("upper", beyond.pick(pair, 50.0, 8.5, 0.0, 1.0))
        // 7 doc px away is a gesture of its own, so it picks the first candidate again.
        assertEquals("upper", beyond.pick(pair, 57.0, 8.5, 50.0, 1.0))
    }

    @Test
    fun aTapThatFindsNothingResetsTheCycle() {
        val picker = StrokePicker()
        assertEquals("upper", picker.pick(pair, 50.0, 8.5, 0.0, 1.0))
        // A tap in the middle of nowhere, then straight back: the failed tap must not leave the
        // cycle half-way round, or the next tap would answer `lower` for no reason the user did.
        assertNull(picker.pick(pair, 50.0, 1000.0, 100.0, 1.0))
        assertEquals("upper", picker.pick(pair, 50.0, 8.5, 200.0, 1.0))
    }

    // ---- edges -------------------------------------------------------------------------------------

    @Test
    fun theToleranceEdgeIsInclusiveAtExactlyHalfWidthPlusSlop() {
        // Half-width 5 at zoom 8: the reach is 5 + 1.5 = 6.5 doc px, and 6.5 is reached.
        val fat = hLine("fat", 0.0, 5.0)
        assertEquals("fat", StrokePicker().pick(listOf(fat), 50.0, 6.5, 0.0, 8.0))
        assertNull(StrokePicker().pick(listOf(fat), 50.0, 6.6, 0.0, 8.0))
        // At zoom 1 the same stroke reaches 5 + 12 = 17 doc px.
        assertEquals("fat", StrokePicker().pick(listOf(fat), 50.0, 17.0, 0.0, 1.0))
        assertNull(StrokePicker().pick(listOf(fat), 50.0, 17.1, 0.0, 1.0))
    }

    @Test
    fun aDegenerateZoomIsReadAsOneToOneRatherThanAsAnInfiniteSlop() {
        val line = hLine("a", 0.0, 1.0)
        // 14 doc px away: past the 1 + 12 = 13 reach. A zero, negative or NaN zoom divided into the
        // slop would make it infinite and pick this up, so the guard is what keeps it a miss. An
        // infinite zoom would collapse the slop to nothing instead, so it is guarded as well.
        assertNull(StrokePicker().pick(listOf(line), 50.0, 14.0, 0.0, 0.0))
        assertNull(StrokePicker().pick(listOf(line), 50.0, 14.0, 0.0, -4.0))
        assertNull(StrokePicker().pick(listOf(line), 50.0, 14.0, 0.0, Double.NaN))
        assertNull(StrokePicker().pick(listOf(line), 50.0, 14.0, 0.0, Double.POSITIVE_INFINITY))
        // And the same zoom still picks what a 1:1 tap picks.
        assertEquals("a", StrokePicker().pick(listOf(line), 50.0, 12.0, 0.0, 0.0))
    }

    @Test
    fun aClockThatIsNotANumberNeverContinuesACycle() {
        val picker = StrokePicker()
        assertEquals("upper", picker.pick(pair, 50.0, 8.5, Double.NaN, 1.0))
        // Neither `dt < 0` nor `dt >= 400` is true for a NaN, so without an explicit guard the
        // distance test alone would answer and this tap would wrongly cycle.
        assertEquals("upper", picker.pick(pair, 50.0, 8.5, Double.NaN, 1.0))
    }

    @Test
    fun aLineWithBrokenCoordinatesIsNeverPickedAndDoesNotHideTheOthers() {
        val broken = InkLine(
            "broken",
            doubleArrayOf(Double.NaN, 100.0),
            doubleArrayOf(0.0, 0.0),
            doubleArrayOf(1.0, 1.0),
        )
        // Every distance to a line with a NaN point is NaN, which is never the smallest, so the line
        // has no nearest point, scores absurdly high and is simply not a candidate.
        val good = hLine("good", 0.0, 1.0)
        assertEquals("good", StrokePicker().pick(listOf(broken, good), 50.0, 0.0, 0.0, 1.0))
        assertNull(StrokePicker().pick(listOf(broken), 50.0, 0.0, 0.0, 1.0))
    }

    @Test
    fun pickingNeverTouchesTheLines() {
        val before = pair.toList()
        tapSeries(StrokePicker(), pair, 50.0, 8.5, 4)
        assertEquals(before, pair, "the picker reads the strokes and must not write to them")
    }

    @Test
    fun cyclingReconsidersTiesAcrossTheOldClusterBoundary() {
        // Scores 0, .75, 1.5: after newest A wins, C beats B by recency within their tie.
        val lines = listOf(hLine("B", 1.75, 1.0), hLine("C", 2.5, 1.0), hLine("A", 1.0, 1.0))
        assertEquals(listOf("A", "C", "B", "A"), tapSeries(StrokePicker(), lines, 50.0, 0.0, 4))
    }

    @Test
    fun duplicateIdsDoNotTrapTheCycle() {
        val lines = listOf(hLine("other", 0.0, 1.0), hLine("same", 0.0, 1.0), hLine("same", 0.0, 1.0))
        assertEquals(listOf("same", "same", "other", "same", "same", "other"),
            tapSeries(StrokePicker(), lines, 50.0, 0.0, 6))
    }

    @Test
    fun replacingACandidateWithTheSameIdRestartsTheCycle() {
        val picker = StrokePicker()
        val a = hLine("a", 0.0, 1.0)
        val b = hLine("b", 0.0, 1.0)
        assertEquals("b", picker.pick(listOf(a, b), 50.0, 0.0, 0.0, 1.0))
        assertEquals("b", picker.pick(listOf(a, hLine("b", 0.0, 1.0)), 50.0, 0.0, 10.0, 1.0))
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private fun hLine(
        id: String,
        y: Double,
        halfWidth: Double,
        x0: Double = 0.0,
        x1: Double = 100.0,
    ) = InkLine(id, doubleArrayOf(x0, x1), doubleArrayOf(y, y), doubleArrayOf(halfWidth, halfWidth))

    private fun vLine(
        id: String,
        x: Double,
        halfWidth: Double,
        y0: Double = 0.0,
        y1: Double = 100.0,
    ) = InkLine(
        id,
        doubleArrayOf(x, x),
        doubleArrayOf(y0, y1),
        doubleArrayOf(halfWidth, halfWidth),
    )

    private fun dot(id: String, x: Double, y: Double, halfWidth: Double) =
        InkLine(id, doubleArrayOf(x), doubleArrayOf(y), doubleArrayOf(halfWidth))

    /**
     * [count] taps at the same point [stepMs] apart, and what each one picked. A null here is a
     * failure in itself, so it is asserted here rather than compared as null.
     */
    private fun tapSeries(
        picker: StrokePicker,
        lines: List<InkLine>,
        x: Double,
        y: Double,
        count: Int,
        startMs: Double = 0.0,
        stepMs: Double = 10.0,
        zoom: Double = 1.0,
    ): List<String> {
        val out = ArrayList<String>(count)
        for (i in 0 until count) {
            val id = picker.pick(lines, x, y, startMs + i * stepMs, zoom)
            out.add(assertNotNull(id, "tap $i of $count picked nothing"))
        }
        return out
    }
}

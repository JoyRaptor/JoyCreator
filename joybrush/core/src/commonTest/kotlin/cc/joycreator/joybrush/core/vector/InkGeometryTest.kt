package cc.joycreator.joybrush.core.vector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * JB-5.10 — the pieces the vector eraser stands on: what an [InkLine] is allowed to be, how a
 * fractional point index turns into a place, and the segment maths the erase is solved with.
 */
class InkGeometryTest {

    private val tol = 1e-9

    // ---- the line contract ------------------------------------------------------------------------

    @Test
    fun aLineNeedsThreeArraysOfOneLength() {
        assertFailsWith<IllegalArgumentException> {
            InkLine("A", doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0), doubleArrayOf(1.0, 1.0))
        }
    }

    @Test
    fun aLineNeedsAtLeastOnePoint() {
        assertFailsWith<IllegalArgumentException> {
            InkLine("A", DoubleArray(0), DoubleArray(0), DoubleArray(0))
        }
    }

    @Test
    fun aSinglePointIsALegalLine() {
        val dab = InkLine("dab", doubleArrayOf(3.0), doubleArrayOf(4.0), doubleArrayOf(2.0))
        assertEquals(1, dab.pointCount)
        assertEquals(0.0, dab.lastParam, tol)
        assertEquals(0.0, dab.totalArcLength, tol)
        assertEquals(1, segmentCountOf(dab.pointCount), "a dot still has one (degenerate) segment")
        assertEquals(InkPoint(3.0, 4.0, 2.0), dab.pointAt(17.0))
    }

    @Test
    fun twoLinesWithTheSameContentAreEqual() {
        val a = InkLine("A", doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 2.0), doubleArrayOf(1.0, 1.5))
        val b = InkLine("A", doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 2.0), doubleArrayOf(1.0, 1.5))
        val c = InkLine("A", doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 2.0), doubleArrayOf(1.0, 1.6))
        val d = InkLine("B", doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 2.0), doubleArrayOf(1.0, 1.5))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != c, "half-widths are part of the line")
        assertTrue(a != d, "so is the id")
    }

    // ---- parameters ------------------------------------------------------------------------------

    @Test
    fun pointAtInterpolatesBetweenPoints() {
        val l = InkLine(
            "A",
            doubleArrayOf(0.0, 10.0, 20.0),
            doubleArrayOf(0.0, 0.0, 0.0),
            doubleArrayOf(1.0, 2.0, 3.0),
        )
        val p = l.pointAt(1.5) // halfway from point 1 to point 2

        assertEquals(15.0, p.x, tol)
        assertEquals(0.0, p.y, tol)
        assertEquals(2.5, p.halfWidth, tol, "half-widths interpolate the same way the points do")
    }

    @Test
    fun pointAtClampsToTheLine() {
        val l = InkLine("A", doubleArrayOf(0.0, 10.0), doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 1.0))

        assertEquals(0.0, l.pointAt(-99.0).x, tol)
        assertEquals(10.0, l.pointAt(99.0).x, tol)
    }

    @Test
    fun arcLengthFollowsThePolylineNotTheStraightLine() {
        // A right angle: a 3 doc px leg, then a 4 doc px leg.
        val l = InkLine(
            "A",
            doubleArrayOf(0.0, 3.0, 3.0),
            doubleArrayOf(0.0, 0.0, 4.0),
            doubleArrayOf(1.0, 1.0, 1.0),
        )

        assertEquals(7.0, l.totalArcLength, tol)
        assertEquals(3.0, l.arcLengthBetween(0.0, 1.0), tol)
        assertEquals(1.5, l.arcLengthBetween(0.5, 1.0), tol, "half of the first leg")
        assertEquals(3.5, l.arcLengthBetween(0.5, 1.5), tol, "across the corner, both legs")
        assertEquals(1.0, l.arcLengthBetween(1.0, 1.25), tol, "a quarter of the second leg")
        assertEquals(7.0, l.arcLengthBetween(-5.0, 99.0), tol, "clamped to the line")
    }

    @Test
    fun anEmptyOrReversedRangeHasNoLength() {
        val l = InkLine("A", doubleArrayOf(0.0, 10.0), doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 1.0))

        assertEquals(0.0, l.arcLengthBetween(1.0, 1.0), tol)
        assertEquals(0.0, l.arcLengthBetween(1.0, 0.25), tol)
        assertEquals(4.0, Piece("A", 0.0, 0.4).arcLengthIn(l), tol)
    }

    // ---- segment maths ---------------------------------------------------------------------------

    @Test
    fun pointToSegmentDistanceClampsToTheEnds() {
        assertEquals(5.0, pointSegmentDistance(5.0, 5.0, 0.0, 0.0, 10.0, 0.0), tol)
        assertEquals(5.0, pointSegmentDistance(15.0, 0.0, 0.0, 0.0, 10.0, 0.0), tol)
        assertEquals(5.0, pointSegmentDistance(-5.0, 0.0, 0.0, 0.0, 10.0, 0.0), tol)
        assertEquals(0.0, pointSegmentDistance(4.0, 0.0, 0.0, 0.0, 10.0, 0.0), tol)
        assertEquals(
            5.0, pointSegmentDistance(3.0, 4.0, 0.0, 0.0, 0.0, 0.0), tol, "a dot is a point",
        )
    }

    @Test
    fun segmentDistanceIsZeroExactlyWhenTheyTouch() {
        // Every one of these puts the closest pair in the interior of both segments, or on a shared
        // endpoint, and none of them is visible from the four endpoint distances: a plus sign is
        // zero apart even though all four endpoints are half the segment length away.
        assertEquals(
            0.0, segmentSegmentDistance(0.0, 0.0, 10.0, 0.0, 5.0, -5.0, 5.0, 5.0), tol, "at right angles",
        )
        assertEquals(
            0.0, segmentSegmentDistance(0.0, 0.0, 10.0, 0.0, 0.0, 0.2, 10.0, -0.2), tol,
            "a shallow crossing, at (5, 0)",
        )
        assertEquals(
            0.0, segmentSegmentDistance(0.0, 0.0, 10.0, 0.0, 10.0, 0.0, 10.0, 5.0), tol, "a shared endpoint",
        )
        assertEquals(
            0.0, segmentSegmentDistance(0.0, 0.0, 10.0, 0.0, 0.0, 0.0, 10.0, 0.0), tol, "the same segment twice",
        )
    }

    @Test
    fun segmentDistanceIsTheGapWhenTheyDoNotTouch() {
        // Disjoint, so the closest pair always includes an endpoint. The first pair's closest
        // approach is interior to both — which for segments that do not cross means they are
        // parallel, and then the gap is the plain perpendicular distance.
        assertEquals(
            3.0, segmentSegmentDistance(0.0, 0.0, 10.0, 0.0, 2.0, 3.0, 8.0, 3.0), tol, "interior to interior",
        )
        assertEquals(
            3.0, segmentSegmentDistance(0.0, 0.0, 10.0, 0.0, 0.0, 3.0, 10.0, 3.0), tol, "overlapping in x",
        )
        assertEquals(
            5.0, segmentSegmentDistance(0.0, 0.0, 1.0, 0.0, 6.0, 0.0, 7.0, 0.0), tol, "end to end",
        )
    }

    @Test
    fun anIntersectionReportsTheParameterOnBothSegments() {
        val out = DoubleArray(4)
        assertEquals(
            1, segmentIntersections(0.0, 0.0, 10.0, 0.0, 2.0, -5.0, 2.0, 5.0, out),
        )
        assertEquals(0.2, out[0], tol, "x = 2 of a 10 doc px segment")
        assertEquals(0.5, out[1], tol)
    }

    @Test
    fun touchingAtAnEndpointCounts() {
        val out = DoubleArray(4)
        assertEquals(1, segmentIntersections(0.0, 0.0, 10.0, 0.0, 10.0, 0.0, 10.0, 5.0, out))
        assertEquals(1.0, out[0], tol)
        assertEquals(0.0, out[1], tol)
    }

    @Test
    fun parallelAndApartSegmentsDoNotCross() {
        val out = DoubleArray(4)
        assertEquals(0, segmentIntersections(0.0, 0.0, 10.0, 0.0, 0.0, 1.0, 10.0, 1.0, out))
        assertEquals(
            0, segmentIntersections(0.0, 0.0, 10.0, 0.0, 20.0, 1.0, 30.0, 1.0, out), "no overlap",
        )
    }

    @Test
    fun aCollinearOverlapReportsWhereItStartsAndEnds() {
        val out = DoubleArray(4)
        assertEquals(2, segmentIntersections(0.0, 0.0, 10.0, 0.0, 5.0, 0.0, 15.0, 0.0, out))
        assertEquals(0.5, out[0], tol, "x = 5, where B starts")
        assertEquals(0.0, out[1], tol)
        assertEquals(1.0, out[2], tol, "x = 10, where A ends")
        assertEquals(0.5, out[3], tol)
    }

    @Test
    fun aDotCrossesOnlyWhatItSitsOn() {
        val out = DoubleArray(4)
        assertEquals(1, segmentIntersections(5.0, 0.0, 5.0, 0.0, 0.0, -5.0, 10.0, 5.0, out))
        assertEquals(0.0, out[0], tol)
        assertEquals(0.5, out[1], tol)
        assertEquals(0, segmentIntersections(5.0, 3.0, 5.0, 3.0, 0.0, -5.0, 10.0, 5.0, out))
    }

    @Test
    fun boxPrefilterRejectsOnlyWhatCannotTouch() {
        assertTrue(boxesMayTouch(0.0, 0.0, 1.0, 1.0, 0.0, 0.9, 0.9, 2.0, 2.0), "they overlap")
        assertTrue(boxesMayTouch(0.0, 0.0, 1.0, 0.0, 0.5, 1.5, -1.0, 1.5, 1.0), "the pad covers it")
        assertTrue(!boxesMayTouch(0.0, 0.0, 1.0, 0.0, 0.0, 2.0, -1.0, 2.0, 1.0), "too far in x")
        assertTrue(!boxesMayTouch(0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 2.0, 1.0, 2.0), "too far in y")
    }

    @Test
    fun anEmptyRangeIsNotAnInterval() {
        assertFailsWith<IllegalArgumentException> { ParamRange(1.0, 0.0) }
    }
}

package cc.joycreator.joybrush.core.brush

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class QuadMathTest {
    private fun quad(vararg coordinates: Float, roundness: Float = 0f): QuadDab {
        require(coordinates.size == 8)
        return QuadDab(coordinates[0], coordinates[1], coordinates[2], coordinates[3],
            coordinates[4], coordinates[5], coordinates[6], coordinates[7], roundness)
    }

    private fun square(roundness: Float = 0f) = quad(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f, roundness = roundness)

    @Test fun sharpSquareAndRectangleHaveAnalyticInsideAndOutsideDistances() {
        val s = QuadMath.prepare(square())
        assertFalse(s.empty)
        assertFalse(s.circle)
        assertEquals(4, s.count)
        assertEquals(-1f, s.signedDistance(0f, 0f))
        assertEquals(-.25f, s.signedDistance(.75f, .25f))
        assertEquals(0f, s.signedDistance(1f, .2f))
        assertEquals(sqrt(2f), s.signedDistance(2f, 2f), 1e-6f)
        assertEquals(1f, s.normalizationScale)
        val r = QuadMath.prepare(quad(-2f, -1f, 2f, -1f, 2f, 1f, -2f, 1f))
        assertEquals(-1f, r.signedDistance(0f, 0f))
        assertEquals(sqrt(2f), r.signedDistance(3f, 2f), 1e-6f)
        assertEquals(1f, r.normalizationScale)
    }

    @Test fun maximumRoundnessIsAnExactCircleAndRectangleBecomesACapsule() {
        val circle = QuadMath.prepare(square(1f))
        assertTrue(circle.circle)
        assertEquals(0.0, circle.circleCenterX)
        assertEquals(0.0, circle.circleCenterY)
        assertEquals(1.0, circle.circleRadius)
        assertEquals(-1f, circle.signedDistance(0f, 0f))
        assertEquals(0f, circle.signedDistance(.6f, .8f), 1e-6f)
        assertEquals(sqrt(2f) - 1f, circle.signedDistance(1f, 1f), 1e-6f)
        val capsule = QuadMath.prepare(quad(-2f, -1f, 2f, -1f, 2f, 1f, -2f, 1f, roundness = 1f))
        assertFalse(capsule.circle)
        assertEquals(-1f, capsule.signedDistance(0f, 0f), 1e-6f)
        assertEquals(0f, capsule.signedDistance(2f, 0f), 1e-6f)
        assertEquals(-.5f, capsule.signedDistance(0f, .5f), 1e-6f)
        assertEquals(sqrt(2f) - 1f, capsule.signedDistance(2f, 1f), 1e-6f)
    }

    @Test fun repeatedConsecutiveHandleProducesTheSameTriangle() {
        val triangle = QuadMath.prepare(quad(-1f, -1f, 1f, -1f, 0f, 1f, 0f, 1f))
        assertFalse(triangle.empty)
        assertEquals(3, triangle.count)
        // The sloping sides have equations +/-2x+y=1, so their centre distance is 1/sqrt(5).
        assertEquals(-1f / sqrt(5f), triangle.signedDistance(0f, 0f), 1e-6f)
        assertTrue(triangle.signedDistance(1f, 1f) > 0f)
        assertEquals(0f, triangle.signedDistance(0f, 1f))
        val rounded = QuadMath.prepare(quad(-1f, -1f, 1f, -1f, 0f, 1f, 0f, 1f, roundness = 1f))
        assertEquals(3, rounded.count)
        assertTrue(rounded.signedDistance(0f, 0f) < 0f)
        assertTrue(rounded.signedDistance(0f, 1f) > 0f, "rounding must actually trim the triangle's apex")
    }

    @Test fun roundedCapsCancelOriginalBoundaryVerticesAndEdgesWithoutSignSeams() {
        val capsule = QuadMath.prepare(quad(-2f, -1f, 2f, -1f, 2f, 1f, -2f, 1f, roundness = 1f))
        val reversed = QuadMath.prepare(quad(-2f, -1f, -2f, 1f, 2f, 1f, 2f, -1f, roundness = 1f))
        // A radius-one capsule around the segment (-1,0)..(1,0), independent of fillet math.
        for (sx in listOf(-1f, 1f)) for (sy in listOf(-1f, 1f)) {
            for (dx in listOf(-.001f, 0f, .001f)) for (dy in listOf(-.001f, 0f, .001f)) {
                val x = sx * 2f + dx; val y = sy + dy
                val expected = hypot(max(abs(x) - 1f, 0f), y) - 1f
                assertTrue(expected > 0f)
                assertEquals(expected, capsule.signedDistance(x, y), 1e-6f, "capsule at $x,$y")
                assertEquals(expected, reversed.signedDistance(x, y), 1e-6f, "reversed capsule at $x,$y")
            }
            for ((x, y) in listOf(sx * 2f to sy * .5f, sx * 1.5f to sy)) {
                val expected = hypot(max(abs(x) - 1f, 0f), y) - 1f
                assertEquals(expected, capsule.signedDistance(x, y), 1e-6f, "trimmed original edge at $x,$y")
            }
        }
        val triangle = QuadMath.prepare(quad(-1f, -1f, 1f, -1f, 0f, 1f, 0f, 1f, roundness = 1f))
        val triangleReverse = QuadMath.prepare(quad(-1f, -1f, 0f, 1f, 1f, -1f, -1f, -1f, roundness = 1f))
        // The apex arc has centre (0,-1/4) and radius sqrt(5)/4 by symmetry and tangency.
        for (x in listOf(-.001f, 0f, .001f)) for (dy in listOf(-.001f, 0f, .001f)) {
            val y = 1f + dy
            val expected = hypot(x, y + .25f) - sqrt(5f) / 4f
            assertEquals(expected, triangle.signedDistance(x, y), 1e-6f, "triangle apex at $x,$y")
            assertEquals(expected, triangleReverse.signedDistance(x, y), 1e-6f, "reversed triangle apex at $x,$y")
        }
        val concave = QuadMath.prepare(quad(0f, 0f, 2f, 0f, .5f, .5f, 0f, 2f, roundness = 1f))
        for (dx in listOf(-.001f, 0f, .001f)) for (dy in listOf(-.001f, 0f, .001f)) {
            assertTrue(concave.signedDistance(.5f + dx, .5f + dy) < 0f,
                "the filled concave corner must stay inside across its original vertex")
        }
    }

    @Test fun concaveCornerRoundingAddsItsCapWhileConvexCornersLoseTheirs() {
        val sharp = QuadMath.prepare(quad(0f, 0f, 2f, 0f, .5f, .5f, 0f, 2f))
        val rounded = QuadMath.prepare(quad(0f, 0f, 2f, 0f, .5f, .5f, 0f, 2f, roundness = 1f))
        assertTrue(sharp.signedDistance(.55f, .55f) > 0f, "point is in the notch")
        assertTrue(rounded.signedDistance(.55f, .55f) < 0f, "rounding fills the concave cap")
        assertTrue(sharp.signedDistance(.05f, .05f) < 0f)
        assertTrue(rounded.signedDistance(.05f, .05f) > 0f, "the convex lower-left cap is removed")
        assertTrue(rounded.normalizationScale > 0f && rounded.normalizationScale.isFinite())
    }

    @Test fun bowTieKeepsBothLobesDespiteItsZeroSignedArea() {
        val bow = QuadMath.prepare(quad(-1f, -1f, 1f, 1f, -1f, 1f, 1f, -1f))
        assertFalse(bow.empty)
        assertEquals(4, bow.count)
        assertTrue(bow.signedDistance(0f, .75f) < 0f)
        assertTrue(bow.signedDistance(0f, -.75f) < 0f)
        assertTrue(bow.signedDistance(.75f, 0f) > 0f)
        assertEquals(0f, bow.signedDistance(0f, 0f))
        assertEquals(1f / 3f, bow.normalizationScale, 1e-6f,
            "the crossing/lobe centroids must supply an interior depth")
        val rounded = QuadMath.prepare(quad(-1f, -1f, 1f, 1f, -1f, 1f, 1f, -1f, roundness = .65f))
        assertFalse(rounded.empty)
        assertTrue(rounded.signedDistance(0f, .6f) < 0f)
        assertTrue(rounded.signedDistance(0f, -.6f) < 0f)
        assertTrue(rounded.signedDistance(.8f, 0f) > 0f)
    }

    @Test fun backtrackedAndCanceledEdgesCannotLeaveGhostOutlines() {
        val cleaned = QuadMath.prepare(quad(0f, 0f, 2f, 0f, 1f, 0f, 0f, 1f))
        assertEquals(3, cleaned.count)
        assertEquals(.75f, cleaned.signedDistance(1.75f, 0f), 1e-6f,
            "the retraced part of the long bottom edge is outside, not a zero-distance line")
        assertTrue(cleaned.signedDistance(.2f, .2f) < 0f)
        for (q in listOf(
            quad(0f, 0f, 2f, 0f, 0f, 0f, 0f, 2f),
            quad(-1f, 0f, 0f, 0f, 1f, 0f, 2f, 0f),
            quad(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            quad(Float.NaN, 0f, 1f, 0f, 1f, 1f, 0f, 1f),
        )) {
            val empty = QuadMath.prepare(q)
            assertTrue(empty.empty)
            assertFalse(empty.circle)
            assertEquals(1f, empty.normalizationScale)
            assertEquals(Float.POSITIVE_INFINITY, empty.signedDistance(0f, 0f))
        }
    }

    @Test fun normalizationFollowsThinOffCentreInteriorsRatherThanTheCoordinateOrigin() {
        val thin = QuadMath.prepare(quad(3f, 4.9f, 5f, 4.9f, 5f, 5.1f, 3f, 5.1f))
        assertEquals(.1f, thin.normalizationScale, 1e-6f)
        assertEquals(-.1f, thin.signedDistance(4f, 5f), 1e-6f)
        assertTrue(thin.signedDistance(0f, 0f) > 0f)
    }

    @Test fun tinyRoundnessCannotTurnOutsideCornersIntoGhostCoverage() {
        for (roundness in listOf(Float.MIN_VALUE, 1e-30f, 1e-10f, 1e-7f, 1e-6f)) {
            val shape = QuadMath.prepare(square(roundness))
            assertFalse(shape.empty)
            assertEquals(sqrt(2f), shape.signedDistance(2f, 2f), 1e-5f)
            assertEquals(-1f, shape.signedDistance(0f, 0f), 1e-5f)
            for (x in -4..4) for (y in -4..4) {
                assertTrue(shape.signedDistance(x * .7f, y * .6f).isFinite(), "roundness $roundness")
            }
        }
        assertTrue(QuadMath.prepare(square(1e-10f)).corners.none { it.rounded },
            "numerically collapsed cap triangles must be treated as sharp")
    }

    @Test fun subPixelNumericalFloorRejectsCollapsedShapesAndBoundsFalloffDivision() {
        val tiny = QuadMath.prepare(quad(-1e-30f, -1e-30f, 1e-30f, -1e-30f,
            1e-30f, 1e-30f, -1e-30f, 1e-30f, roundness = 1f))
        assertTrue(tiny.empty)
        assertEquals(1f, tiny.normalizationScale)
        val narrow = QuadMath.prepare(quad(-.5f, 0f, .5f, 0f, 0f, 2.5e-7f, 0f, 2.5e-7f))
        assertFalse(narrow.empty)
        assertEquals(QuadMath.NUMERICAL_FLOOR.toFloat(), narrow.normalizationScale)
        assertTrue(narrow.signedDistance(0f, 1e-7f) < 0f)
        assertTrue(narrow.signedDistance(0f, 1e-5f) > 0f)
    }

    @Test fun windingTranslationAndUniformScalePreserveTheShape() {
        val original = quad(-1f, -.7f, 1f, -.8f, .2f, .1f, -.7f, 1f, roundness = .6f)
        val reverse = quad(original.x0, original.y0, original.x3, original.y3,
            original.x2, original.y2, original.x1, original.y1, roundness = original.roundness)
        val factor = 2.5f; val tx = 3f; val ty = -2f
        val transformed = quad(original.x0 * factor + tx, original.y0 * factor + ty,
            original.x1 * factor + tx, original.y1 * factor + ty,
            original.x2 * factor + tx, original.y2 * factor + ty,
            original.x3 * factor + tx, original.y3 * factor + ty, roundness = original.roundness)
        val a = QuadMath.prepare(original); val b = QuadMath.prepare(reverse); val c = QuadMath.prepare(transformed)
        for (iy in -8..8) for (ix in -8..8) {
            val x = ix * .19f; val y = iy * .17f
            assertEquals(a.signedDistance(x, y), b.signedDistance(x, y), 1e-5f)
            assertEquals(a.signedDistance(x, y) * factor,
                c.signedDistance(x * factor + tx, y * factor + ty), 1e-5f)
        }
        assertEquals(a.normalizationScale * factor, c.normalizationScale, 1e-5f)
    }

    @Test fun preparedGeometryIsCachedAndFiniteAcrossSharpRoundedAndCrossingCases() {
        val shapes = listOf(square(), square(.5f), square(1f),
            quad(-1f, -1f, 1f, 1f, -1f, 1f, 1f, -1f, roundness = .9f),
            quad(-1f, -1f, 1f, -1f, .2f, .1f, -1f, 1f, roundness = .7f),
            quad(-1f, 0f, 0f, .00001f, 1f, 0f, 0f, 1f, roundness = .8f))
        for (q in shapes) {
            val prepared = QuadMath.prepare(q)
            assertSame(prepared, QuadMath.prepare(q))
            assertSame(prepared, q.prepared)
            assertEquals(q, q.copy(), "the lazy cache must not enter data-class equality")
            assertTrue(prepared.normalizationScale > 0f && prepared.normalizationScale.isFinite())
            for (y in -10..10) for (x in -10..10) {
                val distance = prepared.signedDistance(x * .2f, y * .2f)
                assertTrue(distance.isFinite(), "nonfinite distance for $q at $x,$y")
                assertTrue(abs(distance) < 10f)
            }
        }
    }
}

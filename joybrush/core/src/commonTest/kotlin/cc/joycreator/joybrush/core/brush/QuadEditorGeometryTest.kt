package cc.joycreator.joybrush.core.brush

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DRAFT — uncompiled and untested. Add Android interaction tests when the view lands. */
class QuadEditorGeometryTest {
    private val geometry = QuadEditorGeometry(150f, 175f, 80f, 130f)

    @Test fun normalizedCoordinatesRoundTripWithoutAspectBaking() {
        val shape = QuadShapeSpec(aspect = .8f)
        for (p in shape.points + QuadPoint(.123f, -.789f)) {
            val screen = geometry.toView(p)
            val back = geometry.toPoint(screen.x, screen.y)
            assertTrue(abs(p.x - back.x) < .000001f)
            assertTrue(abs(p.y - back.y) < .000001f)
        }
        assertEquals(QuadPoint(-1f, 1f), geometry.toPoint(-10000f, 10000f))
        val changed = QuadEditorGeometry.move(shape, 1, -1f, 1f)
        assertEquals(shape.aspect, changed.aspect)
        assertEquals(shape.profile, changed.profile)
        assertEquals(shape.roundness, changed.roundness)
        assertEquals(shape.points[0], changed.points[0])
        assertEquals(QuadPoint(-1f, 1f), changed.points[1])
    }

    @Test fun pointCrossingsAndCoincidentPointsRemainLegalAndSelectable() {
        val square = QuadShapeSpec()
        val crossed = QuadEditorGeometry.move(square, 0, 1f, 1f)
        assertEquals(square.points[2], crossed.points[0])
        val where = geometry.toView(crossed.points[0])
        assertEquals(0, geometry.pick(where.x, where.y, crossed, 0f, 22f, 0))
        assertEquals(2, geometry.pick(where.x, where.y, crossed, 0f, 22f, 2))
        assertEquals(-1, geometry.pick(-500f, -500f, crossed, 0f, 22f, 0))
    }

    @Test fun facingUsesClockwiseDocumentCoordinatesAndKeepsAngleInDeadZone() {
        for (angle in listOf(-179f, -90f, -22.5f, 0f, 90f, 179f)) {
            val p = geometry.arrow(angle)
            assertEquals(angle, geometry.facing(p.x, p.y, 0f), .0001f)
        }
        assertEquals(47f, geometry.facing(150f, 175f, 47f, 10f))
        assertEquals(-180f, QuadEditorGeometry.degrees(540f))
        assertEquals(0f, QuadEditorGeometry.degrees(Float.NaN))
        val arrow = geometry.arrow(90f)
        assertEquals(4, geometry.pick(arrow.x, arrow.y, QuadShapeSpec(), 90f, 22f, 0))
        assertEquals(-1, geometry.pick(arrow.x, arrow.y, QuadShapeSpec(), 90f, 22f, 0, includeArrow = false))
    }

    @Test fun ringAndHitTargetsFitAvailableAreaIncludingTallLayouts() {
        val g = assertNotNull(QuadEditorGeometry.fit(10f, 30f, 288f, 320f, 22f, 24f))
        assertTrue(g.halfSide > 0f)
        for (degrees in 0 until 360 step 5) {
            val p = g.arrow(degrees.toFloat())
            assertTrue(p.x - 22f >= 10f - .0001f && p.x + 22f <= 298f + .0001f)
            assertTrue(p.y - 22f >= 30f - .0001f && p.y + 22f <= 350f + .0001f)
        }
        assertNull(QuadEditorGeometry.fit(0f, 0f, 30f, 30f, 22f, 24f))
        assertNull(QuadEditorGeometry.fit(0f, 0f, Float.NaN, 300f, 22f, 24f))
    }
}

package cc.joycreator.joybrush.core.select

import cc.joycreator.joybrush.core.shape.Pt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SelectionMaskAdmissionTest {
    private fun rect(x0: Double, y0: Double, x1: Double, y1: Double) = listOf(
        Pt(x0, y0), Pt(x1, y0), Pt(x1, y1), Pt(x0, y1),
    )

    private fun accepted(points: List<Pt>): SelectionMaskAdmission.Plan =
        assertIs<SelectionMaskAdmission.Result.Accepted>(SelectionMaskAdmission.preflightPolygon(points)).plan

    private fun refused(points: List<Pt>): String =
        assertIs<SelectionMaskAdmission.Result.Refused>(SelectionMaskAdmission.preflightPolygon(points)).reason

    @Test fun alignedEightByEightTileSquareIsAdmittedAtTheLimit() {
        val plan = accepted(rect(0.0, 0.0, 2048.0, 2048.0))
        assertEquals(2048, plan.width)
        assertEquals(2048, plan.height)
        assertEquals(64, plan.coverageTileCount)
    }

    @Test fun unaligningOneAxisByOnePixelAddsAColumnAndRefuses() {
        val reason = refused(rect(1.0, 0.0, 2049.0, 2048.0))
        assertTrue(reason.contains("too many tiles"))
    }

    @Test fun aMaximumSpanSquareIsRefusedByTileCountBeforeRasterisation() {
        val reason = refused(rect(0.0, 0.0, 16384.0, 16384.0))
        assertTrue(reason.contains("too many tiles"))
    }

    @Test fun negativeAndUnalignedCoordinatesUseFloorTileDivision() {
        val plan = accepted(rect(-2048.0, -2048.0, 0.0, 0.0))
        assertEquals(64, plan.coverageTileCount)

        val unaligned = SelectionMaskAdmission.preflightPolygon(rect(-2049.0, 0.0, -1.0, 2048.0))
        assertIs<SelectionMaskAdmission.Result.Refused>(unaligned)
    }

    @Test fun oneTileRowUsesTheSameBoundedTileAccounting() {
        val plan = accepted(rect(0.0, 0.0, 16384.0, 1.0))
        assertEquals(64, plan.coverageTileCount)
        assertEquals(1, plan.height)
        assertTrue(refused(rect(1.0, 0.0, 16385.0, 1.0)).contains("too many tiles"))
    }

    @Test fun rejectsDegenerateNonFiniteAndUnaddressablePolygons() {
        assertTrue(refused(emptyList()).contains("three points"))
        assertTrue(refused(rect(0.0, 1.0, 20.0, 1.0)).contains("no area"))
        assertTrue(refused(rect(0.0, 0.0, Double.POSITIVE_INFINITY, 2.0)).contains("finite"))
        assertTrue(refused(rect(1_073_741_825.0, 0.0, 1_073_741_826.0, 1.0)).contains("origin"))
        assertTrue(refused(rect(-1.0e300, 0.0, 1.0e300, 1.0)).contains("too large"))
    }
}

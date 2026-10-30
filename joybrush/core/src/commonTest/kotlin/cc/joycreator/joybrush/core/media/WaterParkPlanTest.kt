package cc.joycreator.joybrush.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaterParkPlanTest {
    @Test fun aParkedTileCostsWellUnderAWorkingSlot() {
        // p0, p1, paper, w0, w1 at 16 B, flux 8, run 4 = 92 B a texel; a working slot is 227 (MediaAtlasPlacement).
        assertEquals(92L * 256 * 256, WaterParkPlan.bytesPerTile())
        assertTrue(WaterParkPlan.bytesPerTile() * 2 < MediaAtlasPlacement.BYTES_PER_SLOT)
    }

    @Test fun coarseWaterParksItsWaterAtAQuarter() {
        // Paint 48 B at every px; water 44 B at a quarter of them.
        assertEquals(48L * 65536 + 44L * 128 * 128, WaterParkPlan.bytesPerTile(wf = 2))
    }

    @Test fun unitsAreWholeAndNeverNegative() {
        val one = WaterParkPlan.bytesPerTile()
        assertEquals(0, WaterParkPlan.units(-5))
        assertEquals(0, WaterParkPlan.units(one - 1))
        assertEquals(3, WaterParkPlan.units(3 * one + one / 2))
    }
}

package cc.joycreator.joybrush.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaterJobBudgetTest {
    @Test fun slowFramesShrinkItAndFastFramesGrowItWithinItsBounds() {
        val b = WaterJobBudget(start = 1e6, fastMs = 28f, slowMs = 45f, least = 2e5, most = 4e6)
        assertEquals(1e6, b.next(0f))                 // unknown: unchanged
        assertEquals(1e6, b.next(35f))                // on pace: unchanged
        assertTrue(b.next(90f) < 1e6)                 // twice too slow: about halved
        assertEquals(5e5, b.units, 1.0)
        repeat(40) { b.next(500f) }
        assertEquals(2e5, b.units)                    // never below the least
        repeat(40) { b.next(10f) }
        assertEquals(4e6, b.units)                    // never above the most
        b.reset()
        assertEquals(1e6, b.units)
    }
}

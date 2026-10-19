package cc.joycreator.joybrush.core.media

import kotlin.test.Test
import kotlin.test.assertEquals

/** The owner's watercolour A/B (October 18): Option A's steps and debt, Option S's quiet. */
class WaterABTest {
    @Test fun brushFirstRunsOneStepWhileDrawingAndPaysTheRestBackAfter() {
        val s = WaterSchedule()
        assertEquals(4, s.whileDrawing(enabled = false, full = 4), "off: today's four steps")
        repeat(10) { assertEquals(1, s.whileDrawing(true, 4)) }
        assertEquals(30, s.debt, "three steps owed a frame")
        assertEquals(8, s.whileIdle(true, 4), "after the lift: at most eight a frame")
        assertEquals(26, s.debt)
        repeat(6) { s.whileIdle(true, 4) }
        assertEquals(2, s.debt)
        assertEquals(6, s.whileIdle(true, 4), "the last of it")
        assertEquals(4, s.whileIdle(true, 4), "paid: the usual four")
    }

    @Test fun theDebtIsBoundedSoASlowPhoneLagsInsteadOfBursting() {
        val s = WaterSchedule()
        repeat(1000) { s.whileDrawing(true, 4) }
        assertEquals(WaterSchedule.MAX_DEBT, s.debt)
        s.reset()
        assertEquals(0, s.debt)
    }

    @Test fun quietIsStandingWaterThatStoppedSpreading() {
        val q = WaterQuiet(minMm = 0.002f, flowEps = 1f)
        fun grid(vararg wet: Int, flow: Float = 0.3f) = FloatArray(16 * 4).also { g -> for (b in wet) { g[b * 4] = 0.1f; g[b * 4 + 2] = flow } }
        assertEquals(0L, q.quietFor(500), "never measured")
        q.observe(grid(1, 2), 0)
        q.observe(grid(1, 2, 3), 300)                   // still spreading
        q.observe(grid(1, 2, 3), 600)                   // a settling pool's slow edge flow is not "moving"
        assertEquals(900L, q.quietFor(1200))
        q.observe(grid(1, 2, 3, flow = 2.5f), 1500)      // a drip runs
        assertEquals(0L, q.quietFor(1500))
        q.reset(2000)
        assertEquals(500L, q.quietFor(2500))
    }

    @Test fun theDefaultIsTodaysWater() {
        val w = WetConstants()
        assertEquals(w, WaterAB().apply(w), "nothing changes unless a candidate is switched on")
        assertEquals(1.0, WaterAB(noRelief = true).apply(w).noBody)
    }
}

package cc.joycreator.joybrush.core.media

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The wet sheet's arithmetic ([WetSheetMath]). */
class WetSheetMathTest {
    @Test fun aCellIsOneToTwoScreenPixelsAtTheZoomPaintedAt() {
        assertEquals(2, WetSheetMath.cellFor(1f), "100 %: 2 px")
        assertEquals(4, WetSheetMath.cellFor(0.31f), "the owner's zoomed-out page: 4 px, 1.2 on screen")
        assertEquals(4, WetSheetMath.cellFor(0.5f))
        assertEquals(8, WetSheetMath.cellFor(0.2f)); assertEquals(8, WetSheetMath.cellFor(0.05f), "never coarser than 8")
        assertEquals(1, WetSheetMath.cellFor(1.5f)); assertEquals(1, WetSheetMath.cellFor(4f), "zoomed in: full resolution")
        assertEquals(2, WetSheetMath.cellFor(0f), "no zoom known: as at 100 %")
        for (z in listOf(0.3f, 0.45f, 0.7f, 1f)) assertTrue(WetSheetMath.cellFor(z) * z in 1f..2.0001f, "zoom $z")
    }

    @Test fun theSheetIsPlacedRoundThePenOnItsSnap() {
        val o = WetSheetMath.origin(5000.0, 4)
        assertEquals(0f, o % (16 * 4))
        assertTrue(abs(5000 - (o + 1024 * 4 / 2)) <= 16 * 4, "the pen is at the middle, to a snap")
        assertTrue(WetSheetMath.origin(-100.0, 2) < -1000f, "negative paper too")
        assertTrue(WetSheetMath.covers(o, o, 4, 5000.0, 5000.0, 200.0))
        assertFalse(WetSheetMath.covers(o, o, 4, o + 100.0, 5000.0, 200.0), "inside its margin is not covered")
        assertFalse(WetSheetMath.covers(o, o, 4, o + 4096.0 + 1, 5000.0, 0.0))
    }

    @Test fun theTimeStepSharesTheFramesRealTimeBetweenItsSteps() {
        val base = WetConstants()
        // A 33 ms frame of two steps on 4 px cells: 1/60 s each, the water keeps the clock's pace.
        val c = WetSheetMath.constants(base, 33.3f, 2, 4)
        assertEquals(1.0 / 60, c.dt, 1e-4); assertEquals(2, c.substeps)
        // Quick frames with many steps never go under the shipped step; a stalled frame never over the stable one.
        assertEquals(base.dt, WetSheetMath.constants(base, 16.7f, 8, 4).dt)
        assertEquals(1.0 / 30, WetSheetMath.constants(base, 400f, 1, 4).dt, 1e-9)
        assertEquals(1.0 / 60, WetSheetMath.constants(base, 400f, 1, 2).dt, 1e-9, "2 px cells: the step running water needs")
        assertEquals(1.0 / 60, WetSheetMath.constants(base, 400f, 1, 4, speed = 2.0).dt, 1e-9, "twice as fast: half the step")
        assertEquals(2.7, WetSheetMath.runCapMmPerS(), 1e-9, "what a full-resolution cell allows at the shipped step")
        assertEquals(5.4, WetSheetMath.runCapMmPerS(2.0), 1e-9)
        // Full-resolution cells cannot take a longer step at all (it broke into speckle on the Note 9).
        assertEquals(base, WetSheetMath.constants(base, 100f, 1, 1).copy(substeps = base.substeps))
    }

    @Test fun aLongerStepLeavesTheSteadyFlowAndTheViscosityPerSecondAsShipped() {
        val base = WetConstants()
        val c = WetSheetMath.constants(base, 33.3f, 1, 4)
        // The steady flow per unit head: dt * flow / (1 - keep).
        assertEquals(base.dt * base.flow / (1 - base.keep), c.dt * c.flow / (1 - c.keep), 1e-9)
        // What is kept over a thirtieth of a second: four shipped steps' worth.
        assertEquals(Math.pow(base.keep, 4.0), c.keep, 1e-3)
        assertTrue(c.keep < base.keep && c.flow < base.flow * 4)
    }

    @Test fun thePaceAddsStepsWhileFramesAreQuickAndBacksOffAfterASlowOne() {
        val p = WetSheetMath.Pace(most = 6)
        repeat(10) { p.next(16.7f) }
        assertEquals(6, p.steps, "quick frames: up to the most")
        assertEquals(5, p.next(50f), "a missed beat: one fewer")
        repeat(30) { p.next(33.3f) }
        assertEquals(5, p.steps, "and no higher again at once")
        repeat(40) { p.next(33.3f) }
        assertEquals(6, p.steps, "after two calm seconds it tries one more")
        repeat(20) { p.next(60f) }
        assertEquals(1, p.steps, "never under one")
    }
}

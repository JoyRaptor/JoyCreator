package cc.joycreator.joybrush.core.media

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The wet sheet's arithmetic ([WetSheetMath]). */
class WetSheetMathTest {
    @Test fun theSheetIsShapedLikeTheScreen() {
        // The Note 9 at its default resolution: 1080 x 2220.
        val g = WetSheetMath.grid(1080, 2220)
        // 1280 x 624 cells for the screen itself, and 24 more on every side.
        assertEquals(1280 + 48, g.h); assertEquals(624 + 48, g.w)
        assertEquals(0, g.w % 16); assertEquals(0, g.h % 16)
        assertTrue(g.cells < 1024 * 1024, "less to hold than the first slice's square sheet")
        assertEquals(WetSheetMath.Grid(1328, 672), WetSheetMath.grid(2220, 1080), "turned on its side")
        assertEquals(WetSheetMath.Grid(944, 944), WetSheetMath.grid(1000, 1000, WetSheetMath.LONG_FAST))
        assertEquals(128 + 48, WetSheetMath.grid(10, 4000).w, "never a sliver")
    }

    @Test fun aCellCoversTheSameShareOfTheScreenAtEveryZoom() {
        val long = 2220
        // 100 %: 1.75 document px. Zoomed out to the owner's 31 %: 5.6 px, still 1.7 on the screen.
        assertEquals(1.75, WetSheetMath.cellFor(1f, long))
        val out = WetSheetMath.cellFor(0.31f, long)
        assertEquals(5.625, out); assertTrue(out * 0.31 in 1.6..1.8)
        for (z in listOf(0.2f, 0.31f, 0.5f, 0.8f, 1f, 1.5f)) {
            val c = WetSheetMath.cellFor(z, long)
            assertTrue(c * 1280 * z >= long - 0.01 && c * 1280 * z < 1.12 * long, "zoom $z: 1280 cells cover the screen's long side, and little more")
        }
        assertEquals(1.0, WetSheetMath.cellFor(4f, long), "zoomed in: a document pixel, never finer")
        assertEquals(16.0, WetSheetMath.cellFor(0.02f, long), "never coarser than 16")
        assertEquals(2.0, WetSheetMath.cellFor(0f, long), "no zoom known")
        assertTrue(WetSheetMath.cellFor(1f, long, WetSheetMath.LONG_FAST) > 2.4, "Fast: wider cells")
    }

    @Test fun zoomedOutTheWaterTreatsThePaperAsMagnified() {
        assertEquals(1.0, WetSheetMath.worldScale(1.0)); assertEquals(1.0, WetSheetMath.worldScale(1.75))
        assertEquals(1.0, WetSheetMath.worldScale(2.0))
        assertEquals(2.8125, WetSheetMath.worldScale(5.625))
        for (c in listOf(1.0, 1.5, 2.0, 3.0, 5.625, 16.0)) assertTrue(WetSheetMath.physicsCell(c) in 1.0..2.0, "cell $c")
        assertEquals(1.75, WetSheetMath.physicsCell(1.75)); assertEquals(2.0, WetSheetMath.physicsCell(8.0))
    }

    @Test fun theSheetIsPlacedRoundTheMiddleOfTheViewOnItsSnap() {
        val g = WetSheetMath.Grid(624, 1280)
        val o = WetSheetMath.origin(5000.0, g.w, 4.0)
        assertEquals(0f, o % (8 * 4))
        assertEquals(0f, WetSheetMath.origin(5000.0, g.w, 1.625) % 1f, "a whole number of document px whatever the cell")
        assertTrue(abs(5000 - (o + g.w * 4 / 2)) <= 16 * 4, "the view's middle is the sheet's, to a snap")
        assertTrue(WetSheetMath.origin(-100.0, g.w, 2.0) < -700f, "negative paper too")
        val oy = WetSheetMath.origin(5000.0, g.h, 4.0)
        assertTrue(WetSheetMath.covers(o, oy, g, 4.0, 5000.0, 5000.0, 200.0))
        assertFalse(WetSheetMath.covers(o, oy, g, 4.0, o + 100.0, 5000.0, 200.0), "inside its margin is not covered")
        assertFalse(WetSheetMath.covers(o, oy, g, 4.0, o + g.w * 4.0 + 1, 5000.0, 0.0))
        assertTrue(WetSheetMath.covers(o, oy, g, 4.0, 5000.0, oy + g.h * 4.0 - 1, 0.0), "taller than it is wide")
    }

    @Test fun aSheetLaidZoomedOutIsTooCoarseOnceZoomedWellIn() {
        assertFalse(WetSheetMath.tooCoarse(had = 5.625, wanted = 5.625))
        assertFalse(WetSheetMath.tooCoarse(had = 5.625, wanted = 4.0), "a little closer: the wash stays wet")
        assertTrue(WetSheetMath.tooCoarse(had = 5.625, wanted = 1.75), "from 31 % to 100 %: a cell would be 5 screen px")
        assertFalse(WetSheetMath.tooCoarse(had = 1.75, wanted = 5.625), "zoomed out: finer than needed is no harm")
    }

    @Test fun theTimeStepSharesTheFramesRealTimeBetweenItsSteps() {
        val base = WetConstants()
        // A 33 ms frame of two steps on 2 px cells: 1/60 s each, the water keeps the clock's pace.
        val c = WetSheetMath.constants(base, 33.3f, 2, 2.0)
        assertEquals(1.0 / 60, c.dt, 1e-4); assertEquals(2, c.substeps)
        // Quick frames with many steps never go under the shipped step; a stalled frame never over the stable one.
        assertEquals(base.dt, WetSheetMath.constants(base, 16.7f, 8, 2.0).dt)
        assertEquals(1.0 / 30, WetSheetMath.constants(base, 400f, 1, 4.0).dt, 1e-9)
        assertEquals(0.032, WetSheetMath.constants(base, 400f, 1, 2.0).dt, 1e-9, "2 px cells: as long as the spreading stays stable")
        assertEquals(0.0245, WetSheetMath.constants(base, 400f, 1, 1.75).dt, 1e-9)
        // One step in a 27 ms frame on the Note 9's cells: the water keeps the clock (its drips run under their limit).
        assertEquals(0.0245, WetSheetMath.constants(base, 27f, 1, 1.75).dt, 1e-9)
        assertEquals(0.0185, WetSheetMath.constants(base, 37f, 2, 1.75).dt, 1e-9)
        // The step running water needs to reach its limit: a sixtieth on 2 px cells, half that at twice the speed.
        assertEquals(1.0 / 60, WetSheetMath.runDt(2.0), 1e-9); assertEquals(1.75 / 120, WetSheetMath.runDt(1.75), 1e-9)
        assertEquals(1.0 / 60, WetSheetMath.runDt(4.0, speed = 2.0), 1e-9); assertEquals(1.0 / 30, WetSheetMath.runDt(8.0), 1e-9)
        assertEquals(2.7, WetSheetMath.runCapMmPerS(), 1e-9, "what a full-resolution cell allows at the shipped step")
        assertEquals(5.4, WetSheetMath.runCapMmPerS(2.0), 1e-9)
        // Full-resolution cells cannot take a longer step at all (it broke into speckle on the Note 9).
        assertEquals(base, WetSheetMath.constants(base, 100f, 1, 1.0).copy(substeps = base.substeps))
    }

    @Test fun aFrameAsksForTheStepsThatKeepTheClocksPace() {
        assertEquals(2, WetSheetMath.stepsWanted(33.3f, 2.0), "30 frames a second on 2 px cells: two sixtieths")
        assertEquals(1, WetSheetMath.stepsWanted(16.7f, 2.0))
        assertEquals(2, WetSheetMath.stepsWanted(33.3f, 1.75), "the Note 9's cells: two, at seven eighths of the clock")
        assertEquals(3, WetSheetMath.stepsWanted(33.3f, 1.25), "finer cells: shorter steps, one more")
        assertEquals(4, WetSheetMath.stepsWanted(33.3f, 1.0))
        assertEquals(1, WetSheetMath.stepsWanted(0f, 2.0))
        assertEquals(4, WetSheetMath.stepsWanted(33.3f, 2.0, speed = 2.0))
    }

    @Test fun aLongerStepLeavesTheSteadyFlowAndTheViscosityPerSecondAsShipped() {
        val base = WetConstants()
        val c = WetSheetMath.constants(base, 33.3f, 1, 4.0)
        // The steady flow per unit head: dt * flow / (1 - keep).
        assertEquals(base.dt * base.flow / (1 - base.keep), c.dt * c.flow / (1 - c.keep), 1e-9)
        // What is kept over a thirtieth of a second: four shipped steps' worth.
        assertEquals(Math.pow(base.keep, 4.0), c.keep, 1e-3)
        assertTrue(c.keep < base.keep && c.flow < base.flow * 4)
    }

    @Test fun thePaceRunsWhatIsWantedAndBacksOffAfterASlowFrame() {
        val p = WetSheetMath.Pace(most = 6)
        assertEquals(2, p.next(33f, 2), "what the clock wants")
        assertEquals(6, p.next(33f, 9), "never more than the most")
        assertEquals(6, p.next(50f, 9), "a frame of 50 ms is still worth its steps")
        assertEquals(5, p.next(60f, 9), "too slow to watch: one fewer than it ran")
        repeat(30) { p.next(33.3f, 9) }
        assertEquals(5, p.steps, "and no higher again at once")
        repeat(40) { p.next(33.3f, 9) }
        assertEquals(6, p.steps, "after two calm seconds it tries one more")
        assertEquals(1, p.next(33f, 2).also { repeat(20) { p.next(60f, 2) } }.let { p.steps }, "never under one")
        p.reset()
        assertEquals(3, p.next(33f, 3))
    }

    @Test fun printStepsAreMarkedApartFromStrokeSteps() {
        assertFalse(WetSheetMath.isPrintMark(WetSheetMath.strokeMark(7)))
        assertTrue(WetSheetMath.isPrintMark(WetSheetMath.printMark(7)))
        assertTrue(WetSheetMath.strokeMark(8) > WetSheetMath.printMark(7), "later marks are larger")
    }
}

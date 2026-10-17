package cc.joycreator.joybrush.core.select

import cc.joycreator.joybrush.core.brush.PeriodicTiles
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.shape.Pt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The fill pen on an armed Tile board: the drawn shape lands on the repeat, folded back into the board rectangle. */
class SelectionMaskFoldTest {

    /** The reference fold, pixel by pixel: the strongest coverage among every period copy of [p]. */
    private fun reference(mask: SelectionMask, period: RectPx, x: Int, y: Int): Int {
        val box = mask.bounds() ?: return 0
        var best = 0
        var sy = y - ((y - box.y).floorDiv(period.h) + 1) * period.h
        while (sy < box.y + box.h) {
            var sx = x - ((x - box.x).floorDiv(period.w) + 1) * period.w
            while (sx < box.x + box.w) { best = maxOf(best, mask.coverage(sx, sy)); sx += period.w }
            sy += period.h
        }
        return best
    }

    private fun check(mask: SelectionMask, period: RectPx) {
        val folded = mask.foldInto(period)
        val fb = folded.bounds()
        if (fb != null) assertTrue(fb.x >= period.x && fb.y >= period.y && fb.x + fb.w <= period.x + period.w &&
            fb.y + fb.h <= period.y + period.h, "folded coverage leaves the board: $fb vs $period")
        for (y in period.y until period.y + period.h) for (x in period.x until period.x + period.w) {
            assertEquals(reference(mask, period, x, y), folded.coverage(x, y), "pixel ($x,$y) period=$period")
        }
    }

    @Test
    fun aShapeAcrossTheSeamFillsBothSidesOfTheBoard() {
        val period = RectPx(100, 50, 200, 120)
        // An antialiased triangle hanging off the right and bottom edges of the board.
        val tri = SelectionMask.polygon(listOf(Pt(250.3, 120.7), Pt(345.9, 140.2), Pt(260.1, 205.5)))
        check(tri, period)
        val folded = tri.foldInto(period)
        // Document pixel (302, 171) is inside the triangle past the board's bottom-right corner; it lands at (102, 51).
        assertEquals(255, tri.coverage(302, 171))
        assertEquals(255, folded.coverage(period.x + 2, period.y + 1), "the part past the bottom-right corner lands top-left")
    }

    @Test
    fun aShapeLargerThanTheTileFillsEveryPixelOnceNotTwice() {
        val period = RectPx(-7, -9, 23, 31)
        val big = SelectionMask.polygon(listOf(Pt(-60.5, -40.25), Pt(80.75, -35.5), Pt(70.0, 90.0), Pt(-50.0, 75.5)))
        check(big, period)
        val folded = big.foldInto(period)
        for (y in period.y until period.y + period.h) for (x in period.x until period.x + period.w) assertEquals(255, folded.coverage(x, y))
    }

    @Test
    fun aShapeAlreadyInsideTheBoardIsReturnedUnchanged() {
        val inside = SelectionMask.polygon(listOf(Pt(110.0, 60.0), Pt(150.0, 60.0), Pt(130.0, 100.0)))
        assertSame(inside, inside.foldInto(RectPx(100, 50, 200, 120)))
        assertSame(SelectionMask.EMPTY, SelectionMask.EMPTY.foldInto(RectPx(0, 0, 10, 10)))
    }

    @Test
    fun negativeCoordinatesAndTileBordersFoldExactly() {
        val period = RectPx(-300, 200, 260, 70)   // crosses the x = -256 tile border; not tile aligned
        check(SelectionMask.ellipse(-30.0, 190.0, 80.0, 25.0, 0.4), period)
        check(SelectionMask.rect(RectPx(-320, 260, 40, 30)), period)
        assertEquals(-300, PeriodicTiles.wrap(-40, -300, 260))
    }

    @Test
    fun anEmptyPeriodIsRefused() {
        assertFailsWith<IllegalArgumentException> { SelectionMask.rect(RectPx(0, 0, 4, 4)).foldInto(RectPx(0, 0, 0, 5)) }
    }
}

package cc.joycreator.joybrush.core.fill

import cc.joycreator.joybrush.core.brush.FillPen
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.InkRaster
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FillTraceTest {
    private val white = 0xFFFFFFFF.toInt()

    @Test fun aPixelGrowsExactlyThreeQuartersOnAllFourSides() {
        val record = trace(1, 1, byteArrayOf(1))
        assertEquals("shape", record.id)
        assertEquals("fill-brush", record.brushId)
        assertEquals(white, record.colorArgb)
        assertEquals(-.75f, record.samples.minOf { it.x })
        assertEquals(1.75f, record.samples.maxOf { it.x })
        assertEquals(-.75f, record.samples.minOf { it.y })
        assertEquals(1.75f, record.samples.maxOf { it.y })
        val image = raster(record, -1, -1, 3, 3)
        assertContentEquals(intArrayOf(143, 191, 143, 191, 255, 191, 143, 191, 143),
            IntArray(9) { image[it * 4 + 3].toInt() and 255 })
    }

    @Test fun holesKeepOppositeWindingAndShrinkByThreeQuarters() {
        val mask = ByteArray(64) { i -> if (i % 8 in 2..5 && i / 8 in 2..5) 0 else 1 }
        val record = trace(8, 8, mask)
        val image = raster(record, 0, 0, 8, 8)
        assertEquals(0, alpha(image, 8, 3, 3))
        assertEquals(191, alpha(image, 8, 2, 3)) // Hole's left boundary is x=2.75.
        assertEquals(191, alpha(image, 8, 5, 3)) // Right boundary x=5.25.
        assertEquals(191, alpha(image, 8, 3, 2))
        assertEquals(191, alpha(image, 8, 3, 5))
        assertAgainstPixelUnion(8, 8, mask)
    }

    @Test fun aNarrowHoleCollapsesWithoutTurningInsideOut() {
        val mask = ByteArray(25) { if (it == 12) 0 else 1 }
        val record = trace(5, 5, mask)
        assertEquals(255, alpha(raster(record, 0, 0, 5, 5), 5, 2, 2))
        assertAgainstPixelUnion(5, 5, mask)
    }

    @Test fun diagonalPixelsAndDisconnectedComponentsHaveNoBridgePaint() {
        val diagonal = byteArrayOf(1, 0, 0, 1)
        assertAgainstPixelUnion(2, 2, diagonal)
        val disconnected = ByteArray(9 * 7)
        disconnected[0] = 1
        disconnected[6 * 9 + 8] = 1
        disconnected[3 * 9 + 4] = 1
        val record = trace(9, 7, disconnected)
        assertEquals(0, alpha(raster(record, 0, 0, 9, 7), 9, 2, 3))
        assertAgainstPixelUnion(9, 7, disconnected)
    }

    @Test fun negativeDocumentOriginsPreserveEveryByteAndCornerOnReplay() {
        val mask = ByteArray(48) { i -> if (i % 8 in 2..4 && i / 8 in 1..3) 0 else 1 }
        val zero = trace(8, 6, mask)
        val shifted = FillTrace.trace(8, 6, mask, "shape", "fill-brush", white, -260, -513).single()
        assertContentEquals(raster(zero, -1, -1, 10, 8), raster(shifted, -261, -514, 10, 8))
        val replay = FillPen.outline(shifted.samples, shifted.smoothing, shifted.screenPerDoc)
        for (p in shifted.samples) assertTrue(replay.any { it.x == p.x.toDouble() && it.y == p.y.toDouble() },
            "quarter-pixel corners survive the fill pen's mandatory resampling")
    }

    @Test fun gapClosingUsesFloodFillAndDoesNotDoubleApplyIntegerGrowth() {
        val w = 13
        val rgba = ByteArray(w * w * 4) { 255.toByte() }
        fun black(x: Int, y: Int) { for (c in 0..2) rgba[(y * w + x) * 4 + c] = 0 }
        for (x in 2..10) { if (x != 6) black(x, 2); black(x, 10) }
        for (y in 2..10) { black(2, y); black(10, y) }
        val options = FillOptions(tolerance = 0f, gapClosePx = 1, grow = 4)
        val mask = FloodFill.fill(w, w, rgba, 6, 6, options.copy(grow = 0))
        assertEquals(0, mask[0].toInt())
        assertTrue(mask[6 * w + 6].toInt() != 0)
        val record = FillTrace.fill(w, w, rgba, 6, 6, options, "shape", "fill-brush", white).single()
        assertContentEquals(raster(trace(w, w, mask), -1, -1, 15, 15), raster(record, -1, -1, 15, 15))
        assertAgainstPixelUnion(w, w, mask)
    }

    @Test fun solidRegionHasFourCornersInsteadOfThousandsOfRowEdges() {
        val record = trace(64, 37, ByteArray(64 * 37) { 1 })
        assertEquals(5, record.samples.size) // Four corners + explicit close before possible bridges.
        assertEquals(0f, record.smoothing)
        assertEquals(4f, record.screenPerDoc)
        assertTrue(record.samples.all { it.isPlaceable && !it.predicted })
    }

    @Test fun generatedSmallMasksMatchIndependentSupersampledRectangleUnion() {
        var random = 12345
        repeat(40) {
            val w = 3 + it % 7
            val h = 2 + it % 5
            val mask = ByteArray(w * h) {
                random = random * 1664525 + 1013904223
                if ((random ushr 28) < 5) 1 else 0
            }
            if (mask.all { it.toInt() == 0 }) mask[0] = 1
            assertAgainstPixelUnion(w, h, mask)
        }
    }

    @Test fun translucentColourIsAppliedOnceAcrossMergedOverlaps() {
        val mask = ByteArray(16) { 1 }
        val record = FillTrace.trace(4, 4, mask, "shape", "fill-brush", 0x80FF0000.toInt()).single()
        val image = raster(record, 0, 0, 4, 4)
        for (i in 0 until 16) {
            assertEquals(128, image[4 * i].toInt() and 255)
            assertEquals(0, image[4 * i + 1].toInt())
            assertEquals(0, image[4 * i + 2].toInt())
            assertEquals(128, image[4 * i + 3].toInt() and 255)
        }
    }

    @Test fun emptyRegionsAndInvalidBudgetsAreExplicit() {
        assertTrue(FillTrace.trace(0, 1, byteArrayOf(), "a", "b", white).isEmpty())
        assertTrue(FillTrace.trace(3, 2, ByteArray(6), "a", "b", white).isEmpty())
        assertFailsWith<IllegalArgumentException> { FillTrace.trace(-1, 1, byteArrayOf(), "a", "b", white) }
        assertFailsWith<IllegalArgumentException> { FillTrace.trace(2, 2, byteArrayOf(1), "a", "b", white) }
        assertFailsWith<IllegalArgumentException> { FillTrace.trace(Int.MAX_VALUE, 2, byteArrayOf(), "a", "b", white) }
        assertFailsWith<IllegalArgumentException> { FillTrace.trace(16384, 1, ByteArray(16384) { 1 }, "a", "b", white) }
        assertFailsWith<IllegalArgumentException> { FillTrace.trace(1, 1, byteArrayOf(1), "a", "b", white, Int.MAX_VALUE) }
        val wide = ByteArray(20000)
        wide[10000] = 1
        assertEquals(5, FillTrace.trace(20000, 1, wide, "a", "b", white).single().samples.size)
    }

    private fun trace(w: Int, h: Int, mask: ByteArray) =
        FillTrace.trace(w, h, mask, "shape", "fill-brush", white).single()

    private fun raster(record: StrokeRecord, x: Int, y: Int, w: Int, h: Int): ByteArray =
        InkRaster.fill(FillPen.outline(record.samples, record.smoothing, record.screenPerDoc),
            record.colorArgb, x, y, w, h, 1f)!!

    private fun alpha(bytes: ByteArray, w: Int, x: Int, y: Int) = bytes[(y * w + x) * 4 + 3].toInt() and 255

    /** Independent oracle: test all original pixel rectangles at all 16 Lasso sample positions.
     * No contour, sweep, winding, bridge, or production SelectionMask calculation is used here.
     */
    private fun assertAgainstPixelUnion(w: Int, h: Int, mask: ByteArray) {
        val record = trace(w, h, mask)
        val bytes = raster(record, -1, -1, w + 2, h + 2)
        for (y in -1..h) for (x in -1..w) {
            var hits = 0
            for (sy in 0..3) for (sx in 0..3) {
                val xx = x + (sx + .5) / 4
                val yy = y + (sy + .5) / 4
                var inside = false
                for (i in mask.indices) {
                    if (mask[i].toInt() == 0) continue
                    val px = i % w
                    val py = i / w
                    if (xx >= px - .75 && xx < px + 1.75 && yy >= py - .75 && yy < py + 1.75) {
                        inside = true; break
                    }
                }
                if (inside) hits++
            }
            val expected = (hits * 255 + 8) / 16
            val at = ((y + 1) * (w + 2) + x + 1) * 4
            for (c in 0..3) assertEquals(expected, bytes[at + c].toInt() and 255,
                "rectangle-union oracle at ($x,$y), channel $c, ${w}x$h")
        }
    }
}

package cc.joycreator.joybrush.androidkit.tools

import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.doc.RegionFrame
import cc.joycreator.joybrush.core.doc.RegionPaintPlan
import cc.joycreator.joybrush.core.fill.MaskPaintMode
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.select.SelectionMask
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FillPenRasterTest {
    private val key = Tiles.key(0, 0)
    private fun square(x: Float = 0f, y: Float = 0f, size: Float = 8f) = listOf(
        PenSample(x, y, 0.0), PenSample(x + size, y, 10.0),
        PenSample(x + size, y + size, 20.0), PenSample(x, y + size, 30.0),
    )
    private fun tile(r: Int, g: Int, b: Int, a: Int) = ByteArray(Tiles.SIZE * Tiles.SIZE * 4) {
        when (it % 4) { 0 -> r; 1 -> g; 2 -> b; else -> a }.toByte()
    }
    private fun pixel(bytes: ByteArray, x: Int, y: Int) =
        (0..3).map { bytes[(y * Tiles.SIZE + x) * 4 + it].toInt() and 255 }

    @Test fun closesStrokeAndUsesNonzeroRuleForTwiceDrawnLoop() {
        val once = FillPenRaster.prepare(square(), 0f, 1f)
        val twice = FillPenRaster.prepare(square() + square().map { it.copy(timeMs = it.timeMs + 40) }, 0f, 1f)
        assertEquals(255, once.coverage(3, 3))
        assertEquals(255, twice.coverage(3, 3))
        assertEquals(0, twice.coverage(9, 3))
    }

    @Test fun degenerateStrokeAndPredictedOnlyShapeAreEmpty() {
        assertTrue(FillPenRaster.prepare(square().take(2), 0f, 1f).isEmpty)
        assertTrue(FillPenRaster.prepare(square().map { it.copy(predicted = true) }, 0f, 1f).isEmpty)
        assertTrue(FillPenRaster.prepare(List(4) { PenSample(3f, 3f, it.toDouble()) }, 0f, 1f).isEmpty)
    }

    @Test fun negativeOriginsUseFloorTileCoordinates() {
        val mask = FillPenRaster.prepare(square(-4f, -4f), 0f, 1f)
        val result = FillPenRaster.plan(mask, 0xFFFF0000.toInt(), 1f, MaskPaintMode.FILL) { null }
        assertEquals(setOf(Tiles.key(-1, -1), Tiles.key(0, -1), Tiles.key(-1, 0), key), result.keys)
        assertEquals(listOf(255, 0, 0, 255), pixel(assertNotNull(result[Tiles.key(-1, -1)]), 255, 255))
    }

    @Test fun opacityProducesPremultipliedSourceOverAndDoesNotMutateReadback() {
        val before = tile(0, 0, 128, 128)
        val saved = before.copyOf()
        val mask = SelectionMask.rect(RectPx(1, 1, 2, 2))
        val result = assertNotNull(FillPenRaster.plan(mask, 0x00FF0000, .5f, MaskPaintMode.FILL) { before }[key])
        assertEquals(listOf(128, 0, 64, 192), pixel(result, 1, 1))
        assertEquals(listOf(0, 0, 128, 128), pixel(result, 8, 8))
        assertContentEquals(saved, before)
        assertFalse(result === before)
    }

    @Test fun behindRetainsOpaqueLineArtAndOnlyPaintsTransparentArea() {
        val before = tile(0, 0, 0, 0)
        before[0] = 255.toByte(); before[3] = 255.toByte()
        val result = assertNotNull(FillPenRaster.plan(
            SelectionMask.rect(RectPx(0, 0, 2, 1)), 0xFF0000FF.toInt(), 1f, MaskPaintMode.BEHIND,
        ) { before }[key])
        assertEquals(listOf(255, 0, 0, 255), pixel(result, 0, 0))
        assertEquals(listOf(0, 0, 255, 255), pixel(result, 1, 0))
    }

    @Test fun eraseScalesAlphaAndDeletesOnlyAnEntirelyEmptyTile() {
        val before = tile(0, 0, 0, 0)
        before[0] = 128.toByte(); before[3] = 128.toByte()
        val mask = SelectionMask.rect(RectPx(0, 0, 1, 1))
        val half = assertNotNull(FillPenRaster.plan(mask, -1, .5f, MaskPaintMode.ERASE) { before }[key])
        assertEquals(listOf(64, 0, 0, 64), pixel(half, 0, 0))
        val erased = FillPenRaster.plan(mask, -1, 1f, MaskPaintMode.ERASE) { before }
        assertTrue(key in erased)
        assertNull(erased[key])
    }

    @Test fun noOpsReturnNoHistoryWorkAndZeroOpacityDoesNotReadTiles() {
        val mask = SelectionMask.rect(RectPx(0, 0, 2, 2))
        assertTrue(FillPenRaster.plan(mask, -1, 0f, MaskPaintMode.FILL) { error("Must not read") }.isEmpty())
        assertTrue(FillPenRaster.plan(mask, -1, 1f, MaskPaintMode.ERASE) { null }.isEmpty())
        assertTrue(FillPenRaster.plan(mask, -1, 1f, MaskPaintMode.BEHIND) { tile(40, 50, 60, 255) }.isEmpty())
    }

    @Test fun refusesHugeCoordinatesAreaAndTileCountBeforeRasterisation() {
        assertFailsWith<IllegalArgumentException> { FillPenRaster.prepare(square(1e20f, 1e20f, 1e20f), 0f, 1f) }
        assertFailsWith<IllegalArgumentException> { FillPenRaster.prepare(square(size = 3000f), 0f, 1f) }
        // Area fits, but this offset requires 9 by 9 tile allocations instead of 8 by 8.
        assertFailsWith<IllegalArgumentException> { FillPenRaster.prepare(square(1f, 1f, 2048f), 0f, 1f) }
    }

    @Test fun refusesSmoothingExpansionBeforeAnExtremeZoomAllocatesPoints() {
        assertFailsWith<IllegalArgumentException> { FillPenRaster.prepare(square(), .5f, 1e20f) }
        assertEquals(255, FillPenRaster.prepare(square(), Float.NaN, Float.NaN).coverage(3, 3))
    }

    @Test fun maskBudgetIsCheckedBeforeAnyReadback() {
        val mask = SelectionMask.rect(RectPx(-1, 0, 256 * 64, 1))
        assertFailsWith<IllegalArgumentException> {
            FillPenRaster.plan(mask, -1, 1f, MaskPaintMode.FILL) { error("Must not read") }
        }
    }

    @Test fun projectedTileReplacementKeepsHiddenSubstrateAndOtherFrames() {
        val shared = tile(0, 255, 0, 255)
        val current = tile(0, 0, 255, 255)
        val otherFrame = tile(255, 255, 0, 255)
        val currentBefore = current.copyOf(); val sharedBefore = shared.copyOf(); val otherBefore = otherFrame.copyOf()
        val plan = RegionPaintPlan("shared", listOf(RegionFrame("board", RectPx(2, 2, 2, 2), "frame", "cel")))
        val projected = shared.copyOf()
        for (slice in plan.tileSlices(key)) if (slice.plane.celId == "cel")
            RegionPaintPlan.copyRgbaSlice(current, projected, slice.rect)
        val result = assertNotNull(FillPenRaster.plan(
            SelectionMask.rect(RectPx(1, 1, 4, 4)), 0xFFFF0000.toInt(), 1f, MaskPaintMode.FILL,
        ) { projected }[key])
        val stores = mapOf("shared" to shared.copyOf(), "cel" to current.copyOf())
        for (slice in plan.tileSlices(key)) RegionPaintPlan.copyRgbaSlice(result, stores.getValue(slice.plane.celId), slice.rect)
        assertEquals(listOf(0, 255, 0, 255), pixel(stores.getValue("shared"), 2, 2)) // Hidden substrate.
        assertEquals(listOf(255, 0, 0, 255), pixel(stores.getValue("cel"), 2, 2))
        assertEquals(listOf(0, 0, 255, 255), pixel(stores.getValue("cel"), 1, 1)) // Dormant outside.
        assertEquals(listOf(255, 0, 0, 255), pixel(stores.getValue("shared"), 1, 1))
        assertContentEquals(currentBefore, current); assertContentEquals(sharedBefore, shared)
        assertContentEquals(otherBefore, otherFrame)
    }

    @Test fun ownershipBudgetRefusesManyCelsInsideOneVisibleTileBeforeReadback() {
        val ownership = RegionPaintPlan("shared", List(130) { i ->
            RegionFrame("board-$i", RectPx(i, 0, 1, 1), "frame-$i", "cel-$i")
        })
        var reads = 0
        val refusal = assertFailsWith<IllegalArgumentException> {
            FillPenRaster.plan(SelectionMask.rect(RectPx(0, 0, 130, 1)), -1, 1f,
                MaskPaintMode.FILL, ownership) { reads++; null }
        }
        assertTrue(refusal.message!!.contains("animation regions"))
        assertEquals(0, reads)
    }

    @Test fun ownershipBudgetAcceptsTheLimitAndRetainsAnEmptyEraseNoOp() {
        // 127 current cels plus the shared destination is exactly the allowed 128 textures.
        val ownership = RegionPaintPlan("shared", List(127) { i ->
            RegionFrame("board-$i", RectPx(i, 0, 1, 1), "frame-$i", "cel-$i")
        })
        var reads = 0
        val changes = FillPenRaster.plan(SelectionMask.rect(RectPx(0, 0, 127, 1)), -1, 1f,
            MaskPaintMode.ERASE, ownership) { reads++; null }
        assertEquals(1, reads)
        assertTrue(changes.isEmpty())
    }
}

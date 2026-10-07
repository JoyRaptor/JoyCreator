package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.test.*

class RegionTransferTilesTest {
    @Test fun shiftedNegativeCellsCoverEachPixelOnceAcrossBothTileGrids() {
        val source = RectPx(-270, -9, 530, 280)
        val destination = RectPx(17, 245, 530, 280)
        val written = HashSet<Pair<Int, Int>>()
        for (slice in RegionTransferTiles.slices(source, destination)) {
            assertEquals(slice.source.w, slice.destination.w)
            assertEquals(slice.source.h, slice.destination.h)
            for (y in 0 until slice.source.h) for (x in 0 until slice.source.w) {
                val sx = Tiles.tx(slice.sourceKey) * Tiles.SIZE + slice.source.x + x
                val sy = Tiles.ty(slice.sourceKey) * Tiles.SIZE + slice.source.y + y
                val dx = Tiles.tx(slice.destinationKey) * Tiles.SIZE + slice.destination.x + x
                val dy = Tiles.ty(slice.destinationKey) * Tiles.SIZE + slice.destination.y + y
                assertEquals(destination.x - source.x, dx - sx)
                assertEquals(destination.y - source.y, dy - sy)
                assertTrue(sx in source.x until source.x + source.w)
                assertTrue(sy in source.y until source.y + source.h)
                assertTrue(written.add(dx to dy), "A destination pixel was covered twice")
            }
        }
        assertEquals(source.w * source.h, written.size)
        assertTrue((destination.x to destination.y) in written)
        assertTrue((destination.x + destination.w - 1 to destination.y + destination.h - 1) in written)
    }

    @Test fun reciprocalUnevenCellCopiesRetainBlankPixelsAndOuterNeighbors() {
        val a = RectPx(250, 3, 20, 5)
        val b = RectPx(270, 3, 20, 5)
        val before = mutableMapOf<Pair<Int, Int>, Int>()
        for (y in 2..8) for (x in 249..290) before[x to y] = when {
            x < a.x || x >= b.x + b.w || y < a.y || y >= a.y + a.h -> 77
            x in a.x until a.x + a.w -> x - a.x + 1
            else -> 0
        }
        val after = before.toMutableMap()
        // Model exact tile blits from the immutable transaction input, including zero pixels.
        for ((source, destination) in listOf(a to b, b to a)) {
            for (slice in RegionTransferTiles.slices(source, destination)) {
                for (y in 0 until slice.source.h) for (x in 0 until slice.source.w) {
                    val sx = Tiles.tx(slice.sourceKey) * Tiles.SIZE + slice.source.x + x
                    val sy = Tiles.ty(slice.sourceKey) * Tiles.SIZE + slice.source.y + y
                    val dx = Tiles.tx(slice.destinationKey) * Tiles.SIZE + slice.destination.x + x
                    val dy = Tiles.ty(slice.destinationKey) * Tiles.SIZE + slice.destination.y + y
                    after[dx to dy] = before.getValue(sx to sy)
                }
            }
        }
        for (y in 3..7) for (x in 0..19) {
            assertEquals(0, after[a.x + x to y])
            assertEquals(x + 1, after[b.x + x to y])
        }
        assertEquals(77, after[249 to 3]); assertEquals(77, after[290 to 3])
        assertEquals(77, after[260 to 2]); assertEquals(77, after[280 to 8])
    }

    @Test fun rejectsScalingZeroAreaAndOverflowBeforeYieldingAChunk() {
        assertFailsWith<IllegalArgumentException> {
            RegionTransferTiles.slices(RectPx(0, 0, 2, 2), RectPx(0, 0, 3, 2)).toList()
        }
        assertFailsWith<IllegalArgumentException> {
            RegionTransferTiles.slices(RectPx(0, 0, 0, 2), RectPx(1, 1, 0, 2)).toList()
        }
        assertFailsWith<IllegalArgumentException> {
            RegionTransferTiles.slices(RectPx(Int.MAX_VALUE, 0, 2, 2), RectPx(0, 0, 2, 2)).toList()
        }
    }

    private fun preparedEngine(): Pair<GlPaintEngine, JbDocument> {
        var serial = 0
        val doc = DocOps.newDocument("transfer", "Test", 100, 100) { "transfer-${serial++}" }
        val engine = GlPaintEngine().apply {
            initWith { } // No actual GL calls: these refusals must precede texture allocation.
            addLayer(doc.layers.single().id)
            setBoardDocument(doc)
        }
        return engine to doc
    }

    @Test fun oversizedTransferRefusesBeforeMutatingDocumentOrHistory() {
        val (engine, doc) = preparedEngine()
        val layer = doc.layers.single(); val cel = layer.cels.single().id
        val transfer = RegionTransfer(layer.id, cel, cel,
            RectPx(0, 0, 3072, 3072), RectPx(3072, 0, 3072, 3072))
        val error = assertFailsWith<IllegalArgumentException> {
            engine.applyBoardChange(RegionChange(doc, transfers = listOf(transfer)))
        }
        assertTrue(error.message.orEmpty().contains("too large"))
        assertEquals(doc, engine.boardDocument)
        assertEquals(0, engine.undo.undoDepth)
        assertEquals(0, engine.heldTextureNames())
    }

    @Test fun invalidCelRefusesBeforeMutatingDocumentOrHistory() {
        val (engine, doc) = preparedEngine()
        val layer = doc.layers.single(); val cel = layer.cels.single().id
        assertFailsWith<IllegalStateException> {
            engine.applyBoardChange(RegionChange(doc, transfers = listOf(RegionTransfer(
                layer.id, "missing", cel, RectPx(0, 0, 20, 20), RectPx(20, 0, 20, 20)))))
        }
        assertEquals(doc, engine.boardDocument)
        assertEquals(0, engine.undo.undoDepth)
        assertEquals(0, engine.heldTextureNames())
    }
}

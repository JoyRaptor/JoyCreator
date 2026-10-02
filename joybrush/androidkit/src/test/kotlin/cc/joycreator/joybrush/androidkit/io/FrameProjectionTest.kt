package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.androidkit.gl.GlPaintEngine
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.layers.LayerStack
import cc.joycreator.joybrush.core.layers.LayerState
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.paint.UndoLog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

class FrameProjectionTest {
    private val key = Tiles.key(0, 0)
    private fun animated(): JbContents {
        val doc = JbDocument(id = "animation", name = "Frames", boards = listOf(
            Board("board", "Animation", BoardKind.ANIMATION, RectPx(0, 0, 64, 64),
                frames = listOf(Frame("f1", 2), Frame("f2"), Frame("linked")))),
            activeBoardId = "board", activeLayerId = "paint", layers = listOf(
                Layer("paint", "Animated paint", LayerKind.PAINT,
                    cels = listOf(Cel("a", listOf("0_0")), Cel("b", listOf("0_0"))),
                    animatedIn = "board", frameCel = mapOf("f1" to "a", "f2" to "b", "linked" to "a"))))
        fun pixels(red: Boolean) = ByteArray(TILE_BYTES).also { bytes ->
            for (i in 0 until 256 * 256) { bytes[i * 4 + if (red) 0 else 2] = 255.toByte(); bytes[i * 4 + 3] = 255.toByte() }
        }
        return JbContents(doc, mapOf(Triple("paint", "a", "0_0") to pixels(true),
            Triple("paint", "b", "0_0") to pixels(false)), emptyMap())
    }
    private fun fresh(): JbContents {
        val doc = animated().doc
        return JbContents(doc.copy(boards = listOf(doc.boards.single().copy(kind = BoardKind.CANVAS, frames = emptyList())),
            layers = listOf(doc.layers.single().copy(animatedIn = null, frameCel = emptyMap(), cels = listOf(Cel("live"))))),
            emptyMap(), emptyMap())
    }
    private fun engine(): GlPaintEngine = GlPaintEngine().also {
        it.initWith { }
        it.setStack(LayerStack(listOf(LayerState("paint", "Paint")), "paint"))
    }
    private fun plant(engine: GlPaintEngine, store: String, texture: Int) {
        engine.undo.push(UndoLog.Step(listOf(UndoLog.TileChange(store, key, texture, null))))
        assertTrue(engine.undoStep())
        engine.undo.clear()
    }
    @Test fun snapshotRoundTripKeepsEveryCelAndLinkedFrame() {
        val original = animated()
        val result = CanvasSnapshot.merge(CanvasSnapshot.metadataOf(original), fresh(), original.tiles)
        val out = ByteArrayOutputStream(); JbArchive.write(out, result)
        val read = JbArchive.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(original.doc, read.doc)
        original.tiles.forEach { (address, bytes) -> assertContentEquals(bytes, read.tiles.getValue(address)) }
    }
    @Test fun metadataRetainsGraphWithoutRetainingPixelArrays() {
        val original = animated(); val metadata = CanvasSnapshot.metadataOf(original)
        assertTrue(metadata.tiles.isEmpty())
        assertEquals(original.doc, metadata.doc)
        val result = CanvasSnapshot.merge(metadata, fresh(), original.tiles)
        original.tiles.forEach { (address, bytes) -> assertSame(bytes, result.tiles[address]) }
    }
    @Test fun animatedSnapshotWithoutAllCelReadbackIsRefused() {
        assertFailsWith<JbArchiveException> { CanvasSnapshot.merge(CanvasSnapshot.metadataOf(animated()), fresh()) }
    }
    @Test fun foreignCelAndTruncatedPayloadAreRefused() {
        val template = CanvasSnapshot.metadataOf(animated())
        assertFailsWith<JbArchiveException> { CanvasSnapshot.merge(template, fresh(), mapOf(Triple("paint", "foreign", "0_0") to ByteArray(TILE_BYTES))) }
        assertFailsWith<JbArchiveException> { CanvasSnapshot.merge(template, fresh(), mapOf(Triple("paint", "a", "0_0") to byteArrayOf(1))) }
    }
    @Test fun pngUsesSelectedCelAndLinkedFramesSharePixels() {
        val contents = animated()
        fun png(frame: String) = CanvasPng.encode(contents, false, frame)
        assertEquals(0xFFFF0000.toInt(), ImageIO.read(ByteArrayInputStream(png("f1"))).getRGB(10, 10))
        assertEquals(0xFF0000FF.toInt(), ImageIO.read(ByteArrayInputStream(png("f2"))).getRGB(10, 10))
        assertContentEquals(png("f1"), png("linked"))
        assertFailsWith<JbArchiveException> { CanvasPng.encode(contents, false) }
        assertFailsWith<JbArchiveException> { CanvasPng.encode(contents, false, "missing") }
    }
    @Test fun storeAddressesCannotCollideBetweenLayerCelPairs() {
        val first = animated().doc.layers.single()
        assertEquals("paint", CelProjection.storeId(first, "a"))
        val other = first.copy(id = "paintb", cels = listOf(Cel("first"), Cel("")))
        assertNotEquals(CelProjection.storeId(first, "b"), CelProjection.storeId(other, ""))
    }
    @Test fun frameSwitchDoesNotResetPixelsOrHistory() {
        val engine = engine(); val doc = animated().doc; val layer = doc.layers.single()
        plant(engine, "paint", 11)
        engine.projectCels(doc, "f1")
        val second = CelProjection.storeId(layer, "b")
        plant(engine, second, 22)
        engine.undo.push(UndoLog.Step(emptyList(), documentBefore = doc, documentAfter = doc))
        val depth = engine.undo.undoDepth
        repeat(6) { engine.projectCels(doc, if (it % 2 == 0) "f2" else "f1") }
        assertEquals(depth, engine.undo.undoDepth)
        assertEquals(listOf(key), engine.celTileKeys("paint"))
        assertEquals(listOf(key), engine.celTileKeys(second))
        assertEquals(listOf("paint"), engine.layerIds())
        assertEquals(2, engine.paintTileCount())
        assertEquals(2, engine.heldTextureNames())
    }
    @Test fun undoOnFirstCelCannotEraseSecondCelWhileItIsDisplayed() {
        val engine = engine(); val doc = animated().doc; val layer = doc.layers.single()
        engine.projectCels(doc, "f1")
        plant(engine, CelProjection.storeId(layer, "b"), 22)
        engine.projectCels(doc, "f2")
        engine.undo.push(UndoLog.Step(listOf(UndoLog.TileChange<Int>("paint", key, 11, null))))
        assertTrue(engine.undoStep())
        assertEquals(listOf(key), engine.celTileKeys("paint"))
        assertEquals(listOf(key), engine.celTileKeys(CelProjection.storeId(layer, "b")))
        assertTrue(engine.redoStep())
        assertTrue(engine.celTileKeys("paint").isEmpty())
        assertEquals(listOf(key), engine.celTileKeys(CelProjection.storeId(layer, "b")))
    }
    @Test fun staticAnimationUndoTransfersOwnershipWithoutDuplicatingTextures() {
        val engine = engine(); val animated = animated().doc
        val static = fresh().doc
        plant(engine, "paint", 11)
        repeat(4) { engine.projectCels(animated, "f1"); engine.projectCels(static, null) }
        assertEquals(1, engine.heldTextureNames())
        assertEquals(listOf(key), engine.tileKeys("paint"))
    }
    @Test fun contextLossForgetsOffscreenCelsAndUndoMetadata() {
        val engine = engine(); val doc = animated().doc
        engine.projectCels(doc, "f1")
        plant(engine, "paint", 11)
        plant(engine, CelProjection.storeId(doc.layers.single(), "b"), 22)
        engine.undo.push(UndoLog.Step(emptyList(), documentBefore = fresh().doc, documentAfter = doc))
        engine.initWith { }
        assertTrue(engine.lostContent)
        assertEquals(0, engine.heldTextureNames())
        assertEquals(0, engine.paintTileCount())
        assertFalse(engine.undo.canUndo)
    }
    @Test fun documentHistoryReturnsBeforeAndAfterGraphs() {
        val engine = engine(); val before = fresh().doc; val after = animated().doc
        var restored: JbDocument? = null
        engine.onUndoRedo = { step, redo -> restored = if (redo) step.documentAfter else step.documentBefore }
        engine.undo.push(UndoLog.Step(emptyList(), documentBefore = before, documentAfter = after))
        engine.undoStep(); assertEquals(before, restored)
        engine.redoStep(); assertEquals(after, restored)
    }

    @Test fun discardedRedoReleasesItsUnusedCelStores() {
        val engine = engine(); val doc = animated().doc; val static = fresh().doc
        engine.projectCels(doc, "f1")
        plant(engine, "paint", 11)
        plant(engine, CelProjection.storeId(doc.layers.single(), "b"), 22)
        engine.undo.push(UndoLog.Step(emptyList(), documentBefore = static, documentAfter = doc))
        engine.undoStep()
        engine.projectCels(static, null)
        engine.pruneCels(static)
        assertEquals(2, engine.paintTileCount(), "redo still needs the second cel")
        engine.undo.push(UndoLog.Step(emptyList())) // a new edit discards redo
        engine.pruneCels(static)
        assertEquals(1, engine.paintTileCount(), "the abandoned cel must stop consuming the document budget")
        assertEquals(2, engine.heldTextureNames(), "the released texture is in the reuse pool, not released twice")
    }

    @Test fun documentResetRecyclesEveryCelExactlyOnce() {
        val engine = engine(); val doc = animated().doc
        engine.projectCels(doc, "f2")
        plant(engine, "paint", 11)
        plant(engine, CelProjection.storeId(doc.layers.single(), "b"), 22)
        engine.resetDocument()
        assertEquals(0, engine.paintTileCount())
        assertEquals(2, engine.heldTextureNames())
        engine.initWith { }
        assertEquals(0, engine.heldTextureNames())
    }

    @Test fun mergedHistoryKeepsTheFirstAndLastDocumentStates() {
        val engine = engine(); val before = fresh().doc; val middle = animated().doc
        val after = AnimOps.setHold(middle, "board", "f1", 4)
        var restored: JbDocument? = null
        engine.onUndoRedo = { step, redo -> restored = if (redo) step.documentAfter else step.documentBefore }
        engine.undo.push(UndoLog.Step(emptyList(), documentBefore = before, documentAfter = middle))
        engine.undo.push(UndoLog.Step(emptyList(), documentBefore = middle, documentAfter = after))
        engine.undo.mergeNewest(2)
        engine.undoStep(); assertEquals(before, restored)
        engine.redoStep(); assertEquals(after, restored)
    }

    @Test fun sharedMaskKeepsItsIdentityAlongsideEveryAnimationCel() {
        val original = animated()
        val maskBytes = ByteArray(TILE_BYTES)
        val owner = original.doc.layers.single().copy(mask = Cel("matte", listOf("0_0")))
        val retained = CanvasSnapshot.metadataOf(original.copy(doc = original.doc.copy(layers = listOf(owner))))
        val live = fresh().let { it.copy(doc = it.doc.copy(layers = listOf(it.doc.layers.single().copy(
            mask = Cel("live-mask", listOf("0_0"))))), tiles = mapOf(Triple("paint", "live-mask", "0_0") to maskBytes)) }
        val result = CanvasSnapshot.merge(retained, live, original.tiles)
        assertEquals("matte", result.doc.layers.single().mask!!.id)
        assertSame(maskBytes, result.tiles[Triple("paint", "matte", "0_0")])
        assertEquals(3, result.tiles.size)
        val bytes = CanvasPng.encode(result, false, "f2")
        assertEquals(0, ImageIO.read(ByteArrayInputStream(bytes)).getRGB(10, 10))
    }

    @Test fun largeExistingDrawingCanAddBlankOrLinkedFramesButCannotDoubleItsPixels() {
        val heap = 512L * 1024 * 1024
        assertTrue(CelProjection.canAddTiles(1833, 0, heap))
        assertFalse(CelProjection.canAddTiles(1833, 1833, heap))
        assertTrue(CelProjection.canAddTiles(4, 4, heap))
    }
}

package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CanvasSnapshotTest {
    private val paint = ByteArray(TILE_BYTES) { 17 }
    private val mask = ByteArray(TILE_BYTES) { 93 }

    private fun retained(): JbContents {
        val doc = JbDocument(
            id = "original-document", name = "Original name",
            paper = Paper("#123456", textureScale = 3.5f, includeInExport = true),
            boards = listOf(Board(
                "original-board", "Board title", BoardKind.CANVAS, RectPx(-300, 100, 800, 600),
                clipToBoard = true, fps = 27f, frames = listOf(Frame("unused-frame", 4)),
                grid = SpriteGrid(2, 3, 20, 30),
            )),
            layers = listOf(Layer(
                "paint", "Old layer", LayerKind.PAINT, locked = true,
                cels = listOf(Cel("original-cel", listOf("-1_2"))),
                mask = Cel("original-mask", listOf("-1_2")),
            )),
            activeLayerId = "paint", activeBoardId = null,
        )
        return JbContents(doc, mapOf(
            Triple("paint", "original-cel", "-1_2") to paint,
            Triple("paint", "original-mask", "-1_2") to mask,
        ), emptyMap(), byteArrayOf(1, 2, 3))
    }

    private fun fresh(): JbContents {
        val doc = JbDocument(
            id = "renderer-document", name = "Live name", paper = Paper("#ABCDEF"),
            boards = listOf(Board("board-1", "Board 1", BoardKind.CANVAS, RectPx(0, 0, 400, 500))),
            layers = listOf(Layer(
                "paint", "Renamed layer", LayerKind.PAINT, visible = false, opacity = 0.4f,
                blend = BlendMode.MULTIPLY,
                cels = listOf(Cel("cel-1", listOf("2_-3"))),
                mask = Cel("mask-1", listOf("0_0")),
            )),
            activeLayerId = "paint", activeBoardId = "board-1",
        )
        return JbContents(doc, mapOf(
            Triple("paint", "cel-1", "2_-3") to paint,
            Triple("paint", "mask-1", "0_0") to mask,
        ), emptyMap())
    }

    @Test fun noRetainedDocumentReturnsTheFreshObject() {
        val fresh = fresh()
        assertSame(fresh, CanvasSnapshot.merge(null, fresh))
    }

    @Test fun fullBoardGeometryAndMetadataSurviveWithNullActiveBoard() {
        val original = retained()
        val merged = CanvasSnapshot.merge(original, fresh())
        assertEquals(original.doc.boards, merged.doc.boards)
        assertEquals(RectPx(-300, 100, 800, 600), merged.doc.boards.single().rect)
        assertNull(merged.doc.activeBoardId)
        assertEquals("original-document", merged.doc.id)
        assertTrue(DocOps.validate(merged.doc).isEmpty())
    }

    @Test fun selectedOriginalBoardIdSurvives() {
        val original = retained().let { it.copy(doc = it.doc.copy(activeBoardId = "original-board")) }
        assertEquals("original-board", CanvasSnapshot.merge(original, fresh()).doc.activeBoardId)
    }

    @Test fun paperOptionsAndThumbnailSurviveWhileLiveColorAndNameWin() {
        val original = retained()
        val merged = CanvasSnapshot.merge(original, fresh())
        assertEquals(original.doc.paper.copy(color = "#ABCDEF"), merged.doc.paper)
        assertContentEquals(byteArrayOf(1, 2, 3), merged.thumbnailPng)
        assertEquals("Live name", merged.doc.name)
        assertEquals(DOC_VERSION, merged.doc.version)
    }

    @Test fun arbitraryCelAndMaskIdsMoveTogetherWithTheirCurrentPayloads() {
        val merged = CanvasSnapshot.merge(retained(), fresh())
        val layer = merged.doc.layers.single()
        assertEquals(Cel("original-cel", listOf("2_-3")), layer.cels.single())
        assertEquals(Cel("original-mask", listOf("0_0")), layer.mask)
        assertEquals(setOf(
            Triple("paint", "original-cel", "2_-3"), Triple("paint", "original-mask", "0_0"),
        ), merged.tiles.keys)
        assertContentEquals(paint, merged.tiles.getValue(Triple("paint", "original-cel", "2_-3")))
        assertContentEquals(mask, merged.tiles.getValue(Triple("paint", "original-mask", "0_0")))
    }

    @Test fun liveStackSettingsWinAndOriginalLockSurvives() {
        val layer = CanvasSnapshot.merge(retained(), fresh()).doc.layers.single()
        assertEquals("Renamed layer", layer.name)
        assertFalse(layer.visible)
        assertEquals(0.4f, layer.opacity)
        assertEquals(BlendMode.MULTIPLY, layer.blend)
        assertTrue(layer.locked)
    }

    @Test fun additionsDeletionsOrderClippingAndActiveLayerFollowLiveStack() {
        val original = retained().let { it.copy(doc = it.doc.copy(layers = it.doc.layers + Layer(
            "deleted", "Gone", LayerKind.PAINT, cels = listOf(Cel("gone-cel")),
        ))) }
        val added = Layer("added", "New", LayerKind.PAINT, clip = true, locked = true,
            cels = listOf(Cel("new-cel", listOf("5_5"))))
        val live = fresh().let { it.copy(
            doc = it.doc.copy(layers = it.doc.layers + added, activeLayerId = added.id),
            tiles = it.tiles + (Triple("added", "new-cel", "5_5") to paint),
        ) }
        val merged = CanvasSnapshot.merge(original, live)
        assertEquals(listOf("paint", "added"), merged.doc.layers.map { it.id })
        assertEquals(added, merged.doc.layers.last())
        assertEquals("added", merged.doc.activeLayerId)
        assertContentEquals(paint, merged.tiles.getValue(Triple("added", "new-cel", "5_5")))
        assertTrue(DocOps.validate(merged.doc).isEmpty())
    }

    @Test fun removingMaskDoesNotRestoreItsOldMetadataOrPixels() {
        val live = fresh().let { it.copy(
            doc = it.doc.copy(layers = it.doc.layers.map { layer -> layer.copy(mask = null) }),
            tiles = it.tiles.filterKeys { key -> key.second == "cel-1" },
        ) }
        val merged = CanvasSnapshot.merge(retained(), live)
        assertNull(merged.doc.layers.single().mask)
        assertEquals(setOf(Triple("paint", "original-cel", "2_-3")), merged.tiles.keys)
    }

    @Test fun addingMaskCannotCollideWithAnArbitraryExistingPaintCelId() {
        val original = retained().let { it.copy(
            doc = it.doc.copy(layers = it.doc.layers.map { layer -> layer.copy(
                cels = listOf(Cel("mask-1")), mask = null,
            ) }), tiles = emptyMap(),
        ) }
        val merged = CanvasSnapshot.merge(original, fresh())
        val layer = merged.doc.layers.single()
        assertEquals("mask-1", layer.cels.single().id)
        assertEquals("mask-1-mask", layer.mask?.id)
        assertContentEquals(mask, merged.tiles.getValue(Triple("paint", "mask-1-mask", "0_0")))
        assertTrue(DocOps.validate(merged.doc).isEmpty())
    }

    @Test fun mergedDocumentAndAllPayloadsRoundTripThroughTheArchive() {
        val merged = CanvasSnapshot.merge(retained(), fresh())
        val stream = ByteArrayOutputStream()
        JbArchive.write(stream, merged)
        val reopened = JbArchive.read(ByteArrayInputStream(stream.toByteArray()))
        assertEquals(merged.doc, reopened.doc)
        assertEquals(merged.tiles.keys, reopened.tiles.keys)
        for ((key, bytes) in merged.tiles) assertContentEquals(bytes, reopened.tiles.getValue(key))
        assertContentEquals(merged.thumbnailPng, reopened.thumbnailPng)
    }

    @Test fun unsupportedBoardAndInvalidFreshDocumentAreRefused() {
        val original = retained()
        val multiBoard = original.copy(doc = original.doc.copy(boards = original.doc.boards +
            original.doc.boards.single().copy(id = "another")))
        assertFailsWith<JbArchiveException> { CanvasSnapshot.merge(multiBoard, fresh()) }
        val invalid = fresh().let { it.copy(doc = it.doc.copy(activeLayerId = "missing")) }
        assertFailsWith<JbArchiveException> { CanvasSnapshot.merge(original, invalid) }
    }

    @Test fun orphanSnapshotPayloadIsRefusedInsteadOfSilentlyDropped() {
        val live = fresh().let { it.copy(tiles = it.tiles + (Triple("missing", "cel", "0_0") to paint)) }
        assertFailsWith<JbArchiveException> { CanvasSnapshot.merge(retained(), live) }
    }
}

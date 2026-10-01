package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.*

class CanvasSnapshotMetadataTest {
    @Test fun metadataTemplateKeepsIdentityButArchiveUsesOnlyCurrentGpuPixels() {
        val oldBytes = ByteArray(TILE_BYTES) { 33 }
        val liveBytes = ByteArray(TILE_BYTES) { 77 }
        val originalDoc = JbDocument(
            id = "owner-document", name = "Owner drawing", paper = Paper("#ABCDEF"),
            boards = listOf(Board("owner-board", "Board", BoardKind.CANVAS, RectPx(-64, 32, 128, 96))),
            layers = listOf(Layer("paint", "Paint", LayerKind.PAINT,
                cels = listOf(Cel("owner-cel", listOf("-1_0"))))),
            activeLayerId = "paint", activeBoardId = "owner-board",
        )
        val original = JbContents(originalDoc, mapOf(Triple("paint", "owner-cel", "-1_0") to oldBytes), emptyMap())
        val metadata = CanvasSnapshot.metadataOf(original)
        assertTrue(metadata.tiles.isEmpty()) // no loaded pixel arrays remain reachable from the template
        assertSame(originalDoc, metadata.doc)
        val freshDoc = originalDoc.copy(
            boards = listOf(Board("runtime-board", "Board", BoardKind.CANVAS, RectPx(0, 0, 128, 96))),
            layers = listOf(originalDoc.layers.single().copy(cels = listOf(Cel("runtime-cel", listOf("0_0"))))),
            activeBoardId = "runtime-board",
        )
        val fresh = JbContents(freshDoc, mapOf(Triple("paint", "runtime-cel", "0_0") to liveBytes), emptyMap())
        val output = ByteArrayOutputStream()
        JbArchive.write(output, CanvasSnapshot.merge(metadata, fresh))
        val reopened = JbArchive.read(ByteArrayInputStream(output.toByteArray()))
        assertEquals(originalDoc.boards, reopened.doc.boards)
        assertEquals("owner-cel", reopened.doc.layers.single().cels.single().id)
        assertEquals(setOf(Triple("paint", "owner-cel", "0_0")), reopened.tiles.keys)
        assertContentEquals(liveBytes, reopened.tiles.values.single())
    }
}

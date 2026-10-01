package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class JbArchiveCompletenessTest {
    private val paintKey = Triple("paint", "cel", "0_0")
    private val maskKey = Triple("paint", "mask", "-2_3")

    private fun complete(): JbContents = JbContents(
        JbDocument(
            id = "doc", name = "Keep this drawing",
            boards = listOf(Board("board", "Canvas", BoardKind.CANVAS, RectPx(0, 0, 100, 100))),
            layers = listOf(Layer("paint", "Paint", LayerKind.PAINT,
                cels = listOf(Cel("cel", listOf("0_0"))), mask = Cel("mask", listOf("-2_3")))),
            activeLayerId = "paint", activeBoardId = "board",
        ),
        mapOf(paintKey to ByteArray(TILE_BYTES) { 41 }, maskKey to ByteArray(TILE_BYTES) { 73 }),
        emptyMap(),
    )

    private fun refusesBeforeWriting(contents: JbContents) {
        val out = ByteArrayOutputStream().apply { write(91) }
        assertFailsWith<JbArchiveException> { JbArchive.write(out, contents) }
        assertContentEquals(byteArrayOf(91), out.toByteArray())
    }

    @Test fun declaredPaintTileWithoutPayloadIsRefusedBeforeAnyOutput() {
        val contents = complete()
        refusesBeforeWriting(contents.copy(tiles = contents.tiles - paintKey))
    }

    @Test fun declaredMaskTileWithoutPayloadIsRefusedBeforeAnyOutput() {
        val contents = complete()
        refusesBeforeWriting(contents.copy(tiles = contents.tiles - maskKey))
    }

    @Test fun unlistedTileAndUnknownCelAreRefusedBeforeAnyOutput() {
        val contents = complete()
        refusesBeforeWriting(contents.copy(tiles = contents.tiles +
            (Triple("paint", "cel", "9_9") to ByteArray(TILE_BYTES))))
        refusesBeforeWriting(contents.copy(tiles = contents.tiles +
            (Triple("paint", "unknown", "0_0") to ByteArray(TILE_BYTES))))
    }

    @Test fun wrongTileSizeIsRefusedBeforeAnyOutput() {
        val contents = complete()
        refusesBeforeWriting(contents.copy(tiles = contents.tiles + (paintKey to byteArrayOf(1))))
    }

    @Test fun missingPayloadCannotReplaceAnExistingHealthyDrawing() {
        val dir = Files.createTempDirectory("joybrush-completeness-").toFile()
        val file = File(dir, "current.joybrush")
        try {
            val contents = complete()
            JbArchive.save(file, contents)
            val before = file.readBytes()
            assertFailsWith<JbArchiveException> {
                JbArchive.save(file, contents.copy(tiles = contents.tiles - paintKey))
            }
            assertContentEquals(before, file.readBytes())
            assertFalse(File(file.path + ".tmp").exists())
            assertFalse(File(file.path + ".bak").exists())
            val reopened = JbArchive.open(file)
            assertEquals(contents.doc, reopened.doc)
            assertContentEquals(contents.tiles.getValue(paintKey), reopened.tiles.getValue(paintKey))
        } finally {
            File(file.path + ".tmp").delete()
            File(file.path + ".bak").delete()
            file.delete()
            dir.delete()
        }
    }

    @Test fun completePaintAndMaskPayloadsRemainReadable() {
        val contents = complete()
        val out = ByteArrayOutputStream()
        JbArchive.write(out, contents)
        val reopened = JbArchive.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(contents.doc, reopened.doc)
        assertEquals(contents.tiles.keys, reopened.tiles.keys)
        for ((key, value) in contents.tiles) assertContentEquals(value, reopened.tiles.getValue(key))
    }
}

package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.Cel
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.Layer
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.doc.TILE_SIZE
import cc.joycreator.joybrush.core.media.HalfFloat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [JbArchive.saveCarrying]: a save that rewrites only what changed makes the same drawing a full save makes, carries what
 * it may, and carries nothing it cannot vouch for.
 */
class JbArchiveCarryTest {
    private val layer = "oil"
    private val cel = "c-oil"
    private val names = listOf("0_0", "1_0", "2_0", "3_0")

    private fun doc(tiles: List<String> = names, floats: List<String> = names) = JbDocument(
        id = "doc-carry", name = "Carry",
        boards = listOf(Board(id = "b", name = "Board", kind = BoardKind.CANVAS, rect = RectPx(0, 0, 1024, 256))),
        layers = listOf(Layer(id = layer, name = "Layer 1", kind = LayerKind.PAINT, cels = listOf(Cel(id = cel, tiles = tiles, floatTiles = floats)))),
        activeLayerId = layer, activeBoardId = "b",
    )

    private fun look(seed: Int) = ByteArray(TILE_BYTES) { ((it * 13 + seed * 7 + (it shr 9)) and 0xFF).toByte() }
    private fun store(seed: Int) = HalfFloat.encode(FloatArray(TILE_SIZE * TILE_SIZE * 4) { if ((it / 4096 + seed) % 3 == 0) 0f else 0.001f * ((it + seed) % 97) })
    private fun key(k: String, s: String) = MediaTileKey(layer, cel, k, s)

    private fun drawing(lookSeeds: Map<String, Int> = emptyMap(), storeSeeds: Map<String, Int> = emptyMap(), tiles: List<String> = names) = JbContents(
        doc = doc(tiles, tiles),
        tiles = tiles.associate { Triple(layer, cel, it) to look(lookSeeds[it] ?: (it.hashCode() and 0xFF)) },
        strokes = emptyMap(),
        mediaTiles = tiles.flatMap { n -> listOf(key(n, "p0") to store(storeSeeds[n] ?: (n.hashCode() and 0x3F)), key(n, "p1") to store((storeSeeds[n] ?: (n.hashCode() and 0x3F)) + 1)) }.toMap(),
    )

    private fun temp() = File.createTempFile("carry", ".joybrush").also { it.delete(); it.deleteOnExit(); File(it.path + ".bak").deleteOnExit() }

    private fun assertSameDrawing(want: JbContents, file: File) {
        val got = JbArchive.open(file)
        assertEquals(want.doc, got.doc)
        assertEquals(want.tiles.keys, got.tiles.keys)
        for ((k, v) in want.tiles) assertContentEquals(v, got.tiles.getValue(k), "picture $k")
        assertEquals(want.mediaTiles.keys, got.mediaTiles.keys)
        for ((k, v) in want.mediaTiles) assertContentEquals(v, got.mediaTiles.getValue(k), "store $k")
    }

    @Test fun theFirstSaveWritesEverythingAndIsTheDrawingAFullSaveMakes() {
        val f = temp()
        val memo = JbArchive.saveCarrying(f, drawing(), null)
        assertEquals(0, memo.carried)
        assertEquals(2 + 4 + 8, memo.fresh, "mimetype, document, four pictures, eight stores")
        assertSameDrawing(drawing(), f)
        // Entry for entry what the stock writer makes, in its order.
        val stock = ByteArrayOutputStream().also { JbArchive.write(it, drawing()) }.toByteArray()
        val stockNames = java.util.zip.ZipInputStream(stock.inputStream()).use { z -> generateSequence { z.nextEntry }.map { it.name }.toList() }
        assertEquals(stockNames, ZipCarry.index(f).keys.toList())
    }

    @Test fun aStrokeLaterOnlyWhatChangedIsCompressedAgain() {
        val f = temp()
        val memo = JbArchive.saveCarrying(f, drawing(), null)
        // One tile repainted: its picture and its two stores change; nothing else does.
        val after = drawing(lookSeeds = mapOf("1_0" to 200), storeSeeds = mapOf("1_0" to 50))
        val memo2 = JbArchive.saveCarrying(f, after, memo)
        assertEquals(3 + 2, memo2.fresh, "the tile's picture and two stores, the document and the mimetype")
        assertEquals(3 + 6, memo2.carried, "three pictures and six stores came over untouched")
        assertSameDrawing(after, f)
        assertTrue(File(f.path + ".bak").isFile, "the file before is kept beside it, as a full save keeps it")
        // And again with nothing changed: all of it carried but the two small entries always written.
        val memo3 = JbArchive.saveCarrying(f, after, memo2)
        assertEquals(4 + 8, memo3.carried)
        assertSameDrawing(after, f)
    }

    @Test fun aTileThatLeavesTheDrawingLeavesTheFileAndANewOneJoinsIt() {
        val f = temp()
        val memo = JbArchive.saveCarrying(f, drawing(), null)
        val fewer = drawing(tiles = listOf("0_0", "2_0"))
        val memo2 = JbArchive.saveCarrying(f, fewer, memo)
        assertSameDrawing(fewer, f)
        assertEquals(setOf("0_0", "2_0"), JbArchive.open(f).tiles.keys.map { it.third }.toSet())
        val more = drawing(tiles = listOf("0_0", "2_0", "5_5"))
        val memo3 = JbArchive.saveCarrying(f, more, memo2)
        assertEquals(2 + 4, memo3.carried, "the two tiles it had")
        assertSameDrawing(more, f)
    }

    @Test fun aFileThatIsNotTheOneTheMemoRemembersIsWrittenAfresh() {
        val f = temp()
        val memo = JbArchive.saveCarrying(f, drawing(), null)
        // Another program rewrote the file meanwhile (a restore, a sync): same drawing, other bytes.
        JbArchive.save(f, drawing())
        val memo2 = JbArchive.saveCarrying(f, drawing(), memo)
        assertEquals(0, memo2.carried, "nothing is carried from a file this memo did not write")
        assertSameDrawing(drawing(), f)
        // Nor from another path.
        val g = temp()
        val memo3 = JbArchive.saveCarrying(g, drawing(), memo2)
        assertEquals(0, memo3.carried)
        assertSameDrawing(drawing(), g)
    }

    @Test fun compressedBytesThatWentBadOnTheDiskAreNotCarried() {
        val f = temp()
        val memo = JbArchive.saveCarrying(f, drawing(), null)
        // One byte flips inside a store tile's compressed data; the file's length and time are put back as they were.
        val kept = ZipCarry.index(f).getValue("layers/$layer/$cel/2_0.p0.f16")
        val time = f.lastModified()
        RandomAccessFile(f, "rw").use { raf ->
            val at = kept.dataOffset + kept.compressedSize / 2
            raf.seek(at); val b = raf.read(); raf.seek(at); raf.write(b xor 0x40)
        }
        assertTrue(f.setLastModified(time))
        val memo2 = JbArchive.saveCarrying(f, drawing(), memo)
        assertEquals(1 + 2, memo2.fresh, "the damaged tile is compressed anew from the drawing, beside the two small entries")
        assertSameDrawing(drawing(), f)
    }

    @Test fun aDrawingTheArchiveRefusesIsRefusedBeforeTheOlderFileIsTouched() {
        val f = temp()
        val memo = JbArchive.saveCarrying(f, drawing(), null)
        val before = f.readBytes()
        // The document lists a store tile no state was given for.
        val broken = drawing().let { it.copy(mediaTiles = it.mediaTiles - key("3_0", "p0") - key("3_0", "p1")) }
        val refused = runCatching { JbArchive.saveCarrying(f, broken, memo) }.exceptionOrNull()
        assertTrue(refused is JbArchiveException, "refused in words: $refused")
        assertContentEquals(before, f.readBytes(), "the file the person had is the file they have")
        assertTrue(!File(f.path + ".tmp").exists(), "and no half-written file is left")
    }
}

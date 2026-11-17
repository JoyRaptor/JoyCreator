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
        // The document lists the same tiles, so its entry is the same bytes and is carried too.
        assertEquals(3 + 1, memo2.fresh, "the tile's picture and two stores, and the mimetype (always written)")
        assertEquals(3 + 6 + 1, memo2.carried, "three pictures, six stores and the document came over untouched")
        assertSameDrawing(after, f)
        assertTrue(File(f.path + ".bak").isFile, "the file before is kept beside it, as a full save keeps it")
        // And again with nothing changed: all of it carried but the mimetype.
        val memo3 = JbArchive.saveCarrying(f, after, memo2)
        assertEquals(4 + 8 + 1, memo3.carried)
        assertEquals(1, memo3.fresh)
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
        assertEquals(1 + 1, memo2.fresh, "the damaged tile is compressed anew from the drawing, beside the mimetype")
        assertEquals(4 + 7 + 1, memo2.carried, "everything else, the document included, is still carried")
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

    /** JBX-37: a writer that fails once everything is written, in each way a phone fails it, for both kinds of save. */
    @Test fun aSaveThatFailsWhileWritingLeavesTheOlderFileAndNothingElse() {
        val first = drawing()
        val second = drawing(lookSeeds = mapOf("1_0" to 201), storeSeeds = mapOf("1_0" to 21))
        val faults = listOf<Pair<String, () -> Nothing>>(
            "the disk is full" to { throw java.io.IOException("No space left on device") },
            "the memory ran out" to { throw OutOfMemoryError("test") },
            "something unforeseen" to { throw IllegalStateException("test") })
        try {
            for (carrying in listOf(true, false)) for ((what, fault) in faults) {
                val f = temp()
                var memo = JbArchive.saveCarrying(f, first, null)
                val before = f.readBytes()
                var sawTmp = false
                JbArchive.beforeCommit = { tmp -> sawTmp = tmp.isFile && tmp.length() > 0; fault() }
                val failure = runCatching { if (carrying) JbArchive.saveCarrying(f, second, memo) else JbArchive.save(f, second) }.exceptionOrNull()
                JbArchive.beforeCommit = null
                assertTrue(sawTmp, "$what: the failure came after the new file was written")
                if (what == "the memory ran out") assertTrue(failure is OutOfMemoryError, "$what is heard as it is: $failure")
                else assertTrue(failure is JbArchiveException, "$what is refused in words: $failure")
                assertContentEquals(before, f.readBytes(), "$what: the file the person had is the file they have")
                assertTrue(!File(f.path + ".tmp").exists(), "$what: no half-written file is left")
                assertSameDrawing(first, f)
                // And the next save, with the memo the failed one never replaced, is whole and still carries.
                memo = JbArchive.saveCarrying(f, second, memo)
                assertSameDrawing(second, f)
                assertTrue(memo.carried > 0, "$what: the save after the failure still carries what did not change")
                assertSameDrawing(first, File(f.path + ".bak"))
            }
        } finally { JbArchive.beforeCommit = null }
    }

    // ── Tiles given without their bytes (JBX-36: the canvas rereads only what changed) ──

    private val fp = cc.joycreator.joybrush.core.paint.TileFingerprint

    /** [d] with only the tiles named in [read] carrying bytes (and their fingerprints); the rest as their fingerprints alone. */
    private fun sparse(d: JbContents, read: Set<String>) = SparseContents(
        d.copy(tiles = d.tiles.filterKeys { it.third in read }, mediaTiles = d.mediaTiles.filterKeys { it.key in read }),
        d.tiles.filterKeys { it.third !in read }.mapValues { Unread(it.value.size, fp.ofBytes(it.value)) },
        d.mediaTiles.filterKeys { it.key !in read }.mapValues { Unread(it.value.size, fp.ofHalves(it.value)) },
        d.tiles.filterKeys { it.third in read }.mapValues { fp.ofBytes(it.value) },
        d.mediaTiles.filterKeys { it.key in read }.mapValues { fp.ofHalves(it.value) })

    /** [d] read whole, each tile with its fingerprint: what the canvas hands over the first time. */
    private fun whole(d: JbContents) = sparse(d, names.toSet() + "5_5")

    private fun entries(f: File) = ZipCarry.index(f).values.map { "${it.name} ${it.method} ${it.crc} ${it.compressedSize} ${it.size}" }

    @Test fun aSaveGivenOnlyTheChangedTilesMakesTheFileAFullSaveMakes() {
        val f = temp(); val full = temp()
        val memo = JbArchive.saveCarrying(f, whole(drawing()), null)
        assertSameDrawing(drawing(), f)
        val after = drawing(lookSeeds = mapOf("1_0" to 200), storeSeeds = mapOf("1_0" to 50))
        val memo2 = JbArchive.saveCarrying(f, sparse(after, setOf("1_0")), memo)
        assertEquals(3 + 1, memo2.fresh, "the changed tile's picture and two stores, and the mimetype")
        assertEquals(3 + 6 + 1, memo2.carried)
        assertSameDrawing(after, f)
        // Entry for entry the file a save with every tile's bytes makes from the same older file.
        JbArchive.saveCarrying(full, drawing(), null).let { JbArchive.saveCarrying(full, after, it) }
        assertEquals(entries(full), entries(f))
        assertSameDrawing(drawing(), File(f.path + ".bak"))
        // And again from its own memo, nothing read at all.
        val memo3 = JbArchive.saveCarrying(f, sparse(after, emptySet()), memo2)
        assertEquals(4 + 8 + 1, memo3.carried)
        assertSameDrawing(after, f)
    }

    @Test fun aTileWithoutBytesThatTheFileCannotVouchForRefusesTheSaveAndLeavesTheFile() {
        val first = drawing()
        val after = drawing(lookSeeds = mapOf("1_0" to 200), storeSeeds = mapOf("1_0" to 50))
        fun refused(what: String, prepare: (File, JbArchive.SaveMemo) -> Pair<SparseContents, JbArchive.SaveMemo?>) {
            val f = temp()
            val memo = JbArchive.saveCarrying(f, whole(first), null)
            val (given, withMemo) = prepare(f, memo)
            val before = f.readBytes()
            val failure = runCatching { JbArchive.saveCarrying(f, given, withMemo) }.exceptionOrNull()
            assertTrue(failure is JbArchiveNeedsBytes, "$what: $failure")
            assertContentEquals(before, f.readBytes(), "$what: the file is as it was")
            assertTrue(!File(f.path + ".tmp").exists(), "$what: no half-written file is left")
            // The whole drawing then saves as always.
            JbArchive.saveCarrying(f, after, withMemo)
            assertSameDrawing(after, f)
        }
        refused("no earlier save is remembered") { _, _ -> sparse(after, setOf("1_0")) to null }
        // The canvas remembers a tile as it was painted since; the file holds the older one.
        refused("the tile changed since the file was written") { _, memo -> sparse(after, emptySet()) to memo }
        // A save that was given the bytes alone (a copy, the picture kept at a pause) remembers no fingerprints.
        refused("the last save was given no fingerprints") { f, _ -> sparse(first, emptySet()) to JbArchive.saveCarrying(f, first, null) }
        refused("the file is not the one that save wrote") { f, memo ->
            JbArchive.save(f, first)   // the same drawing written again by someone else
            f.setLastModified(f.lastModified() + 5000)
            sparse(after, setOf("1_0")) to memo
        }
        refused("the file's bytes were damaged in a tile that is carried") { f, memo ->
            val e = ZipCarry.index(f).values.first { it.name.contains("/2_0") && !it.name.contains(".p") }
            val length = f.length(); val time = f.lastModified()
            RandomAccessFile(f, "rw").use { val at = e.dataOffset + e.compressedSize / 2; it.seek(at); val b = it.read(); it.seek(at); it.write(b xor 0x55) }
            f.setLastModified(time)
            assertEquals(length, f.length())
            sparse(after, setOf("1_0")) to memo
        }
    }

    // ── A drawing just opened from its file (JbArchive.memoOf) ──

    private fun printsOf(d: JbContents) = d.tiles.mapValues { fp.ofBytes(it.value) } to d.mediaTiles.mapValues { fp.ofHalves(it.value) }

    @Test fun aDrawingOpenedFromItsFileIsSavedByWhatChangedFromTheFirstSaveOn() {
        val f = temp(); val full = temp()
        JbArchive.save(f, drawing())                       // any earlier writer: a plain save, another session, a copy
        val (tiles, media) = printsOf(JbArchive.open(f))   // the fingerprints of the tiles as they went onto the canvas
        val memo = JbArchive.memoOf(f, tiles, media)!!
        val after = drawing(lookSeeds = mapOf("1_0" to 200), storeSeeds = mapOf("1_0" to 50))
        val memo2 = JbArchive.saveCarrying(f, sparse(after, setOf("1_0")), memo)
        assertEquals(3 + 6, memo2.carried, "every tile that did not change, taken from the file as it lay")
        assertEquals(3 + 2, memo2.fresh, "the changed tile's picture and stores, the mimetype and the document")
        assertSameDrawing(after, f)
        JbArchive.save(full, after)
        assertEquals(entries(full).map { it.substringBefore(' ') }, entries(f).map { it.substringBefore(' ') }, "the same entries in the same order")
        // From then on it is an ordinary remembered file.
        val memo3 = JbArchive.saveCarrying(f, sparse(after, emptySet()), memo2)
        assertEquals(4 + 8 + 1, memo3.carried)
        assertSameDrawing(after, f)
    }

    @Test fun aFileThatIsNotWhatWentOntoTheCanvasIsNotTrusted() {
        val first = drawing()
        val after = drawing(lookSeeds = mapOf("1_0" to 200), storeSeeds = mapOf("1_0" to 50))
        // The canvas holds another picture of a tile than the file (the open redrew it): its fingerprint is not the file's.
        run {
            val f = temp(); JbArchive.save(f, first)
            val (tiles, media) = printsOf(first)
            val memo = JbArchive.memoOf(f, tiles, media)!!
            val before = f.readBytes()
            assertTrue(runCatching { JbArchive.saveCarrying(f, sparse(after, emptySet()), memo) }.exceptionOrNull() is JbArchiveNeedsBytes)
            assertContentEquals(before, f.readBytes())
        }
        // The file was written again after the open.
        run {
            val f = temp(); JbArchive.save(f, first)
            val (tiles, media) = printsOf(first)
            val memo = JbArchive.memoOf(f, tiles, media)!!
            JbArchive.save(f, after); f.setLastModified(f.lastModified() + 5000)
            assertTrue(runCatching { JbArchive.saveCarrying(f, sparse(first, emptySet()), memo) }.exceptionOrNull() is JbArchiveNeedsBytes)
            assertSameDrawing(after, f)
        }
        // A tile the file does not hold, or no file at all: no memo, and the next save reads everything.
        run {
            val f = temp(); JbArchive.save(f, first)
            val (tiles, media) = printsOf(first)
            assertEquals(null, JbArchive.memoOf(f, tiles + (Triple(layer, cel, "9_9") to tiles.values.first()), media))
            assertEquals(null, JbArchive.memoOf(temp(), tiles, media))
        }
        // A tile given WITH its bytes is never taken from a file known only by its directory: it is compressed anew.
        run {
            val f = temp(); JbArchive.save(f, first)
            val (tiles, media) = printsOf(first)
            val memo = JbArchive.memoOf(f, tiles, media)!!
            val memo2 = JbArchive.saveCarrying(f, first, memo)
            assertEquals(0, memo2.carried)
            assertSameDrawing(first, f)
        }
    }

    @Test fun aTileWithoutBytesThatIsNotTheDrawingsIsRefusedLikeAnyOther() {
        val f = temp()
        val memo = JbArchive.saveCarrying(f, whole(drawing()), null)
        val d = drawing()
        val print = fp.ofBytes(d.tiles.getValue(Triple(layer, cel, "0_0")))
        val stray = SparseContents(d.copy(tiles = d.tiles - Triple(layer, cel, "0_0")),
            mapOf(Triple(layer, cel, "0_0") to Unread(TILE_BYTES, print), Triple(layer, cel, "9_9") to Unread(TILE_BYTES, print)))
        val failure = runCatching { JbArchive.saveCarrying(f, stray, memo) }.exceptionOrNull()
        assertTrue(failure is JbArchiveException && failure !is JbArchiveNeedsBytes, "a tile the document does not list: $failure")
        assertSameDrawing(drawing(), f)
        val both = SparseContents(d, mapOf(Triple(layer, cel, "0_0") to Unread(TILE_BYTES, print)))
        assertTrue(runCatching { JbArchive.saveCarrying(f, both, memo) }.exceptionOrNull() is JbArchiveException)
        assertSameDrawing(drawing(), f)
    }
}

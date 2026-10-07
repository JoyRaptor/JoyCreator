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
import cc.joycreator.joybrush.core.media.MediaStores
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A media layer in the `.joybrush` file (step 3b, the Lead's store-format ruling): `.f16` store tiles round-trip, a save
 * mid-flow keeps the water (so reopening resumes wet), and every disagreement between the document and the files is
 * refused in one sentence, in both directions, on write AND on read.
 */
class JbArchiveMediaTest {
    private val layer = "wc"
    private val cel = "c-wc"
    private val mediaBytes = MediaStores.tileBytes(TILE_SIZE)

    private fun doc(floatTiles: List<String> = listOf("0_0", "1_0")) = JbDocument(
        id = "doc-m", name = "Media",
        boards = listOf(Board(id = "b", name = "Board", kind = BoardKind.CANVAS, rect = RectPx(0, 0, 512, 256))),
        layers = listOf(Layer(id = layer, name = "Watercolour", kind = LayerKind.MEDIA,
            cels = listOf(Cel(id = cel, tiles = listOf("0_0", "1_0"), floatTiles = floatTiles)))),
        activeLayerId = layer, activeBoardId = "b",
    )

    private fun look(seed: Int) = ByteArray(TILE_BYTES) { ((it * 13 + seed) and 0xFF).toByte() }

    /** Real half floats, so the bytes mean something: a wash of water `w` mm deep. */
    private fun state(w: Float) = HalfFloat.encode(FloatArray(TILE_SIZE * TILE_SIZE * 4) { if (it % 4 == 0) w else 0.001f * (it % 7) })

    private fun key(k: String, store: String) = MediaTileKey(layer, cel, k, store)

    /** Watercolour mid-flow: paint planes on both tiles, water still on the left one, no paper crush (it never writes one). */
    private fun wet() = JbContents(
        doc = doc(),
        tiles = mapOf(Triple(layer, cel, "0_0") to look(1), Triple(layer, cel, "1_0") to look(2)),
        strokes = emptyMap(),
        mediaTiles = mapOf(
            key("0_0", "p0") to state(0f), key("0_0", "p1") to state(0f),
            key("0_0", "w0") to state(0.3f), key("0_0", "w1") to state(0f),
            key("1_0", "p0") to state(0f), key("1_0", "p1") to state(0f),
        ),
    )

    private fun bytesOf(c: JbContents) = ByteArrayOutputStream().also { JbArchive.write(it, c) }.toByteArray()
    private fun read(b: ByteArray) = JbArchive.read(ByteArrayInputStream(b))

    @Test fun aSaveMidFlowReopensWet() {
        val back = read(bytesOf(wet()))
        assertEquals(wet().mediaTiles.keys, back.mediaTiles.keys)
        for ((k, v) in wet().mediaTiles) assertContentEquals(v, back.mediaTiles.getValue(k), k.toString())
        assertEquals(0.3f, HalfFloat.decode(back.mediaTiles.getValue(key("0_0", "w0")))[0], 1e-3f, "the water is still there")
        assertTrue(back.mediaTiles.keys.none { it.store == "paper" }, "watercolour made no paper tiles, and none appear")
    }

    @Test fun storeTilesAreNamedAsTheFormatSays() {
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(bytesOf(wet()))).use { z -> while (true) names += (z.nextEntry ?: break).name }
        assertTrue("layers/wc/c-wc/0_0.w0.f16" in names, names.toString())
        assertTrue("layers/wc/c-wc/0_0.rgba" in names, "the look is an ordinary paint tile")
        assertEquals(mediaBytes, TILE_SIZE * TILE_SIZE * 8, "half floats at rest")
    }

    @Test fun writingRefusesStateTheDocumentDoesNotList() {
        val extra = wet().copy(mediaTiles = wet().mediaTiles + (key("2_0", "p0") to state(0f)))
        val e = assertFailsWith<JbArchiveException> { bytesOf(extra) }
        assertTrue("which does not list it" in e.message!!, e.message)
    }

    @Test fun writingRefusesADeclaredTileWithNoState() {
        val missing = wet().copy(mediaTiles = wet().mediaTiles.filterKeys { it.key != "1_0" })
        val e = assertFailsWith<JbArchiveException> { bytesOf(missing) }
        assertTrue("media tile \"1_0\"" in e.message!! && "no state was given" in e.message!!, e.message)
    }

    @Test fun writingRefusesAnUnknownStoreAndAWrongSize() {
        assertFailsWith<JbArchiveException> { bytesOf(wet().copy(mediaTiles = wet().mediaTiles + (key("0_0", "mud") to state(0f)))) }
        val short = wet().copy(mediaTiles = wet().mediaTiles + (key("0_0", "p0") to ByteArray(10)))
        assertTrue("is 10 bytes" in assertFailsWith<JbArchiveException> { bytesOf(short) }.message!!)
    }

    @Test fun readingRefusesADeclaredTileTheArchiveLacks() {
        // Written as a document that lists one tile, then the document is swapped for one that lists two.
        val one = JbContents(doc(listOf("0_0")), mapOf(Triple(layer, cel, "0_0") to look(1), Triple(layer, cel, "1_0") to look(2)),
            emptyMap(), mediaTiles = wet().mediaTiles.filterKeys { it.key == "0_0" })
        val bytes = bytesOf(one)
        val swapped = JbArchiveSwap.replaceDocument(bytes, doc())
        val e = assertFailsWith<JbArchiveException> { read(swapped) }
        assertTrue("this archive has no state for it" in e.message!!, e.message)
    }

    @Test fun readingRefusesStateTheDocumentDoesNotList() {
        val bytes = bytesOf(wet())
        val swapped = JbArchiveSwap.replaceDocument(bytes, doc(listOf("0_0")).let { d ->
            d.copy(layers = d.layers.map { l -> l.copy(cels = l.cels.map { it.copy(tiles = listOf("0_0", "1_0")) }) })
        })
        val e = assertFailsWith<JbArchiveException> { read(swapped) }
        assertTrue("which does not list it" in e.message!!, e.message)
    }
}

/** Rewrites an archive with another `document.json`: how a reader meets a document and files that disagree. */
private object JbArchiveSwap {
    fun replaceDocument(archive: ByteArray, doc: JbDocument): ByteArray {
        val entries = ArrayList<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(archive)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                entries += e.name to (if (e.name == "document.json") cc.joycreator.joybrush.core.doc.DocJson.encode(doc).toByteArray() else z.readBytes())
            }
        }
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zos ->
            for ((name, bytes) in entries) {
                val entry = java.util.zip.ZipEntry(name)
                if (name == "mimetype") {
                    entry.method = java.util.zip.ZipEntry.STORED
                    entry.size = bytes.size.toLong(); entry.compressedSize = bytes.size.toLong()
                    entry.crc = java.util.zip.CRC32().apply { update(bytes) }.value
                }
                zos.putNextEntry(entry); zos.write(bytes); zos.closeEntry()
            }
        }
        return out.toByteArray()
    }
}

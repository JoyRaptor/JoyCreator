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
import java.io.Closeable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [JbArchive.openDeferringMedia] ("open as it is read"): the same drawing as [JbArchive.open] gives, with the paint state
 * read from the file when asked for; the same refusals with the same words; the file's later fate does not reach a
 * drawing that was checked.
 */
class DeferredMediaTest {
    private val layer = "wc"
    private val cel = "c-wc"

    private fun doc(floatTiles: List<String> = listOf("0_0", "1_0")) = JbDocument(
        id = "doc-d", name = "Deferred",
        boards = listOf(Board(id = "b", name = "Board", kind = BoardKind.CANVAS, rect = RectPx(0, 0, 512, 256))),
        layers = listOf(Layer(id = layer, name = "Layer 1", kind = LayerKind.PAINT,
            cels = listOf(Cel(id = cel, tiles = listOf("0_0", "1_0"), floatTiles = floatTiles)))),
        activeLayerId = layer, activeBoardId = "b",
    )

    private fun look(seed: Int) = ByteArray(TILE_BYTES) { ((it * 13 + seed) and 0xFF).toByte() }
    private fun state(w: Float) = HalfFloat.encode(FloatArray(TILE_SIZE * TILE_SIZE * 4) { if (it % 4 == 0) w else 0.001f * (it % 7) })
    private fun key(k: String, store: String) = MediaTileKey(layer, cel, k, store)

    private fun painted() = JbContents(
        doc = doc(),
        tiles = mapOf(Triple(layer, cel, "0_0") to look(1), Triple(layer, cel, "1_0") to look(2)),
        strokes = emptyMap(),
        mediaTiles = mapOf(
            key("0_0", "p0") to state(0.1f), key("0_0", "p1") to state(0.2f), key("0_0", "w0") to state(0.3f),
            key("0_0", MediaStores.GROUND) to look(7),
            key("1_0", "p0") to state(0.4f), key("1_0", "p1") to state(0.5f),
        ),
    )

    private fun temp(): File = File.createTempFile("deferred-media", ".joybrush").also { it.deleteOnExit() }
    private fun saved(c: JbContents): File = temp().also { JbArchive.save(it, c) }
    private fun close(c: JbContents) { (c.mediaTiles as? Closeable)?.close() }

    @Test fun itIsTheSameDrawingAsAnOrdinaryOpen() {
        val file = saved(painted())
        val whole = JbArchive.open(file)
        val deferred = JbArchive.openDeferringMedia(file)
        try {
            assertTrue(deferred.mediaTiles is DeferredMedia, "the paint state is held on disk")
            assertEquals(whole.doc, deferred.doc)
            assertEquals(whole.tiles.keys, deferred.tiles.keys)
            for ((k, v) in whole.tiles) assertContentEquals(v, deferred.tiles.getValue(k))
            assertEquals(whole.mediaTiles.keys, deferred.mediaTiles.keys)
            assertEquals(whole.mediaTiles.size, deferred.mediaTiles.size)
            for ((k, v) in whole.mediaTiles) assertContentEquals(v, deferred.mediaTiles.getValue(k), k.toString())
            // Walked as the canvas walks it, and walked twice: nothing is used up.
            repeat(2) { for ((k, v) in deferred.mediaTiles) assertContentEquals(whole.mediaTiles.getValue(k), v, k.toString()) }
            assertTrue(key("0_0", "w0") in deferred.mediaTiles)
            assertFalse(key("9_9", "p0") in deferred.mediaTiles)
            assertEquals(null, deferred.mediaTiles[key("9_9", "p0")])
        } finally { close(deferred); file.delete() }
    }

    @Test fun aDrawingWithNoPaintStateIsOpenedAsBefore() {
        val plain = painted().copy(doc = doc(floatTiles = emptyList()), mediaTiles = emptyMap())
        val file = saved(plain)
        val back = JbArchive.openDeferringMedia(file)
        assertFalse(back.mediaTiles is DeferredMedia)
        assertTrue(back.mediaTiles.isEmpty())
        for ((k, v) in plain.tiles) assertContentEquals(v, back.tiles.getValue(k))
        file.delete()
    }

    @Test fun whatAnOrdinaryOpenRefusesIsRefusedWithTheSameWords() {
        // Cut short, not a zip at all, and a zip that is not a drawing: each refused before anything is handed out.
        val good = saved(painted())
        val bytes = good.readBytes()
        val cases = listOf(bytes.copyOf(bytes.size / 2), "not a drawing".toByteArray(), ByteArray(0))
        for (bad in cases) {
            val file = temp().also { it.writeBytes(bad) }
            val plain = assertFailsWith<JbArchiveException> { JbArchive.open(file) }
            val deferred = assertFailsWith<JbArchiveException> { JbArchive.openDeferringMedia(file) }
            assertEquals(plain.message, deferred.message)
            file.delete()
        }
        val missing = File(good.parentFile, "no-such-${System.nanoTime()}.joybrush")
        assertEquals(assertFailsWith<JbArchiveException> { JbArchive.open(missing) }.message,
            assertFailsWith<JbArchiveException> { JbArchive.openDeferringMedia(missing) }.message)
        good.delete()
    }

    @Test fun aStateTheDocumentDoesNotListIsStillRefused() {
        // The document says only 0_0 holds paint state; the file carries 1_0's as well. Written by hand: save() would refuse.
        val honest = saved(painted())
        val lying = temp()
        java.util.zip.ZipFile(honest).use { zip ->
            java.util.zip.ZipOutputStream(lying.outputStream()).use { out ->
                for (e in zip.entries()) {
                    val data = zip.getInputStream(e).readBytes()
                    val body = if (e.name == "document.json") cc.joycreator.joybrush.core.doc.DocJson.encode(doc(floatTiles = listOf("0_0"))).toByteArray() else data
                    val entry = java.util.zip.ZipEntry(e.name)
                    if (e.method == java.util.zip.ZipEntry.STORED) {
                        entry.method = java.util.zip.ZipEntry.STORED; entry.size = body.size.toLong(); entry.compressedSize = body.size.toLong()
                        entry.crc = java.util.zip.CRC32().apply { update(body) }.value
                    }
                    out.putNextEntry(entry); out.write(body); out.closeEntry()
                }
            }
        }
        val plain = assertFailsWith<JbArchiveException> { JbArchive.open(lying) }
        val deferred = assertFailsWith<JbArchiveException> { JbArchive.openDeferringMedia(lying) }
        assertEquals(plain.message, deferred.message)
        honest.delete(); lying.delete()
    }

    @Test fun aSaveOverTheFileDoesNotReachADrawingAlreadyChecked() {
        val file = saved(painted())
        val whole = JbArchive.open(file)
        val deferred = JbArchive.openDeferringMedia(file)
        try {
            // The working save replaces the file by rename; the drawing in hand still reads what was checked, or says so.
            val other = painted().let { c -> c.copy(mediaTiles = c.mediaTiles.mapValues { (_, v) -> ByteArray(v.size) { 1 } }) }
            // On a build PC with Windows a file held open cannot be renamed, so the save itself is refused there and the
            // file stays as it was: also "does not reach". On the phone (and Linux) the rename goes through.
            try { JbArchive.save(file, other) } catch (e: JbArchiveException) { }
            for (k in whole.mediaTiles.keys) {
                val got = try { deferred.mediaTiles.getValue(k) } catch (e: JbArchiveException) { null }
                if (got != null) assertContentEquals(whole.mediaTiles.getValue(k), got, "never the other file's bytes: $k")
            }
        } finally { close(deferred); file.delete() }
    }

    @Test fun anotherFileAtThePathIsAnErrorNeverItsBytes() {
        val file = saved(painted())
        val deferred = JbArchive.openDeferringMedia(file)
        close(deferred)   // let go: the next read opens the path again
        val other = painted().let { c -> c.copy(mediaTiles = c.mediaTiles.mapValues { (_, v) -> ByteArray(v.size) { 1 } }) }
        JbArchive.save(file, other)
        for (k in deferred.mediaTiles.keys) assertFailsWith<JbArchiveException>(k.toString()) { deferred.mediaTiles.getValue(k) }
        close(deferred); file.delete()
    }

    @Test fun aDirectoryThatDisagreesWithTheReadingIsNotTrusted() {
        // What sends openDeferringMedia back to the ordinary whole open: the zip's directory must say of every tile what
        // the checking pass read. (A zip whose directory lies cannot be made with java.util.zip, so the check is asked directly.)
        val file = saved(painted())
        val whole = JbArchive.open(file)
        java.util.zip.ZipFile(file).use { zip ->
            fun noted(name: String, size: Int, crc: Long) = mapOf(key("0_0", "p0") to DeferredMedia.Entry(name, size, crc))
            val name = "layers/$layer/$cel/0_0.p0${MediaStores.EXT}"
            val bytes = whole.mediaTiles.getValue(key("0_0", "p0"))
            val crc = java.util.zip.CRC32().apply { update(bytes) }.value
            assertTrue(DeferredMedia.agrees(zip, noted(name, bytes.size, crc)))
            assertFalse(DeferredMedia.agrees(zip, noted(name, bytes.size, crc xor 1L)), "another CRC")
            assertFalse(DeferredMedia.agrees(zip, noted(name, bytes.size - 1, crc)), "another length")
            assertFalse(DeferredMedia.agrees(zip, noted("layers/$layer/$cel/9_9.p0${MediaStores.EXT}", bytes.size, crc)), "no such entry")
        }
        file.delete()
    }

    @Test fun theRecoveryCopyOfADeferredDrawingIsItsFile() {
        val dir = File(System.getProperty("java.io.tmpdir"), "deferred-recovery-${System.nanoTime()}").also { it.mkdirs() }
        val file = saved(painted())
        val deferred = JbArchive.openDeferringMedia(file)
        try {
            val recovery = DrawingRecovery(File(dir, "joybrush-recovery-test.joybrush"))
            recovery.rememberOpened(deferred)
            val whole = JbArchive.open(file)
            val back = recovery.read(deferred.doc.id)
            try {
                assertEquals(whole.mediaTiles.keys, back.mediaTiles.keys)
                for ((k, v) in whole.mediaTiles) assertContentEquals(v, back.mediaTiles.getValue(k), k.toString())
                for ((k, v) in whole.tiles) assertContentEquals(v, back.tiles.getValue(k))
                // Remembering the recovery copy's own contents again copies nothing onto itself and loses nothing.
                recovery.rememberOpened(back)
            } finally { close(back) }
            val again = recovery.read(deferred.doc.id)
            try { for ((k, v) in whole.mediaTiles) assertContentEquals(v, again.mediaTiles.getValue(k), k.toString()) } finally { close(again) }
        } finally { close(deferred); file.delete(); dir.deleteRecursively() }
    }

    @Test fun afterItIsLetGoAndTheFileIsGoneAReadFailsInWords() {
        val file = saved(painted())
        val deferred = JbArchive.openDeferringMedia(file)
        close(deferred)
        // Let go and still there: it opens the path again and reads the same bytes.
        assertContentEquals(JbArchive.open(file).mediaTiles.getValue(key("0_0", "p0")), deferred.mediaTiles.getValue(key("0_0", "p0")))
        close(deferred)
        assertTrue(file.delete())
        assertFailsWith<JbArchiveException> { deferred.mediaTiles.getValue(key("0_0", "p0")) }
        assertEquals(6, deferred.mediaTiles.size, "what it holds is still known")
    }
}

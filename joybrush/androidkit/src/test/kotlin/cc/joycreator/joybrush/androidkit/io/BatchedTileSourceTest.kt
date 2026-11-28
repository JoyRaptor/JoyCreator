package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.Cel
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.Layer
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.doc.TILE_SIZE
import cc.joycreator.joybrush.core.media.MediaStores
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JBX-36 S2: a streamed save's tiles are read back a batch at a time on another thread (the graphics thread on a phone)
 * while the save writes them. The file is the drawing as it was frozen; at most two batches wait in memory; a read
 * that stops refuses the save in the words that make the screen read the whole drawing the old way; the copies not
 * read are always let go.
 */
class BatchedTileSourceTest {
    private val layer = "oil"
    private val cel = "c"
    private val count = 40
    private val names = (0 until count).map { "${it % 10}_${it / 10}" }
    private val storeBytes = MediaStores.tileBytes(TILE_SIZE, "p0")
    private val gl = Executors.newSingleThreadExecutor()

    @BeforeTest fun on() { ArchiveCarrying.streamTiles = true }
    @AfterTest fun off() { ArchiveCarrying.streamTiles = true; gl.shutdownNow() }   // the default since 11-28

    private fun doc() = JbDocument(
        id = "doc-batched", name = "Batched",
        boards = listOf(Board(id = "b", name = "Board", kind = BoardKind.CANVAS, rect = RectPx(0, 0, 10 * TILE_SIZE, 4 * TILE_SIZE))),
        layers = listOf(Layer(id = layer, name = "Layer 1", kind = LayerKind.PAINT, cels = listOf(Cel(id = cel, tiles = names, floatTiles = names)))),
        activeLayerId = layer, activeBoardId = "b",
    )
    private val pictureKeys = names.map { Triple(layer, cel, it) }
    private val storeKeys = names.flatMap { listOf(MediaTileKey(layer, cel, it, "p0"), MediaTileKey(layer, cel, it, "p1")) }
    private fun bytesOf(k: Any, seed: Int): ByteArray = when (k) {
        is MediaTileKey -> ByteArray(storeBytes) { ((it * 5 + names.indexOf(k.key) + seed + k.store.length + (it shr 13)) and 0x3F).toByte() }
        is Triple<*, *, *> -> ByteArray(TILE_BYTES) { ((it * 3 + names.indexOf(k.third) * 11 + seed + (it shr 10)) and 0xFF).toByte() }
        else -> error("no such key")
    }

    private class Fake(val frozen: Map<Any, ByteArray>, val failAt: Int = -1) {
        val reads = AtomicInteger()
        val drops = AtomicInteger()
        fun read(k: Any): ByteArray {
            if (reads.incrementAndGet() == failAt) throw IllegalStateException("the graphics restarted")
            return frozen.getValue(k)
        }
    }

    private fun source(fake: Fake, batch: Long, alive: () -> Boolean = { true }, post: ((() -> Unit) -> Unit)? = null): BatchedTileSource {
        val order: List<Any> = pictureKeys + storeKeys
        val sizes = order.associateWith { if (it is MediaTileKey) storeBytes else TILE_BYTES }
        return BatchedTileSource(order, sizes, emptyMap(), batch, post ?: { work -> gl.execute { work() } },
            { fake.read(it) }, { fake.drops.incrementAndGet() }, alive, waitMs = 5_000L)
    }

    private fun temp() = File.createTempFile("batched", ".joybrush").also { it.delete(); it.deleteOnExit(); File(it.path + ".bak").deleteOnExit() }

    @Test fun aStreamedSaveWritesTheFrozenDrawingWithAtMostTwoBatchesWaiting() {
        val frozen = (pictureKeys + storeKeys).associateWith { bytesOf(it, 1) }
        val fake = Fake(frozen)
        val batch = 4L * storeBytes
        val src = source(fake, batch)
        val f = temp()
        JbArchive.saveCarrying(f, JbContents(doc(), src.pictures, emptyMap(), null, src.media), null)
        assertTrue(src.mostBytesWaiting <= 2 * batch, "at most two batches waiting: ${src.mostBytesWaiting} bytes, a batch is $batch")
        assertEquals(count * 3, fake.reads.get(), "every frozen tile read once")
        val back = JbArchive.open(f)
        for (k in pictureKeys) assertContentEquals(frozen.getValue(k), back.tiles.getValue(k), "picture $k")
        for (k in storeKeys) assertContentEquals(frozen.getValue(k), back.mediaTiles.getValue(k), "store $k")
        gl.submit { }.get()
        assertEquals(1, fake.drops.get(), "the end of the save let the copies go once")
    }

    @Test fun aTileThatCannotBeReadRefusesTheSaveAndLeavesTheFile() {
        val f = temp()
        JbArchive.saveCarrying(f, JbContents(doc(), pictureKeys.associateWith { bytesOf(it, 0) }, emptyMap(), null,
            storeKeys.associateWith { bytesOf(it, 0) }), null)
        val before = f.readBytes()
        val fake = Fake((pictureKeys + storeKeys).associateWith { bytesOf(it, 2) }, failAt = 17)
        val src = source(fake, 4L * storeBytes)
        assertFailsWith<JbArchiveNeedsBytes> {
            JbArchive.saveCarrying(f, JbContents(doc(), src.pictures, emptyMap(), null, src.media), null)
        }
        assertContentEquals(before, f.readBytes(), "the older file stands")
        assertFalse(File(f.path + ".tmp").exists(), "no half file is left")
        gl.submit { }.get()
        assertEquals(1, fake.drops.get(), "the copies not read were let go")
    }

    @Test fun aScreenThatStoppedDrawingRefusesAtOnceInsteadOfWaiting() {
        val fake = Fake((pictureKeys + storeKeys).associateWith { bytesOf(it, 3) })
        // Nothing posted ever runs (no frames), and the view says it no longer draws.
        val src = source(fake, 4L * storeBytes, alive = { false }, post = { })
        val began = System.currentTimeMillis()
        assertFailsWith<JbArchiveNeedsBytes> { (src.pictures as TileStream<Triple<String, String, String>>).take(pictureKeys[0]) }
        assertTrue(System.currentTimeMillis() - began < 2_000L, "refused at once, not after the wait")
    }

    @Test fun walkingAStreamedMapIsRefusedButNamingItIsNot() {
        val src = source(Fake(emptyMap()), 1L shl 20)
        assertEquals("$count streamed tiles", src.pictures.toString())
        assertTrue(src.pictures.containsKey(pictureKeys[0]))
        assertFailsWith<UnsupportedOperationException> { src.pictures.entries.size }
    }
}

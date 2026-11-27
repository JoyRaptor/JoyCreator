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
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JBX-36 S1: a save holds one tile at a time. A drawing's tiles come through a [TileStream] (as they will from the
 * graphics chip, a batch at a time); the save asks each tile's length to check the drawing, then takes its bytes once,
 * writes them and hands them back before taking the next. The file is the one a save of the same tiles in memory makes.
 */
class JbArchiveStreamTest {
    @BeforeTest fun on() { ArchiveCarrying.streamTiles = true }
    @AfterTest fun off() { ArchiveCarrying.streamTiles = false }

    private val layer = "oil"
    private val cel = "c-oil"
    private val count = 120
    private val names = (0 until count).map { "${it % 12}_${it / 12}" }
    private val storeBytes = MediaStores.tileBytes(TILE_SIZE, "p0")

    private fun doc() = JbDocument(
        id = "doc-stream", name = "Stream",
        boards = listOf(Board(id = "b", name = "Board", kind = BoardKind.CANVAS, rect = RectPx(0, 0, 12 * TILE_SIZE, 10 * TILE_SIZE))),
        layers = listOf(Layer(id = layer, name = "Layer 1", kind = LayerKind.PAINT, cels = listOf(Cel(id = cel, tiles = names, floatTiles = names)))),
        activeLayerId = layer, activeBoardId = "b",
    )

    private fun look(i: Int) = ByteArray(TILE_BYTES) { ((it * 31 + i * 7 + (it shr 10)) and 0xFF).toByte() }
    private fun store(i: Int, s: Int) = ByteArray(storeBytes) { ((it * 17 + i * 3 + s + (it shr 12)) and 0x3F).toByte() }
    private val tileKeys = names.map { Triple(layer, cel, it) }
    private val mediaKeys = names.flatMap { listOf(MediaTileKey(layer, cel, it, "p0"), MediaTileKey(layer, cel, it, "p1")) }
    private fun lookOf(k: Triple<String, String, String>) = look(names.indexOf(k.third))
    private fun storeOf(k: MediaTileKey) = store(names.indexOf(k.key), if (k.store == "p0") 0 else 1)

    /** Makes each tile when asked and counts how many are out (taken, not yet written back) at once. */
    private class Counting<K>(private val listed: List<K>, private val sizeOfKey: (K) -> Int, private val make: (K) -> ByteArray) :
        AbstractMap<K, ByteArray>(), TileStream<K> {
        var out = 0; var most = 0; var made = 0
        private val taken = HashSet<K>()
        private fun hand(key: K): ByteArray {
            made++
            if (taken.add(key)) { out++; most = maxOf(most, out) }
            return make(key)
        }
        override val keys: Set<K> = LinkedHashSet(listed)
        override val size: Int get() = listed.size
        override fun containsKey(key: K) = key in keys
        override fun get(key: K): ByteArray? = if (key in keys) hand(key) else null
        override fun sizeOf(key: K): Int? = if (key in keys) sizeOfKey(key) else null
        override fun take(key: K): ByteArray = hand(key)
        override fun written(key: K) { if (taken.remove(key)) out-- }
        override val entries: Set<Map.Entry<K, ByteArray>> get() =
            keys.mapTo(LinkedHashSet()) { java.util.AbstractMap.SimpleImmutableEntry(it, hand(it)) }
    }

    private fun streamed(): Triple<JbContents, Counting<Triple<String, String, String>>, Counting<MediaTileKey>> {
        val t = Counting(tileKeys, { TILE_BYTES }, ::lookOf)
        val m = Counting(mediaKeys, { storeBytes }, ::storeOf)
        return Triple(JbContents(doc = doc(), tiles = t, strokes = emptyMap(), mediaTiles = m), t, m)
    }

    private fun inMemory() = JbContents(doc = doc(), tiles = tileKeys.associateWith(::lookOf), strokes = emptyMap(),
        mediaTiles = mediaKeys.associateWith(::storeOf))

    private fun temp() = File.createTempFile("stream", ".joybrush").also { it.delete(); it.deleteOnExit(); File(it.path + ".bak").deleteOnExit() }

    @Test fun aSaveHoldsOneTileAtATimeAndWritesTheSameFile() {
        val (contents, t, m) = streamed()
        val got = ByteArrayOutputStream().also { JbArchive.write(it, contents) }.toByteArray()
        assertTrue(t.most <= 1, "picture tiles out at once: ${t.most} of $count")
        assertTrue(m.most <= 1, "paint store tiles out at once: ${m.most} of ${2 * count}")
        assertEquals(count, t.made, "each picture tile made once")
        assertEquals(2 * count, m.made, "each store tile made once")
        val want = ByteArrayOutputStream().also { JbArchive.write(it, inMemory()) }.toByteArray()
        assertContentEquals(want, got, "the same file as a save of the same tiles held in memory")
    }

    @Test fun aSaveThatRewritesOnlyWhatChangedAlsoHoldsOneTileAtATime() {
        val f = temp()
        val (first, t1, m1) = streamed()
        val memo = JbArchive.saveCarrying(f, first, null)
        assertTrue(t1.most <= 1 && m1.most <= 1, "first save: ${t1.most} pictures, ${m1.most} stores out at once")
        val (again, t2, m2) = streamed()
        val memo2 = JbArchive.saveCarrying(f, again, memo)
        assertTrue(t2.most <= 1 && m2.most <= 1, "second save: ${t2.most} pictures, ${m2.most} stores out at once")
        assertEquals(1 + 1 + count + 2 * count, memo2.carried + memo2.fresh)
        assertEquals(count + 2 * count + 1, memo2.carried, "every tile and the document carried")
        val back = JbArchive.open(f)
        for (k in tileKeys) assertContentEquals(lookOf(k), back.tiles.getValue(k), "picture $k")
        for (k in mediaKeys) assertContentEquals(storeOf(k), back.mediaTiles.getValue(k), "store $k")
    }

    @Test fun aSaveWithTilesLeftUnreadTakesOnlyTheOnesItWasGiven() {
        val f = temp()
        val printOf = { i: Int -> cc.joycreator.joybrush.core.paint.TileFingerprint.Print(i.toLong(), i.toLong() * 3) }
        val prints = tileKeys.withIndex().associate { (i, k) -> k to printOf(i) }
        val mediaPrints = mediaKeys.withIndex().associate { (i, k) -> k to printOf(1000 + i) }
        val memo = JbArchive.saveCarrying(f, SparseContents(inMemory(), tilePrints = prints, mediaPrints = mediaPrints), null)
        // Ten picture tiles read back again; the rest left unread, to be taken from the file.
        val read = tileKeys.take(10)
        val t = Counting(read, { TILE_BYTES }, ::lookOf)
        val m = Counting(emptyList<MediaTileKey>(), { storeBytes }, ::storeOf)
        val sparse = SparseContents(JbContents(doc = doc(), tiles = t, strokes = emptyMap(), mediaTiles = m),
            tiles = (tileKeys - read.toSet()).associateWith { Unread(TILE_BYTES, prints.getValue(it)) },
            mediaTiles = mediaKeys.associateWith { Unread(storeBytes, mediaPrints.getValue(it)) },
            tilePrints = read.associateWith { prints.getValue(it) })
        JbArchive.saveCarrying(f, sparse, memo)
        assertEquals(10, t.made, "only the ten tiles read back are taken")
        assertTrue(t.most <= 1, "out at once: ${t.most}")
        val back = JbArchive.open(f)
        for (k in tileKeys) assertContentEquals(lookOf(k), back.tiles.getValue(k), "picture $k")
    }

    @Test fun checkingADrawingWithoutSavingItTakesNoTile() {
        val (contents, t, m) = streamed()
        JbArchive.requireStructure(contents)
        assertEquals(0, t.made + m.made, "lengths are enough to check a drawing")
    }
}

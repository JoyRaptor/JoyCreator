package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.test.*

/** [BoardSnapshot.captureSparse]: a tile that is known is not read, and the drawing lists what a whole capture lists. */
class BoardSnapshotSparseTest {
    private var serial = 0
    private fun ids() = "id-${++serial}"
    private fun drawing(): JbDocument {
        val base = DocOps.newDocument("doc", "Art", 512, 512, ::ids)
        val layer = base.layers.single()
        return base.copy(layers = listOf(layer.copy(mask = Cel("mask"))))
    }
    private fun tile(seed: Int) = ByteArray(TILE_BYTES) { ((it + seed) % 251).toByte() }
    private val fp = cc.joycreator.joybrush.core.paint.TileFingerprint
    private fun printOf(bytes: ByteArray) = if (bytes.size == TILE_BYTES) fp.ofBytes(bytes) else fp.ofHalves(bytes)
    private fun seenOf(bytes: ByteArray) = BoardSnapshot.Seen(Tiles.isBlank(bytes), Unread(bytes.size, printOf(bytes)))

    @Test fun knownTilesAreNotReadAndTheDocumentListsWhatAWholeCaptureLists() {
        val doc = drawing(); val layer = doc.layers.single().id; val cel = doc.layers.single().cels.single().id
        val keys = listOf(Tiles.key(0, 0), Tiles.key(1, 0), Tiles.key(2, 0), Tiles.key(3, 0))
        // 0_0 and 1_0 painted, 2_0 blank, 3_0 painted; the mask has a blank tile at 0_0 (kept: it hides).
        val bytes = mapOf(keys[0] to tile(1), keys[1] to tile(2), keys[2] to ByteArray(TILE_BYTES), keys[3] to tile(4))
        val mask = ByteArray(TILE_BYTES)
        val whole = BoardSnapshot.capture(doc, { _, c -> if (c == "mask") listOf(keys[0]) else keys }, { _, c, k -> if (c == "mask") mask else bytes[k] })
        val read = ArrayList<String>()
        // Known: 0_0 (painted), 2_0 (blank), the mask tile. Read: 1_0 and 3_0.
        val known = setOf(keys[0], keys[2])
        val sparse = BoardSnapshot.captureSparse(doc, { _, c -> if (c == "mask") listOf(keys[0]) else keys },
            { _, c, k -> read.add("$c/${Tiles.tx(k)}"); if (c == "mask") mask else bytes[k] },
            seen = { _, c, k -> if (c == "mask") seenOf(mask) else if (k in known) seenOf(bytes.getValue(k)) else null },
            print = { _, c, k -> printOf(if (c == "mask") mask else bytes.getValue(k)) })
        assertEquals(listOf("$cel/1", "$cel/3"), read.sorted())
        assertEquals(whole.doc, sparse.contents.doc, "the same tiles are listed, the blank one left out, the blank mask tile kept")
        assertEquals(setOf(Triple(layer, cel, "1_0"), Triple(layer, cel, "3_0")), sparse.contents.tiles.keys)
        assertEquals(setOf(Triple(layer, cel, "0_0"), Triple(layer, "mask", "0_0")), sparse.tiles.keys)
        assertEquals(whole.tiles.keys, sparse.contents.tiles.keys + sparse.tiles.keys)
        for ((k, u) in sparse.tiles) { assertEquals(printOf(whole.tiles.getValue(k)), u.print); assertEquals(TILE_BYTES, u.size) }
        // The tiles that were read come with the fingerprints they were given, and no others do.
        assertEquals(sparse.contents.tiles.keys, sparse.tilePrints.keys)
        for ((k, p) in sparse.tilePrints) assertEquals(printOf(whole.tiles.getValue(k)), p)
        assertFalse(sparse.whole)
    }

    @Test fun withNothingKnownItIsTheWholeCapture() {
        val doc = drawing()
        val sparse = BoardSnapshot.captureSparse(doc, { _, _ -> listOf(0L) }, { _, _, _ -> tile(7) })
        assertTrue(sparse.whole)
        assertEquals(BoardSnapshot.capture(doc, { _, _ -> listOf(0L) }, { _, _, _ -> tile(7) }).doc, sparse.contents.doc)
    }

    @Test fun paintStoreTilesGoTheSameWay() {
        val doc = drawing(); val layer = doc.layers.single().id; val cel = doc.layers.single().cels.single().id
        val size = cc.joycreator.joybrush.core.media.MediaStores.tileBytes(TILE_SIZE, "p0")
        val store = ByteArray(size) { (it % 7 + 1).toByte() }
        val read = ArrayList<Long>()
        val sparse = BoardSnapshot.captureSparse(doc, { _, _ -> emptyList() }, { _, _, _ -> null },
            mediaKeys = { _, c -> if (c == cel) mapOf("p0" to listOf(Tiles.key(0, 0), Tiles.key(1, 0), Tiles.key(2, 0))) else emptyMap() },
            mediaRead = { _, _, _, k -> read.add(k); store },
            mediaSeen = { _, _, _, k -> when (k) { Tiles.key(0, 0) -> seenOf(store); Tiles.key(2, 0) -> seenOf(ByteArray(size)); else -> null } })
        assertEquals(listOf(Tiles.key(1, 0)), read)
        assertEquals(setOf(MediaTileKey(layer, cel, "0_0", "p0")), sparse.mediaTiles.keys)
        assertEquals(setOf(MediaTileKey(layer, cel, "1_0", "p0")), sparse.contents.mediaTiles.keys)
        assertEquals(listOf("0_0", "1_0"), sparse.contents.doc.layers.single().cels.single().floatTiles, "the blank store tile is not listed")
    }
}

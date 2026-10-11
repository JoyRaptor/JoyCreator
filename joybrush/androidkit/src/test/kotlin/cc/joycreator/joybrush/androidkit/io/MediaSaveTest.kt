package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.media.HalfFloat
import cc.joycreator.joybrush.core.media.MediaStores
import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.test.*

/** Step 4a and R51: a layer's media payload is saved, merged and reopened whole, never refused, hidden or dropped. */
class MediaSaveTest {
    private var serial = 0
    private fun ids() = "id-${++serial}"

    /** One canvas with two ordinary layers, the second carrying media, as the canvas keeps it (one shared cel per layer). */
    private fun drawing(): JbDocument {
        val base = DocOps.newDocument("doc", "Art", 512, 512, ::ids)
        val paint = base.layers.single()
        val media = paint.copy(id = "wc", name = "Layer 2", cels = listOf(Cel("wc-cel")))
        return base.copy(layers = listOf(paint, media))
    }

    private val state = HalfFloat.encode(FloatArray(TILE_SIZE * TILE_SIZE * 4) { 0.25f })
    private val look = ByteArray(TILE_BYTES).also { it[3] = -1 }

    private fun capture(doc: JbDocument) = BoardSnapshot.capture(doc,
        keys = { layer, _ -> if (layer == "wc") listOf(Tiles.key(0, 0)) else emptyList() },
        read = { _, _, _ -> look },
        mediaKeys = { layer, _ -> if (layer == "wc") mapOf("p0" to listOf(Tiles.key(0, 0), Tiles.key(1, 0)), "w0" to listOf(Tiles.key(0, 0)),
            MediaStores.GROUND to listOf(Tiles.key(0, 0))) else emptyMap() },
        mediaRead = { _, _, store, key -> when {
            store == MediaStores.GROUND -> look
            key == Tiles.key(1, 0) -> ByteArray(MediaStores.tileBytes(TILE_SIZE))
            else -> state
        } })

    @Test fun aMediaLayerIsCapturedWithItsStateAndBlankStateIsLeftOut() {
        val snap = capture(drawing())
        val cel = snap.doc.layers.single { it.id == "wc" }.cels.single()
        assertEquals(listOf("0_0"), cel.floatTiles, "the all-zero tile holds nothing")
        assertEquals(setOf("p0", "w0", MediaStores.GROUND), snap.mediaTiles.keys.map { it.store }.toSet())
        assertEquals(LayerKind.PAINT, snap.doc.layers.single { it.id == "wc" }.kind, "an ordinary layer")
        assertEquals(listOf("0_0"), cel.tiles, "its look is an ordinary tile")
    }

    @Test fun itRoundTripsThroughTheFileAndReopensWet() {
        val snap = capture(drawing())
        val out = java.io.ByteArrayOutputStream(); JbArchive.write(out, snap)
        val back = JbArchive.read(java.io.ByteArrayInputStream(out.toByteArray()))
        assertEquals(LayerKind.PAINT, back.doc.layers.single { it.id == "wc" }.kind)
        assertContentEquals(state, back.mediaTiles.getValue(MediaTileKey("wc", "wc-cel", "0_0", "w0")))
        assertContentEquals(look, back.mediaTiles.getValue(MediaTileKey("wc", "wc-cel", "0_0", MediaStores.GROUND)))
    }

    @Test fun aLayerWithoutMediaSavesAsItAlwaysDid() {
        val snap = capture(drawing())
        val paint = snap.doc.layers.first()
        assertTrue(paint.cels.all { it.floatTiles.isEmpty() })
        assertTrue(snap.mediaTiles.keys.none { it.layerId == paint.id })
    }

    @Test fun aCelWhosePaintWasSettledSinceTheLastSaveListsNoMediaAndStillSaves() {
        // The owner's autosave failure (2026-10-11): the document still listed the tiles of its last save, the engine no
        // longer had a payload there (a plain write settled it), and the archive refused the listed tile with no state.
        val stale = drawing().let { d -> d.copy(layers = d.layers.map { l ->
            if (l.id == "wc") l.copy(cels = l.cels.map { it.copy(floatTiles = listOf("-1_-1")) }) else l }) }
        val snap = BoardSnapshot.capture(stale, keys = { layer, _ -> if (layer == "wc") listOf(Tiles.key(0, 0)) else emptyList() },
            read = { _, _, _ -> look })
        assertTrue(snap.doc.layers.single { it.id == "wc" }.cels.single().floatTiles.isEmpty(), "a stale list is dropped")
        val out = java.io.ByteArrayOutputStream(); JbArchive.write(out, snap)   // threw before the fix
        assertTrue(out.size() > 0)
    }

    @Test fun theMergeKeepsTheStateUnderTheSavedCelIds() {
        val saved = capture(drawing())
        val retained = CanvasSnapshot.metadataOf(saved)
        assertTrue(retained.mediaTiles.isEmpty(), "metadata holds no second copy of the state")
        // The live readback names its cels freshly; the merge puts them back under the saved ids.
        val live = saved.doc.copy(layers = saved.doc.layers.map { l -> l.copy(cels = l.cels.map { it.copy(id = it.id + "-live") }) })
        val fresh = JbContents(live, saved.tiles.mapKeys { (k, _) -> Triple(k.first, k.second + "-live", k.third) }, emptyMap(), null,
            saved.mediaTiles.mapKeys { (k, _) -> k.copy(celId = k.celId + "-live") })
        val merged = CanvasSnapshot.merge(retained, fresh)
        assertEquals(saved.mediaTiles.keys, merged.mediaTiles.keys)
        assertEquals(listOf("0_0"), merged.doc.layers.single { it.id == "wc" }.cels.single().floatTiles)
    }
}

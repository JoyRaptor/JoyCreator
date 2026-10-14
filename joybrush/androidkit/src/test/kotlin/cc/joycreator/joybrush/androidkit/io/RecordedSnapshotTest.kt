package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.brush.*
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.media.MediaStores
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.RecordedCel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.*

class RecordedSnapshotTest {
    private var serial = 0
    private fun ids() = "id-${++serial}"
    private val brush = BrushPreset(id = "frozen-pen", name = "Pen", size = Param(4f))
    private val brushes get() = mapOf(brush.id to brush)
    private fun drawing() = DocOps.newDocument("doc", "Art", 128, 128, ::ids)
    private fun bytes(value: Int) = ByteArray(TILE_BYTES).also { it[0] = value.toByte(); it[3] = -1 }
    private fun line(seq: Long = 1L, id: String = "same") = StrokeRecord(id, brush.id, 81L, 0f, 1f,
        listOf(PenSample(20f, 20f, 0.0), PenSample(24f, 20f, 16.0)), seq = seq)
    private fun slab(seq: Long, texture: Int, op: String = "normal") =
        RecordedCel.PixelSlab(seq, UndoLog.SlabValue(texture, op))
    private fun capture(doc: JbDocument, planes: Map<Pair<String, String?>, RecordedCel<Int>>,
        expected: Map<Pair<String, String?>, List<Long>> = planes.filterValues { it.records.isNotEmpty() }
            .mapValues { emptyList<Long>() },
        readTexture: (Int) -> ByteArray? = { bytes(it) },
        lookRead: (String, String?, Long) -> ByteArray? = { _, _, _ -> bytes(99) }) =
        RecordedSnapshot.capture(doc, planes, brushes, readTexture,
            { _, _ -> emptyList() }, { _, _, _ -> error("projection read") }, expected, lookRead)

    @Test fun sameRecordIdsStayScopedAcrossSharedFrameAndOtherLayer() {
        val base = drawing()
        val regional = RegionDocumentOps.create(base, "Loop", RectPx(0, 0, 64, 64), ::ids).doc
        val layer = regional.layers.single()
        val shared = layer.sharedCelId!!
        val frame = layer.cels.first { it.id != shared }.id
        val other = Layer("other", "Other", LayerKind.PAINT, cels = listOf(Cel("other-cel")))
        val doc = regional.copy(layers = regional.layers + other)
        val planes: Map<Pair<String, String?>, RecordedCel<Int>> = mapOf(
            (layer.id to null) to RecordedCel<Int>(layer.id, null, listOf(line())),
            (layer.id to frame) to RecordedCel<Int>(layer.id, frame, listOf(line().copy(seed = 82))),
            (other.id to null) to RecordedCel<Int>(other.id, null, listOf(line().copy(seed = 83))),
        )
        val result = capture(doc, planes)
        assertEquals(3, result.strokes.size)
        assertEquals(81L, result.strokes.getValue(layer.id to shared).single().seed)
        assertEquals(82L, result.strokes.getValue(layer.id to frame).single().seed)
        assertEquals(83L, result.strokes.getValue(other.id to "other-cel").single().seed)
        for ((address, _) in result.strokes) {
            val cel = result.doc.layers.first { it.id == address.first }.cels.first { it.id == address.second }
            assertEquals("layers/${address.first}/${address.second}/strokes.jbs", cel.strokesFile)
        }
    }

    @Test fun orderedSlabsOperatorsLooksAndFrozenBrushRoundTrip() {
        val doc = drawing()
        val layer = doc.layers.single()
        val cel = layer.cels.single()
        val plane = RecordedCel(layer.id, null, listOf(line(3, "later"), line()),
            mapOf(0L to listOf(slab(4, 44, "erase"), slab(0, 11), slab(2, 22, "multiply")),
                Tiles.key(1, 0) to listOf(slab(0, 55))))
        val result = capture(doc, mapOf((layer.id to null) to plane), mapOf((layer.id to null) to listOf(0L)))
        assertEquals(listOf(1L, 3L), result.strokes.getValue(layer.id to cel.id).map { it.seq })
        assertEquals(listOf(SlabRef("0_0", 0), SlabRef("0_0", 2, "multiply"), SlabRef("0_0", 4, "erase")),
            result.doc.layers.single().cels.single().slabs)
        assertContentEquals(bytes(11), result.tiles.getValue(Triple(layer.id, cel.id, "0_0")))
        assertContentEquals(bytes(22), result.slabs.getValue(SlabKey(layer.id, cel.id, "0_0", 2)))
        assertEquals(listOf("0_0"), result.doc.layers.single().cels.single().looks)
        val output = ByteArrayOutputStream().also { JbArchive.write(it, result) }.toByteArray()
        val loaded = JbArchive.read(ByteArrayInputStream(output))
        assertEquals(result.doc, loaded.doc)
        assertEquals(result.strokes, loaded.strokes)
        assertEquals(result.brushes, loaded.brushes)
        for ((key, value) in result.tiles) assertContentEquals(value, loaded.tiles.getValue(key))
        for ((key, value) in result.slabs) assertContentEquals(value, loaded.slabs.getValue(key))
        for ((key, value) in result.looks) assertContentEquals(value, loaded.looks.getValue(key))
    }

    @Test fun missingAuthoritativeReadsRefuseWithoutChangingCallerState() {
        val doc = drawing()
        val layer = doc.layers.single()
        val plane = RecordedCel(layer.id, null, listOf(line()), mapOf(0L to listOf(slab(0, 1), slab(2, 2))))
        val planes: Map<Pair<String, String?>, RecordedCel<Int>> = mapOf((layer.id to null) to plane)
        assertFailsWith<JbArchiveException> { capture(doc, planes, readTexture = { if (it == 2) null else bytes(it) }) }
        assertFailsWith<JbArchiveException> {
            capture(doc, planes, mapOf((layer.id to null) to listOf(0L)), lookRead = { _, _, _ -> null })
        }
        assertEquals(listOf(0L, 2L), plane.slabs.getValue(0L).map { it.seq })
        assertEquals(listOf(line()), plane.records)
        assertTrue(doc.layers.single().cels.single().slabs.isEmpty())
    }

    @Test fun invalidPlaneAndLookIdentitiesRefuseBeforeAnyRead() {
        val doc = drawing()
        val layer = doc.layers.single()
        val invalid: List<Map<Pair<String, String?>, RecordedCel<Int>>> = listOf(
            mapOf(("missing" to null) to RecordedCel<Int>("missing", null)),
            mapOf((layer.id to null) to RecordedCel<Int>("different", null)),
            mapOf((layer.id to "missing") to RecordedCel<Int>(layer.id, "missing")),
            mapOf((layer.id to layer.cels.single().id) to RecordedCel<Int>(layer.id, layer.cels.single().id)),
        )
        for (planes in invalid) assertFailsWith<IllegalArgumentException> {
            RecordedSnapshot.capture<Int>(doc, planes, brushes, { _: Int -> error("texture read") },
                { _, _ -> error("keys read") }, { _, _, _ -> error("plain read") })
        }
        assertFailsWith<IllegalArgumentException> {
            capture(doc, mapOf((layer.id to null) to RecordedCel<Int>(layer.id, null)),
                mapOf((layer.id to null) to listOf(0L)), readTexture = { error("read") })
        }
    }

    @Test fun missingFrozenReferenceRefusesBeforeReading() {
        val doc = drawing()
        val layer = doc.layers.single()
        val plane = RecordedCel<Int>(layer.id, null, listOf(line().copy(brushId = "library-only")))
        assertFailsWith<IllegalArgumentException> {
            capture(doc, mapOf((layer.id to null) to plane), readTexture = { error("read") })
        }
    }

    @Test fun plainPixelBaselineUsesIdenticalSnapshotAndArchiveContent() {
        val original = drawing()
        val doc = original.copy(layers = original.layers.map { it.copy(mask = Cel("mask")) })
        val keys: (String, String) -> List<Long> = { _, _ -> listOf(0L) }
        val read: (String, String, Long) -> ByteArray? = { _, cel, _ -> if (cel == "mask") ByteArray(TILE_BYTES) else bytes(12) }
        val baseline = BoardSnapshot.capture(doc, keys, read)
        val recorded = RecordedSnapshot.capture<Int>(doc, emptyMap(), emptyMap(), { error("texture") }, keys, read)
        assertEquals(baseline.doc, recorded.doc)
        assertEquals(baseline.tiles.keys, recorded.tiles.keys)
        for ((key, value) in baseline.tiles) assertContentEquals(value, recorded.tiles.getValue(key))
        assertTrue(recorded.strokes.isEmpty() && recorded.slabs.isEmpty() && recorded.looks.isEmpty() && recorded.brushes.isEmpty())
        val a = LineArchiveFixture.entries(LineArchiveFixture.encoded(baseline))
        val b = LineArchiveFixture.entries(LineArchiveFixture.encoded(recorded))
        assertEquals(a.map { it.first }, b.map { it.first })
        for (index in a.indices) assertContentEquals(a[index].second, b[index].second)
    }

    @Test fun recordedBranchRetainsZeroMaskAndClearsStaleMediaAndLooks() {
        val original = drawing()
        val layer = original.layers.single()
        val cel = layer.cels.single().copy(floatTiles = listOf("0_0"))
        val doc = original.copy(layers = listOf(layer.copy(cels = listOf(cel), mask = Cel("mask"))))
        val plane = RecordedCel<Int>(layer.id, null, listOf(line()))
        val result = RecordedSnapshot.capture(doc, mapOf((layer.id to null) to plane), brushes, { bytes(it) },
            { _, id -> if (id == "mask") listOf(0L) else emptyList() }, { _, _, _ -> ByteArray(TILE_BYTES) },
            expectedLookKeys = mapOf((layer.id to null) to emptyList()))
        assertContentEquals(ByteArray(TILE_BYTES), result.tiles.getValue(Triple(layer.id, "mask", "0_0")))
        assertTrue(result.doc.layers.single().cels.single().floatTiles.isEmpty())
        assertTrue(result.doc.layers.single().cels.single().looks.isEmpty())
        assertTrue(result.mediaTiles.isEmpty())
    }

    @Test fun mediaReadbackUsesPhysicalDocumentCelAndCopiesActualPayload() {
        val doc = drawing()
        val layer = doc.layers.single()
        val cel = layer.cels.single()
        val plane = RecordedCel<Int>(layer.id, null, listOf(line()))
        val payload = ByteArray(MediaStores.tileBytes(TILE_SIZE, MediaStores.GROUND)).also { it[3] = -1 }
        val result = RecordedSnapshot.capture(doc, mapOf((layer.id to null) to plane), brushes, { bytes(it) },
            { _, _ -> emptyList() }, { _, _, _ -> null },
            expectedLookKeys = mapOf((layer.id to null) to emptyList()),
            mediaKeys = { owner, physical ->
                assertEquals(layer.id, owner); assertEquals(cel.id, physical)
                mapOf(MediaStores.GROUND to listOf(0L))
            }, mediaRead = { owner, physical, store, _ ->
                assertEquals(layer.id, owner); assertEquals(cel.id, physical)
                assertEquals(MediaStores.GROUND, store); payload
            })
        val expected = payload.copyOf()
        payload.fill(0)
        assertContentEquals(expected, result.mediaTiles.getValue(MediaTileKey(layer.id, cel.id, "0_0", MediaStores.GROUND)))
        assertEquals(listOf("0_0"), result.doc.layers.single().cels.single().floatTiles)
        assertFailsWith<JbArchiveException> {
            RecordedSnapshot.capture(doc, mapOf((layer.id to null) to plane), brushes, { bytes(it) },
                { _, _ -> emptyList() }, { _, _, _ -> null },
                expectedLookKeys = mapOf((layer.id to null) to emptyList()),
                mediaKeys = { _, _ -> mapOf(MediaStores.GROUND to listOf(0L)) })
        }
    }

    @Test fun omittedLookIndexRefusesBeforeEveryCallbackButExplicitEmptyIndexAllowsNoDabs() {
        val doc = drawing()
        val layer = doc.layers.single()
        val plane = RecordedCel<Int>(layer.id, null, listOf(line().copy(samples = emptyList())))
        val planes: Map<Pair<String, String?>, RecordedCel<Int>> = mapOf((layer.id to null) to plane)
        var callbacks = 0
        assertFailsWith<IllegalArgumentException> {
            RecordedSnapshot.capture(doc, planes, brushes, { callbacks++; bytes(it) },
                { _, _ -> callbacks++; emptyList() }, { _, _, _ -> callbacks++; null },
                lookRead = { _, _, _ -> callbacks++; null },
                mediaKeys = { _, _ -> callbacks++; emptyMap() },
                mediaRead = { _, _, _, _ -> callbacks++; null })
        }
        assertEquals(0, callbacks)
        val result = RecordedSnapshot.capture(doc, planes, brushes, { error("no textures") },
            { _, _ -> error("no plain pixels") }, { _, _, _ -> error("no projection") },
            expectedLookKeys = mapOf((layer.id to null) to emptyList()),
            lookRead = { _, _, _ -> error("empty authoritative index has no look read") })
        assertTrue(result.looks.isEmpty())
        assertEquals(plane.records, result.strokes.getValue(layer.id to layer.cels.single().id))
    }

    @Test fun unsupportedAnimatedLayerRefusesBeforeCallbacks() {
        val original = drawing()
        val board = original.boards.single().copy(kind = BoardKind.ANIMATION, frames = listOf(Frame("frame")))
        val layer = original.layers.single().copy(animatedIn = board.id,
            frameCel = mapOf("frame" to original.layers.single().cels.single().id))
        val doc = original.copy(boards = listOf(board), layers = listOf(layer))
        assertTrue(DocOps.validate(doc).isEmpty())
        var callbacks = 0
        assertFailsWith<IllegalArgumentException> {
            RecordedSnapshot.capture<Int>(doc, emptyMap(), emptyMap(), { callbacks++; null },
                { _, _ -> callbacks++; emptyList() }, { _, _, _ -> callbacks++; null })
        }
        assertEquals(0, callbacks)
    }
}

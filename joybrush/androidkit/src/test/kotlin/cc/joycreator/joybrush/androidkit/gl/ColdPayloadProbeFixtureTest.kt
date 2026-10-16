package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.androidkit.io.*
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.media.HalfFloat
import cc.joycreator.joybrush.core.media.MediaStores
import cc.joycreator.joybrush.core.doc.TILE_SIZE
import cc.joycreator.joybrush.core.stroke.CaptureDomain
import cc.joycreator.joybrush.core.stroke.CaptureRect
import cc.joycreator.joybrush.core.stroke.CapturedPhysicalSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.*

class ColdPayloadProbeFixtureTest {
    private fun source(): JbContents {
        val base = LineArchiveFixture.contents()
        val native = Layer("native-plane", "Native", LayerKind.PAINT,
            cels = listOf(Cel("native-cel", tiles = listOf("-1_2"))))
        return base.copy(doc = base.doc.copy(layers = base.doc.layers + native),
            tiles = base.tiles + (Triple("native-plane", "native-cel", "-1_2") to LineArchiveFixture.tile(21)))
    }

    private fun roundTrip(c: JbContents): JbContents = ByteArrayOutputStream().use { out ->
        JbArchive.write(out, c)
        JbArchive.read(ByteArrayInputStream(out.toByteArray()))
    }

    private fun snapshot(c: JbContents) = c.copy(
        tiles = c.tiles.mapValues { it.value.copyOf() }, mediaTiles = c.mediaTiles.mapValues { it.value.copyOf() },
        slabs = c.slabs.mapValues { it.value.copyOf() }, looks = c.looks.mapValues { it.value.copyOf() },
        strokes = c.strokes.mapValues { (_, rs) -> rs.toList() })

    @Test fun fixtureRoundTripsAndKeepsOriginalMapsAndPayloadsUntouched() {
        val input = source()
        val before = snapshot(input)
        val prepared = ColdPayloadProbeFixture.enrich(input)
        val enriched = prepared.contentsCopy()
        val back = roundTrip(enriched)
        assertEquals(listOf(1L,3L),enriched.strokes.getValue("l" to "c").map { it.seq })
        assertEquals(listOf(3L,1L),input.strokes.getValue("l" to "c").map { it.seq })
        assertEquals(enriched.doc, back.doc)
        assertEquals(enriched.strokes, back.strokes)
        for ((key, bytes) in enriched.tiles) assertContentEquals(bytes, back.tiles.getValue(key))
        for ((key, bytes) in enriched.slabs) assertContentEquals(bytes, back.slabs.getValue(key))
        for ((key, bytes) in enriched.looks) assertContentEquals(bytes, back.looks.getValue(key))
        for ((key, bytes) in enriched.mediaTiles) assertContentEquals(bytes, back.mediaTiles.getValue(key))
        assertEquals(before.doc, input.doc)
        assertEquals(before.tiles.keys, input.tiles.keys)
        assertEquals(before.slabs.keys, input.slabs.keys)
        assertEquals(before.looks.keys, input.looks.keys)
        assertEquals(before.mediaTiles.keys, input.mediaTiles.keys)
        for ((key, bytes) in before.tiles) assertContentEquals(bytes, input.tiles.getValue(key))
        for ((key, bytes) in before.slabs) assertContentEquals(bytes, input.slabs.getValue(key))
        for ((key, bytes) in before.looks) assertContentEquals(bytes, input.looks.getValue(key))
        for ((key, bytes) in before.mediaTiles) assertContentEquals(bytes, input.mediaTiles.getValue(key))
        assertEquals(before.strokes, input.strokes)
        assertEquals(before.thumbnailPng?.toList(), input.thumbnailPng?.toList())
    }

    @Test fun savedLookOutsideBottomManifestUsesFirstSlabTileStorageAndSharedCelRoundTrips() {
        val base = source()
        val originalLayer = base.doc.layers.single { it.id == "l" }
        val originalCel = originalLayer.cels.single()
        val savedLookKey = "2_1"
        assertTrue(savedLookKey !in originalCel.tiles)
        assertTrue(savedLookKey !in originalCel.slabs.map { it.key })
        val savedLook = LineArchiveFixture.tile(42)
        val cel = originalCel.copy(looks = listOf(savedLookKey))
        val doc = base.doc.copy(layers = base.doc.layers.map { layer ->
            if (layer.id == originalLayer.id) layer.copy(sharedCelId = cel.id, cels = listOf(cel)) else layer
        })
        val input = base.copy(doc = doc,
            looks = mapOf(Triple(originalLayer.id, cel.id, savedLookKey) to savedLook.copyOf()),
            strokes = base.strokes.mapValues { (_, records) -> records.map { record ->
                record.copy(samples = record.samples.map { it.copy(x = it.x + 512f, y = it.y + 256f) })
            } })

        val prepared = ColdPayloadProbeFixture.enrich(input)
        val enriched = prepared.contentsCopy()
        val outputCel = enriched.doc.layers.single { it.id == originalLayer.id }.cels.single()
        assertEquals(cel.id, enriched.doc.layers.single { it.id == originalLayer.id }.sharedCelId)
        assertTrue(savedLookKey in outputCel.tiles)
        assertEquals(listOf(SlabRef(savedLookKey, 4L, "normal")),
            outputCel.slabs.filter { it.key == savedLookKey })
        assertContentEquals(prepared.expected.last().bytesCopy(),
            enriched.tiles.getValue(Triple(originalLayer.id, cel.id, savedLookKey)))
        assertFalse(SlabKey(originalLayer.id, cel.id, savedLookKey, 4L) in enriched.slabs)

        val archived = roundTrip(enriched)
        val roundTripLayer = archived.doc.layers.single { it.id == originalLayer.id }
        val roundTripCel = roundTripLayer.cels.single()
        assertEquals(cel.id, roundTripLayer.sharedCelId)
        assertTrue(savedLookKey in roundTripCel.tiles)
        assertEquals(outputCel.slabs, roundTripCel.slabs)
        assertContentEquals(enriched.tiles.getValue(Triple(originalLayer.id, cel.id, savedLookKey)),
            archived.tiles.getValue(Triple(originalLayer.id, cel.id, savedLookKey)))
        assertContentEquals(prepared.expected.last().bytesCopy(),
            archived.looks.getValue(Triple(originalLayer.id, cel.id, savedLookKey)))
        val cold = ColdRecordedLoadPlan.prepare(archived)
        val firstSlab = cold.recordedPlanes.single().slabs.single {
            it.key == cc.joycreator.joybrush.core.paint.Tiles.key(2,1) }
        assertEquals(4L, firstSlab.seq)
        assertContentEquals(prepared.expected.last().bytesCopy(), cold.copyPayload(firstSlab.id))
        val upper = roundTripCel.slabs.single { it.key == savedLookKey && it.seq == 4L }
        assertEquals(4L, upper.seq)
        assertFalse(SlabKey(originalLayer.id, cel.id, savedLookKey, upper.seq) in archived.slabs)

        // No bottom source existed: the generated seq-4 first slab is stored in the .rgba tile entry,
        // not an invented seq-zero base plus an upper .s4 entry.
        assertTrue(savedLookKey !in input.doc.layers.single { it.id == originalLayer.id }.cels.single().tiles)
        assertEquals(listOf("0_0"), input.doc.layers.single { it.id == originalLayer.id }.cels.single().tiles)
        assertFalse(Triple(originalLayer.id, cel.id, savedLookKey) in input.tiles)
    }

    @Test fun expectedDescriptorsAreExactAndHalfFloatsAreFiniteAndNotByteQuantized() {
        val p = ColdPayloadProbeFixture.enrich(source())
        val expected = p.expected
        assertEquals(listOf("MASK_RGBA8", "MEDIA_RGBA16F", "GROUND_RGBA8", "SOURCE_SLAB_RGBA8"), expected.map { it.category })
        assertEquals(listOf(
            ColdPayloadProbeFixture.Address("native-plane", ColdPayloadProbeFixture.MASK_CEL, ColdPayloadProbeFixture.MASK_TILE),
            ColdPayloadProbeFixture.Address(ColdPayloadProbeFixture.MEDIA_LAYER, ColdPayloadProbeFixture.MEDIA_CEL,
                ColdPayloadProbeFixture.MEDIA_TILE, "p0"),
            ColdPayloadProbeFixture.Address(ColdPayloadProbeFixture.MEDIA_LAYER, ColdPayloadProbeFixture.MEDIA_CEL,
                ColdPayloadProbeFixture.MEDIA_TILE, MediaStores.GROUND),
            ColdPayloadProbeFixture.Address("l", "c", "0_0", seq = 4L),
        ), expected.map { it.address })
        val floatBytes = expected[1].bytesCopy()
        assertEquals(MediaStores.tileBytes(TILE_SIZE, "p0"), floatBytes.size)
        val decoded = HalfFloat.decode(floatBytes)
        assertTrue(decoded.all { it.isFinite() })
        assertTrue(decoded.any { (it * 255f).let { v -> v != kotlin.math.round(v) } })
        val enriched = p.contentsCopy()
        assertContentEquals(expected[0].bytesCopy(), enriched.tiles.getValue(Triple("native-plane", ColdPayloadProbeFixture.MASK_CEL,
            ColdPayloadProbeFixture.MASK_TILE)))
        assertContentEquals(expected[2].bytesCopy(), enriched.mediaTiles.getValue(MediaTileKey(ColdPayloadProbeFixture.MEDIA_LAYER,
            ColdPayloadProbeFixture.MEDIA_CEL, ColdPayloadProbeFixture.MEDIA_TILE, MediaStores.GROUND)))
        assertContentEquals(expected[1].bytesCopy(), enriched.mediaTiles.getValue(MediaTileKey(ColdPayloadProbeFixture.MEDIA_LAYER,
            ColdPayloadProbeFixture.MEDIA_CEL, ColdPayloadProbeFixture.MEDIA_TILE, "p0")))
        val cel = enriched.doc.layers.single { it.id == "l" }.cels.single()
        val upper = cel.slabs.single { it.seq == 4L }
        assertEquals("normal", upper.op)
        val upperBytes = enriched.slabs.getValue(SlabKey("l", "c", "0_0", upper.seq))
        assertContentEquals(upperBytes, enriched.looks.getValue(Triple("l", "c", "0_0")))
        assertTrue((0 until TILE_BYTES step 4).all { upperBytes[it + 3] == 0xff.toByte() })
    }

    @Test fun upperSequenceUsesCheckedGlobalMaximumAndOverflowRefuses() {
        val base = source()
        val p = ColdPayloadProbeFixture.enrich(base)
        assertEquals(4L, p.expected.last().address.seq)
        val maximal = base.copy(strokes = base.strokes.mapValues { (_, rs) -> rs.map { it.copy(seq = Long.MAX_VALUE) } })
        assertFailsWith<IllegalArgumentException> { ColdPayloadProbeFixture.enrich(maximal) }
    }

    @Test fun multiTileExplicitSharedPromotionProfilePassesColdAdmission() {
        val base = source()
        val layer = base.doc.layers.single { it.id == "l" }
        val cel = layer.cels.single().copy(
            tiles = listOf("-1_2", "0_0", "1_0", "2_0"),
            slabs = layer.cels.single().slabs + listOf(
                SlabRef("-1_2", 0), SlabRef("-1_2", 2, "multiply"),
                SlabRef("1_0", 0), SlabRef("1_0", 2, "screen"),
                SlabRef("2_0", 0), SlabRef("2_0", 2, "behind")),
            looks = listOf("-1_2", "0_0", "1_0", "2_0"))
        val expanded = base.copy(doc = base.doc.copy(layers = base.doc.layers.map {
            if (it.id == "l") it.copy(sharedCelId = cel.id, cels = listOf(cel)) else it
        }), tiles = base.tiles + mapOf(
            Triple("l", "c", "-1_2") to LineArchiveFixture.tile(30),
            Triple("l", "c", "1_0") to LineArchiveFixture.tile(31),
            Triple("l", "c", "2_0") to LineArchiveFixture.tile(36)),
            slabs = base.slabs + mapOf(
                SlabKey("l", "c", "-1_2", 2) to LineArchiveFixture.tile(32),
                SlabKey("l", "c", "1_0", 2) to LineArchiveFixture.tile(33),
                SlabKey("l", "c", "2_0", 2) to LineArchiveFixture.tile(37)),
            looks = base.looks + mapOf(
                Triple("l", "c", "-1_2") to LineArchiveFixture.tile(34),
                Triple("l", "c", "1_0") to LineArchiveFixture.tile(35),
                Triple("l", "c", "2_0") to LineArchiveFixture.tile(38)))
        val prepared = ColdPayloadProbeFixture.enrich(expanded)
        assertEquals("-1_2", prepared.expected.last().address.key)
        val enriched = prepared.contentsCopy()
        val cold = ColdRecordedLoadPlan.prepare(enriched)
        assertEquals(1, cold.recordedPlanes.size)
        assertEquals(2, cold.nativePlanes.size)
        assertEquals(1, cold.masks.size)
        assertEquals(setOf("p0", MediaStores.GROUND), cold.nativePlanes.single { it.documentCelId == ColdPayloadProbeFixture.MEDIA_CEL }
            .media.mapNotNull { it.store }.toSet())
        assertEquals(4, cold.recordedPlanes.single().savedLookKeys.size)
        assertEquals(9, cold.recordedPlanes.single().slabs.size)
    }

    @Test fun preparedPayloadsAndExpectedDescriptorsResistCallerMutation() {
        val input = source()
        val prepared = ColdPayloadProbeFixture.enrich(input)
        val expected = prepared.expected
        val expectedBytes = expected.first().bytesCopy()
        expectedBytes.fill(0)
        assertContentEquals(ColdPayloadProbeFixture.enrich(source()).expected.first().bytesCopy(), expected.first().bytesCopy())
        assertFails { (expected as MutableList<ColdPayloadProbeFixture.ExpectedPayload>).clear() }
        val first = prepared.contentsCopy()
        first.tiles.getValue(Triple("native-plane", "native-cel", "-1_2")).fill(0)
        first.tiles.getValue(Triple("native-plane", ColdPayloadProbeFixture.MASK_CEL, ColdPayloadProbeFixture.MASK_TILE)).fill(0)
        first.mediaTiles.values.forEach { it.fill(0) }
        val second = prepared.contentsCopy()
        assertContentEquals(LineArchiveFixture.tile(21), second.tiles.getValue(Triple("native-plane", "native-cel", "-1_2")))
        assertContentEquals(expected.first().bytesCopy(), second.tiles.getValue(Triple("native-plane", ColdPayloadProbeFixture.MASK_CEL,
            ColdPayloadProbeFixture.MASK_TILE)))
        assertTrue(second.mediaTiles.values.any { it.any { byte -> byte != 0.toByte() } })
        input.tiles.values.first().fill(0)
        assertContentEquals(expected.first().bytesCopy(), prepared.expected.first().bytesCopy())
        assertContentEquals(LineArchiveFixture.tile(5), prepared.contentsCopy().tiles.getValue(Triple("l", "c", "0_0")))
    }

    @Test fun conflictingOrAmbiguousProfilesFailClosed() {
        val base = source()
        val native = base.doc.layers.single { it.id == "native-plane" }
        val cases = listOf(
            base.copy(doc = base.doc.copy(layers = base.doc.layers.map { if (it.id == native.id) it.copy(mask = Cel("existing")) else it })),
            base.copy(mediaTiles = mapOf(MediaTileKey("native-plane", "native-cel", "-1_2", "g") to ByteArray(TILE_BYTES))),
            base.copy(doc = base.doc.copy(layers = base.doc.layers + native.copy(id = ColdPayloadProbeFixture.PREFIX + "collision"))),
            base.copy(looks = emptyMap()),
            base.copy(doc = base.doc.copy(layers = base.doc.layers.map { if (it.id == "l") it.copy(cels = it.cels.map { c -> c.copy(floatTiles = listOf("0_0")) }) else it })),
            base.copy(strokes = base.strokes.mapValues { (_, rs) -> rs.map { it.copy(capturedPhysicalSource =
                CapturedPhysicalSource(CaptureDomain(CaptureRect(0, 0, 8, 8)))) } }),
        )
        cases.forEach { assertFailsWith<IllegalArgumentException> { ColdPayloadProbeFixture.enrich(it) } }
    }
}

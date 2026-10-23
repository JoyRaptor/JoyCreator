package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.brush.*
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.media.MediaStores
import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.test.*

class ColdRecordedLoadPlanTest {
    private val address = ColdRecordedLoadPlan.Address("l", null)
    private fun mixed() = LineArchiveFixture.contents()
    private fun tile(n: Int = 9) = LineArchiveFixture.tile(n)
    private fun replaceCel(c: JbContents, cel: Cel) = c.copy(doc = c.doc.copy(layers = c.doc.layers.map { it.copy(cels = listOf(cel)) }))
    private fun native(): JbContents {
        val c = mixed()
        return c.copy(doc = c.doc.copy(brushes = emptyList(), layers = c.doc.layers.map { it.copy(cels = listOf(Cel("c", tiles = listOf("0_0")))) }),
            strokes = emptyMap(), slabs = emptyMap(), looks = emptyMap(), brushes = emptyMap())
    }
    private fun regional(): JbContents {
        val c = native()
        val region = Board("region", "Region", BoardKind.ANIMATION, RectPx(5, 7, 80, 90),
            frames = listOf(Frame("f0"), Frame("f1"), Frame("f2"), Frame("linked")), currentFrameId = "f0")
        val layer = c.doc.layers.single().copy(sharedCelId = "shared", cels = listOf(
            Cel("shared", tiles = listOf("0_0")), Cel("active", tiles = listOf("0_0")),
            Cel("inactive", tiles = listOf("0_0")), Cel("empty")),
            regions = listOf(RegionFrames("region", mapOf("f0" to "active", "f1" to "inactive", "f2" to "empty", "linked" to "active"))))
        return c.copy(doc = c.doc.copy(boards = c.doc.boards + region, layers = listOf(layer)),
            tiles = mapOf(Triple("l", "shared", "0_0") to tile(1), Triple("l", "active", "0_0") to tile(2),
                Triple("l", "inactive", "0_0") to tile(3)))
    }
    private fun failure(c: JbContents): String = assertFailsWith<JbArchiveException> { ColdRecordedLoadPlan.prepare(c) }.message.orEmpty()

    @Test fun fullSlabStackKeepsBottomAndUpperSequenceOperatorAndBytes() {
        val c = mixed()
        val p = ColdRecordedLoadPlan.prepare(c)
        val plane = p.recordedPlanes.single()
        assertEquals(address, plane.address)
        assertEquals("c", plane.documentCelId)
        assertEquals(listOf(0L, 2L), plane.slabs.map { it.seq })
        assertEquals(listOf("normal", "multiply"), plane.slabs.map { it.op })
        assertContentEquals(c.tiles.values.single(), p.copyPayload(plane.slabs[0].id))
        assertContentEquals(c.slabs.values.single(), p.copyPayload(plane.slabs[1].id))
        assertEquals(listOf(Tiles.key(0, 0)), plane.savedLookKeys)
        assertEquals(ColdRecordedLoadPlan.Kind.SAVED_LINE_LOOK_RGBA8, plane.savedLooks.single().kind)
        assertTrue(plane.nativeTiles.isEmpty())
        assertEquals(listOf(1L, 3L), p.recordsCopy(address).map { it.seq })
        assertEquals(2L, p.summary.recordCount)
        assertEquals(1, p.summary.unresolvedLinePlanes)
        assertFalse(p.summary.dynamicReplayValidated)
        assertEquals(p.uploads.sumOf { it.byteCount.toLong() }, p.summary.uploadBytes)
        assertEquals(p.uploads.size, p.uploads.map { it.id }.toSet().size)
    }

    @Test fun nonzeroBottomSequenceRemainsSourceRatherThanBeingNormalized() {
        val c = mixed()
        val cel = c.doc.layers.single().cels.single().copy(slabs = listOf(SlabRef("0_0", 2, "behind"), SlabRef("0_0", 4, "screen")))
        val p = ColdRecordedLoadPlan.prepare(replaceCel(c, cel).copy(slabs = mapOf(SlabKey("l", "c", "0_0", 4) to tile(31))))
        assertEquals(listOf(2L, 4L), p.recordedPlanes.single().slabs.map { it.seq })
        assertEquals(listOf("behind", "screen"), p.recordedPlanes.single().slabs.map { it.op })
    }

    @Test fun sharedAliasAndAllCurrentInactiveLinkedEmptyCelsMapOnce() {
        val p = ColdRecordedLoadPlan.prepare(regional())
        assertEquals(setOf(null, "active", "inactive", "empty"), p.planes.map { it.address.celId }.toSet())
        assertEquals(4, p.planes.size) // linked frame is a reference, not a fifth allocation owner.
        assertEquals("shared", p.planes.single { it.address.celId == null }.documentCelId)
        assertEquals(0, p.planes.single { it.address.celId == "empty" }.nativeTiles.size)
        assertEquals(3, p.uploads.size)
        assertEquals(setOf(1, 2, 3), p.uploads.map { p.copyPayload(it.id)[0].toInt() }.toSet())
    }

    @Test fun maskHasSeparateArchiveIdentityAndNeverAFakePaintPlane() {
        val c = native()
        val maskKey = Triple("l", "mask", "-1_2")
        val source = c.copy(doc = c.doc.copy(layers = c.doc.layers.map { it.copy(mask = Cel("mask", tiles = listOf("-1_2"))) }),
            tiles = c.tiles + (maskKey to tile(11)))
        val p = ColdRecordedLoadPlan.prepare(source)
        assertEquals(1, p.planes.size)
        val mask = p.masks.single()
        assertEquals("l", mask.layerId)
        assertEquals("mask", mask.documentCelId)
        assertEquals(ColdRecordedLoadPlan.Kind.MASK_RGBA8, mask.tiles.single().kind)
        assertNull(mask.tiles.single().address)
        assertContentEquals(source.tiles.getValue(maskKey), p.copyPayload(mask.tiles.single().id))
    }

    @Test fun nativeMediaGroundAndFloatFormatsRemainDistinctOnExactCel() {
        val c = regional()
        val layers = c.doc.layers.map { l -> l.copy(cels = l.cels.map {
            if (it.id == "inactive") it.copy(floatTiles = listOf("0_0")) else it
        }) }
        val g = MediaTileKey("l", "inactive", "0_0", "g")
        val f = g.copy(store = "p0")
        val p = ColdRecordedLoadPlan.prepare(c.copy(doc = c.doc.copy(layers = layers), mediaTiles = mapOf(
            g to tile(33), f to ByteArray(MediaStores.tileBytes(TILE_SIZE, "p0")) { 7 })))
        val payload = p.planes.single { it.address.celId == "inactive" }.media
        assertEquals(setOf(ColdRecordedLoadPlan.Kind.GROUND_RGBA8, ColdRecordedLoadPlan.Kind.MEDIA_RGBA16F), payload.map { it.kind }.toSet())
        assertTrue(payload.all { it.address == ColdRecordedLoadPlan.Address("l", "inactive") })
        assertEquals(setOf(TILE_BYTES, MediaStores.tileBytes(TILE_SIZE, "p0")), payload.map { p.copyPayload(it.id).size }.toSet())
    }

    @Test fun recordedEmptyFileRetainsAnExplicitEmptyLookIndex() {
        val c = native()
        val source = replaceCel(c, c.doc.layers.single().cels.single().copy(strokesFile = "layers/l/c/strokes.jbs"))
            .copy(strokes = mapOf(("l" to "c") to emptyList()))
        val p = ColdRecordedLoadPlan.prepare(source)
        assertEquals(emptyList(), p.recordedPlanes.single().savedLookKeys)
        assertEquals(emptyList(), p.recordsCopy(address))
        assertEquals(0, p.summary.unresolvedLinePlanes)
    }

    @Test fun distantSeqZeroNativeBaseInsideRecordedPlaneDoesNotBecomeASavedLineLook() {
        val c = mixed()
        val cel = c.doc.layers.single().cels.single().copy(tiles = listOf("0_0", "8_-3"))
        val p = ColdRecordedLoadPlan.prepare(replaceCel(c, cel).copy(tiles = c.tiles + (Triple("l", "c", "8_-3") to tile(22))))
        val plane = p.recordedPlanes.single()
        assertEquals(setOf(Tiles.key(0, 0), Tiles.key(8, -3)), plane.slabs.map { it.key }.toSet())
        assertEquals(listOf(Tiles.key(0, 0)), plane.savedLookKeys)
    }

    @Test fun completeSnapshotIsUnaffectedByCallerMutation() {
        val c = mixed()
        val tile = c.tiles.values.single().copyOf()
        val slab = c.slabs.values.single().copyOf()
        val look = c.looks.values.single().copyOf()
        val samples = c.strokes.values.single().first().samples.toMutableList()
        val records = c.strokes.values.single().toMutableList().apply { this[0] = first().copy(samples = samples) }
        val cels = c.doc.layers.single().cels.toMutableList()
        val layers = mutableListOf(c.doc.layers.single().copy(cels = cels))
        val extensions = mutableMapOf("owner" to "original")
        val thumb = byteArrayOf(1, 2, 3)
        val source = c.copy(doc = c.doc.copy(layers = layers), tiles = mapOf(Triple("l", "c", "0_0") to tile),
            slabs = mapOf(SlabKey("l", "c", "0_0", 2) to slab), looks = mapOf(Triple("l", "c", "0_0") to look),
            strokes = mapOf(("l" to "c") to records), thumbnailPng = thumb,
            brushes = c.brushes.mapValues { it.value.copy(extensions = extensions) })
        val p = ColdRecordedLoadPlan.prepare(source)
        val beforeDoc = p.metadataCopy()
        val beforeRecords = p.recordsCopy(address)
        val beforeBytes = p.uploads.associate { it.id to p.copyPayload(it.id) }
        tile.fill(0); slab.fill(0); look.fill(0); thumb.fill(0)
        samples.clear(); records.clear(); cels.clear(); layers.clear(); extensions["owner"] = "changed"
        assertEquals(beforeDoc, p.metadataCopy())
        assertEquals(beforeRecords, p.recordsCopy(address))
        for ((id, bytes) in beforeBytes) assertContentEquals(bytes, p.copyPayload(id))
        assertContentEquals(byteArrayOf(1, 2, 3), p.thumbnailCopy())
        assertEquals("original", p.frozenBrushesCopy().values.single().extensions["owner"])
    }

    @Test fun getterCopiesAndDescriptorCastsCannotMutateThePlan() {
        val p = ColdRecordedLoadPlan.prepare(mixed().copy(thumbnailPng = byteArrayOf(3)))
        val id = p.uploads.first().id
        val original = p.copyPayload(id)
        p.copyPayload(id).fill(0); p.thumbnailCopy()!!.fill(0)
        runCatching { (p.metadataCopy().layers as MutableList<Layer>).clear() }
        runCatching { (p.frozenBrushesCopy().values.single().extensions as MutableMap<String, String>)["x"] = "changed" }
        assertContentEquals(original, p.copyPayload(id))
        assertContentEquals(byteArrayOf(3), p.thumbnailCopy())
        assertEquals(1, p.metadataCopy().layers.size)
        assertFalse("x" in p.frozenBrushesCopy().values.single().extensions)
        assertFails { (p.uploads as MutableList<ColdRecordedLoadPlan.Upload>).clear() }
        assertFails { (p.recordedPlanes.single().slabs as MutableList<ColdRecordedLoadPlan.Upload>).clear() }
        assertFails { (p.recordedPlanes.single().savedLookKeys as MutableList<Long>).clear() }
        assertFails { (p.recordsCopy(address).first().samples as MutableList<PenSample>).clear() }
    }

    @Test fun rawSeedAndSensorNaNBitsAreCopiedWithoutCodecNormalization() {
        val c = mixed()
        val bits = 0x7fc01234
        val record = c.strokes.values.single().first().copy(seed = Long.MIN_VALUE,
            samples = listOf(PenSample(-1f, 2f, 3.0, 0.5f, Float.fromBits(bits), Float.fromBits(bits), Float.fromBits(bits))))
        val p = ColdRecordedLoadPlan.prepare(c.copy(strokes = mapOf(("l" to "c") to listOf(record))))
        val back = p.recordsCopy(address).single()
        assertEquals(Long.MIN_VALUE, back.seed)
        assertEquals(bits, back.samples.single().tilt.toRawBits())
        assertEquals(bits, back.samples.single().azimuth.toRawBits())
    }

    @Test fun malformedPayloadsStillUseActualArchiveStructuralAdmission() {
        val c = mixed()
        for (bad in listOf(c.copy(tiles = emptyMap()), c.copy(slabs = emptyMap()), c.copy(looks = emptyMap()),
            c.copy(brushes = emptyMap()), c.copy(tiles = mapOf(Triple("l", "c", "0_0") to byteArrayOf(1))))) failure(bad)
    }

    @Test fun parsedNumericAliasesAreRefusedInsteadOfOverwritingOneUpload() {
        val c = native()
        val cel = c.doc.layers.single().cels.single().copy(tiles = listOf("0_0", "00_0"))
        val aliased = replaceCel(c, cel).copy(tiles = c.tiles + (Triple("l", "c", "00_0") to tile(51)))
        JbArchive.requireStructure(aliased) // Generic archive compatibility is deliberately wider.
        assertTrue(failure(aliased).contains("aliased"))
    }

    @Test fun canonicalStrokeDeclarationPresenceIsAColdOnlyGate() {
        val c = native()
        val unlisted = c.copy(strokes = mapOf(("l" to "c") to emptyList()))
        val marker = replaceCel(c, c.doc.layers.single().cels.single().copy(strokesFile = "marker"))
            .copy(strokes = mapOf(("l" to "c") to emptyList()))
        JbArchive.requireStructure(unlisted); JbArchive.requireStructure(marker)
        assertTrue(failure(unlisted).contains("declaration mismatch"))
        assertTrue(failure(marker).contains("Noncanonical strokes"))
    }

    @Test fun reservedNativeFamilyIdentifiersCannotCollideWithPaintOwners() {
        val c = native()
        for (id in listOf("l#mask", "l#p0", "l#g")) {
            val source = c.copy(doc = c.doc.copy(layers = c.doc.layers.map { it.copy(id = id) }),
                tiles = mapOf(Triple(id, "c", "0_0") to tile()))
            assertTrue(failure(source).contains("Reserved"))
        }
    }

    @Test fun regionalCurrentInactiveAndHeldLinesAllNeedPersistedClips() {
        // Legacy v10 structure remains readable; the cold runtime still cannot infer its domain.
        val base = regional().let { it.copy(doc = it.doc.copy(version = 10)) }
        val brush = LineArchiveFixture.brush
        val record = mixed().strokes.values.single().first()
        for (id in listOf("shared", "active", "inactive")) for (held in listOf(false, true)) {
            val layer = base.doc.layers.single().copy(cels = base.doc.layers.single().cels.map {
                if (it.id == id) it.copy(strokesFile = "layers/l/$id/strokes.jbs") else it
            }, regions = base.doc.layers.single().regions.map { it.copy(held = held) })
            val source = base.copy(doc = base.doc.copy(layers = listOf(layer), brushes = listOf(brush.id)),
                strokes = mapOf(("l" to id) to listOf(record)), brushes = mapOf(brush.id to brush))
            JbArchive.requireStructure(source)
            assertTrue(failure(source).contains("captured physical clipping"))
        }
    }

    @Test fun recordedMediaColocationIsRefusedButNativeLazyMediaIsNotInvented() {
        val c = mixed()
        val source = replaceCel(c, c.doc.layers.single().cels.single().copy(floatTiles = listOf("0_0")))
            .copy(mediaTiles = mapOf(MediaTileKey("l", "c", "0_0", "g") to tile()))
        assertTrue(failure(source).contains("share media"))
    }

    @Test fun nonlineOrderedDisplayCannotQuietlyComposeDuringColdOpen() {
        val c = native()
        val cel = c.doc.layers.single().cels.single().copy(slabs = listOf(SlabRef("0_0", 4, "normal")))
        assertTrue(failure(replaceCel(c, cel)).contains("needs composition"))
    }

    @Test fun frozenOnlyRootIsOutsideTheFirstAdapterProfile() {
        val c = native()
        val brush = LineArchiveFixture.brush
        val source = c.copy(doc = c.doc.copy(brushes = listOf(brush.id)), brushes = mapOf(brush.id to brush))
        JbArchive.requireStructure(source)
        assertTrue(failure(source).contains("Frozen-only"))
    }

    @Test fun unsupportedReferencedEnginesImagesShapingAndGrainAreEarlyRefusals() {
        val c = mixed()
        val base = c.brushes.values.single()
        val presets = listOf(base.copy(engine = "smudge"), base.copy(tip = base.tip.copy(source = "image", image = "tip.png")),
            base.copy(line = LineStyleSpec()), base.copy(paperGrain = base.paperGrain.copy(enabled = true)))
        for (brush in presets) failure(c.copy(brushes = mapOf(brush.id to brush)))
    }

    @Test fun shippedLegacyPaperResponseIsPreservedWithoutPretendingReplayWasValidated() {
        val c = mixed()
        val brush = c.brushes.values.single().copy(version = 6, paper = PaperResponse(influence = 0.15f))
        val p = ColdRecordedLoadPlan.prepare(c.copy(brushes = mapOf(brush.id to brush)))
        assertEquals(0.15f, p.frozenBrushesCopy().values.single().paper.influence)
        assertFalse(p.summary.dynamicReplayValidated)
    }

    @Test fun invalidOriginalVersionBrushIsNotUpgradedIntoAdmission() {
        val c = mixed()
        val brush = c.brushes.values.single().copy(version = 1, blend = "multiply")
        assertTrue(BrushValidate.validate(brush).isNotEmpty())
        assertTrue(failure(c.copy(brushes = mapOf(brush.id to brush))).contains("Invalid frozen"))
    }

    @Test fun anInternalCallerCannotManufactureAPreparedPlanWithItsOwnArrays() {
        assertFailsWith<IllegalArgumentException> {
            ColdRecordedLoadPlan.Prepared(Any(), "not validated", emptyMap(), emptyMap(), emptyMap(), null,
                listOf(byteArrayOf(1)), emptyList(), emptyList(), emptyList(),
                ColdRecordedLoadPlan.Summary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        }
    }

    @Test fun supportedNormalWcStaysColdAndNonNormalWcIsRefused() {
        val c = mixed()
        val original = c.brushes.values.single()
        val brush = original.copy(engine = ENGINE_WATERCOLOUR, watercolour = WatercolourSpec())
        val p = ColdRecordedLoadPlan.prepare(c.copy(brushes = mapOf(brush.id to brush)))
        assertFalse(p.summary.dynamicReplayValidated)
        // MULTIPLY is valid archive metadata; the GPU cold profile still requires NORMAL.
        // ERASE is rejected earlier by BrushValidate and cannot exercise this adapter gate.
        val multiply = brush.copy(version = maxOf(9, brush.version), blend = "multiply")
        assertTrue(BrushValidate.validate(multiply).isEmpty())
        assertTrue(failure(c.copy(brushes = mapOf(brush.id to multiply))).contains("NORMAL"))
    }

    @Test fun externalWcImagesAreNotAssumedToBeAvailableAtReplay() {
        val c = mixed()
        val images = WatercolourSpec().dabImages.toMutableList().apply { this[0] = "other/dab.png" }
        val brush = c.brushes.values.single().copy(engine = ENGINE_WATERCOLOUR,
            watercolour = WatercolourSpec(dabImages = images))
        assertTrue(failure(c.copy(brushes = mapOf(brush.id to brush))).contains("supported built-in"))
    }

    @Test fun bogusDeclaredFootprintIsNotPretendedToHaveBeenReplayed() {
        val c = mixed()
        val source = replaceCel(c, c.doc.layers.single().cels.single().copy(looks = listOf("99_99")))
            .copy(looks = mapOf(Triple("l", "c", "99_99") to tile()))
        // Its original tile's multi-slab plain display is independently unsupported; use no slabs.
        val plainBase = replaceCel(source, source.doc.layers.single().cels.single().copy(slabs = emptyList())).copy(slabs = emptyMap())
        val p = ColdRecordedLoadPlan.prepare(plainBase)
        assertEquals(listOf(Tiles.key(99, 99)), p.recordedPlanes.single().savedLookKeys)
        assertEquals(1, p.summary.unresolvedLinePlanes)
    }

    @Test fun boardKindAndPeriodicDisplayAreNotSilentlyAdapted() {
        val c = native()
        assertTrue(failure(c.copy(doc = c.doc.copy(boards = c.doc.boards.map { it.copy(tiled = true) }))).contains("periodic"))
        failure(c.copy(doc = c.doc.copy(boards = c.doc.boards.map { it.copy(kind = BoardKind.CHARACTER) })))
    }

    @Test fun byteEntrySampleAndCpuBudgetsRefuseBeforeDeepSnapshot() {
        val c = mixed()
        for (limits in listOf(ColdRecordedLoadPlan.Limits(maxUploadBytes = 1), ColdRecordedLoadPlan.Limits(maxCpuBytes = 1),
            ColdRecordedLoadPlan.Limits(maxEntries = 1), ColdRecordedLoadPlan.Limits(maxSamples = 1))) {
            assertTrue(assertFailsWith<JbArchiveException> { ColdRecordedLoadPlan.prepare(c, limits) }.message.orEmpty().contains("budget"))
        }
        assertFailsWith<IllegalArgumentException> { ColdRecordedLoadPlan.Limits(maxEntries = 0) }
        assertFailsWith<IllegalArgumentException> { ColdRecordedLoadPlan.Limits(maxCpuBytes = -1) }
    }

    @Test fun thumbnailAndVariableBrushMetadataAlsoConsumeCpuBudget() {
        val c = mixed()
        val p = ColdRecordedLoadPlan.prepare(c)
        val limit = ColdRecordedLoadPlan.Limits(maxCpuBytes = p.summary.chargedCpuBytes)
        assertFailsWith<JbArchiveException> { ColdRecordedLoadPlan.prepare(c.copy(thumbnailPng = byteArrayOf(1)), limit) }
        val brush = c.brushes.values.single().copy(extensions = mapOf("extra" to "x".repeat(100)))
        assertFailsWith<JbArchiveException> { ColdRecordedLoadPlan.prepare(c.copy(brushes = mapOf(brush.id to brush)), limit) }
    }

    @Test fun hugeMetadataCountIsRefusedBeforeReadingOrEncodingItsElements() {
        val boards = object : AbstractList<Board>() {
            override val size: Int get() = Int.MAX_VALUE
            override fun get(index: Int): Board = error("Metadata elements must not be touched before count admission")
        }
        val c = native()
        assertTrue(failure(c.copy(doc = c.doc.copy(boards = boards))).contains("entry budget"))
    }
}

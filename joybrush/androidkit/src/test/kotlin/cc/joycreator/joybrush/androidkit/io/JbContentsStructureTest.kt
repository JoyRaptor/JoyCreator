package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.media.MediaStores
import cc.joycreator.joybrush.core.stroke.StrokeCodecException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.*

/** Actual archive admission tests: no GL, renderer hooks, or mock validation implementation. */
class JbContentsStructureTest {
    private val paint = Triple("l", "c", "0_0")
    private val upper = SlabKey("l", "c", "0_0", 2)
    private fun mixed() = LineArchiveFixture.contents()
    private fun bytes() = LineArchiveFixture.tile(19)
    private fun withCel(contents: JbContents, cel: Cel): JbContents = contents.copy(
        doc = contents.doc.copy(layers = contents.doc.layers.map { it.copy(cels = listOf(cel)) }))

    private fun plain(key: String = "0_0"): JbContents {
        val base = mixed()
        return base.copy(
            doc = base.doc.copy(brushes = emptyList(), layers = base.doc.layers.map {
                it.copy(cels = listOf(Cel("c", tiles = listOf(key))))
            }),
            tiles = mapOf(Triple("l", "c", key) to bytes()), strokes = emptyMap(),
            slabs = emptyMap(), looks = emptyMap(), brushes = emptyMap())
    }

    private fun reject(contents: JbContents): String {
        val structural = assertFailsWith<JbArchiveException> { JbArchive.requireStructure(contents) }
        val out = ByteArrayOutputStream().apply { write(73) }
        assertFailsWith<JbArchiveException> { JbArchive.write(out, contents) }
        assertContentEquals(byteArrayOf(73), out.toByteArray(), "all admission must precede archive output")
        return structural.message.orEmpty()
    }

    private fun encoded(contents: JbContents): ByteArray = ByteArrayOutputStream().also {
        JbArchive.write(it, contents)
    }.toByteArray()

    @Test fun completeMixedSourcesPassStructureAndStillRoundtripThroughTheWriter() {
        val contents = mixed()
        JbArchive.requireStructure(contents)
        val back = JbArchive.read(ByteArrayInputStream(encoded(contents)))
        assertEquals(contents.doc, back.doc)
        assertEquals(contents.strokes.mapValues { it.value.sortedBy { r -> r.seq } }, back.strokes)
        assertContentEquals(contents.tiles.getValue(paint), back.tiles.getValue(paint))
        assertContentEquals(contents.slabs.getValue(upper), back.slabs.getValue(upper))
        assertContentEquals(contents.looks.getValue(paint), back.looks.getValue(paint))
    }

    @Test fun structuralAdmissionDoesNotRunTheWritersGeometricLookCheck() {
        val base = mixed()
        val distant = Triple("l", "c", "99_99")
        val contents = withCel(base, base.doc.layers.single().cels.single().copy(looks = listOf("99_99")))
            .copy(looks = mapOf(distant to bytes()))
        JbArchive.requireStructure(contents) // A valid declaration, but no line actually reaches this tile.
        val out = ByteArrayOutputStream().apply { write(91) }
        val error = assertFailsWith<JbArchiveException> { JbArchive.write(out, contents) }
        assertTrue(error.message.orEmpty().contains("no line crosses"))
        assertContentEquals(byteArrayOf(91), out.toByteArray())
    }

    @Test fun rendererIncompatibilityIsNotInventedAsGenericArchiveStructure() {
        val base = mixed()
        val frozen = base.brushes.mapValues { it.value.copy(engine = "smudge") }
        // Structurally retained frozen sources need not be executable by the current GPU renderer.
        JbArchive.requireStructure(base.copy(brushes = frozen))
    }

    @Test fun admissionDoesNotMutateBorrowedInputOrSortTheCallersStrokeList() {
        val contents = mixed()
        val records = contents.strokes.getValue("l" to "c")
        val ids = records.map { it.id }
        val tile = contents.tiles.getValue(paint).copyOf()
        val slab = contents.slabs.getValue(upper).copyOf()
        val look = contents.looks.getValue(paint).copyOf()
        JbArchive.requireStructure(contents)
        assertEquals(ids, records.map { it.id })
        assertContentEquals(tile, contents.tiles.getValue(paint))
        assertContentEquals(slab, contents.slabs.getValue(upper))
        assertContentEquals(look, contents.looks.getValue(paint))
    }

    @Test fun admissionReturnsNoCertificateThatCanBypassLaterWriterValidation() {
        val tiles = plain().tiles.toMutableMap()
        val contents = plain().copy(tiles = tiles)
        JbArchive.requireStructure(contents)
        tiles.clear()
        reject(contents)
    }

    @Test fun invalidDocumentStillFailsBeforeAnyOutput() {
        reject(plain().copy(doc = plain().doc.copy(version = DOC_VERSION + 1)))
    }

    @Test fun missingBottomTileIsRejectedInBothStructuralAndWritePaths() {
        assertTrue(reject(mixed().copy(tiles = emptyMap())).contains("no tile was given"))
    }

    @Test fun extraTileOrUnknownTileOwnerCannotBeOmittedByStructuralAdmission() {
        val base = plain()
        reject(base.copy(tiles = base.tiles + (Triple("l", "c", "9_9") to bytes())))
        reject(base.copy(tiles = base.tiles + (Triple("l", "missing", "0_0") to bytes())))
    }

    @Test fun wrongRgbaTileSizeIsRejected() {
        reject(plain().copy(tiles = mapOf(paint to byteArrayOf(1))))
    }

    @Test fun upperSlabCompletenessIsCheckedInBothDirections() {
        val base = mixed()
        reject(base.copy(slabs = emptyMap()))
        reject(base.copy(slabs = base.slabs + (upper.copy(seq = 7) to bytes())))
    }

    @Test fun upperSlabSizeAndSequenceCollisionStayGuarded() {
        val base = mixed()
        reject(base.copy(slabs = mapOf(upper to byteArrayOf(1))))
        val cel = base.doc.layers.single().cels.single().copy(
            slabs = listOf(SlabRef("0_0", 0), SlabRef("0_0", 1)))
        assertTrue(reject(withCel(base, cel).copy(slabs = mapOf(upper.copy(seq = 1) to bytes()))).contains("seq"))
    }

    @Test fun savedLookCompletenessAndSizesStayGuardedWithoutGeometricReplay() {
        val base = mixed()
        reject(base.copy(looks = emptyMap()))
        reject(base.copy(looks = base.looks + (Triple("l", "c", "9_9") to bytes())))
        reject(base.copy(looks = mapOf(paint to byteArrayOf(1))))
    }

    @Test fun nativeMaskTilesParticipateInReverseCompleteness() {
        val base = plain()
        val mask = Triple("l", "mask", "-2_3")
        val contents = base.copy(doc = base.doc.copy(layers = base.doc.layers.map {
            it.copy(mask = Cel("mask", tiles = listOf("-2_3")))
        }), tiles = base.tiles + (mask to bytes()))
        JbArchive.requireStructure(contents)
        reject(contents.copy(tiles = contents.tiles - mask))
    }

    @Test fun sourceMetadataOnMaskRemainsADocumentRefusal() {
        val base = plain()
        reject(base.copy(doc = base.doc.copy(layers = base.doc.layers.map {
            it.copy(mask = Cel("mask", strokesFile = "layers/l/mask/strokes.jbs"))
        })))
    }

    @Test fun lazyMediaStoresNeedAtLeastOneDeclaredPayloadWithoutInventedGround() {
        val base = plain()
        val contents = withCel(base, Cel("c", tiles = listOf("0_0"), floatTiles = listOf("0_0")))
        reject(contents)
        val ground = MediaTileKey("l", "c", "0_0", MediaStores.GROUND)
        JbArchive.requireStructure(contents.copy(mediaTiles = mapOf(ground to bytes())))
        val pigment = ground.copy(store = "p0")
        JbArchive.requireStructure(contents.copy(mediaTiles = mapOf(pigment to ByteArray(MediaStores.tileBytes(TILE_SIZE, "p0")))))
    }

    @Test fun wrongMediaStoreOwnerDeclarationAndSizeAreRejected() {
        val base = withCel(plain(), Cel("c", tiles = listOf("0_0"), floatTiles = listOf("0_0")))
        for (key in listOf(MediaTileKey("l", "c", "0_0", "unknown"),
            MediaTileKey("l", "missing", "0_0", "p0"), MediaTileKey("l", "c", "9_9", "p0"))) {
            reject(base.copy(mediaTiles = mapOf(key to bytes())))
        }
        reject(base.copy(mediaTiles = mapOf(MediaTileKey("l", "c", "0_0", MediaStores.GROUND) to byteArrayOf(1))))
        reject(base.copy(mediaTiles = mapOf(MediaTileKey("l", "c", "0_0", "p0") to bytes())))
    }

    @Test fun frozenBrushDeclarationsAreExactInBothDirections() {
        val base = mixed()
        reject(base.copy(brushes = emptyMap()))
        val extra = base.brushes.values.single().copy(id = "extra")
        reject(base.copy(brushes = base.brushes + (extra.id to extra)))
    }

    @Test fun wrongFrozenIdentityAndInvalidDefinitionFailStructure() {
        val base = mixed()
        reject(base.copy(brushes = base.brushes.mapValues { it.value.copy(id = "wrong") }))
        reject(base.copy(brushes = base.brushes.mapValues { it.value.copy(size = Param(0f)) }))
    }

    @Test fun recordIdentitySequenceAndFrozenReferencesRemainStructural() {
        val base = mixed()
        val records = base.strokes.getValue("l" to "c")
        reject(base.copy(strokes = mapOf(("l" to "c") to records.map { it.copy(id = "same") })))
        reject(base.copy(strokes = mapOf(("l" to "c") to records.map { it.copy(seq = 3) })))
        reject(base.copy(strokes = mapOf(("l" to "c") to records.map { it.copy(brushId = "missing") })))
        reject(base.copy(strokes = mapOf(("l" to "c") to records.map { it.copy(groupId = " ") })))
    }

    @Test fun missingDeclaredStrokeFilePayloadAndUnknownStrokeOwnerAreRejected() {
        val base = mixed()
        reject(base.copy(strokes = emptyMap()))
        reject(base.copy(strokes = base.strokes + (("l" to "missing") to emptyList())))
    }

    @Test fun negativeSequenceStillRunsTheActualCodecSerializabilityCheck() {
        val base = mixed()
        val contents = base.copy(strokes = base.strokes.mapValues { (_, records) -> records.map { it.copy(seq = -1) } })
        assertFailsWith<StrokeCodecException> { JbArchive.requireStructure(contents) }
        val out = ByteArrayOutputStream().apply { write(37) }
        assertFailsWith<StrokeCodecException> { JbArchive.write(out, contents) }
        assertContentEquals(byteArrayOf(37), out.toByteArray())
    }

    @Test fun unsafeGeneratedTileAndStrokesPathsFailEvenWhenEntriesAreNotRetained() {
        val unsafe = "../escape"
        val base = plain()
        val tile = base.copy(doc = base.doc.copy(layers = base.doc.layers.map { it.copy(id = unsafe) }),
            tiles = mapOf(Triple(unsafe, "c", "0_0") to bytes()))
        assertTrue(reject(tile).contains("entry name"))
        val source = base.copy(doc = base.doc.copy(layers = base.doc.layers.map {
            it.copy(id = unsafe, cels = listOf(Cel("c", strokesFile = "marker")))
        }), tiles = emptyMap(), strokes = mapOf((unsafe to "c") to emptyList()))
        assertTrue(reject(source).contains("entry name"))
    }

    @Test fun genericNumericSpellingAndEmptyStrokeMetadataCompatibilityAreNotTightened() {
        val alternateKey = plain("00_0")
        JbArchive.requireStructure(alternateKey)
        assertEquals(alternateKey.doc, JbArchive.read(ByteArrayInputStream(encoded(alternateKey))).doc)

        val base = plain()
        val emptyUnlisted = base.copy(strokes = mapOf(("l" to "c") to emptyList()))
        JbArchive.requireStructure(emptyUnlisted)
        assertEquals(emptyList(), JbArchive.read(ByteArrayInputStream(encoded(emptyUnlisted))).strokes["l" to "c"])

        val marker = withCel(base, Cel("c", tiles = listOf("0_0"), strokesFile = "noncanonical-presence-marker"))
            .copy(strokes = mapOf(("l" to "c") to emptyList()))
        JbArchive.requireStructure(marker)
        assertEquals(marker.doc, JbArchive.read(ByteArrayInputStream(encoded(marker))).doc)
    }
}

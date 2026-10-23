package cc.joycreator.joybrush.core.render

import cc.joycreator.joybrush.core.doc.*
import kotlin.test.*

class SavedCelTileSourceTest {
    private fun doc(cel: Cel, visible: Boolean = true, mask: Cel? = null) = JbDocument(
        id = "doc", name = "Saved cel", boards = listOf(Board("board", "Canvas", BoardKind.CANVAS, RectPx(0, 0, 1, 1))),
        layers = listOf(Layer("paint", "Paint", LayerKind.PAINT, visible = visible, cels = listOf(cel), mask = mask)),
    )

    private fun solid(r: Int, g: Int, b: Int, a: Int = 255) = ByteArray(RegionRenderer.TILE_BYTES).also { bytes ->
        for (i in bytes.indices step 4) {
            bytes[i] = r.toByte(); bytes[i + 1] = g.toByte(); bytes[i + 2] = b.toByte(); bytes[i + 3] = a.toByte()
        }
    }

    private fun source(
        document: JbDocument,
        base: TileSource,
        look: (String, String, Int, Int) -> ByteArray? = { _, _, _, _ -> null },
        slab: (String, String, Long, Int, Int) -> ByteArray? = { _, _, _, _, _ -> null },
        hasLines: (String, String) -> Boolean = { _, _ -> true },
    ) = SavedCelTileSource(document, base, look, slab, hasLines)

    @Test fun declaredLookIsReturnedByteForByteAndPlainTileStaysBase() {
        val look = solid(7, 99, 201, 200).also { it[0] = 13 }
        val base = solid(255, 0, 0)
        val cel = Cel("c", tiles = listOf("0_0", "1_0"), strokesFile = "strokes.jbs", looks = listOf("0_0"))
        val reads = mutableListOf<String>()
        val tiles = source(doc(cel), TileSource { _, _, tx, _ -> reads += "base:$tx"; if (tx == 0) base else solid(2, 3, 4) },
            look = { _, _, _, _ -> reads += "look"; look })

        tiles.validate()
        assertContentEquals(look, tiles.tile("paint", "c", 0, 0))
        assertContentEquals(solid(2, 3, 4), tiles.tile("paint", "c", 1, 0))
        assertEquals(2, reads.count { it == "look" })
    }

    @Test fun explicitSeqZeroBottomAndOrderedSlabOpsComposeOnce() {
        val base = solid(128, 128, 128)
        val cel = Cel("c", tiles = listOf("0_0"), slabs = listOf(
            SlabRef("0_0", 0, "normal"),
            SlabRef("0_0", 1, "multiply"),
            SlabRef("0_0", 2, "behind"),
            SlabRef("0_0", 3, "normal"),
            SlabRef("0_0", 4, "erase"),
        ))
        val calls = mutableListOf<Long>()
        val upper = mapOf(
            1L to solid(128, 128, 128), // 128 gray multiplied by 128 gray -> 64 gray.
            2L to solid(0, 255, 0),    // behind leaves the opaque 64 gray above it unchanged.
            3L to solid(128, 0, 0, 128), // half-red over gray -> (160,32,32,255).
            4L to solid(0, 0, 0, 128),  // destination-out keeps 127/255 -> (80,16,16,127).
        )
        val baseReads = mutableListOf<Int>()
        val tiles = source(doc(cel), TileSource { _, _, tx, _ -> baseReads += tx; base },
            slab = { _, _, seq, _, _ -> calls += seq; upper[seq] })

        tiles.validate()
        val result = tiles.tile("paint", "c", 0, 0)!!
        assertEquals(listOf(80, 16, 16, 127), (0..3).map { result[it].toInt() and 255 })
        assertEquals(listOf(1L, 2L, 3L, 4L, 1L, 2L, 3L, 4L), calls) // preflight then render, no seq-0 slab-file read.
        assertEquals(listOf(0, 0), baseReads) // one bounded preflight read, one actual tile read; seq 0 is never a slab file.
    }

    @Test fun behindAndMultiplyHaveTheirOwnHandWorkedResults() {
        val base = solid(128, 128, 128)
        val cel = Cel("c", tiles = listOf("0_0"), slabs = listOf(
            SlabRef("0_0", 0, "normal"), SlabRef("0_0", 1, "multiply"), SlabRef("0_0", 2, "behind"),
        ))
        val upper = mapOf(1L to solid(128, 128, 128), 2L to solid(0, 255, 0))
        val tiles = source(doc(cel), TileSource { _, _, _, _ -> base }, slab = { _, _, seq, _, _ -> upper[seq] })
        val result = tiles.tile("paint", "c", 0, 0)!!
        assertEquals(listOf(64, 64, 64, 255), (0..3).map { result[it].toInt() and 255 })
    }

    @Test fun validateClosesOverHiddenCelsAndRefusesMissingOrMalformedPayloads() {
        val cel = Cel("hidden", tiles = listOf("0_0"), strokesFile = "strokes.jbs", looks = listOf("0_0"))
        val hidden = source(doc(cel, visible = false), TileSource { _, _, _, _ -> solid(1, 2, 3) })
        assertFailsWith<RegionException> { hidden.validate() }

        val malformed = source(doc(cel), TileSource { _, _, _, _ -> solid(1, 2, 3) }, look = { _, _, _, _ -> ByteArray(12) })
        assertFailsWith<RegionException> { malformed.validate() }

        val slabCel = Cel("c", tiles = listOf("0_0"), slabs = listOf(
            SlabRef("0_0", 0), SlabRef("0_0", 1),
        ))
        val missingSlab = source(doc(slabCel), TileSource { _, _, _, _ -> solid(1, 2, 3) })
        assertFailsWith<RegionException> { missingSlab.validate() }
    }

    @Test fun maskItemPayloadAndUnknownAddressesAreRefused() {
        val masked = source(doc(Cel("c"), mask = Cel("m", looks = listOf("0_0"))), TileSource { _, _, _, _ -> null })
        assertFailsWith<RegionException> { masked.validate() }
        val mediaMask = source(doc(Cel("c"), mask = Cel("m", floatTiles = listOf("0_0"))), TileSource { _, _, _, _ -> null })
        assertFailsWith<RegionException> { mediaMask.validate() }
        assertFailsWith<RegionException> { masked.tile("missing", "c", 0, 0) }
        assertFailsWith<RegionException> { masked.tile("paint", "missing", 0, 0) }
    }

    @Test fun mixedLegacyInkAndPaintLooksKeepTheirSeparateExportPaths() {
        val ink = Layer("ink", "Legacy ink", LayerKind.INK,
            cels = listOf(Cel("ink-cel", tiles = listOf("0_0"), strokesFile = "legacy.jbs")))
        val paint = Layer("paint", "Paint", LayerKind.PAINT,
            cels = listOf(Cel("paint-cel", tiles = listOf("0_0"), strokesFile = "strokes.jbs", looks = listOf("0_0"))))
        val d = JbDocument(id = "doc", name = "Mixed", boards = listOf(Board("board", "Canvas", BoardKind.CANVAS, RectPx(0, 0, 1, 1))),
            layers = listOf(ink, paint))
        val inkPixels = solid(11, 22, 33)
        val paintBase = solid(1, 2, 3)
        val paintLook = solid(90, 80, 70)
        val source = source(d, TileSource { layer, _, _, _ -> if (layer == "ink") inkPixels else paintBase },
            look = { _, _, _, _ -> paintLook })

        source.validate()
        assertContentEquals(inkPixels, source.tile("ink", "ink-cel", 0, 0))
        assertContentEquals(paintLook, source.tile("paint", "paint-cel", 0, 0))
    }

    @Test fun emptyDeclaredStrokeFileCanBeDistinguishedFromMissingLookBearingLines() {
        val empty = Cel("empty", tiles = listOf("0_0"), strokesFile = "empty.jbs")
        val base = solid(1, 2, 3)
        val noRecords = source(doc(empty), TileSource { _, _, _, _ -> base }, hasLines = { _, _ -> false })
        noRecords.validate()
        assertContentEquals(base, noRecords.tile("paint", "empty", 0, 0))

        val ambiguous = source(doc(empty), TileSource { _, _, _, _ -> base })
        assertFailsWith<RegionException> { ambiguous.validate() }
    }
}

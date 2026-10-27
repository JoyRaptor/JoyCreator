package cc.joycreator.joybrush.core.doc

import cc.joycreator.joybrush.core.brush.BrushRules
import cc.joycreator.joybrush.core.brush.ENGINE_FILL
import cc.joycreator.joybrush.core.brush.ENGINE_MEDIA
import cc.joycreator.joybrush.core.brush.ENGINE_PUSH
import cc.joycreator.joybrush.core.brush.ENGINE_SMUDGE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Media in the document since R51 (JB-2.40, DOC_VERSION 9): pencil, watercolour and oil are a payload listed on the
 * tiles of a PAINT cel ([Cel.floatTiles]), round-tripped; a v8 MEDIA layer opens as PAINT with its payload; each wrong
 * combination is refused in one sentence.
 */
class MediaLayerDocTest {
    private fun ids(): () -> String { var n = 0; return { "id${n++}" } }

    /** A drawing whose second layer carries media on two tiles; [kind] and [version] as the test needs. */
    private fun withMedia(kind: LayerKind = LayerKind.PAINT, version: Int = DOC_VERSION, cel: (Cel) -> Cel = { it }): JbDocument {
        val doc = DocOps.newDocument("doc", "Test", 800, 600, ids())
        val paint = doc.layers.first()
        val media = paint.copy(id = "l-media", name = "Layer 2", kind = kind,
            cels = paint.cels.map { cel(it.copy(id = "c-media", tiles = listOf("0_0", "1_0"), floatTiles = listOf("0_0", "1_0"))) })
        return doc.copy(version = version, layers = doc.layers + media)
    }

    @Test fun aPaintLayerWithMediaAndLinesIsValidAndRoundTrips() {
        val doc = withMedia { it.copy(strokesFile = "lines.bin") }
        assertEquals(emptyList(), DocOps.validate(doc))
        val back = DocJson.decode(DocJson.encode(doc))
        assertEquals(doc, back)
        assertEquals(listOf("0_0", "1_0"), back.layers.last().cels.single().floatTiles)
        assertEquals("lines.bin", back.layers.last().cels.single().strokesFile)
        assertEquals(14, DOC_VERSION, "Saved clip end behaviour (12), linked component drawings (13) and frame tweening (14) extend the document format")
    }

    @Test fun aVersion8MediaLayerOpensAsPaintWithItsPayload() {
        // The owner's test drawings from the media port: a MEDIA layer in a version 8 file.
        val v8 = DocJson.encode(withMedia(LayerKind.MEDIA, version = 8))
        assertTrue("\"MEDIA\"" in v8 && "\"version\": 8" in v8, "the fixture really is a v8 media file")
        val opened = DocJson.decode(v8)
        assertEquals(LayerKind.PAINT, opened.layers.last().kind)
        assertEquals(listOf("0_0", "1_0"), opened.layers.last().cels.single().floatTiles)
        assertEquals(9, opened.version, "what it is from now on")
        assertEquals(emptyList(), DocOps.validate(opened))
        assertFalse("\"MEDIA\"" in DocJson.encode(opened), "a version 9 file never writes MEDIA")
    }

    @Test fun aVersion8FileWithoutMediaKeepsItsVersion() {
        val doc = DocOps.newDocument("doc", "Test", 800, 600, ids()).copy(version = 8)
        assertEquals(8, DocJson.decode(DocJson.encode(doc)).version)
    }

    @Test fun aVersion9FileThatStillSaysMediaIsRefusedInWords() {
        val problems = DocOps.validate(DocJson.decode(DocJson.encode(withMedia(LayerKind.MEDIA))))
        assertTrue(problems.any { "is MEDIA, a kind version 9 reads as paint" in it }, problems.toString())
    }

    @Test fun mediaOnAPaintLayerNeedsVersion9() {
        val problems = DocOps.validate(withMedia(version = 8))
        assertTrue(problems.any { "need document version 9" in it }, problems.toString())
    }

    @Test fun mediaStateOnAnInkLayerIsRefused() {
        val problems = DocOps.validate(withMedia(LayerKind.INK) { it.copy(tiles = emptyList()) })
        assertTrue(problems.any { "is ink, so cel" in it && "cannot have media state" in it }, problems.toString())
    }

    @Test fun aPreVersion10CelWithMediaAndStrokesIsRefused() {
        val problems = DocOps.validate(withMedia(version = 9) { it.copy(strokesFile = "s.bin") })
        assertTrue(problems.any { "cannot have strokes before document version 10" in it }, problems.toString())
    }

    @Test fun aLayerWithMediaCanHaveAMaskButTheMaskHasNone() {
        // The mask multiplies the look tiles, which is all the compositor and the export ever read (R48).
        val doc = withMedia()
        val masked = doc.copy(layers = doc.layers.map {
            if (it.id == "l-media") it.copy(mask = Cel(id = "m-media", tiles = listOf("0_0"))) else it
        })
        assertEquals(emptyList(), DocOps.validate(masked))
        val bad = masked.copy(layers = masked.layers.map { it.copy(mask = it.mask?.copy(floatTiles = listOf("0_0"))) })
        assertTrue(DocOps.validate(bad).any { "has media state; a mask is pixels" in it })
    }

    @Test fun mediaLookTilesArePixels() {
        assertTrue(LayerKind.MEDIA.hasPixels)
        assertTrue(LayerKind.PAINT.hasPixels)
        assertTrue(!LayerKind.INK.hasPixels)
    }

    @Test fun aRepeatedMediaTileIsRefused() {
        val problems = DocOps.validate(withMedia { it.copy(floatTiles = listOf("0_0", "0_0")) })
        assertTrue(problems.any { "lists a media tile twice" in it }, problems.toString())
    }

    @Test fun everyBrushPaintsOnPaintAndMediaIsRefusedOnInkInWords() {
        for (engine in listOf(ENGINE_MEDIA, "stamp", ENGINE_FILL, ENGINE_SMUDGE, ENGINE_PUSH)) {
            assertEquals(null, BrushRules.refusalFor(engine, LayerKind.PAINT), engine)
            assertEquals(null, BrushRules.refusalFor(engine, LayerKind.MEDIA), "$engine: a v8 media layer is paint")
        }
        val why = assertNotNull(BrushRules.refusalFor(ENGINE_MEDIA, LayerKind.INK))
        assertTrue("paint on a paint layer" in why, why)
    }
}

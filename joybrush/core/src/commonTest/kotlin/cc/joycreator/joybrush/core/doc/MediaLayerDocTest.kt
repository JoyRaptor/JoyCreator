package cc.joycreator.joybrush.core.doc

import cc.joycreator.joybrush.core.brush.BrushRules
import cc.joycreator.joybrush.core.brush.ENGINE_PUSH
import cc.joycreator.joybrush.core.brush.ENGINE_SMUDGE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The media layer in the document (v8, MEDIA_ENGINE_PLAN §4): float state tiles listed beside the look tiles,
 * round-tripped, and each wrong combination refused in one sentence.
 */
class MediaLayerDocTest {
    private fun ids(): () -> String { var n = 0; return { "id${n++}" } }

    private fun withMedia(cel: (Cel) -> Cel = { it }): JbDocument {
        val doc = DocOps.newDocument("doc", "Test", 800, 600, ids())
        val paint = doc.layers.first()
        val media = paint.copy(id = "l-media", name = "Watercolour", kind = LayerKind.MEDIA,
            cels = paint.cels.map { cel(it.copy(id = "c-media", tiles = listOf("0_0", "1_0"), floatTiles = listOf("0_0", "1_0"))) })
        return doc.copy(layers = doc.layers + media)
    }

    @Test fun aMediaLayerIsValidAndRoundTrips() {
        val doc = withMedia()
        assertEquals(emptyList(), DocOps.validate(doc))
        val back = DocJson.decode(DocJson.encode(doc))
        assertEquals(doc, back)
        assertEquals(listOf("0_0", "1_0"), back.layers.last().cels.single().floatTiles)
        assertEquals(DOC_VERSION, back.version)
    }

    @Test fun mediaLookTilesArePixels() {
        assertTrue(LayerKind.MEDIA.hasPixels)
        assertTrue(LayerKind.PAINT.hasPixels)
        assertTrue(!LayerKind.INK.hasPixels)
    }

    @Test fun aMediaCelCannotHaveStrokes() {
        val problems = DocOps.validate(withMedia { it.copy(strokesFile = "s.bin") })
        assertTrue(problems.any { "is media, so cel" in it && "cannot have strokes" in it }, problems.toString())
    }

    @Test fun mediaStateOnAPaintLayerIsRefused() {
        val doc = DocOps.newDocument("doc", "Test", 800, 600, ids())
        val paint = doc.layers.first()
        val bad = doc.copy(layers = listOf(paint.copy(cels = paint.cels.map { it.copy(floatTiles = listOf("0_0")) })))
        val problems = DocOps.validate(bad)
        assertTrue(problems.any { "is paint, so cel" in it && "cannot have media state" in it }, problems.toString())
    }

    @Test fun aRepeatedMediaTileIsRefused() {
        val problems = DocOps.validate(withMedia { it.copy(floatTiles = listOf("0_0", "0_0")) })
        assertTrue(problems.any { "lists a media tile twice" in it }, problems.toString())
    }

    @Test fun pixelReadingBrushesRefuseAMediaLayerInWords() {
        for (engine in listOf(ENGINE_SMUDGE, ENGINE_PUSH, "wet")) {
            val why = assertNotNull(BrushRules.refusalFor(engine, LayerKind.MEDIA))
            assertTrue("works on a paint layer" in why, why)
        }
    }
}

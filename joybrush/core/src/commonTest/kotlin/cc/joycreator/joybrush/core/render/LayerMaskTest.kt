package cc.joycreator.joybrush.core.render

import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.Cel
import cc.joycreator.joybrush.core.doc.DocJson
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.Layer
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.doc.RectPx
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JB-2.23 (Lead ruling R48): masks and clipping, by the ONE set of rules both compositors call. Every expected pixel is
 * worked out by hand in its comment.
 */
class LayerMaskTest {

    private val t = RegionRenderer.TILE_BYTES

    /** A whole tile of one premultiplied colour. */
    private fun tile(r: Int, g: Int, b: Int, a: Int) = ByteArray(t) { i ->
        when (i % 4) { 0 -> r; 1 -> g; 2 -> b; else -> a }.toByte()
    }

    private fun layer(id: String, mask: Boolean = false, clip: Boolean = false, visible: Boolean = true, opacity: Float = 1f) =
        Layer(
            id = id, name = id, kind = LayerKind.PAINT, visible = visible, opacity = opacity,
            cels = listOf(Cel("c", listOf("0_0"))),
            mask = if (mask) Cel(LayerMask.MASK_CEL, listOf("0_0")) else null,
            clip = clip,
        )

    private fun doc(vararg layers: Layer) = JbDocument(
        id = "d", name = "d",
        boards = listOf(Board("b", "B", BoardKind.CANVAS, RectPx(0, 0, 256, 256))),
        layers = layers.toList(),
    )

    /** One pixel at (10, 10), premultiplied floats. */
    private fun pixel(d: JbDocument, tiles: Map<Pair<String, String>, ByteArray>, paper: String? = null): FloatArray =
        RegionRenderer.renderPremultiplied(d, TileSource { l, c, tx, ty -> if (tx == 0 && ty == 0) tiles[l to c] else null },
            RectPx(10, 10, 1, 1), null, paper)

    private fun near(want: Float, got: Float, what: String) = assertEquals(want, got, 1e-3f, what)

    // ── the rules ──

    @Test
    fun coverageIsTheRedChannelAlphaIsIgnoredAndNoMaskIsFull() {
        assertEquals(1f, LayerMask.coverage(null, 0))
        val m = tile(200, 7, 7, 0)
        near(200f / 255f, LayerMask.coverage(m, 0), "R 200 with alpha 0 is coverage 200, not 0")
    }

    @Test
    fun aRunOfClippedLayersAllClipIntoTheSameBase() {
        val abc = listOf(layer("a"), layer("b"), layer("c", clip = true))
        assertEquals(1, LayerMask.clipBaseOf(2, abc))
        val run = listOf(layer("a"), layer("b", clip = true), layer("c", clip = true))
        assertEquals(0, LayerMask.clipBaseOf(1, run))
        assertEquals(0, LayerMask.clipBaseOf(2, run))
        assertNull(LayerMask.clipBaseOf(0, listOf(layer("a", clip = true))), "the bottom layer has nothing to clip to")
        assertNull(LayerMask.clipBaseOf(1, abc), "an unclipped layer has no base")
    }

    // ── the export, by hand ──

    @Test
    fun aMaskMultipliesTheLayerBeforeOpacityAndBlend() {
        // Opaque red at 50% with a mask of 128, over white paper: k = 0.5 × 128/255 = 0.25098.
        // R: 1·k + 1·(1−k) = 1.   G and B: 0 + 1·(1−k) = 0.74902.
        val d = doc(layer("a", mask = true, opacity = 0.5f))
        val px = pixel(d, mapOf(("a" to "c") to tile(255, 0, 0, 255), ("a" to LayerMask.MASK_CEL) to tile(128, 0, 0, 255)), "#FFFFFF")
        near(1f, px[0], "red")
        near(1f - 0.5f * 128f / 255f, px[1], "green")
        near(1f, px[3], "alpha")
        // Mask-AFTER-blend would have given G = 1 − (1 − (1 − 0.5)) × … ; the order matters and this is the chosen one.
    }

    @Test
    fun aMaskTileThatWasNeverPaintedShowsEverything() {
        val d = doc(layer("a", mask = true))
        val px = pixel(d, mapOf(("a" to "c") to tile(0, 0, 255, 255)))
        near(1f, px[2], "blue, fully")
        near(1f, px[3], "alpha")
    }

    @Test
    fun aClippedLayerShowsOnlyWhereItsBaseHasPaint() {
        // Base: blue at alpha 128 (premultiplied 0,0,128,128). Clipped: opaque red. No paper.
        // Clip alpha = 128/255 = 0.50196, so the red contributes (0.50196, 0, 0, 0.50196).
        // Over the base (0, 0, 0.50196, 0.50196): R = 0.50196, B = 0.50196 × (1 − 0.50196) = 0.25, A = 0.50196 + 0.25 = 0.75196.
        val d = doc(layer("base"), layer("red", clip = true))
        val px = pixel(d, mapOf(("base" to "c") to tile(0, 0, 128, 128), ("red" to "c") to tile(255, 0, 0, 255)))
        val ca = 128f / 255f
        near(ca, px[0], "red")
        near(ca * (1f - ca), px[2], "blue")
        near(ca + ca * (1f - ca), px[3], "alpha")
    }

    @Test
    fun whereTheBaseIsEmptyTheClippedLayerIsNot() {
        val d = doc(layer("base"), layer("red", clip = true))
        val px = pixel(d, mapOf(("red" to "c") to tile(255, 0, 0, 255)))
        near(0f, px[3], "nothing at all")
    }

    @Test
    fun hidingTheBaseHidesWhatIsClippedToIt() {
        val d = doc(layer("base", visible = false), layer("red", clip = true))
        val px = pixel(d, mapOf(("base" to "c") to tile(0, 0, 255, 255), ("red" to "c") to tile(255, 0, 0, 255)))
        near(0f, px[3], "nothing: the base is the clipped layer's shape")
    }

    @Test
    fun theBasesOwnMaskShapesTheClipToo() {
        // The base's mask is 0 here: the base is hidden by it, and so is everything clipped to it.
        val d = doc(layer("base", mask = true), layer("red", clip = true))
        val px = pixel(d, mapOf(
            ("base" to "c") to tile(0, 0, 255, 255), ("base" to LayerMask.MASK_CEL) to tile(0, 0, 0, 255),
            ("red" to "c") to tile(255, 0, 0, 255),
        ))
        near(0f, px[3], "masked out, both")
    }

    @Test
    fun theBasesOpacityFadesTheBaseNotTheClip() {
        // Base: opaque blue at 20% opacity. Clipped red, opaque: clip alpha is the base's ALPHA (1), not its opacity.
        // Base: (0, 0, 0.2, 0.2). Red over it: R = 1, A = 1.
        val d = doc(layer("base", opacity = 0.2f), layer("red", clip = true))
        val px = pixel(d, mapOf(("base" to "c") to tile(0, 0, 255, 255), ("red" to "c") to tile(255, 0, 0, 255)))
        near(1f, px[0], "red, fully")
        near(1f, px[3], "alpha")
    }

    // ── the document ──

    @Test
    fun maskAndClipSurviveTheFileAndAreValidated() {
        val d = doc(layer("a", mask = true), layer("b", clip = true))
        val back = DocJson.decode(DocJson.encode(d))
        assertEquals(d.layers, back.layers)
        assertTrue(DocOps.validate(d).isEmpty(), DocOps.validate(d).joinToString())

        val bottomClipped = doc(layer("a", clip = true))
        assertTrue(DocOps.validate(bottomClipped).any { it.contains("clipped") && it.contains("no layer below") })
        val inkMask = doc(layer("a").copy(kind = LayerKind.INK, cels = listOf(Cel("c")), mask = Cel("m")))
        assertTrue(DocOps.validate(inkMask).any { it.contains("only a paint layer can have a mask") })
        val clash = doc(layer("a").copy(mask = Cel("c")))
        assertTrue(DocOps.validate(clash).any { it.contains("a mask and a cel both called") })
    }
}

package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.brush.FrozenBrushPngs
import cc.joycreator.joybrush.core.brush.SampledTipSpec
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

/**
 * Unibrush U4: an editable line whose brush draws from pictures (Texture, Dry, Sparkle) is saved through the same mapping
 * the canvas uses ([LineSaveMapping], what `JbCanvasView.withLines` calls) WITH those pictures, and reopens with them and
 * without the brush library. Before U4 the canvas passed none, and such a save could not be written at all.
 */
class LinePictureSaveTest {
    private val picture = LineArchiveFixture.brush.let { it.copy(tip = it.tip.copy(sampled = SampledTipSpec(listOf("tips/one.png")))) }
    private val record = StrokeRecord("a", picture.id, 1, 0f, 1f,
        listOf(PenSample(20f, 20f, 0.0, 1f), PenSample(24f, 20f, 16.0, 0.5f)), seq = 1)

    private fun png(argb: Int = -1): ByteArray {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, argb)
        return ByteArrayOutputStream().also { assertTrue(ImageIO.write(image, "png", it)) }.toByteArray()
    }
    private val pngs = FrozenBrushPngs.snapshot(mapOf("tips/one.png" to png()))

    /** The drawing as the canvas reads it back from its tiles: no lines declared and no brush pictures yet. */
    private fun plain(): JbContents {
        val doc = JbDocument(id = "d", name = "Pictures",
            boards = listOf(Board("b", "Canvas", BoardKind.CANVAS, RectPx(0, 0, 512, 512))),
            layers = listOf(Layer("ink", "Ink", LayerKind.PAINT, cels = listOf(Cel("c1", tiles = listOf("0_0"))))))
        return JbContents(doc, mapOf(Triple("ink", "c1", "0_0") to LineArchiveFixture.tile(50)), emptyMap())
    }
    private val planes = listOf(PlaneItems("ink", "c1", listOf(record), crossed = setOf("0_0"),
        bases = mapOf("0_0" to LineArchiveFixture.tile(5)), slabs = emptyMap()))

    @Test fun aPictureLineSavesWithItsPicturesAndReopensWithoutTheLibrary() {
        val saved = LineSaveMapping.apply(plain(), planes, mapOf(picture.id to picture), mapOf(picture.id to pngs))
        val bytes = ByteArrayOutputStream().also { JbArchive.write(it, saved) }.toByteArray()
        val back = JbArchive.read(ByteArrayInputStream(bytes), { error("a new archive must not consult the brush library") })
        assertEquals(picture, back.brushes.getValue(picture.id))
        assertTrue(back.brushAssets.getValue(picture.id).sameBytes(pngs), "the picture comes back byte for byte")
        assertEquals(listOf(record), back.strokes.getValue("ink" to "c1"))
    }

    @Test fun withoutItsPicturesThePictureLineCannotBeSavedSoTheOldFileStays() {
        // What the canvas did before U4 (no pictures handed to the mapping): refused before anything is written.
        assertFails { LineSaveMapping.apply(plain(), planes, mapOf(picture.id to picture)) }
    }
}

package cc.joycreator.joybrush.android.library

import cc.joycreator.joybrush.androidkit.io.JbContents
import cc.joycreator.joybrush.androidkit.io.SlabKey
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.SlabRef
import cc.joycreator.joybrush.core.doc.TILE_SIZE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Second code review E8: a drawing with editable lines got no Library picture ("cannot be exported by this renderer yet"). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ThumbnailLinesTest {
    private fun tile(r: Int, g: Int, b: Int) = ByteArray(TILE_SIZE * TILE_SIZE * 4).also {
        for (i in 0 until TILE_SIZE * TILE_SIZE) { it[i * 4] = r.toByte(); it[i * 4 + 1] = g.toByte(); it[i * 4 + 2] = b.toByte(); it[i * 4 + 3] = -1 }
    }

    @Test fun aDrawingWithALineOnTopGetsItsPicture() {
        var id = 0
        val base = DocOps.newDocument("doc", "Art", 64, 64) { "id${id++}" }
        val layer = base.layers.single()
        val cel = layer.cels.single()
        // A red base with a blue line baked above it: the screen shows blue.
        val doc = base.copy(layers = listOf(layer.copy(cels = listOf(cel.copy(tiles = listOf("0_0"),
            slabs = listOf(SlabRef("0_0", 0), SlabRef("0_0", 2)))))))
        val contents = JbContents(doc, mapOf(Triple(layer.id, cel.id, "0_0") to tile(255, 0, 0)), emptyMap(),
            slabs = mapOf(SlabKey(layer.id, cel.id, "0_0", 2) to tile(0, 0, 255)))
        val thumb = DrawingLibrary.thumbnailOf(contents)
        assertNotNull("a line drawing gets a picture", thumb)
        assertEquals(0xFF0000FF.toInt(), thumb!!.getPixel(10, 10))
    }
}

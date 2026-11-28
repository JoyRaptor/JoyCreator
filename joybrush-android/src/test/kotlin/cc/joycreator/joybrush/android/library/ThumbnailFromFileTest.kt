package cc.joycreator.joybrush.android.library

import cc.joycreator.joybrush.androidkit.io.JbArchive
import cc.joycreator.joybrush.androidkit.io.JbContents
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.TILE_SIZE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * JBX-36 S3: the save on leaving reads only what changed, so the Library's picture is drawn from the file just written,
 * its tiles read from the file as the picture needs them. Each tile is read once (the rows go down the picture), and
 * the picture is the one the whole drawing in memory gives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ThumbnailFromFileTest {
    private val side = 4
    private fun tile(i: Int) = ByteArray(TILE_SIZE * TILE_SIZE * 4).also {
        for (p in 0 until TILE_SIZE * TILE_SIZE) {
            it[p * 4] = (i * 40).toByte(); it[p * 4 + 1] = (255 - i * 15).toByte(); it[p * 4 + 2] = ((p shr 8) and 0xFF).toByte(); it[p * 4 + 3] = -1
        }
    }

    private fun drawing(): JbContents {
        var id = 0
        val base = DocOps.newDocument("doc", "Art", side * TILE_SIZE, side * TILE_SIZE) { "id${id++}" }
        val layer = base.layers.single()
        val cel = layer.cels.single()
        val keys = (0 until side * side).map { DocOps.key(it % side, it / side) }
        val doc = base.copy(layers = listOf(layer.copy(cels = listOf(cel.copy(tiles = keys.sorted())))))
        return JbContents(doc, keys.withIndex().associate { (i, k) -> Triple(layer.id, cel.id, k) to tile(i) }, emptyMap())
    }

    /** The drawing's tiles, counting how often each is asked for. */
    private class Counting(private val inner: Map<Triple<String, String, String>, ByteArray>) : AbstractMap<Triple<String, String, String>, ByteArray>() {
        val asked = HashMap<Triple<String, String, String>, Int>()
        override val entries get() = inner.entries
        override val keys get() = inner.keys
        override fun containsKey(key: Triple<String, String, String>) = inner.containsKey(key)
        override fun get(key: Triple<String, String, String>): ByteArray? { asked.merge(key, 1, Int::plus); return inner[key] }
    }

    @Test fun eachTileIsAskedForOnceWhileThePictureIsDrawn() {
        val d = drawing()
        val counting = Counting(d.tiles)
        assertNotNull(DrawingLibrary.thumbnailOf(d.copy(tiles = counting)))
        val most = counting.asked.values.maxOrNull() ?: 0
        assertTrue("a tile was asked for $most times; read from a file each ask is a whole tile unpacked", most <= 1)
    }

    @Test fun thePictureFromTheFileIsThePictureOfTheDrawing() {
        val d = drawing()
        val file = File.createTempFile("thumb", ".joybrush").also { it.delete(); it.deleteOnExit() }
        JbArchive.save(file, d)
        val fromMemory = DrawingLibrary.thumbnailOf(d)!!
        val fromFile = DrawingLibrary.thumbnailOfFile(file)!!
        assertEquals(fromMemory.width, fromFile.width)
        assertEquals(fromMemory.height, fromFile.height)
        for (y in 0 until fromMemory.height step 7) for (x in 0 until fromMemory.width step 7)
            assertEquals("pixel $x,$y", fromMemory.getPixel(x, y), fromFile.getPixel(x, y))
    }
}

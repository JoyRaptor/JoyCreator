package cc.joycreator.joybrush.core.paint

import kotlin.test.Test
import kotlin.test.assertEquals

class ThumbnailsTest {

    /** A [w] x [h] premultiplied image, every pixel [px] (r, g, b, a). */
    private fun image(w: Int, h: Int, px: IntArray): ByteArray =
        ByteArray(w * h * 4) { i -> px[i % 4].toByte() }

    @Test
    fun aSolidColourStaysThatColour() {
        val out = Thumbnails.downsample(image(4, 4, intArrayOf(200, 40, 10, 255)), 4, 4, 2)
        assertEquals(4, out.size)
        for (c in out) assertEquals(0xFFC8280A.toInt(), c)
    }

    @Test
    fun anEmptyPixelIsZeroAndAHalfCoveredOneKeepsItsColour() {
        // A 2 x 1 image: one opaque red pixel beside an empty one, averaged into ONE pixel.
        val px = byteArrayOf(255.toByte(), 0, 0, 255.toByte(), 0, 0, 0, 0)
        val out = Thumbnails.downsample(px, 2, 1, 1)
        assertEquals(0xFFFF0000.toInt(), out[0])
        assertEquals(0, out[1])
        // Averaged 2 x 2 with three empty pixels: a quarter-opaque pixel that is still pure red, not dark red.
        val quad = ByteArray(16).also { it[0] = 255.toByte(); it[3] = 255.toByte() }
        val q = Thumbnails.downsample(quad, 2, 2, 2)
        assertEquals(64, q[0] ushr 24)
        assertEquals(0xFF0000, q[0] and 0xFFFFFF)
    }

    @Test
    fun aSizeThatIsNotAMultipleIsRefused() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { Thumbnails.downsample(ByteArray(3 * 3 * 4), 3, 3, 2) }
    }
}

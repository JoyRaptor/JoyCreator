package cc.joycreator.joybrush.androidkit.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** JB-2.03a — the colour the eyedropper reports is the colour a person SEES: paint at the layer's opacity, over the paper. */
class EyedropperTest {

    private fun px(r: Int, g: Int, b: Int, a: Int) = byteArrayOf(r.toByte(), g.toByte(), b.toByte(), a.toByte())

    @Test
    fun anEmptySpotShowsThePaper() {
        assertEquals(0xFFF6F4EE.toInt(), Eyedropper.seen(null, 0, 1f, 0xFFF6F4EE.toInt()))
    }

    @Test
    fun opaquePaintIsItselfWhateverThePaper() {
        // Premultiplied opaque red over white paper, layer opacity 1: red.
        assertEquals(0xFFFF0000.toInt(), Eyedropper.seen(px(255, 0, 0, 255), 0, 1f, 0xFFFFFFFF.toInt()))
    }

    @Test
    fun halfPaintOverPaperIsTheMix() {
        // Premultiplied 50% blue (0,0,128,128) over white: r = 0 + 255*(1-128/255) = 127; b = 128 + 127 = 255.
        val c = Eyedropper.seen(px(0, 0, 128, 128), 0, 1f, 0xFFFFFFFF.toInt())
        assertEquals(127, (c shr 16) and 0xFF)
        assertEquals(255, c and 0xFF)
        assertEquals(0xFF, (c ushr 24) and 0xFF, "an eyedropper result is always opaque")
    }

    @Test
    fun theLayersOpacityScalesThePaint() {
        // Opaque black at layer opacity 0.5 over white: 0*0.5 + 255*(1-0.5) = 127.5 -> 128 (rounded half up).
        val c = Eyedropper.seen(px(0, 0, 0, 255), 0, 0.5f, 0xFFFFFFFF.toInt())
        assertEquals(128, (c shr 16) and 0xFF)
    }

    @Test
    fun theCancelCircleIsARealCircle() {
        assertTrue(Eyedropper.insideCircle(10f, 10f, 10f, 10f, 12f))
        assertTrue(Eyedropper.insideCircle(22f, 10f, 10f, 10f, 12f))
        assertFalse(Eyedropper.insideCircle(19f, 19f, 10f, 10f, 12f), "a corner of the bounding square is outside")
    }
}

package cc.joycreator.joybrush.core.paper
import cc.joycreator.joybrush.core.doc.RectPx
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class PaperMipTest {
    private fun checker() = PaperTexture(8, 8, ByteArray(256) { i ->
        if (i % 4 == 3) 255.toByte() else if ((i / 4 % 8 + i / 32) % 2 == 0) 0 else 254.toByte()
    })
    @Test fun minificationAveragesEveryChannelAndWraps() {
        val t = checker(); val s = FloatArray(4)
        for (p in listOf(-12.7, 0.5, 8.5, 91.2)) {
            t.filtered(p, p, 2.0, s)
            for (c in 0..2) assertEquals(127f / 255f, s[c], 0.000001f)
            assertEquals(1f, s[3])
        }
    }
    @Test fun fractionalLodBlendsAndMagnificationIsUnchanged() {
        val t = checker(); val a = FloatArray(4); val b = FloatArray(4)
        t.bilinear(0.5, 0.5, a); t.filtered(0.5, 0.5, 0.25, b)
        assertTrue(a.contentEquals(b))
        t.filtered(0.5, 0.5, kotlin.math.sqrt(2.0), b)
        assertEquals(63.5f / 255f, b[0], 0.000001f)
    }
    @Test fun oddRectangularMipRetainsWholeImageAndRejectsInvalidFootprints() {
        val t = PaperTexture(3, 1, byteArrayOf(0, 0, 0, -1, 0, 0, 0, -1, -1, -1, -1, -1))
        val s = FloatArray(4); t.filtered(0.5, 0.5, 16.0, s)
        assertEquals(85f / 255f, s[0], 0.000001f)
        for (f in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY))
            assertFailsWith<IllegalArgumentException> { t.filtered(0.0, 0.0, f, s) }
    }
    @Test fun rasterMinifiesLookAndSurfaceBeforeLighting() {
        val look = LookEntry("test", "Test", "#FFFFFF", "look.png", "#7F7F7F", 0.5f, 32f, true)
        val surface = SurfaceEntry("test", "Test", "surface.png", 8, 0.5f, 0.1f, 32f, true)
        val tex = checker()
        val p = ResolvedPaper(surface, look, -1, 1f, 1f, 1f, true, false)
        val data = PaperRaster.render(p, tex, tex, RectPx(-3, 7, 12, 12))
        for (i in data.indices step 4) {
            for (c in 0..2) assertEquals(127, data[i + c].toInt() and 255)
            assertEquals(255, data[i + 3].toInt() and 255)
        }
    }
}

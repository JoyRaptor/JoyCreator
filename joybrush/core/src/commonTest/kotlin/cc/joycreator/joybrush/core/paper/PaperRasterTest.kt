package cc.joycreator.joybrush.core.paper

import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.doc.Paper
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaperRasterTest {
    private fun paper(base: Int = 0xFFDAC6A2.toInt(), look: LookEntry? = null, surface: SurfaceEntry? = null,
                      light: Boolean = true, tint: Boolean = false) = ResolvedPaper(surface, look, base, 1f, 1f, 1f, light, tint)
    private fun texture(size: Int = 64) = PaperTexture(size, size, ByteArray(size * size * 4) { i ->
        when (i % 4) { 0 -> (110 + (i / 4 * 73 % 35)).toByte(); 1 -> (110 + (i / 4 * 31 % 35)).toByte()
            2 -> (70 + (i / 4 * 97 % 150)).toByte(); else -> 255.toByte() }
    })
    private val look = LookEntry("test", "Test", "#DAC6A2", "look.png", "#808080", 2f, 32f, true)
    private val surface = SurfaceEntry("test", "Test", "surface.png", 64, 2f, 0.1f, 32f, true)

    @Test fun flatAndMissingTexturesKeepExactBase() {
        for (p in listOf(paper(), paper(look = look, surface = surface))) {
            val out = PaperRaster.render(p, null, null, RectPx(-20, 7, 9, 4))
            for (i in out.indices step 4) {
                assertEquals(218, out[i].toInt() and 255); assertEquals(198, out[i + 1].toInt() and 255)
                assertEquals(162, out[i + 2].toInt() and 255); assertEquals(255, out[i + 3].toInt() and 255)
            }
        }
    }
    @Test fun constantLookTintUsesItsMeanIncludingExplicitBaseColourTint() {
        val tex = PaperTexture(1, 1, byteArrayOf(64, 96, 120, -1))
        val p = PaperState.resolve(Paper(lookId = look.id, tint = look.base, light = false), PaperCatalogue(surfaces = emptyList(), looks = listOf(look)))
        assertTrue(p.tintSet, "an explicit tint equal to look.base still divides the look by its mean")
        val out = PaperRaster.render(p, tex, null, RectPx(0, 0, 2, 2))
        assertEquals(109, out[0].toInt() and 255)
        assertEquals(149, out[1].toInt() and 255)
        assertEquals(152, out[2].toInt() and 255)
    }
    @Test fun lightOffSurfaceDoesNotAffectAppearanceAndAmoledStaysBlack() {
        val p = paper(look = look, surface = surface, light = false)
        val rect = RectPx(0, 0, 24, 24)
        assertTrue(PaperRaster.render(p, texture(), null, rect).contentEquals(PaperRaster.render(p, texture(), texture(), rect)))
        val black = PaperRaster.render(paper(base = 0xFF000000.toInt(), surface = surface, light = false), null, texture(), rect)
        for (i in black.indices) assertEquals(if (i % 4 == 3) 255 else 0, black[i].toInt() and 255)
    }
    @Test fun localFramePreservesGlobalHashAndSamplesAtLargeSignedCoordinates() {
        val tex = texture(); val global = FloatArray(4); val local = FloatArray(4)
        for (corner in listOf(1e7 to -1e7, -1e7 to 1e7)) for (rotate in listOf(false, true)) for (seed in listOf(0, 10)) {
            val f = PaperRaster.localFrame(corner.first, corner.second, 2.0, 1.25, 32.0, 64)
            assertTrue(abs(f.localOriginX) < 64 && abs(f.localOriginY) < 64)
            for (k in 0 until 20) {
                val x = k * 0.13; val y = k * 0.29
                HexTile.sampleSurface(tex, corner.first / 2.5 + x, corner.second / 2.5 + y, 32.0, rotate, 0.1f, global, seed)
                PaperRaster.sampleLocal(f, tex, x, y, 32.0, rotate, local, 0.1f, seed)
                for (q in 0..3) assertEquals(global[q], local[q], 1f / 255f)
                HexTile.sampleLook(tex, corner.first / 2.5 + x, corner.second / 2.5 + y, 32.0, rotate, global, seed)
                PaperRaster.sampleLocal(f, tex, x, y, 32.0, rotate, local, seed = seed)
                for (q in 0..2) assertEquals(global[q], local[q], 1f / 255f)
            }
        }
    }
    @Test fun paperLookDoesNotRepeatAtOneTexturePeriod() {
        val data = PaperRaster.render(paper(look = look, light = false), texture(), null, RectPx(0, 0, 512, 64))
        var a = 0.0; var b = 0.0; var aa = 0.0; var bb = 0.0; var ab = 0.0; var n = 0
        for (y in 0 until 64) for (x in 0 until 384) {
            val av = (data[(y * 512 + x) * 4 + 2].toInt() and 255).toDouble()
            val bv = (data[(y * 512 + x + 128) * 4 + 2].toInt() and 255).toDouble()
            a += av; b += bv; aa += av * av; bb += bv * bv; ab += av * bv; n++
        }
        val correlation = (ab - a * b / n) / sqrt((aa - a * a / n) * (bb - b * b / n))
        assertTrue(abs(correlation) < 0.3, "period correlation=$correlation")
    }
}
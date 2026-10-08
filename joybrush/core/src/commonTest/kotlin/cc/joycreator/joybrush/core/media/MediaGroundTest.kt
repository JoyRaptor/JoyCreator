package cc.joycreator.joybrush.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JB-2.40 requirement 3 and §Q1, by hand-worked pixels: media lies on the pixels under it without changing one it does
 * not paint, and what it paints reads right over the paper.
 */
class MediaGroundTest {
    private val paper = doubleArrayOf(0.96, 0.94, 0.90)

    // Premultiplied grounds: an opaque ink line, a half-transparent grey wash, a soft red edge, and nothing.
    private val ink = doubleArrayOf(0.05, 0.05, 0.08, 1.0)
    private val grey = doubleArrayOf(0.25, 0.25, 0.25, 0.5)
    private val edge = doubleArrayOf(0.2, 0.0, 0.0, 0.2)
    private val clear = doubleArrayOf(0.0, 0.0, 0.0, 0.0)

    @Test fun renderOfNoMediaOverAGroundIsTheGroundToTheBit() {
        // u_relief = 0 in the app, so an empty pixel is lit as its base exactly: the result must be the ground itself,
        // not something that only looks the same over the paper (a grey wash at half alpha is not an opaque light grey).
        for (g in listOf(ink, grey, edge, clear)) {
            val px = MediaGround.layerPixel(MediaGround.base(g, paper), g, paper)
            for (i in 0 until 4) assertEquals(g[i], px[i], 0.0, "channel $i of ${g.toList()}")
        }
    }

    @Test fun eightBitGroundsSurviveTheTripExactly() {
        // Every 8-bit premultiplied value a look tile can hold, through the same arithmetic and back to bytes.
        for (a in 0..255 step 15) for (c in 0..a step 7) {
            val g = doubleArrayOf(c / 255.0, (a - c) / 255.0, c / 510.0, a / 255.0)
            val px = MediaGround.layerPixel(MediaGround.base(g, paper), g, paper)
            for (i in 0 until 4) assertEquals(Math8.byte(g[i]), Math8.byte(px[i]), "a=$a c=$c channel $i")
        }
    }

    @Test fun paintOverAGroundReadsAsTheLitResultOverThePaper() {
        // Watercolour glazed over an ink line, and over a grey wash: whatever the media made of the base, the layer pixel
        // over the paper colour is exactly that.
        for (g in listOf(ink, grey, edge, clear)) {
            val base = MediaGround.base(g, paper)
            for (lit in listOf(DoubleArray(3) { base[it] * 0.6 }, doubleArrayOf(0.2, 0.5, 0.7), doubleArrayOf(0.99, 0.98, 0.97))) {
                val shown = MediaGround.overPaper(MediaGround.layerPixel(lit, g, paper), paper)
                for (i in 0 until 3) assertEquals(lit[i], shown[i], 1e-9, "ground ${g.toList()} lit ${lit.toList()}")
            }
        }
    }

    @Test fun aGlazeOverInkDoesNotEraseIt() {
        // Check 3 of the spec: watercolour over ink glazes it. The ink stays at least as dark as it was.
        val base = MediaGround.base(ink, paper)
        val glazed = MediaGround.layerPixel(DoubleArray(3) { base[it] * 0.9 }, ink, paper)
        assertEquals(1.0, glazed[3], 1e-12, "an opaque ground stays opaque")
        assertTrue((0 until 3).all { glazed[it] <= ink[it] + 1e-12 })
    }

    private object Math8 { fun byte(v: Double) = kotlin.math.round(v * 255.0).toInt() }
}

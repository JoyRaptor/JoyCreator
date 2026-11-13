package cc.joycreator.joybrush.core.media

import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pencil takes the chosen colour (the owner, 2026-11-13): black stays the stick's graphite, a colour is a film. */
class StickInkTest {
    private val hb = Sticks.ALL.getValue("HB")
    private val black = doubleArrayOf(0.0, 0.0, 0.0)

    @Test fun blackIsTheSticksOwnGraphiteToTheBit() {
        for ((name, stick) in Sticks.ALL) {
            val plain = StickMaterial.of(stick)
            val inked = plain.inked(black)
            assertEquals(plain.flakeR, inked.flakeR, "$name: black must not move the flakes")
            assertFalse(inked.ink!!.tinted, "$name: black lays no film")
            assertTrue(inked.ink!!.k.all { it == 0.0 } && inked.ink!!.s.all { it == 0.0 }, "$name: black lays no film")
            assertEquals(plain, inked.copy(ink = null), "$name: nothing else about the material changes")
        }
    }

    @Test fun aGreyLightensTheFlakesAndWhiteIsAWhitePencil() {
        val base = StickMaterial.of(hb).flakeR
        val grey = stickInk(doubleArrayOf(0.5, 0.5, 0.5), base)
        val white = stickInk(doubleArrayOf(1.0, 1.0, 1.0), base)
        assertFalse(grey.tinted); assertFalse(white.tinted)
        assertTrue(grey.flakeR > base && grey.flakeR < white.flakeR, "a grey sits between the lead and white")
        assertEquals(1.0, white.flakeR, 1e-12)
    }

    @Test fun aColourIsAFilmWhoseThickLayerIsThatColour() {
        val red = stickInk(doubleArrayOf(0.8, 0.12, 0.1), 0.12)
        assertTrue(red.tinted)
        assertEquals(1.0, red.tint)
        assertEquals(0.12, red.flakeR, "a colour leaves the lead's own brightness alone")
        // Kubelka-Munk: a thick layer reflects R∞ = 1 + K/S − sqrt((K/S)² + 2K/S); red must stay the brightest channel.
        val rInf = DoubleArray(3) { val q = red.k[it] / red.s[it]; 1 + q - kotlin.math.sqrt(q * q + 2 * q) }
        assertTrue(rInf[0] > rInf[1] && rInf[0] > rInf[2], "thick red reads red: ${rInf.toList()}")
        fun lin(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        assertTrue(abs(rInf[0] - lin(0.8)) < 1e-9, "the thick layer is the chosen colour")
        assertTrue(red.k.all { it.isFinite() && it >= 0 } && red.s.all { it.isFinite() && it > 0 })
    }

    @Test fun everyColourGivesFiniteNumbers() {
        val steps = listOf(0.0, 0.01, 0.03, 0.5, 0.97, 1.0)
        for (r in steps) for (g in steps) for (b in steps) {
            val ink = stickInk(doubleArrayOf(r, g, b), 0.01243)
            assertTrue(ink.flakeR.isFinite() && ink.flakeR in 0.0..1.0, "flakeR for $r $g $b")
            assertTrue(ink.k.all { it.isFinite() && it >= 0 } && ink.s.all { it.isFinite() && it >= 0 }, "film for $r $g $b")
        }
    }

    @Test fun nearGreysDoNotFlipBetweenFlakesAndFilmAbruptlyInBrightness() {
        // Just under the grey threshold is flakes, just over it a film: both must be dark for a near-black colour.
        val under = stickInk(doubleArrayOf(0.05, 0.05, 0.05 + STICK_GREY_CHROMA * 0.9), 0.01243)
        val over = stickInk(doubleArrayOf(0.05, 0.05, 0.05 + STICK_GREY_CHROMA * 1.1), 0.01243)
        assertFalse(under.tinted); assertTrue(over.tinted)
        assertTrue(under.flakeR < 0.02)
        val rInf = DoubleArray(3) { val q = over.k[it] / over.s[it]; 1 + q - kotlin.math.sqrt(q * q + 2 * q) }
        assertTrue(rInf.all { it < 0.03 }, "a near-black colour is near black as a film too: ${rInf.toList()}")
    }
}

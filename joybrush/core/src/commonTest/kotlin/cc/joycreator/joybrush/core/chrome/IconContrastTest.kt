package cc.joycreator.joybrush.core.chrome

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The owner's icon rule: black over a picture lighter than middle grey, white with a shadow over one darker. */
class IconContrastTest {

    private fun grey(v: Int): Int = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    /** The sRGB grey whose lightness is nearest [l]. */
    private fun greyOfL(l: Float): Int = (0..255).minBy { abs(IconContrast.lightness(grey(it)) - l) }.let { grey(it) }

    private fun near(expected: Float, actual: Float, eps: Float = 0.2f) =
        assertTrue(abs(expected - actual) <= eps, "expected $expected, was $actual")

    @Test
    fun lightnessRunsFromBlackToWhiteAndMiddleGreyIsNot128() {
        near(0f, IconContrast.lightness(grey(0)))
        near(100f, IconContrast.lightness(grey(255)))
        // CIE middle grey is sRGB 119, not 128 (128 looks lighter than half: L* 53.6).
        near(50.0f, IconContrast.lightness(grey(119)))
        near(53.6f, IconContrast.lightness(grey(128)))
    }

    @Test
    fun aLittleLighterThanMiddleGreyIsBlackAndALittleDarkerIsWhite() {
        assertEquals(IconInk.DARK, IconContrast.inkFor(intArrayOf(greyOfL(52f)), null))
        assertEquals(IconInk.LIGHT, IconContrast.inkFor(intArrayOf(greyOfL(48f)), null))
        assertEquals(IconInk.DARK, IconContrast.inkFor(intArrayOf(grey(255)), null))
        assertEquals(IconInk.LIGHT, IconContrast.inkFor(intArrayOf(grey(0)), null))
    }

    @Test
    fun anIconKeepsItsInkUntilThePictureIsClearlyPastTheMiddle() {
        // Black icon: it stays black at 48 (inside the band) and turns white at 45.
        assertEquals(IconInk.DARK, IconContrast.inkFor(intArrayOf(greyOfL(48f)), IconInk.DARK))
        assertEquals(IconInk.LIGHT, IconContrast.inkFor(intArrayOf(greyOfL(45f)), IconInk.DARK))
        // White icon: it stays white at 52 and turns black at 55.
        assertEquals(IconInk.LIGHT, IconContrast.inkFor(intArrayOf(greyOfL(52f)), IconInk.LIGHT))
        assertEquals(IconInk.DARK, IconContrast.inkFor(intArrayOf(greyOfL(55f)), IconInk.LIGHT))
    }

    @Test
    fun anIconStraddlingBlackAndWhiteIsWhiteWithAShadow() {
        val mixed = intArrayOf(grey(255), grey(255), grey(255), grey(0))
        assertEquals(IconInk.LIGHT, IconContrast.inkFor(mixed, null))
        assertEquals(IconInk.LIGHT, IconContrast.inkFor(mixed, IconInk.DARK))
    }

    @Test
    fun theAverageIsTakenInLightNotInLightness() {
        // L* 30 and 70 are 40 apart (not mixed). Their luminances average to L* ≈ 55, so the icon is black; averaging the
        // two lightness numbers would say exactly 50, a coin toss.
        val two = intArrayOf(greyOfL(30f), greyOfL(70f))
        assertEquals(IconInk.DARK, IconContrast.inkFor(two, null))
    }

    @Test
    fun noSamplesKeepsTheInkOrIsWhite() {
        assertEquals(IconInk.DARK, IconContrast.inkFor(IntArray(0), IconInk.DARK))
        assertEquals(IconInk.LIGHT, IconContrast.inkFor(IntArray(0), null))
    }

    @Test
    fun colourCountsByHowBrightItLooks() {
        // Pure yellow is light (L* 97) → black icon; pure blue is dark (L* 32) → white icon.
        assertEquals(IconInk.DARK, IconContrast.inkFor(intArrayOf(0xFFFFFF00.toInt()), null))
        assertEquals(IconInk.LIGHT, IconContrast.inkFor(intArrayOf(0xFF0000FF.toInt()), null))
    }
}

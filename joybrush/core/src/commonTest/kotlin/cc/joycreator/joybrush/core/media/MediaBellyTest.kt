package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.brush.BELLY_MODES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaBellyTest {
    private val blue = doubleArrayOf(0.22, 0.38, 0.75)
    private fun lum(c: DoubleArray) = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]

    @Test fun everyModeIsHandled() {
        for (m in BELLY_MODES) {
            val c = MediaBelly.colorFor(m, blue, 7, manual = doubleArrayOf(1.0, 0.0, 0.0), last = doubleArrayOf(0.0, 1.0, 0.0))
            if (m == "off") assertNull(c) else assertTrue(c != null && c.all { it in 0.0..1.0 }, m)
        }
    }

    @Test fun darkerIsDarkerAndLighterIsLighter() {
        assertTrue(lum(MediaBelly.colorFor("darker", blue, 1)!!) < lum(blue))
        assertTrue(lum(MediaBelly.colorFor("lighter", blue, 1)!!) > lum(blue))
    }

    @Test fun hslRoundTrips() {
        val (h, s, l) = MediaBelly.rgbToHsl(blue)
        val back = MediaBelly.hslToRgb(h, s, l)
        for (i in 0..2) assertEquals(blue[i], back[i], 1e-9)
    }

    @Test fun shiftIsTheSameForTheSameSeed() {
        assertEquals(MediaBelly.colorFor("shift", blue, 42)!!.toList(), MediaBelly.colorFor("shift", blue, 42)!!.toList())
    }
}

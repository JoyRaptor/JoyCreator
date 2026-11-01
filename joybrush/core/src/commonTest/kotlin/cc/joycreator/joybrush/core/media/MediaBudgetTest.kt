package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.brush.MEDIUM_DRY
import cc.joycreator.joybrush.core.brush.MEDIUM_PASTE
import cc.joycreator.joybrush.core.brush.MEDIUM_WET
import cc.joycreator.joybrush.core.layers.LayerBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Lead's store-format ruling (2026-10-07): lazy stores per medium, a ground beside them (R51), a ceiling in words. */
class MediaBudgetTest {
    @Test fun eachMediumCreatesOnlyTheStoresItWrites() {
        assertEquals(listOf("paper"), MediaStores.forMedium(MEDIUM_DRY))
        assertFalse("paper" in MediaStores.forMedium(MEDIUM_WET), "a watercolour stroke makes no paper tiles")
        assertEquals(listOf("p0", "p1"), MediaStores.forMedium(MEDIUM_PASTE))
        assertTrue(MediaStores.WATER.all { it in MediaStores.forMedium(MEDIUM_PASTE, thinned = true) }, "thinned oil carries water")
        for (m in listOf(MEDIUM_DRY, MEDIUM_WET, MEDIUM_PASTE)) assertTrue(MediaStores.forMedium(m).all { it in MediaStores.ALL })
    }

    @Test fun aStoreTileIsHalfFloatsAtRest() {
        assertEquals(512 * 1024, MediaStores.tileBytes(256))
        assertEquals(".f16", MediaStores.EXT)
    }

    @Test fun theGroundIsRgba8AndPartOfThePayload() {
        assertEquals(256 * 1024, MediaStores.tileBytes(256, MediaStores.GROUND), "the ground is a look tile's size")
        assertEquals(512 * 1024, MediaStores.tileBytes(256, "p0"))
        assertEquals(MediaStores.ALL + "g", MediaStores.PAYLOAD)
        assertEquals("L1" to "g", MediaStores.parse("L1#g"))
        assertEquals(null, MediaStores.parse("L1#mask"), "a mask is not a payload")
    }

    @Test fun theMediaCeilingIs256MbOnASixGbPhoneAndSaysSo() {
        assertEquals(256L shl 20, LayerBudget.mediaBudgetBytes(6L shl 30))
        assertEquals(LayerBudget.MIN_MEDIA_BYTES, LayerBudget.mediaBudgetBytes(0L))
        assertTrue("256 MB" in LayerBudget.mediaFullMessage(256L shl 20))
    }

    @Test fun theWindowIsItsOwnReservationAndIsCheckedBeforeItIsMade() {
        assertEquals(132L shl 20, LayerBudget.MEDIA_WINDOW_DRY_BYTES, "about 130 MB for pencil and oil")
        assertEquals(240L shl 20, LayerBudget.MEDIA_WINDOW_WET_BYTES, "about 240 MB once water runs")
        val mb = 1L shl 20
        assertTrue(LayerBudget.windowFits(availBytes = 600 * mb, thresholdBytes = 200 * mb, wet = false))
        assertFalse(LayerBudget.windowFits(availBytes = 500 * mb, thresholdBytes = 200 * mb, wet = true), "a low-memory margin of twice the threshold")
        assertTrue(LayerBudget.windowFits(availBytes = 520 * mb, thresholdBytes = 200 * mb, wet = true, haveBytes = LayerBudget.MEDIA_WINDOW_DRY_BYTES),
            "adding water to a dry window needs only the difference")
        assertTrue("watercolour" in LayerBudget.windowRefusal(wet = true))
    }
}

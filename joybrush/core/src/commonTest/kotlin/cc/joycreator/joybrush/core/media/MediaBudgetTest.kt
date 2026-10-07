package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.brush.MEDIUM_DRY
import cc.joycreator.joybrush.core.brush.MEDIUM_PASTE
import cc.joycreator.joybrush.core.brush.MEDIUM_WET
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.layers.LayerBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Lead's store-format ruling (2026-10-07): lazy stores per medium, media layers at their real cost, a ceiling in words. */
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

    @Test fun aMediaLayerCountsAtItsRealCost() {
        assertEquals(7, LayerBudget.slotsFor(LayerKind.MEDIA), "look 4 B/px + p0, p1, paper at 8 B/px = 28 B/px")
        assertEquals(1, LayerBudget.slotsFor(LayerKind.PAINT))
        assertEquals(9, LayerBudget.slotsUsed(listOf(LayerKind.PAINT, LayerKind.MEDIA, LayerKind.INK)))
        assertTrue(LayerBudget.roomFor(LayerKind.PAINT, listOf(LayerKind.MEDIA), 8))
        assertFalse(LayerBudget.roomFor(LayerKind.MEDIA, listOf(LayerKind.MEDIA), 13), "two media layers need 14 slots")
    }

    @Test fun theMediaCeilingIs256MbOnASixGbPhoneAndSaysSo() {
        assertEquals(256L shl 20, LayerBudget.mediaBudgetBytes(6L shl 30))
        assertEquals(LayerBudget.MIN_MEDIA_BYTES, LayerBudget.mediaBudgetBytes(0L))
        assertTrue("256 MB" in LayerBudget.mediaFullMessage(256L shl 20))
    }
}

package cc.joycreator.joybrush.android.select

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SelectPlacementTest {
    private val strip = RectF(0f, 1000f, 120f, 1800f)       // the tool strip, left edge
    private val button = RectF(900f, 2050f, 1060f, 2210f)   // the round button, bottom right

    /** The row wanted at x=40 slides right of the strip (the Select bench's tap on a line near the left edge). */
    @Test fun aRowOverTheStripSlidesRightOfIt() {
        assertEquals(126f, SelectPlacement.freeX(1400f, 1520f, 40f, 600f, 1080f, 6f, listOf(strip))!!, 0.01f)
    }

    @Test fun aBandWithoutTheStripKeepsTheWantedPlace() {
        assertEquals(40f, SelectPlacement.freeX(600f, 720f, 40f, 600f, 1080f, 6f, listOf(strip))!!, 0.01f)
    }

    /** The bar centred at the bottom slides left of the round button. */
    @Test fun theBarSlidesLeftOfTheRoundButton() {
        assertEquals(894f - 700f, SelectPlacement.freeX(2060f, 2170f, 300f, 700f, 1080f, 6f, listOf(button))!!, 0.01f)
    }

    @Test fun noGapWideEnoughGivesNull() {
        assertNull(SelectPlacement.freeX(2060f, 2170f, 100f, 1000f, 1080f, 6f, listOf(button)))
    }
}

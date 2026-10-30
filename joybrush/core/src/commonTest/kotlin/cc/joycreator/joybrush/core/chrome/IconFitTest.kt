package cc.joycreator.joybrush.core.chrome

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A picture goes into its box in its own shape: Joybot's tall art must not be squeezed into a square. */
class IconFitTest {

    // Joybot's two faces (joybot_neutral, joybot_smile) are 360 x 564 pixels.
    private val joyW = 360
    private val joyH = 564

    @Test
    fun joybotsTallArtInASquareKeepsItsShapeAndFillsTheHeight() {
        val box = IconFit.inside(0, 0, 52, 52, joyW, joyH)
        assertEquals(52, box.height)
        assertEquals(33, box.width)                 // 52 * 360 / 564 = 33.2
        assertEquals(9, box.left)                   // centred: (52 - 33) / 2
        assertEquals(0, box.top)
        // The old way (the whole square) was 1.0 wide for 1.0 tall: 57% too wide for art that is 0.638.
        assertTrue(box.width.toDouble() / box.height < 0.7, "he must stay narrower than tall")
    }

    @Test
    fun theShapeIsKeptWithinAPixelAtTheSizesTheButtonUses() {
        for (size in listOf(26, 39, 52, 78, 104, 130)) {
            val box = IconFit.inside(0, 0, size, size, joyW, joyH)
            val wanted = size.toDouble() * joyW / joyH
            assertTrue(abs(box.width - wanted) <= 0.5 + 1e-9, "size $size: width ${box.width}, wanted $wanted")
            assertEquals(size, box.height)
        }
    }

    @Test
    fun aWidePictureFillsTheWidthAndIsCentredUpAndDown() {
        val box = IconFit.inside(0, 0, 40, 40, 200, 100)
        assertEquals(40, box.width)
        assertEquals(20, box.height)
        assertEquals(10, box.top)
        assertEquals(0, box.left)
    }

    @Test
    fun aSquarePictureFillsASquareBoxExactly() {
        assertEquals(FitBox(5, 7, 45, 47), IconFit.inside(5, 7, 45, 47, 128, 128))
    }

    @Test
    fun aBoxAwayFromTheOriginKeepsItsPosition() {
        val box = IconFit.inside(100, 200, 152, 252, joyW, joyH)
        assertEquals(252, box.bottom)
        assertEquals(200, box.top)
        assertEquals(100 + 9, box.left)
        assertEquals(100 + 9 + 33, box.right)
    }

    @Test
    fun theFittedBoxNeverLeavesTheBox() {
        for (w in listOf(1, 2, 7, 33, 360, 5000)) for (h in listOf(1, 3, 9, 564, 4000)) {
            val box = IconFit.inside(10, 20, 62, 72, w, h)
            assertTrue(box.left >= 10 && box.top >= 20 && box.right <= 62 && box.bottom <= 72, "$w x $h gave $box")
            assertTrue(box.width >= 1 && box.height >= 1, "$w x $h left nothing: $box")
        }
    }

    @Test
    fun aPictureWithNoSizeGetsTheWholeBox() {
        assertEquals(FitBox(0, 0, 52, 52), IconFit.inside(0, 0, 52, 52, 0, 564))
        assertEquals(FitBox(0, 0, 52, 52), IconFit.inside(0, 0, 52, 52, 360, -1))
        assertEquals(FitBox(3, 3, 3, 3), IconFit.inside(3, 3, 3, 3, 360, 564))
    }
}

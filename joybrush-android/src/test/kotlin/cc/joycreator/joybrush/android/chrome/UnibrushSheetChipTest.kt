package cc.joycreator.joybrush.android.chrome

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * BRUSH-UI item 1 (owner, 11-14): unselected Unibrush chips were solid white under white words. The faint 8% fill had its
 * alpha reset to 255. An unselected chip's fill must stay faint so its word reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UnibrushSheetChipTest {
    private fun drawn(selected: Boolean): Bitmap {
        val chip = UnibrushSheetChip(ChromeKit(RuntimeEnvironment.getApplication()), "Square", "Square tip") {}
        chip.isSelected = selected
        chip.measure(View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(44, View.MeasureSpec.EXACTLY))
        chip.layout(0, 0, 120, 44)
        return Bitmap.createBitmap(120, 44, Bitmap.Config.ARGB_8888).also { chip.draw(Canvas(it)) }
    }

    @Test fun anUnselectedChipsFillIsFaintNotSolid() {
        // A point inside the rounded fill, clear of the centred word.
        val a = Color.alpha(drawn(selected = false).getPixel(10, 22))
        assertTrue("unselected fill alpha $a should be faint", a in 1..80)
    }

    @Test fun aSelectedChipIsStillSolidCyan() {
        val a = Color.alpha(drawn(selected = true).getPixel(10, 22))
        assertTrue("selected fill alpha $a", a > 200)
    }
}

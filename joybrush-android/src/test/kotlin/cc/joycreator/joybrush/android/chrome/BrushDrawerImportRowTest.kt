package cc.joycreator.joybrush.android.chrome

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import cc.joycreator.joybrush.android.select.testBrush
import cc.joycreator.joybrush.core.chrome.BrushShelf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** BRUSH-IMPORT: the drawer's way in, "Import…" under the shelf names, with a hover label; absent without a handler. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
class BrushDrawerImportRowTest {
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun row(drawer: View) = descendants(drawer).filterIsInstance<TextView>().filter { it.text.toString() == "Import…" }

    @Test fun theImportRowOpensThePickerAndHasAHoverLabel() {
        val kit = ChromeKit(RuntimeEnvironment.getApplication())
        var asked = 0
        val drawer = BrushDrawerView(kit, listOf(testBrush("a", name = "A")), BrushShelf.Kind.ALL, null, onPick = {},
            onImport = { asked++ })
        val rows = row(drawer)
        assertEquals(1, rows.size)
        assertTrue(rows.single().contentDescription.toString().contains("Procreate"))
        rows.single().performClick()
        assertEquals(1, asked)
    }

    @Test fun aPictureTipBrushWithoutItsPicturesStillGetsARowAndNeverThrows() {
        // Tablet 11-13: an imported Photoshop brush's stand-in asked for tip pictures the drawer does not have and the app
        // closed. The stand-in draws the disc instead.
        val kit = ChromeKit(RuntimeEnvironment.getApplication())
        val tipped = testBrush("supplies.clod", name = "CLOD 1").let {
            it.copy(tip = it.tip.copy(sampled = cc.joycreator.joybrush.core.brush.SampledTipSpec(listOf("tips/a.png"))))
        }
        val drawer = BrushDrawerView(kit, listOf(tipped), BrushShelf.Kind.ALL, null, onPick = {})
        drawer.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.AT_MOST))
        drawer.layout(0, 0, drawer.measuredWidth, drawer.measuredHeight)
        assertTrue(descendants(drawer).filterIsInstance<android.widget.ImageView>().isNotEmpty())
    }

    @Test fun noHandlerNoRow() {
        val kit = ChromeKit(RuntimeEnvironment.getApplication())
        val drawer = BrushDrawerView(kit, listOf(testBrush("a", name = "A")), BrushShelf.Kind.ALL, null, onPick = {})
        assertTrue(row(drawer).isEmpty())
    }
}

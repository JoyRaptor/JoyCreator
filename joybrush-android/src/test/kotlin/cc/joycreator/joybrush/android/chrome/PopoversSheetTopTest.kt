package cc.joycreator.joybrush.android.chrome

import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 11-27 (emulator after-shots): the tool strip showed through a tall see-through brush sheet. The strip now moves up to
 * end above an open bottom sheet, so Popovers says where the sheet's top is and when it moves or closes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PopoversSheetTopTest {
    private val context = RuntimeEnvironment.getApplication()
    private val kit = ChromeKit(context)
    private val host = FrameLayout(context)

    private fun layout(height: Int) {
        host.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 1080, height)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun aSheetSaysWhereItsTopIsAndTellsWhenItOpensMovesAndCloses() {
        layout(2000)
        val popovers = Popovers(kit, host)
        var told = 0
        popovers.onSheetMoved = { told++ }
        assertNull(popovers.sheetTopPx())
        shadowOf(Looper.getMainLooper()).idle()
        val content = View(context).apply { layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 900) }
        popovers.showSheet(content, maxWidthDp = 600f, alignEnd = false, modal = false)
        layout(2000)
        val top = popovers.sheetTopPx()!!
        assertTrue("the sheet stands on the bottom edge, about its height up: $top", top in 1000..1100)
        assertTrue("opening it told the strip", told >= 1)
        val afterOpen = told
        content.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 1200)
        content.requestLayout()
        layout(2000)
        assertTrue("a taller sheet moved its top up", popovers.sheetTopPx()!! < top)
        assertTrue("and told the strip", told > afterOpen)
        val beforeClose = told
        popovers.close()
        assertNull(popovers.sheetTopPx())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("closing it told the strip once", beforeClose + 1, told)
    }

    @Test fun aPanelBesideAButtonIsNotASheet() {
        layout(2000)
        val popovers = Popovers(kit, host)
        var told = 0
        popovers.onSheetMoved = { told++ }
        val anchor = View(context)
        host.addView(anchor, FrameLayout.LayoutParams(100, 100))
        layout(2000)
        popovers.show(View(context).apply { minimumHeight = 300 }, anchor)
        layout(2000)
        assertNull(popovers.sheetTopPx())
        shadowOf(Looper.getMainLooper()).idle()
        popovers.close()
        assertEquals(0, told)
    }
}

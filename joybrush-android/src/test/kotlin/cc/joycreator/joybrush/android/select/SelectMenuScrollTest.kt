package cc.joycreator.joybrush.android.select

import android.content.res.Configuration
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import cc.joycreator.joybrush.android.chrome.ChromeKit
import cc.joycreator.joybrush.android.chrome.Popovers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SelectMenuScrollTest {
    private fun descendants(v: View): List<View> = listOf(v) +
        if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    @Test fun shortWindowWithLargeTextAndPasteCanReachTheLastSettingsChoice() {
        val app = RuntimeEnvironment.getApplication()
        val context = app.createConfigurationContext(Configuration(app.resources.configuration).apply { fontScale = 1.8f })
        val kit = ChromeKit(context)
        var dimChanges = 0
        val settings = SelectSettingsView(kit, false, false, {}, {}, canPasteLines = true,
            showLockedOption = true, onDimRest = { dimChanges++ })
        val scroll = SelectMenuScrollView(context, settings) { kit.dpi(36f) }
        layout(scroll, kit.dpi(260f), kit.dpi(240f))
        assertTrue(scroll.height <= kit.dpi(204f))
        assertTrue(settings.height > scroll.height)
        val controls = descendants(settings)
        assertTrue(controls.any { it.contentDescription == "Paste the copied editable lines onto this layer" })
        val last = controls.first { it.contentDescription == "Dim the rest while selecting: on" }
        assertEquals(kit.dpi(40f), last.height)
        scroll.scrollTo(0, settings.height)
        val lastAt = IntArray(2); val scrollAt = IntArray(2)
        last.getLocationOnScreen(lastAt); scroll.getLocationOnScreen(scrollAt)
        assertTrue(lastAt[1] >= scrollAt[1])
        assertTrue(lastAt[1] + last.height <= scrollAt[1] + scroll.height)
        last.performClick()
        assertEquals(1, dimChanges)
    }

    @Test fun moreLengthRowsKeepTheirTargetsAndReflowWhenTheHeightChanges() {
        val context = RuntimeEnvironment.getApplication()
        val kit = ChromeKit(context)
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        repeat(9) { i -> list.addView(TextView(context).apply {
            text = "Action $i with a longer explanation"
            textSize = 24f
            minHeight = kit.dpi(40f)
        }) }
        var reserve = kit.dpi(36f)
        val scroll = SelectMenuScrollView(context, list) { reserve }
        layout(scroll, kit.dpi(220f), kit.dpi(240f))
        assertTrue(list.height > scroll.height)
        assertTrue((0 until list.childCount).all { list.getChildAt(it).height >= kit.dpi(40f) })
        scroll.scrollTo(0, list.height)
        assertTrue(scroll.scrollY > 0)
        reserve = kit.dpi(80f)
        scroll.requestLayout()
        layout(scroll, kit.dpi(220f), kit.dpi(180f))
        assertTrue(scroll.height <= kit.dpi(100f))
        scroll.scrollTo(0, list.height)
        assertEquals(list.height - scroll.height, scroll.scrollY)
    }

    @Test fun wrappingAMenuKeepsOutsideTapDismissalWithoutActivatingAnAction() {
        val context = RuntimeEnvironment.getApplication()
        val kit = ChromeKit(context)
        val host = FrameLayout(context)
        val anchor = View(context)
        host.addView(anchor, FrameLayout.LayoutParams(40, 40))
        layout(host, kit.dpi(320f), kit.dpi(240f))
        var actionCount = 0
        val content = TextView(context).apply { text = "Action"; setOnClickListener { actionCount++ } }
        val popovers = Popovers(kit, host)
        popovers.show(SelectMenuScrollView(context, content) { kit.dpi(36f) }, anchor, widthDp = 220f)
        assertTrue(popovers.isOpen)
        val outsideCatcher = host.getChildAt(host.childCount - 2)
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 1f, 1f, 0)
        assertTrue(outsideCatcher.dispatchTouchEvent(down))
        down.recycle()
        assertFalse(popovers.isOpen)
        assertEquals(0, actionCount)
    }
}

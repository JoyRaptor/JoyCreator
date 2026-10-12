package cc.joycreator.joybrush.android.select

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import cc.joycreator.joybrush.android.chrome.ChromeKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SelectToastTest {
    private fun layout(root: FrameLayout) {
        root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 400, 800)
    }

    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    private fun toastSurface(root: FrameLayout): LinearLayout = root.getChildAt(root.childCount - 1) as LinearLayout

    @Test fun actionIsLabelledRunsOnceAndDisposeRemovesToast() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        layout(root)
        val toast = SelectToast(ChromeKit(activity), root, { 0xFFFFFFFF.toInt() })
        var calls = 0
        toast.show("Saved", "Undo") { calls++ }
        layout(root)

        val action = views(toastSurface(root)).first { it.contentDescription == "Undo" }
        assertNotNull(action.contentDescription)
        assertTrue(action.isClickable)
        action.performClick()
        action.performClick()
        assertEquals(1, calls)

        toast.dispose()
        assertEquals(0, root.childCount)
    }

    @Test fun staleInkReplyCannotRestyleNewToastAndDismissInvalidatesReply() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        layout(root)
        val replies = mutableListOf<(IntArray) -> Unit>()
        val toast = SelectToast(ChromeKit(activity), root, { 0xFFFFFFFF.toInt() }) { _, reply -> replies += reply }

        toast.show("First")
        layout(root)
        shadowOf(activity.mainLooper).idle()
        val stale = replies.last()
        toast.dismiss()
        toast.show("Second")
        layout(root)
        shadowOf(activity.mainLooper).idle()
        val active = toastSurface(root)
        val activeMessage = active.getChildAt(0) as TextView
        replies.last()(IntArray(3) { 0xFFFFFFFF.toInt() })
        val brightInk = activeMessage.currentTextColor
        stale(IntArray(3) { 0xFF000000.toInt() })
        assertEquals(brightInk, activeMessage.currentTextColor)

        toast.dismiss()
        val before = activeMessage.currentTextColor
        replies.last()(IntArray(3) { 0xFF000000.toInt() })
        assertEquals(before, activeMessage.currentTextColor)
        toast.dispose()
        assertEquals(0, root.childCount)
    }
}

package cc.joycreator.joybrush.android.select

import android.view.View
import android.view.ViewGroup
import cc.joycreator.joybrush.android.chrome.ChromeKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SelectRowCapabilityTest {
    private class ActionsStub : SelectRowView.Actions {
        val calls = mutableListOf<String>()
        override fun colour(anchor: View) { calls += "colour" }
        override fun brush(anchor: View) { calls += "brush" }
        override fun weight(factor: Float, done: Boolean) { calls += "weight:$factor:$done" }
        override fun weightSlider(anchor: View) { calls += "weight-slider" }
        override fun nudge() { calls += "nudge" }
        override fun duplicate() { calls += "duplicate" }
        override fun delete() { calls += "delete" }
        override fun flip(horizontal: Boolean) { calls += if (horizontal) "flip-h" else "flip-v" }
        override fun more(anchor: View) { calls += "more" }
        override fun fill() { calls += "fill" }
    }

    private fun descendants(v: View): List<View> = listOf(v) +
        if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()

    private fun row(canFill: Boolean = true, canEdit: Boolean = true, canMore: Boolean): Pair<SelectRowView, ActionsStub> {
        val actions = ActionsStub()
        val row = SelectRowView(ChromeKit(RuntimeEnvironment.getApplication()), actions)
        row.showPixels(0xFF336699.toInt(), canFill = canFill, canEdit = canEdit, canMore = canMore)
        return row to actions
    }

    @Test fun pixelRowIsEmptyWhenNoActionsAreAvailable() {
        val (row, _) = row(canFill = false, canEdit = false, canMore = false)
        assertEquals(0, row.childCount)
        assertFalse(descendants(row).any { it.isClickable })
    }

    @Test fun pixelRowOmitsMoreWhenNoMoreActionIsAvailable() {
        val (row, _) = row(canMore = false)
        assertFalse(descendants(row).any { it.contentDescription == "More actions for the selected paint" })
        assertTrue("last action must not be followed by an empty separator", row.getChildAt(row.childCount - 1).isClickable)
    }

    @Test fun pixelRowKeepsMoreActionWhenCallerReportsSupportedContent() {
        val (row, actions) = row(canMore = true)
        descendants(row).single { it.contentDescription == "More actions for the selected paint" }.performClick()
        assertEquals(listOf("more"), actions.calls)
    }

    @Test fun supportedPixelFillAndEditActionsRemainWired() {
        val (row, actions) = row(canFill = true, canEdit = true, canMore = true)
        val controls = descendants(row)
        controls.single { it.contentDescription == "Fill the selection with the current colour" }.performClick()
        controls.single { it.contentDescription == "Delete" }.performClick()
        controls.single { it.contentDescription == "Flip horizontal" }.performClick()
        controls.single { it.contentDescription == "Flip vertical" }.performClick()
        assertEquals(listOf("fill", "delete", "flip-h", "flip-v"), actions.calls)
    }
}

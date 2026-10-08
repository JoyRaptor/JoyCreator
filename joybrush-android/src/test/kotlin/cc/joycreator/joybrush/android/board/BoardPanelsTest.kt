package cc.joycreator.joybrush.android.board

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import com.fadcam.ui.faditor.tools.DrawerFill
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h640dp-mdpi")
class BoardPanelsTest {
    @Test fun invalidRangeKeepsBothNumbersAndRunsCustomValidatorOnlyOncePerApply() {
        val panels = BoardPanels(activity())
        var validations=0
        var committed:List<String>?=null
        panels.formValidated("Export range",listOf("From" to "5","To" to "2"),{ values ->
            validations++
            if(values[0].toInt() > values[1].toInt()) "From must come before To" else null
        }) { committed=it }
        val form=dialog()
        button(form,"Apply").performClick()
        assertTrue(form.isShowing)
        assertEquals("5",input(form,"From").text.toString())
        assertEquals("2",input(form,"To").text.toString())
        assertEquals("From must come before To",input(form,"From").error)
        assertEquals(1,validations)
        assertNull(committed)
        input(form,"To").setText("7")
        button(form,"Apply").performClick()
        assertEquals(2,validations)
        assertEquals(listOf("5","7"),committed)
        assertFalse(form.isShowing)
    }
    private fun activity() = Robolectric.buildActivity(Activity::class.java).setup().get()
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun dialog(): Dialog = ShadowDialog.getLatestDialog()
    private fun children(dialog: Dialog) = views(dialog.window!!.decorView)
    private fun button(dialog: Dialog, text: String) = children(dialog).filterIsInstance<Button>().first { it.text.toString() == text }
    private fun input(dialog: Dialog, name: String) = children(dialog).filterIsInstance<EditText>().first { it.contentDescription == name }

    @Test fun invalidDimensionsStayOpenWithValuesAndInlineErrors() {
        val panels = BoardPanels(activity())
        var calls = 0
        panels.form("Size", listOf("Width" to "120", "Height" to "60")) { calls++ }
        val dialog = dialog()
        val width = input(dialog, "Width")
        // Exercise the validator independently of the numeric keyboard's character filter.
        width.inputType = android.text.InputType.TYPE_CLASS_TEXT
        for (invalid in listOf("", "cat", "1.5", "2147483648", "0", "-2")) {
            width.setText(invalid)
            button(dialog, "Apply").performClick()
            assertTrue(dialog.isShowing)
            assertNotNull(width.error)
            assertEquals(invalid, width.text.toString())
            assertEquals("60", input(dialog, "Height").text.toString())
            assertEquals(0, calls)
        }
        width.setText("240")
        button(dialog, "Apply").performClick()
        assertFalse(dialog.isShowing)
        assertEquals(1, calls)
    }

    @Test fun signedCoordinatesAndTrimmedNameReachCallbackAfterDismiss() {
        val panels = BoardPanels(activity())
        var result: List<String>? = null
        panels.form("Move", listOf("Name" to " Walk ", "X" to "-12", "Y" to "0")) {
            assertFalse(dialog().isShowing)
            result = it
        }
        button(dialog(), "Apply").performClick()
        assertEquals(listOf("Walk", "-12", "0"), result)
    }

    @Test fun emptyNameStaysEditableAndCancelDoesNotCommit() {
        val panels = BoardPanels(activity())
        var committed = false
        panels.form("Name", listOf("Name" to "   ")) { committed = true }
        val dialog = dialog()
        button(dialog, "Apply").performClick()
        assertTrue(dialog.isShowing)
        assertEquals("Enter a name", input(dialog, "Name").error)
        button(dialog, "Cancel").performClick()
        assertFalse(dialog.isShowing)
        assertFalse(committed)
    }

    @Test fun menuDismissesBeforeOpeningNestedConfirmationAndCloseCancelsIt() {
        val panels = BoardPanels(activity())
        var confirmed = false
        panels.menu("Boards", listOf("Remove")) {
            assertEquals(0, it)
            assertFalse(dialog().isShowing)
            panels.confirm("Remove?", "You can undo this", "Remove") { confirmed = true }
        }
        val menu = dialog()
        button(menu, "Remove").performClick()
        val confirm = dialog()
        assertNotSame(menu, confirm)
        assertTrue(confirm.isShowing)
        panels.close()
        assertFalse(confirm.isShowing)
        assertFalse(confirmed)
    }

    @Test fun drawerFollowsTransparencyWithoutDimAndRetainsLargeActions() {
        val activity = activity()
        DrawerFill.setSeeThroughPct(activity, 50)
        val panels = BoardPanels(activity)
        panels.confirm("Move?", "Move every frame together", "Move") {}
        val dialog = dialog()
        val window = dialog.window!!
        assertEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        assertEquals(0, Color.alpha((window.decorView.background as ColorDrawable).color))
        val panel = children(dialog).filterIsInstance<LinearLayout>().first { it.background is GradientDrawable }
        assertEquals(DrawerFill.fill(activity), (panel.background as GradientDrawable).color!!.defaultColor)
        DrawerFill.setSeeThroughPct(activity, 80)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(DrawerFill.fill(activity), (panel.background as GradientDrawable).color!!.defaultColor)
        assertTrue(Color.alpha((panel.background as GradientDrawable).color!!.defaultColor) < 128)
        assertTrue(button(dialog, "Move").minimumHeight >= 44)
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE, window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
        panels.close()
    }
}

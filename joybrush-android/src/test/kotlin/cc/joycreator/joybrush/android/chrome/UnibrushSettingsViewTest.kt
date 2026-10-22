package cc.joycreator.joybrush.android.chrome

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import cc.joycreator.joybrush.core.brush.*
import cc.joycreator.joybrush.core.input.StabilizerPreference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UnibrushSettingsViewTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun click(view: View, description: String) {
        val control = descendants(view).single { it.contentDescription?.toString() == description }
        assertTrue(description, control.performClick())
    }
    private fun sheet(recipe: BrushPreset = UnibrushModes.presets().first(),
                      change: (BrushPreset, Boolean) -> Unit = { _, _ -> },
                      save: (BrushPreset) -> Unit = {},
                      pick: ((UnibrushAssetRequest, (List<String>?) -> Unit) -> Unit)? = null) =
        UnibrushSettingsView(ChromeKit(context), recipe, StabilizerPreference(), View(context),
            change, {}, save, {}, { _, _ -> }, pick)

    @Test fun everyModeOpensEveryPageWithinTheScreenHeightBudget() {
        for (recipe in UnibrushModes.presets()) {
            val view = sheet(recipe)
            for (page in listOf("quick", "tip", "all")) {
                click(view, "Show $page brush settings")
                view.measure(View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.AT_MOST))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                assertTrue(recipe.name, view.measuredHeight <= minOf(900, (context.resources.displayMetrics.heightPixels * .70f).toInt()))
                assertEquals(recipe, view.currentRecipe())
                assertTrue(descendants(view).filter { it.isClickable }.all { !it.contentDescription.isNullOrBlank() })
            }
        }
    }

    @Test fun hostRefusalKeepsThePreviousRecipeAndAllowsTheNextValidEdit() {
        var refuse = true
        var accepted = 0
        val view = sheet(change = { _, _ -> if (refuse) throw BrushException("Resource admission refused") else accepted++ })
        val before = view.currentRecipe()
        click(view, "Shape A: Square")
        assertEquals(before, view.currentRecipe())
        assertEquals(0, accepted)
        refuse = false
        click(view, "Shape A: Square")
        assertEquals(0f, view.currentRecipe().tip.quad!!.a.roundness, 0f)
        assertEquals(1, accepted)
    }

    @Test fun newBrushReceivesTheAcceptedEditedModeWithoutChangingTheBuiltin() {
        val original = UnibrushModes.presets().first()
        var saved: BrushPreset? = null
        val view = sheet(original, save = { saved = it })
        click(view, "Shape A: Square")
        click(view, "Save these settings as a new mode. The next screen chooses its name")
        assertEquals(view.currentRecipe(), saved)
        assertEquals(1f, original.tip.quad!!.a.roundness, 0f)
        assertEquals(0f, saved!!.tip.quad!!.a.roundness, 0f)
    }

    @Test fun pickerCompletionFromAnOldPanelCannotReplaceTheCurrentRecipe() {
        var complete: ((List<String>?) -> Unit)? = null
        val view = sheet(pick = { request, done ->
            assertEquals(UnibrushAssetSlot.sampledTips, request.slot)
            complete = done
        })
        click(view, "Show all brush settings")
        click(view, "Choose one to sixteen ordered PNG tip images")
        assertNotNull(complete)
        click(view, "Show quick brush settings")
        val before = view.currentRecipe()
        complete!!(listOf("picked/safe/tip.png"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, view.currentRecipe())
        assertNull(view.currentRecipe().tip.sampled)
    }
}

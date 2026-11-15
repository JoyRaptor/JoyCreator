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

    @Test fun everyModeOpensWithinTheScreenHeightBudget() {
        for (recipe in UnibrushModes.presets()) {
            val view = sheet(recipe)
            view.measure(View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.AT_MOST))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            assertTrue(recipe.name, view.measuredHeight <= minOf(900, (context.resources.displayMetrics.heightPixels * .70f).toInt()))
            assertEquals(recipe, view.currentRecipe())
            assertTrue(descendants(view).filter { it.isClickable }.all { !it.contentDescription.isNullOrBlank() })
        }
    }

    /** BRUSH-UI section K.1: the shared top bar, no tabs, and every group button has somewhere to go. */
    @Test fun oneListUnderTheSharedTopBar() {
        val view = sheet()
        val labels = descendants(view).mapNotNull { it.contentDescription?.toString() }
        assertTrue(labels.none { it.startsWith("Show ") && it.endsWith(" brush settings") })
        for (g in listOf("Shape", "Pressure & tilt", "Colour", "Paper", "Stroke ends", "Feel")) click(view, "Go to $g settings")
        // K.4: the footprint opens the tip's shape editor.
        click(view, "Footprint: the mark this brush leaves as the pen tilts. Tap to edit the tip's shape")
        click(view, "Keep these settings and close")
    }

    /** BRUSH-UI section K.3: with a picture maker, every slider shows a picture at both ends; Softness asks for dabs. */
    @Test fun slidersShowPicturesAtBothEnds() {
        val parts = ArrayList<TestStroke.Part>()
        val view = UnibrushSettingsView(ChromeKit(context), UnibrushModes.presets().first(), StabilizerPreference(), View(context),
            { _, _ -> }, {}, {}, {}, { _, _ -> }, endPicture = { _, _, _, part, _, _ -> parts += part; {} })
        val pictures = descendants(view).filterIsInstance<android.widget.ImageView>().filter { it.contentDescription == null }
        assertTrue("${pictures.size} end pictures", pictures.size >= 10)
        // Attached and laid out, as on the phone: the pictures are asked for once their views have a size.
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activity.setContentView(view, ViewGroup.LayoutParams(420, 4000))
        view.measure(View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(parts.toString(), parts.isNotEmpty())
    }

    @Test fun cancelPutsTheModeBackAsItOpened() {
        val original = UnibrushModes.presets().first()
        var last: BrushPreset? = null
        var closed = 0
        val view = UnibrushSettingsView(ChromeKit(context), original, StabilizerPreference(), View(context),
            { p, _ -> last = p }, {}, {}, { closed++ }, { _, _ -> })
        click(view, "Shape A: Square")
        assertEquals(0f, last!!.tip.quad!!.a.roundness, 0f)
        click(view, "Cancel: put the brush back as it was and close")
        assertEquals(original, last)
        assertEquals(1, closed)
    }

    @Test fun slidersSayWhatEachEndDoes() {
        val view = sheet()
        val words = descendants(view).filterIsInstance<android.widget.TextView>().map { it.text.toString() }
        assertTrue(words.containsAll(listOf("Small", "Large", "Crisp", "Soft")))
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
        click(view, "Choose one to sixteen ordered PNG tip images")
        assertNotNull(complete)
        click(view, "Shape A: Square")   // a structural edit rebuilds the panel the picker belonged to
        val before = view.currentRecipe()
        complete!!(listOf("picked/safe/tip.png"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, view.currentRecipe())
        assertNull(view.currentRecipe().tip.sampled)
    }
    @Test fun secondColourPickerIsAvailableWithoutChangingTheRecipe() {
        val recipe=UnibrushModes.presets().first()
        var picked=0
        val view=UnibrushSettingsView(ChromeKit(context), recipe, StabilizerPreference(), View(context),
            {_,_->}, {}, {}, {}, {_,_->}, onChooseSecondaryColour={picked++})
        click(view, "Choose the background colour used by Secondary colour mix")
        assertEquals(1,picked)
        assertEquals(recipe,view.currentRecipe())
    }
}

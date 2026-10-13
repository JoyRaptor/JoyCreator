package cc.joycreator.joybrush.android.chrome

import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.TextView
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_WATERCOLOUR
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.VERSION_WATERCOLOUR
import cc.joycreator.joybrush.core.brush.WatercolourSpec
import cc.joycreator.joybrush.core.chrome.BrushTuning
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WatercolourBrushSettingsTest {
    private fun brush() = BrushPreset(id = "wc", name = "Watercolour", engine = ENGINE_WATERCOLOUR,
        version = VERSION_WATERCOLOUR, watercolour = WatercolourSpec(), size = Param(300f),
        flow = Param(.89f), opacity = Param(.8f), spacing = .15f)
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else emptyList()

    @Test fun opensWithLivePreviewAndToggleEditsPersistIntoTheActualBrush() {
        val kit = ChromeKit(RuntimeEnvironment.getApplication())
        val p = brush()
        val preview = View(kit.context)
        var latest = emptyMap<String, Float>()
        var committed = false
        val sheet = BrushSettingsView(kit, p, emptyMap(), preview,
            onChange = { positions, done -> latest = positions; committed = done },
            onReset = {}, onTest = {}, onDone = {})
        assertSame(sheet, preview.parent)
        assertTrue(descendants(sheet).any { it is ScrollView })
        assertEquals(2, descendants(sheet).count { it is CurveEditorView })
        val invert = descendants(sheet).filterIsInstance<CheckBox>().single { it.text == "Invert wash texture" }
        assertFalse(invert.isChecked)
        invert.performClick()
        assertTrue(committed)
        val tuned = BrushTuning.apply(p, mapOf(p.id to latest))
        assertTrue(tuned.watercolour!!.backdropInverted)
        assertEquals(p.watercolour!!.sampleSeeds, tuned.watercolour!!.sampleSeeds)
        assertEquals(p.watercolour!!.pressureBezier, tuned.watercolour!!.pressureBezier)
        assertEquals(p.watercolour!!.tiltBezier, tuned.watercolour!!.tiltBezier)
    }

    @Test fun resetRestoresCheckboxesWithoutEmittingASecondTuningChange() {
        val kit = ChromeKit(RuntimeEnvironment.getApplication())
        var changes = 0
        var resets = 0
        val sheet = BrushSettingsView(kit, brush(), mapOf("watercolour.backdropInverted" to 1f), View(kit.context),
            onChange = { _, _ -> changes++ }, onReset = { resets++ }, onTest = {}, onDone = {})
        val invert = descendants(sheet).filterIsInstance<CheckBox>().single { it.text == "Invert wash texture" }
        assertTrue(invert.isChecked)
        descendants(sheet).filterIsInstance<TextView>().single { it.text == "Reset" }.performClick()
        assertFalse(invert.isChecked)
        assertEquals(1, resets)
        assertEquals(0, changes)
    }
}

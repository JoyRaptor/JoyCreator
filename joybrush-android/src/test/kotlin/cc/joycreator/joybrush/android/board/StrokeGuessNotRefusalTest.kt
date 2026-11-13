package cc.joycreator.joybrush.android.board

import android.graphics.Color
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.core.brush.BLEND_MULTIPLY
import cc.joycreator.joybrush.core.brush.BLEND_SCREEN
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_FILL
import cc.joycreator.joybrush.core.brush.Param
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JBX-05 (ledger A3, A5, A6): the toddler is never turned away. A hidden layer is shown and painted; the fill pen and a
 * multiply/screen glaze with a mask selected paint the layer itself, and the mask stays selected. No refusal sentence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StrokeGuessNotRefusalTest {
    private val surfaces = ArrayList<JbCanvasView>()
    @After fun pauseUnattachedSurfaces() { surfaces.forEach { it.onPause() } }

    private fun surface(): JbCanvasView = JbCanvasView(RuntimeEnvironment.getApplication()).also {
        surfaces.add(it)
        it.preset = BrushPreset(id = "stamp", name = "Stamp", size = Param(12f))
        it.colorArgb = Color.RED
        it.longPressEyedropper = false
    }
    private fun get(view: JbCanvasView, name: String): Any? = JbCanvasView::class.java.getDeclaredField(name)
        .also { it.isAccessible = true }.get(view)
    private fun set(view: JbCanvasView, name: String, value: Any?) {
        JbCanvasView::class.java.getDeclaredField(name).also { it.isAccessible = true }.set(view, value)
    }
    private fun begin(view: JbCanvasView) {
        JbCanvasView::class.java.declaredMethods.single { it.name == "startStroke" && it.parameterCount == 2 }
            .also { it.isAccessible = true }.invoke(view, false, null)
    }
    private class Heard(view: JbCanvasView) {
        val refused = ArrayList<String>(); val notices = ArrayList<String>()
        init { view.onRefused = { refused.add(it) }; view.onNotice = { notices.add(it) } }
    }
    private fun withMaskSelected(view: JbCanvasView) {
        set(view, "stackUi", view.layers.withMask(view.activeLayerId, true))
        set(view, "editingMask", true)
    }

    @Test fun aHiddenLayerIsShownAndPaintedInsteadOfRefused() {
        val view = surface(); val heard = Heard(view)
        set(view, "stackUi", view.layers.withVisible(view.activeLayerId, false))
        assertFalse(view.layers.active.visible)
        begin(view)
        assertTrue(heard.refused.isEmpty())
        assertTrue(view.strokeInProgress)
        assertTrue(view.layers.active.visible)
        assertEquals(1, heard.notices.size)
        assertTrue(heard.notices.single().contains("shown"))
        view.cancelInterruptedStroke()
    }

    @Test fun aGlazeWithTheMaskSelectedPaintsTheLayerAndKeepsTheMaskSelected() {
        for (blend in listOf(BLEND_MULTIPLY, BLEND_SCREEN)) {
            val view = surface(); val heard = Heard(view)
            view.preset = view.preset!!.copy(blend = blend)
            withMaskSelected(view)
            begin(view)
            assertTrue(blend, heard.refused.isEmpty())
            assertTrue(view.strokeInProgress)
            assertFalse(get(view, "strokeOnMask") as Boolean)
            assertEquals(view.activeLayerId, get(view, "strokeLayerId"))
            assertTrue(view.editingMask)
            assertTrue(heard.notices.single().contains("layer itself"))
            view.cancelInterruptedStroke()
        }
    }

    @Test fun theFillPenWithTheMaskSelectedFillsTheLayer() {
        val view = surface(); val heard = Heard(view)
        view.preset = BrushPreset(id = "fill", name = "Fill pen", engine = ENGINE_FILL, size = Param(8f))
        withMaskSelected(view)
        begin(view)
        assertTrue(heard.refused.toString(), heard.refused.isEmpty())
        assertFalse(get(view, "strokeOnMask") as Boolean)
        assertTrue(view.editingMask)
        assertTrue(heard.notices.single().contains("fill pen"))
        view.cancelInterruptedStroke()
    }

    @Test fun anOrdinaryBrushStillPaintsTheSelectedMask() {
        val view = surface(); val heard = Heard(view)
        withMaskSelected(view)
        begin(view)
        assertTrue(heard.refused.isEmpty())
        assertTrue(get(view, "strokeOnMask") as Boolean)
        assertTrue(heard.notices.isEmpty())
        view.cancelInterruptedStroke()
    }
}

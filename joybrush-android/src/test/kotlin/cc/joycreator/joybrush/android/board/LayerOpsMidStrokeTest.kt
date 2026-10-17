package cc.joycreator.joybrush.android.board

import android.graphics.Color
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.doc.BlendMode
import cc.joycreator.joybrush.core.layers.LayerStack
import cc.joycreator.joybrush.core.layers.LayerState
import cc.joycreator.joybrush.core.paint.UndoLog
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Just-works sweep: a layer change asked for with the pen down used to do nothing and say nothing. It now says
 * "Lift the pen first." and changes nothing; the stroke is never ended to make room for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayerOpsMidStrokeTest {
    private val surfaces = ArrayList<JbCanvasView>()
    @After fun pauseUnattachedSurfaces() { surfaces.forEach { it.onPause() } }

    private val sentence = "Lift the pen first."

    private fun surface(): JbCanvasView = JbCanvasView(RuntimeEnvironment.getApplication()).also {
        surfaces.add(it)
        // The fill pen starts a stroke without any GL work, so an unattached surface can hold the pen down.
        it.preset = BrushPreset(id = "fill", name = "Fill", engine = "fill", size = Param(12f), smoothing = 0f)
        it.colorArgb = Color.RED
        it.longPressEyedropper = false
        it.maxLayers = 8
    }

    private fun set(view: JbCanvasView, name: String, value: Any?) {
        JbCanvasView::class.java.getDeclaredField(name).also { it.isAccessible = true }.set(view, value)
    }
    private fun penDown(view: JbCanvasView) {
        JbCanvasView::class.java.declaredMethods.single { it.name == "startStroke" && it.parameterCount == 2 }
            .also { it.isAccessible = true }.invoke(view, false, null)
        assertTrue("the pen is down", view.strokeInProgress)
    }
    private fun twoLayers(view: JbCanvasView): LayerStack {
        val first = view.activeLayerId
        val stack = LayerStack(listOf(LayerState(first, "First"), LayerState("second", "Second")), "second")
        set(view, "stackUi", stack)
        set(view, "activeLayer", "second")
        return stack
    }
    private fun said(view: JbCanvasView) = ArrayList<String>().also { reasons -> view.onRefused = { reasons.add(it) } }

    @Test fun everyLayerChangeWithThePenDownSaysSoAndChangesNothing() {
        val view = surface(); val stack = twoLayers(view); val reasons = said(view)
        penDown(view)
        val changed = ArrayList<LayerStack>(); view.onLayersChanged = { changed.add(it) }

        assertFalse(view.addLayer())
        assertFalse(view.duplicateLayer("second"))
        assertFalse(view.deleteLayer("second"))
        view.moveLayer("second", 0)
        view.renameLayer("second", "Renamed")
        view.setLayerBlend("second", BlendMode.MULTIPLY)
        view.previewLayerOpacity("second", 0.5f) // not shown while the release could not keep it
        view.commitLayerOpacity(stack, "second", 0.5f)
        view.setClip("second", true)
        view.addMask("second")
        view.deleteMask("second")

        assertEquals(List(10) { sentence }, reasons)
        assertEquals("the stack is exactly as it was", stack, view.layers)
        assertTrue("nothing told the screen the stack moved", changed.isEmpty())
        assertTrue("the stroke was left alone, not ended to make room", view.strokeInProgress)
    }

    @Test fun addingAMaskMidStrokeNoLongerLeavesTheBrushPointingAtAMaskThatIsNotThere() {
        val view = surface(); twoLayers(view); said(view)
        penDown(view)
        view.addMask("second")
        assertFalse(view.editingMask)
        assertFalse(view.layers["second"]!!.hasMask)
    }

    @Test fun withThePenUpTheSameChangesWorkAndSayNothing() {
        val view = surface(); twoLayers(view); val reasons = said(view)
        assertFalse(view.strokeInProgress)
        assertTrue(view.addLayer())
        assertTrue(view.layers.size == 3)
        view.renameLayer("second", "Renamed")
        view.setLayerBlend("second", BlendMode.MULTIPLY)
        assertEquals("Renamed", view.layers["second"]!!.name)
        assertEquals(BlendMode.MULTIPLY, view.layers["second"]!!.blend)
        assertTrue(reasons.isEmpty())
    }

    @Test fun thePenLiftedAfterARefusalLetsTheSameChangeThrough() {
        val view = surface(); twoLayers(view); val reasons = said(view)
        penDown(view)
        assertFalse(view.addLayer())
        view.cancelInterruptedStroke()
        assertTrue(view.addLayer())
        assertEquals(listOf(sentence), reasons)
    }

    // The hop to the UI thread is a View.post, which an unattached test surface never runs; what can be proved here is that
    // the canvas listens for the engine's first undo trim, and that hearing it neither throws nor reads as a refusal.
    @Test fun theCanvasListensForTheFirstUndoTrimAndTreatsItAsANoticeNotARefusal() {
        val view = surface(); val refusals = said(view)
        view.onNotice = { }
        val engine = JbCanvasView::class.java.getDeclaredField("engine").also { it.isAccessible = true }.get(view)
        @Suppress("UNCHECKED_CAST")
        val log = engine.javaClass.getMethod("getUndo").invoke(engine) as UndoLog<Int>
        val told = log.onFirstTrim
        assertNotNull("the canvas listens for the first trim", told)
        told!!.invoke(7)
        assertTrue(refusals.isEmpty())
    }
}

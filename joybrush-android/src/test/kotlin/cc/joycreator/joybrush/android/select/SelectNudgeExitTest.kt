package cc.joycreator.joybrush.android.select

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import cc.joycreator.joybrush.android.chrome.ChromeKit
import cc.joycreator.joybrush.android.chrome.JbIcon
import cc.joycreator.joybrush.android.chrome.Popovers
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.core.select.SelectionState
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises the real Controller row-to-nudge-to-bar path; this does not claim GL execution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectNudgeExitTest {
    private val surfaces = ArrayList<JbCanvasView>()

    @After fun pauseSurfaces() { surfaces.forEach { it.onPause() } }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun nudgeBackRestoresSelectionRowWithoutSubmittingDrawingEdits() {
        val context = RuntimeEnvironment.getApplication()
        val root = FrameLayout(context)
        val canvas = JbCanvasView(context).also {
            surfaces += it
            root.addView(it, FrameLayout.LayoutParams(400, 800))
        }
        val kit = ChromeKit(context)
        val popovers = Popovers(kit, root)
        val rig = Rig(mapOf(planeP to listOf(lineRecord("a", 1), lineRecord("b", 2, y = 30f))))
        val controller = SelectController(kit, root, canvas, rig.host, popovers, object : SelectController.App {
            override fun paperArgb() = Color.WHITE
            override fun openColour(anchor: View, current: Int, onPicked: (Int) -> Unit) {}
            override fun openLineBrushes(anchor: View, onPicked: (String) -> Unit) {}
            override fun brushIcon(brushId: String) = JbIcon.BRUSH
            override fun newLineId() = "new"
            override fun copySelection(selection: SelectionState.Selection) {}
            override fun restyleRing(on: Boolean, pulse: Boolean) {}
            override fun paintMakesLines() = false
            override fun setPaintMakesLines(on: Boolean) {}
            override fun hasRestingThumbForCount() = false
        })
        rig.canvas.supportsLineLift = true
        controller.attach()
        val addresses = listOf(planeP.address("a"), planeP.address("b"))
        assertTrue(controller.tool.selectAddresses(addresses))
        controller.selectToolInHand = true
        layout(root)

        val selectionBefore = controller.tool.selection
        assertNotNull(selectionBefore)
        assertEquals(addresses.toSet(), (selectionBefore as SelectionState.Lines).ids)
        val row = descendants(root).filterIsInstance<SelectRowView>().single()
        val bar = descendants(root).filterIsInstance<SelectBarView>().single()
        assertEquals(View.VISIBLE, row.visibility)
        assertEquals(View.VISIBLE, bar.visibility)
        assertTrue(descendants(bar).none { it.contentDescription == "Back to moving and resizing the selection" })
        val nudge = descendants(row).single { it.contentDescription == "Push or pull part of the selected lines" }

        assertTrue(nudge.performClick())
        assertTrue(controller.tool.nudging)
        assertEquals(View.GONE, row.visibility)
        assertEquals(View.VISIBLE, bar.visibility)
        assertSame("entering nudge preserves the selected state", selectionBefore, controller.tool.selection)

        val back = descendants(bar).single { it.contentDescription == "Back to moving and resizing the selection" }
        assertTrue(back.performClick())
        assertFalse(controller.tool.nudging)
        assertEquals(View.VISIBLE, row.visibility)
        assertEquals(View.VISIBLE, bar.visibility)
        assertSame("leaving nudge preserves the selected state", selectionBefore, controller.tool.selection)
        assertTrue(descendants(bar).none { it.contentDescription == "Back to moving and resizing the selection" })
        assertEquals(addresses.toSet(), (controller.tool.selection as SelectionState.Lines).ids)
        assertTrue("nudge mode changes do not submit line edits", rig.canvas.submitted.isEmpty())
    }

    private fun layout(root: View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 400, 800)
    }
}

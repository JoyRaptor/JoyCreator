package cc.joycreator.joybrush.android.select

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import cc.joycreator.joybrush.android.chrome.ChromeKit
import cc.joycreator.joybrush.android.chrome.JbIcon
import cc.joycreator.joybrush.android.chrome.Popovers
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.androidkit.select.SelectionHost
import cc.joycreator.joybrush.core.select.SelectionState
import cc.joycreator.joybrush.core.shape.Pt
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises the real pixel-selection row and More callback without claiming pixel-engine support. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectPixelMenuTest {
    private val surfaces = ArrayList<JbCanvasView>()
    private val controllers = ArrayList<SelectController>()

    @After fun cleanUp() {
        controllers.forEach { it.detach() }
        surfaces.forEach { it.onPause() }
        LineSelectionClipboard.clearForTests()
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private data class Fixture(
        val root: FrameLayout,
        val row: SelectRowView,
        val popovers: Popovers,
        val controller: SelectController,
        val allowAdjustColour: MutableBox<Boolean>,
    )

    private class MutableBox<T>(var value: T)

    private fun fixture(canAdjustColour: Boolean, hasClipboard: Boolean = false): Fixture {
        LineSelectionClipboard.clearForTests()
        if (hasClipboard) assertTrue(LineSelectionClipboard.accept(canonicalCopyPayload(listOf(lineRecord("copied", 1)))))
        val context = RuntimeEnvironment.getApplication()
        val root = FrameLayout(context)
        val canvas = JbCanvasView(context).also {
            surfaces += it
            root.addView(it, FrameLayout.LayoutParams(400, 800))
        }
        val kit = ChromeKit(context)
        val popovers = Popovers(kit, root)
        val rig = Rig()
        val capability = MutableBox(canAdjustColour)
        val host = object : SelectionHost by rig.host {
            override val canAdjustColour: Boolean get() = capability.value
        }
        val controller = SelectController(kit, root, canvas, host, popovers, object : SelectController.App {
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
        controller.attach()
        controller.tool.state.loop(
            listOf(Pt(0.0, 0.0), Pt(40.0, 0.0), Pt(40.0, 40.0), Pt(0.0, 40.0)),
            emptyList(), emptyList(), SelectionState.Reading(), adding = false,
        )
        controller.tool.reread(SelectionState.Reading(take = SelectionState.Take.PIXELS))
        layout(root)
        val row = descendants(root).filterIsInstance<SelectRowView>().single()
        controllers += controller
        return Fixture(root, row, popovers, controller, capability)
    }

    @Test fun pixelRowAndMoreAreAbsentWithoutARealMenuCapability() {
        val f = fixture(canAdjustColour = false)
        assertTrue(f.controller.tool.selection is SelectionState.Pixels)
        assertEquals(View.GONE, f.row.visibility)
        assertFalse(descendants(f.row).any { it.contentDescription == "More actions for the selected paint" })
        assertFalse(f.popovers.isOpen)
    }

    @Test fun pixelMoreOpensTheRealAdjustColourMenuWhenCapabilityIsAvailable() {
        val f = fixture(canAdjustColour = true)
        assertEquals(View.VISIBLE, f.row.visibility)
        val more = descendants(f.row).single { it.contentDescription == "More actions for the selected paint" }

        assertTrue(more.performClick())
        assertTrue(f.popovers.isOpen)
        layout(f.root)
        assertTrue(descendants(f.root).filterIsInstance<TextView>().any { it.text.toString() == "Adjust colour" })
    }

    @Test fun pixelMoreRechecksCapabilityAtClickAndDoesNotOpenAnEmptyPopover() {
        val f = fixture(canAdjustColour = true)
        val more = descendants(f.row).single { it.contentDescription == "More actions for the selected paint" }
        f.allowAdjustColour.value = false

        assertTrue(more.performClick())
        assertFalse(f.popovers.isOpen)
        assertFalse(descendants(f.root).filterIsInstance<TextView>().any { it.text.toString() == "Adjust colour" })
    }

    @Test fun copiedLinesKeepPasteAvailableFromAPixelSelection() {
        val f = fixture(canAdjustColour = false, hasClipboard = true)
        assertEquals(View.VISIBLE, f.row.visibility)
        val more = descendants(f.row).single { it.contentDescription == "More actions for the selected paint" }
        assertTrue(more.performClick())
        assertTrue(f.popovers.isOpen)
        assertTrue(descendants(f.root).filterIsInstance<TextView>().any { it.text.toString() == "Paste" })
        assertFalse(descendants(f.root).filterIsInstance<TextView>().any { it.text.toString() == "Adjust colour" })
    }

    @Test fun pixelMoreRechecksClearedClipboardAtClick() {
        val f = fixture(canAdjustColour = false, hasClipboard = true)
        val more = descendants(f.row).single { it.contentDescription == "More actions for the selected paint" }
        LineSelectionClipboard.clearForTests()
        assertTrue(more.performClick())
        assertFalse(f.popovers.isOpen)
    }

    private fun layout(root: View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 400, 800)
    }
}

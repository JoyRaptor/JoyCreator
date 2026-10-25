package cc.joycreator.joybrush.android.select

import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import cc.joycreator.joybrush.android.chrome.ChromeKit
import cc.joycreator.joybrush.android.chrome.JbIcon
import cc.joycreator.joybrush.android.chrome.Popovers
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.androidkit.lines.LineSnapshot
import cc.joycreator.joybrush.core.select.SelectionState
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Failure-path coverage for paste preparation; no EGL execution or allocation stress is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectPasteFailureTest {
    private val surfaces = ArrayList<JbCanvasView>()

    @After fun pauseSurfaces() {
        surfaces.forEach { it.onPause() }
        LineSelectionClipboard.clearForTests()
    }

    @Test fun pastePreparationOutOfMemoryPreservesClipboardAndPublishesNoEdit() {
        val copied = canonicalCopyPayload(listOf(lineRecord("copied", 1)))
        assertTrue(LineSelectionClipboard.accept(copied))
        val clipboardBefore = LineSelectionClipboard.snapshot()

        val context = RuntimeEnvironment.getApplication()
        val canvas = JbCanvasView(context).also { surfaces += it }
        val root = FrameLayout(context)
        val hostRig = Rig()
        val popovers = Popovers(ChromeKit(context), root)
        val app = object : SelectController.App {
            override fun paperArgb() = Color.WHITE
            override fun openColour(anchor: View, current: Int, onPicked: (Int) -> Unit) {}
            override fun openLineBrushes(anchor: View, onPicked: (String) -> Unit) {}
            override fun brushIcon(brushId: String) = JbIcon.BRUSH
            override fun newLineId(): String = throw OutOfMemoryError("injected during paste preparation")
            override fun copySelection(selection: SelectionState.Selection) {}
            override fun restyleRing(on: Boolean, pulse: Boolean) {}
            override fun paintMakesLines() = false
            override fun setPaintMakesLines(on: Boolean) {}
            override fun hasRestingThumbForCount() = false
        }
        val controller = SelectController(ChromeKit(context), root, canvas, hostRig.host, popovers, app)

        SelectController::class.java.getDeclaredMethod("pasteLines").also { it.isAccessible = true }
            .invoke(controller)

        assertSame("failed preparation must preserve the accepted clipboard payload", clipboardBefore,
            LineSelectionClipboard.snapshot())
        assertTrue("no edit may be submitted after preparation fails", hostRig.canvas.submitted.isEmpty())
        assertTrue("the user should hear the memory refusal",
            hostRig.ui.notes.contains(LineSelectionClipboard.MEMORY_REFUSAL))
    }

    @Test fun failedStagingRollsBackOnlyBrushesAddedByThatPreparation() {
        val rig = Rig()
        val snapshotBefore: LineSnapshot = rig.canvas.snapshot
        val ids = ArrayDeque(listOf("lb-preexisting", "lb-transient"))
        val host = CanvasSelectionHost(rig.canvas, rig.ui) { ids.removeFirst() }
        val preexisting = testBrush("library-preexisting", name = "Preexisting")
        val transient = testBrush("library-transient", name = "Transient", engine = "tuft")

        assertEquals("lb-preexisting", host.importBrush(preexisting))
        assertNotNull(host.brushPreset("lb-preexisting"))
        try {
            host.prepareWithBrushStaging {
                assertEquals("lb-transient", host.importBrush(transient))
                assertNotNull(host.brushPreset("lb-transient"))
                throw OutOfMemoryError("injected after staging a brush")
            }
            fail("the injected allocation failure must escape the staging helper")
        } catch (_: OutOfMemoryError) {
            // Expected: the finally block must still roll back the transient definition.
        }

        assertNotNull("a brush staged before this preparation remains available", host.brushPreset("lb-preexisting"))
        assertNull("a brush staged by the failed preparation must be removed", host.brushPreset("lb-transient"))
        assertSame("staging must not mutate the engine snapshot", snapshotBefore, rig.canvas.snapshot)
        assertTrue("staging must not publish an edit", rig.canvas.submitted.isEmpty())
    }
}

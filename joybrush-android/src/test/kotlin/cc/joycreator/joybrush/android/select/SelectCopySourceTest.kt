package cc.joycreator.joybrush.android.select

import android.graphics.Color
import cc.joycreator.joybrush.android.JoyBrushActivity
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.androidkit.gl.GlPaintEngine
import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.core.select.SelectionState
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercise the Activity's actual copy callback, rather than sorting records in a test-side imitation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectCopySourceTest {
    private val surfaces = ArrayList<JbCanvasView>()
    @After fun cleanUp() { surfaces.forEach { it.onPause() }; LineSelectionClipboard.clearForTests() }

    private fun copy(installCanonicalRecords: Boolean): List<LineSelectionClipboard.Entry> {
        LineSelectionClipboard.clearForTests()
        val lower = LinePlane("lower", null)
        val upper = LinePlane("upper", null)
        // Both are opaque, overlap exactly, and have deliberately inverted local sequence numbers.
        val red = lineRecord("red-under", 100).copy(colorArgb = Color.RED)
        val blue = lineRecord("blue-over", 1).copy(colorArgb = Color.BLUE)
        val rig = Rig(mapOf(lower to listOf(red), upper to listOf(blue)))
        rig.canvas.active = listOf(upper)
        rig.canvas.visible = listOf(lower, upper)
        val surface = JbCanvasView(RuntimeEnvironment.getApplication()).also { surfaces += it }
        if (installCanonicalRecords) {
            val engine = JbCanvasView::class.java.getDeclaredField("engine").also { it.isAccessible = true }
                .get(surface) as GlPaintEngine
            engine.addLayer("lower"); engine.addLayer("upper")
            val native = GlPaintEngine::class.java.getDeclaredField("nativeLines").also { it.isAccessible = true }
                .get(engine)
            native.javaClass.getDeclaredMethod("addBrushes", Map::class.java).also { it.isAccessible = true }
                .invoke(native, mapOf("lb-1" to frozenInk))
            val put = native.javaClass.getDeclaredMethod("put", LinePlane::class.java, String::class.java,
                StrokeRecord::class.java, Set::class.java).also { it.isAccessible = true }
            put.invoke(native, lower, red.id, red, emptySet<Long>())
            put.invoke(native, upper, blue.id, blue, emptySet<Long>())
        }
        val activity = Robolectric.buildActivity(JoyBrushActivity::class.java).get()
        JoyBrushActivity::class.java.getDeclaredField("canvas").also { it.isAccessible = true }.set(activity, surface)
        val app = JoyBrushActivity::class.java.getDeclaredMethod("selectApp", CanvasSelectionHost::class.java)
            .also { it.isAccessible = true }.invoke(activity, rig.host) as SelectController.App
        app.copySelection(SelectionState.Lines(setOf(lower.address(red.id), upper.address(blue.id)), null, null, null))
        return LineSelectionClipboard.snapshot()
    }

    @Test fun copyAndPastePreserveLayerOverlapDespiteLocalSequenceNumbers() {
        val copied = copy(installCanonicalRecords = true)
        assertEquals(listOf("red-under", "blue-over"), copied.map { it.record.id })
        val pasted = LineSelectionClipboard.paste(copied, LinePlane("destination", null),
            listOf("fresh-under", "fresh-over"), listOf("lb-1", "lb-1"), 0f, 0f)
        assertEquals("the opaque upper blue line must remain on top", Color.BLUE, pasted.last().record.colorArgb)
        assertEquals(Color.RED, pasted.first().record.colorArgb)
        assertEquals(pasted.first().record.samples, pasted.last().record.samples)
    }

    @Test fun unknownCanonicalSourceCannotBeCopiedFromOnlyAUiSnapshot() {
        assertTrue("a UI-only snapshot cannot prove an unbounded source", copy(installCanonicalRecords = false).isEmpty())
    }
}

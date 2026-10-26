package cc.joycreator.joybrush.android.select

import android.graphics.Color
import android.os.Looper
import cc.joycreator.joybrush.android.JoyBrushActivity
import cc.joycreator.joybrush.android.bench.BenchRunner
import cc.joycreator.joybrush.android.bench.SelectBench
import cc.joycreator.joybrush.android.bench.SelectBrushModeBench
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.androidkit.gl.GlPaintEngine
import cc.joycreator.joybrush.androidkit.lines.LineCopyResult
import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.androidkit.lines.LineEdit
import cc.joycreator.joybrush.androidkit.select.SelectTool
import cc.joycreator.joybrush.core.select.SelectionState
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast

/** Actual Activity -> View -> GL metadata queue -> main Handler. No EGL/rendering proof is claimed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SelectCopySourceTest {
    private val surfaces = ArrayList<JbCanvasView>()
    @After fun cleanUp() { surfaces.forEach { it.onPause() }; LineSelectionClipboard.clearForTests() }

    private inner class Bench(canonical: Boolean = true) {
        val lower = LinePlane("lower", null)
        val upper = LinePlane("upper", null)
        val red = lineRecord("red-under", 100).copy(colorArgb = Color.RED)
        val blue = lineRecord("blue-over", 1).copy(colorArgb = Color.BLUE)
        val planes = mapOf(lower to listOf(red), upper to listOf(blue))
        val rig = Rig(planes).also { it.canvas.active = listOf(upper); it.canvas.visible = listOf(lower, upper) }
        val surface = JbCanvasView(RuntimeEnvironment.getApplication()).also { surfaces += it }
        val engine = JbCanvasView::class.java.getDeclaredField("engine").also { it.isAccessible = true }
            .get(surface) as GlPaintEngine
        val activity = Robolectric.buildActivity(JoyBrushActivity::class.java).get()
        val app: SelectController.App

        init {
            if (canonical) {
                seedCanonicalLines(engine, planes)
                val snapshot = engine.lineSnapshot()
                rig.canvas.snapshot = snapshot
                JbCanvasView::class.java.getDeclaredField("lineSnapshot").also { it.isAccessible = true }.set(surface, snapshot)
            }
            JoyBrushActivity::class.java.getDeclaredField("canvas").also { it.isAccessible = true }.set(activity, surface)
            app = JoyBrushActivity::class.java.getDeclaredMethod("selectApp", CanvasSelectionHost::class.java)
                .also { it.isAccessible = true }.invoke(activity, rig.host) as SelectController.App
        }

        fun copy() = app.copySelection(SelectionState.Lines(setOf(lower.address(red.id), upper.address(blue.id)), null, null, null))
        fun expected() = planes.flatMap { (p, rs) -> rs.map { AddressedStroke(p.address(it.id), it) } }
        fun field(name: String) = JbCanvasView::class.java.getDeclaredField(name).also { it.isAccessible = true }

        fun installSelectBench(): Pair<BenchRunner, SelectBench> {
            // Keep the Activity alive: the regression must prove the session guard independently of finish().
            val select = SelectBench(surface, "0123456789abcdef0123456789abcdef") {}
            val runner = BenchRunner(surface, BenchRunner.Spec(listOf("select"), emptyList(), 1, true, false)) {}
            BenchRunner::class.java.getDeclaredField("selectBench").also { it.isAccessible = true }.set(runner, select)
            JoyBrushActivity::class.java.getDeclaredField("activeBench").also { it.isAccessible = true }.set(activity, runner)
            return runner to select
        }

        fun runMetadataQueue() {
            val done = CountDownLatch(1)
            var failure: Throwable? = null
            // Queue behind the View's real onGl event, then run its metadata work on the same GL thread.
            surface.queueEvent {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val work = field("frameWork").get(surface) as ArrayDeque<() -> Unit>
                    while (work.isNotEmpty()) work.removeFirst().invoke()
                } catch (t: Throwable) { failure = t }
                finally { done.countDown() }
            }
            assertTrue("GL metadata event must resolve", done.await(5, TimeUnit.SECONDS))
            failure?.let { throw it }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun copyAndPastePreserveLayerOverlapDespiteLocalSequenceNumbers() {
        val b = Bench()
        b.copy(); b.runMetadataQueue()
        val copied = LineSelectionClipboard.snapshot()
        assertEquals(listOf("red-under", "blue-over"), copied.map { it.record.id })
        val pasted = LineSelectionClipboard.paste(copied, LinePlane("destination", null),
            listOf("fresh-under", "fresh-over"), listOf("lb-1", "lb-1"), 0f, 0f)
        assertEquals("the opaque upper blue line must remain on top", Color.BLUE, pasted.last().record.colorArgb)
        assertEquals(Color.RED, pasted.first().record.colorArgb)
        assertEquals(pasted.first().record.samples, pasted.last().record.samples)
        assertFalse(b.engine.undo.canUndo)
    }

    @Test fun unknownCanonicalSourceCannotBeCopiedFromOnlyAUiSnapshot() {
        LineSelectionClipboard.clearForTests()
        val b = Bench(canonical = false)
        b.copy(); b.runMetadataQueue()
        assertFalse(LineSelectionClipboard.hasLines)
        assertFalse(b.engine.undo.canUndo)
    }

    @Test fun acceptedCopySurvivesDrawingEpochSwitchAndPastesIntoAnEmptyDestination() {
        val source = Bench()
        source.copy(); source.runMetadataQueue()
        val copied = LineSelectionClipboard.snapshot()
        assertEquals(2, copied.size)
        source.field("lineEditEpoch").setInt(source.surface, source.surface.lineEditEpoch + 1)

        val destination = Rig(mapOf(planeP to emptyList()), brushes = emptyMap())
        val brushIds = copied.map { destination.host.importBrush(it.brush())!! }
        val batch = LineSelectionClipboard.paste(copied, planeP,
            listOf("destination-red", "destination-blue"), brushIds, 0f, 0f)
        val tool = SelectTool(destination.host, 1f) { true }
        assertTrue(tool.addAndSelect(batch))
        val edit = destination.canvas.submitted.single().first as LineEdit.Add
        assertEquals(listOf(Color.RED, Color.BLUE), edit.records.map { it.record.colorArgb })
        assertEquals(setOf("destination-red", "destination-blue"),
            (tool.selection as SelectionState.Lines).ids.map { it.recordId }.toSet())
        assertTrue(edit.brushes.isNotEmpty())
        assertSame(copied, LineSelectionClipboard.snapshot())
        assertFalse(source.engine.undo.canUndo)
    }

    @Test fun anUnprovedCopyPreservesThePreviousClipboard() {
        assertTrue(LineSelectionClipboard.accept(canonicalCopyPayload(listOf(lineRecord("keep", 1)))))
        val before = LineSelectionClipboard.snapshot()
        val b = Bench(canonical = false)
        b.copy(); b.runMetadataQueue()
        assertSame(before, LineSelectionClipboard.snapshot())
    }

    @Test fun anOldCopyCallbackDoesNotReplaceClipboardOrToastOnTheNewDrawing() {
        assertTrue(LineSelectionClipboard.accept(canonicalCopyPayload(listOf(lineRecord("keep", 1)))))
        val before = LineSelectionClipboard.snapshot()
        val b = Bench()
        val notices = ShadowToast.shownToastCount()
        b.copy()
        b.field("lineEditEpoch").setInt(b.surface, b.surface.lineEditEpoch + 1)
        b.runMetadataQueue()
        assertSame(before, LineSelectionClipboard.snapshot())
        assertEquals(notices, ShadowToast.shownToastCount())
        assertFalse(b.engine.undo.canUndo)
    }

    @Test fun queuedActivityCopyCannotOverwriteRestoredClipboardAfterBenchmarkAbort() =
        queuedBenchmarkCopyIsDropped(complete = false)

    @Test fun queuedActivityCopyCannotOverwriteRestoredClipboardAfterBenchmarkCompletion() =
        queuedBenchmarkCopyIsDropped(complete = true)

    private fun queuedBenchmarkCopyIsDropped(complete: Boolean) {
        assertTrue(LineSelectionClipboard.accept(canonicalCopyPayload(listOf(lineRecord("owner-clipboard", 1)))))
        val before = LineSelectionClipboard.snapshot()
        val b = Bench()
        val (runner, select) = b.installSelectBench()
        val epoch = b.surface.lineEditEpoch
        val notices = ShadowToast.shownToastCount()
        b.copy()
        if (complete) {
            SelectBench::class.java.getDeclaredMethod("done").also { it.isAccessible = true }.invoke(select)
        } else runner.abortSelect()
        assertFalse("session invalidation must not rely on finishing the Activity", b.activity.isFinishing)
        assertFalse(b.activity.isDestroyed)
        assertEquals(epoch, b.surface.lineEditEpoch)
        assertSame(before, LineSelectionClipboard.snapshot())
        b.runMetadataQueue()
        assertSame("late Ready must not replace the restored owner clipboard", before, LineSelectionClipboard.snapshot())
        assertEquals("late Copy must not add a success/refusal toast", notices, ShadowToast.shownToastCount())
        assertFalse(b.engine.undo.canUndo)
    }

    @Test fun queuedActivityCopyCannotOverwriteClipboardAfterA10AbortAndPreferenceRestore() = queuedA10CopyIsDropped(false)

    @Test fun queuedActivityCopyCannotOverwriteClipboardAfterA10CompletionAndPreferenceRestore() = queuedA10CopyIsDropped(true)

    private fun queuedA10CopyIsDropped(complete: Boolean) {
        assertTrue(LineSelectionClipboard.accept(canonicalCopyPayload(listOf(lineRecord("owner-a10-copy", 1)))))
        val before = LineSelectionClipboard.snapshot()
        val b = Bench()
        val prefs = b.surface.context.getSharedPreferences("joybrush_chrome", android.content.Context.MODE_PRIVATE)
        val key = "joybrush.brushMakesLines.joybrush.ink"
        prefs.edit().putString("tools", "owner-a10-tools").remove(key).commit()
        val a10 = SelectBrushModeBench(b.surface, "0123456789abcdef0123456789abcdef") {}
        val runner = BenchRunner(b.surface, BenchRunner.Spec(listOf("selectA10"), emptyList(), 1, true, false)) {}
        BenchRunner::class.java.getDeclaredField("selectBrushModeBench").also { it.isAccessible = true }.set(runner, a10)
        JoyBrushActivity::class.java.getDeclaredField("activeBench").also { it.isAccessible = true }.set(b.activity, runner)
        prefs.edit().putString("tools", "during-a10-tools").putBoolean(key, true).commit()
        val epoch = b.surface.lineEditEpoch
        val notices = ShadowToast.shownToastCount()
        b.copy()  // Real Activity/View queue; completion is deliberately held until after session invalidation.
        if (complete) SelectBrushModeBench::class.java.getDeclaredMethod("finish").also { it.isAccessible = true }.invoke(a10)
        else runner.abortSelect()
        assertFalse(b.activity.isFinishing)
        assertFalse(b.activity.isDestroyed)
        assertEquals(epoch, b.surface.lineEditEpoch)
        assertEquals("owner-a10-tools", prefs.getString("tools", null))
        assertFalse(prefs.contains(key))
        b.runMetadataQueue()
        assertSame(before, LineSelectionClipboard.snapshot())
        assertEquals(notices, ShadowToast.shownToastCount())
        assertEquals("owner-a10-tools", prefs.getString("tools", null))
        assertFalse(prefs.contains(key))
        assertFalse(b.engine.undo.canUndo)
    }

    @Test fun actualViewTimeoutResolvesOnceAndLateMetadataCannotOverwriteIt() {
        val b = Bench()
        val replies = ArrayList<LineCopyResult>()
        b.surface.requestLineCopy(b.surface.lineSnapshot, b.expected()) { replies += it }
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertEquals(1, replies.size)
        assertTrue(replies.single() is LineCopyResult.Refused)
        b.runMetadataQueue()
        assertEquals(1, replies.size)
        assertFalse(b.engine.undo.canUndo)
    }

    @Test fun actualViewRefusesConcurrentRequestsAndLostContextStillCallsBackOnce() {
        val b = Bench()
        val first = ArrayList<LineCopyResult>(); val second = ArrayList<LineCopyResult>()
        b.surface.requestLineCopy(b.surface.lineSnapshot, b.expected()) { first += it }
        b.surface.requestLineCopy(b.surface.lineSnapshot, b.expected()) { second += it }
        assertEquals(1, second.size)
        assertTrue(second.single() is LineCopyResult.Refused)
        b.field("graphicsEpoch").setInt(b.surface, 1)
        b.field("contentLost").setBoolean(b.surface, true)
        b.runMetadataQueue()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertEquals(1, first.size)
        assertTrue(first.single() is LineCopyResult.Refused)
        assertFalse(b.engine.undo.canUndo)
    }
}

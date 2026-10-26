package cc.joycreator.joybrush.android.bench

import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import cc.joycreator.joybrush.android.select.LineSelectionClipboard
import cc.joycreator.joybrush.android.select.canonicalCopyPayload
import cc.joycreator.joybrush.android.select.lineRecord
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.androidkit.BrushLibrary
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SelectBenchCleanupTest {
    private val canvases = ArrayList<JbCanvasView>()

    @After fun cleanUp() {
        canvases.forEach { it.onPause() }
        LineSelectionClipboard.clearForTests()
    }

    @Test fun abortRestoresCapturedClipboardOnceAndReportsWhetherItChangedState() {
        LineSelectionClipboard.clearForTests()
        val beforePayload = canonicalCopyPayload(listOf(lineRecord("before-bench", 1)))
        assertTrue(LineSelectionClipboard.accept(beforePayload))
        val before = LineSelectionClipboard.snapshot()

        val canvas = JbCanvasView(RuntimeEnvironment.getApplication()).also { canvases += it }
        canvas.editableLines = true
        val bench = SelectBench(canvas, "0123456789abcdef0123456789abcdef") {}
        val duringPayload = canonicalCopyPayload(listOf(lineRecord("during-bench", 2)))
        assertTrue(LineSelectionClipboard.accept(duringPayload))
        assertSame(duringPayload.entries, LineSelectionClipboard.snapshot())
        canvas.editableLines = false

        assertTrue(bench.abort())
        assertSame("abort restores the clipboard captured when the bench was constructed", before,
            LineSelectionClipboard.snapshot())
        assertTrue("abort restores the canvas-local editable-lines override", canvas.editableLines)

        val afterAbortPayload = canonicalCopyPayload(listOf(lineRecord("after-abort", 3)))
        assertTrue(LineSelectionClipboard.accept(afterAbortPayload))
        val changedAfterAbort = LineSelectionClipboard.snapshot()
        assertFalse("a repeated abort is a no-op", bench.abort())
        assertSame("a repeated abort cannot restore over newer clipboard content", changedAfterAbort,
            LineSelectionClipboard.snapshot())
    }

    private class TouchRoot(context: Context) : FrameLayout(context) {
        val actions = ArrayList<Int>()
        var onCancel: (() -> Unit)? = null
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            actions += event.actionMasked
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) onCancel?.invoke()
            return true
        }
    }

    @Test fun runnerA10AbortInvalidatesCopyThenCancelsContactBeforeDurableSubsetRestore() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("joybrush_chrome", Context.MODE_PRIVATE)
        val key = "joybrush.brushMakesLines.joybrush.ink"
        prefs.edit().remove(key).putString("tools", "owner-tools").commit()
        val canvas = JbCanvasView(context).also { canvases += it }
        val originalBrush = BrushLibrary.builtIn().first()
        canvas.preset = originalBrush
        canvas.editableLines = true
        val originalPaper = canvas.documentPaper
        val bench = SelectBrushModeBench(canvas, "0123456789abcdef0123456789abcdef") {}
        var finishes = 0
        val runner = BenchRunner(canvas, BenchRunner.Spec(listOf("selectA10"), emptyList(), 1, true, false)) { finishes++ }
        BenchRunner::class.java.getDeclaredField("selectBrushModeBench").also { it.isAccessible = true }.set(runner, bench)
        val guard = requireNotNull(runner.selectCopyGuard)
        assertTrue(guard())
        val root = TouchRoot(context)
        root.addView(canvas, FrameLayout.LayoutParams(500, 500))
        root.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 500, 500)
        prefs.edit().putString("tools", "bench-tools").putBoolean(key, true).putString("unrelated", "new-value").commit()
        canvas.preset = BrushLibrary.builtIn().last()
        canvas.editableLines = false
        canvas.paper = canvas.paper.copy(baseArgb = 0xFF151515.toInt())
        val clipboard = canonicalCopyPayload(listOf(lineRecord("unrelated-copy", 1)))
        assertTrue(LineSelectionClipboard.accept(clipboard))
        val copied = LineSelectionClipboard.snapshot()
        val gestures = SelectBrushModeBench::class.java.getDeclaredField("gestures")
            .also { it.isAccessible = true }.get(bench) as SelectGestures
        var completed = false
        gestures.stroke(listOf(SelectGestures.P(20f, 40f), SelectGestures.P(460f, 40f)), 100, pen = false) { completed = true }
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertTrue(root.actions.contains(MotionEvent.ACTION_DOWN))
        root.onCancel = {
            assertFalse("late Copy must be blocked before cancellation callbacks", guard())
            assertEquals("bench-tools", prefs.getString("tools", null))
        }
        runner.abortSelect()
        assertEquals(MotionEvent.ACTION_CANCEL, root.actions.last())
        assertFalse(root.actions.contains(MotionEvent.ACTION_UP))
        assertFalse(guard())
        assertEquals("owner-tools", prefs.getString("tools", null))
        assertFalse(prefs.contains(key))
        assertEquals("new-value", prefs.getString("unrelated", null))
        assertSame(originalBrush, canvas.preset)
        assertTrue(canvas.editableLines)
        assertEquals(originalPaper, canvas.documentPaper)
        assertSame("A10 never restores over an unrelated clipboard", copied, LineSelectionClipboard.snapshot())
        assertEquals(1, finishes)
        val actions = root.actions.toList()
        prefs.edit().putString("tools", "after-abort").commit()
        runner.abortSelect()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(actions, root.actions)
        assertFalse(completed)
        assertEquals(1, finishes)
        assertEquals("after-abort", prefs.getString("tools", null))
    }

    @Test fun cancellingAnInFlightSyntheticStrokeSendsCancelAndDropsAllFutureEvents() {
        val context = RuntimeEnvironment.getApplication()
        val root = TouchRoot(context)
        val canvas = View(context)
        root.addView(canvas, FrameLayout.LayoutParams(500, 500))
        root.measure(
            View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 500, 500)

        val gestures = SelectGestures(canvas)
        var completed = false
        gestures.stroke(
            path = listOf(SelectGestures.P(20f, 40f), SelectGestures.P(460f, 40f)),
            ms = 100,
            pen = true,
            settleMs = 350,
        ) { completed = true }
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

        assertTrue("the partially delivered stroke has begun", root.actions.contains(MotionEvent.ACTION_DOWN))
        assertTrue("some movement is delivered before cancellation", root.actions.contains(MotionEvent.ACTION_MOVE))
        assertFalse("the stroke has not lifted before cancellation", root.actions.contains(MotionEvent.ACTION_UP))
        assertFalse("the completion callback has not fired", completed)

        gestures.cancel()
        assertEquals(MotionEvent.ACTION_CANCEL, root.actions.last())
        assertFalse("cancellation never synthesizes a successful lift", root.actions.contains(MotionEvent.ACTION_UP))
        val actionsAtCancel = root.actions.toList()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertEquals("queued movement, UP, and completion work are removed", actionsAtCancel, root.actions)
        assertFalse("cancelled strokes never call their completion callback", completed)
    }
}

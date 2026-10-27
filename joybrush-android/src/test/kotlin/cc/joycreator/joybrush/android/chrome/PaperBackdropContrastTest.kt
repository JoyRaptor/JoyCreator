package cc.joycreator.joybrush.android.chrome

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.core.chrome.IconContrast
import cc.joycreator.joybrush.core.chrome.IconInk
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Real paper setter and native top-button pixels; not a substitute for Activity/GLES phone acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PaperBackdropContrastTest {
    private val surfaces = ArrayList<JbCanvasView>()

    @After fun pauseSurfaces() { surfaces.forEach { it.onPaperBackdropChanged = null; it.onPause() } }

    private fun canvas() = JbCanvasView(org.robolectric.RuntimeEnvironment.getApplication()).also { surfaces += it }

    @Test fun runtimePaperChangesNotifyWithoutPaperVisitOrDuplicateResolvedValue() {
        val canvas = canvas()
        var changes = 0
        var paperVisits = 0
        canvas.onPaperBackdropChanged = { changes++ }
        canvas.onPaperChanged = { paperVisits++ }
        val original = canvas.paper
        val dark = original.copy(baseArgb = Color.rgb(21, 21, 21))
        canvas.paper = dark
        assertEquals(1, changes)
        assertEquals(0, paperVisits)
        canvas.paper = dark
        assertEquals("same resolved backdrop does not duplicate display invalidation", 1, changes)
        canvas.paper = original
        assertEquals("benchmark cleanup also invalidates restored paper", 2, changes)
        assertEquals(0, paperVisits)
    }

    @Test fun documentPaperPathKeepsItsExactMetadataCallbackOnce() {
        val canvas = canvas()
        val target = canvas.documentPaper.copy(color = "#151515", tint = null, includeInExport = false)
        var displays = 0
        val visits = mutableListOf<cc.joycreator.joybrush.core.doc.Paper>()
        canvas.onPaperBackdropChanged = { displays++ }
        canvas.onPaperChanged = { visits += it }
        canvas.applyDocumentPaper(target)
        assertEquals(listOf(target), visits)
        assertEquals(target, canvas.documentPaper)
        assertEquals(1, displays)
    }

    @Test fun workerPaperChangeDeliversOnUiAndClearedObserverDoesNotReceiveLateWork() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val canvas = JbCanvasView(controller.get()).also { surfaces += it }
        controller.get().setContentView(canvas)
        var calls = 0
        var observedThread: Thread? = null
        canvas.onPaperBackdropChanged = { calls++; observedThread = Thread.currentThread() }
        try {
            val dark = canvas.paper.copy(baseArgb = Color.rgb(21, 21, 21))
            Thread { canvas.paper = dark }.apply { start(); join() }
            assertEquals(0, calls)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, calls)
            assertSame(Looper.getMainLooper().thread, observedThread)
            Thread { canvas.paper = dark.copy(baseArgb = Color.WHITE) }.apply { start(); join() }
            canvas.onPaperBackdropChanged = null
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("cleared observer is not captured by a queued callback", 1, calls)
        } finally {
            canvas.onPause()
            controller.pause().stop().destroy()
        }
    }

    @Test fun paperNotificationRefreshesNativeGlyphAndRetainsDisabledStateAndCyanRing() {
        val canvas = canvas()
        val kit = ChromeKit(canvas.context)
        val button = TopButton(kit, JbIcon.HOME, "Home").apply { on = true }
        button.measure(View.MeasureSpec.makeMeasureSpec(40, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(40, View.MeasureSpec.EXACTLY))
        button.layout(0, 0, 40, 40)
        canvas.onPaperBackdropChanged = {
            button.ink = IconContrast.inkFor(IntArray(9) { canvas.paper.baseArgb }, button.ink)
        }
        fun image(background: Int): Bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply { drawColor(background); button.draw(this) }
        }
        canvas.paper = canvas.paper.copy(baseArgb = Color.rgb(21, 21, 21))
        canvas.paper = canvas.paper.copy(baseArgb = Color.WHITE)
        assertEquals(IconInk.DARK, button.ink)
        val white = image(Color.WHITE)
        assertTrue((12..27).any { x -> (12..27).any { y -> Color.red(white.getPixel(x, y)) < 32 } })
        canvas.paper = canvas.paper.copy(baseArgb = Color.rgb(21, 21, 21))
        assertEquals(IconInk.LIGHT, button.ink)
        val dark = image(Color.rgb(21, 21, 21))
        val bright = (12..27).maxOf { x -> (12..27).maxOf { y -> Color.red(dark.getPixel(x, y)) } }
        assertTrue("enabled icon uses readable light ink on dark paper", bright > 200)
        assertTrue((0..39).any { x -> (0..39).any { y ->
            val pixel = dark.getPixel(x, y)
            Color.green(pixel) > Color.red(pixel) + 50 && Color.blue(pixel) > Color.red(pixel) + 50
        } })
        button.isEnabled = false
        val disabled = image(Color.rgb(21, 21, 21))
        val dim = (12..27).maxOf { x -> (12..27).maxOf { y -> Color.red(disabled.getPixel(x, y)) } }
        assertTrue("disabled glyph stays dimmer than enabled", dim < bright)
    }
}

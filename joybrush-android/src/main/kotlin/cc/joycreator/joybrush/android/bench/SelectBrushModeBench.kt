package cc.joycreator.joybrush.android.bench

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import cc.joycreator.joybrush.android.BrushLinePreferences
import cc.joycreator.joybrush.android.chrome.BrushDrawerView
import cc.joycreator.joybrush.android.chrome.BrushSettingsView
import cc.joycreator.joybrush.android.chrome.ToolStripView
import cc.joycreator.joybrush.android.select.BrushModeBadge
import cc.joycreator.joybrush.androidkit.BrushLibrary
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.androidkit.select.SelectTool
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.chrome.BrushShelf
import org.json.JSONObject

/** Separate debug A10 UI proof. It never paints/saves or claims D23 stroke-publication evidence. */
internal class SelectBrushModeBench(private val canvas: JbCanvasView, private val runId: String,
                                   private val finished: () -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
    private val gestures = SelectGestures(canvas)
    private val prefs = canvas.context.getSharedPreferences("joybrush_chrome", Context.MODE_PRIVATE)
    private val policy = BrushLinePreferences(prefs)
    private val library = BrushLibrary.builtIn()
    private val ink = library.singleOrNull { it.id == "joybrush.ink" }
    private val pixels = library.firstOrNull { it.engine == "media" && it.blend != "erase" }
    private val saved = SelectBenchPreferences(prefs, setOf("tools") + listOfNotNull(ink)
        .map { "joybrush.brushMakesLines.${it.id}" })
    private val paperBefore = canvas.documentPaper
    private val presetBefore = canvas.preset
    private val modeBefore = canvas.editableLines
    private var gate: SelectBenchShotGate? = null
    private var active = true
    private var event = 0
    private var passed = 0
    private var failed = 0
    internal val acceptsCopyResult: () -> Boolean = { active }

    fun start() {
        if (!active) return
        if (!runId.matches(Regex("[0-9a-f]{32}"))) { cleanup(); finished(); return }
        if (ink == null || pixels == null || !BrushLinePreferences.canSwitch(ink) || BrushLinePreferences.canSwitch(pixels)) {
            stop("a10Setup", "unique shipped Ink/media fixtures are unavailable"); return
        }
        canvas.benchFreshDrawing {
            if (!active) return@benchFreshDrawing
            if ((canvas.tool as? SelectTool)?.selection != null) {
                stop("a10Setup", "fresh drawing retained a selection"); return@benchFreshDrawing
            }
            pickBrush(ink) {
                report("a10Setup", true, JSONObject().put("inkId", ink.id).put("pixelsId", pixels.id))
                holdSettings(ink) { sheet -> mode(sheet, true, "a10LinesOn", "12_a10_lines_on") {
                    mode(sheet, false, "a10LinesOff", "13_a10_lines_off") {
                        closeSettings(sheet) { pixelSheet(pixels) }
                    }
                } }
            }
        }
    }

    private fun pixelSheet(brush: BrushPreset) {
        pickBrush(brush) { holdSettings(brush) { sheet ->
            val switches = gestures.all(sheet).filterIsInstance<Switch>()
            val reasons = gestures.all(sheet).filterIsInstance<TextView>()
                .count { it.text.toString() == BrushModeBadge.PIXELS_LABEL }
            val ok = switches.isEmpty() && reasons == 1 && !policy.enabled(brush) && !canvas.editableLines
            report("a10PixelsOnly", ok, JSONObject().put("brushId", brush.id).put("switches", switches.size)
                .put("exactD22Reasons", reasons))
            if (!ok) { finish(); return@holdSettings }
            capture("14_a10_pixels_only") { closeSettings(sheet) {
                pickBrush(requireNotNull(ink)) { drawerCanvas(Color.WHITE, false, "a10DrawerWhite", "15_a10_white_canvas_ink_pixels") {
                    pickBrush(requireNotNull(ink)) { holdSettings(requireNotNull(ink)) { inkSheet ->
                        setMode(inkSheet, true) { closeSettings(inkSheet) {
                            drawerCanvas(0xFF151515.toInt(), true, "a10DrawerDark", "16_a10_dark_canvas_ink_lines") { finish() }
                        } }
                    } }
                } }
            } }
        } }
    }

    private fun mode(sheet: BrushSettingsView, on: Boolean, step: String, shot: String, next: () -> Unit) {
        setMode(sheet, on) {
            val control = gestures.all(sheet).filterIsInstance<Switch>().singleOrNull()
            val ok = control?.isChecked == on && policy.enabled(ink) == on && canvas.editableLines == on
            report(step, ok, JSONObject().put("brushId", ink?.id).put("mode", if (on) "lines" else "pixels"))
            if (ok) capture(shot, next) else finish()
        }
    }

    private fun setMode(sheet: BrushSettingsView, on: Boolean, next: () -> Unit) {
        if (!active) return
        if (canvas.preset?.id != ink?.id) { stop("a10Precondition", "settings no longer belong to shipped Ink"); return }
        val control = gestures.all(sheet).filterIsInstance<Switch>().singleOrNull()
        if (control == null || control.contentDescription != "Editable lines") {
            stop("a10Precondition", "eligible sheet has no unique accessible line switch"); return
        }
        if (control.isChecked == on) next()
        else gestures.tapView(control, settleMs = 600) {
            if (!active) return@tapView
            if (control.isChecked == on && policy.enabled(ink) == on && canvas.editableLines == on) next()
            else stop("a10Precondition", "UI switch did not publish the shared per-brush flag")
        }
    }

    private fun drawerCanvas(colour: Int, lines: Boolean, step: String, shot: String, next: () -> Unit) {
        if (!active) return
        // This is an unsaved benchmark document fixture, not PaperSheet acceptance or a stored new-drawing default.
        canvas.paper = canvas.paper.copy(surface = null, look = null, baseArgb = colour, show = 0f,
            bite = 0f, light = false, tintSet = true, screenTransparent = false)
        openDrawer { drawer -> shelf(drawer, BrushShelf.Kind.ALL) {
            val rows = exactRows(drawer, requireNotNull(ink))
            if (rows.size != 1) { stop(step, "shipped Ink row is ambiguous"); return@shelf }
            ensureVisible(rows.single(), 0) {
                val badge = gestures.all(rows.single()).filterIsInstance<BrushModeBadge>().singleOrNull()
                val ok = badge != null && badge.editableLines == lines && policy.enabled(ink) == lines &&
                    badge.contentDescription == if (lines) BrushModeBadge.LINES_LABEL else BrushModeBadge.PIXELS_LABEL
                report(step, ok, JSONObject().put("canvasArgb", colour).put("inkMode", if (lines) "lines" else "pixels")
                    .put("previewPaper", "owned by existing preview source; not asserted")
                    .put("badgeLabel", badge?.contentDescription))
                if (!ok) { finish(); return@ensureVisible }
                capture(shot) {
                    // Real row pick dismisses the drawer; no blanket window click or direct model selection.
                    gestures.tapView(rows.single()) { if (active) next() }
                }
            }
        } }
    }

    private fun openDrawer(next: (BrushDrawerView) -> Unit) {
        if (!active) return
        val strip = gestures.find<ToolStripView>().singleOrNull()
        val brush = strip?.let { gestures.labelled("Brush —", it) }
        if (brush == null) { stop("a10Precondition", "no unique ToolStrip Brush"); return }
        fun opened(retry: Boolean) {
            if (!active) return
            val drawer = gestures.find<BrushDrawerView>().singleOrNull()
            if (drawer != null) next(drawer)
            else if (retry) gestures.tapView(brush) { opened(false) }
            else stop("a10Precondition", "Brush taps did not open its drawer")
        }
        gestures.tapView(brush) { opened(true) }
    }

    private fun shelf(drawer: BrushDrawerView, kind: BrushShelf.Kind, next: () -> Unit) {
        val label = BrushDrawerView.nameOf(kind) + " brushes"
        val buttons = gestures.all(drawer).filter { it.isShown && it.contentDescription == label }
        if (buttons.size != 1) { stop("a10Precondition", "drawer shelf is not unique: $label"); return }
        ensureVisible(buttons.single(), 0) { gestures.tapView(buttons.single()) { if (active) next() } }
    }

    private fun exactRows(drawer: BrushDrawerView, brush: BrushPreset) = gestures.all(drawer).filter {
        it.contentDescription?.toString()?.startsWith("${brush.name}. Hold for its settings. ") == true
    }

    private fun pickBrush(target: BrushPreset, next: () -> Unit) {
        openDrawer { drawer -> shelf(drawer, BrushShelf.kindOf(target)) {
            val rows = exactRows(drawer, target)
            if (rows.size != 1) { stop("a10Precondition", "brush row is ambiguous: ${target.id}"); return@shelf }
            ensureVisible(rows.single(), 0) { gestures.tapView(rows.single()) {
                if (!active) return@tapView
                if (canvas.preset?.id != target.id || (canvas.tool as? SelectTool)?.selectToolInHand != false)
                    stop("a10Precondition", "UI picked a different brush/tool than ${target.id}")
                else next()
            } }
        } }
    }

    private fun holdSettings(target: BrushPreset, next: (BrushSettingsView) -> Unit) {
        if (!active) return
        if (canvas.preset?.id != target.id) { stop("a10Precondition", "wrong brush before settings hold"); return }
        val strip = gestures.find<ToolStripView>().singleOrNull()
        val brush = strip?.let { gestures.labelled("Brush —", it) }
        if (brush == null) { stop("a10Precondition", "no Brush control for settings hold"); return }
        val point = fromView(brush, brush.width / 2f, brush.height / 2f)
        gestures.stroke(listOf(point, point), 750, pen = false, settleMs = 600) {
            if (!active) return@stroke
            val sheet = gestures.find<BrushSettingsView>().singleOrNull()
            if (sheet == null || canvas.preset?.id != target.id) stop("a10Precondition", "hold did not open the expected brush sheet")
            else next(sheet)
        }
    }

    private fun closeSettings(sheet: BrushSettingsView, next: () -> Unit) {
        val done = gestures.all(sheet).singleOrNull { it.contentDescription == "Close the brush settings" }
        if (done == null) { stop("a10Precondition", "settings Done is not unique"); return }
        gestures.tapView(done) { if (active) next() }
    }

    /** Scroll the actual UI, bounded; never tap a clipped row or call a picker/model directly. */
    private fun ensureVisible(view: View, attempt: Int, next: () -> Unit) {
        if (!active) return
        val rect = Rect()
        if (view.getGlobalVisibleRect(rect) && rect.height() >= view.height - 1 && rect.width() >= view.width - 1) { next(); return }
        if (attempt >= 6) { stop("a10Precondition", "target stays clipped after bounded drawer scrolling"); return }
        var parent = view.parent
        while (parent is View && parent !is ScrollView) parent = parent.parent
        val scroll = parent as? ScrollView
        if (scroll == null || scroll.height <= 0) { stop("a10Precondition", "clipped target has no scrollable viewport"); return }
        val loc = IntArray(2).also { view.getLocationInWindow(it) }
        val origin = IntArray(2).also { scroll.getLocationInWindow(it) }
        val below = loc[1] + view.height / 2 > origin[1] + scroll.height / 2
        val a = fromView(scroll, scroll.width / 2f, scroll.height * if (below) .8f else .2f)
        val b = fromView(scroll, scroll.width / 2f, scroll.height * if (below) .2f else .8f)
        gestures.stroke(listOf(a, b), 350, pen = false, settleMs = 400) { ensureVisible(view, attempt + 1, next) }
    }

    private fun fromView(view: View, x: Float, y: Float): SelectGestures.P {
        val a = IntArray(2).also { view.getLocationInWindow(it) }
        val b = IntArray(2).also { canvas.getLocationInWindow(it) }
        return SelectGestures.P(a[0] - b[0] + x, a[1] - b[1] + y)
    }

    private fun emit(row: JSONObject) { if (active) Log.i(BenchRunner.TAG,
        row.put("benchCase", "selectA10").put("runId", runId).put("event", event++).toString()) }

    private fun report(name: String, pass: Boolean, detail: JSONObject) {
        if (pass) passed++ else failed++
        emit(detail.put("selectBench", name).put("pass", pass))
    }

    private fun capture(name: String, next: () -> Unit) {
        if (!active) return
        val currentGate = gate ?: SelectBenchShotGate(canvas.context.cacheDir, runId, ui).also { gate = it }
        if (!currentGate.expect(event)) { stop("a10ScreenshotAck", "stale screenshot expectation"); return }
        canvas.benchState(readPixels = false) { _, _ -> if (active) canvas.rootView.postOnAnimation {
            if (active) canvas.rootView.postOnAnimation {
                if (active) emit(JSONObject().put("selectBenchShot", "$name.png").put("shotProtocol", 1))
            }
        } }
        currentGate.await(onReady = { if (active) next() }, onTimeout = { stop("a10ScreenshotAck", "ACK timeout/consume failure") })
    }

    private fun stop(step: String, why: String) { if (active) { report(step, false, JSONObject().put("why", why)); finish() } }

    private fun finish() {
        if (!active) return
        emit(JSONObject().put("selectBench", "summary").put("passed", passed).put("failed", failed))
        val terminal = JSONObject().put("benchCase", "selectA10").put("selectBenchDone", true).put("runId", runId).put("event", event++)
        cleanup()
        Log.i(BenchRunner.TAG, terminal.toString())
        finished()
    }

    fun abort(): Boolean { if (!active) return false; cleanup(); return true }

    private fun cleanup() {
        if (!active) return
        active = false
        ui.removeCallbacksAndMessages(null)
        gate?.cancel()
        try { gestures.cancel() } finally {
            try { saved.restore() } finally {
                canvas.preset = presetBefore
                canvas.editableLines = modeBefore
                canvas.applyDocumentPaper(paperBefore)
            }
        }
    }
}

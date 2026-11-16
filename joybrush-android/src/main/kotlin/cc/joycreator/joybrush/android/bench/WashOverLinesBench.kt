package cc.joycreator.joybrush.android.bench

import android.os.Handler
import android.os.Looper
import android.util.Log
import cc.joycreator.joybrush.androidkit.BrushLibrary
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.androidkit.bench.StrokeReplay
import cc.joycreator.joybrush.androidkit.gl.media.WetSheet
import cc.joycreator.joybrush.androidkit.io.JbContents
import cc.joycreator.joybrush.androidkit.lines.LineEdit
import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_MEDIA
import cc.joycreator.joybrush.core.brush.MEDIUM_WET
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import org.json.JSONArray
import org.json.JSONObject

/**
 * A watercolour wash on a layer that holds an editable line (ATLAS-SOLID ledger C6, 2026-11-16), through the app's own
 * canvas on a throwaway drawing: an editable line is drawn with [line] (the brush the case was started with), then a wash
 * of the first built-in watercolour is painted across it on the SAME layer.
 *
 * Checked: the line is still a line (it used to be turned into paint first); the wash shows while wet; a save taken while
 * it is wet and opened again holds the line as a line AND the wash; Dry now leaves the line a line; recolouring the line
 * and back redraws its tiles from the layer's items to the same picture (so the wash is among them, not lost under the
 * redraw), also after the reopen; undo of the two recolours gives the dried picture back.
 *
 * Not covered: an animation frame, a mask, moving or deleting the line under the wash, the wash's look next to a wash on
 * a plain layer, a second wash, memory running out during the print, and a real pen.
 */
class WashOverLinesBench(private val canvas: JbCanvasView, private val spec: BenchRunner.Spec, private val line: BrushPreset,
                         private val run: Int, private val next: () -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
    private val report = JSONObject().put("case", "washOverLinesProbe").put("brush", line.id).put("run", run)
    private val problems = JSONArray()
    private val refusals = ArrayList<String>()
    private val notices = ArrayList<String>()
    private var priorRefused: ((String) -> Unit)? = null
    private var priorNotice: ((String) -> Unit)? = null
    private var priorStrokeEnded: (() -> Unit)? = null
    private val sheetBefore = WetSheet.enabled
    private var finished = false
    private lateinit var lineOnly: JbCanvasView.BenchState
    private lateinit var wet: JbCanvasView.BenchState
    private lateinit var dried: JbCanvasView.BenchState
    private var wetSave: JbContents? = null

    fun start() {
        priorRefused = canvas.onRefused; canvas.onRefused = { why -> refusals.add(why) }
        priorNotice = canvas.onNotice; canvas.onNotice = { what -> notices.add(what) }
        priorStrokeEnded = canvas.onStrokeEnded; canvas.onStrokeEnded = null
        canvas.benchWatch = true; canvas.benchErrors.clear()
        WetSheet.enabled = true
        ui.postDelayed({ if (!finished) { problem("timed out"); finish() } }, 150_000L)
        canvas.benchFreshDrawing { later(300) { drawLine() } }
    }

    private fun drawLine() {
        canvas.preset = line
        stroke(0.2f, 0.8f, 0.45f, 0.55f, passes = 1) {
            fence("line") { st ->
                lineOnly = st
                if (onlyLine() == null) { problem("the first stroke was not kept as a line: ${canvas.lastLineRefusal}"); finish(); return@fence }
                wash()
            }
        }
    }

    private fun wash() {
        val water = BrushLibrary.builtIn().firstOrNull { it.engine == ENGINE_MEDIA && it.media?.medium == MEDIUM_WET }
            ?: run { problem("no built-in watercolour brush"); finish(); return }
        report.put("wash", water.id)
        canvas.preset = water
        stroke(0.3f, 0.7f, 0.3f, 0.7f, passes = 3) {
            later(1500) {
                fence("wet") { st ->
                    wet = st
                    val painted = MatrixDiff.count(lineOnly.visible(), st.visible()).px
                    report.put("washPx", painted).put("undoAfterWash", st.undoDepth - lineOnly.undoDepth)
                    if (onlyLine() == null) problem("after the wash the line is no longer a line (lines: ${lines()})")
                    if (notices.any { it.contains("became paint") }) problem("the lines were turned into paint: $notices")
                    if (refusals.isNotEmpty()) problem("the wash was refused: $refusals")
                    if (painted == 0) { problem("the wash painted nothing"); finish(); return@fence }
                    saveWet()
                }
            }
        }
    }

    private fun saveWet() {
        canvas.snapshot({ contents -> if (!finished) { wetSave = contents; dry() } },
            { why -> if (!finished) { problem("the save while wet failed: $why"); finish() } }, keepWet = true)
    }

    private fun dry() {
        canvas.dryWashNow()
        later(600) {
            fence("dried") { st ->
                dried = st
                report.put("driedVsWetPx", MatrixDiff.count(wet.visible(), st.visible()).px)
                if (onlyLine() == null) problem("after Dry now the line is no longer a line (lines: ${lines()})")
                if (MatrixDiff.same(lineOnly.visible(), st.visible())) problem("after Dry now the wash is gone")
                recolourAndBack("dried", st) { undoTwice() }
            }
        }
    }

    /** The line's tiles redrawn from the layer's items, twice: the second redraw must be the picture we started from. */
    private fun recolourAndBack(what: String, from: JbCanvasView.BenchState, then: () -> Unit) {
        val (plane, record) = onlyLine() ?: run { problem("$what: no line to edit"); finish(); return }
        val other = if (record.colorArgb == OTHER) 0xFF356DAB.toInt() else OTHER
        edit(plane, record.copy(colorArgb = other), "$what recolour") { m ->
            edit(plane, record, "$what recolour back") { b ->
                val changed = MatrixDiff.count(from.visible(), m.visible()).px
                val off = MatrixDiff.count(from.visible(), b.visible()).px
                report.put("${what}RecolourPx", changed).put("${what}RedrawOffPx", off)
                if (changed == 0) problem("$what: recolouring the line changed nothing")
                if (off != 0) problem("$what: after recolour and back $off px differ (the wash is not among the layer's items)")
                if (b.undoDepth != from.undoDepth + 2) problem("$what: two edits were ${b.undoDepth - from.undoDepth} undo steps")
                then()
            }
        }
    }

    private fun undoTwice() {
        canvas.undo(); canvas.undo()
        later(400) {
            fence("undo edits") { st ->
                if (!MatrixDiff.same(dried.visible(), st.visible())) problem("undo of the two recolours is not the dried picture")
                reopen()
            }
        }
    }

    private fun reopen() {
        val contents = wetSave ?: run { problem("no save to reopen"); finish(); return }
        canvas.load(contents) {
            later(500) {
                fence("reopened") { st ->
                    val lost = MatrixDiff.count(wet.visible(), st.visible()).px
                    val wash = MatrixDiff.count(lineOnly.visible(), wet.visible()).px
                    report.put("reopenVsWetPx", lost).put("linesAfterReopen", lines())
                    if (onlyLine() == null) problem("reopened: the line did not come back as a line (lines: ${lines()})")
                    if (lost * 10 > wash) problem("reopened: $lost px differ from the wet picture that was saved (the wash is $wash px)")
                    recolourAndBack("reopened", st) { finish() }
                }
            }
        }
    }

    // ---- plumbing ----

    private fun JbCanvasView.BenchState.visible(): Map<Long, ByteArray> = planes["visible"].orEmpty()
    private fun lines(): Int = canvas.lineSnapshot.planes.values.sumOf { it.size }
    private fun onlyLine(): Pair<LinePlane, StrokeRecord>? =
        canvas.lineSnapshot.planes.entries.firstNotNullOfOrNull { (plane, list) -> list.singleOrNull()?.let { plane to it } }

    private fun edit(plane: LinePlane, after: StrokeRecord, what: String, then: (JbCanvasView.BenchState) -> Unit) {
        canvas.submitLineEdit(LineEdit.Replace(listOf(AddressedStroke(plane.address(after.id), after)), emptyMap(), emptyMap())) { result ->
            if (finished) return@submitLineEdit
            if (!result.ok) { problem("$what refused: ${result.message}"); finish() } else fence(what, then)
        }
    }

    /** A scribble over part of the page ([x0]..[y1] as fractions of the view). */
    private fun stroke(x0: Float, x1: Float, y0: Float, y1: Float, passes: Int, then: () -> Unit) {
        val w = canvas.width.toFloat(); val h = canvas.height.toFloat()
        StrokeReplay.play(canvas, StrokeReplay.zigzag(x0 * w, x1 * w, y0 * h, y1 * h, passes, 600.0), null, onDown = {},
            onUp = { later(300) { then() } })
    }

    /** Reads the canvas behind everything queued so far (a wash still wet is read as a save made now would hold it). */
    private fun fence(what: String, then: (JbCanvasView.BenchState) -> Unit) {
        canvas.benchState { st, why ->
            if (finished) return@benchState
            if (st == null) { problem("$what: the canvas could not be read: $why"); finish() }
            else try { then(st) } catch (e: Exception) { problem("$what: $e"); finish() }
        }
    }

    private fun later(ms: Long, block: () -> Unit) { ui.postDelayed({ if (!finished) block() }, ms) }
    private fun problem(what: String) { if (problems.length() < 20) problems.put(what) }

    private fun finish() {
        if (finished) return
        finished = true
        WetSheet.enabled = sheetBefore
        canvas.onRefused = priorRefused; canvas.onNotice = priorNotice; canvas.onStrokeEnded = priorStrokeEnded
        if (canvas.benchErrors.isNotEmpty()) problem("canvas errors: ${canvas.benchErrors.toList()}")
        Log.i(BenchRunner.TAG, report.put("problems", problems).put("pass", problems.length() == 0).toString())
        ui.postDelayed({ next() }, spec.settleMs)
    }

    private companion object { const val OTHER = 0xFFB03A2E.toInt() }
}

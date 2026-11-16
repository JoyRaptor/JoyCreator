package cc.joycreator.joybrush.android.bench

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import cc.joycreator.joybrush.androidkit.BrushLibrary
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.androidkit.bench.StrokeReplay
import cc.joycreator.joybrush.core.doc.BoardDocumentOps
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.layers.LayerStack
import org.json.JSONArray
import org.json.JSONObject

/**
 * A layer copy the phone has no graphics memory for (R2-JB-01, Codex audit 2026-11-15), through the app's own
 * [JbCanvasView.duplicateLayer], on a throwaway drawing whose one layer has an animation board (so cels), a mask with a
 * stroke in it, and strokes drawn with an ink brush (editable lines where the brush keeps them; [lines] in the report
 * says how many there were).
 *
 * Every tile of the copy is made to fail in turn ([JbCanvasView.benchFailLayerTile]). After each failure: the layer list
 * shown is the one from before, the same layer is still the one painted on, the person was told in words, nothing was
 * added to Undo, the line count is the same and every pixel read back is the same. Then a copy that is allowed to work
 * (one more layer, one Undo step, Undo takes it away again), and a stroke after all of it (one Undo step, Undo gives the
 * first picture back).
 *
 * Not covered: the Select tool's own selection, the mask's pixels (the read-back is the layer as the screen shows it),
 * realistic paint on the layer, and a failure in a float (paint-state) tile.
 */
class DuplicateFailureBench(private val canvas: JbCanvasView, private val spec: BenchRunner.Spec, private val run: Int,
                            private val next: () -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
    private val report = JSONObject().put("case", "duplicateFailureProbe").put("run", run)
    private val problems = JSONArray()
    private val refusals = ArrayList<String>()
    private var shown: LayerStack? = null
    private var priorRefused: ((String) -> Unit)? = null
    private var priorLayers: ((LayerStack) -> Unit)? = null
    private var priorStrokeEnded: (() -> Unit)? = null
    private var finished = false
    private val editableBefore = canvas.editableLines
    private lateinit var rect: RectPx
    private lateinit var base: JbCanvasView.BenchState
    private lateinit var baseStack: LayerStack
    private var baseLines = 0
    private var faults = 0

    fun start() {
        priorRefused = canvas.onRefused; canvas.onRefused = { why -> refusals.add(why) }
        priorLayers = canvas.onLayersChanged; canvas.onLayersChanged = { s -> shown = s; priorLayers?.invoke(s) }
        priorStrokeEnded = canvas.onStrokeEnded; canvas.onStrokeEnded = null
        canvas.benchWatch = true
        ui.postDelayed({ if (!finished) { problem("timed out"); finish() } }, 180_000L)
        canvas.benchFreshDrawing { later(300) { board() } }
    }

    // ---- the drawing: an animation board, strokes, a mask with a stroke in it ----

    private fun board() {
        val all = BrushLibrary.builtIn()
        // The ink pen keeps editable lines (as in SelectBench), so the copy has lines to carry.
        canvas.editableLines = true
        canvas.preset = all.firstOrNull { it.id == "joybrush.ink" } ?: all.firstOrNull { it.id == "ink" }
            ?: all.first { it.engine == "stamp" }
        report.put("brush", canvas.preset?.id)
        val s = (minOf(512, (minOf(canvas.pageWidth, canvas.pageHeight) * 0.6).toInt()) / 8) * 8
        rect = RectPx((canvas.pageWidth - s) / 2, canvas.pageHeight / 5, s, s)
        var n = 0
        canvas.editBoards { d -> BoardDocumentOps.createAnimation(d, "Duplicate probe", rect, { "dp-${++n}" }) }
        fence("board") { st ->
            if (st.doc?.boards?.any { it.name == "Duplicate probe" } != true) { problem("the animation board was not made"); finish(); return@fence }
            later(100) { stroke(0.15f, 0.85f, 0.2f, 0.5f) { maskStroke() } }
        }
    }

    private fun maskStroke() {
        canvas.addMask(canvas.activeLayerId)
        later(200) {
            report.put("maskEditing", canvas.editingMask)
            stroke(0.3f, 0.7f, 0.55f, 0.8f) {
                canvas.setEditingMask(false)
                // One more outside the board: the canvas under the frames has paint as well.
                later(100) { stroke(-0.4f, -0.1f, 0.1f, 0.9f) { settle { readBase() } } }
            }
        }
    }

    private fun readBase() = fence("base") { st ->
        base = st
        baseStack = shown ?: run { problem("the layer list was never reported"); finish(); return@fence }
        baseLines = lines()
        val meta = st.doc?.layers?.firstOrNull { it.id == canvas.activeLayerId }
        report.put("layers", baseStack.size).put("lines", baseLines).put("undoDepth", st.undoDepth)
            .put("hasMask", baseStack.active.hasMask).put("cels", meta?.cels?.size ?: 0).put("regions", meta?.regions?.size ?: 0)
            .put("planes", st.planes.size).put("tilesRead", st.planes.values.sumOf { it.size })
        if (!baseStack.active.hasMask) problem("setup: the layer has no mask")
        if (st.planes.values.all { it.isEmpty() }) problem("setup: nothing was painted")
        attempt(0)
    }

    // ---- each tile of the copy fails in turn ----

    private fun attempt(n: Int) {
        if (n >= CAP) { report.put("capped", true); allowed(); return }
        refusals.clear(); canvas.benchErrors.clear()
        canvas.benchFailLayerTile(n)
        val accepted = canvas.duplicateLayer(baseStack.activeId)
        canvas.benchFailLayerTile(-1) { left ->
            val fired = left < 0
            fence("attempt $n") { st ->
                if (!accepted) { problem("attempt $n: the copy was not even tried"); finish(); return@fence }
                if (!fired) {
                    // The copy needed fewer than n tiles: it went through whole. Checked as the allowed copy.
                    report.put("tilesInACopy", n - left)
                    afterAllowed(st)
                    return@fence
                }
                faults++
                val at = "fault $n"
                if (!sameStack(shown, baseStack)) problem("$at: the layer list shown is not the one from before")
                if (canvas.activeLayerId != baseStack.activeId) problem("$at: a different layer is painted on")
                if (refusals.none { it.contains("not enough memory") }) problem("$at: the person was not told (heard: $refusals)")
                if (canvas.benchErrors.none { it.startsWith("duplicate layer") }) problem("$at: the refusal was not the copy's own (${canvas.benchErrors.toList()})")
                if (canvas.benchErrors.any { it.startsWith("queued command") }) problem("$at: the failure reached the queue itself")
                if (st.undoDepth != base.undoDepth) problem("$at: Undo grew by ${st.undoDepth - base.undoDepth}")
                if (st.doc?.layers?.size != base.doc?.layers?.size) problem("$at: the document has ${st.doc?.layers?.size} layers")
                if (lines() != baseLines) problem("$at: ${lines()} lines, were $baseLines")
                if (!samePixels(st, base)) problem("$at: the picture changed")
                if (problems.length() > 12) { finish(); return@fence }
                attempt(n + 1)
            }
        }
    }

    // ---- a copy that is allowed to work, Undo, and a stroke after all of it ----

    private fun allowed() {
        refusals.clear(); canvas.benchErrors.clear()
        if (!canvas.duplicateLayer(baseStack.activeId)) { problem("the allowed copy was not tried"); finish(); return }
        fence("allowed") { st -> afterAllowed(st) }
    }

    private fun afterAllowed(st: JbCanvasView.BenchState) {
        if (shown?.size != baseStack.size + 1) problem("allowed copy: ${shown?.size} layers shown, expected ${baseStack.size + 1}")
        if (st.undoDepth != base.undoDepth + 1) problem("allowed copy: Undo grew by ${st.undoDepth - base.undoDepth}, expected 1")
        if (refusals.isNotEmpty()) problem("allowed copy: refused: $refusals")
        report.put("linesAfterCopy", lines())
        canvas.undo()
        later(300) {
            fence("undo copy") { u ->
                if (!sameStack(shown, baseStack)) problem("undo of the copy: the layer list is not the one from before")
                if (u.undoDepth != base.undoDepth) problem("undo of the copy: Undo depth ${u.undoDepth}, was ${base.undoDepth}")
                if (lines() != baseLines) problem("undo of the copy: ${lines()} lines, were $baseLines")
                if (!samePixels(u, base)) problem("undo of the copy: the picture changed")
                nextStroke()
            }
        }
    }

    private fun nextStroke() {
        refusals.clear()
        stroke(0.2f, 0.8f, 0.3f, 0.7f) {
            settle {
                fence("next stroke") { st ->
                    if (st.undoDepth != base.undoDepth + 1) problem("next stroke: Undo grew by ${st.undoDepth - base.undoDepth}, expected 1")
                    if (samePixels(st, base) && lines() == baseLines) problem("next stroke: nothing was drawn (heard: $refusals)")
                    canvas.undo()
                    later(300) {
                        fence("undo stroke") { u ->
                            if (!samePixels(u, base)) problem("undo of the next stroke: not the first picture")
                            if (lines() != baseLines) problem("undo of the next stroke: ${lines()} lines, were $baseLines")
                            finish()
                        }
                    }
                }
            }
        }
    }

    // ---- plumbing ----

    private fun lines(): Int = canvas.lineSnapshot.planes.values.sumOf { it.size }

    private fun sameStack(a: LayerStack?, b: LayerStack): Boolean =
        a != null && a.layers.map { it.id } == b.layers.map { it.id } && a.activeId == b.activeId &&
            a.layers.map { it.hasMask } == b.layers.map { it.hasMask }

    private fun samePixels(a: JbCanvasView.BenchState, b: JbCanvasView.BenchState): Boolean =
        a.planes.keys == b.planes.keys && a.planes.all { (name, tiles) -> MatrixDiff.same(tiles, b.planes[name].orEmpty()) }

    /** A scribble across a part of the board's square ([x0]..[y1] as fractions of it; negative x is left of the board). */
    private fun stroke(x0: Float, x1: Float, y0: Float, y1: Float, then: () -> Unit) {
        val s = rect.w.toFloat()
        val a = canvas.view.docToScreen(rect.x + x0 * s, rect.y + y0 * s)
        val b = canvas.view.docToScreen(rect.x + x1 * s, rect.y + y1 * s)
        StrokeReplay.play(canvas, StrokeReplay.zigzag(a.first, b.first, a.second, b.second, 3, 500.0), null, onDown = {},
            onUp = { later(250) { then() } })
    }

    private fun settle(then: () -> Unit) {
        val t0 = SystemClock.uptimeMillis()
        fun poll() {
            canvas.benchState(readPixels = false) { st, _ ->
                if (finished) return@benchState
                if ((st?.idle == true && !canvas.strokeInProgress) || SystemClock.uptimeMillis() - t0 > 8_000L) then()
                else ui.postDelayed({ if (!finished) poll() }, 250)
            }
        }
        poll()
    }

    /** Reads the canvas behind everything queued so far; its answer also means "what was queued has run". */
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
        canvas.benchFailLayerTile(-1)
        canvas.onRefused = priorRefused; canvas.onLayersChanged = priorLayers; canvas.onStrokeEnded = priorStrokeEnded
        canvas.editableLines = editableBefore
        if (faults == 0) problem("no copy was made to fail")
        Log.i(BenchRunner.TAG, report.put("faults", faults).put("problems", problems).put("pass", problems.length() == 0).toString())
        ui.postDelayed({ next() }, spec.settleMs)
    }

    private companion object { const val CAP = 48 }
}

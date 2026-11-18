package cc.joycreator.joybrush.android.bench

import android.os.Handler
import android.os.Looper
import android.util.Log
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.androidkit.bench.StrokeReplay
import cc.joycreator.joybrush.androidkit.lines.LineEdit
import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import org.json.JSONArray
import org.json.JSONObject

/**
 * An editable line made wider, undone and redone (L9, the 11-14 phone check: "redo of a width change is not the exact
 * picture you had"). Through the app's own canvas on a throwaway drawing: one line with the brush the case was started
 * with, kept as a line; then the edit Select's width slider sends (the same record with another `widthScale`), twice
 * over (1x to [WIDE] and on to [WIDER]), with Undo and Redo walked across both.
 *
 * Checked: each Undo gives back exactly the picture before that edit, each Redo exactly the picture after it, and the
 * line's record carries the width that picture was drawn with. Also the same edit sent AGAIN after an undo (a new edit,
 * not a redo): how far its picture is from the first time, which says whether drawing the line twice gives one picture.
 *
 * Not covered: the slider itself (the Select screens), a real pen, a line on a frame.
 */
class LineWidthRedoBench(private val canvas: JbCanvasView, private val spec: BenchRunner.Spec, private val line: BrushPreset,
                         private val run: Int, private val next: () -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
    private val report = JSONObject().put("case", "lineWidthRedoProbe").put("brush", line.id).put("run", run)
    private val problems = JSONArray()
    private val linesBefore = canvas.editableLines
    private var finished = false
    private lateinit var drawn: JbCanvasView.BenchState
    private lateinit var wide: JbCanvasView.BenchState
    private lateinit var wider: JbCanvasView.BenchState

    fun start() {
        canvas.benchWatch = true; canvas.benchErrors.clear()
        canvas.editableLines = true
        ui.postDelayed({ if (!finished) { problem("timed out"); finish() } }, 90_000L)
        canvas.benchFreshDrawing {
            later(300) {
                canvas.preset = line
                canvas.editableLines = true
                val w = canvas.width.toFloat(); val h = canvas.height.toFloat()
                StrokeReplay.play(canvas, StrokeReplay.zigzag(0.25f * w, 0.75f * w, 0.4f * h, 0.6f * h, 2, 600.0), null,
                    onDown = { canvas.editableLines = true }, onUp = { awaitLine(0) })
            }
        }
    }

    /** A watercolour line is only a line once its fade at the lift has run (it is committed then): wait for it. */
    private fun awaitLine(tries: Int) {
        later(500) {
            if (onlyLine() == null && tries < 20) { awaitLine(tries + 1); return@later }
            report.put("lineAfterMs", (tries + 1) * 500)
            later(500) { fence("line") { st -> drawn = st; widen() } }
        }
    }

    private fun widen() {
        val (plane, record) = onlyLine() ?: run { problem("the stroke was not kept as a line: ${canvas.lastLineRefusal}; lines ${canvas.lineSnapshot.planes.values.sumOf { it.size }}, brush ${canvas.preset?.id}, switch ${canvas.editableLines}"); finish(); return }
        edit(plane, record.copy(widthScale = WIDE), "wide") { a ->
            wide = a
            report.put("widePx", MatrixDiff.count(drawn.visible(), a.visible()).px)
            edit(plane, record.copy(widthScale = WIDER), "wider") { b ->
                wider = b
                report.put("widerPx", MatrixDiff.count(a.visible(), b.visible()).px)
                step("undoWider", { canvas.undo() }, wide, WIDE) {
                    step("undoWide", { canvas.undo() }, drawn, 1f) {
                        step("redoWide", { canvas.redo() }, wide, WIDE) {
                            step("redoWider", { canvas.redo() }, wider, WIDER) {
                                // The same edit as a NEW edit: undo the last one and send it again.
                                canvas.undo()
                                later(500) {
                                    fence("undo again") { _ ->
                                        edit(plane, record.copy(widthScale = WIDER), "wider again") { again ->
                                            report.put("sameEditAgainOffPx", MatrixDiff.count(wider.visible(), again.visible()).px)
                                            finish()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** [press], then the picture must be [want] exactly and the line's record must say [width]. */
    private fun step(name: String, press: () -> Unit, want: JbCanvasView.BenchState, width: Float, then: () -> Unit) {
        press()
        later(600) {
            fence(name) { st ->
                val off = MatrixDiff.count(want.visible(), st.visible()).px
                val has = onlyLine()?.second?.widthScale
                report.put("${name}OffPx", off).put("${name}Width", has?.toDouble() ?: -1.0)
                if (off != 0) problem("$name: $off px differ from the picture that step had")
                if (has == null || kotlin.math.abs(has - width) > 1e-4f) problem("$name: the line's width is $has, that picture was drawn at $width")
                then()
            }
        }
    }

    private fun JbCanvasView.BenchState.visible(): Map<Long, ByteArray> = planes["visible"].orEmpty()
    private fun onlyLine(): Pair<LinePlane, StrokeRecord>? =
        canvas.lineSnapshot.planes.entries.firstNotNullOfOrNull { (plane, list) -> list.singleOrNull()?.let { plane to it } }

    private fun edit(plane: LinePlane, after: StrokeRecord, what: String, then: (JbCanvasView.BenchState) -> Unit) {
        canvas.submitLineEdit(LineEdit.Replace(listOf(AddressedStroke(plane.address(after.id), after)), emptyMap(), emptyMap())) { result ->
            if (finished) return@submitLineEdit
            if (!result.ok) { problem("$what refused: ${result.message}"); finish() } else later(300) { fence(what, then) }
        }
    }

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
        canvas.editableLines = linesBefore
        if (canvas.benchErrors.isNotEmpty()) problem("canvas errors: ${canvas.benchErrors.toList()}")
        Log.i(BenchRunner.TAG, report.put("problems", problems).put("pass", problems.length() == 0).toString())
        ui.postDelayed({ next() }, spec.settleMs)
    }

    private companion object { const val WIDE = 2.1f; const val WIDER = 3.0f }
}

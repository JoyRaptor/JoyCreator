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

/**
 * The `lineParity` case (Unibrush finish U8): does a stroke of this brush stay an editable line, and does the line
 * come back pixel for pixel when the engine redraws it from its record?
 *
 * One ordinary stroke is drawn through the real pen path. Its tiles are read back (A). The line is then recoloured and
 * recoloured back with two ordinary Select edits; each edit makes the engine throw away the line's pixels and redraw it
 * from the record (prepareRecordedLine), so the tiles read after the second edit (B) are the redrawn line, not a cache.
 * The tiles read between the edits (M) must differ from A, which is the proof the redraw really happened. A equal to B
 * is the parity claim. Needs `jb_lines true`, or the stroke is a plain pixel stroke and is logged as such.
 *
 * Reads go through [JbCanvasView.benchState], which queues behind everything already on the GL thread, so each read is
 * also the wait for the commit or edit before it. Nothing here writes the drawing outside the canvas's own routes.
 */
class LineParityBench(
    private val canvas: JbCanvasView, private val spec: BenchRunner.Spec, private val preset: BrushPreset, private val run: Int,
    private val next: () -> Unit, private val abort: () -> Unit,
) {
    private val ui = Handler(Looper.getMainLooper())
    private var finished = false
    private val notices = ArrayList<String>()
    private val priorNotice = canvas.onNotice
    private val priorColor = canvas.colorArgb
    private lateinit var timeout: Runnable

    fun start() {
        timeout = Runnable {
            if (finished) return@Runnable
            end()
            Log.i(TAG, json().put("status", "INCONCLUSIVE").put("reason", "Timed out waiting for the canvas").toString())
            abort()
        }
        ui.postDelayed(timeout, 60_000L)
        // Every sentence the canvas says during the run, so "kept as pixels" is read from what the person would be told.
        canvas.onNotice = { m -> notices.add(m); priorNotice?.invoke(m) }
        try {
            canvas.clearCanvas()
            canvas.preset = preset
            canvas.colorArgb = spec.colors.getOrNull(spec.cases.indexOf(CASE)) ?: DRAW
            ui.postDelayed({ guarded { drawn() } }, 300)
        } catch (e: Exception) { failed(e) }
    }

    private fun drawn() {
        // The fence after the clear: this is where the undo count starts, and which lines already existed.
        canvas.benchState(readPixels = false) { before, why ->
            guarded {
                if (before == null) return@guarded inconclusive("before read: $why")
                val known = canvas.lineSnapshot.planes.values.flatten().mapTo(HashSet()) { it.id }
                val samples = StrokeReplay.case("slowC", canvas.width, canvas.height) ?: return@guarded inconclusive("no replay stroke")
                StrokeReplay.play(canvas, samples, null, onDown = {}, onUp = {
                    ui.postDelayed({ guarded { committed(before.undoDepth, known) } }, TAIL_MS)
                })
            }
        }
    }

    private fun committed(undoBefore: Int, known: Set<String>) {
        canvas.benchState { a, why ->
            guarded {
                if (a == null) return@guarded inconclusive("stroke read: $why")
                val found = canvas.lineSnapshot.planes.entries.firstNotNullOfOrNull { (plane, list) ->
                    list.firstOrNull { it.id !in known }?.let { plane to it }
                }
                val kept = notices.firstOrNull { it.contains("kept as pixels", true) }
                if (found == null) {
                    // Not a line: say so (and why), and there is nothing to compare.
                    return@guarded finish(json().put("keptAsLine", false).put("notice", kept ?: NULL).put("tiles", a.visible().size)
                        .put("pixelsDiff", NULL).put("maxByteDiff", NULL).put("undoSteps", a.undoDepth - undoBefore))
                }
                val (plane, record) = found
                val other = if (record.colorArgb == OTHER) DRAW else OTHER
                recolour(plane, record, record.copy(colorArgb = other)) { m ->
                    recolour(plane, record, record) { b ->
                        val ta = a.visible()
                        val diff = compare(ta, b.visible())
                        finish(json().put("keptAsLine", true).put("notice", kept ?: NULL).put("tiles", ta.size)
                            .put("pixelsDiff", diff.first).put("maxByteDiff", diff.second).put("undoSteps", b.undoDepth - undoBefore)
                            .put("recolouredPixelsDiff", compare(ta, m.visible()).first))
                    }
                }
            }
        }
    }

    /** Replaces [original] by [after] as one Select edit, then reads the tiles; [then] gets that read. */
    private fun recolour(plane: LinePlane, original: StrokeRecord, after: StrokeRecord, then: (JbCanvasView.BenchState) -> Unit) {
        canvas.submitLineEdit(LineEdit.Replace(listOf(AddressedStroke(plane.address(original.id), after)))) { result ->
            guarded {
                if (!result.ok) return@guarded inconclusive("edit refused: ${result.message}")
                canvas.benchState { s, why -> guarded { if (s == null) inconclusive("edit read: $why") else then(s) } }
            }
        }
    }

    /** Pixels (RGBA groups) that differ, and the biggest single byte difference, over every tile either side holds. */
    private fun compare(a: Map<Long, ByteArray>, b: Map<Long, ByteArray>): Pair<Int, Int> {
        var pixels = 0
        var worst = 0
        for (key in a.keys + b.keys) {
            val x = a[key]
            val y = b[key]
            val n = maxOf(x?.size ?: 0, y?.size ?: 0)
            var i = 0
            while (i < n) {
                var differs = false
                for (c in 0 until 4) {
                    val d = Math.abs(((x?.getOrNull(i + c)?.toInt() ?: 0) and 255) - ((y?.getOrNull(i + c)?.toInt() ?: 0) and 255))
                    if (d > 0) { differs = true; if (d > worst) worst = d }
                }
                if (differs) pixels++
                i += 4
            }
        }
        return pixels to worst
    }

    private fun JbCanvasView.BenchState.visible(): Map<Long, ByteArray> = planes["visible"].orEmpty()

    private fun json() = org.json.JSONObject().put("case", CASE).put("brush", preset.id).put("run", run)
        .put("editableLines", canvas.editableLines)

    private fun finish(report: org.json.JSONObject) {
        if (finished) return
        end()
        Log.i(TAG, report.toString())
        ui.postDelayed({ next() }, spec.settleMs)
    }

    private fun inconclusive(reason: String) {
        if (finished) return
        end()
        Log.i(TAG, json().put("status", "INCONCLUSIVE").put("reason", reason).toString())
        abort()
    }

    private fun failed(e: Throwable) = inconclusive("${e.javaClass.simpleName}: ${e.message}")

    private fun guarded(block: () -> Unit) { try { block() } catch (e: Exception) { failed(e) } }

    private fun end() {
        finished = true
        ui.removeCallbacks(timeout)
        canvas.onNotice = priorNotice
        canvas.colorArgb = priorColor
    }

    private companion object {
        const val TAG = BenchRunner.TAG
        const val CASE = "lineParity"
        const val TAIL_MS = 250L
        const val DRAW = 0xFF356DAB.toInt()
        const val OTHER = 0xFFC8501E.toInt()
        val NULL: Any = org.json.JSONObject.NULL
    }
}

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
 * [UnibrushParityEvidence] holds the verdict: nonblank pictures, M different, A equal to B, one undo step per stroke and
 * edit. Undo and redo are then walked through the same four pictures. Needs `jb_lines true`, or the stroke is a plain
 * pixel stroke and is logged as such.
 *
 * Reads go through [JbCanvasView.benchState], which queues behind everything already on the GL thread, so each read is
 * also the wait for the commit or edit before it. Nothing here writes the drawing outside the canvas's own routes.
 */
class LineParityBench(
    private val canvas: JbCanvasView, private val spec: BenchRunner.Spec, private val preset: BrushPreset, private val run: Int,
    private val next: () -> Unit, private val abort: () -> Unit,
) {
    private val ui = Handler(Looper.getMainLooper())
    private val lifetime = UnibrushProbeLifetime()
    private val notices = ArrayList<String>()
    private var notice: UnibrushProbeNoticeLease? = null
    private val priorColor = canvas.colorArgb
    private lateinit var timeout: Runnable

    fun start() {
        timeout = Runnable { inconclusive("Timed out waiting for the canvas") }
        ui.postDelayed(timeout, 60_000L)
        // Every sentence the canvas says during the run, so "kept as pixels" is read from what the person would be told.
        notice = UnibrushProbeNoticeLease(lifetime, { canvas.onNotice }, { canvas.onNotice = it }) { m -> notices.add(m) }
        canvas.benchFailLiftRedraw = spec.failLiftRedraw
        try {
            canvas.clearCanvas()
            canvas.preset = preset
            canvas.colorArgb = spec.colors.getOrNull(spec.cases.indexOf(CASE)) ?: DRAW
            ui.postDelayed({ guarded { drawn() } }, 300)
        } catch (e: Exception) { failed(e) }
    }

    private fun drawn() {
        // The fence after the clear: this is where the undo count starts, and which lines already existed.
        canvas.benchState { before, why ->
            guarded {
                if (before == null) return@guarded inconclusive("before read: $why")
                val known = canvas.lineSnapshot.planes.values.flatten().mapTo(HashSet()) { it.id }
                val samples = StrokeReplay.case("slowC", canvas.width, canvas.height) ?: return@guarded inconclusive("no replay stroke")
                val baseline = frame(before)
                StrokeReplay.play(canvas, samples, null, onDown = {}, onUp = {
                    ui.postDelayed({ guarded { committed(baseline, known) } }, TAIL_MS)
                })
            }
        }
    }

    private fun committed(baseline: UnibrushParityEvidence.Frame, known: Set<String>) {
        canvas.benchState { a, why ->
            guarded {
                if (a == null) return@guarded inconclusive("stroke read: $why")
                val found = canvas.lineSnapshot.planes.entries.firstNotNullOfOrNull { (plane, list) ->
                    list.firstOrNull { it.id !in known }?.let { plane to it }
                }
                val kept = notices.firstOrNull { it.contains("kept as pixels", true) }
                dump(a)
                if (found == null) {
                    // Not a line: say so (and why), and there is nothing to compare.
                    return@guarded finish(json().put("keptAsLine", false).put("pass", false).put("notice", kept ?: NULL)
                        .put("reason", canvas.lastLineRefusal ?: "refused at pen-down").put("tiles", a.visible().size)
                        .put("crc", crc(a.visible())).put("undoSteps", a.undoDepth - baseline.undoDepth))
                }
                val drawnFrame = frame(a)
                val (plane, record) = found
                val other = if (record.colorArgb == OTHER) DRAW else OTHER
                recolour(plane, record, record.copy(colorArgb = other)) { m ->
                    recolour(plane, record, record) { b ->
                        val verdict = UnibrushParityEvidence.evaluate(baseline, drawnFrame, frame(m), frame(b))
                        val report = json().put("keptAsLine", true).put("notice", kept ?: NULL).put("tiles", a.visible().size)
                            .put("crc", crc(a.visible())).put("parity", verdict.matchingRecolourReplay).put("verdict", verdict.reason)
                            .put("undoSteps", b.undoDepth - baseline.undoDepth)
                        // Back through the same pictures: undo, undo, then redo, redo.
                        walk(listOf(m, a), { canvas.undo() }, "undo", report) {
                            walk(listOf(m, b), { canvas.redo() }, "redo", report) {
                                val owned = notice?.ownerChanged != true
                                finish(report.put("pass", verdict.matchingRecolourReplay && owned &&
                                    report.getBoolean("undo") && report.getBoolean("redo")))
                            }
                        }
                    }
                }
            }
        }
    }

    /** Presses [press] once per expected picture and compares after each; writes [key] true only if all of them matched. */
    private fun walk(expected: List<JbCanvasView.BenchState>, press: () -> Unit, key: String, report: org.json.JSONObject, then: () -> Unit) {
        var ok = true
        fun step(i: Int) {
            if (i == expected.size) { report.put(key, ok); then(); return }
            press()
            canvas.benchState { s, why ->
                guarded {
                    if (s == null) return@guarded inconclusive("$key read: $why")
                    if (!MatrixDiff.same(expected[i].visible(), s.visible()) || s.undoDepth != expected[i].undoDepth) ok = false
                    step(i + 1)
                }
            }
        }
        step(0)
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

    private fun frame(s: JbCanvasView.BenchState) = UnibrushParityEvidence.Frame(s.planes["visible"], s.undoDepth,
        canvas.lineSnapshot.planes.values.sumOf { it.size })

    private fun JbCanvasView.BenchState.visible(): Map<Long, ByteArray> = planes["visible"].orEmpty()

    /** The picture's bytes in the app's cache, so a host can measure lines on against lines off. */
    private fun dump(s: JbCanvasView.BenchState) {
        val name = "lineParity_${preset.id.replace(Regex("[^a-z0-9]"), "_")}_${canvas.editableLines}.bin"
        java.io.File(canvas.context.cacheDir, name).outputStream().buffered().use { out ->
            for (key in s.visible().keys.sorted()) out.write(s.visible().getValue(key))
        }
    }

    /** The picture as one number, so two runs (lines on, lines off) can be compared in the log. */
    private fun crc(tiles: Map<Long, ByteArray>): Long {
        val c = java.util.zip.CRC32()
        for (key in tiles.keys.sorted()) c.update(tiles.getValue(key))
        return c.value
    }

    private fun json() = org.json.JSONObject().put("case", CASE).put("brush", preset.id).put("run", run)
        .put("editableLines", canvas.editableLines)

    private fun finish(report: org.json.JSONObject) {
        if (lifetime.closed) return
        end()
        Log.i(TAG, report.toString())
        ui.postDelayed({ next() }, spec.settleMs)
    }

    private fun inconclusive(reason: String) {
        if (lifetime.closed) return
        end()
        Log.i(TAG, json().put("status", "INCONCLUSIVE").put("pass", false).put("reason", reason).toString())
        abort()
    }

    private fun failed(e: Throwable) = inconclusive("${e.javaClass.simpleName}: ${e.message}")

    private fun guarded(block: () -> Unit) { lifetime.runIfActive { try { block() } catch (e: Exception) { failed(e) } } }

    private fun end() {
        lifetime.close()
        ui.removeCallbacks(timeout)
        notice?.close()
        canvas.benchFailLiftRedraw = false
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

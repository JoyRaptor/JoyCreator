package cc.joycreator.joybrush.android.bench

import android.os.Handler
import android.os.Looper
import android.util.Log
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.androidkit.UnibrushResources
import cc.joycreator.joybrush.androidkit.bench.StrokeReplay
import cc.joycreator.joybrush.androidkit.io.JbArchive
import cc.joycreator.joybrush.androidkit.lines.LineEdit
import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.core.brush.BrushAssetPaths
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.FrozenBrushPngs
import cc.joycreator.joybrush.core.brush.FrozenBrushRegistry
import cc.joycreator.joybrush.core.brush.UnibrushModes
import cc.joycreator.joybrush.core.select.Homography
import cc.joycreator.joybrush.core.select.LineTransform
import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.stroke.StrokeEdit
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import java.io.File

/**
 * The `lineMatrix` and `lineReopen` cases (Unibrush finish U8), run with `jb_lines true`.
 *
 * lineMatrix: one stroke of this brush is moved, rotated and re-brushed by ordinary line edits. Each edit must change the
 * picture and be one undo step; undo walks back through the same pictures to the stroke, redo forward again. The stroke
 * is then saved to a bench file, with the picture's checksum beside it.
 *
 * lineReopen: run after a force-stop. The file opens with no brush library at all, so the drawing has only what it
 * carries. The picture must match the checksum, and the first edit (recolour, recolour back) must redraw the line from
 * its saved recipe to the same picture.
 */
class LineMatrixBench(
    private val canvas: JbCanvasView, private val spec: BenchRunner.Spec, private val preset: BrushPreset, private val run: Int,
    private val reopen: Boolean, private val next: () -> Unit, private val abort: () -> Unit,
) {
    private val ui = Handler(Looper.getMainLooper())
    private val lifetime = UnibrushProbeLifetime()
    private val report = org.json.JSONObject().put("case", if (reopen) "lineReopen" else "lineMatrix").put("brush", preset.id).put("run", run)
    private val file = File(canvas.context.cacheDir, "lineMatrix_${preset.id.replace(Regex("[^a-z0-9]"), "_")}.jb")
    private val sum = File(file.path + ".crc")
    private lateinit var timeout: Runnable

    fun start() {
        timeout = Runnable { inconclusive("Timed out waiting for the canvas") }
        ui.postDelayed(timeout, 60_000L)
        try { if (reopen) reopened() else { canvas.clearCanvas(); canvas.preset = preset; ui.postDelayed({ guarded { draw() } }, 300) } }
        catch (e: Exception) { failed(e) }
    }

    private fun draw() {
        val samples = StrokeReplay.case("slowC", canvas.width, canvas.height) ?: return inconclusive("no replay stroke")
        StrokeReplay.play(canvas, samples, null, onDown = {}, onUp = { ui.postDelayed({ guarded { drawn() } }, TAIL_MS) })
    }

    private fun drawn() = read("stroke") { a ->
        val (plane, record) = onlyLine() ?: return@read finish(report.put("pass", false).put("reason", "not kept as a line: ${canvas.lastLineRefusal}"))
        val h = record.samples.first()
        val centre = Pt(h.x.toDouble(), h.y.toDouble())
        val moved = LineTransform.apply(record, Homography.translate(150.0, 90.0), WEIGHT) ?: return@read inconclusive("move refused")
        val turned = LineTransform.apply(moved, Homography.rotate(.4, centre), WEIGHT) ?: return@read inconclusive("rotate refused")
        // A picture brush moves to a plain tip, a plain one to a picture: both directions of a re-brush carry their pictures.
        val pictures = BrushAssetPaths.required(preset).isNotEmpty()
        val target = UnibrushModes.presets().first { it.id == if (pictures) "unibrush.round" else "unibrush.texture" }
        val pngs = if (pictures) FrozenBrushPngs.empty() else checkNotNull(UnibrushResources().pngs(target)) { "no Texture pictures" }
        val snapshot = canvas.lineSnapshot
        val frozen = FrozenBrushRegistry.freezeWithAssets(snapshot.brushes, snapshot.brushAssets, target, pngs) { "lb-bench-rebrush" }
        val rebrushed = StrokeEdit.rebrush(turned, frozen.id)
        val assets = if (pictures) emptyMap() else mapOf(frozen.id to pngs)
        edit(plane, record.id, moved, emptyMap(), "move") { m ->
            edit(plane, record.id, turned, emptyMap(), "rotate") { t ->
                edit(plane, record.id, rebrushed, mapOf(frozen.id to frozen.preset), "rebrush", assets) { r ->
                    val chain = listOf(a, m, t, r)
                    if ((1 until chain.size).any { MatrixDiff.same(chain[it - 1].visible(), chain[it].visible()) })
                        return@edit finish(report.put("pass", false).put("reason", "an edit did not change the picture"))
                    if ((1 until chain.size).any { chain[it].undoDepth != chain[it - 1].undoDepth + 1 })
                        return@edit finish(report.put("pass", false).put("reason", "an edit was not exactly one undo step"))
                    walk(listOf(t, m, a), { canvas.undo() }, "undo") {
                        walk(listOf(m, t, r), { canvas.redo() }, "redo") {
                            walk(listOf(t, m, a), { canvas.undo() }, "undoAgain") { save(a) }
                        }
                    }
                }
            }
        }
    }

    private fun save(a: JbCanvasView.BenchState) {
        canvas.snapshot({ contents ->
            guarded {
                JbArchive.save(file, contents)
                sum.writeText(crc(a.visible()).toString())
                finish(report.put("bytes", file.length()).put("pass", report.getBoolean("undo") && report.getBoolean("redo") && report.getBoolean("undoAgain")))
            }
        }, { why -> guarded { inconclusive("snapshot: $why") } })
    }

    private fun reopened() {
        if (!file.isFile || !sum.isFile) return inconclusive("no saved file: run lineMatrix first")
        val contents = JbArchive.open(file, null)
        canvas.load(contents) {
            ui.postDelayed({ guarded {
                read("open") { o ->
                    val (plane, record) = onlyLine() ?: return@read finish(report.put("pass", false).put("reason", "the line did not come back"))
                    val same = crc(o.visible()).toString() == sum.readText()
                    val other = if (record.colorArgb == OTHER) DRAW else OTHER
                    edit(plane, record.id, record.copy(colorArgb = other), emptyMap(), "recolour") { m ->
                        edit(plane, record.id, record, emptyMap(), "recolourBack") { b ->
                            val redrawn = MatrixDiff.same(o.visible(), b.visible())
                            val changed = !MatrixDiff.same(o.visible(), m.visible())
                            finish(report.put("sameAsSaved", same).put("firstEditChanged", changed).put("firstEditRedrawn", redrawn)
                                .put("pass", same && changed && redrawn))
                        }
                    }
                }
            } }, TAIL_MS)
        }
    }

    private fun onlyLine(): Pair<LinePlane, StrokeRecord>? =
        canvas.lineSnapshot.planes.entries.firstNotNullOfOrNull { (plane, list) -> list.singleOrNull()?.let { plane to it } }

    private fun edit(plane: LinePlane, id: String, after: StrokeRecord, brushes: Map<String, BrushPreset>, name: String,
                     assets: Map<String, FrozenBrushPngs> = emptyMap(), then: (JbCanvasView.BenchState) -> Unit) {
        canvas.submitLineEdit(LineEdit.Replace(listOf(AddressedStroke(plane.address(id), after)), brushes, assets)) { result ->
            guarded {
                if (!result.ok) return@guarded finish(report.put("pass", false).put("reason", "$name refused: ${result.message}"))
                read(name, then)
            }
        }
    }

    private fun walk(expected: List<JbCanvasView.BenchState>, press: () -> Unit, key: String, then: () -> Unit) {
        var ok = true
        fun step(i: Int) {
            if (i == expected.size) { report.put(key, ok); then(); return }
            press()
            read(key) { s ->
                if (!MatrixDiff.same(expected[i].visible(), s.visible()) || s.undoDepth != expected[i].undoDepth) ok = false
                step(i + 1)
            }
        }
        step(0)
    }

    private fun read(what: String, then: (JbCanvasView.BenchState) -> Unit) {
        canvas.benchState { s, why -> guarded { if (s == null) inconclusive("$what read: $why") else then(s) } }
    }

    private fun JbCanvasView.BenchState.visible(): Map<Long, ByteArray> = planes["visible"].orEmpty()

    /** Empty tiles are left out: one the stroke only touched comes back from a file as no tile at all, the same picture. */
    private fun crc(tiles: Map<Long, ByteArray>): Long {
        val c = java.util.zip.CRC32()
        for (key in tiles.keys.sorted()) tiles.getValue(key).takeIf { t -> t.any { it != 0.toByte() } }?.let { c.update(it) }
        return c.value
    }

    private fun finish(r: org.json.JSONObject) {
        if (lifetime.closed) return
        end()
        Log.i(TAG, r.toString())
        ui.postDelayed({ next() }, spec.settleMs)
    }

    private fun inconclusive(reason: String) {
        if (lifetime.closed) return
        end()
        Log.i(TAG, report.put("status", "INCONCLUSIVE").put("pass", false).put("reason", reason).toString())
        abort()
    }

    private fun failed(e: Throwable) = inconclusive("${e.javaClass.simpleName}: ${e.message}")
    private fun guarded(block: () -> Unit) { lifetime.runIfActive { try { block() } catch (e: Exception) { failed(e) } } }
    private fun end() { lifetime.close(); ui.removeCallbacks(timeout) }

    private companion object {
        const val TAG = BenchRunner.TAG
        const val TAIL_MS = 250L
        const val DRAW = 0xFF356DAB.toInt()
        const val OTHER = 0xFFC8501E.toInt()
        val WEIGHT = LineTransform.Weight.SCALE
    }
}

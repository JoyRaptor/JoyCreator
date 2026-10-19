package cc.joycreator.joybrush.android.bench

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import cc.joycreator.joybrush.androidkit.BrushLibrary
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.androidkit.bench.FrameStats
import cc.joycreator.joybrush.androidkit.bench.StrokeReplay
import cc.joycreator.joybrush.androidkit.tools.CanvasTool
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_SMUDGE
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.doc.BoardDocumentOps
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.doc.RegionChange
import cc.joycreator.joybrush.core.doc.SpriteGrid
import org.json.JSONArray
import org.json.JSONObject

/**
 * The device matrix (LEAD_PLAN_20261016 step 9): every shipped brush on every board kind, measured, on the phone.
 *
 *     adb shell am start -n com.fadcam.beta/com.fadcam.joybrush.JoyBrushBenchActivity --es jb_bench matrix
 *         [--es jb_bench_brushes all|id,id] [--es jb_matrix_boards canvas,animation,tile,sprite]
 *         [--ei jb_matrix_from N] [--ei jb_matrix_to N]
 *
 * Each cell starts from a brand-new empty drawing ([JbCanvasView.benchFreshDrawing], the call a file open makes), makes the
 * board kind with the app's own board operations ([JbCanvasView.editBoards], [JbCanvasView.setTileBoard]), replays one
 * ~0.6 s scribble through the canvas's real touch path ([StrokeReplay]), and reads the layer back before, after and after one
 * undo. One JSON line per cell on logcat tag `JoyBrushPerf` (`"case":"matrix"`), a `start` line before it so a crash still
 * names the cell. The brushes that act on what is already there (the erasers, the smudges) are given two crossing inked
 * strokes first; those are part of the before picture, never of the measurement.
 *
 * A refusal in words is a valid outcome and is recorded; a stroke that changes nothing and says nothing is a failure.
 */
class MatrixBench(private val canvas: JbCanvasView, private val spec: BenchRunner.Spec, private val onDone: () -> Unit) {

    enum class Kind(val label: String) { CANVAS("canvas"), ANIMATION("animation"), TILE("tile"), SPRITE("sprite") }

    private class Job(val index: Int, val kind: Kind, val preset: BrushPreset)

    private val ui = Handler(Looper.getMainLooper())
    private val jobs = ArrayDeque<Job>()
    private val refusals = ArrayList<String>()
    private var priorRefused: ((String) -> Unit)? = null
    private var priorStrokeEnded: (() -> Unit)? = null
    private var priorTool: CanvasTool? = null
    private var startedAt = 0L
    private var cells = 0
    private lateinit var primer: BrushPreset

    fun start() {
        val all = BrushLibrary.builtIn()
        primer = (all.firstOrNull { it.engine == "stamp" && it.id.endsWith("ink") } ?: all.first { it.engine == "stamp" })
            .copy(size = Param(PRIMER_PX))
        val brushes = if (spec.brushes.any { it.equals("all", true) }) all else spec.brushes.mapNotNull { b ->
            (all.firstOrNull { it.id.equals(b, true) } ?: all.firstOrNull { it.name.equals(b, true) }).also { if (it == null) Log.w(TAG, "no brush '$b'") }
        }
        val kinds = spec.matrixBoards.ifEmpty { Kind.values().map { it.label } }.mapNotNull { l -> Kind.values().firstOrNull { it.label == l.lowercase() } }
        var index = 0
        for (k in kinds) for (b in brushes) { if (index in spec.matrixFrom..spec.matrixTo) jobs.addLast(Job(index, k, b)); index++ }
        Log.i(TAG, JSONObject().put("case", "matrix").put("event", "plan").put("cells", index).put("queued", jobs.size)
            .put("boards", JSONArray(kinds.map { it.label })).put("brushes", JSONArray(brushes.map { it.id })).toString())
        // Refusals are collected, not toasted; the stroke-ended hook (recent colours, autosave clock) is left out of a bench.
        priorRefused = canvas.onRefused; canvas.onRefused = { why -> refusals.add(why) }
        priorStrokeEnded = canvas.onStrokeEnded; canvas.onStrokeEnded = null
        priorTool = canvas.tool
        canvas.benchWatch = true
        startedAt = SystemClock.uptimeMillis()
        waitForSurface()
    }

    private fun waitForSurface() {
        if (!canvas.surfaceReady || canvas.width == 0) { ui.postDelayed({ waitForSurface() }, 200); return }
        ui.postDelayed({ next() }, 1500)
    }

    private fun next() {
        val job = jobs.removeFirstOrNull()
        if (job == null) {
            canvas.benchWatch = false
            canvas.onRefused = priorRefused; canvas.onStrokeEnded = priorStrokeEnded
            canvas.tool = priorTool
            Log.i(TAG, JSONObject().put("case", "matrix").put("event", "done").put("cells", cells)
                .put("elapsedMs", SystemClock.uptimeMillis() - startedAt).toString())
            onDone()
            return
        }
        cells++
        Cell(job).run()
    }

    private inner class Cell(val job: Job) {
        val kind = job.kind
        val preset = job.preset
        var phase = "start"
        var finished = false
        var rect = RectPx(0, 0, 0, 0)
        var primed = false
        var error: String? = null
        var stateBefore: JbCanvasView.BenchState? = null
        var stateAfter: JbCanvasView.BenchState? = null
        var stateUndone: JbCanvasView.BenchState? = null
        var stats: FrameStats.Summary? = null
        var settled = true
        var settleMs = 0L
        val timeout = Runnable { fail("timeout in phase $phase") }

        fun run() {
            Log.i(TAG, JSONObject().put("case", "matrix").put("event", "start").put("idx", job.index)
                .put("brush", preset.id).put("board", kind.label).toString())
            // A fresh launch has the Select tool in the hand (SelectTool.selectToolInHand defaults to true), and it takes every
            // pen-down as a selection until a brush is tapped on the strip. The bench takes no tool, so the brush paints
            // (measured on the Note 9 2026-10-17: toolTouch claimed 435 of 435 stylus events, startStroke never ran).
            canvas.tool = null
            refusals.clear(); canvas.benchErrors.clear()
            ui.postDelayed(timeout, CELL_TIMEOUT_MS)
            phase = "fresh"
            try { canvas.benchFreshDrawing { later(150) { setUpBoard() } } }
            catch (e: Exception) { fail("fresh drawing refused: ${e.javaClass.simpleName}: ${e.message}") }
        }

        private fun later(ms: Long, block: () -> Unit) { ui.postDelayed({ if (!finished) block() }, ms) }

        private fun fail(why: String) { if (error == null) error = why; finish() }

        // ---- the board kind, made the way the app makes it ----

        private fun setUpBoard() {
            phase = "board"
            val s = (minOf(ZONE_PX, (minOf(canvas.pageWidth, canvas.pageHeight) * 0.8).toInt()) / 8) * 8
            rect = RectPx((canvas.pageWidth - s) / 2, canvas.pageHeight / 5, s, s)
            val name = "Matrix ${kind.label}"
            val make: ((cc.joycreator.joybrush.core.doc.JbDocument) -> RegionChange)? = when (kind) {
                Kind.CANVAS -> null
                Kind.ANIMATION -> { d -> BoardDocumentOps.createAnimation(d, name, rect, ids()) }
                Kind.TILE -> { d -> BoardDocumentOps.createImage(d, name, rect, ids()) }
                Kind.SPRITE -> { d -> BoardDocumentOps.createSprite(d, name, rect, SpriteGrid(2, 2, s / 2, s / 2), ids()) }
            }
            if (make == null) { primeThenMeasure(); return }
            canvas.editBoards(make)
            // The read-back queues behind the edit, so its answer is also "the edit has been applied".
            canvas.benchState(readPixels = false) { st, err ->
                if (err != null) { fail("board read: $err"); return@benchState }
                val board = st?.doc?.boards?.firstOrNull { it.name == name }
                    ?: run { fail("board not made" + refusalText()?.let { ": $it" }.orEmpty()); return@benchState }
                if (kind != Kind.TILE) { later(100) { primeThenMeasure() }; return@benchState }
                phase = "tile arm"
                canvas.setTileBoard(board.id) { actual ->
                    if (actual != board.id) fail("tile not armed" + refusalText()?.let { ": $it" }.orEmpty())
                    else later(100) { primeThenMeasure() }
                }
            }
        }

        private fun ids(): () -> String { var n = 0; return { "mx-${++n}" } }

        // ---- something under the brush, for the brushes that act on what is there ----

        private fun needsBase() = preset.blend == "erase" || preset.engine == ENGINE_SMUDGE

        private fun primeThenMeasure() {
            if (!needsBase()) { measure(); return }
            phase = "prime"
            primed = true
            canvas.preset = primer
            canvas.colorArgb = PRIMER_A
            play(path(passes = 6, mirrored = false, ms = 900.0)) {
                later(150) {
                    canvas.colorArgb = PRIMER_B
                    play(path(passes = 5, mirrored = true, ms = 900.0)) { later(250) { measure() } }
                }
            }
        }

        // ---- the measurement ----

        private fun measure() {
            canvas.preset = preset
            canvas.colorArgb = TEST_COLOR
            phase = "settle before"
            waitIdle(SETTLE_MS) { _ ->
                phase = "before"
                canvas.benchState { st, err ->
                    if (st == null) { fail("before read: $err"); return@benchState }
                    stateBefore = st
                    stroke()
                }
            }
        }

        private fun stroke() {
            phase = "stroke"
            refusals.clear(); canvas.benchErrors.clear()
            val samples = path(passes = 4, mirrored = false, ms = STROKE_MS)
            canvas.startFrameStats {
                play(samples, withStats = true) {
                    ui.postDelayed({
                        canvas.finishFrameStats { s ->
                            stats = s
                            phase = "settle after"
                            val t0 = SystemClock.uptimeMillis()
                            waitIdle(SETTLE_MS) { ok ->
                                settled = ok; settleMs = SystemClock.uptimeMillis() - t0
                                phase = "after"
                                canvas.benchState { st, err ->
                                    if (st == null) { fail("after read: $err"); return@benchState }
                                    stateAfter = st
                                    undo()
                                }
                            }
                        }
                    }, TAIL_MS)
                }
            }
        }

        private fun undo() {
            val before = stateBefore; val after = stateAfter
            if (before == null || after == null || after.undoDepth <= before.undoDepth) { finish(); return }
            phase = "undo"
            canvas.undo()
            waitIdle(SETTLE_MS) { _ ->
                canvas.benchState { st, err ->
                    if (st == null) fail("undo read: $err") else { stateUndone = st; finish() }
                }
            }
        }

        // ---- plumbing ----

        /** Polls the GL thread until nothing is moving (no stroke, media stroke, water or fade) twice in a row, or [limit] ms pass. */
        private fun waitIdle(limit: Long, done: (settled: Boolean) -> Unit) {
            val t0 = SystemClock.uptimeMillis()
            var calm = 0
            fun poll() {
                canvas.benchState(readPixels = false) { st, err ->
                    if (finished) return@benchState
                    if (st == null) { fail("idle read: $err"); return@benchState }
                    calm = if (st.idle && !canvas.strokeInProgress) calm + 1 else 0
                    when {
                        calm >= 2 -> done(true)
                        SystemClock.uptimeMillis() - t0 > limit -> done(false)
                        else -> ui.postDelayed({ if (!finished) poll() }, 250)
                    }
                }
            }
            poll()
        }

        private fun play(samples: List<StrokeReplay.Sample>, withStats: Boolean = false, onUp: () -> Unit) {
            StrokeReplay.play(canvas, samples, if (withStats) canvas.frameStats else null, onDown = {}, onUp = onUp)
        }

        /** The scribble, in view px, for this board kind: inside the zone; across the tile's right edge; inside one sprite cell. */
        private fun path(passes: Int, mirrored: Boolean, ms: Double): List<StrokeReplay.Sample> {
            val s = rect.w.toFloat()
            val x = rect.x.toFloat(); val y = rect.y.toFloat()
            val (x0, x1, y0, y1) = when (kind) {
                Kind.TILE -> listOf(x + 0.6f * s, x + 1.25f * s, y + 0.2f * s, y + 0.8f * s)
                Kind.SPRITE -> listOf(x + 0.5f * s + 0.075f * s, x + s - 0.075f * s, y + 0.5f * s + 0.075f * s, y + s - 0.075f * s)
                else -> listOf(x + 0.15f * s, x + 0.85f * s, y + 0.15f * s, y + 0.85f * s)
            }
            val a = canvas.view.docToScreen(x0, y0); val b = canvas.view.docToScreen(x1, y1)
            return if (mirrored) StrokeReplay.zigzag(b.first, a.first, a.second, b.second, passes, ms)
            else StrokeReplay.zigzag(a.first, b.first, a.second, b.second, passes, ms)
        }

        private fun refusalText(): String? = refusals.distinct().joinToString(" | ").ifEmpty { null }

        // ---- the answer ----

        private fun finish() {
            if (finished) return
            finished = true
            ui.removeCallbacks(timeout)
            val line = try { report() } catch (e: Exception) {
                JSONObject().put("case", "matrix").put("idx", job.index).put("brush", preset.id).put("board", kind.label)
                    .put("verdict", "error").put("error", "report: ${e.javaClass.simpleName}: ${e.message}")
            }
            Log.i(TAG, line.toString())
            ui.postDelayed({ next() }, 300)
        }

        private fun report(): JSONObject {
            val before = stateBefore; val after = stateAfter; val undone = stateUndone
            fun plane(st: JbCanvasView.BenchState?, name: String) = st?.planes?.get(name).orEmpty()
            val strip = (rect.w * 0.25f).toInt()
            val seen = MatrixDiff.count(plane(before, "visible"), plane(after, "visible"), rect, strip)
            val frameKeys = after?.planes?.keys.orEmpty().filter { it.startsWith("frame:") }
            val sharedKeys = after?.planes?.keys.orEmpty().filter { it.startsWith("shared:") }
            val framePx = frameKeys.sumOf { MatrixDiff.count(plane(before, it), plane(after, it)).px }
            val sharedInZone = sharedKeys.sumOf { MatrixDiff.count(plane(before, it), plane(after, it), rect).insidePx }
            val steps = if (before != null && after != null) after.undoDepth - before.undoDepth else 0
            val exact: Boolean? = if (undone != null && before != null) {
                (before.planes.keys + undone.planes.keys).all { MatrixDiff.same(plane(before, it), plane(undone, it)) }
            } else null
            val err = error ?: canvas.benchErrors.firstOrNull()
            val refusedText = refusalText()
            val wrapPx = if (kind == Kind.TILE) seen.stripPx else 0
            val verdict = MatrixDiff.verdict(err, refusedText != null, seen.px, steps, exact, kind.label, wrapPx, framePx, sharedInZone)
            val s = stats
            fun r2(v: Double) = Math.round(v * 100.0) / 100.0
            return JSONObject().put("case", "matrix").put("idx", job.index).put("brush", preset.id).put("name", preset.name)
                .put("engine", preset.engine).put("board", kind.label)
                .put("refused", refusedText ?: JSONObject.NULL).put("error", err ?: JSONObject.NULL)
                .put("changedPx", seen.px).put("changedTiles", seen.tiles)
                .put("undoSteps", steps).put("undoExact", exact ?: JSONObject.NULL)
                .put("p50", s?.let { r2(it.busy.p50) } ?: JSONObject.NULL).put("p95", s?.let { r2(it.busy.p95) } ?: JSONObject.NULL)
                .put("frames", s?.strokeFrames ?: 0)
                // Where a frame's time goes (as the plain bench reports it): mean/p95 of work, display, and every timed
                // paint and media section (MediaPhases, set by JbCanvasView while the bench records).
                .also { j ->
                    if (s != null) {
                        fun pct(x: FrameStats.Pct?) = x?.let { JSONObject().put("p50", r2(it.p50)).put("p95", r2(it.p95)).put("max", r2(it.max)).put("mean", r2(it.mean)) } ?: JSONObject.NULL
                        j.put("workMs", pct(s.work)).put("displayMs", pct(s.display))
                        j.put("phasesMs", JSONObject().also { ph -> for ((k, v) in s.phases) ph.put(k, pct(v)) })
                    }
                }
                .put("verdict", verdict).put("primed", primed).put("settled", settled).put("settleMs", settleMs)
                .also {
                    if (kind == Kind.TILE) it.put("wrapPx", wrapPx).put("leakPx", seen.outsidePx)
                    if (kind == Kind.ANIMATION) it.put("framePx", framePx).put("sharedInBoardPx", sharedInZone)
                    if (canvas.benchErrors.size > 1) it.put("errors", JSONArray(canvas.benchErrors.toList()))
                }
        }
    }

    companion object {
        private const val TAG = BenchRunner.TAG
        private const val ZONE_PX = 480
        private const val STROKE_MS = 600.0
        private const val TAIL_MS = 250L
        private const val SETTLE_MS = 45_000L
        private const val CELL_TIMEOUT_MS = 240_000L
        private const val PRIMER_PX = 44f
        private const val PRIMER_A = 0xFFB03030.toInt()
        private const val PRIMER_B = 0xFF2050B0.toInt()
        private const val TEST_COLOR = 0xFF205CA9.toInt()
    }
}

package cc.joycreator.joybrush.android.bench

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import cc.joycreator.joybrush.androidkit.BrushLibrary
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.androidkit.bench.FrameStats
import cc.joycreator.joybrush.androidkit.bench.StrokeReplay
import cc.joycreator.joybrush.core.brush.BrushPreset

/**
 * Debug-only stroke replay benchmark (LEAD_PLAN_20261010 step 3). Started over adb through the debug build's
 * `JoyBrushBenchActivity`, never from the UI:
 *
 *     adb shell am start -n com.fadcam.beta/com.fadcam.joybrush.JoyBrushBenchActivity \
 *         --es jb_bench "fastC,scribble" --es jb_bench_brushes "ink,media:dry:Proto" --ei jb_bench_runs 3
 *
 * Results go to logcat, tag [TAG], one line per run plus a `done` line. In bench mode the screen opens a fresh,
 * unsaved drawing and never restores or writes the working file (the activity's job), so the person's drawing is
 * never touched.
 */
class BenchRunner(private val canvas: JbCanvasView, private val spec: Spec, private val onDone: () -> Unit) {

    class Spec(val cases: List<String>, val brushes: List<String>, val runs: Int, val clear: Boolean, val legacy: Boolean,
               /** Per case (same order as [cases]): the colour to paint it in, or none to keep the brush's. */
               val colors: List<Int> = emptyList(), val slope: FloatArray? = null, val wetness: Int? = null,
               /** Fewer, longer water substeps per frame (4 = shipped): the same simulated time, less accuracy. */
               val wetSubsteps: Int? = null, val holdMs: Long = 0L, val settleMs: Long = 800L)

    private val ui = Handler(Looper.getMainLooper())
    private val jobs = ArrayDeque<Triple<BrushPreset, String, Int>>()

    fun start() {
        canvas.mediaLegacyWindow = spec.legacy
        spec.slope?.let { canvas.setMediaSlope(it[0], it[1]) }
        canvas.mediaWetQuality = spec.wetSubsteps?.let { n ->
            val shipped = cc.joycreator.joybrush.core.media.WetConstants()
            shipped.copy(dt = shipped.dt * shipped.substeps / n, substeps = n)
        }
        val all = BrushLibrary.builtIn()
        Log.i(TAG, "brushes available: " + all.joinToString(",") { it.id })
        for (b in spec.brushes) {
            val p = all.firstOrNull { it.id.equals(b, true) } ?: all.firstOrNull { it.name.equals(b, true) }
            if (p == null) { Log.w(TAG, "no brush '$b'"); continue }
            for (c in spec.cases) for (r in 1..spec.runs) jobs.addLast(Triple(p, c, r))
        }
        waitForSurface()
    }

    private fun waitForSurface() {
        if (!canvas.surfaceReady || canvas.width == 0) { ui.postDelayed({ waitForSurface() }, 200); return }
        ui.postDelayed({ next() }, 1500)   // let startup settle (paper bakes, first frames)
    }

    private fun next() {
        val job = jobs.removeFirstOrNull() ?: run {
            // The comparison holds the picture for a screenshot while the water runs on.
            if (spec.holdMs > 0) { Log.i(TAG, "hold ${spec.holdMs}"); ui.postDelayed({ Log.i(TAG, "done"); onDone() }, spec.holdMs) }
            else { Log.i(TAG, "done"); onDone() }
            return
        }
        val (preset, case, run) = job
        val bakeOperator = when (case) {
            "recordedBakeProbe" -> "normal"
            "recordedBakeEraseProbe" -> "erase"
            "recordedBakeBehindProbe" -> "behind"
            "recordedBakeMultiplyProbe" -> "multiply"
            "recordedBakeScreenProbe" -> "screen"
            else -> null
        }
        if (case == "recordedProbe" || bakeOperator != null) {
            val probePreset = if (bakeOperator == null) preset else preset.copy(
                opacity = cc.joycreator.joybrush.core.brush.Param(0.55f), blend = bakeOperator)
            val record = cc.joycreator.joybrush.core.stroke.StrokeRecord("recorded-probe", preset.id, 19L,
                0.2f, 1f, (0 until 24).map { i -> cc.joycreator.joybrush.core.input.PenSample(
                    if (bakeOperator == null) 64f + i * 4f else 232f + i * 6f,
                    if (bakeOperator == null) 96f + (i % 6) * 2f else 240f + (i % 6) * 5f, i * 16.0,
                    pressure = 0.7f, tilt = 0.4f, azimuth = 0.3f) }, colorArgb = 0xFF205CA9.toInt(), seq = 1)
            canvas.runRecordedReplayProbe(probePreset, record, bake = bakeOperator != null) { result ->
                val report = org.json.JSONObject(result).put("brush", preset.id).put("case", case).put("run", run)
                    .put("fixtureOpacity", probePreset.opacity.base)
                    .put("proofScope", if (bakeOperator == null) "Android replay adapter and ordered arithmetic; no live wiring or physical quality claim"
                        else "GPU source bake and public undo/redo on throwaway engine; no live display, save or physical quality claim")
                Log.i(TAG, report.toString())
                ui.postDelayed({ next() }, spec.settleMs)
            }
            return
        }
        val samples = StrokeReplay.case(case, canvas.width, canvas.height)
        if (samples == null) { Log.w(TAG, "no case '$case'"); next(); return }
        if (spec.clear) canvas.clearCanvas()
        canvas.preset = spec.wetness?.let { w -> preset.media?.let { m -> preset.copy(media = m.copy(wetness = w)) } } ?: preset
        spec.colors.getOrNull(spec.cases.indexOf(case))?.let { canvas.colorArgb = it }
        ui.postDelayed({
            canvas.startFrameStats {
                StrokeReplay.play(canvas, samples, canvas.frameStats, onDown = {}, onUp = {
                    // Keep recording the frames that finish the stroke (the last batch, its write-back).
                    ui.postDelayed({
                        canvas.finishFrameStats { s -> report(preset, case, run, samples.size, s); ui.postDelayed({ next() }, spec.settleMs) }
                    }, TAIL_MS)
                })
            }
        }, 300)
    }

    private fun report(p: BrushPreset, case: String, run: Int, samples: Int, s: FrameStats.Summary) {
        fun f(v: Double) = "%.2f".format(v)
        fun pct(x: FrameStats.Pct?) = if (x == null) "null" else "{\"p50\":${f(x.p50)},\"p95\":${f(x.p95)},\"max\":${f(x.max)},\"mean\":${f(x.mean)}}"
        Log.i(TAG, "{\"legacy\":${spec.legacy},\"brush\":\"${p.id}\",\"case\":\"$case\",\"run\":$run,\"samples\":$samples," +
            "\"frames\":${s.frames},\"strokeFrames\":${s.strokeFrames},\"firstFrameMs\":${f(s.firstFrameMs)}," +
            "\"busyMs\":${pct(s.busy)},\"workMs\":${pct(s.work)},\"mediaMs\":${pct(s.media)},\"displayMs\":${pct(s.display)}," +
            "\"finishMs\":${pct(s.finish)},\"simMs\":${pct(s.sim)},\"flushMs\":${pct(s.flush)},\"gapMs\":${pct(s.gap)},\"latencyMs\":${pct(s.latency)}," +
            "\"gapsOver17\":${s.over17},\"gapsOver34\":${s.over34},\"mediaFrames\":${s.mediaFrames}," +
            "\"windowMoves\":${s.mediaWindowMoves},\"windowLoads\":${s.mediaWindowLoads}}")
    }

    companion object {
        const val TAG = "JoyBrushPerf"
        private const val TAIL_MS = 250L
        private const val SETTLE_MS = 800L

        /** The bench request in [intent], or null: debug builds only, and only when asked for. */
        fun spec(intent: Intent?, info: ApplicationInfo): Spec? {
            if (intent == null || info.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
            val cases = intent.getStringExtra("jb_bench") ?: return null
            fun list(s: String?) = s?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            return Spec(list(cases), list(intent.getStringExtra("jb_bench_brushes")).ifEmpty { listOf("ink") },
                intent.getIntExtra("jb_bench_runs", 3).coerceIn(1, 20), intent.getBooleanExtra("jb_bench_clear", true),
                intent.getBooleanExtra("jb_media_legacy", false),
                colors = list(intent.getStringExtra("jb_bench_colors")).map { android.graphics.Color.parseColor(it) },
                slope = intent.getStringExtra("jb_slope")?.split(",")?.mapNotNull { it.trim().toFloatOrNull() }?.takeIf { it.size == 2 }?.toFloatArray(),
                wetness = intent.getIntExtra("jb_wetness", -1).takeIf { it >= 0 },
                wetSubsteps = intent.getIntExtra("jb_wet_substeps", -1).takeIf { it in 1..16 },
                holdMs = intent.getLongExtra("jb_hold_ms", 0L), settleMs = intent.getLongExtra("jb_settle_ms", 800L))
        }
    }
}

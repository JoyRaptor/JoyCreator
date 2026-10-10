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

    class Spec(val cases: List<String>, val brushes: List<String>, val runs: Int, val clear: Boolean, val legacy: Boolean)

    private val ui = Handler(Looper.getMainLooper())
    private val jobs = ArrayDeque<Triple<BrushPreset, String, Int>>()

    fun start() {
        canvas.mediaLegacyWindow = spec.legacy
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
        val job = jobs.removeFirstOrNull() ?: run { Log.i(TAG, "done"); onDone(); return }
        val (preset, case, run) = job
        val samples = StrokeReplay.case(case, canvas.width, canvas.height)
        if (samples == null) { Log.w(TAG, "no case '$case'"); next(); return }
        if (spec.clear) canvas.clearCanvas()
        canvas.preset = preset
        ui.postDelayed({
            canvas.startFrameStats {
                StrokeReplay.play(canvas, samples, onDown = {}, onUp = {
                    // Keep recording the frames that finish the stroke (the last batch, its write-back).
                    ui.postDelayed({
                        canvas.finishFrameStats { s -> report(preset, case, run, samples.size, s); ui.postDelayed({ next() }, SETTLE_MS) }
                    }, TAIL_MS)
                })
            }
        }, 300)
    }

    private fun report(p: BrushPreset, case: String, run: Int, samples: Int, s: FrameStats.Summary) {
        fun f(v: Double) = "%.2f".format(v)
        fun pct(x: FrameStats.Pct?) = if (x == null) "null" else "{\"p50\":${f(x.p50)},\"p95\":${f(x.p95)},\"max\":${f(x.max)},\"mean\":${f(x.mean)}}"
        Log.i(TAG, "{\"legacy\":${spec.legacy},\"brush\":\"${p.id}\",\"case\":\"$case\",\"run\":$run,\"samples\":$samples,\"frames\":${s.frames}," +
            "\"cpuMs\":${pct(s.cpu)},\"gapMs\":${pct(s.gap)},\"gpuMs\":${pct(s.gpu)},\"gpuFrames\":${s.gpuFrames}," +
            "\"gapsOver8\":${s.over8},\"gapsOver16\":${s.over16},\"mediaFrames\":${s.mediaFrames}," +
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
                intent.getBooleanExtra("jb_media_legacy", false))
        }
    }
}

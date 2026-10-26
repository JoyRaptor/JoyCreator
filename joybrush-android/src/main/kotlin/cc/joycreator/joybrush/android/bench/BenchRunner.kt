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
class BenchRunner(private val canvas: JbCanvasView, private val spec: Spec, private val finished: () -> Unit) {
    // The atlas switch is process-wide: a run that changed it puts it back, so the app after a bench draws as shipped.
    private val atlasBefore = canvas.mediaAtlasEnabled
    private val coarseBefore = cc.joycreator.joybrush.androidkit.gl.media.MediaLayerEngine.coarseWaterDefault
    private val rowsBefore = cc.joycreator.joybrush.androidkit.gl.media.MediaLayerEngine.atlasRowPasses
    private val jobsBefore = canvas.mediaWaterJobs
    private val budgetBefore = canvas.mediaBudgetBytes
    private val editableLinesBefore = canvas.editableLines
    // Every refusal a run meets, in the log beside its frames (the canvas tells the person in words; the bench counts them).
    private val refusedBefore = canvas.onRefused.also { prior -> canvas.onRefused = { m -> Log.i(TAG, "refused: $m"); prior?.invoke(m) } }
    private val onDone: () -> Unit = {
        if (spec.atlas != null) canvas.mediaAtlasEnabled = atlasBefore
        cc.joycreator.joybrush.core.media.PasteSpacing.fixed = null
        if (spec.coarseWater != null) cc.joycreator.joybrush.androidkit.gl.media.MediaLayerEngine.coarseWaterDefault = coarseBefore
        if (spec.atlasRows != null) cc.joycreator.joybrush.androidkit.gl.media.MediaLayerEngine.atlasRowPasses = rowsBefore
        if (spec.waterJobs != null) canvas.mediaWaterJobs = jobsBefore
        if (spec.mediaBudgetMb != null) canvas.mediaBudgetBytes = budgetBefore
        if (spec.editableLines != null) canvas.editableLines = editableLinesBefore
        if (spec.dropDry != null) cc.joycreator.joybrush.androidkit.gl.media.MediaAtlasDropDry.on = true
        canvas.onRefused = refusedBefore
        finished()
    }

    class Spec(val cases: List<String>, val brushes: List<String>, val runs: Int, val clear: Boolean, val legacy: Boolean,
               /** Per case (same order as [cases]): the colour to paint it in, or none to keep the brush's. */
               val colors: List<Int> = emptyList(), val slope: FloatArray? = null, val wetness: Int? = null,
               /** Fewer, longer water substeps per frame (4 = shipped): the same simulated time, less accuracy. */
               val wetSubsteps: Int? = null, val holdMs: Long = 0L, val settleMs: Long = 800L,
               /** The `matrix` case ([MatrixBench]): which board kinds (none = all four) and which cell numbers (from..to) to run. */
               val matrixBoards: List<String> = emptyList(), val matrixFrom: Int = 0, val matrixTo: Int = Int.MAX_VALUE,
               /** The owner's watercolour comparison to run with (WaterTest id: today, a, s, flat, flatnr, all); none = as set. */
               val water: String? = null,
               /** The media atlas on or off for this run (`jb_media_atlas`); none = as shipped. */
               val atlas: Boolean? = null,
               /** Every paste stroke at this step spacing (`jb_paste_spacing`, 1 = shipped hops); none = as the phone keeps up. */
               val pasteSpacing: Double? = null,
               /** Coarse water on or off for this run (`jb_coarse_water`); none = as shipped. */
               val coarseWater: Boolean? = null,
               /** The atlas's scattered passes by row (`jb_atlas_rows` 0 one pass, 1 scissor per row, 2 and a flush, shipped); none = as shipped. */
               val atlasRows: Int? = null,
               /** Water jobs in the atlas on or off for this run (`jb_water_jobs`); none = as shipped. */
               val waterJobs: Boolean? = null,
               /** The drawing's media room for this run, MB (`jb_media_budget_mb`): a long session's limit, reached sooner. */
               val mediaBudgetMb: Int? = null,
               /** Dry tiles' water let go between strokes (`jb_drop_dry`); none = as shipped. */
               val dropDry: Boolean? = null,
               /** How long after the surface the first case starts (`jb_start_ms`): measures a cold first stroke. */
               val startMs: Long = 1500L,
               /** Keep FrameStats through watercolour idle confirmation; opt-in diagnostic tail (`jb_full_tail`). */
               val fullTail: Boolean = false,
               /** Temporary canvas-only editable-lines override (`jb_editable_lines`); never writes preferences. */
               val editableLines: Boolean? = null,
               /** Select evidence identity; the host ignores all other runs in logcat. */
               val selectRunId: String = "")

    private val ui = Handler(Looper.getMainLooper())
    private val jobs = ArrayDeque<Triple<BrushPreset, String, Int>>()
    private var selectBench: SelectBench? = null
    private var selectBrushModeBench: SelectBrushModeBench? = null
    /** Null for ordinary/non-Select work; a request captures the exact Select session's read-only guard. */
    internal val selectCopyGuard: (() -> Boolean)? get() =
        selectBench?.acceptsCopyResult ?: selectBrushModeBench?.acceptsCopyResult

    /** Only Select owns synthetic UI gestures; abort them when its Activity leaves the foreground. */
    fun abortSelect() {
        val selectStopped = selectBench?.abort() == true
        val a10Stopped = selectBrushModeBench?.abort() == true
        if (selectStopped || a10Stopped) onDone()
    }

    fun start() {
        spec.editableLines?.let { canvas.editableLines = it; Log.i(TAG, "editable lines override: $it") }
        canvas.mediaLegacyWindow = spec.legacy
        spec.slope?.let { canvas.setMediaSlope(it[0], it[1]) }
        canvas.mediaWetQuality = spec.wetSubsteps?.let { n ->
            val shipped = cc.joycreator.joybrush.core.media.WetConstants()
            shipped.copy(dt = shipped.dt * shipped.substeps / n, substeps = n)
        }
        spec.atlas?.let { canvas.mediaAtlasEnabled = it; Log.i(TAG, "media atlas: $it") }
        spec.pasteSpacing?.let { cc.joycreator.joybrush.core.media.PasteSpacing.fixed = it; Log.i(TAG, "paste spacing: $it") }
        spec.coarseWater?.let { cc.joycreator.joybrush.androidkit.gl.media.MediaLayerEngine.coarseWaterDefault = it; Log.i(TAG, "coarse water: $it") }
        spec.atlasRows?.let { cc.joycreator.joybrush.androidkit.gl.media.MediaLayerEngine.atlasRowPasses = it; Log.i(TAG, "atlas rows: $it") }
        spec.waterJobs?.let { canvas.mediaWaterJobs = it; Log.i(TAG, "water jobs: $it") }
        spec.mediaBudgetMb?.let { canvas.mediaBudgetBytes = it.toLong() shl 20; Log.i(TAG, "media room: $it MB") }
        spec.dropDry?.let { cc.joycreator.joybrush.androidkit.gl.media.MediaAtlasDropDry.on = it; Log.i(TAG, "drop dry water: $it") }
        spec.water?.let { canvas.mediaWaterAB = cc.joycreator.joybrush.android.WaterTest.of(it).ab; Log.i(TAG, "water test: $it") }
        if ("matrix" in spec.cases) { MatrixBench(canvas, spec, onDone).start(); return }
        if ("select" in spec.cases) {
            selectBench = SelectBench(canvas, spec.selectRunId, onDone).also { it.start() }
            return
        }
        if ("selectA10" in spec.cases) {
            selectBrushModeBench = SelectBrushModeBench(canvas, spec.selectRunId, onDone)
            selectBrushModeBench?.start()
            return
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
        ui.postDelayed({ next() }, spec.startMs)   // let startup settle (paper bakes, first frames, media prewarm)
    }

    private fun next() {
        val job = jobs.removeFirstOrNull() ?: run {
            // The comparison holds the picture for a screenshot while the water runs on.
            if (spec.holdMs > 0) { Log.i(TAG, "hold ${spec.holdMs}"); ui.postDelayed({ Log.i(TAG, "done"); onDone() }, spec.holdMs) }
            else { Log.i(TAG, "done"); onDone() }
            return
        }
        val (preset, case, run) = job
        if (case == "recordedCaptureProbe") { runRecordedCaptureCase(preset, case, run); return }
        if (case == "mediaAtlasProbe") {
            // Media Atlas v2 B1: window vs atlas engines on this GPU, same frames, readback compare (MediaAtlasProbe).
            canvas.runMediaAtlasProbe { result ->
                val report = try { org.json.JSONObject(result) } catch (e: Exception) { org.json.JSONObject().put("pass", false).put("raw", result) }
                logRecordedReport(report.put("brush", preset.id).put("case", case).put("run", run), preset.id, case, run)
                ui.postDelayed({ next() }, spec.settleMs)
            }
            return
        }
        val bakeOperator = when (case) {
            "recordedBakeProbe" -> "normal"
            "recordedSavedProbe" -> "normal"
            "recordedBakeEraseProbe" -> "erase"
            "recordedBakeBehindProbe" -> "behind"
            "recordedBakeMultiplyProbe" -> "multiply"
            "recordedBakeScreenProbe" -> "screen"
            else -> null
        }
        val jointProbe = case == "recordedJointProbe" || case == "recordedJointColdProbe"
        val promotionProbe = case == "recordedPromotionProbe" || case == "recordedPromotionRegionProbe" ||
            case == "recordedPromotionColdProbe" || case == "recordedColdPayloadProbe" || case == "recordedColdFailureProbe"
        val capturedCopy = case == "recordedCapturedCopyProbe"
        val capturedJoint = case == "recordedCapturedJointProbe"
        val watercolourEdits = case == "recordedWatercolourEditProbe"
        val watercolourOrdinary = case == "recordedWatercolourOrdinaryProbe"
        if (case == "nativeFrameDuplicateProbe") {
            canvas.runNativeFrameDuplicateProbe { result ->
                val report = org.json.JSONObject(result).put("case", case).put("run", run)
                    .put("proofScope", "Disposable ordinary Ink frame duplicate, clipped physical bases, independent Delete and Undo/redo; no captured/joint, save/recovery, watercolor or production activation proof")
                logRecordedReport(report, preset.id, case, run)
                ui.postDelayed({ next() }, spec.settleMs)
            }
            return
        }
        if (case == "recordedProbe" || bakeOperator != null || jointProbe || promotionProbe || capturedCopy || capturedJoint || watercolourEdits || watercolourOrdinary) {
            val probePreset = if (jointProbe || promotionProbe || capturedCopy || capturedJoint) preset.copy(
                opacity = cc.joycreator.joybrush.core.brush.Param(0.55f), blend = "normal")
            else if (bakeOperator == null) preset else preset.copy(
                version = if (bakeOperator == "multiply" || bakeOperator == "screen") maxOf(9, preset.version) else preset.version,
                opacity = cc.joycreator.joybrush.core.brush.Param(0.55f), blend = bakeOperator)
            val record = cc.joycreator.joybrush.core.stroke.StrokeRecord("recorded-probe", preset.id, 19L,
                0.2f, 1f, (0 until 24).map { i -> cc.joycreator.joybrush.core.input.PenSample(
                    if (bakeOperator == null && !jointProbe && !promotionProbe && !capturedCopy && !capturedJoint) 64f + i * 4f else 232f + i * 6f,
                    if (bakeOperator == null && !jointProbe && !promotionProbe && !capturedCopy && !capturedJoint) 96f + (i % 6) * 2f else 240f + (i % 6) * 5f, i * 16.0,
                    pressure = 0.7f, tilt = 0.4f, azimuth = 0.3f) }, colorArgb = 0xFF205CA9.toInt(), seq = 1,
                capturedPhysicalSource = if (capturedCopy || capturedJoint) cc.joycreator.joybrush.core.stroke.CapturedPhysicalSource(
                    cc.joycreator.joybrush.core.stroke.CaptureDomain(
                        cc.joycreator.joybrush.core.stroke.CaptureRect(0,0,512,512),
                        listOf(cc.joycreator.joybrush.core.stroke.CaptureRect(256,256,200,40))),
                    cc.joycreator.joybrush.core.stroke.SourcePlacement(-257,513)) else null)
            canvas.runRecordedReplayProbe(probePreset, record, bake = bakeOperator != null,
                openCold = case == "recordedSavedProbe" || case == "recordedJointColdProbe", joint = jointProbe,
                promotion = promotionProbe, regional = case == "recordedPromotionRegionProbe",
                coldLoad = case == "recordedPromotionColdProbe", capturedCopy = capturedCopy,
                coldPayload = case == "recordedColdPayloadProbe", coldFailures = case == "recordedColdFailureProbe",
                capturedJoint = capturedJoint, watercolourEdits = watercolourEdits,
                watercolourOrdinary = watercolourOrdinary) { result ->
                val report = org.json.JSONObject(result).put("brush", preset.id).put("case", case).put("run", run)
                if (!watercolourOrdinary) report
                    .put("fixtureOpacity", probePreset.opacity.base)
                    .put("fixtureBrushVersion", probePreset.version)
                    .put("fixtureBrushSize", probePreset.size.base)
                    .put("fixtureWatercolourSize", probePreset.watercolour?.size ?: org.json.JSONObject.NULL)
                report.put("proofScope", if (watercolourOrdinary) "Ordinary NativeLines Add/Replace, independent integer source-pixel translation, tint and Undo/redo; derived frozen grain/paperCut0 fixture. No selected lift/cache, physical fade, View save/recovery or owner-preset quality claim"
                        else if (watercolourEdits) "NativeLines add/lift/tint, actual coverage-cache counters, canonical RGBA8 equality and undo/redo; disposable engine, no View/Select/save/recovery/quality acceptance"
                        else if (capturedJoint) "Bound captured source Scene, native siblings, exact integer movement and public joint undo/redo; debug throwaway only, no live View/Select/save/recovery or quality claim"
                        else if (capturedCopy) "Disposable complete recipe pooling then captured-domain integer texel copy; no source Scene, live capture, Select, save/recovery, quality or performance claim"
                        else if (promotionProbe) "Actual native-base transfer, physical display and raw/legacy history; no live capture, Select, save/recovery, quality or performance claim"
                        else if (jointProbe) "Actual joint engine root/public undo/redo and canonical GPU sources/looks; no live recording, Select, save, quality or performance claim"
                        else if (bakeOperator == null) "Android replay adapter and ordered arithmetic; no live wiring or physical quality claim"
                        else "GPU source bake and public undo/redo on throwaway engine; no live display, save or physical quality claim")
                logRecordedReport(report, preset.id, case, run)
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
                    if (spec.fullTail) waitForFullTail(preset, case, run, samples.size)
                    else ui.postDelayed({ finishStats(preset, case, run, samples.size, TailObservation(false, null)) }, TAIL_MS)
                })
            }
        }, 300)
    }

    private data class TailObservation(val enabled: Boolean, val observedIdleAfterUpMs: Long?)

    /** Polls existing idle telemetry only; never settles or cancels the watercolour fade. */
    private fun waitForFullTail(preset: BrushPreset, case: String, run: Int, samples: Int) {
        val upAtMs = android.os.SystemClock.uptimeMillis()
        val deadline = upAtMs + FULL_TAIL_TIMEOUT_MS
        var consecutiveIdle = 0
        var phase = "idle_observation"
        var terminal = false // Poll callbacks and the watchdog are serialized on the UI thread.
        lateinit var deadlineWatchdog: Runnable

        fun stopWithoutSummary() {
            // The GL queue may be stalled too. Ask it to stop recording, but do not wait or invent metrics.
            canvas.finishFrameStats { }
            jobs.clear()
            onDone()
        }

        fun fail(reason: String, errorClass: String? = null) {
            if (terminal) return
            terminal = true
            ui.removeCallbacks(deadlineWatchdog)
            val errorField = errorClass?.let { ",\"errorClass\":\"$it\"" } ?: ""
            val scope = when {
                reason == "full_tail_telemetry_error" -> "recorded-down-through-telemetry-error"
                phase == "idle_observation" -> "recorded-down-through-timeout-no-idle-confirmation"
                else -> "idle-confirmed-but-frame-stats-snapshot-did-not-complete"
            }
            Log.e(TAG, "{\"error\":\"$reason\",\"phase\":\"$phase\"$errorField," +
                "\"measurementScope\":\"$scope\",\"tailPollIntervalMs\":$FULL_TAIL_POLL_MS," +
                "\"tailTimeoutMs\":$FULL_TAIL_TIMEOUT_MS,\"tailIdleConfirmations\":$FULL_TAIL_IDLE_CONFIRMATIONS," +
                "\"editableLines\":${canvas.editableLines}}")
            stopWithoutSummary()
        }

        deadlineWatchdog = Runnable { fail("full_tail_deadline") }
        ui.postAtTime(deadlineWatchdog, deadline)

        fun poll() {
            if (terminal || phase != "idle_observation") return
            canvas.benchState(readPixels = false) { state, error ->
                if (terminal || phase != "idle_observation") return@benchState
                if (error != null || state == null) {
                    fail("full_tail_telemetry_error", error?.javaClass?.simpleName ?: "missing_state")
                    return@benchState
                }
                consecutiveIdle = if (state.idle && !canvas.strokeInProgress) consecutiveIdle + 1 else 0
                val now = android.os.SystemClock.uptimeMillis()
                when {
                    consecutiveIdle >= FULL_TAIL_IDLE_CONFIRMATIONS -> {
                        phase = "stats_snapshot"
                        // Replace the idle-observation watchdog with a snapshot watchdog at the same absolute deadline.
                        ui.removeCallbacks(deadlineWatchdog)
                        ui.postAtTime(deadlineWatchdog, deadline)
                        canvas.finishFrameStats { summary ->
                            if (terminal) return@finishFrameStats
                            terminal = true
                            ui.removeCallbacks(deadlineWatchdog)
                            report(preset, case, run, samples, summary, TailObservation(true, now - upAtMs))
                            ui.postDelayed({ next() }, spec.settleMs)
                        }
                    }
                    now >= deadline -> fail("full_tail_deadline")
                    else -> ui.postDelayed({ poll() }, FULL_TAIL_POLL_MS)
                }
            }
        }
        ui.postDelayed({ poll() }, FULL_TAIL_POLL_MS)
    }

    private fun finishStats(preset: BrushPreset, case: String, run: Int, samples: Int, tail: TailObservation) {
        canvas.finishFrameStats { s ->
            report(preset, case, run, samples, s, tail)
            ui.postDelayed({ next() }, spec.settleMs)
        }
    }

    private fun runRecordedCaptureCase(preset: BrushPreset, case: String, run: Int) {
        val terminal = java.util.concurrent.atomic.AtomicBoolean(false)
        val priorObserver = canvas.onDiagnosticStrokeCapture
        val priorPreset = canvas.preset
        val priorColor = canvas.colorArgb
        val priorBrush = canvas.brush
        val diagnosticBrush = priorBrush.copy(erase = false)
        val diagnosticColor = spec.colors.getOrNull(spec.cases.indexOf(case)) ?: 0xFF356DAB.toInt()
        var observer: ((JbCanvasView.DiagnosticStrokeCaptureResult) -> Unit)? = null
        var latched: JbCanvasView.DiagnosticStrokeCaptureResult? = null
        var captureCallbacks = 0
        var upDispatched = false
        var phase = "admission"
        fun restoreObserver() {
            if (canvas.onDiagnosticStrokeCapture === observer) canvas.onDiagnosticStrokeCapture = priorObserver
        }
        fun restoreBrushUi() {
            restoreObserver()
            if (canvas.preset === preset) canvas.preset = priorPreset
            if (canvas.colorArgb == diagnosticColor) canvas.colorArgb = priorColor
            if (canvas.brush == diagnosticBrush) canvas.brush = priorBrush
        }
        lateinit var timeout: Runnable
        fun finish(report: org.json.JSONObject, abort: Boolean = false) {
            if (!terminal.compareAndSet(false, true)) return
            ui.removeCallbacks(timeout)
            restoreBrushUi()
            report.put("brush", preset.id).put("case", case).put("run", run)
                .put("motionEventFixtureSampleCount", 24).put("motionEventFixtureDurationMs", 384)
                .put("motionEventFixturePressure", 0.7).put("motionEventFixtureTilt", 0.4)
                .put("motionEventFixtureOrientation", 0.3).put("captureCallbackCount", captureCallbacks)
                .put("upDispatchCompleted", upDispatched)
                .put("proofScope", "Synthetic MotionEvent dispatch through actual live raw capture and legacy GPU commit; exact captured replay parity only, no recording/Select/save activation")
            logRecordedReport(report, preset.id, case, run)
            if (abort) {
                // A timed-out GL operation might already be executing. Stop this bench sequence so
                // another synthetic stroke is not started while its eventual cleanup is pending.
                jobs.clear()
                Log.i(TAG, "done (inconclusive captured parity timeout; remaining jobs aborted)")
                onDone()
            } else ui.postDelayed({ next() }, spec.settleMs)
        }
        fun inconclusive(reason: String, abort: Boolean = false) {
            finish(org.json.JSONObject().put("probe", "recordedCaptureParity").put("passed", false)
                .put("inconclusive", true).put("phase", phase).put("failure", reason), abort)
        }
        timeout = Runnable { inconclusive("Captured parity bench timed out while waiting for $phase", abort = true) }
        ui.postDelayed(timeout, 30_000L)
        try {
            // An ordinary clear is queued first. Admission subsequently checks the actual EMPTY
            // physical target on GL before any DOWN; a fixed delay alone never grants admission.
            canvas.preset = preset
            canvas.brush = diagnosticBrush
            canvas.colorArgb = diagnosticColor
            canvas.clearCanvas()
            canvas.prepareRecordedCaptureProbe { ticket, reason ->
                try {
                if (!terminal.get()) {
                    if (ticket == null) {
                        inconclusive(reason ?: "No actual empty-plane admission", abort = reason?.contains("timed out") == true)
                    } else {
                        phase = "actual MotionEvent DOWN/MOVE/UP capture"
                        observer = { result ->
                            if (!terminal.get()) {
                                captureCallbacks++
                                latched = if (captureCallbacks == 1) result else
                                    JbCanvasView.DiagnosticStrokeCaptureResult.Refused("Diagnostic contact delivered multiple results")
                            }
                        }
                        canvas.onDiagnosticStrokeCapture = observer
                        val cx = canvas.width * 0.5f
                        val cy = canvas.height * 0.5f
                        val samples = (0 until 24).map { i ->
                            val t = i / 23f
                            StrokeReplay.Sample(cx + (t - 0.5f) * 92f, cy + (t - 0.5f) * 12f,
                                0.7f, 0.4f, 0.3f, i * (384.0 / 23.0))
                        }
                        StrokeReplay.play(canvas, samples, null, onDown = {}, onUp = {
                            upDispatched = true
                            // Completion result is latched by the contact's original observer during
                            // dispatch of UP. Restore it only AFTER that dispatch, then queue GL parity
                            // behind endStroke. No assertion mistakes a raw UI callback for GPU truth.
                            restoreBrushUi()
                            if (!terminal.get()) when (val result = latched) {
                                is JbCanvasView.DiagnosticStrokeCaptureResult.Captured -> {
                                    phase = "queued actual legacy GPU commit/settlement/parity"
                                    canvas.runRecordedCaptureProbe(ticket, result) { json ->
                                        if (!terminal.get()) {
                                            val report = try { org.json.JSONObject(json) } catch (e: Exception) {
                                                org.json.JSONObject().put("passed", false).put("inconclusive", true)
                                                    .put("failure", "Captured parity returned invalid JSON: ${e.message}")
                                            }
                                            finish(report, abort = report.optBoolean("callbackTimeout", false))
                                        }
                                    }
                                }
                                is JbCanvasView.DiagnosticStrokeCaptureResult.Refused -> inconclusive(result.reason)
                                null -> inconclusive("Actual UP produced no latched captured record; no parity/activation claimed")
                            }
                        })
                    }
                }
                } catch (e: Exception) {
                    inconclusive("Captured parity admission callback ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            inconclusive("Captured parity bench ${e.javaClass.simpleName}: ${e.message}")
        }
    }


    /** Android truncates large single log messages. Preserve the exact UTF8 report across chunks. */
    private fun logRecordedReport(report: org.json.JSONObject, brush: String, case: String, run: Int) {
        val json = report.toString()
        val bytes = json.toByteArray(Charsets.UTF_8)
        if (bytes.size <= 3000) { Log.i(TAG, json); return }
        val parts = (bytes.size + 999) / 1000
        repeat(parts) { part ->
            val data = bytes.copyOfRange(part * 1000, minOf(bytes.size, (part + 1) * 1000))
            Log.i(TAG, org.json.JSONObject().put("recordedProbeChunk", true).put("brush", brush)
                .put("case", case).put("run", run).put("part", part).put("parts", parts)
                .put("dataBase64", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)).toString())
        }
    }

    private fun report(p: BrushPreset, case: String, run: Int, samples: Int, s: FrameStats.Summary,
                       tail: TailObservation = TailObservation(false, null)) {
        fun f(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
        fun pct(x: FrameStats.Pct?) = if (x == null) "null" else "{\"p50\":${f(x.p50)},\"p95\":${f(x.p95)},\"max\":${f(x.max)},\"mean\":${f(x.mean)}}"
        Log.i(TAG, "{\"legacy\":${spec.legacy},\"brush\":\"${p.id}\",\"case\":\"$case\",\"run\":$run,\"samples\":$samples," +
            "\"frames\":${s.frames},\"strokeFrames\":${s.strokeFrames},\"firstFrameMs\":${f(s.firstFrameMs)}," +
            "\"busyMs\":${pct(s.busy)},\"workMs\":${pct(s.work)},\"mediaMs\":${pct(s.media)},\"displayMs\":${pct(s.display)}," +
            "\"finishMs\":${pct(s.finish)},\"simMs\":${pct(s.sim)},\"flushMs\":${pct(s.flush)},\"gapMs\":${pct(s.gap)},\"latencyMs\":${pct(s.latency)}," +
            "\"gapsOver17\":${s.over17},\"gapsOver34\":${s.over34},\"mediaFrames\":${s.mediaFrames}," +
            "\"windowMoves\":${s.mediaWindowMoves},\"windowLoads\":${s.mediaWindowLoads}," +
            "\"editableLines\":${canvas.editableLines},\"fullTail\":${tail.enabled}," +
            "\"tailPollIntervalMs\":${if (tail.enabled) FULL_TAIL_POLL_MS else "null"}," +
            "\"tailTimeoutMs\":${if (tail.enabled) FULL_TAIL_TIMEOUT_MS else "null"}," +
            "\"tailIdleConfirmations\":${if (tail.enabled) FULL_TAIL_IDLE_CONFIRMATIONS else "null"}," +
            "\"observedIdleAfterUpMs\":${tail.observedIdleAfterUpMs ?: "null"}," +
            "\"tailTimedOut\":false," +
            "\"measurementScope\":\"${if (tail.enabled) "recorded-down-through-two-idle-confirmations" else "stroke-plus-fixed-250ms-tail"}\"," +
            "\"fullSpanFrames\":${s.fullSpanFrames},\"fullSpanBusyMs\":${pct(s.fullSpanBusy)}," +
            "\"finalRecordedFrameEndAfterUpMs\":${f(s.finalRecordedFrameEndAfterUpMs)}," +
            "\"phasesMs\":{" + s.phases.entries.joinToString(",") { (k, v) -> "\"$k\":${pct(v)}" } + "}," +
            "\"memory\":${s.memory ?: "null"},\"device\":${deviceMemory()}}")
    }

    /** The phone's memory as Android reports it (bench only): what a bigger media atlas could use. */
    private fun deviceMemory(): String {
        val am = canvas.context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return "{\"availMem\":${mi.availMem},\"totalMem\":${mi.totalMem},\"threshold\":${mi.threshold},\"lowMemory\":${mi.lowMemory}," +
            "\"memoryClassMb\":${am.memoryClass},\"largeMemoryClassMb\":${am.largeMemoryClass}," +
            "\"nativeHeapAllocated\":${android.os.Debug.getNativeHeapAllocatedSize()}}"
    }

    companion object {
        const val TAG = "JoyBrushPerf"
        private const val TAIL_MS = 250L
        private const val SETTLE_MS = 800L
        private const val FULL_TAIL_POLL_MS = 100L
        private const val FULL_TAIL_TIMEOUT_MS = 5_000L
        private const val FULL_TAIL_IDLE_CONFIRMATIONS = 2

        /** The bench request in [intent], or null: debug builds only, and only when asked for. */
        fun spec(intent: Intent?, info: ApplicationInfo): Spec? {
            if (intent == null || info.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
            val cases = intent.getStringExtra("jb_bench") ?: return null
            fun list(s: String?) = s?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            return Spec(list(cases), list(intent.getStringExtra("jb_bench_brushes")).ifEmpty { listOf(if ("matrix" in list(cases)) "all" else "ink") },
                intent.getIntExtra("jb_bench_runs", 3).coerceIn(1, 20), intent.getBooleanExtra("jb_bench_clear", true),
                intent.getBooleanExtra("jb_media_legacy", false),
                colors = list(intent.getStringExtra("jb_bench_colors")).map { android.graphics.Color.parseColor(it) },
                slope = intent.getStringExtra("jb_slope")?.split(",")?.mapNotNull { it.trim().toFloatOrNull() }?.takeIf { it.size == 2 }?.toFloatArray(),
                wetness = intent.getIntExtra("jb_wetness", -1).takeIf { it >= 0 },
                wetSubsteps = intent.getIntExtra("jb_wet_substeps", -1).takeIf { it in 1..16 },
                holdMs = intent.getLongExtra("jb_hold_ms", 0L), settleMs = intent.getLongExtra("jb_settle_ms", 800L),
                matrixBoards = list(intent.getStringExtra("jb_matrix_boards")), matrixFrom = intent.getIntExtra("jb_matrix_from", 0),
                matrixTo = intent.getIntExtra("jb_matrix_to", Int.MAX_VALUE), water = intent.getStringExtra("jb_water"),
                atlas = if (intent.hasExtra("jb_media_atlas")) intent.getBooleanExtra("jb_media_atlas", false) else null,
                pasteSpacing = intent.getStringExtra("jb_paste_spacing")?.toDoubleOrNull(),
                coarseWater = if (intent.hasExtra("jb_coarse_water")) intent.getBooleanExtra("jb_coarse_water", true) else null,
                atlasRows = if (intent.hasExtra("jb_atlas_rows")) intent.getIntExtra("jb_atlas_rows", 0) else null,
                waterJobs = if (intent.hasExtra("jb_water_jobs")) intent.getBooleanExtra("jb_water_jobs", true) else null,
                mediaBudgetMb = intent.getIntExtra("jb_media_budget_mb", -1).takeIf { it > 0 },
                dropDry = if (intent.hasExtra("jb_drop_dry")) intent.getBooleanExtra("jb_drop_dry", true) else null,
                startMs = intent.getLongExtra("jb_start_ms", 1500L).coerceIn(0L, 60_000L),
                fullTail = intent.getBooleanExtra("jb_full_tail", false),
                editableLines = if (intent.hasExtra("jb_editable_lines")) intent.getBooleanExtra("jb_editable_lines", false) else null,
                selectRunId = intent.getStringExtra("jb_select_run_id").orEmpty())
        }
    }
}

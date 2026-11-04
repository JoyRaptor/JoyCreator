package cc.joycreator.joybrush.android.bench

import android.os.Handler
import android.os.Looper
import cc.joycreator.joybrush.android.WorkingSaveBarrier
import cc.joycreator.joybrush.android.WorkingSaveHandoff
import cc.joycreator.joybrush.androidkit.io.JbArchive
import cc.joycreator.joybrush.androidkit.io.JbContents
import cc.joycreator.joybrush.androidkit.io.TILE_BYTES
import cc.joycreator.joybrush.core.doc.DocOps
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Debug-only companion for a device check. Scratch data is confined to a fresh cache subdirectory. */
object WorkingSaveProbe {
    fun run(cacheDir: File, ready: (String) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "run must start on the UI thread" }
        val main = Handler(Looper.getMainLooper())
        val io = Executors.newSingleThreadExecutor()
        val dir = File(cacheDir, "working-save-probe-${UUID.randomUUID()}")
        val delivered = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        fun finish(problem: String?) {
            if (!finished.compareAndSet(false, true)) return
            io.execute {
                var finalProblem = problem
                val cleaned = try { !dir.exists() || dir.deleteRecursively() }
                    catch (e: Throwable) { finalProblem = finalProblem ?: (e.message ?: "scratch cleanup failed"); false }
                if (!cleaned) finalProblem = finalProblem ?: "scratch cleanup failed"
                io.shutdown()
                val report = JSONObject().put("probe", "workingSaveHandoff")
                    .put("passed", finalProblem == null).put("scratchCleaned", cleaned)
                    .put("fullRgba8Comparisons", if (finalProblem == null) 2 else 0)
                    .put("preservedPixelMarkers", if (finalProblem == null) 3 else 0)
                    .put("coverage", JSONArray().put("delayed snapshot; archive write finishes before reopen read")
                        .put("actual JbArchive pixel round-trip preserves outgoing and reopened edits")
                        .put("failed handoff blocks stale reopen; late completion is idempotent")
                        .put("cancelled waiter is not called").put("cache-only unique scratch directory"))
                    .put("limits", JSONArray().put("does not exercise Activity recreation, Lifecycle callbacks, GL, or rendered canvas pixels")
                        .put("does not measure device performance"))
                if (finalProblem != null) report.put("error", finalProblem)
                main.post { if (delivered.compareAndSet(false, true)) ready(report.toString()) }
            }
        }
        try {
            check(dir.mkdirs()) { "could not create probe cache directory" }
            val doc = DocOps.newDocument("working-save-probe", "Handoff probe", 4, 4) { UUID.randomUUID().toString() }
            val layer = doc.layers.single()
            val cel = layer.cels.single()
            val drawing = JbContents(doc.copy(layers = listOf(layer.copy(cels = listOf(cel.copy(tiles = listOf("0_0")))))),
                mapOf(Triple(layer.id, cel.id, "0_0") to ByteArray(TILE_BYTES)), emptyMap())
            fun mark(source: JbContents, offset: Int, value: Int): JbContents {
                val pixels = source.tiles.values.single().clone()
                pixels[offset] = value.toByte(); pixels[offset + 1] = (value / 2).toByte()
                pixels[offset + 2] = (value / 3).toByte(); pixels[offset + 3] = 255.toByte()
                return source.copy(tiles = mapOf(source.tiles.keys.single() to pixels))
            }
            fun requireMark(contents: JbContents, offset: Int, value: Int) {
                val p = contents.tiles.values.single()
                check(p[offset].toInt() and 255 == value && p[offset + 1].toInt() and 255 == value / 2 &&
                    p[offset + 2].toInt() and 255 == value / 3 && p[offset + 3].toInt() and 255 == 255) {
                    "archive pixel marker at $offset did not survive"
                }
            }
            val initial = mark(drawing, 0, 60)
            val outgoing = mark(initial, 4, 90)
            val path = File(dir, "working.joybrush").absolutePath
            val barrier = WorkingSaveBarrier()
            val handoff = WorkingSaveHandoff(barrier)
            val saveTicket = barrier.begin(path)
            handoff.requested(path)
            val writeFinished = AtomicBoolean(false)
            barrier.whenSettled(path) { problem ->
                if (problem != null) { finish("outgoing handoff failed: $problem"); return@whenSettled }
                if (!writeFinished.get()) { finish("reopen became ready before the archive write finished"); return@whenSettled }
                io.execute {
                    try {
                        val restored = JbArchive.open(File(path))
                        requireMark(restored, 0, 60)
                        check(restored.tiles.values.single().contentEquals(outgoing.tiles.values.single())) { "outgoing RGBA8 bytes changed" }
                        val withNewEdit = mark(restored, 8, 120)
                        JbArchive.save(File(path), withNewEdit)
                        val roundTrip = JbArchive.open(File(path))
                        requireMark(roundTrip, 0, 60); requireMark(roundTrip, 4, 90); requireMark(roundTrip, 8, 120)
                        check(roundTrip.tiles.values.single().contentEquals(withNewEdit.tiles.values.single())) { "reopened RGBA8 bytes changed" }
                        val stale = File(dir, "stale.joybrush")
                        JbArchive.save(stale, drawing)
                        main.post {
                            try {
                                val failBarrier = WorkingSaveBarrier()
                                val failHandoff = WorkingSaveHandoff(failBarrier)
                                val failPath = stale.absolutePath
                                val snapshotTicket = failBarrier.begin(failPath)
                                failHandoff.requested(failPath)
                                var calls = 0
                                var staleReadStarted = false
                                failBarrier.whenSettled(failPath) { failure ->
                                    calls++
                                    check(failure == "simulated outgoing write failure")
                                    staleReadStarted = failure == null
                                    // No read is issued for this existing, deliberately stale archive.
                                }
                                snapshotTicket.complete("simulated outgoing write failure")
                                snapshotTicket.complete(null) // a late callback cannot clear failure
                                failHandoff.queueAdvanced("simulated outgoing write failure", drained = true)
                                check(calls == 1) { "failure waiter was not completed exactly once" }
                                check(!staleReadStarted) { "failed handoff permitted a stale read" }
                                val cancelTicket = failBarrier.begin("cancelled-path")
                                var cancelledCalls = 0
                                val waiter = failBarrier.whenSettled("cancelled-path") { cancelledCalls++ }
                                waiter.close(); cancelTicket.complete(null)
                                check(cancelledCalls == 0) { "cancelled waiter ran" }
                                finish(null)
                            } catch (e: Throwable) { finish(e.message ?: e.javaClass.simpleName) }
                        }
                    } catch (e: Throwable) { finish(e.message ?: e.javaClass.simpleName) }
                }
            }
            io.execute {
                try {
                    JbArchive.save(File(path), initial)
                    main.postDelayed({
                        // Simulated GL snapshot answer; only now is the new archive write submitted.
                        io.execute {
                            try {
                                JbArchive.save(File(path), outgoing)
                                writeFinished.set(true)
                                main.post { saveTicket.complete(null); handoff.queueAdvanced(null, drained = true) }
                            } catch (e: Throwable) {
                                main.post { saveTicket.complete(e.message ?: "archive write failed"); handoff.queueAdvanced(e.message, true) }
                            }
                        }
                    }, 150L)
                } catch (e: Throwable) { main.post { saveTicket.complete(e.message); handoff.queueAdvanced(e.message, true) } }
            }
        } catch (e: Throwable) { finish(e.message ?: e.javaClass.simpleName) }
    }
}

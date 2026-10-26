package cc.joycreator.joybrush.android.bench

import android.os.Handler
import android.os.SystemClock
import java.io.File

/** Debug acceptance only: hold a state until the host has saved its run/event-specific screenshot. */
internal class SelectBenchShotGate(
    private val cacheDir: File,
    private val runId: String,
    private val ui: Handler,
    private val timeoutMs: Long = 20_000L,
) {
    private var pending: File? = null
    private var lastEvent = -1
    private var cancelled = false
    private var waiting = false

    init { require(runId.matches(Regex("[0-9a-f]{32}"))) }

    fun expect(event: Int): Boolean {
        if (cancelled || pending != null || event <= lastEvent) return false
        val file = File(cacheDir, "select-bench-$runId-$event.ack")
        if (file.exists()) return false // A marker predating its request is not capture proof.
        lastEvent = event
        pending = file
        return true
    }

    fun await(onReady: () -> Unit, onTimeout: () -> Unit) {
        if (cancelled || waiting) return
        val file = pending ?: run { onTimeout(); return }
        waiting = true
        val end = SystemClock.uptimeMillis() + timeoutMs
        fun poll() {
            if (cancelled || !waiting) return
            if (file.isFile) {
                waiting = false
                if (file.delete()) { pending = null; onReady() } else onTimeout()
            } else if (SystemClock.uptimeMillis() >= end) {
                waiting = false
                onTimeout()
            } else ui.postDelayed({ poll() }, 100L)
        }
        ui.post { poll() }
    }

    fun cancel() {
        if (cancelled) return
        cancelled = true
        waiting = false
        pending?.let { if (it.isFile) it.delete() }
        pending = null
    }
}

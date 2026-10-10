package cc.joycreator.joybrush.androidkit.bench

import android.opengl.GLES30
import android.os.SystemClock

/**
 * Per-frame timing of the canvas's renderer, for the debug stroke replay (LEAD_PLAN_20261010 step 3). Off unless
 * [recording]: then each drawn frame records its CPU time on the GL thread, the gap since the previous frame, and,
 * where the GPU offers `GL_EXT_disjoint_timer_query`, the GPU time of everything the frame submitted.
 *
 * GPU results arrive a few frames late, so queries live in a small ring and are collected when available, never by
 * waiting (a wait would serialise CPU and GPU and change what is being measured). A frame whose query reports a
 * disjoint event (frequency change, context switch) is dropped from the GPU column rather than trusted.
 *
 * GL thread only, except [mediaWindowMoves]/[mediaWindowLoads]/[mediaFrames], which the media window bumps on the same
 * thread, and [snapshot], which the UI thread may read once recording has stopped and the GL thread has collected.
 */
class FrameStats {
    @Volatile var recording = false
        private set

    private val cpuMs = ArrayList<Double>()
    private val gapMs = ArrayList<Double>()
    private val gpuMs = ArrayList<Double>()
    private var lastFrameStartNs = 0L
    private var frameStartNs = 0L

    var mediaWindowMoves = 0
    var mediaWindowLoads = 0
    var mediaFrames = 0

    // ---- GPU timer queries ----
    private var gpuChecked = false
    private var gpuTimer = false
    private val ring = IntArray(RING)
    private val pending = BooleanArray(RING)
    private var head = 0
    private var open = -1
    private val scratch = IntArray(1)

    /** UI thread (via the GL queue is not needed: the flags are volatile and read at the next frame start). */
    fun start() {
        cpuMs.clear(); gapMs.clear(); gpuMs.clear()
        lastFrameStartNs = 0L
        mediaWindowMoves = 0; mediaWindowLoads = 0; mediaFrames = 0
        recording = true
    }

    fun stop() { recording = false }

    /** First thing in onDrawFrame. */
    fun frameBegin() {
        if (!recording) return
        if (!gpuChecked) {
            gpuChecked = true
            val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
            gpuTimer = ext.contains("GL_EXT_disjoint_timer_query")
            if (gpuTimer) GLES30.glGenQueries(RING, ring, 0)
        }
        frameStartNs = SystemClock.elapsedRealtimeNanos()
        if (lastFrameStartNs != 0L) gapMs += (frameStartNs - lastFrameStartNs) / 1e6
        lastFrameStartNs = frameStartNs
        if (gpuTimer) {
            collect()
            if (!pending[head]) {
                GLES30.glGetIntegerv(GL_GPU_DISJOINT_EXT, scratch, 0)   // clears the disjoint flag
                GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, ring[head])
                open = head
            }
        }
    }

    /** Last thing in onDrawFrame (also on its exception paths: a frame that threw still cost what it cost). */
    fun frameEnd() {
        if (frameStartNs == 0L) return
        cpuMs += (SystemClock.elapsedRealtimeNanos() - frameStartNs) / 1e6
        frameStartNs = 0L
        if (open >= 0) {
            GLES30.glEndQuery(GL_TIME_ELAPSED_EXT)
            pending[open] = true
            head = (head + 1) % RING
            open = -1
        }
    }

    /** Reads every finished query in the ring without waiting. */
    fun collect() {
        if (!gpuTimer) return
        for (i in 0 until RING) {
            if (!pending[i]) continue
            GLES30.glGetQueryObjectuiv(ring[i], GLES30.GL_QUERY_RESULT_AVAILABLE, scratch, 0)
            if (scratch[0] == 0) continue
            GLES30.glGetQueryObjectuiv(ring[i], GLES30.GL_QUERY_RESULT, scratch, 0)
            val ns = scratch[0].toLong() and 0xFFFFFFFFL
            pending[i] = false
            GLES30.glGetIntegerv(GL_GPU_DISJOINT_EXT, scratch, 0)
            if (scratch[0] == 0) gpuMs += ns / 1e6
        }
    }

    data class Summary(
        val frames: Int, val cpu: Pct, val gap: Pct, val gpu: Pct?, val gpuFrames: Int,
        val over8: Int, val over16: Int, val mediaWindowMoves: Int, val mediaWindowLoads: Int, val mediaFrames: Int,
    )
    data class Pct(val p50: Double, val p95: Double, val max: Double, val mean: Double)

    fun snapshot(): Summary = Summary(
        frames = cpuMs.size, cpu = pct(cpuMs), gap = pct(gapMs), gpu = if (gpuMs.isEmpty()) null else pct(gpuMs), gpuFrames = gpuMs.size,
        over8 = gapMs.count { it > 8.5 }, over16 = gapMs.count { it > 17.0 },
        mediaWindowMoves = mediaWindowMoves, mediaWindowLoads = mediaWindowLoads, mediaFrames = mediaFrames,
    )

    companion object {
        private const val RING = 8
        const val GL_TIME_ELAPSED_EXT = 0x88BF
        const val GL_GPU_DISJOINT_EXT = 0x8FBB

        fun pct(v: List<Double>): Pct {
            if (v.isEmpty()) return Pct(0.0, 0.0, 0.0, 0.0)
            val s = v.sorted()
            fun at(q: Double) = s[((s.size - 1) * q).toInt().coerceIn(0, s.size - 1)]
            return Pct(at(0.5), at(0.95), s.last(), s.average())
        }
    }
}

package cc.joycreator.joybrush.androidkit.bench

import android.opengl.GLES30

/**
 * Per-frame timing of the canvas's renderer, for the debug stroke replay (LEAD_PLAN_20261010 step 3). Off unless
 * [recording]. Each drawn frame records, on the GL thread:
 *  - its phases: queued GL work (dabs, commits), the media engine, the display draw, and a `glFinish` (so the frame's
 *    GPU work is inside the measurement: "busy" = the whole cost of the frame, CPU and GPU, serialised). Timer queries
 *    were tried first and returned impossible values on the Note 20's Adreno; finishing is honest, if pessimistic.
 *  - when it started, so gaps between frames and the delay from each pen report to the frame that drew it can be
 *    measured inside the stroke only (pen-down to pen-up), never across the idle time around it.
 *
 * GL thread only, except [noteInput]/[noteDown]/[noteUp] (UI thread, synchronised) and [snapshot] (after [stop]).
 */
class FrameStats {
    @Volatile var recording = false
        private set

    private val starts = ArrayList<Long>()       // frame start, uptime ns
    /** GPU-finished frame end, uptime ns; kept only for an active recording. */
    private val ends = ArrayList<Long>()
    private val busy = ArrayList<Double>()
    private val work = ArrayList<Double>()
    private val media = ArrayList<Double>()
    private val display = ArrayList<Double>()
    private val finish = ArrayList<Double>()
    private val sim = ArrayList<Double>()
    private val flush = ArrayList<Double>()
    /** Media phase time inside the current frame (bench only; the media canvas adds to these with glFinish brackets). */
    var simAcc = 0.0
    var flushAcc = 0.0
    /** Named media phases (MediaPhases: each mediaTrace section, glFinish brackets), summed per frame; one list per name. */
    private val phaseAcc = LinkedHashMap<String, Double>()
    private val phases = LinkedHashMap<String, ArrayList<Double>>()
    /** What the media engine's textures cost (JSON, MediaLayerEngine.memoryReport), written once per recording. */
    @Volatile var memory: String? = null

    /** GL thread: [ms] more of phase [name] in the frame in progress. */
    fun phase(name: String, ms: Double) { if (recording) phaseAcc[name] = (phaseAcc[name] ?: 0.0) + ms }
    private val inputs = ArrayList<Long>()       // pen reports dispatched, uptime ns
    private var downNs = 0L
    private var upNs = 0L
    private var t0 = 0L
    private var t1 = 0L
    private var t2 = 0L
    private var t3 = 0L

    var mediaWindowMoves = 0
    var mediaWindowLoads = 0
    var mediaFrames = 0

    /** GL thread. */
    fun start() {
        starts.clear(); ends.clear(); busy.clear(); work.clear(); media.clear(); display.clear(); finish.clear(); sim.clear(); flush.clear(); simAcc = 0.0; flushAcc = 0.0
        phaseAcc.clear(); phases.clear(); memory = null
        synchronized(inputs) { inputs.clear(); downNs = 0L; upNs = 0L }
        mediaWindowMoves = 0; mediaWindowLoads = 0; mediaFrames = 0
        recording = true
    }

    fun stop() { recording = false }

    /** UI thread: a pen report was just dispatched. */
    fun noteInput() { if (recording) synchronized(inputs) { inputs += System.nanoTime(); if (downNs == 0L) downNs = inputs.last() } }
    fun noteUp() { synchronized(inputs) { upNs = System.nanoTime() } }

    fun frameBegin() { if (recording) { t0 = System.nanoTime(); t1 = 0L; t2 = 0L; t3 = 0L } }
    fun afterWork() { if (t0 != 0L) t1 = System.nanoTime() }
    fun afterMedia() { if (t0 != 0L) t2 = System.nanoTime() }
    fun afterDisplay() { if (t0 != 0L) t3 = System.nanoTime() }

    /** Last thing in onDrawFrame (also when it threw: a frame that threw still cost what it cost). */
    fun frameEnd() {
        if (t0 == 0L) return
        val end0 = System.nanoTime()
        GLES30.glFinish()
        val end = System.nanoTime()
        fun ms(a: Long, b: Long) = if (a == 0L || b == 0L) 0.0 else (b - a) / 1e6
        starts += t0
        ends += end
        busy += (end - t0) / 1e6
        work += ms(t0, t1); media += ms(t1, t2); display += ms(t2, t3); finish += (end - end0) / 1e6
        sim += simAcc; flush += flushAcc; simAcc = 0.0; flushAcc = 0.0
        // Every phase gets one value per frame (0 where it did not run), so it lines up with the frames above.
        for (name in phaseAcc.keys) if (name !in phases) phases[name] = ArrayList(List(starts.size - 1) { 0.0 })
        for ((name, list) in phases) list += phaseAcc[name] ?: 0.0
        phaseAcc.clear()
        t0 = 0L
    }

    data class Summary(
        val frames: Int, val strokeFrames: Int, val busy: Pct, val work: Pct, val media: Pct, val display: Pct, val finish: Pct,
        val sim: Pct, val flush: Pct,
        val gap: Pct, val latency: Pct, val firstFrameMs: Double, val over17: Int, val over34: Int,
        val mediaWindowMoves: Int, val mediaWindowLoads: Int, val mediaFrames: Int,
        /** Per media phase over the stroke's frames (MediaPhases); empty unless a media stroke ran. */
        val phases: Map<String, Pct> = emptyMap(),
        val memory: String? = null,
        /** Frames from the first frame after down through the last recorded frame (including an opt-in fade tail). */
        val fullSpanFrames: Int = 0,
        val fullSpanBusy: Pct = Pct(0.0, 0.0, 0.0, 0.0),
        /** End of the last recorded frame, including its glFinish, relative to UP; this is not the exact fade commit time. */
        val finalRecordedFrameEndAfterUpMs: Double = -1.0,
    )
    data class Pct(val p50: Double, val p95: Double, val max: Double, val mean: Double)

    data class FullSpan(val frameIndices: List<Int>, val busy: Pct, val finalRecordedFrameEndAfterUpMs: Double)

    fun snapshot(): Summary {
        val (down, up, ins) = synchronized(inputs) { Triple(downNs, upNs, inputs.toList()) }
        // Frames that drew the stroke: from the first frame after pen-down to the first after pen-up.
        val inStroke = starts.indices.filter { starts[it] >= down && (up == 0L || starts[it] <= up + 20_000_000L) }
        val gaps = inStroke.zipWithNext { a, b -> (starts[b] - starts[a]) / 1e6 }
        // Each report waits for the next frame to start (then that frame's busy time and the display's own latency).
        val lat = ins.mapNotNull { t -> starts.firstOrNull { it >= t }?.let { (it - t) / 1e6 } }
        val first = starts.firstOrNull { it >= down }?.let { (it - down) / 1e6 } ?: -1.0
        fun sel(v: List<Double>) = pct(inStroke.map { v[it] })
        val fullSpan = fullSpan(starts, ends, busy, down, up)
        return Summary(starts.size, inStroke.size, sel(busy), sel(work), sel(media), sel(display), sel(finish), sel(sim), sel(flush),
            pct(gaps), pct(lat), first, gaps.count { it > 17.5 }, gaps.count { it > 34.0 },
            mediaWindowMoves, mediaWindowLoads, mediaFrames, phases.mapValues { (_, v) -> sel(v) }, memory,
            fullSpan.frameIndices.size, fullSpan.busy, fullSpan.finalRecordedFrameEndAfterUpMs)
    }

    companion object {
        /** Pure timing fold used by snapshot and unit tests. Times are monotonic nanoseconds. */
        fun fullSpan(startsNs: List<Long>, endsNs: List<Long>, busyMs: List<Double>, downNs: Long, upNs: Long): FullSpan {
            val indices = startsNs.indices.filter { startsNs[it] >= downNs && it < endsNs.size && it < busyMs.size }
            val lastEndAfterUp = if (upNs == 0L) -1.0 else indices.lastOrNull()?.let { (endsNs[it] - upNs) / 1e6 } ?: -1.0
            return FullSpan(indices, pct(indices.map { busyMs[it] }), lastEndAfterUp)
        }

        fun pct(v: List<Double>): Pct {
            if (v.isEmpty()) return Pct(0.0, 0.0, 0.0, 0.0)
            val s = v.sorted()
            fun at(q: Double) = s[((s.size - 1) * q).toInt().coerceIn(0, s.size - 1)]
            return Pct(at(0.5), at(0.95), s.last(), s.average())
        }
    }
}

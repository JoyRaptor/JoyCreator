package cc.joycreator.joybrush.androidkit.gl.media

import android.opengl.GLES30
import cc.joycreator.joybrush.androidkit.gl.ShaderLibrary
import cc.joycreator.joybrush.core.media.DOC_PX_PER_MM
import cc.joycreator.joybrush.core.media.MediaAtlasPlacement
import cc.joycreator.joybrush.core.media.MediaSample
import cc.joycreator.joybrush.core.media.MediaSpline
import cc.joycreator.joybrush.core.media.WETNESS
import cc.joycreator.joybrush.core.media.WaterBlocks
import cc.joycreator.joybrush.core.media.WetBrushes
import cc.joycreator.joybrush.core.media.WetConstants
import cc.joycreator.joybrush.core.media.WetStroke
import cc.joycreator.joybrush.core.media.paintFromColor
import cc.joycreator.joybrush.core.paint.Tiles
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The atlas room's P2 proof on the device (ATLAS_ROOM_20261023, debug bench only): water run block by block through a
 * working atlas too small for it must be the window's water, bit for bit. A 5 x 5-tile window and an atlas engine whose
 * host ([ProbeBlockHost]) keeps at most [CAPACITY] tiles resident (a block of one tile and its ring take 9), everything
 * else parked ([MediaWaterPark]); every job runs in blocks. The same wet strokes go to both (a flat Round, then a tilted
 * Wash, each left to run); after each, every tile's state planes (p0, p1, paper, w0, w1, raw bits) are compared. GL thread.
 */
internal object MediaAtlasBlockedProbe {
    private const val SIDE = 256
    private const val WORLD = 5
    private const val CAPACITY = 15

    fun probe(shaders: ShaderLibrary, paper: MediaPaperGl): String {
        val report = JSONObject().put("probe", "atlasRoomBlocked").put("world", WORLD).put("capacity", CAPACITY)
        val win = MediaLayerEngine(shaders, atlasMode = false).also { it.init(WORLD * SIDE, WORLD * SIDE) }
        val atl = MediaLayerEngine(shaders, atlasMode = true).also { it.init(4 * SIDE, 4 * SIDE + SIDE) }   // 16 slots + the zero row
        val park = MediaWaterPark(1, WORLD * WORLD + 8)
        val host = ProbeBlockHost(atl, park, paper, CAPACITY)
        atl.setResidency(emptyMap())
        atl.blockHost = host
        val read = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
        try {
            val ppm = DOC_PX_PER_MM
            val strokes = JSONArray()
            fun samples(n: Int, from: Float, to: Float, bend: Float, phase: Double): List<MediaSample> = List(n) { i ->
                val t = i / (n - 1.0)
                val x = from + (to - from) * t; val y = from + 0.8 * (to - from) * t + bend * sin(PI * t + phase)
                MediaSample(x.toDouble(), y, 0.35 + 0.5 * abs(sin(3 * PI * t)), 0.6, 0.4 + t, i * 8.0)
            }
            for ((label, slope, brushName) in listOf(Triple("watercolour Round flat", floatArrayOf(0f, 0f), "Round"),
                    Triple("watercolour wash tilted", floatArrayOf(0.2f, 0.35f), "Wash"))) {
                val brush = WetBrushes.ALL[brushName] ?: WetBrushes.ALL.values.first()
                win.slope = slope; atl.slope = slope
                val wets = listOf(win, atl).map { WetStroke(brush, paintFromColor(doubleArrayOf(0.2, 0.45, 0.8)), ppm, 7, WETNESS[WETNESS.size / 2]) }
                val splines = wets.map { w -> MediaSpline(sink = w::add) }
                val pts = samples(60, 450f, 760f, -30f, 1.0)
                var frames = 0
                for (chunk in pts.chunked(5)) {
                    frames++
                    for (s in chunk) { splines[0].add(s); splines[1].add(s) }
                    win.wetFrame(wets[0].take(), paper, ppm, WetConstants()); win.finishWaterJob()
                    val b = wets[1].take()
                    // The frame's own tiles (where its dabs land) are here before it draws, as the atlas's admission does.
                    b.dirty?.let { d -> host.holdOutsideJob(MediaAtlasPlacement.tiles(d[0] - 64, d[1] - 64, d[2] + 64, d[3] + 64, 0)) }
                    atl.wetFrame(b, paper, ppm, WetConstants()); atl.finishWaterJob()
                }
                repeat(10) { frames++; win.wetFrame(null, paper, ppm, WetConstants()); win.finishWaterJob(); atl.wetFrame(null, paper, ppm, WetConstants()); atl.finishWaterJob() }
                strokes.put(compare(win, atl, host, read).put("stroke", label).put("frames", frames))
            }
            var bad = 0L; var changed = 0L
            for (i in 0 until strokes.length()) { bad += strokes.getJSONObject(i).getLong("pixels"); changed += strokes.getJSONObject(i).getLong("changedFromEmpty") }
            report.put("strokes", strokes).put("blockedVsWindowPixels", bad).put("nonvacuous", changed > 10_000)
                .put("blockLoads", host.loads).put("blockParks", host.parks).put("blocksRun", host.blocks)
                .put("loadMsPerTile", if (host.loads > 0) host.loadNs / 1e6 / host.loads else JSONObject.NULL)
                .put("glError", GLES30.glGetError())
                .put("pass", bad == 0L && changed > 10_000 && host.parks > 0)
        } catch (e: Throwable) {
            report.put("pass", false).put("error", e.toString()).put("at", e.stackTrace.take(8).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" })
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glDeleteFramebuffers(1, intArrayOf(read), 0)
            runCatching { atl.blockHost = null }
            runCatching { win.release() }; runCatching { atl.release() }; runCatching { park.release() }
        }
        return report.toString()
    }

    /** Every tile's state planes, window vs blocked atlas (the atlas's tile brought in alone, from the park). */
    private fun compare(win: MediaLayerEngine, atl: MediaLayerEngine, host: ProbeBlockHost, read: Int): JSONObject {
        val o = JSONObject(); var total = 0L; var changed = 0L
        val perPlane = HashMap<String, Long>()
        val wt = win.stateTextures()
        for (ty in 0 until WORLD) for (tx in 0 until WORLD) {
            val key = Tiles.key(tx, ty)
            val slot = host.holdOutsideJob(listOf(key), only = true).getValue(key)
            val (ax, ay) = atl.slotTexel(slot)
            val at = atl.stateTextures()
            for ((name, tex) in wt) {
                val a = readRect(read, tex, tx * SIDE, ty * SIDE)
                val b = readRect(read, at.getValue(name), ax, ay)
                var px = 0; var i = 0
                while (i < a.size) {
                    if (a[i] != b[i] || a[i + 1] != b[i + 1] || a[i + 2] != b[i + 2] || a[i + 3] != b[i + 3]) px++
                    if (a[i] != 0 || a[i + 1] != 0 || a[i + 2] != 0 || a[i + 3] != 0) changed++
                    i += 4
                }
                if (px > 0) perPlane[name] = (perPlane[name] ?: 0L) + px
                total += px
            }
        }
        for ((k, v) in perPlane) o.put(k, v)
        return o.put("pixels", total).put("changedFromEmpty", changed)
    }

    private fun readRect(fbo: Int, tex: Int, x: Int, y: Int): IntArray {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
        val b = ByteBuffer.allocateDirect(SIDE * SIDE * 16).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(x, y, SIDE, SIDE, GLES30.GL_RGBA, GLES30.GL_FLOAT, b)
        val ints = IntArray(SIDE * SIDE * 4); b.asIntBuffer().get(ints); return ints
    }
}

/**
 * A block host that owns every tile of one atlas engine (the blocked probe's world): at most [capacity] resident, the rest
 * parked. Resident tiles are FULL (the truth, both halves where the step does not write them all) or borrowed ring copies
 * (only what a block's passes read; let go at the step's end, never written back). A tile whose block has run this step
 * and leaves takes its current half (still read as ring) and its next half to the park; at the step's end next becomes
 * current. Tiles with brush input not yet in the water never leave (the park holds no input).
 */
internal class ProbeBlockHost(private val e: MediaLayerEngine, private val park: MediaWaterPark, private val paper: MediaPaperGl,
                              private val capacity: Int) : WaterBlockHost {
    private val slotOf = LinkedHashMap<Long, Int>(16, 0.75f, true)   // access order: least recently used first
    private val borrowed = HashSet<Long>()
    private val cur = HashMap<Long, Int>()
    private val next = HashMap<Long, Int>()
    private val done = HashSet<Long>()
    private var cells = IntArray(4)
    var loads = 0; private set
    var parks = 0; private set
    var blocks = 0; private set
    var loadNs = 0L; private set

    override fun fitsWhole(cells: IntArray, skip: Set<Long>): Boolean { this.cells = cells; return false }

    override fun enter(block: WaterBlocks.Block, k: Int) {
        blocks++
        val need = (block.own + block.ring).toSet()
        for (t in block.own) if (t in borrowed) drop(t)   // a borrowed copy is not enough to write
        val loaded = ArrayList<Long>()
        for (t in need) if (slotOf[t] == null) {
            makeRoom(need)
            if (t in block.own) loadFull(t, both = !inside(t)) else loadRing(t)
            loaded += t
        }
        e.setResidency(slotOf.toMap())
        for (t in loaded) e.bakeTile(t, paper)
    }

    override fun leave(block: WaterBlocks.Block, k: Int) { done += block.own }

    override fun stepDone(k: Int) {
        for (t in borrowed.toList()) drop(t)
        for ((t, u) in next) { cur.put(t, u)?.let(park::give) }
        next.clear(); done.clear()
        e.setResidency(slotOf.toMap())
    }

    override fun jobDone() {
        for (t in borrowed.toList()) drop(t)
        e.setResidency(slotOf.toMap())
    }

    /** Outside a job: [keys] resident as the truth (both halves); [only]: everything else leaves first. Returns their slots. */
    fun holdOutsideJob(keys: Collection<Long>, only: Boolean = false): Map<Long, Int> {
        val need = keys.toSet()
        if (only) for (t in slotOf.keys.toList()) if (t !in need) evict(t)
        val loaded = ArrayList<Long>()
        for (t in need) if (slotOf[t] == null) { makeRoom(need); loadFull(t, both = true); loaded += t }
        e.setResidency(slotOf.toMap())
        for (t in loaded) e.bakeTile(t, paper)
        return need.associateWith { slotOf.getValue(it) }
    }

    private fun inside(t: Long): Boolean {
        val x0 = Tiles.tx(t) * Tiles.SIZE; val y0 = Tiles.ty(t) * Tiles.SIZE
        return x0 >= cells[0] && y0 >= cells[1] && x0 + Tiles.SIZE <= cells[2] && y0 + Tiles.SIZE <= cells[3]
    }

    private fun makeRoom(need: Set<Long>) {
        while (slotOf.size >= capacity) {
            val victim = slotOf.keys.firstOrNull { it !in need && !e.inputPendingIn(it) }
                ?: error("the probe's atlas is too small: need ${need.map { "(${Tiles.tx(it)},${Tiles.ty(it)})" }}, resident " +
                    slotOf.keys.map { "(${Tiles.tx(it)},${Tiles.ty(it)})${if (it in need) "n" else ""}${if (e.inputPendingIn(it)) "i" else ""}" } +
                    " of $capacity; ${e.inputReport()}")
            evict(victim)
        }
    }

    private fun freeSlot(): Int { val used = slotOf.values.toSet(); return (0 until capacity).first { it !in used } }

    private fun evict(t: Long) {
        if (t in borrowed) { drop(t); return }
        val slot = slotOf.remove(t) ?: return
        cur[t] = take().also { e.parkHalf(slot, park, it, next = false) }
        if (t in done) next[t] = take().also { e.parkHalf(slot, park, it, next = true) }
        parks++
    }

    private fun drop(t: Long) { slotOf.remove(t); borrowed.remove(t) }

    private fun take(): Int = park.take() ?: error("the park is full")

    private fun loadFull(t: Long, both: Boolean) {
        val slot = freeSlot()
        slotOf[t] = slot
        val (ax, ay) = e.slotTexel(slot)
        GLES30.glFinish(); val t0 = System.nanoTime()
        e.clearSlot(ax, ay)
        cur.remove(t)?.let { u -> e.loadHalf(park, u, slot, both, ringOnly = false); park.give(u) }
        GLES30.glFinish(); loadNs += System.nanoTime() - t0; loads++
    }

    private fun loadRing(t: Long) {
        val slot = freeSlot()
        slotOf[t] = slot; borrowed += t
        val (ax, ay) = e.slotTexel(slot)
        GLES30.glFinish(); val t0 = System.nanoTime()
        e.clearSlot(ax, ay)
        cur[t]?.let { u -> e.loadHalf(park, u, slot, both = false, ringOnly = true) }
        GLES30.glFinish(); loadNs += System.nanoTime() - t0; loads++
    }
}

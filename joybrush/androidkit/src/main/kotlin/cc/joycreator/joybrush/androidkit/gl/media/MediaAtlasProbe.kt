package cc.joycreator.joybrush.androidkit.gl.media

import android.opengl.GLES30
import cc.joycreator.joybrush.androidkit.gl.ShaderLibrary
import cc.joycreator.joybrush.core.media.DOC_PX_PER_MM
import cc.joycreator.joybrush.core.media.DabBatch
import cc.joycreator.joybrush.core.media.DryStroke
import cc.joycreator.joybrush.core.media.MediaSample
import cc.joycreator.joybrush.core.media.MediaSpline
import cc.joycreator.joybrush.core.media.PasteBrushes
import cc.joycreator.joybrush.core.media.PasteStroke
import cc.joycreator.joybrush.core.media.StickMaterial
import cc.joycreator.joybrush.core.media.Sticks
import cc.joycreator.joybrush.core.media.WETNESS
import cc.joycreator.joybrush.core.media.WetBrushes
import cc.joycreator.joybrush.core.media.WetConstants
import cc.joycreator.joybrush.core.media.WetStroke
import cc.joycreator.joybrush.core.media.opaquePaint
import cc.joycreator.joybrush.core.media.paintFromColor
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Media Atlas v2 B1, the DEVICE proof (debug bench only): three [MediaLayerEngine]s on THIS GPU, side by side, through the
 * very same frames: the window engine, the atlas engine (JB_ATLAS, identity page table, one instance per slot) and the
 * atlas engine with its table off by one tile (the control that must be detected). The strokes are made by the app's
 * own builders (DryStroke, WetStroke, PasteStroke, an eraser batch) with a fixed number of samples per frame, so the
 * frames are the same in all three (a timed replay is not: frame batching would differ run to run). After each stroke the
 * full float state (every plane, raw bits) and the RGBA8 look are read back and compared, window vs atlas (0 required)
 * and window vs broken (> 0 required). The same check runs headless in tools/media_atlas_parity_check.js.
 *
 * A 768² window = 3 × 3 slots, so the middle tile has a neighbour on every side; strokes and passes stay 96 px inside it
 * (at the window's own edge the window clamps and the atlas reads zero, a deliberate difference that B2 removes).
 * GL thread. Allocates three engines (~100 MB each at this size) and releases them.
 */
internal object MediaAtlasProbe {
    private const val SIZE = 768

    fun probe(shaders: ShaderLibrary, paper: MediaPaperGl): String {
        // pasteCopy: the window with one copy-back per paste step (the path before paired steps): must be the window's bits.
        val kinds = listOf("window", "atlas", "scrambled", "broken", "pasteCopy")
        val engines = kinds.map { k ->
            MediaLayerEngine(shaders, atlasMode = k != "window" && k != "pasteCopy", atlasBroken = k == "broken").also {
                it.init(SIZE, SIZE); if (k == "pasteCopy") it.pastePairs = false
            }
        }
        // B2's free placement: the same 3 × 3 tiles, every one in a slot that is not its own (no two neighbours adjacent in
        // the atlas the way they are on the layer). Read back un-shuffled, it must be the window's bits.
        engines[2].setResidency(SCRAMBLE.withIndex().associate { (t, slot) -> cc.joycreator.joybrush.core.paint.Tiles.key(t % 3, t / 3) to slot })
        val report = JSONObject().put("probe", "mediaAtlasB1").put("size", SIZE).put("renderer", GLES30.glGetString(GLES30.GL_RENDERER))
        val read = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
        try {
            val strokes = JSONArray()
            val ppm = DOC_PX_PER_MM
            val look = MediaLook(floatArrayOf(0.95f, 0.93f, 0.88f))
            val interior = floatArrayOf(96f, 96f, SIZE - 96f, SIZE - 96f)
            fun compare(name: String, frames: Int) {
                val snaps = engines.mapIndexed { i, e -> snapshot(e, read, e.renderLook(interior, paper, look, ppm), if (i == 2) SCRAMBLE else null) }
                val o = JSONObject().put("stroke", name).put("frames", frames)
                for ((i, k) in kinds.withIndex()) if (i > 0) o.put(k, diff(snaps[0], snaps[i]))
                o.put("changedFromEmpty", snaps[0].values.sumOf { a -> a.count { it != 0 } })
                strokes.put(o)
            }
            // A path through the middle tile and across both seam lines, kept off the window's edge.
            fun samples(n: Int, from: Float, to: Float, bend: Float, phase: Double): List<MediaSample> = List(n) { i ->
                val t = i / (n - 1.0)
                val x = from + (to - from) * t; val y = from + 0.8 * (to - from) * t + bend * sin(PI * t + phase)
                MediaSample(x.toDouble(), y, 0.35 + 0.5 * abs(sin(3 * PI * t)), 0.6, 0.4 + t, i * 8.0)
            }
            // 1) pencil (HB): a fixed 6 samples per frame
            run {
                val stick = Sticks.ALL["HB"] ?: Sticks.ALL.values.first()
                val dry = engines.map { DryStroke(stick, paper.stats.toothMm, ppm) }
                val splines = dry.map { d -> MediaSpline(sink = d::add) }
                val pts = samples(90, 230f, 540f, 40f, 0.0)
                var frames = 0
                for (chunk in pts.chunked(6)) {
                    frames++
                    for ((i, e) in engines.withIndex()) {
                        for (s in chunk) splines[i].add(s)
                        val b = dry[i].take()
                        if (b.count > 0) {
                            val last = (b.count - 1) * DabBatch.FLOATS
                            e.dryFrame(b, b.dabs[last + 16].toDouble(), b.dabs[last + 17].toDouble(), paper, StickMaterial.of(stick), ppm)
                        }
                    }
                }
                compare("pencil HB", frames)
            }
            // 2) watercolour (Round), flat, then left to run; 3) a tilted wash over it
            for ((label, slope, brushName) in listOf(Triple("watercolour Round flat", floatArrayOf(0f, 0f), "Round"),
                    Triple("watercolour wash tilted", floatArrayOf(0.2f, 0.35f), "Wash"))) {
                val brush = WetBrushes.ALL[brushName] ?: WetBrushes.ALL.values.first()
                engines.forEach { it.slope = slope }
                val wets = engines.map { WetStroke(brush, paintFromColor(doubleArrayOf(0.2, 0.45, 0.8)), ppm, 7, WETNESS[WETNESS.size / 2]) }
                val splines = wets.map { w -> MediaSpline(sink = w::add) }
                val pts = samples(60, 250f, 520f, -30f, 1.0)
                var frames = 0
                for (chunk in pts.chunked(5)) {
                    frames++
                    for ((i, e) in engines.withIndex()) { for (s in chunk) splines[i].add(s); e.wetFrame(wets[i].take(), paper, ppm, WetConstants()) }
                }
                repeat(10) { frames++; for (e in engines) e.wetFrame(null, paper, ppm, WetConstants()) }
                compare(label, frames)
            }
            engines.forEach { it.slope = floatArrayOf(0f, 0f) }
            // 4) oil (Oil round) over the wash
            run {
                val brush = PasteBrushes.ALL["Oil round"] ?: PasteBrushes.ALL.values.first()
                engines.forEach { it.reloadBrush(opaquePaint(doubleArrayOf(0.7, 0.25, 0.1)), brush, 11) }
                val paste = engines.map { PasteStroke(brush, ppm, 1.0) }
                val splines = paste.map { p -> MediaSpline(sink = p::add) }
                val pts = samples(60, 520f, 240f, 25f, 2.0)
                var frames = 0
                for (chunk in pts.chunked(4)) {
                    frames++
                    for ((i, e) in engines.withIndex()) { for (s in chunk) splines[i].add(s); for (step in paste[i].take()) e.pasteStep(step, brush, paper, ppm, 11); e.pasteSettle() }
                }
                compare("oil round", frames)
            }
            // 4b) a palette knife (trowel) back across it: many steps a frame, the paired steps' strips and leftovers
            run {
                val brush = PasteBrushes.ALL["Palette knife 1"] ?: PasteBrushes.ALL.values.first()
                engines.forEach { it.reloadBrush(opaquePaint(doubleArrayOf(0.2, 0.3, 0.75)), brush, 12) }
                val paste = engines.map { PasteStroke(brush, ppm, 1.0) }
                val splines = paste.map { p -> MediaSpline(sink = p::add) }
                val pts = samples(50, 250f, 520f, -20f, 0.5)
                var frames = 0
                for (chunk in pts.chunked(5)) {
                    frames++
                    for ((i, e) in engines.withIndex()) { for (s in chunk) splines[i].add(s); for (step in paste[i].take()) e.pasteStep(step, brush, paper, ppm, 12); e.pasteSettle() }
                }
                compare("palette knife", frames)
            }
            // 5) the eraser across everything, both seams
            run {
                var frames = 0
                for (f in 0 until 8) {
                    frames++
                    val n = 6
                    val dabs = FloatArray(n * DabBatch.FLOATS)
                    var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
                    for (k in 0 until n) {
                        val a = (f * n + k) / (8.0 * n) * 2 * PI
                        val x = SIZE / 2 + 140 * cos(a); val y = SIZE / 2 + 140 * sin(a)
                        val o = k * DabBatch.FLOATS
                        dabs[o] = x.toFloat(); dabs[o + 1] = y.toFloat(); dabs[o + 2] = 18f; dabs[o + 3] = 0.5f + 0.5f * (k % 2)
                        x0 = minOf(x0, x - 18); y0 = minOf(y0, y - 18); x1 = maxOf(x1, x + 18); y1 = maxOf(y1, y + 18)
                    }
                    val batch = DabBatch(dabs, n, doubleArrayOf(x0 - 2, y0 - 2, x1 + 2, y1 + 2))
                    for (e in engines) e.eraseFrame(batch, setOf("p0", "p1", "paper", "w0", "w1"))
                }
                compare("eraser", frames)
            }
            report.put("strokes", strokes)
            var atlasBad = 0L; var brokenSeen = 0L; var vacuous = false
            for (i in 0 until strokes.length()) {
                val s = strokes.getJSONObject(i)
                atlasBad += s.getJSONObject("atlas").getLong("pixels"); brokenSeen += s.getJSONObject("broken").getLong("pixels")
                if (s.getInt("changedFromEmpty") < 1000) vacuous = true
            }
            var scrambledBad = 0L
            for (i in 0 until strokes.length()) scrambledBad += strokes.getJSONObject(i).getJSONObject("scrambled").getLong("pixels")
            var pasteBad = 0L
            for (i in 0 until strokes.length()) pasteBad += strokes.getJSONObject(i).getJSONObject("pasteCopy").getLong("pixels")
            report.put("atlasVsWindowPixels", atlasBad).put("scrambledVsWindowPixels", scrambledBad).put("pastePairsVsCopyPixels", pasteBad)
                .put("brokenDetectedPixels", brokenSeen).put("nonvacuous", !vacuous)
            report.put("glError", GLES30.glGetError())
            report.put("pass", atlasBad == 0L && scrambledBad == 0L && pasteBad == 0L && brokenSeen > 0L && !vacuous)
        } catch (e: Throwable) {
            report.put("pass", false).put("error", e.toString())
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glDeleteFramebuffers(1, intArrayOf(read), 0)
            engines.forEach { runCatching { it.release() } }
        }
        return report.toString()
    }

    /** Tile t (column t % 3, row t / 3) lives in slot SCRAMBLE[t] in the scrambled engine. */
    private val SCRAMBLE = intArrayOf(4, 7, 1, 8, 0, 5, 2, 6, 3)

    /** Every state plane as raw float bits, and the look as RGBA8 bytes widened to ints; [slots]: put scrambled tiles back. */
    private fun snapshot(e: MediaLayerEngine, fbo: Int, look: Int, slots: IntArray? = null): Map<String, IntArray> {
        val out = LinkedHashMap<String, IntArray>()
        for ((name, tex) in e.stateTextures().toSortedMap()) out[name] = unscramble(readFloatBits(fbo, tex), slots)
        out["look"] = unscramble(readBytes(fbo, look), slots)
        return out
    }

    /** Layer layout from slot layout: tile t's 256² block copied from slot [slots] [t]'s place (4 ints per texel). */
    private fun unscramble(a: IntArray, slots: IntArray?): IntArray {
        if (slots == null) return a
        val o = IntArray(a.size); val side = 256; val n = SIZE / side
        for (t in slots.indices) {
            val tx = t % n; val ty = t / n; val sx = slots[t] % n; val sy = slots[t] / n
            for (row in 0 until side) System.arraycopy(a, ((sy * side + row) * SIZE + sx * side) * 4, o, ((ty * side + row) * SIZE + tx * side) * 4, side * 4)
        }
        return o
    }

    private fun attach(fbo: Int, tex: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "probe readback target incomplete" }
    }

    private fun readFloatBits(fbo: Int, tex: Int): IntArray {
        attach(fbo, tex)
        val b = ByteBuffer.allocateDirect(SIZE * SIZE * 16).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, SIZE, SIZE, GLES30.GL_RGBA, GLES30.GL_FLOAT, b)
        val ints = IntArray(SIZE * SIZE * 4); b.asIntBuffer().get(ints); return ints
    }

    private fun readBytes(fbo: Int, tex: Int): IntArray {
        attach(fbo, tex)
        val b = ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, SIZE, SIZE, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, b)
        return IntArray(SIZE * SIZE * 4) { b.get(it).toInt() and 0xFF }
    }

    /** Pixels (4 channels) that differ in any plane, per plane, and their total. */
    private fun diff(a: Map<String, IntArray>, b: Map<String, IntArray>): JSONObject {
        val o = JSONObject(); var total = 0L
        for ((name, x) in a) {
            val y = b[name] ?: run { o.put(name, "missing"); total += x.size / 4; null } ?: continue
            var px = 0
            var i = 0
            while (i < x.size) { if (x[i] != y[i] || x[i + 1] != y[i + 1] || x[i + 2] != y[i + 2] || x[i + 3] != y[i + 3]) px++; i += 4 }
            if (px > 0) o.put(name, px)
            total += px
        }
        return o.put("pixels", total)
    }
}

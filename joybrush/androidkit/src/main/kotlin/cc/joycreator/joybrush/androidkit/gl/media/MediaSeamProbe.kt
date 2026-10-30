package cc.joycreator.joybrush.androidkit.gl.media

import android.opengl.GLES30
import cc.joycreator.joybrush.androidkit.gl.GlPaintEngine
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.media.MediaSample
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The owner's oil hairlines (2026-10-27, Note 9: a 1 px darker last row on tiles, along tile-row boundaries, baked into the
 * saved look), measured on the REAL host (debug bench only). The same oil strokes, inside the old window's reach, are
 * painted by the atlas and by the window engine on fresh probe-owned engines, as the app paints them. Then every look row
 * next to a tile boundary is compared with its neighbours: a seam is a row that stands out from the rows on both sides of
 * it by more than ordinary rows do (the baseline: the same measure on rows 64 and 192, inside the tiles).
 *
 * GL thread. The user's canvas is not touched; the process-wide atlas switch is put back.
 */
internal object MediaSeamProbe {
    private const val LAYER = "seam-probe"
    private const val FRAME_MS = 16L
    private const val SAMPLES_PER_FRAME = 5
    private const val N = 256

    /** [previews]: draws brush-drawer previews on this GL context (as holding a brush does), between strokes in a 4th run. */
    fun probe(paper: Paper, preset: BrushPreset, memory: () -> Pair<Long, Long>, previews: (() -> Unit)? = null): String {
        val report = JSONObject().put("probe", "mediaSeam").put("renderer", GLES30.glGetString(GLES30.GL_RENDERER)).put("brush", preset.id)
        val before = MediaAtlasGl.enabled
        val engines = JSONArray()
        try {
            val runs = listOf(Triple(true, false, false), Triple(false, false, false), Triple(true, true, false)) +
                if (previews != null) listOf(Triple(true, true, true)) else emptyList()
            for ((atlas, isLong, withPreviews) in runs) {
                MediaAtlasGl.enabled = atlas
                long = isLong
                val o = try { run(paper, preset, memory, if (withPreviews) previews else null) } catch (e: Throwable) {
                    JSONObject().put("error", e.toString()).put("at", e.stackTrace.take(6).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" })
                }
                engines.put(o.put("engine", (if (atlas) "atlas" else "window") + (if (isLong) " long" else "") + if (withPreviews) " +previews" else ""))
            }
            // On the owner's paper (Drawing 5, 10-27: construction pulp surface, tan look, show 0.17, bite 1): the tooth the oil reads.
            run {
                MediaAtlasGl.enabled = true
                long = true
                val owners = Paper(textureId = "construction_pulp", lookId = "construction_tan", show = 0.17f, bite = 1f, includeInExport = true)
                val o = try { run(owners, preset, memory, null) } catch (e: Throwable) {
                    JSONObject().put("error", e.toString()).put("at", e.stackTrace.take(6).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" })
                }
                engines.put(o.put("engine", "atlas long owner-paper"))
            }
            // Reopened (as a saved drawing comes back: its look only, no payload), then oil across it.
            val saved = lastTiles
            if (saved != null) {
                MediaAtlasGl.enabled = true
                long = true
                val o = try { run(paper, preset, memory, null, reopen = saved) } catch (e: Throwable) {
                    JSONObject().put("error", e.toString()).put("at", e.stackTrace.take(6).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" })
                }
                engines.put(o.put("engine", "atlas long reopened"))
            }
        } finally { MediaAtlasGl.enabled = before }
        return report.put("engines", engines).put("glError", GLES30.glGetError()).toString()
    }

    /** The looks of the last long atlas run: what a save of it would keep. */
    private var lastTiles: Map<Long, ByteArray>? = null

    private fun run(paper: Paper, preset: BrushPreset, memory: () -> Pair<Long, Long>, previews: (() -> Unit)?,
                    reopen: Map<Long, ByteArray>? = null): JSONObject {
        val engine = GlPaintEngine()
        val canvas = MediaCanvas(engine, memory)
        try {
            engine.init()
            engine.onMediaRestored = { layer, plane, keys -> canvas.restored(layer, plane, keys) }
            engine.onLookWritten = { layer, plane, keys -> canvas.lookWritten(layer, plane, keys) }
            engine.setDocumentPaper(paper)
            engine.addLayer(LAYER)
            var now = 1_000L
            val warnings = ArrayList<String>()
            engine.onMediaWarning = { warnings += it }
            fun idle() { var n = 0; while (n < 600) { now += FRAME_MS; n++; if (!canvas.frame(now)) break } }
            fun stroke(i: Int) {
                val pts = path(i)
                canvas.begin(LAYER, preset, COLOURS[i % COLOURS.size], null, pts[0].x, pts[0].y, now)?.let { throw IllegalStateException("refused: $it") }
                for (chunk in pts.chunked(SAMPLES_PER_FRAME)) { canvas.addSamples(chunk); now += FRAME_MS; canvas.frame(now) }
                canvas.end()
                previews?.invoke()
                idle()
            }
            // As the owner paints: strokes, an undo and a redo, an undo, then strokes across what is left.
            if (reopen != null) {
                for ((key, rgba) in reopen) engine.writeTile(LAYER, key, rgba)
                for (i in 3 until 6) stroke(i)
                canvas.releaseWindow()
                val tiles = engine.tileKeys(LAYER).associateWith { checkNotNull(engine.readTile(LAYER, it)) }
                return measure(tiles).put("droppedPayloads", warnings.count { "outside the media funnel" in it })
            }
            for (i in 0 until 3) stroke(i)
            canvas.settleOwed(); engine.undoStep(); idle()
            canvas.settleOwed(); engine.redoStep(); idle()
            canvas.settleOwed(); engine.undoStep(); idle()
            for (i in 3 until 6) stroke(i)
            canvas.releaseWindow()
            val tiles = engine.tileKeys(LAYER).associateWith { checkNotNull(engine.readTile(LAYER, it)) }
            if (long && MediaAtlasGl.enabled) lastTiles = tiles
            return measure(tiles).put("droppedPayloads", warnings.count { "outside the media funnel" in it })
        } finally {
            runCatching { canvas.release() }
            if (engine.ready) runCatching { engine.release() }
        }
    }

    /**
     * For each horizontal tile boundary with paint on both sides: how much the last row of the tile above and the first row
     * of the tile below stand out from the rows beside them (luma, 0-255, opaque columns only); the same for vertical
     * boundaries; and the baseline on interior rows.
     */
    private fun measure(tiles: Map<Long, ByteArray>): JSONObject {
        // Oil's look is translucent (alpha about 115-230 on the owner's tiles): luma of the colour, un-premultiplied.
        fun luma(t: ByteArray, x: Int, y: Int): Double { val i = (y * N + x) * 4
            val a = maxOf(t[i + 3].toInt() and 0xFF, 1) / 255.0
            return (0.3 * (t[i].toInt() and 0xFF) + 0.59 * (t[i + 1].toInt() and 0xFF) + 0.11 * (t[i + 2].toInt() and 0xFF)) / a }
        fun alpha(t: ByteArray, x: Int, y: Int) = t[(y * N + x) * 4 + 3].toInt() and 0xFF
        /** Mean of (neighbours' mean - this line) over opaque samples; [at] gives the three samples of line position k. */
        fun standOut(count: Int, at: (Int) -> Triple<Pair<ByteArray, Pair<Int, Int>>, Pair<ByteArray, Pair<Int, Int>>, Pair<ByteArray, Pair<Int, Int>>>): Double? {
            var sum = 0.0; var n = 0
            for (k in 0 until count) {
                val (a, b, c) = at(k)
                if (alpha(a.first, a.second.first, a.second.second) < 128 || alpha(b.first, b.second.first, b.second.second) < 128 ||
                    alpha(c.first, c.second.first, c.second.second) < 128) continue
                val la = luma(a.first, a.second.first, a.second.second); val lb = luma(b.first, b.second.first, b.second.second)
                val lc = luma(c.first, c.second.first, c.second.second)
                sum += (la + lc) / 2 - lb; n++
            }
            return if (n >= 16) sum / n else null
        }
        val rowsAbove = ArrayList<Double>(); val rowsBelow = ArrayList<Double>(); val cols = ArrayList<Double>(); val base = ArrayList<Double>()
        val worst = JSONArray()
        for ((key, t) in tiles) {
            val tx = (key shr 32).toInt(); val ty = key.toInt()
            for (y in listOf(64, 192)) standOut(N) { x -> Triple(t to (x to y - 1), t to (x to y), t to (x to y + 1)) }?.let { base += it }
            val below = tiles[cc.joycreator.joybrush.core.paint.Tiles.key(tx, ty + 1)]
            if (below != null) {
                standOut(N) { x -> Triple(t to (x to 254), t to (x to 255), below to (x to 0)) }?.let { rowsAbove += it; if (abs(it) > 5) worst.put("bottom of $tx,$ty: ${"%.1f".format(it)}") }
                standOut(N) { x -> Triple(t to (x to 255), below to (x to 0), below to (x to 1)) }?.let { rowsBelow += it }
            }
            val right = tiles[cc.joycreator.joybrush.core.paint.Tiles.key(tx + 1, ty)]
            if (right != null) standOut(N) { y -> Triple(t to (254 to y), t to (255 to y), right to (0 to y)) }?.let { cols += it; if (abs(it) > 5) worst.put("right of $tx,$ty: ${"%.1f".format(it)}") }
        }
        fun stats(v: List<Double>) = JSONObject().put("n", v.size).put("mean", if (v.isEmpty()) 0.0 else v.average())
            .put("maxAbs", v.maxOfOrNull { abs(it) } ?: 0.0)
        return JSONObject().put("tiles", tiles.size).put("lastRowAbove", stats(rowsAbove)).put("firstRowBelow", stats(rowsBelow))
            .put("lastColumn", stats(cols)).put("interiorBaseline", stats(base)).put("worst", worst)
    }

    /** Six oil strokes: inside the old window's reach (about 900 px) by default; [long]: across many tiles, as the undo probe's. */
    var long = false
    private fun path(i: Int): List<MediaSample> {
        if (long) {
            val n = 320
            val (x0, y0, dx, dy) = when (i % 3) { 0 -> listOf(-600.0, -300.0, 1900.0, 700.0); 1 -> listOf(1300.0, -500.0, -1700.0, 1300.0); else -> listOf(-400.0, 700.0, 1800.0, -900.0) }
            val shift = 90.0 * (i / 3)
            return List(n) { k ->
                val t = k / (n - 1.0)
                MediaSample(x0 + dx * t + shift, y0 + dy * t + 180 * sin(2 * PI * t * 1.5 + i) + shift, 0.35 + 0.5 * abs(sin(3 * PI * t)), 0.6, 0.4 + t, k * 8.0)
            }
        }
        val n = 160
        return List(n) { k ->
            val t = k / (n - 1.0)
            val x = 60 + 780 * t + 40 * sin(2 * PI * t + i)
            val y = 80 + 120 * i + 200 * sin(PI * t * (1 + i % 3))
            MediaSample(x, y, 0.4 + 0.5 * abs(sin(3 * PI * t)), 0.6, 0.4 + t, k * 8.0)
        }
    }

    private val COLOURS = intArrayOf(0xFFE8C21E.toInt(), 0xFF2F7D3A.toInt(), 0xFF7A2E2A.toInt(), 0xFF2E6FCC.toInt())
}

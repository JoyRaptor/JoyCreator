package cc.joycreator.joybrush.androidkit.gl.media

import android.opengl.GLES30
import cc.joycreator.joybrush.androidkit.gl.GlPaintEngine
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.media.MediaSample
import cc.joycreator.joybrush.core.media.MediaStores
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Atlas A2, the park ledger on the REAL host (debug bench only; tasks/joybrush/reviews/ATLAS_A2_PROBE_SCOUT_20261027.md).
 * The same strokes are painted twice, each time on a fresh probe-owned [GlPaintEngine] and [MediaCanvas] (the production
 * path: admission, eviction, write-back, the water park, blocks), with a fixed number of samples a frame and a synthetic
 * clock, so the frames are the same in both: once in a roomy atlas ([MediaCanvas.slotGrid] none, the phone's own size, park
 * off) and once squeezed into [tight] slots with the park on, so tiles must leave and come back mid-stroke and while the
 * water runs. What a save would read ([GlPaintEngine.readMediaTile] for every payload store, [GlPaintEngine.readTile] for
 * the look) must be the same bytes in both; the tight run must have parked and missed (not vacuous); GL error 0.
 *
 * GL thread. The user's canvas is not touched; the process-wide atlas and park switches are put back.
 */
internal object MediaAtlasLedgerProbe {
    private const val LAYER = "atlas-ledger-probe"
    private const val FRAME_MS = 16L
    private const val SAMPLES_PER_FRAME = 5
    /** Frames the water may run on after the last stroke; the same in both runs. */
    private const val DRY_FRAMES = 600

    private class Run(val tiles: Map<String, String>, val frames: Int, val parked: Int, val misses: Int, val blocks: Int,
                      val slots: String, val refused: String?)

    fun probe(paper: Paper, presets: List<BrushPreset>, memory: () -> Pair<Long, Long>, tight: Pair<Int, Int>, parkUnits: Int): String {
        val report = JSONObject().put("probe", "mediaAtlasLedgerA2").put("renderer", GLES30.glGetString(GLES30.GL_RENDERER))
            .put("brushes", presets.joinToString(",") { it.id }).put("tight", "${tight.first}x${tight.second}").put("parkUnits", parkUnits)
        val atlasBefore = MediaAtlasGl.enabled
        val parkBefore = MediaAtlasPark.units
        try {
            MediaAtlasGl.enabled = true
            MediaAtlasPark.units = 0
            val roomy = paint(paper, presets, memory, null)
            MediaAtlasPark.units = parkUnits
            val squeezed = paint(paper, presets, memory, tight)
            fun side(r: Run) = JSONObject().put("tiles", r.tiles.size).put("frames", r.frames).put("parkedTotal", r.parked)
                .put("admissionMisses", r.misses).put("blocksRun", r.blocks).put("slots", r.slots).put("refused", r.refused ?: JSONObject.NULL)
            report.put("roomy", side(roomy)).put("squeezed", side(squeezed))
            val differ = (roomy.tiles.keys + squeezed.tiles.keys).filter { roomy.tiles[it] != squeezed.tiles[it] }.sorted()
            report.put("differingTiles", differ.size).put("firstDiffering", differ.take(12).joinToString(" "))
            val glError = GLES30.glGetError()
            report.put("glError", glError)
            val painted = roomy.tiles.keys.count { it.startsWith("look ") }
            report.put("pass", roomy.refused == null && squeezed.refused == null && differ.isEmpty() && painted >= 12 &&
                squeezed.parked > 0 && squeezed.misses > 0 && roomy.frames == squeezed.frames && glError == GLES30.GL_NO_ERROR)
        } catch (e: Throwable) {
            report.put("pass", false).put("error", e.toString()).put("at", e.stackTrace.take(8).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" })
        } finally {
            MediaAtlasGl.enabled = atlasBefore
            MediaAtlasPark.units = parkBefore
        }
        return report.toString()
    }

    /** One fresh engine and canvas, every stroke, the water left to run, everything written back, then every tile hashed. */
    private fun paint(paper: Paper, presets: List<BrushPreset>, memory: () -> Pair<Long, Long>, grid: Pair<Int, Int>?): Run {
        val engine = GlPaintEngine()
        val canvas = MediaCanvas(engine, memory)
        var parked = 0; var misses = 0; var blocks = 0; var slots = "?"
        try {
            engine.init()
            engine.setDocumentPaper(paper)
            engine.addLayer(LAYER)
            canvas.slotGrid = grid
            var now = 1_000L
            var frames = 0
            fun counters() { canvas.atlas?.let { a -> parked = maxOf(parked, a.parkedTotal); misses = maxOf(misses, a.admissionMisses)
                blocks = maxOf(blocks, a.blocksRun); slots = "${a.media.slotCount} slots, ${a.media.slotColumns} wide" } }
            for ((i, preset) in presets.withIndex()) {
                val pts = path(i)
                val colour = COLOURS[i % COLOURS.size]
                canvas.begin(LAYER, preset, colour, null, pts[0].x, pts[0].y, now)?.let { why ->
                    return Run(emptyMap(), frames, parked, misses, blocks, slots, why)
                }
                for (chunk in pts.chunked(SAMPLES_PER_FRAME)) {
                    canvas.add(chunk)
                    now += FRAME_MS; frames++
                    canvas.frame(now)
                    counters()
                }
                canvas.end()
                counters()
            }
            var dry = 0
            while (dry < DRY_FRAMES) { now += FRAME_MS; dry++; frames++; val more = canvas.frame(now); counters(); if (!more) break }
            canvas.releaseWindow()
            return Run(hashes(engine), frames, parked, misses, blocks, slots, null)
        } finally {
            runCatching { canvas.release() }
            if (engine.ready) runCatching { engine.release() }
        }
    }

    /**
     * Stroke [i]: a long wavy line across many tiles, negative coordinates included, so a small atlas must evict while it
     * paints and its water has neighbours on every side. Pressure and time vary along it.
     */
    private fun path(i: Int): List<MediaSample> {
        val n = 360
        val x0 = -700.0 + 90 * i; val y0 = -500.0 + 140 * i
        return List(n) { k ->
            val t = k / (n - 1.0)
            MediaSample(x0 + 2100 * t, y0 + 900 * t + 220 * sin(2 * PI * t * 1.5 + i), 0.35 + 0.5 * abs(sin(3 * PI * t)), 0.6, 0.4 + t, k * 8.0)
        }
    }

    /** "store key" -> SHA-256 of what a save reads: every payload store tile, and every look tile. */
    private fun hashes(engine: GlPaintEngine): Map<String, String> {
        val out = sortedMapOf<String, String>()
        for (store in MediaStores.PAYLOAD) for (key in engine.mediaTileKeys(LAYER, store)) {
            engine.readMediaTile(LAYER, store, key)?.let { out["$store $key"] = sha(it) }
        }
        for (key in engine.tileKeys(LAYER)) engine.readTile(LAYER, key)?.let { out["look $key"] = sha(it) }
        return out
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private val COLOURS = intArrayOf(0xFF2E6FCC.toInt(), 0xFFB8461A.toInt(), 0xFF2F7D3A.toInt(), 0xFF6A3C9A.toInt())
}

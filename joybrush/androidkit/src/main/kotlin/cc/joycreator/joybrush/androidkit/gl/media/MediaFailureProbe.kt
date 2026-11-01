package cc.joycreator.joybrush.androidkit.gl.media

import android.opengl.GLES30
import cc.joycreator.joybrush.androidkit.gl.GlPaintEngine
import cc.joycreator.joybrush.androidkit.gl.MediaRoomException
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.media.MediaSample
import org.json.JSONObject

/**
 * The owner's pink knife lines (2026-10-31, Note 9): a media frame failed near the end of a line, the engine went without
 * writing it back, and the next two lines across it redrew their boxes empty. On the REAL host (debug bench only): a line that
 * fails on its last frame the way the canvas handles it, then a line across it. What the first line laid must still show;
 * [keepPaint] false takes the old way out, to prove the probe sees the bug.
 *
 * GL thread. The user's canvas is not touched; the process-wide atlas switch is put back.
 */
internal object MediaFailureProbe {
    private const val LAYER = "failure-probe"
    private const val FRAME_MS = 16L
    private const val SAMPLES_PER_FRAME = 4

    fun probe(paper: Paper, preset: BrushPreset, memory: () -> Pair<Long, Long>): String {
        val atlasBefore = MediaAtlasGl.enabled
        val o = JSONObject().put("probe", "mediaFailure").put("brush", preset.id)
        var pass = true
        try {
            MediaAtlasGl.enabled = true
            for (keep in listOf(false, true)) {
                val r = run(paper, preset, memory, keep)
                o.put(if (keep) "fixed" else "oldWay", r)
                // The old way must show the hole, or this probe proves nothing.
                if (r.getInt("lostPixels") * 200 > r.getInt("laidPixels") != !keep) pass = false
            }
            val full = run(paper, preset, memory, keepPaint = true, roomRunsOut = true)
            o.put("roomRanOut", full)
            if (full.getInt("lostPixels") * 200 > full.getInt("laidPixels") || full.getInt("refused") == 0) pass = false
        } finally { MediaAtlasGl.enabled = atlasBefore }
        val glError = GLES30.glGetError()
        return o.put("glError", glError).put("pass", pass && glError == GLES30.GL_NO_ERROR).toString()
    }

    private fun run(paper: Paper, preset: BrushPreset, memory: () -> Pair<Long, Long>, keepPaint: Boolean, roomRunsOut: Boolean = false): JSONObject {
        val engine = GlPaintEngine()
        val canvas = MediaCanvas(engine, memory)
        try {
            engine.init()
            engine.onMediaRestored = { layer, plane, keys -> canvas.restored(layer, plane, keys) }
            engine.onLookWritten = { layer, plane, keys -> canvas.lookWritten(layer, plane, keys) }
            engine.setDocumentPaper(paper)
            engine.addLayer(LAYER)
            var now = 1_000L
            var refused = 0
            fun line(x0: Double, y0: Double, x1: Double, y1: Double, colour: Int, fail: Boolean) {
                val n = 80
                val pts = List(n) { k -> val t = k / (n - 1.0); MediaSample(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, 0.7, 0.6, 0.4, k * 8.0) }
                canvas.begin(LAYER, preset, colour, null, pts[0].x, pts[0].y, now)?.let { throw IllegalStateException("refused: $it") }
                val chunks = pts.chunked(SAMPLES_PER_FRAME)
                for ((i, chunk) in chunks.withIndex()) {
                    canvas.addSamples(chunk); now += FRAME_MS
                    canvas.failNextFrame = fail && !roomRunsOut && i == chunks.lastIndex - 2
                    // The drawing's room runs out half way down the line, two tiles' worth past what it holds.
                    if (fail && roomRunsOut && i == chunks.size / 2) engine.mediaBudgetBytes = engine.mediaResidentBytes() + (2L shl 20)
                    try { canvas.frame(now) } catch (e: MediaRoomException) {
                        // As JbCanvasView.drawFrame: the stroke ends, the engine stays.
                        refused++
                        canvas.dropStroke()
                        engine.endMediaStroke()
                        engine.mediaBudgetBytes = Long.MAX_VALUE
                        return
                    } catch (e: RuntimeException) {
                        canvas.failNextFrame = false
                        // As JbCanvasView.drawFrame; the old way only closed the step and released.
                        if (keepPaint) canvas.dropStroke()
                        engine.endMediaStroke()
                        if (keepPaint) canvas.releaseAfterFailure() else canvas.release()
                        return
                    }
                }
                canvas.end()
                var f = 0; while (f++ < 600) { now += FRAME_MS; if (!canvas.frame(now)) break }
            }
            line(300.0, 100.0, 300.0, 1100.0, 0xFFFF2DA8.toInt(), fail = true)
            val after = coverage(engine)
            line(40.0, 620.0, 640.0, 700.0, 0xFFFF2DA8.toInt(), fail = false)
            canvas.releaseWindow()
            val later = coverage(engine)
            val laid = after.values.sumOf { b -> b.count { it } }
            val lost = after.entries.sumOf { (k, b) -> val c = later[k]; b.indices.count { b[it] && (c == null || !c[it]) } }
            return JSONObject().put("laidPixels", laid).put("lostPixels", lost).put("refused", refused)
        } finally {
            runCatching { canvas.release() }
            if (engine.ready) runCatching { engine.release() }
        }
    }

    /** Where each look tile shows paint: alpha over a half. */
    private fun coverage(engine: GlPaintEngine): Map<Long, BooleanArray> = engine.tileKeys(LAYER).associateWith { k ->
        val px = engine.readTile(LAYER, k)
        if (px == null) BooleanArray(0) else BooleanArray(px.size / 4) { (px[it * 4 + 3].toInt() and 0xFF) > 128 }
    }
}

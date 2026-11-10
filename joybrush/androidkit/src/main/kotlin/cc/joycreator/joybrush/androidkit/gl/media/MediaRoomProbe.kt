package cc.joycreator.joybrush.androidkit.gl.media

import cc.joycreator.joybrush.androidkit.gl.GlPaintEngine
import cc.joycreator.joybrush.androidkit.gl.MediaRoomException
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.media.MediaSample
import cc.joycreator.joybrush.core.paint.Tiles
import org.json.JSONObject
import java.security.MessageDigest

/**
 * The owner's bar (2026-11-09): an artist who knows nothing about memory never meets a memory refusal. On the REAL host
 * (debug bench only, run as part of `mediaFailureProbe`):
 *
 *  - [roomMade]: a line, then the drawing's media room made nearly full, then a line far away. The second line used to be
 *    refused ("this drawing has used it"). Now the first line's old paint sets: nothing refused, every pixel of the first
 *    line's picture unchanged, the second line whole. A third line across the first loses none of it, and undoing all
 *    three gives back the blank layer.
 *  - [farMark]: a line, then a short mark 80 tiles away. It used to fail its frame ("resident tiles spread over too large
 *    a table", second review E7). Now the cached tiles are let go: no failure, both marks there.
 *
 * GL thread. The user's canvas is not touched; the caller puts the process-wide atlas switch back.
 */
internal object MediaRoomProbe {
    private const val LAYER = "room-probe"
    private const val FRAME_MS = 16L
    private const val PER_FRAME = 4
    private const val PINK = 0xFFFF2DA8.toInt()

    private class Host(paper: Paper, memory: () -> Pair<Long, Long>) {
        val engine = GlPaintEngine()
        val canvas = MediaCanvas(engine, memory)
        var now = 1_000L
        var refused = 0
        var failed: String? = null
        init {
            engine.init()
            engine.onMediaRestored = { layer, plane, keys -> canvas.restored(layer, plane, keys) }
            engine.onLookWritten = { layer, plane, keys -> canvas.lookWritten(layer, plane, keys) }
            engine.setDocumentPaper(paper)
            engine.addLayer(LAYER)
        }

        /** One stroke as the canvas view runs it: a refusal ends it where it got to, any other failure is noted. */
        fun line(preset: BrushPreset, x0: Double, y0: Double, x1: Double, y1: Double, n: Int = 80) {
            val pts = List(n) { k -> val t = k / (n - 1.0); MediaSample(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, 0.7, 0.6, 0.4, k * 8.0) }
            canvas.begin(LAYER, preset, PINK, null, pts[0].x, pts[0].y, now)?.let { refused++; failed = failed ?: "begin: $it"; return }
            for (chunk in pts.chunked(PER_FRAME)) {
                canvas.addSamples(chunk); now += FRAME_MS
                try { canvas.frame(now) } catch (e: MediaRoomException) {
                    refused++; failed = failed ?: "refused: ${e.message}"
                    canvas.dropStroke(); engine.endMediaStroke(); return
                } catch (e: RuntimeException) {
                    failed = failed ?: "failed: $e"
                    canvas.dropStroke(); engine.endMediaStroke(); canvas.releaseAfterFailure(); return
                }
            }
            try { canvas.end() } catch (e: MediaRoomException) { refused++; failed = failed ?: "end: ${e.message}" }
            idle()
        }

        fun idle() { var f = 0; while (f++ < 600) { now += FRAME_MS; if (!canvas.frame(now)) break } }

        fun looks(): Map<Long, String> = engine.tileKeys(LAYER).associateWith { k -> engine.readTile(LAYER, k)?.let(::sha) ?: "none" }
        fun covered(): Map<Long, BooleanArray> = engine.tileKeys(LAYER).associateWith { k ->
            val px = engine.readTile(LAYER, k)
            if (px == null) BooleanArray(0) else BooleanArray(px.size / 4) { (px[it * 4 + 3].toInt() and 0xFF) > 128 }
        }
        fun payloadTiles(keys: Collection<Long>): Int = keys.count { engine.hasPayload(LAYER, it) }
        fun release() { runCatching { canvas.release() }; if (engine.ready) runCatching { engine.release() } }
    }

    fun roomMade(paper: Paper, preset: BrushPreset, memory: () -> Pair<Long, Long>): JSONObject {
        val h = Host(paper, memory)
        val o = JSONObject()
        try {
            h.line(preset, 300.0, 100.0, 300.0, 1100.0)
            h.canvas.releaseWindow()   // the first line's tiles are in the layer's stores only, as after a pan away
            val firstKeys = h.engine.tileKeys(LAYER)
            val firstLooks = h.looks()
            val firstCover = h.covered()
            val payloadBefore = h.payloadTiles(firstKeys)
            // Nearly full: one more megabyte, and the far line needs many.
            h.engine.mediaBudgetBytes = h.engine.mediaResidentBytes() + (1L shl 20)
            val far = 60 * Tiles.SIZE.toDouble()
            h.line(preset, far + 300.0, 100.0, far + 300.0, 1100.0)
            h.canvas.releaseWindow()
            val set = h.engine.mediaTilesSet
            val afterFar = h.looks()
            val firstChanged = firstKeys.count { firstLooks[it] != afterFar[it] }
            val farLaid = h.covered().filterKeys { it !in firstCover }.values.sumOf { b -> b.count { it } }
            val payloadAfter = h.payloadTiles(firstKeys)
            // A line across the first, where its paint has set: what the first laid must still show.
            h.engine.mediaBudgetBytes = Long.MAX_VALUE
            h.line(preset, 40.0, 620.0, 640.0, 700.0)
            h.canvas.releaseWindow()
            val later = h.covered()
            val laid = firstCover.values.sumOf { b -> b.count { it } }
            val lost = firstCover.entries.sumOf { (k, b) -> val c = later[k]; b.indices.count { b[it] && (c == null || !c[it]) } }
            // One press, one step: three strokes, three undos, a blank layer; the set paint is in the far line's step.
            var undone = 0
            while (undone < 3) { h.canvas.settleOwed(); if (!h.engine.undoStep()) break; undone++; h.idle() }
            h.canvas.releaseWindow()
            val leftAfterUndo = h.covered().values.sumOf { b -> b.count { it } }
            val extraUndo = h.engine.undoStep()
            val ok = h.refused == 0 && h.failed == null && set > 0 && firstChanged == 0 && farLaid > 1000 && payloadAfter < payloadBefore &&
                lost * 200 <= laid && undone == 3 && leftAfterUndo == 0 && !extraUndo
            o.put("ok", ok).put("refused", h.refused).put("failed", h.failed ?: "").put("tilesSet", set)
                .put("firstLineTilesChanged", firstChanged).put("farLaidPixels", farLaid)
                .put("payloadTilesBefore", payloadBefore).put("payloadTilesAfter", payloadAfter)
                .put("laidPixels", laid).put("lostPixels", lost).put("undone", undone).put("pixelsLeftAfterUndo", leftAfterUndo).put("extraUndoStep", extraUndo)
        } catch (e: RuntimeException) {
            o.put("ok", false).put("threw", e.toString())
        } finally { h.release() }
        return o
    }

    fun farMark(paper: Paper, preset: BrushPreset, memory: () -> Pair<Long, Long>): JSONObject {
        val h = Host(paper, memory)
        val o = JSONObject()
        try {
            h.line(preset, 300.0, 100.0, 300.0, 600.0, n = 40)
            val first = h.covered().values.sumOf { b -> b.count { it } }
            // No release: the first line's tiles are still cached in the atlas when the pen comes down 80 tiles away.
            val far = 80 * Tiles.SIZE.toDouble()
            h.line(preset, far + 100.0, far + 100.0, far + 220.0, far + 160.0, n = 24)
            val letGo = h.canvas.atlas?.farLetGo ?: -1
            h.canvas.releaseWindow()
            val all = h.covered().values.sumOf { b -> b.count { it } }
            val ok = h.refused == 0 && h.failed == null && first > 1000 && all > first && letGo > 0
            o.put("ok", ok).put("refused", h.refused).put("failed", h.failed ?: "").put("firstPixels", first).put("allPixels", all).put("cachedTilesLetGo", letGo)
        } catch (e: RuntimeException) {
            o.put("ok", false).put("threw", e.toString())
        } finally { h.release() }
        return o
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
}

package cc.joycreator.joybrush.androidkit.gl

import android.opengl.GLES30
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.media.MediaStores
import cc.joycreator.joybrush.core.paint.Tiles
import org.json.JSONArray
import org.json.JSONObject

/**
 * A duplicated animation frame keeps its realistic-paint state (R2-JB-03, Codex audit 2026-11-15). Before the fix the copy
 * had the picture but none of the paint under it (pigment, paper, ground), so pencil, oil and old-engine water could not
 * go on working it.
 *
 * On a throwaway engine: an animation board, its first frame given pixels and a payload in every store on two tiles (one
 * of them only partly under the board), then the app's own Duplicate frame ([RegionDocumentOps.addFrame]). Checked: every
 * payload tile of the source is on the copy with the same bytes; the source's is untouched; the copy's payload agrees with
 * its look (nothing is dropped as stale); it is one undo step; Undo takes the copy's payload away and leaves the
 * source's; Redo brings the same bytes back.
 *
 * Not covered: a sprite cell move or swap (still settles), painting on with a real brush, a save and reopen, memory
 * running out part way.
 */
internal object FramePayloadProbe {
    fun run(engine: GlPaintEngine, layerId: String): JSONObject {
        val report = JSONObject().put("probe", "framePayload")
        val problems = JSONArray()
        fun problem(s: String) { if (problems.length() < 16) problems.put(s) }
        var phase = "setup"
        try {
            val size = Tiles.SIZE
            var serial = 0
            fun fresh() = "frame-payload-${++serial}"
            // The board ends half way through the second tile: that tile is only partly the frame's.
            val rect = RectPx(0, 0, size + size / 2, size)
            val base = JbDocument(id = "frame-payload-doc", name = "Disposable frame payload probe",
                boards = listOf(Board("frame-payload-root", "Root", BoardKind.CANVAS, RectPx(-512, -512, 1536, 1536))),
                layers = listOf(Layer(layerId, "Probe", LayerKind.PAINT, cels = listOf(Cel("frame-payload-shared")))),
                activeLayerId = layerId)
            engine.setBoardDocument(base)
            engine.applyBoardChange(RegionDocumentOps.create(base, "Payload", rect, ::fresh))
            val before = checkNotNull(engine.boardDocument)
            val board = before.boards.single { it.kind == BoardKind.ANIMATION }
            val sourceCel = before.layers.single().regions.single().frameCel.getValue(board.currentFrameId!!)
            val keys = listOf(Tiles.key(0, 0), Tiles.key(1, 0))

            // The look first (a plain write drops any payload), then the payload, then both are said to agree.
            for ((n, key) in keys.withIndex()) {
                val rgba = ByteArray(size * size * 4) { i -> if (i % 4 == 3) 0xFF.toByte() else ((i / 4 + n * 37) and 0x7F).toByte() }
                engine.writeCelTile(layerId, sourceCel, key, rgba)
            }
            for ((n, key) in keys.withIndex()) for ((s, store) in MediaStores.PAYLOAD.withIndex()) {
                val bytes = ByteArray(MediaStores.tileBytes(size, store))
                if (store == MediaStores.GROUND) for (i in bytes.indices) bytes[i] = ((i + s * 11 + n * 5) and 0xFF).toByte()
                // Half floats between 0.5 and 1 (high byte 0x38..0x3B), so every value is an ordinary number.
                else for (i in bytes.indices step 2) { bytes[i] = ((i / 2 * 7 + s * 13 + n) and 0xFF).toByte(); bytes[i + 1] = (0x38 + ((i / 2 + s) and 3)).toByte() }
                engine.writeMediaTile(layerId, store, key, bytes, plane = sourceCel)
            }
            engine.agreeAllPayloads()
            fun payload(cel: String): Map<Pair<String, Long>, ByteArray> {
                val out = LinkedHashMap<Pair<String, Long>, ByteArray>()
                for (key in keys) for (store in MediaStores.PAYLOAD) engine.readMediaTile(layerId, store, key, plane = cel)?.let { out[store to key] = it.copyOf() }
                return out
            }
            val source = payload(sourceCel)
            report.put("sourcePayloadTiles", source.size)
            if (source.size != keys.size * MediaStores.PAYLOAD.size) problem("setup: the source frame holds ${source.size} payload tiles, not ${keys.size * MediaStores.PAYLOAD.size}")

            phase = "duplicate"
            val doc = checkNotNull(engine.boardDocument)
            val change = RegionDocumentOps.addFrame(doc, board.id, NewFrame.DUPLICATE, ::fresh)
            val copy = checkNotNull(FrameDuplicatePlan.validate(doc, change)) { "not a frame duplicate" }.single()
            val targetCel = copy.toCelId
            val depth = engine.undo.undoDepth
            engine.applyBoardChange(change)
            fun same(a: Map<Pair<String, Long>, ByteArray>, b: Map<Pair<String, Long>, ByteArray>) =
                a.keys == b.keys && a.all { (k, v) -> v.contentEquals(b.getValue(k)) }
            fun sourceKept(at: String) { if (!same(source, payload(sourceCel))) problem("$at: the source frame's payload changed") }

            val copied = payload(targetCel)
            report.put("copiedPayloadTiles", copied.size).put("undoSteps", engine.undo.undoDepth - depth)
            if (!same(source, copied)) problem("duplicate: the copy holds ${copied.size} payload tiles, the same bytes on ${copied.count { (k, v) -> source[k]?.contentEquals(v) == true }}")
            if (engine.undo.undoDepth != depth + 1) problem("duplicate: ${engine.undo.undoDepth - depth} undo steps")
            sourceKept("duplicate")
            val stale = engine.dropStalePayload(layerId, keys, targetCel)
            report.put("droppedAsStale", stale.size)
            if (stale.isNotEmpty()) problem("duplicate: ${stale.size} of the copy's tiles were dropped as not agreeing with its look")
            if (!keys.all { engine.hasPayload(layerId, it, targetCel) }) problem("duplicate: the copy has no payload after the agreement check")

            phase = "undo"
            check(engine.undoStep()) { "undo refused" }
            val undone = payload(targetCel)
            report.put("afterUndoPayloadTiles", undone.size)
            if (undone.isNotEmpty()) problem("undo: the copy still holds ${undone.size} payload tiles")
            sourceKept("undo")

            phase = "redo"
            check(engine.redoStep()) { "redo refused" }
            val redone = payload(targetCel)
            report.put("afterRedoPayloadTiles", redone.size)
            if (!same(source, redone)) problem("redo: the copy's payload is not the source's (${redone.size} tiles)")
            sourceKept("redo")
            val staleAgain = engine.dropStalePayload(layerId, keys, targetCel)
            report.put("droppedAsStaleAfterRedo", staleAgain.size)
            if (staleAgain.isNotEmpty()) problem("redo: ${staleAgain.size} of the copy's tiles were dropped as stale")

            val gl = GLES30.glGetError()
            report.put("glError", gl)
            if (gl != GLES30.GL_NO_ERROR) problem("GL error $gl")
        } catch (e: Throwable) {
            problem("$phase: $e")
            while (GLES30.glGetError() != GLES30.GL_NO_ERROR) Unit
        }
        return report.put("problems", problems).put("passed", problems.length() == 0)
    }
}

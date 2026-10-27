package cc.joycreator.joybrush.androidkit.gl.media

import android.opengl.GLES30
import cc.joycreator.joybrush.androidkit.gl.GlPaintEngine
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.media.MediaSample
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The owner's oil seams (2026-10-27, Note 9: one tile showing a different moment than its neighbours, payloads dropped as
 * "changed outside the media funnel"), on the REAL host (debug bench only). A fresh probe-owned [GlPaintEngine] and
 * [MediaCanvas], wired as the app wires them, paint with the app's own calls and a synthetic clock:
 *
 *  A, then B across it (and across many tiles), then undo B, then redo B, then undo B again and paint C.
 *
 * What the person sees, every look tile, must be: after the undo, exactly what it was before B; after the redo, exactly what
 * it was after B. No payload may be dropped at any point. Run in the phone-sized atlas and squeezed into [tight] slots, so
 * tiles leave and come back between strokes. A blank tile counts as no tile. GL error 0.
 *
 * GL thread. The user's canvas is not touched; the process-wide atlas switch is put back.
 */
internal object MediaUndoProbe {
    private const val LAYER = "undo-probe"
    private const val FRAME_MS = 16L
    private const val SAMPLES_PER_FRAME = 5
    private const val IDLE_FRAMES = 600
    private const val ZERO = "zero"

    fun probe(paper: Paper, preset: BrushPreset, memory: () -> Pair<Long, Long>, tight: Pair<Int, Int>): String {
        val report = JSONObject().put("probe", "mediaUndo").put("renderer", GLES30.glGetString(GLES30.GL_RENDERER))
            .put("brush", preset.id)
        val atlasBefore = MediaAtlasGl.enabled
        val runs = JSONArray()
        var pass = true
        try {
            MediaAtlasGl.enabled = true
            for (grid in listOf(null, tight)) {
                val o = try { run(paper, preset, memory, grid) } catch (e: Throwable) {
                    JSONObject().put("pass", false).put("error", e.toString())
                        .put("at", e.stackTrace.take(8).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" })
                }
                o.put("grid", grid?.let { "${it.first}x${it.second}" } ?: "phone")
                if (!o.optBoolean("pass")) pass = false
                runs.put(o)
            }
        } finally { MediaAtlasGl.enabled = atlasBefore }
        val glError = GLES30.glGetError()
        return report.put("runs", runs).put("glError", glError).put("pass", pass && glError == GLES30.GL_NO_ERROR).toString()
    }

    private fun run(paper: Paper, preset: BrushPreset, memory: () -> Pair<Long, Long>, grid: Pair<Int, Int>?): JSONObject {
        val engine = GlPaintEngine()
        val canvas = MediaCanvas(engine, memory)
        val o = JSONObject()
        val warnings = ArrayList<String>()
        try {
            engine.init()
            engine.onMediaRestored = { layer, plane, keys -> canvas.restored(layer, plane, keys) }
            engine.onLookWritten = { layer, plane, keys -> canvas.lookWritten(layer, plane, keys) }
            engine.onMediaWarning = { warnings += it }
            engine.setDocumentPaper(paper)
            engine.addLayer(LAYER)
            canvas.slotGrid = grid
            var now = 1_000L
            fun idle() { var n = 0; while (n < IDLE_FRAMES) { now += FRAME_MS; n++; if (!canvas.frame(now)) break } }
            fun stroke(i: Int, colour: Int) {
                val pts = path(i)
                canvas.begin(LAYER, preset, colour, null, pts[0].x, pts[0].y, now)?.let { throw IllegalStateException("refused: $it") }
                for (chunk in pts.chunked(SAMPLES_PER_FRAME)) { canvas.addSamples(chunk); now += FRAME_MS; canvas.frame(now) }
                canvas.end()
                idle()
            }
            val phases = JSONArray()
            fun drops() = warnings.count { "outside the media funnel" in it }
            /** Where history and drops stand after [name]: undo depth, a redo there or not, drops so far, write-backs. */
            fun mark(name: String) { phases.put("$name: depth ${engine.undo.undoDepth} redo ${engine.undo.peekRedo() != null} drops ${drops()} wb ${canvas.atlas?.writeBacks ?: -1}") }
            fun undo() {
                canvas.settleOwed(); mark("settled"); check(engine.undoStep()) { "nothing to undo" }; mark("undone")
                idle(); mark("undo+idle")
            }
            fun redo() {
                canvas.settleOwed(); mark("settled"); check(engine.redoStep()) { "nothing to redo (${phases.join(" / ")})" }; mark("redone")
                idle(); mark("redo+idle")
            }

            stroke(0, 0xFFE8C21E.toInt()); mark("A")
            val afterA = looks(engine); mark("A read")
            stroke(1, 0xFF2F7D3A.toInt()); mark("B")
            val afterB = looks(engine); mark("B read")
            undo()
            val undone = looks(engine)
            redo()
            val redone = looks(engine)
            undo()
            stroke(2, 0xFFB8461A.toInt()); mark("C")
            o.put("phases", phases)
            val writeBacks = canvas.atlas?.writeBacks ?: -1
            canvas.releaseWindow()

            fun cmp(name: String, want: Map<Long, String>, got: Map<Long, String>): Int {
                val keys = (want.keys + got.keys).sorted()
                val bad = keys.filter { (want[it] ?: ZERO) != (got[it] ?: ZERO) }
                o.put(name, JSONObject().put("differingTiles", bad.size)
                    .put("first", bad.take(8).joinToString(" ") { k -> "${k shr 32},${k.toInt()}" }))
                return bad.size
            }
            val bChanged = (afterA.keys + afterB.keys).count { (afterA[it] ?: ZERO) != (afterB[it] ?: ZERO) }
            val undoBad = cmp("undoVsBeforeB", afterA, undone)
            val redoBad = cmp("redoVsAfterB", afterB, redone)
            o.put("tilesAfterA", afterA.size).put("tilesAfterB", afterB.size).put("tilesBChanged", bChanged)
                .put("writeBacks", writeBacks).put("slots", canvas.atlas?.let { "${it.media.slotCount}" } ?: "released")
                .put("droppedPayloadWarnings", warnings.count { "outside the media funnel" in it })
                .put("warnings", warnings.take(6).joinToString(" | "))
            o.put("pass", undoBad == 0 && redoBad == 0 && bChanged >= 6 && warnings.none { "outside the media funnel" in it })
        } finally {
            runCatching { canvas.release() }
            if (engine.ready) runCatching { engine.release() }
        }
        return o
    }

    /** Stroke [i]: long and wavy across many tiles; B crosses A, C crosses both. */
    private fun path(i: Int): List<MediaSample> {
        val n = 320
        val (x0, y0, dx, dy) = when (i) { 0 -> listOf(-600.0, -300.0, 1900.0, 700.0); 1 -> listOf(1300.0, -500.0, -1700.0, 1300.0); else -> listOf(-400.0, 700.0, 1800.0, -900.0) }
        return List(n) { k ->
            val t = k / (n - 1.0)
            MediaSample(x0 + dx * t, y0 + dy * t + 180 * sin(2 * PI * t * 1.5 + i), 0.35 + 0.5 * abs(sin(3 * PI * t)), 0.6, 0.4 + t, k * 8.0)
        }
    }

    /** Every look tile (what the person sees) by key: SHA-256, or "zero" for a blank one. */
    private fun looks(engine: GlPaintEngine): Map<Long, String> =
        engine.tileKeys(LAYER).associateWith { k -> engine.readTile(LAYER, k)?.let(::sha) ?: ZERO }

    private fun sha(b: ByteArray): String = if (b.all { it.toInt() == 0 }) ZERO
        else MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}

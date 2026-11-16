package cc.joycreator.joybrush.androidkit.gl.media

import android.opengl.GLES30
import cc.joycreator.joybrush.androidkit.gl.GlPaintEngine
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.media.MediaSample
import org.json.JSONObject

/**
 * A wash on the wet sheet when the phone has no graphics memory at the two moments that used to cost it (Codex audit
 * ENG-01 and ENG-02, 2026-11-15). On the REAL engine and canvas (debug bench only), with the failures injected
 * ([WetSheet.failKeeps], [WetSheet.failEngines]):
 *
 *  - cancel: the copy kept for a stroke's cancel cannot be made, and the stroke is cancelled. The wash laid before it
 *    must still be there (it was emptied with the sheet), and the cancelled stroke must not.
 *  - history: an undo, then a redo, needs the sheet's engine in its other shape (the view was turned between strokes) and
 *    that engine cannot be made. The step must be refused with nothing changed, and go through once memory is back.
 *
 * What is compared is what a save would hold: the layer's look tiles with the sheet stamped in ([MediaCanvas.withSheetStamped]).
 *
 * GL thread. The user's canvas is not touched; the process-wide sheet switch is put back.
 */
internal object WetSheetFailureProbe {
    private const val LAYER = "sheet-failure-probe"
    private const val FRAME_MS = 16L
    private const val SAMPLES_PER_FRAME = 4
    /** A small sheet (the brush settings preview's): the probe makes several. */
    private const val LONG_CELLS = 608
    private val UPRIGHT = WetSheet.View(1f, 540.0, 1110.0, 1080, 2220)
    private val TURNED = WetSheet.View(1f, 540.0, 1110.0, 1080, 2220, (Math.PI / 2).toFloat())

    fun probe(paper: Paper, preset: BrushPreset, memory: () -> Pair<Long, Long>): String {
        val sheetBefore = WetSheet.enabled
        val o = JSONObject().put("probe", "wetSheetFailure").put("brush", preset.id)
        var pass = true
        try {
            WetSheet.enabled = true
            for ((name, run) in listOf<Pair<String, (Rig) -> JSONObject>>("cancel" to ::cancel, "cancelControl" to ::cancelControl, "history" to ::history)) {
                val r = Rig(paper, preset, memory).use(run)
                o.put(name, r)
                if (!r.optBoolean("ok")) pass = false
            }
        } finally { WetSheet.enabled = sheetBefore }
        val glError = GLES30.glGetError()
        return o.put("glError", glError).put("pass", pass && glError == GLES30.GL_NO_ERROR).toString()
    }

    /** The wash, then a stroke whose rollback copy cannot be made, cancelled. */
    private fun cancel(rig: Rig): JSONObject {
        rig.line(300.0, 700.0, 800.0, 900.0)
        val wash = rig.picture()
        rig.canvas.sheetFailures(keeps = 99, engines = 0)
        val refused = rig.line(300.0, 1300.0, 800.0, 1500.0, cancel = true)
        rig.canvas.sheetFailures(0, 0)
        val after = rig.picture()
        val lost = lost(wash, after); val extra = lost(after, wash); val laid = count(wash)
        return JSONObject().put("washPixels", laid).put("lostPixels", lost).put("strayPixels", extra).put("strokeRefused", refused != null)
            .put("ok", laid > 2000 && lost * 50 <= laid && extra * 50 <= laid)
    }

    /** The same with memory to spare: the cancel must take its stroke off and nothing else (the measure sees a stroke). */
    private fun cancelControl(rig: Rig): JSONObject {
        rig.line(300.0, 700.0, 800.0, 900.0)
        val wash = rig.picture()
        rig.line(300.0, 1300.0, 800.0, 1500.0)
        val both = rig.picture()
        val undone = rig.engine.undoStep()
        rig.line(300.0, 1300.0, 800.0, 1500.0, cancel = true)
        val after = rig.picture()
        val laid = count(wash); val second = lost(both, wash)
        return JSONObject().put("washPixels", laid).put("secondStrokePixels", second).put("lostPixels", lost(wash, after)).put("strayPixels", lost(after, wash))
            .put("ok", undone && laid > 2000 && second > 2000 && lost(wash, after) * 50 <= laid && lost(after, wash) * 50 <= laid)
    }

    /** Two strokes, the view turned, a third: undo and redo across the turn, each first with no memory for the other engine. */
    private fun history(rig: Rig): JSONObject {
        val e = rig.engine
        rig.line(300.0, 700.0, 800.0, 800.0)
        val one = rig.picture()
        rig.line(300.0, 1000.0, 800.0, 1100.0)
        val two = rig.picture()
        rig.canvas.sheetView = TURNED
        rig.line(300.0, 1400.0, 800.0, 1500.0)
        val three = rig.picture()
        val o = JSONObject()
        var ok = true
        fun same(name: String, a: Map<Long, BooleanArray>, b: Map<Long, BooleanArray>) {
            val d = lost(a, b) + lost(b, a); val n = maxOf(count(a), count(b))
            o.put(name, "$d of $n differ")
            if (n < 2000 || d * 50 > n) ok = false
        }
        fun step(name: String, want: Boolean, got: Boolean) { o.put(name, got); if (got != want) ok = false }
        step("undoThird", true, e.undoStep())
        same("afterUndoThird", two, rig.picture())
        val before = rig.picture()
        rig.canvas.sheetFailures(keeps = 0, engines = 1)
        step("undoSecondNoMemory", false, e.undoStep())
        rig.canvas.sheetFailures(0, 0)
        same("afterRefusedUndo", before, rig.picture())
        step("undoSecond", true, e.undoStep())
        same("afterUndoSecond", one, rig.picture())
        step("redoSecond", true, e.redoStep())
        val redone = rig.picture()
        same("afterRedoSecond", two, redone)
        rig.canvas.sheetFailures(keeps = 0, engines = 1)
        step("redoThirdNoMemory", false, e.redoStep())
        rig.canvas.sheetFailures(0, 0)
        same("afterRefusedRedo", redone, rig.picture())
        step("redoThird", true, e.redoStep())
        same("afterRedoThird", three, rig.picture())
        return o.put("ok", ok)
    }

    private class Rig(paper: Paper, private val preset: BrushPreset, memory: () -> Pair<Long, Long>) {
        val engine = GlPaintEngine()
        val canvas = MediaCanvas(engine, memory)
        private var now = 1_000L
        init {
            engine.init()
            engine.onMediaRestored = { layer, plane, keys -> canvas.restored(layer, plane, keys) }
            engine.onLookWritten = { layer, plane, keys -> canvas.lookWritten(layer, plane, keys) }
            engine.setDocumentPaper(paper)
            engine.addLayer(LAYER)
            canvas.sheetLongCells = LONG_CELLS
            canvas.sheetView = UPRIGHT
        }

        /** One stroke, ended (its undo step) or given up; the water then runs a moment. Returns the refusal, if it was refused. */
        fun line(x0: Double, y0: Double, x1: Double, y1: Double, cancel: Boolean = false): String? {
            val n = 60
            val pts = List(n) { k -> val t = k / (n - 1.0); MediaSample(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, 0.8, 0.6, 0.4, k * 8.0) }
            canvas.begin(LAYER, preset, 0xFF1E5AC8.toInt(), null, pts[0].x, pts[0].y, now)?.let { return it }
            for (chunk in pts.chunked(SAMPLES_PER_FRAME)) { canvas.addSamples(chunk); now += FRAME_MS; canvas.frame(now) }
            if (cancel) canvas.cancel() else canvas.end()
            repeat(20) { now += FRAME_MS; canvas.frame(now) }
            return null
        }

        /** Where the layer shows paint, the sheet's wash included, as a save would hold it. */
        fun picture(): Map<Long, BooleanArray> = canvas.withSheetStamped {
            engine.tileKeys(LAYER).associateWith { k ->
                val px = engine.readTile(LAYER, k)
                if (px == null) BooleanArray(0) else BooleanArray(px.size / 4) { (px[it * 4 + 3].toInt() and 0xFF) > 8 }
            }
        }

        fun <T> use(body: (Rig) -> T): T = try { body(this) } finally {
            runCatching { canvas.release() }
            if (engine.ready) runCatching { engine.release() }
        }
    }

    private fun count(a: Map<Long, BooleanArray>): Int = a.values.sumOf { b -> b.count { it } }

    /** Pixels [a] shows that [b] does not. */
    private fun lost(a: Map<Long, BooleanArray>, b: Map<Long, BooleanArray>): Int =
        a.entries.sumOf { (k, x) -> val y = b[k]; x.indices.count { x[it] && (y == null || it >= y.size || !y[it]) } }
}

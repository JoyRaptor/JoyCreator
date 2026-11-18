package cc.joycreator.joybrush.android.bench

import android.os.Handler
import android.os.Looper
import android.util.Log
import cc.joycreator.joybrush.androidkit.BrushLibrary
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.androidkit.bench.StrokeReplay
import cc.joycreator.joybrush.androidkit.gl.media.WetSheet
import cc.joycreator.joybrush.androidkit.io.JbContents
import cc.joycreator.joybrush.androidkit.lines.LineEdit
import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_MEDIA
import cc.joycreator.joybrush.core.brush.MEDIUM_WET
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import org.json.JSONArray
import org.json.JSONObject

/**
 * A watercolour wash on a layer that holds an editable line (ATLAS-SOLID ledger C6, 2026-11-16), through the app's own
 * canvas on a throwaway drawing: an editable line is drawn with [line] (the brush the case was started with), then a wash
 * of the first built-in watercolour is painted across it on the SAME layer.
 *
 * Checked: the line is still a line (it used to be turned into paint first); the wash shows while wet; Dry now leaves
 * the line a line; recolouring the line and back redraws its tiles from the layer's items to the same picture (so the
 * wash is among them, not lost under the redraw); undo of the two recolours gives the dried picture back; undo of the
 * wash (one press) gives the line alone, redo straight after gives the dried picture exactly, with the wash among the
 * items again; undone once more, the line redraws without it. A save of the DRIED wash opens to the dried picture
 * exactly. A save taken while WET opens with the line a line and the wash among the items; its picture is held against
 * the reads just before and just after that save, since the water was moving.
 *
 * Not covered: an animation frame, a mask, moving or deleting the line under the wash, the wash's look next to a wash on
 * a plain layer, a second wash, memory running out during the print, and a real pen.
 */
class WashOverLinesBench(private val canvas: JbCanvasView, private val spec: BenchRunner.Spec, private val line: BrushPreset,
                         private val run: Int, private val next: () -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
    private val report = JSONObject().put("case", "washOverLinesProbe").put("brush", line.id).put("run", run)
    private val problems = JSONArray()
    private val refusals = ArrayList<String>()
    private val notices = ArrayList<String>()
    private var priorRefused: ((String) -> Unit)? = null
    private var priorNotice: ((String) -> Unit)? = null
    private var priorStrokeEnded: (() -> Unit)? = null
    private val sheetBefore = WetSheet.enabled
    private val linesBefore = canvas.editableLines
    private var finished = false
    private lateinit var lineOnly: JbCanvasView.BenchState
    private lateinit var wet: JbCanvasView.BenchState
    private lateinit var dried: JbCanvasView.BenchState
    private var wetSave: JbContents? = null
    private var driedSave: JbContents? = null
    private lateinit var wetAfter: JbCanvasView.BenchState
    private lateinit var unwashed: JbCanvasView.BenchState

    fun start() {
        priorRefused = canvas.onRefused; canvas.onRefused = { why -> refusals.add(why) }
        priorNotice = canvas.onNotice; canvas.onNotice = { what -> notices.add(what) }
        priorStrokeEnded = canvas.onStrokeEnded; canvas.onStrokeEnded = null
        canvas.benchWatch = true; canvas.benchErrors.clear()
        WetSheet.enabled = true
        canvas.editableLines = true   // the first stroke is kept as a line, as with the drawer's Editable lines switch on
        ui.postDelayed({ if (!finished) { problem("timed out"); finish() } }, 150_000L)
        canvas.benchFreshDrawing { later(300) { drawLine() } }
    }

    private fun drawLine() {
        canvas.preset = line
        stroke(0.2f, 0.8f, 0.45f, 0.55f, passes = 1) {
            fence("line") { st ->
                lineOnly = st
                if (onlyLine() == null) { problem("the first stroke was not kept as a line: ${canvas.lastLineRefusal}"); finish(); return@fence }
                wash()
            }
        }
    }

    private fun wash() {
        val water = BrushLibrary.builtIn().firstOrNull { it.engine == ENGINE_MEDIA && it.media?.medium == MEDIUM_WET }
            ?: run { problem("no built-in watercolour brush"); finish(); return }
        report.put("wash", water.id)
        canvas.preset = water
        stroke(0.3f, 0.7f, 0.3f, 0.7f, passes = 3) {
            later(1500) {
                fence("wet") { st ->
                    wet = st
                    val painted = MatrixDiff.count(lineOnly.visible(), st.visible()).px
                    report.put("washPx", painted).put("undoAfterWash", st.undoDepth - lineOnly.undoDepth)
                    if (onlyLine() == null) problem("after the wash the line is no longer a line (lines: ${lines()})")
                    if (notices.any { it.contains("became paint") }) problem("the lines were turned into paint: $notices")
                    if (refusals.isNotEmpty()) problem("the wash was refused: $refusals")
                    if (painted == 0) { problem("the wash painted nothing"); finish(); return@fence }
                    saveWet()
                }
            }
        }
    }

    private fun saveWet() {
        canvas.snapshot({ contents ->
            if (finished) return@snapshot
            wetSave = contents
            // The water runs on between the read before the save and this one; the save holds a moment between the two.
            fence("wet after the save") { st -> wetAfter = st; dry() }
        }, { why -> if (!finished) { problem("the save while wet failed: $why"); finish() } }, keepWet = true)
    }

    private fun dry() {
        canvas.dryWashNow()
        later(600) {
            fence("dried") { st ->
                dried = st
                report.put("driedVsWetPx", MatrixDiff.count(wet.visible(), st.visible()).px)
                if (onlyLine() == null) problem("after Dry now the line is no longer a line (lines: ${lines()})")
                if (MatrixDiff.same(lineOnly.visible(), st.visible())) problem("after Dry now the wash is gone")
                canvas.snapshot({ contents ->
                    if (finished) return@snapshot
                    driedSave = contents
                    recolourAndBack("dried", st) { undoTwice() }
                }, { why -> if (!finished) { problem("the save of the dried wash failed: $why"); finish() } })
            }
        }
    }

    /** The line's tiles redrawn from the layer's items, twice: the second redraw must be the picture we started from. */
    private fun recolourAndBack(what: String, from: JbCanvasView.BenchState, then: () -> Unit) {
        val (plane, record) = onlyLine() ?: run { problem("$what: no line to edit"); finish(); return }
        val other = if (record.colorArgb == OTHER) 0xFF356DAB.toInt() else OTHER
        edit(plane, record.copy(colorArgb = other), "$what recolour") { m ->
            edit(plane, record, "$what recolour back") { b ->
                val changed = MatrixDiff.count(from.visible(), m.visible()).px
                val off = MatrixDiff.count(from.visible(), b.visible()).px
                report.put("${what}RecolourPx", changed).put("${what}RedrawOffPx", off)
                if (changed == 0) problem("$what: recolouring the line changed nothing")
                if (off != 0) problem("$what: after recolour and back $off px differ (the wash is not among the layer's items)")
                if (b.undoDepth != from.undoDepth + 2) problem("$what: two edits were ${b.undoDepth - from.undoDepth} undo steps")
                then()
            }
        }
    }

    private fun undoTwice() {
        canvas.undo(); canvas.undo()
        later(400) {
            fence("undo edits") { st ->
                if (!MatrixDiff.same(dried.visible(), st.visible())) problem("undo of the two recolours is not the dried picture")
                undoWash { presses -> redoWash(presses) }
            }
        }
    }

    /**
     * Undo takes the dried wash away again (the print, and the stroke with it: one press), the line stays a line and the
     * picture is the line alone. [then] gets the number of presses it took.
     */
    private fun undoWash(round: String = "", then: (Int) -> Unit) {
        var presses = 0
        fun back() {
            canvas.undo(); presses++
            later(500) {
                fence("undo wash $presses") { st ->
                    if (st.undoDepth > lineOnly.undoDepth && presses < 4) { back(); return@fence }
                    report.put("undoPressesForWash$round", presses).put("undoWashOffPx$round", MatrixDiff.count(lineOnly.visible(), st.visible()).px)
                    if (st.undoDepth != lineOnly.undoDepth) problem("undo of the wash: Undo depth ${st.undoDepth}, the line alone was ${lineOnly.undoDepth}")
                    if (!MatrixDiff.same(lineOnly.visible(), st.visible())) problem("undo of the wash is not the line alone")
                    if (onlyLine() == null) problem("undo of the wash: the line is no longer a line (lines: ${lines()})")
                    unwashed = st
                    then(presses)
                }
            }
        }
        back()
    }

    /**
     * Redo straight after the undo, before anything else is done (a new edit would empty what there is to redo): the
     * dried picture is back, exactly, the wash among the layer's items again. Then it is undone once more and the line,
     * now without the wash, is redrawn from the items.
     */
    private fun redoWash(presses: Int) {
        var left = presses
        fun forward() {
            if (left == 0) {
                fence("redo wash") { st ->
                    val off = MatrixDiff.count(dried.visible(), st.visible()).px
                    report.put("redoWashOffPx", off).put("redoDepthOff", st.undoDepth - dried.undoDepth)
                    if (off != 0) problem("redo of the wash: $off px differ from the dried picture")
                    if (st.undoDepth != dried.undoDepth) problem("redo of the wash: Undo depth ${st.undoDepth}, the dried picture's was ${dried.undoDepth}")
                    if (onlyLine() == null) problem("redo of the wash: the line is no longer a line (lines: ${lines()})")
                    recolourAndBack("redone", st) {
                        canvas.undo(); canvas.undo()
                        later(400) { undoWash("Again") { recolourAndBack("unwashed", unwashed) { reopenWet() } } }
                    }
                }
                return
            }
            canvas.redo(); left--
            later(500) { fence("redo press") { forward() } }
        }
        forward()
    }

    /**
     * The save taken while the wash was wet, opened again. The wash was moving when it was saved, so the picture is held
     * against the reads just before and just after the save: it may differ from each by no more than they differ from each
     * other (plus a little), and the line is a line with the wash among the items.
     */
    private fun reopenWet() {
        val contents = wetSave ?: run { problem("no save to reopen"); finish(); return }
        canvas.load(contents) {
            later(500) {
                fence("reopened") { st ->
                    val before = MatrixDiff.count(wet.visible(), st.visible()).px
                    val after = MatrixDiff.count(wetAfter.visible(), st.visible()).px
                    val moved = MatrixDiff.count(wet.visible(), wetAfter.visible()).px
                    val wash = MatrixDiff.count(lineOnly.visible(), wet.visible()).px
                    report.put("reopenVsWetPx", before).put("reopenVsWetAfterPx", after).put("wetMovedAroundSavePx", moved).put("linesAfterReopen", lines())
                    if (onlyLine() == null) problem("reopened: the line did not come back as a line (lines: ${lines()})")
                    val allowed = moved + moved / 4 + wash / 100
                    if (before > allowed || after > allowed) problem("reopened wet save: $before and $after px differ from the wet picture before and after the save, which differ by $moved from each other (the wash is $wash px)")
                    recolourAndBack("reopened", st) { reopenDried() }
                }
            }
        }
    }

    /** The save of the dried wash, opened again: nothing was moving, so the picture must be the dried one exactly. */
    private fun reopenDried() {
        val contents = driedSave ?: run { problem("no dried save to reopen"); finish(); return }
        canvas.load(contents) {
            later(500) {
                fence("reopened dried") { st ->
                    val off = MatrixDiff.count(dried.visible(), st.visible()).px
                    report.put("reopenDriedOffPx", off).put("linesAfterReopenDried", lines())
                    if (off != 0) problem("reopened dried save: $off px differ from the dried picture")
                    if (onlyLine() == null) problem("reopened dried save: the line did not come back as a line (lines: ${lines()})")
                    recolourAndBack("reopenedDried", st) { finish() }
                }
            }
        }
    }

    // ---- plumbing ----

    private fun JbCanvasView.BenchState.visible(): Map<Long, ByteArray> = planes["visible"].orEmpty()
    private fun lines(): Int = canvas.lineSnapshot.planes.values.sumOf { it.size }
    private fun onlyLine(): Pair<LinePlane, StrokeRecord>? =
        canvas.lineSnapshot.planes.entries.firstNotNullOfOrNull { (plane, list) -> list.singleOrNull()?.let { plane to it } }

    private fun edit(plane: LinePlane, after: StrokeRecord, what: String, then: (JbCanvasView.BenchState) -> Unit) {
        canvas.submitLineEdit(LineEdit.Replace(listOf(AddressedStroke(plane.address(after.id), after)), emptyMap(), emptyMap())) { result ->
            if (finished) return@submitLineEdit
            if (!result.ok) { problem("$what refused: ${result.message}"); finish() } else fence(what, then)
        }
    }

    /** A scribble over part of the page ([x0]..[y1] as fractions of the view). */
    private fun stroke(x0: Float, x1: Float, y0: Float, y1: Float, passes: Int, then: () -> Unit) {
        val w = canvas.width.toFloat(); val h = canvas.height.toFloat()
        StrokeReplay.play(canvas, StrokeReplay.zigzag(x0 * w, x1 * w, y0 * h, y1 * h, passes, 600.0), null, onDown = {},
            onUp = { later(300) { then() } })
    }

    /** Reads the canvas behind everything queued so far (a wash still wet is read as a save made now would hold it). */
    private fun fence(what: String, then: (JbCanvasView.BenchState) -> Unit) {
        canvas.benchState { st, why ->
            if (finished) return@benchState
            if (st == null) { problem("$what: the canvas could not be read: $why"); finish() }
            else try { then(st) } catch (e: Exception) { problem("$what: $e"); finish() }
        }
    }

    private fun later(ms: Long, block: () -> Unit) { ui.postDelayed({ if (!finished) block() }, ms) }
    private fun problem(what: String) { if (problems.length() < 20) problems.put(what) }

    private fun finish() {
        if (finished) return
        finished = true
        WetSheet.enabled = sheetBefore
        canvas.editableLines = linesBefore
        canvas.onRefused = priorRefused; canvas.onNotice = priorNotice; canvas.onStrokeEnded = priorStrokeEnded
        if (canvas.benchErrors.isNotEmpty()) problem("canvas errors: ${canvas.benchErrors.toList()}")
        Log.i(BenchRunner.TAG, report.put("problems", problems).put("pass", problems.length() == 0).toString())
        ui.postDelayed({ next() }, spec.settleMs)
    }

    private companion object { const val OTHER = 0xFFB03A2E.toInt() }
}

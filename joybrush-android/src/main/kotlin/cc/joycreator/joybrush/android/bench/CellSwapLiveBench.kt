package cc.joycreator.joybrush.android.bench

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import cc.joycreator.joybrush.androidkit.BrushLibrary
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.androidkit.bench.StrokeReplay
import cc.joycreator.joybrush.core.brush.ENGINE_MEDIA
import cc.joycreator.joybrush.core.brush.MEDIUM_PASTE
import cc.joycreator.joybrush.core.doc.BoardDocumentOps
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.doc.RegionChange
import cc.joycreator.joybrush.core.doc.SpriteCellOps
import cc.joycreator.joybrush.core.doc.SpriteGrid
import org.json.JSONArray
import org.json.JSONObject

/**
 * Moved paint stays paint, in the real canvas (R2-JB-03, second half; the engine session's question: is the paint state
 * the edit reads the LIVE one, and does whoever holds a copy of the tiles write an old one back afterwards?).
 *
 * A throwaway drawing with a locked sprite board of two cells: oil is painted in the left cell, the cells are swapped at
 * once (no wait for the paint to be written back), oil is painted again on the cell the paint moved to, then everything
 * is walked back with Undo and forward with Redo.
 *
 * Checked: the paint is in the right cell after the swap and gone from the left; the right cell's tile HOLDS paint state
 * (before the fix it was a picture only); the second stroke paints and nothing is refused; Undo of that stroke gives
 * exactly the picture after the swap (an old copy written back over the moved paint would show here); Undo of the swap
 * gives exactly the picture before it; Redo of both gives the pictures back.
 *
 * Not covered: a save and reopen, watercolour, a real pen, more than one layer, an animation board.
 */
class CellSwapLiveBench(private val canvas: JbCanvasView, private val spec: BenchRunner.Spec, private val run: Int,
                        private val next: () -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
    private val report = JSONObject().put("case", "cellSwapLiveProbe").put("run", run)
    private val problems = JSONArray()
    private val refusals = ArrayList<String>()
    private var priorRefused: ((String) -> Unit)? = null
    private var priorStrokeEnded: (() -> Unit)? = null
    private var finished = false
    private lateinit var left: RectPx
    private lateinit var right: RectPx
    private lateinit var boardId: String
    private lateinit var empty: JbCanvasView.BenchState
    private lateinit var painted: JbCanvasView.BenchState
    private lateinit var swapped: JbCanvasView.BenchState
    private lateinit var again: JbCanvasView.BenchState

    fun start() {
        priorRefused = canvas.onRefused; canvas.onRefused = { why -> refusals.add(why) }
        priorStrokeEnded = canvas.onStrokeEnded; canvas.onStrokeEnded = null
        canvas.benchWatch = true; canvas.benchErrors.clear()
        ui.postDelayed({ if (!finished) { problem("timed out"); finish() } }, 170_000L)
        canvas.benchFreshDrawing { later(300) { board() } }
    }

    private fun board() {
        val x = ((canvas.pageWidth - 2 * CELL) / 2 / 8) * 8 + 24   // not on a tile edge
        val y = (canvas.pageHeight / 4 / 8) * 8 + 40
        left = RectPx(x, y, CELL, CELL); right = RectPx(x + CELL, y, CELL, CELL)
        var n = 0
        canvas.editBoards { d -> BoardDocumentOps.createSprite(d, "Swap probe", RectPx(x, y, 2 * CELL, CELL), SpriteGrid(2, 1, CELL, CELL), { "sw-${++n}" }) }
        fence("board") { st ->
            boardId = st.doc?.boards?.firstOrNull { it.kind == BoardKind.SPRITE }?.id ?: run { problem("the sprite board was not made: $refusals"); finish(); return@fence }
            canvas.editBoards { d -> RegionChange(BoardDocumentOps.setLocked(d, boardId, true)) }
            fence("lock") { e ->
                empty = e
                val oil = BrushLibrary.builtIn().firstOrNull { it.engine == ENGINE_MEDIA && it.media?.medium == MEDIUM_PASTE }
                    ?: run { problem("no built-in oil brush"); finish(); return@fence }
                report.put("brush", oil.id)
                canvas.preset = oil
                later(200) { stroke(left) { settle { afterFirst() } } }
            }
        }
    }

    private fun afterFirst() = fence("painted") { st ->
        painted = st
        val inLeft = MatrixDiff.count(empty.visible(), st.visible(), left).insidePx
        report.put("paintedLeftPx", inLeft)
        if (inLeft == 0) { problem("the first stroke painted nothing in the left cell: $refusals"); finish(); return@fence }
        canvas.benchPayloadAt(left.x + CELL / 2, left.y + CELL / 2) { has ->
            report.put("leftHasPaintStateBefore", has)
            if (!has) problem("setup: the painted cell holds no paint state")
            // At once: the paint is not given time to be written back first.
            canvas.editBoards { d -> SpriteCellOps.swap(d, boardId, 0, 1) }
            fence("swapped") { s -> afterSwap(s) }
        }
    }

    private fun afterSwap(st: JbCanvasView.BenchState) {
        swapped = st
        val inRight = MatrixDiff.count(empty.visible(), st.visible(), right).insidePx
        val inLeft = MatrixDiff.count(empty.visible(), st.visible(), left).insidePx
        report.put("swappedRightPx", inRight).put("swappedLeftPx", inLeft).put("swapUndoSteps", st.undoDepth - painted.undoDepth)
        if (inRight == 0) problem("after the swap the right cell holds no paint (refused: $refusals)")
        if (inLeft != 0) problem("after the swap $inLeft px of paint are still in the left cell")
        if (st.undoDepth != painted.undoDepth + 1) problem("the swap was ${st.undoDepth - painted.undoDepth} undo steps")
        canvas.benchPayloadAt(right.x + CELL / 2, right.y + CELL / 2) { has ->
            report.put("rightHasPaintStateAfter", has)
            if (!has) problem("the moved paint is a picture only: the right cell holds no paint state")
            refusals.clear()
            stroke(right) { settle { fence("again") { a -> afterSecond(a) } } }
        }
    }

    private fun afterSecond(st: JbCanvasView.BenchState) {
        again = st
        val added = MatrixDiff.count(swapped.visible(), st.visible()).px
        report.put("secondStrokePx", added).put("secondStrokeUndoSteps", st.undoDepth - swapped.undoDepth)
        if (added == 0) problem("the second stroke painted nothing on the moved paint (refused: $refusals)")
        if (refusals.isNotEmpty()) problem("refused: $refusals")
        walk(listOf("undo stroke" to swapped, "undo swap" to painted), { canvas.undo() }) {
            walk(listOf("redo swap" to swapped, "redo stroke" to again), { canvas.redo() }) { finish() }
        }
    }

    private fun walk(steps: List<Pair<String, JbCanvasView.BenchState>>, press: () -> Unit, then: () -> Unit) {
        fun step(i: Int) {
            if (i == steps.size) { then(); return }
            val (name, want) = steps[i]
            press()
            later(500) {
                settle {
                    fence(name) { st ->
                        val off = MatrixDiff.count(want.visible(), st.visible()).px
                        report.put(name.replace(' ', '_') + "_offPx", off)
                        if (off != 0) problem("$name: $off px differ from the picture it should give back")
                        step(i + 1)
                    }
                }
            }
        }
        step(0)
    }

    // ---- plumbing ----

    private fun JbCanvasView.BenchState.visible(): Map<Long, ByteArray> = planes["visible"].orEmpty()

    /** A scribble well inside [cell]. */
    private fun stroke(cell: RectPx, then: () -> Unit) {
        val a = canvas.view.docToScreen(cell.x + 0.3f * CELL, cell.y + 0.3f * CELL)
        val b = canvas.view.docToScreen(cell.x + 0.7f * CELL, cell.y + 0.7f * CELL)
        StrokeReplay.play(canvas, StrokeReplay.zigzag(a.first, b.first, a.second, b.second, 3, 500.0), null, onDown = {},
            onUp = { later(200) { then() } })
    }

    private fun settle(then: () -> Unit) {
        val t0 = SystemClock.uptimeMillis()
        var calm = 0
        fun poll() {
            canvas.benchState(readPixels = false) { st, _ ->
                if (finished) return@benchState
                calm = if (st?.idle == true && !canvas.strokeInProgress) calm + 1 else 0
                if (calm >= 2 || SystemClock.uptimeMillis() - t0 > 12_000L) then()
                else ui.postDelayed({ if (!finished) poll() }, 250)
            }
        }
        poll()
    }

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
        canvas.onRefused = priorRefused; canvas.onStrokeEnded = priorStrokeEnded
        if (canvas.benchErrors.isNotEmpty()) problem("canvas errors: ${canvas.benchErrors.toList()}")
        Log.i(BenchRunner.TAG, report.put("problems", problems).put("pass", problems.length() == 0).toString())
        ui.postDelayed({ next() }, spec.settleMs)
    }

    private companion object { const val CELL = 160 }
}

package cc.joycreator.joybrush.android.bench

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import cc.joycreator.joybrush.androidkit.BrushLibrary
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.androidkit.bench.StrokeReplay
import cc.joycreator.joybrush.androidkit.io.JbArchive
import cc.joycreator.joybrush.androidkit.io.JbContents
import cc.joycreator.joybrush.core.brush.ENGINE_MEDIA
import cc.joycreator.joybrush.core.brush.MEDIUM_PASTE
import cc.joycreator.joybrush.core.doc.AnimationBoardOps
import cc.joycreator.joybrush.core.doc.BoardDocumentOps
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.doc.RegionChange
import cc.joycreator.joybrush.core.doc.SpriteCellOps
import cc.joycreator.joybrush.core.doc.SpriteGrid
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Moved paint stays paint, in the real canvas, and survives a save and a reopen (R2-JB-03, second half; the engine
 * session's question: is the paint state the edit reads the LIVE one, and does whoever holds a copy of the tiles write an
 * old one back afterwards? and the orchestrator's: is the paint state still on the moved pixels after the file is read
 * again?).
 *
 * A throwaway drawing. [drag] false: a locked sprite board of two cells, oil painted in the left cell, the cells swapped
 * at once (no wait for the paint to be written back). [drag] true: an animation board with oil painted in its frame,
 * dragged right and down onto part of itself. Then oil is painted again on the moved paint, everything is walked back
 * with Undo and forward with Redo, the drawing is saved to a file of its own, the file is opened the way the screen
 * opens one (JbArchive.openDeferringMedia) and put on the canvas.
 *
 * Checked: the paint is where it was moved to and nowhere else; that place HOLDS paint state (before the fix it was a
 * picture only); the second stroke paints and nothing is refused; Undo of that stroke gives exactly the picture after the
 * move (an old copy written back over the moved paint would show here); Undo of the move gives exactly the picture
 * before it; Redo of both gives the pictures back; after the save and the reopen the picture is exactly the same, the
 * moved place still holds paint state, and a second save holds the same picture tiles and the same paint-state tiles,
 * byte for byte, as the first.
 *
 * Not covered: watercolour, a real pen, more than one layer, a mask, painting on after the reopen, the screen's own
 * autosave and recovery copy (the lifecycle gate covers those for paint that was not moved).
 */
class CellSwapLiveBench(private val canvas: JbCanvasView, private val spec: BenchRunner.Spec, private val run: Int,
                        private val drag: Boolean, private val next: () -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
    private val case = if (drag) "boardDragLiveProbe" else "cellSwapLiveProbe"
    private val report = JSONObject().put("case", case).put("run", run)
    private val problems = JSONArray()
    private val refusals = ArrayList<String>()
    private var priorRefused: ((String) -> Unit)? = null
    private var priorStrokeEnded: (() -> Unit)? = null
    private var finished = false
    private lateinit var from: RectPx
    private lateinit var to: RectPx
    private lateinit var boardId: String
    private lateinit var empty: JbCanvasView.BenchState
    private lateinit var painted: JbCanvasView.BenchState
    private lateinit var moved: JbCanvasView.BenchState
    private lateinit var again: JbCanvasView.BenchState
    private var spillPx = 0

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
        var n = 0
        if (drag) {
            from = RectPx(x, y, 200, 168); to = RectPx(x + 120, y + 140, 200, 168)   // overlaps where it was
            canvas.editBoards { d -> BoardDocumentOps.createAnimation(d, "Drag probe", from, { "dg-${++n}" }) }
        } else {
            from = RectPx(x, y, CELL, CELL); to = RectPx(x + CELL, y, CELL, CELL)
            canvas.editBoards { d -> BoardDocumentOps.createSprite(d, "Swap probe", RectPx(x, y, 2 * CELL, CELL), SpriteGrid(2, 1, CELL, CELL), { "sw-${++n}" }) }
        }
        fence("board") { st ->
            val kind = if (drag) BoardKind.ANIMATION else BoardKind.SPRITE
            boardId = st.doc?.boards?.firstOrNull { it.kind == kind }?.id ?: run { problem("the board was not made: $refusals"); finish(); return@fence }
            if (!drag) canvas.editBoards { d -> RegionChange(BoardDocumentOps.setLocked(d, boardId, true)) }
            fence("ready") { e ->
                empty = e
                val oil = BrushLibrary.builtIn().firstOrNull { it.engine == ENGINE_MEDIA && it.media?.medium == MEDIUM_PASTE }
                    ?: run { problem("no built-in oil brush"); finish(); return@fence }
                report.put("brush", oil.id)
                canvas.preset = oil
                later(200) { stroke(from) { settle { afterFirst() } } }
            }
        }
    }

    private fun afterFirst() = fence("painted") { st ->
        painted = st
        val c = MatrixDiff.count(empty.visible(), st.visible(), from)
        // Paint the stroke laid outside the place that is moved (beyond the board's edge) stays where it is.
        spillPx = c.outsidePx
        report.put("paintedPx", c.insidePx).put("paintedOutsidePx", spillPx)
        if (c.insidePx == 0) { problem("the first stroke painted nothing where it was aimed: $refusals"); finish(); return@fence }
        canvas.benchPayloadAt(from.x + from.w / 2, from.y + from.h / 2) { has ->
            report.put("hasPaintStateBefore", has)
            if (!has) problem("setup: the painted place holds no paint state")
            // At once: the paint is not given time to be written back first.
            if (drag) canvas.editBoards { d -> AnimationBoardOps.moveAll(d, boardId, to.x, to.y) }
            else canvas.editBoards { d -> SpriteCellOps.swap(d, boardId, 0, 1) }
            fence("moved") { s -> afterMove(s) }
        }
    }

    private fun afterMove(st: JbCanvasView.BenchState) {
        moved = st
        val c = MatrixDiff.count(empty.visible(), st.visible(), to)
        report.put("movedPx", c.insidePx).put("leftBehindPx", c.outsidePx).put("moveUndoSteps", st.undoDepth - painted.undoDepth)
        if (c.insidePx == 0) problem("after the move there is no paint where it was moved to (refused: $refusals)")
        if (c.outsidePx != spillPx) problem("after the move ${c.outsidePx} px of paint are outside the place it was moved to; $spillPx were laid outside to begin with")
        if (c.insidePx != MatrixDiff.count(empty.visible(), painted.visible(), from).insidePx) problem("the moved place holds ${c.insidePx} px of paint, not what was painted")
        if (st.undoDepth != painted.undoDepth + 1) problem("the move was ${st.undoDepth - painted.undoDepth} undo steps")
        canvas.benchPayloadAt(to.x + to.w / 2, to.y + to.h / 2) { has ->
            report.put("hasPaintStateAfter", has)
            if (!has) problem("the moved paint is a picture only: it holds no paint state")
            refusals.clear()
            stroke(to) { settle { fence("again") { a -> afterSecond(a) } } }
        }
    }

    private fun afterSecond(st: JbCanvasView.BenchState) {
        again = st
        val added = MatrixDiff.count(moved.visible(), st.visible()).px
        report.put("secondStrokePx", added).put("secondStrokeUndoSteps", st.undoDepth - moved.undoDepth)
        if (added == 0) problem("the second stroke painted nothing on the moved paint (refused: $refusals)")
        if (refusals.isNotEmpty()) problem("refused: $refusals")
        walk(listOf("undo stroke" to moved, "undo move" to painted), { canvas.undo() }) {
            walk(listOf("redo move" to moved, "redo stroke" to again), { canvas.redo() }) { saveAndReopen() }
        }
    }

    // ---- the file: saved, opened as the screen opens one, put on the canvas, saved again ----

    private fun saveAndReopen() {
        val file = File(canvas.context.cacheDir, "moved-paint-$case.joybrush")
        canvas.snapshot({ first ->
            if (finished) return@snapshot
            try {
                report.put("savedPictureTiles", first.tiles.size).put("savedPaintStateTiles", first.mediaTiles.size)
                if (first.mediaTiles.isEmpty()) problem("the save holds no paint state at all")
                JbArchive.save(file, first)
                report.put("fileBytes", file.length())
                val opened = JbArchive.openDeferringMedia(file)
                canvas.load(opened) {
                    later(500) {
                        fence("reopened") { st ->
                            val off = MatrixDiff.count(again.visible(), st.visible()).px
                            report.put("reopenedOffPx", off)
                            if (off != 0) problem("reopened: $off px differ from the picture that was saved")
                            canvas.benchPayloadAt(to.x + to.w / 2, to.y + to.h / 2) { has ->
                                report.put("hasPaintStateReopened", has)
                                if (!has) problem("reopened: the moved paint holds no paint state")
                                canvas.snapshot({ second ->
                                    if (finished) return@snapshot
                                    val pictures = differing(first.tiles, second.tiles)
                                    val state = differing(first.mediaTiles, second.mediaTiles)
                                    report.put("resavedPictureTilesDiffering", pictures).put("resavedPaintStateTilesDiffering", state)
                                        .put("resavedPaintStateTiles", second.mediaTiles.size)
                                    if (pictures != 0) problem("after the reopen $pictures picture tiles are not what was saved")
                                    if (state != 0) problem("after the reopen $state paint-state tiles are not what was saved")
                                    file.delete()
                                    finish()
                                }, { why -> problem("the second save failed: $why"); file.delete(); finish() })
                            }
                        }
                    }
                }
            } catch (e: Exception) { problem("save and reopen: $e"); file.delete(); finish() }
        }, { why -> problem("the save failed: $why"); finish() })
    }

    /** How many tiles are in one map and not the other, or hold other bytes. An all-clear tile is the same as none. */
    private fun <K> differing(a: Map<K, ByteArray>, b: Map<K, ByteArray>): Int = (a.keys + b.keys).count { k ->
        val x = a[k]; val y = b[k]
        if (x != null && y != null) !x.contentEquals(y) else (x ?: y)!!.any { it != 0.toByte() }
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

    /** A scribble well inside [place]. */
    private fun stroke(place: RectPx, then: () -> Unit) {
        val a = canvas.view.docToScreen(place.x + 0.3f * place.w, place.y + 0.3f * place.h)
        val b = canvas.view.docToScreen(place.x + 0.7f * place.w, place.y + 0.7f * place.h)
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

package cc.joycreator.joybrush.core.sprite

import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.SpriteGrid
import kotlin.math.abs

/**
 * The sprite board's STATE and the questions the UI asks it. Every grid number in this file comes out
 * of [SpriteGridMath]; none of it is computed here.
 *
 * Two invariants, and both of them are the reason this class exists at all:
 *
 *  1. **A board that has just had its grid changed is `fitRect`'d, always** (Decision 2). The board's
 *     rect IS the grid afterwards, so an export of "this board" is the grid and not the old rect with a
 *     sliver hanging off it.
 *  2. **Nothing here mutates.** Every function returns a new `Board` or a new `SpriteGrid`; the undo
 *     stack holds documents, and a view that mutated one would be a view the undo stack could not
 *     describe (Decision 13).
 *
 * The hit test takes DOCUMENT px, and the one screen number it needs — the zoom — is named
 * `screenPerDoc` and used for exactly one thing: sizing a touch tolerance. See Decision 5.
 *
 * It draws nothing, holds no roll, no badge and no selection, and there is no gesture state machine
 * here: deciding "is this finger on a handle or on a cell" needs a finger, and the one thing this row
 * does own — which handle, and how far — is [edgeAt] and [dragged]. The view that draws them is the
 * view half of JB-4.01, which is ⛔ until JB-2.01 lands.
 */
class SpriteBoard(val board: Board, val density: Float = 1f) {

    /**
     * The board with [grid] written into it, and NOTHING else touched — the rect stays where it was.
     *
     * This is how a board is made STALE on purpose, which is the only way to see the spare strip
     * (Decision 9): [fitted] is the way back from here. Splitting the two is deliberate — a caller
     * that wants both writes [grid] and hands the result to [fitted].
     *
     * Refuses a board that is not a SPRITE board, in words, naming the kind (Cut Decision C5): a grid
     * on a CANVAS board is a document `DocOps.validate` rule 5 will complain about, and saying so at
     * the moment it is written beats saying it at save time.
     */
    fun withGrid(grid: SpriteGrid): Board {
        require(board.kind == BoardKind.SPRITE) {
            "board \"${board.id}\" is a ${board.kind} board, and only a SPRITE board has a grid"
        }
        return board.copy(grid = grid)
    }

    /**
     * The board with [grid] written into it AND resized to hold that grid EXACTLY —
     * [SpriteGridMath.fitRect], applied.
     *
     * Both halves, on purpose. Writing the grid without resizing is [withGrid]; resizing to a grid
     * this board does not hold would leave a board whose rect and whose grid disagree, and that is a
     * document nothing can describe. So this is the one that satisfies invariant 1 above.
     */
    fun fitted(grid: SpriteGrid): Board = board.copy(
        grid = grid,
        rect = SpriteGridMath.fitRect(board.rect, grid),
    )

    // ---- the two ways a grid is set ----

    /**
     * "Cells of N px": [SpriteGridMath.bySize] over [board]'s rect, as many WHOLE cells as fit. A
     * partial cell at the right or bottom edge is not a cell anybody can draw a sprite in, so it is
     * not a cell, and what is left over stays outside the grid until [fitted] removes it.
     */
    fun bySize(cellW: Int, cellH: Int): SpriteGrid = SpriteGridMath.bySize(board.rect, cellW, cellH)

    /**
     * "N columns × M rows": [SpriteGridMath.byCount] over [board]'s rect. The leftover (2 px of board
     * for 3 columns across 500) stays OUTSIDE the grid until [fitted] removes it.
     */
    fun byCount(cols: Int, rows: Int): SpriteGrid = SpriteGridMath.byCount(board.rect, cols, rows)

    /**
     * The `−`/`+` stepper's arithmetic (R36 Q2), for the stepper the view half will draw: one DOCUMENT
     * px on a cell edge, or one whole column / row on a count. Both go straight back through [bySize]
     * / [byCount], so a step of `+1` on a count is `byCount(cols + 1, rows)` and nothing else.
     *
     * The step is a DOCUMENT unit and there is no density in it (Decision 10): density is a touch
     * target's number and this is a value. It is PROVISIONAL — the Lead owns the touch feel, and
     * only this function and the four tests that pin it move if the step becomes 8.
     */
    fun stepped(axis: Axis, delta: Int): SpriteGrid {
        val grid = board.grid ?: throw IllegalArgumentException(
            "board \"${board.id}\" has no grid, so there is no value to step"
        )
        return when (axis) {
            Axis.CELL_W -> bySize(grid.cellW + delta, grid.cellH)
            Axis.CELL_H -> bySize(grid.cellW, grid.cellH + delta)
            Axis.COLS -> byCount(grid.cols + delta, grid.rows)
            Axis.ROWS -> byCount(grid.cols, grid.rows + delta)
        }
    }

    /** What a stepper step changes. Two axes, because a cell has two edges and a count has two numbers. */
    enum class Axis { CELL_W, CELL_H, COLS, ROWS }

    // ---- hit testing ----

    /**
     * Screen px a finger-down may be from the grid's right edge, bottom edge or corner and still grab
     * it. 22 dp × density, at the use site (R32).
     */
    val edgeGrabPx: Float get() = EDGE_GRAB_PX_DP * density

    /**
     * Which handle a finger-down at document ([xDoc], [yDoc]) has hold of, or null.
     *
     * DOCUMENT px — the view divides by its zoom once, at its own edge, and this layer never sees a
     * screen number (Decision 5).
     *
     * [screenPerDoc] is `ViewTransform.zoom`, screen px per doc px. It is used for ONE thing and one
     * thing only: the grab depth is a TOUCH distance, so it is compared in screen px —
     * `|xDoc - gridRight| * screenPerDoc <= edgeGrabPx`. **Pass the zoom, never its inverse.**
     *
     * The lines are the GRID's and not the board's (Decision 3), so the extent is asked of
     * [SpriteGridMath.fitRect] — the maths already knows the grid's size, including its clamps, and a
     * `cols * cellW` written here would be a second copy of a rule that already exists.
     *
     * The zone is TWO-SIDED and the CORNER is checked first (Decision 4), so a finger is wider than
     * a 1 px line and a finger at the corner is unambiguous by construction rather than by a tie-break.
     *
     * Non-finite input, a non-positive or non-finite [screenPerDoc], or a board with no grid → null.
     */
    fun edgeAt(xDoc: Float, yDoc: Float, screenPerDoc: Float): SpriteGridMath.Edge? {
        if (!xDoc.isFinite() || !yDoc.isFinite()) return null
        if (!screenPerDoc.isFinite() || screenPerDoc <= 0f) return null
        val grid = board.grid ?: return null
        // In Double, so a grid at the far edge of an unbounded canvas cannot overflow on the way to
        // being compared (LEAD R19's shape, on the one sum this function owns).
        val gridRect = SpriteGridMath.fitRect(board.rect, grid)
        val right = gridRect.x.toDouble() + gridRect.w.toDouble()
        val bottom = gridRect.y.toDouble() + gridRect.h.toDouble()
        val toRight = abs(xDoc.toDouble() - right) * screenPerDoc
        val toBottom = abs(yDoc.toDouble() - bottom) * screenPerDoc
        val grab = edgeGrabPx.toDouble()
        return when {
            toRight <= grab && toBottom <= grab -> SpriteGridMath.Edge.CORNER
            toRight <= grab -> SpriteGridMath.Edge.RIGHT
            toBottom <= grab -> SpriteGridMath.Edge.BOTTOM
            else -> null
        }
    }

    /** Which cell a finger-down is on, in document px — [SpriteGridMath.cellAt] verbatim. */
    fun cellAt(xDoc: Float, yDoc: Float): Int = SpriteGridMath.cellAt(board, xDoc, yDoc)

    // ---- resizing by dragging an edge ----

    /**
     * The board and grid after a drag by ([dxDoc], [dyDoc]) DOCUMENT px on [edge], then `fitRect`'d.
     * [SpriteGridMath.dragEdge] splits the delta over the cells and rounds ties away from zero; this
     * class adds nothing.
     *
     * BOTH numbers are passed whichever edge is held, and the maths decides which one counts
     * (Decision 6). A view that pre-zeroed the other axis would be a second implementation of that
     * rule in a place with no tests. A drag that would take a cell below 1 px is NOT refused here: the
     * clamp is in the maths, it is tested there, and a second refusal would be a second answer to one
     * question (Decision 7).
     */
    fun dragged(edge: SpriteGridMath.Edge, dxDoc: Float, dyDoc: Float): Pair<Board, SpriteGrid> {
        val grid = board.grid ?: throw IllegalArgumentException(
            "board \"${board.id}\" has no grid, so there is no edge to drag"
        )
        val dragged = SpriteGridMath.dragEdge(grid, edge, dxDoc, dyDoc)
        return fitted(dragged) to dragged
    }

    // ---- sub-grid ----

    /**
     * The sub-grid lines in document px — [SpriteGridMath.subGridLines] verbatim. Empty for `div` < 2
     *  or `div` > 8, and empty for a board with no grid. "Off" IS an empty list; nothing is clamped.
     *
     * The 2..8 range is `private` in [SpriteGridMath] and that is the point: a `subGridDiv` here
     * would be a second copy of two constants nobody else can see drift, and J1 is what would go red
     * (Decision 1). A `div` of 1 is "off", never a line down the middle of every cell (Decision 11).
     */
    fun subGridLines(div: Int): List<FloatArray> = SpriteGridMath.subGridLines(board, div)

    // ---- "used", DERIVED and never stored (R36 Q1) ----

    /**
     * Which of [cells] hold anything, by index, reading straight RGBA8.
     *
     * A cell is used when at least one of its pixels has alpha above 0. This is a *definition*, not a
     * cache: the document has no per-cell flag to go stale (Decision 8), so the cost is one pass over
     * the cells a caller already has, and the answer is the truth about those pixels rather than a
     * record of what somebody drew once.
     *
     * Cells need not be the same size as each other and a short cell is not an error: it is used if
     * any of the bytes it does have is non-zero in the alpha position.
     *
     * The index returned is the index in [cells] — reading order, the order the packer walks — and
     * nothing about this function knows how big the board's grid is. That it needs no renderer, no GL
     * engine and no file is the whole of the answer to "which cells hold art": the caller has the
     * bytes already, and asking again is cheaper than keeping a second truth.
     */
    fun usedCellsOf(cells: List<ByteArray>): Set<Int> {
        val used = LinkedHashSet<Int>()
        for (index in cells.indices) {
            val bytes = cells[index]
            // RGBA8: alpha is every fourth byte, at offset 3. An opaque colour with alpha 0 is not
            // ink, so only the alpha position is read — and only a COMPLETE pixel is a pixel, which
            // is why the walk stops when the last three bytes are not there.
            var p = 3
            while (p < bytes.size) {
                if (bytes[p] != 0.toByte()) {
                    used.add(index)
                    break
                }
                p += 4
            }
        }
        return used
    }

    companion object {
        /**
         * Grab depth in DP, multiplied by density at the use site (R32). See Decision 5 and Q1.
         *
         * The ONLY number this class publishes. It is a touch distance, not a grid rule, and it is the
         * only `const` here on purpose: J1 fails the moment a second constant — an `Int` cap, a sub-grid
         * range — appears, because a copy of a rule is the one failure nothing else in the file can
         * catch.
         */
        const val EDGE_GRAB_PX_DP: Float = 22f
    }
}

/**
 * The intent vocabulary of the grid gesture, so a view cannot invent a fourth gesture and so the
 * *names* of the two things this row owns are settled in one place.
 *
 * It is a sealed type on purpose: a board that took a `String` would accept "tap", "longpress",
 * "drag", "pinch" and whatever the next view invents, and a gesture a person cannot predict is a
 * gesture that resizes their sheet by accident. The `fun interface` adapters a view needs to turn
 * these into callbacks are the view half's, not this file's.
 */
sealed class GridGesture {
    /** A finger landed, on no handle and on no cell. The view's own signal; the board adds nothing. */
    object Begin : GridGesture()

    /** A cell was tapped, by index. */
    data class CellTapped(val index: Int) : GridGesture()

    /** A long-press on a cell. Never fires with [CellTapped] — Cut Decision C3. */
    data class CellLongPressed(val index: Int) : GridGesture()

    /** The finger has hold of [edge] and is dragging it. */
    data class EdgeGrabbed(val edge: SpriteGridMath.Edge) : GridGesture()

    /** A second finger, or a lift that took no handle. The gesture is over and nothing is applied. */
    object Cancel : GridGesture()
}

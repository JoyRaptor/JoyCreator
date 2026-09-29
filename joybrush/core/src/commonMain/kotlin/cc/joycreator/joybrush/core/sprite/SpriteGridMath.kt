package cc.joycreator.joybrush.core.sprite

import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.doc.SpriteGrid
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The sprite board's grid, as arithmetic: cells of a chosen size, cells of a chosen count, which
 * cell is under a finger, and what happens when the edge is dragged. The board UI is JB-4.01; this
 * is the part of it that can be proved on a PC.
 *
 * Everything here is PURE arithmetic over the document model ([Board], [SpriteGrid], [RectPx]) and
 * writes nothing: no file, no clock, no random source. That is what lets the whole grid be tested
 * without a screen, a pen or a phone.
 *
 * The three facts everything else follows from:
 *
 *  1. **The grid starts at the board rect's top-left**, and cells are WHOLE pixels. A sprite sheet
 *     is pixel exact — a cell of 63.5 px would make "which cell is this" a floating point question
 *     with a different answer in the renderer than in the packer. Board rects may sit at a negative
 *     x or y (the canvas is unbounded), which the whole of this file handles and nothing here
 *     assumes an origin of zero.
 *  2. **A grid is a [SpriteGrid] of exactly `cols` x `rows` cells**, and the board may be BIGGER
 *     than the grid: the spare strip on the right and bottom is simply outside the grid. A cell
 *     never straddles the board's edge.
 *  3. **Reading order is row-major, and it is the packer's order** — cell `i` is column `i % cols`,
 *     row `i / cols`, which is what `SpritePacker.pack` writes at `left = (i % cols) * cellW` and
 *     `top = (i / cols) * cellH`. [cellRect] and [cellAt] use that formula rather than a second
 *     one, so the board a person draws in and the sheet that comes out cannot disagree about where
 *     cell 5 is.
 *
 * Nothing is thrown for bad input: sizes and counts below 1 become 1, and a cell bigger than the
 * board gives one column or row. A grid the person cannot draw in is a bad answer; a crash on the
 * way to a clamped answer is a worse one.
 */
object SpriteGridMath {

    /**
     * The most cells one sheet may hold.
     *
     * Not an arbitrary number: beyond a few thousand cells a sheet is bigger than the memory a
     * phone will hand it, and `SpritePacker` refuses a sheet whose pixel count does not fit in an
     * array anyway. Clamping here means the board cannot be drawn into a state that export would
     * then refuse.
     */
    const val MAX_CELLS = 4096

    /** The largest cell edge, in pixels, for the same reason [MAX_CELLS] is the largest cell count. */
    const val MAX_CELL_PX = 4096

    /** A sub-grid finer than this is noise; coarser than [MAX_SUB_GRID_DIV] and it is a grid, not a sub-grid. */
    private const val MIN_SUB_GRID_DIV = 2

    /** Above this the lines are closer together than a finger is wide. */
    private const val MAX_SUB_GRID_DIV = 8

    // ---------- making a grid ----------

    /**
     * A grid of cells of exactly [cellW] x [cellH], as many whole ones as fit [rect].
     *
     * "As many as FIT" is the whole behaviour: `bySize(RectPx(0, 0, 512, 256), 100, 100)` is 5 x 2
     * and not 6 x 3, because a partial cell at the right and bottom edge is not a cell anybody can
     * draw a sprite in. What is left over stays outside the grid.
     *
     * @param cellW cell width in px, clamped to 1..[MAX_CELL_PX].
     * @param cellH cell height in px, clamped the same way.
     *
     * Throws nothing: a cell wider than the board yields one column, not an error.
     */
    fun bySize(rect: RectPx, cellW: Int, cellH: Int): SpriteGrid {
        val w = cellWOf(cellW)
        val h = cellHOf(cellH)
        val cols = colsOf(wholeCells(rect.w, w))
        val rows = rowsOf(wholeCells(rect.h, h), cols)
        return SpriteGrid(cols = cols, rows = rows, cellW = w, cellH = h)
    }

    /**
     * A grid of exactly [cols] x [rows] cells over [rect], each `rect.w / cols` by `rect.h / rows`
     * px floored (and never below 1 px).
     *
     * The floor is where the leftover goes: three columns across 500 px is 166 px each and 2 px of
     * board outside the grid, which is the same "the board may be bigger than the grid" rule
     * [bySize] obeys. [fitRect] is the way back from this grid to the rect it really covers.
     *
     * @param cols clamped to 1..[MAX_CELLS]; @param rows clamped so `cols * rows <= MAX_CELLS`.
     */
    fun byCount(rect: RectPx, cols: Int, rows: Int): SpriteGrid {
        val c = colsOf(cols)
        val r = rowsOf(rows, c)
        // `cellW >= 1`, so this division cannot throw and its floor is an Int floor for a
        // non-negative extent; a negative extent clamps to a 1 px cell with it.
        val w = cellWOf(rect.w / c)
        val h = cellHOf(rect.h / r)
        return SpriteGrid(cols = c, rows = r, cellW = w, cellH = h)
    }

    /**
     * The rect that holds [grid] EXACTLY, at [rect]'s top-left.
     *
     * This is what a board's rect becomes after [byCount]: the drawn area grew by a column, and
     * the rect follows it so that a later export is the grid and not the old rect with a sliver of
     * grid hanging off it.
     */
    fun fitRect(rect: RectPx, grid: SpriteGrid): RectPx {
        val cols = colsOf(grid.cols)
        val rows = rowsOf(grid.rows, cols)
        return RectPx(x = rect.x, y = rect.y, w = cols * cellWOf(grid.cellW), h = rows * cellHOf(grid.cellH))
    }

    // ---------- where a cell is ----------

    /**
     * Cell [index] in READING order (row-major), in document px.
     *
     * The same formula `SpritePacker.pack` lays pixels out with, which is the point of the method
     * existing at all.
     *
     * @throws IllegalArgumentException if the board has no grid, or [index] is not a cell the grid
     *   holds. Both are bugs rather than states: the grid of a sprite board is validated at save
     *   time (`DocOps` rule 5), and asking for a cell that is not there is a wrong index, not a
     *   drawing the person made.
     */
    fun cellRect(board: Board, index: Int): RectPx {
        val grid = board.grid
            ?: throw IllegalArgumentException(
                "board \"${board.id}\" has no grid, so cell $index is a cell that does not exist"
            )
        val cols = colsOf(grid.cols)
        val rows = rowsOf(grid.rows, cols)
        val cellW = cellWOf(grid.cellW)
        val cellH = cellHOf(grid.cellH)
        val last = cols * rows - 1
        require(index in 0..last) { "cell $index is outside a ${cols}x$rows grid, which holds 0..$last" }
        val col = index % cols
        val row = index / cols
        return RectPx(
            x = board.rect.x + col * cellW,
            y = board.rect.y + row * cellH,
            w = cellW,
            h = cellH,
        )
    }

    /**
     * The index of the cell under a document point, or -1.
     *
     * Cells are HALF-OPEN: `x` in `[left, left + cellW)`. So the pixel at the top-left of a cell
     * belongs to that cell and the first pixel of the NEXT cell belongs to the next one, which is
     * what makes `cellAt(cellRect(i).topLeft) == i` for every `i` — the property a hit test is
     * judged by.
     *
     * -1 means "not on the grid": outside the board rect, on the spare strip right of or below the
     * grid, or a point that is not a number at all. A pen that reports NaN during a lift must not
     * select a cell.
     */
    fun cellAt(board: Board, x: Float, y: Float): Int {
        val grid = board.grid ?: return -1
        if (!x.isFinite() || !y.isFinite()) return -1
        val cols = colsOf(grid.cols)
        val rows = rowsOf(grid.rows, cols)
        val cellW = cellWOf(grid.cellW)
        val cellH = cellHOf(grid.cellH)
        // Offsets from the grid's top-left. Both are non-negative from here, and the cell edges are
        // at least 1, so neither division can be 0/0 — a huge point saturates an Int in `.toInt()`
        // and then fails the range test below, which is the -1 it should have been.
        val fromLeft = x - board.rect.x.toFloat()
        val fromTop = y - board.rect.y.toFloat()
        if (fromLeft < 0f || fromTop < 0f) return -1
        val col = floor(fromLeft / cellW).toInt()
        val row = floor(fromTop / cellH).toInt()
        if (col >= cols || row >= rows) return -1
        return row * cols + col
    }

    // ---------- dragging an edge ----------

    /**
     * Which edge of the grid a drag has hold of.
     *
     * RIGHT is the vertical edge at the right-hand side (it moves the cell WIDTH), BOTTOM is the
     * horizontal edge along the bottom (it moves the cell HEIGHT), and CORNER is the handle where
     * they meet. Named for the part of the grid that moves, so a caller cannot get the axis and the
     * delta crossed over.
     */
    enum class Edge { RIGHT, BOTTOM, CORNER }

    /**
     * The grid after its right / bottom edge (or corner) is dragged by ([dx], [dy]) document px.
     *
     * Every cell grows by `dx / cols` and `dy / rows`, because a drag moves the OUTER edge of the
     * whole grid: dragging the right edge 80 px on 8 columns has to widen all 8 of them, so the
     * grid's right edge is still the same place afterwards. The counts never change — this resizes
     * cells, it does not add or drop a column.
     *
     * Rounding is to nearest, and a cell never falls below 1 px or rises above [MAX_CELL_PX], so a
     * long drag off the side of the screen stops at a grid that can still be packed rather than
     * running away to infinity.
     *
     * @param edge which edge the drag has hold of. RIGHT uses [dx] only, BOTTOM uses [dy] only.
     * @param dx horizontal drag in document px. A non-finite one moves nothing.
     * @param dy vertical drag in document px.
     */
    fun dragEdge(grid: SpriteGrid, edge: Edge, dx: Float, dy: Float): SpriteGrid {
        val cols = colsOf(grid.cols)
        val rows = rowsOf(grid.rows, cols)
        val cellW = cellWOf(grid.cellW)
        val cellH = cellHOf(grid.cellH)
        val byWidth = edge == Edge.RIGHT || edge == Edge.CORNER
        val byHeight = edge == Edge.BOTTOM || edge == Edge.CORNER
        val w = if (byWidth) grownCell(cellW, dx / cols.toFloat()) else cellW
        val h = if (byHeight) grownCell(cellH, dy / rows.toFloat()) else cellH
        return SpriteGrid(cols = cols, rows = rows, cellW = w, cellH = h)
    }

    // ---------- sub-grid lines ----------

    /**
     * The sub-grid: [div] x [div] divisions inside EVERY cell, as `(x0, y0, x1, y1)` segments in
     * document px — vertical lines first within a cell, then horizontal, cells in reading order.
     *
     * This is a HELPER for lining a sprite up inside its cell (blueprint §3), never exported: a
     * sheet goes out with the cell borders and nothing else, because a line the person used to aim
     * with would be a line in someone else's animation. Only the INTERIOR lines are returned; the
     * cell borders themselves are the board's to draw, and a duplicate of them is a visibly
     * heavier grid line.
     *
     * Lines land on whole pixels (`k * cell / div`, floored), which keeps a line from covering two
     * half pixels and keeps this testable without a tolerance.
     *
     * @param div divisions per cell, 2..8. Outside that there is nothing useful to draw and an
     *   empty list is the answer — not a throw, because [div] comes off a slider.
     * @return an empty list for a board with no grid, or a [div] outside 2..8.
     */
    fun subGridLines(board: Board, div: Int): List<FloatArray> {
        val grid = board.grid ?: return emptyList()
        if (div < MIN_SUB_GRID_DIV || div > MAX_SUB_GRID_DIV) return emptyList()
        val cols = colsOf(grid.cols)
        val rows = rowsOf(grid.rows, cols)
        val cellW = cellWOf(grid.cellW)
        val cellH = cellHOf(grid.cellH)
        val lines = ArrayList<FloatArray>(cols * rows * (div - 1) * 2)
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val left = board.rect.x + col * cellW
                val top = board.rect.y + row * cellH
                val right = left + cellW
                val bottom = top + cellH
                for (step in 1 until div) {
                    val x = (left + step * cellW / div).toFloat()
                    lines.add(floatArrayOf(x, top.toFloat(), x, bottom.toFloat()))
                }
                for (step in 1 until div) {
                    val y = (top + step * cellH / div).toFloat()
                    lines.add(floatArrayOf(left.toFloat(), y, right.toFloat(), y))
                }
            }
        }
        return lines
    }

    // ---------- the clamps, in one place ----------

    /** Columns: 1..[MAX_CELLS]. [cols] is the spine of reading order, so it is the one that is kept. */
    private fun colsOf(cols: Int): Int {
        if (cols < 1) return 1
        if (cols > MAX_CELLS) return MAX_CELLS
        return cols
    }

    /**
     * Rows: 1..(MAX_CELLS / [cols]), which keeps `cols * rows <= MAX_CELLS`.
     *
     * Rows are the count that gives way, and that is a choice the spec left open (see its Questions
     * section): a grid is read across a row, so keeping the columns the person asked for keeps their
     * picture and gives up the number of rows that run off the bottom. [cols] is already clamped to
     * 1..MAX_CELLS, so this divisor is at least 1 and the limit is at least 1.
     */
    private fun rowsOf(rows: Int, cols: Int): Int {
        if (rows < 1) return 1
        val limit = MAX_CELLS / cols
        return if (rows > limit) limit else rows
    }

    private fun cellWOf(cellW: Int): Int = if (cellW < 1) 1 else if (cellW > MAX_CELL_PX) MAX_CELL_PX else cellW

    private fun cellHOf(cellH: Int): Int = if (cellH < 1) 1 else if (cellH > MAX_CELL_PX) MAX_CELL_PX else cellH

    /** How many whole [cell]-px cells fit in [extent] px, never fewer than one. */
    private fun wholeCells(extent: Int, cell: Int): Int {
        val count = extent / cell
        return if (count < 1) 1 else count
    }

    /**
     * One cell's new edge after a drag that moves the whole grid's edge by [perCellDelta] px.
     *
     * Long arithmetic and a clamp on both sides, because `cell + delta` on an Int that has run away
     * is an overflow that wraps a drag past 4096 px into a cell of minus something.
     */
    private fun grownCell(cell: Int, perCellDelta: Float): Int {
        val sum = cell.toLong() + roundedPx(perCellDelta).toLong()
        if (sum < 1L) return 1
        if (sum > MAX_CELL_PX.toLong()) return MAX_CELL_PX
        return sum.toInt()
    }

    /**
     * [value] to the nearest whole pixel, ties away from zero, saturating at +-[MAX_CELL_PX].
     *
     * NaN is 0 because a NaN drag is a pointer that has not reported a position, and the grid that
     * came back should be the one that was there rather than a grid sized by a number that is not.
     * Infinity saturates, because a drag that ran off the edge of the screen should stop at a grid
     * that can still be packed.
     */
    private fun roundedPx(value: Float): Int {
        if (value.isNaN()) return 0
        val cap = MAX_CELL_PX.toFloat()
        if (value >= cap) return MAX_CELL_PX
        if (value <= -cap) return -MAX_CELL_PX
        val rounded = if (value >= 0f) floor(value + 0.5f) else ceil(value - 0.5f)
        return rounded.toInt()
    }
}

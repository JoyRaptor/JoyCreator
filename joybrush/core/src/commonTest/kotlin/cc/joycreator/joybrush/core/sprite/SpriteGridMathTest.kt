package cc.joycreator.joybrush.core.sprite

import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.doc.SpriteGrid
import cc.joycreator.joybrush.core.export.SpritePacker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The sprite grid maths (JB-4.01a).
 *
 * These are the spec's cases first and then the ones it implies, in the order the spec lists them,
 * so a red test can be read back against the spec without hunting. Where a test pins a choice the
 * spec left open (which of cols/rows gives way when the sheet is too big to pack; ties in the drag
 * rounding) the choice is written out in the comment above the assertion rather than left for the
 * next reader to guess at.
 */
class SpriteGridMathTest {

    // ── helpers ────────────────────────────────────────────────────────────────

    /** A sprite board at an arbitrary origin, because boards are not required to start at 0,0. */
    private fun spriteBoard(x: Int, y: Int, w: Int, h: Int, grid: SpriteGrid?): Board = Board(
        id = "b-sprite",
        name = "Sprite",
        kind = BoardKind.SPRITE,
        rect = RectPx(x, y, w, h),
        grid = grid,
    )

    /** 8 columns of 64 x 64 at (100, -40): 32 cells, and an origin that is not zero. */
    private fun eightByFour(): Board = spriteBoard(
        100, -40, 512, 256,
        SpriteGrid(cols = 8, rows = 4, cellW = 64, cellH = 64),
    )

    /** One cell, every byte equal to [v], so a wrong cell is a wrong number at a known place. */
    private fun solidCell(w: Int, h: Int, v: Int): ByteArray {
        val out = ByteArray(w * h * 4)
        var i = 0
        while (i < out.size) {
            out[i] = v.toByte()
            out[i + 1] = v.toByte()
            out[i + 2] = v.toByte()
            out[i + 3] = 0xFF.toByte()
            i += 4
        }
        return out
    }

    private fun redChannelOf(bytes: ByteArray, x: Int, y: Int, sheetWidth: Int): Int =
        bytes[(y * sheetWidth + x) * 4].toInt() and 0xFF

    // ── 1. by size: cells of a given pixel size ────────────────────────────────

    @Test
    fun bySizeFitsWholeCellsIntoTheBoard() {
        val grid = SpriteGridMath.bySize(RectPx(0, 0, 512, 256), 64, 64)
        assertEquals(SpriteGrid(cols = 8, rows = 4, cellW = 64, cellH = 64), grid)
    }

    @Test
    fun bySizeLeavesTheRemainderOutsideTheGrid() {
        // 5 x 2 and not 6 x 3: a partial cell at the right and bottom edge is not a cell anybody
        // can draw a sprite in, so the 12 px and the 56 px stay outside the grid.
        val grid = SpriteGridMath.bySize(RectPx(0, 0, 512, 256), 100, 100)
        assertEquals(SpriteGrid(cols = 5, rows = 2, cellW = 100, cellH = 100), grid)
        assertEquals(500, grid.cols * grid.cellW)
        assertEquals(200, grid.rows * grid.cellH)
    }

    @Test
    fun aCellBiggerThanTheBoardIsOneColumnAndOneRow() {
        val grid = SpriteGridMath.bySize(RectPx(10, 20, 32, 16), 64, 64)
        assertEquals(SpriteGrid(cols = 1, rows = 1, cellW = 64, cellH = 64), grid)
    }

    @Test
    fun bySizeWorksOnABoardAtANegativeOrigin() {
        val grid = SpriteGridMath.bySize(RectPx(-200, -100, 512, 256), 64, 64)
        assertEquals(SpriteGrid(cols = 8, rows = 4, cellW = 64, cellH = 64), grid)
    }

    // ── 2. by count, and the rect that really holds it ─────────────────────────

    @Test
    fun byCountDividesTheBoardAndFloorsTheLeftover() {
        val rect = RectPx(0, 0, 500, 300)
        val grid = SpriteGridMath.byCount(rect, 3, 2)
        assertEquals(SpriteGrid(cols = 3, rows = 2, cellW = 166, cellH = 150), grid)
        assertEquals(RectPx(0, 0, 498, 300), SpriteGridMath.fitRect(rect, grid))
    }

    @Test
    fun fitRectKeepsTheBoardsTopLeft() {
        val grid = SpriteGrid(cols = 3, rows = 2, cellW = 166, cellH = 150)
        assertEquals(
            RectPx(100, -40, 498, 300),
            SpriteGridMath.fitRect(RectPx(100, -40, 500, 300), grid),
        )
    }

    @Test
    fun bySizeThenFitRectIsTheAreaTheGridCovers() {
        val grid = SpriteGridMath.bySize(RectPx(0, 0, 512, 256), 100, 100)
        assertEquals(RectPx(0, 0, 500, 200), SpriteGridMath.fitRect(RectPx(0, 0, 512, 256), grid))
    }

    // ── 3. reading order, and a negative origin ────────────────────────────────

    @Test
    fun cellRectReadsInRowMajorOrder() {
        val board = eightByFour()
        assertEquals(RectPx(100, -40, 64, 64), SpriteGridMath.cellRect(board, 0))
        assertEquals(RectPx(548, -40, 64, 64), SpriteGridMath.cellRect(board, 7))
        assertEquals(RectPx(100, 24, 64, 64), SpriteGridMath.cellRect(board, 8))
    }

    @Test
    fun everyCellOfAnEightByFourGridIsWhereItShouldBe() {
        val board = eightByFour()
        for (index in 0 until 32) {
            val cell = SpriteGridMath.cellRect(board, index)
            val col = index % 8
            val row = index / 8
            assertEquals(RectPx(100 + col * 64, -40 + row * 64, 64, 64), cell, "cell $index")
        }
    }

    // ── 4. which cell is under a point ────────────────────────────────────────

    @Test
    fun cellAtMapsEveryCellsOwnPixelsBackToIt() {
        val board = eightByFour()
        for (index in 0 until 32) {
            val cell = SpriteGridMath.cellRect(board, index)
            assertEquals(
                index,
                SpriteGridMath.cellAt(board, cell.x.toFloat(), cell.y.toFloat()),
                "top-left pixel of cell $index",
            )
            assertEquals(
                index,
                SpriteGridMath.cellAt(
                    board,
                    (cell.x + cell.w - 1).toFloat(),
                    (cell.y + cell.h - 1).toFloat(),
                ),
                "bottom-right pixel of cell $index",
            )
        }
    }

    @Test
    fun onePixelPastTheEdgeOfTheGridIsNotACell() {
        val board = eightByFour()
        // The grid is 8 x 64 = 512 wide and 4 x 64 = 256 tall, at (100, -40).
        assertEquals(-1, SpriteGridMath.cellAt(board, 100f + 512f, -40f), "one px right")
        assertEquals(-1, SpriteGridMath.cellAt(board, 100f, -40f + 256f), "one px below")
        assertEquals(-1, SpriteGridMath.cellAt(board, 99f, -40f), "one px left")
        assertEquals(-1, SpriteGridMath.cellAt(board, 100f, -41f), "one px above")
        // Inside the BOARD rect but outside the grid: the spare strip a bySize grid leaves.
        val spare = spriteBoard(0, 0, 500, 300, SpriteGrid(cols = 5, rows = 2, cellW = 100, cellH = 100))
        assertEquals(-1, SpriteGridMath.cellAt(spare, 500f, 0f))
        assertEquals(4, SpriteGridMath.cellAt(spare, 499f, 0f), "the last column is still a cell")
        assertEquals(-1, SpriteGridMath.cellAt(spare, 0f, 200f))
        // Row 1 of 2, column 0 of 5, so reading order puts it at 1 * 5 + 0 = 5.
        assertEquals(5, SpriteGridMath.cellAt(spare, 0f, 199f), "the last row is still a cell")
    }

    @Test
    fun aPointThatIsNotANumberIsNotACell() {
        val board = eightByFour()
        assertEquals(-1, SpriteGridMath.cellAt(board, Float.NaN, 0f))
        assertEquals(-1, SpriteGridMath.cellAt(board, 0f, Float.NaN))
        assertEquals(-1, SpriteGridMath.cellAt(board, Float.NaN, Float.NaN))
        assertEquals(-1, SpriteGridMath.cellAt(board, Float.POSITIVE_INFINITY, 0f))
        assertEquals(-1, SpriteGridMath.cellAt(board, 0f, Float.NEGATIVE_INFINITY))
    }

    @Test
    fun aBoardWithNoGridHasNoCellUnderAPointAndNoCellToAskFor() {
        val board = spriteBoard(0, 0, 512, 256, grid = null)
        assertEquals(-1, SpriteGridMath.cellAt(board, 0f, 0f))
        assertFailsWith<IllegalArgumentException> { SpriteGridMath.cellRect(board, 0) }
    }

    @Test
    fun aCellIndexTheGridDoesNotHoldIsABug() {
        val board = eightByFour()
        assertFailsWith<IllegalArgumentException> { SpriteGridMath.cellRect(board, 32) }
        assertFailsWith<IllegalArgumentException> { SpriteGridMath.cellRect(board, -1) }
    }

    // ── 5. dragging an edge ───────────────────────────────────────────────────

    @Test
    fun draggingTheRightEdgeWidensEveryColumn() {
        val grid = SpriteGrid(cols = 8, rows = 4, cellW = 64, cellH = 64)
        val dragged = SpriteGridMath.dragEdge(grid, SpriteGridMath.Edge.RIGHT, 80f, 9999f)
        assertEquals(SpriteGrid(cols = 8, rows = 4, cellW = 74, cellH = 64), dragged)
    }

    @Test
    fun draggingTheBottomEdgeStopsAtOnePixel() {
        val grid = SpriteGrid(cols = 8, rows = 4, cellW = 64, cellH = 64)
        val dragged = SpriteGridMath.dragEdge(grid, SpriteGridMath.Edge.BOTTOM, -9999f, -1000f)
        assertEquals(SpriteGrid(cols = 8, rows = 4, cellW = 64, cellH = 1), dragged)
    }

    @Test
    fun draggingTheCornerMovesBothEdges() {
        val grid = SpriteGrid(cols = 8, rows = 4, cellW = 64, cellH = 64)
        val dragged = SpriteGridMath.dragEdge(grid, SpriteGridMath.Edge.CORNER, 80f, 80f)
        assertEquals(SpriteGrid(cols = 8, rows = 4, cellW = 74, cellH = 84), dragged)
    }

    @Test
    fun aDragIsSharedOutOverTheCellsAndRoundedToWholePixels() {
        // 3 columns of 4 px is 1.33 px each, so the cell grows by 1 and not by 1.33; the drag is
        // deliberately lost a little, because a cell is a whole number of pixels and something has
        // to give. -2 px over 2 rows is exactly -1 each.
        val grid = SpriteGrid(cols = 3, rows = 2, cellW = 10, cellH = 10)
        val dragged = SpriteGridMath.dragEdge(grid, SpriteGridMath.Edge.CORNER, 4f, -2f)
        assertEquals(SpriteGrid(cols = 3, rows = 2, cellW = 11, cellH = 9), dragged)
    }

    @Test
    fun aDragNeverChangesTheNumberOfCells() {
        val grid = SpriteGrid(cols = 8, rows = 4, cellW = 64, cellH = 64)
        for (edge in SpriteGridMath.Edge.entries) {
            val dragged = SpriteGridMath.dragEdge(grid, edge, 1234f, 1234f)
            assertEquals(8, dragged.cols, "$edge")
            assertEquals(4, dragged.rows, "$edge")
        }
    }

    @Test
    fun aDragOfNoDistanceChangesNothing() {
        val grid = SpriteGrid(cols = 8, rows = 4, cellW = 64, cellH = 64)
        assertEquals(grid, SpriteGridMath.dragEdge(grid, SpriteGridMath.Edge.CORNER, 0f, 0f))
        assertEquals(grid, SpriteGridMath.dragEdge(grid, SpriteGridMath.Edge.CORNER, Float.NaN, Float.NaN))
    }

    @Test
    fun aDragThatRanOffTheScreenSaturatesInsteadOfOverflowing() {
        val grid = SpriteGrid(cols = 8, rows = 4, cellW = 64, cellH = 64)
        val dragged = SpriteGridMath.dragEdge(
            grid,
            SpriteGridMath.Edge.CORNER,
            Float.MAX_VALUE,
            Float.NEGATIVE_INFINITY,
        )
        assertEquals(SpriteGrid(cols = 8, rows = 4, cellW = SpriteGridMath.MAX_CELL_PX, cellH = 1), dragged)
    }

    // ── 6. the guards ─────────────────────────────────────────────────────────

    @Test
    fun zeroCountsBecomeOneCell() {
        val grid = SpriteGridMath.byCount(RectPx(0, 0, 500, 300), 0, 0)
        assertEquals(SpriteGrid(cols = 1, rows = 1, cellW = 500, cellH = 300), grid)
    }

    @Test
    fun negativeCountsAndSizesAreClampedRatherThanThrown() {
        val byCount = SpriteGridMath.byCount(RectPx(0, 0, 500, 300), -4, -9)
        assertEquals(SpriteGrid(cols = 1, rows = 1, cellW = 500, cellH = 300), byCount)

        // 1 px cells over the whole board would be 500 x 300 = 150,000 cells, so the row count is
        // the one that gives way: 4096 / 500 = 8 rows, which is the largest sheet that fits the cap.
        val bySize = SpriteGridMath.bySize(RectPx(0, 0, 500, 300), 0, -8)
        assertEquals(SpriteGrid(cols = 500, rows = 8, cellW = 1, cellH = 1), bySize)
        assertTrue(bySize.cols * bySize.rows <= SpriteGridMath.MAX_CELLS)
    }

    @Test
    fun aSheetTooBigToPackIsClampedDownToTheCap() {
        // 100 x 100 is 10,000 cells. The columns are kept (reading order runs across a row) and the
        // rows are clamped to 4096 / 100 = 40.
        val grid = SpriteGridMath.byCount(RectPx(0, 0, 500, 300), 100, 100)
        assertEquals(SpriteGrid(cols = 100, rows = 40, cellW = 5, cellH = 7), grid)
        assertTrue(grid.cols * grid.rows <= SpriteGridMath.MAX_CELLS)
    }

    @Test
    fun aColumnCountPastTheCapIsClampedBeforeTheRowsAreWorkedOut() {
        val grid = SpriteGridMath.byCount(RectPx(0, 0, 500, 300), 99999, 4)
        assertEquals(1, grid.rows, "4096 columns leaves room for one row")
        assertTrue(grid.cols <= SpriteGridMath.MAX_CELLS)
        assertTrue(grid.cols * grid.rows <= SpriteGridMath.MAX_CELLS)
    }

    @Test
    fun aCellPastTheCapIsClampedToTheCap() {
        // 5000 x 5000 of 9999 px cells: the cell is clamped to the cap, and the clamped cell is
        // then the one that is measured against the board, which is why this is 1 x 1 and not 2 x 2.
        val grid = SpriteGridMath.bySize(RectPx(0, 0, 5000, 5000), 9999, 9999)
        assertEquals(SpriteGridMath.MAX_CELL_PX, grid.cellW)
        assertEquals(SpriteGridMath.MAX_CELL_PX, grid.cellH)
        assertEquals(1, grid.cols)
        assertEquals(1, grid.rows)
    }

    // ── 7. the sub-grid ───────────────────────────────────────────────────────

    @Test
    fun subGridLinesAreTheInteriorLinesOfEveryCell() {
        val board = spriteBoard(0, 0, 128, 64, SpriteGrid(cols = 2, rows = 1, cellW = 64, cellH = 64))
        val lines = SpriteGridMath.subGridLines(board, 2)
        // Two cells, each with one vertical and one horizontal interior line, in reading order.
        assertEquals(4, lines.size)
        assertEquals(listOf(32f, 0f, 32f, 64f), lines[0].toList())
        assertEquals(listOf(0f, 32f, 64f, 32f), lines[1].toList())
        assertEquals(listOf(96f, 0f, 96f, 64f), lines[2].toList())
        assertEquals(listOf(64f, 32f, 128f, 32f), lines[3].toList())
    }

    @Test
    fun aSubGridDeeperThanTwoDrawsEveryInteriorLineAndNoBorder() {
        // 4 divisions of a 64 px cell: lines at 16, 32 and 48. The borders at 0 and 64 are the
        // board's to draw, so they are not here.
        val board = spriteBoard(0, 0, 64, 64, SpriteGrid(cols = 1, rows = 1, cellW = 64, cellH = 64))
        val lines = SpriteGridMath.subGridLines(board, 4)
        assertEquals(6, lines.size)
        assertEquals(listOf(16f, 32f, 48f), verticalLines(lines))
        assertEquals(listOf(16f, 32f, 48f), horizontalLines(lines))
    }

    @Test
    fun aSubGridOutsideTwoToEightHasNothingToDraw() {
        val board = spriteBoard(0, 0, 128, 64, SpriteGrid(cols = 2, rows = 1, cellW = 64, cellH = 64))
        assertEquals(0, SpriteGridMath.subGridLines(board, 1).size)
        assertEquals(0, SpriteGridMath.subGridLines(board, 0).size)
        assertEquals(0, SpriteGridMath.subGridLines(board, -3).size)
        assertEquals(0, SpriteGridMath.subGridLines(board, 9).size)
        assertEquals(0, SpriteGridMath.subGridLines(board, Int.MAX_VALUE).size)
        // And both ends of the range really do draw.
        assertEquals(4, SpriteGridMath.subGridLines(board, 2).size)
        assertEquals(28, SpriteGridMath.subGridLines(board, 8).size)
    }

    @Test
    fun aBoardWithNoGridHasNoSubGrid() {
        val board = spriteBoard(0, 0, 128, 64, grid = null)
        assertEquals(0, SpriteGridMath.subGridLines(board, 4).size)
    }

    @Test
    fun subGridLinesFollowTheCellsOfANegativeOriginBoard() {
        val board = eightByFour()
        val lines = SpriteGridMath.subGridLines(board, 2)
        // 32 cells x (1 vertical + 1 horizontal), and the first is the middle of cell 0.
        assertEquals(64, lines.size)
        assertEquals(listOf(132f, -40f, 132f, 24f), lines[0].toList())
        assertEquals(listOf(100f, -8f, 164f, -8f), lines[1].toList())
    }

    // ── 8. agreement with the packer ──────────────────────────────────────────

    @Test
    fun cellsLandInTheColumnsAndRowsSpritePackerLaysThemOutIn() {
        val cols = 3
        val grid = SpriteGrid(cols = cols, rows = 2, cellW = 32, cellH = 16)
        val board = spriteBoard(0, 0, cols * 32, 2 * 16, grid)
        for (index in 0 until cols * 2) {
            val cell = SpriteGridMath.cellRect(board, index)
            // SpritePacker.pack: left = (i % cols) * cellStride, top = (i / cols) * cellH.
            assertEquals((index % cols) * 32, cell.x, "cell $index column")
            assertEquals((index / cols) * 16, cell.y, "cell $index row")
        }
    }

    @Test
    fun theSheetSpritePackerWritesHasACellInEveryRectThisSaysItHas() {
        val cols = 3
        val rows = 2
        val cellW = 8
        val cellH = 4
        val cells = (0 until cols * rows).map { solidCell(cellW, cellH, 20 + it) }
        val sheet = SpritePacker.pack(
            cells = cells,
            cellW = cellW,
            cellH = cellH,
            cols = cols,
            id = "s",
            name = "S",
            sheetFileName = "S.png",
            fps = 8f,
            clips = emptyList(),
        )
        val board = spriteBoard(0, 0, cols * cellW, rows * cellH, SpriteGrid(cols, rows, cellW, cellH))
        assertEquals(sheet.width, cols * cellW)
        assertEquals(sheet.height, rows * cellH)
        for (index in cells.indices) {
            val cell = SpriteGridMath.cellRect(board, index)
            assertEquals(
                20 + index,
                redChannelOf(sheet.rgba, cell.x, cell.y, sheet.width),
                "cell $index is not at ${cell.x},${cell.y} in the packed sheet",
            )
        }
    }

    // ── helpers for reading segments back ─────────────────────────────────────

    /** The x of every segment that runs vertically (`x0 == x1`). */
    private fun verticalLines(lines: List<FloatArray>): List<Float> =
        lines.filter { it[0] == it[2] }.map { it[0] }

    /** The y of every segment that runs horizontally (`y0 == y1`). */
    private fun horizontalLines(lines: List<FloatArray>): List<Float> =
        lines.filter { it[1] == it[3] }.map { it[1] }
}

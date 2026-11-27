package cc.joycreator.joybrush.core.sprite

import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardDocumentOps
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.Cel
import cc.joycreator.joybrush.core.doc.DocException
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.Layer
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.doc.SpriteCellOps
import cc.joycreator.joybrush.core.doc.SpriteGrid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * JB-NOW-15 — exact Sprite-grid arithmetic boundaries.
 *
 * What this covers that [SpriteGridMathTest] / [SpriteBoardTest] / [BoardDocumentOpsTest] /
 * [SpriteCellOpsTest] do not: NON-SQUARE cells end to end (bySize/byCount/cellRect/cellAt/seams/
 * drag axes/sub-grid) at negative origins, first/last cells against independently computed Long
 * row/column products, exact seam ownership, the ±0.5 drag tie (flagged missing in the JB-4.01a
 * re-review), supported min/max edges, integer-overflow-adjacent inputs with no giant arrays,
 * board-size vs cell-size helper semantics per the 2026-10-07 owner clarification, and the
 * UI-safe-clamp vs content-refusal split (including one [DocOps.validate] gap, pinned, not fixed).
 *
 * Every expectation is hand-computed in the comment above it, never transcribed from the
 * implementation. Nothing here allocates a pixel buffer: huge inputs are arithmetic only.
 *
 * Limitations (reported, not faked): there is no drag-gesture UI in core — [GridGesture] is a
 * vocabulary and [SpriteBoard] documents its view half as blocked behind JB-2.01 — so
 * finger-down → grab → commit sequences are untestable here; only edgeAt/dragEdge/dragged maths.
 * Handle/form/stepper behaviour in SPRITE_GRID_INTEGRATION_20261007 (Android side) is not driven
 * from core. No Gradle run per contract; symbols verified by reading production sources.
 */
class SpriteGridBoundaryCorpusTest {

    private fun spriteBoard(rect: RectPx, grid: SpriteGrid?, locked: Boolean = false): Board = Board(
        id = "b-sprite",
        name = "Sprite",
        kind = BoardKind.SPRITE,
        rect = rect,
        grid = grid,
        locked = locked,
    )

    /** The non-square fixture: 3 x 2 of 100 x 150 at (-200, -100), exactly fitted (300 x 300). */
    private fun nonSquare(): Board = spriteBoard(
        RectPx(-200, -100, 300, 300),
        SpriteGrid(cols = 3, rows = 2, cellW = 100, cellH = 150),
    )

    /**
     * The cell rectangle from first principles: column [col], row [row] of whole [cw] x [ch]
     * cells from ([ox], [oy]), in Long so the expectation can never share an overflow with the
     * implementation.
     */
    private fun expectRect(ox: Int, oy: Int, col: Int, row: Int, cw: Int, ch: Int): RectPx {
        val left = ox.toLong() + col.toLong() * cw
        val top = oy.toLong() + row.toLong() * ch
        return RectPx(left.toInt(), top.toInt(), cw, ch)
    }

    private fun paintDoc(board: Board): JbDocument = JbDocument(
        id = "doc",
        name = "Boundary",
        boards = listOf(board),
        layers = listOf(Layer("paint", "Paint", LayerKind.PAINT, cels = listOf(Cel("shared")))),
    )

    // ── 1. non-square cells: the cell-size entry ──────────────────────────────

    @Test
    fun nonSquareBySizeDerivesCountsFromCellSize() {
        // 512 / 128 = 4 columns, 256 / 32 = 8 rows: different counts per axis, the whole point.
        val grid = SpriteGridMath.bySize(RectPx(0, 0, 512, 256), 128, 32)
        assertEquals(SpriteGrid(cols = 4, rows = 8, cellW = 128, cellH = 32), grid)
        // Exact fit, so fitRect is the rect it was given.
        assertEquals(RectPx(0, 0, 512, 256), SpriteGridMath.fitRect(RectPx(0, 0, 512, 256), grid))
        // Counts come from the cell size, never the origin: same grid at a negative origin.
        assertEquals(grid, SpriteGridMath.bySize(RectPx(-300, -200, 512, 256), 128, 32))
    }

    // ── 2. non-square cells: the board-size entry ─────────────────────────────

    @Test
    fun nonSquareByCountDerivesCellSizeFromBoardSize() {
        // 500 / 3 = 166 (Int floor), 301 / 2 = 150: the floor leaves a 2 x 1 remainder outside.
        val grid = SpriteGridMath.byCount(RectPx(-200, -100, 500, 301), 3, 2)
        assertEquals(SpriteGrid(cols = 3, rows = 2, cellW = 166, cellH = 150), grid)
        assertEquals(RectPx(-200, -100, 498, 300), SpriteGridMath.fitRect(RectPx(-200, -100, 500, 301), grid))
        // The remainder is outside the grid: the grid's right edge is at -200 + 498 = 298.
        val board = spriteBoard(RectPx(-200, -100, 500, 301), grid)
        assertEquals(2, SpriteGridMath.cellAt(board, 297f, 0f), "last px of column 2: (-200 + 497) / 166 = 2")
        assertEquals(-1, SpriteGridMath.cellAt(board, 298f, 0f), "the 2 px remainder is not a cell")
        // Last cell against the independent product: col 2, row 1.
        assertEquals(RectPx(132, 50, 166, 150), SpriteGridMath.cellRect(board, 5))
        assertEquals(expectRect(-200, -100, 2, 1, 166, 150), SpriteGridMath.cellRect(board, 5))
    }

    // ── 3. first/last cells as independent row-major products ─────────────────

    @Test
    fun firstAndLastCellsMatchIndependentRowMajorProducts() {
        val board = nonSquare()
        for (index in 0 until 6) {
            val col = index % 3
            val row = index / 3
            assertEquals(expectRect(-200, -100, col, row, 100, 150), SpriteGridMath.cellRect(board, index), "cell $index")
        }
        // Named explicitly: first, first row's last, second row's first, last.
        assertEquals(RectPx(-200, -100, 100, 150), SpriteGridMath.cellRect(board, 0))
        assertEquals(RectPx(0, -100, 100, 150), SpriteGridMath.cellRect(board, 2))
        assertEquals(RectPx(-200, 50, 100, 150), SpriteGridMath.cellRect(board, 3))
        assertEquals(RectPx(0, 50, 100, 150), SpriteGridMath.cellRect(board, 5))
        assertFailsWith<IllegalArgumentException> { SpriteGridMath.cellRect(board, 6) }
        assertFailsWith<IllegalArgumentException> { SpriteGridMath.cellRect(board, -1) }
    }

    // ── 4. exact seams at a negative origin ───────────────────────────────────

    @Test
    fun exactSeamsBelongToTheNextCell() {
        val board = nonSquare()
        // Cells are half-open: the seam pixel starts the next cell, on both axes.
        assertEquals(0, SpriteGridMath.cellAt(board, -200f, -100f), "top-left corner is cell 0")
        assertEquals(0, SpriteGridMath.cellAt(board, -101f, 0f), "last px of column 0")
        assertEquals(1, SpriteGridMath.cellAt(board, -100f, 0f), "seam starts column 1")
        assertEquals(0, SpriteGridMath.cellAt(board, -150f, 49f), "last px of row 0")
        assertEquals(3, SpriteGridMath.cellAt(board, -150f, 50f), "seam starts row 1")
        // Last cell's last pixel, then one past on each axis.
        assertEquals(5, SpriteGridMath.cellAt(board, 99f, 199f))
        assertEquals(-1, SpriteGridMath.cellAt(board, 100f, 199f), "one px past the right edge")
        assertEquals(-1, SpriteGridMath.cellAt(board, 99f, 200f), "one px past the bottom edge")
        assertEquals(-1, SpriteGridMath.cellAt(board, -201f, -100f), "one px before the left edge")
        assertEquals(-1, SpriteGridMath.cellAt(board, -200f, -101f), "one px above the top edge")
    }

    // ── 5. drag rounding ties away from zero (the flagged missing case) ──────

    @Test
    fun dragRoundingTiesAwayFromZero() {
        val grid = SpriteGrid(cols = 8, rows = 4, cellW = 64, cellH = 64)
        // 4 px over 8 columns is exactly 0.5 px each: away from zero is +1, not banker's even.
        val up = SpriteGridMath.dragEdge(grid, SpriteGridMath.Edge.RIGHT, 4f, 9999f)
        assertEquals(SpriteGrid(cols = 8, rows = 4, cellW = 65, cellH = 64), up)
        // -0.5 is -1, and the ignored axis is still ignored even at a tie.
        val down = SpriteGridMath.dragEdge(grid, SpriteGridMath.Edge.RIGHT, -4f, -9999f)
        assertEquals(SpriteGrid(cols = 8, rows = 4, cellW = 63, cellH = 64), down)
        // Same tie on the other axis: 2 px over 4 rows is 0.5 each.
        val taller = SpriteGridMath.dragEdge(grid, SpriteGridMath.Edge.BOTTOM, 9999f, 2f)
        assertEquals(SpriteGrid(cols = 8, rows = 4, cellW = 64, cellH = 65), taller)
        val shorter = SpriteGridMath.dragEdge(grid, SpriteGridMath.Edge.BOTTOM, -9999f, -2f)
        assertEquals(SpriteGrid(cols = 8, rows = 4, cellW = 64, cellH = 63), shorter)
    }

    // ── 6. drag axes stay separate on non-square cells ────────────────────────

    @Test
    fun dragAxesStaySeparateOnNonSquareCells() {
        val grid = SpriteGrid(cols = 4, rows = 8, cellW = 128, cellH = 32)
        // 40 / 4 = 10 wider; height bit-identical proves the axes never cross.
        assertEquals(
            SpriteGrid(cols = 4, rows = 8, cellW = 138, cellH = 32),
            SpriteGridMath.dragEdge(grid, SpriteGridMath.Edge.RIGHT, 40f, 9999f),
        )
        // 80 / 8 = 10 taller; width bit-identical.
        assertEquals(
            SpriteGrid(cols = 4, rows = 8, cellW = 128, cellH = 42),
            SpriteGridMath.dragEdge(grid, SpriteGridMath.Edge.BOTTOM, -9999f, 80f),
        )
        assertEquals(
            SpriteGrid(cols = 4, rows = 8, cellW = 138, cellH = 42),
            SpriteGridMath.dragEdge(grid, SpriteGridMath.Edge.CORNER, 40f, 80f),
        )
    }

    // ── 7. sub-grid lines follow non-square cells ─────────────────────────────

    @Test
    fun subGridLinesFollowNonSquareCells() {
        val board = nonSquare()
        val lines = SpriteGridMath.subGridLines(board, 3)
        // 6 cells x (2 vertical + 2 horizontal) interior lines.
        assertEquals(24, lines.size)
        // Int floor per axis: 100/3 -> 33, 66; 150/3 -> 50, 100. Verticals first, then horizontals.
        assertEquals(listOf(-167f, -100f, -167f, 50f), lines[0].toList())
        assertEquals(listOf(-134f, -100f, -134f, 50f), lines[1].toList())
        assertEquals(listOf(-200f, -50f, -100f, -50f), lines[2].toList())
        assertEquals(listOf(-200f, 0f, -100f, 0f), lines[3].toList())
        // Last cell (index 5, lines 20..23) starts at its own top-left (0, 50).
        assertEquals(listOf(33f, 50f, 33f, 200f), lines[20].toList())
        assertEquals(listOf(0f, 150f, 100f, 150f), lines[23].toList())
    }

    // ── 8. supported minimum: one whole cell ──────────────────────────────────

    @Test
    fun supportedMinimumIsOneWholeCell() {
        // A cell bigger than the board is one column and one row, not an error.
        assertEquals(
            SpriteGrid(cols = 1, rows = 1, cellW = 4096, cellH = 4096),
            SpriteGridMath.bySize(RectPx(5, 5, 10, 10), 99999, 99999),
        )
        // One count each way is the whole board as one cell.
        assertEquals(
            SpriteGrid(cols = 1, rows = 1, cellW = 500, cellH = 300),
            SpriteGridMath.byCount(RectPx(0, 0, 500, 300), 1, 1),
        )
        val board = spriteBoard(RectPx(7, -3, 500, 300), SpriteGrid(cols = 1, rows = 1, cellW = 500, cellH = 300))
        // First cell IS the last cell.
        assertEquals(RectPx(7, -3, 500, 300), SpriteGridMath.cellRect(board, 0))
        assertFailsWith<IllegalArgumentException> { SpriteGridMath.cellRect(board, 1) }
        assertEquals(0, SpriteGridMath.cellAt(board, 7f, -3f))
        assertEquals(0, SpriteGridMath.cellAt(board, 506f, 296f), "last px: 7+500-1, -3+300-1")
        assertEquals(-1, SpriteGridMath.cellAt(board, 507f, 0f))
        assertEquals(-1, SpriteGridMath.cellAt(board, 0f, 297f))
    }

    // ── 9. supported maximum: exactly 4096 cells and 4096 px ──────────────────

    @Test
    fun supportedMaximumIsExactly4096CellsAnd4096Px() {
        assertEquals(4096, SpriteGridMath.MAX_CELLS)
        assertEquals(4096, SpriteGridMath.MAX_CELL_PX)
        // 64 x 64 = 4096 cells: exactly at the cap is allowed, not refused (the guard is `>`).
        val full = SpriteGridMath.byCount(RectPx(0, 0, 4096, 4096), 64, 64)
        assertEquals(SpriteGrid(cols = 64, rows = 64, cellW = 64, cellH = 64), full)
        assertEquals(SpriteGridMath.MAX_CELLS, full.cols * full.rows)
        // 65 x 65 = 4225: rows give way (columns are kept, reading order runs across a row).
        // 4096 / 65 = 63, so 65 x 63 = 4095; cells re-fill the board: 4096/65 = 63, 4096/63 = 65.
        val over = SpriteGridMath.byCount(RectPx(0, 0, 4096, 4096), 65, 65)
        assertEquals(SpriteGrid(cols = 65, rows = 63, cellW = 63, cellH = 65), over)
        assertTrue(over.cols * over.rows <= SpriteGridMath.MAX_CELLS)
        // Cells saturate at the cap, then the board is measured against the clamped cell.
        val huge = SpriteGridMath.bySize(RectPx(0, 0, 9000, 9000), 99999, 99999)
        assertEquals(SpriteGrid(cols = 2, rows = 2, cellW = 4096, cellH = 4096), huge)
        // The largest fitRect product arithmetic can produce, against its Long product:
        // 4096 x 4096 = 16,777,216 needs no Int-overflow guard only because both factors
        // are pre-clamped, which this pins.
        val fit = SpriteGridMath.fitRect(RectPx(0, 0, 1, 1), SpriteGrid(4096, 1, 4096, 4096))
        assertEquals(RectPx(0, 0, 16777216, 4096), fit)
        assertEquals(4096L * 4096L, fit.w.toLong())
    }

    // ── 10. overflow-adjacent inputs, no giant arrays ─────────────────────────

    @Test
    fun overflowAdjacentInputsNeedNoGiantArrays() {
        // Clamped counts: Int extremes become a 4096 x 1 grid of 1 x 100 without allocating it.
        assertEquals(
            SpriteGrid(cols = 4096, rows = 1, cellW = 1, cellH = 100),
            SpriteGridMath.byCount(RectPx(0, 0, 100, 100), Int.MAX_VALUE, Int.MIN_VALUE),
        )
        // Clamped cells: Int.MIN_VALUE dimensions become 1 px cells; 500 x 300 of 1 px would be
        // 150,000 cells, so rows give way to 4096 / 500 = 8.
        val tiny = SpriteGridMath.bySize(RectPx(0, 0, 500, 300), Int.MIN_VALUE, Int.MIN_VALUE)
        assertEquals(SpriteGrid(cols = 500, rows = 8, cellW = 1, cellH = 1), tiny)
        assertTrue(tiny.cols * tiny.rows <= SpriteGridMath.MAX_CELLS)
        // A grid ending exactly at Int.MAX_VALUE is addressable; 1 px further right refuses.
        val edge = spriteBoard(
            RectPx(Int.MAX_VALUE - 16777216, 0, 16777216, 4096),
            SpriteGrid(cols = 4096, rows = 1, cellW = 4096, cellH = 4096),
        )
        assertEquals(
            RectPx(Int.MAX_VALUE - 16777216, 0, 4096, 4096),
            SpriteGridMath.cellRect(edge, 0),
        )
        assertEquals(
            RectPx(Int.MAX_VALUE - 4096, 0, 4096, 4096),
            SpriteGridMath.cellRect(edge, 4095),
        )
        val past = spriteBoard(
            RectPx(Int.MAX_VALUE - 16777216 + 1, 0, 16777216, 4096),
            SpriteGrid(cols = 4096, rows = 1, cellW = 4096, cellH = 4096),
        )
        assertEquals(Int.MAX_VALUE - 16777216 + 1, SpriteGridMath.cellRect(past, 0).x)
        assertFailsWith<IllegalArgumentException> { SpriteGridMath.cellRect(past, 4095) }
        // Int.MIN_VALUE origin: cell 0 sits exactly on it, and the hit test agrees.
        val low = spriteBoard(
            RectPx(Int.MIN_VALUE, Int.MIN_VALUE, 64, 64),
            SpriteGrid(cols = 1, rows = 1, cellW = 64, cellH = 64),
        )
        assertEquals(RectPx(Int.MIN_VALUE, Int.MIN_VALUE, 64, 64), SpriteGridMath.cellRect(low, 0))
        assertEquals(0, SpriteGridMath.cellAt(low, Int.MIN_VALUE.toFloat(), Int.MIN_VALUE.toFloat()))
    }

    // ── 11. board-size vs cell-size helpers (owner clarification 2026-10-07) ──

    @Test
    fun boardSizeEntryKeepsCountsWhileCellSizeEntryReDerivesThem() {
        var serial = 0
        fun ids() = "board-size-${++serial}"
        val root = JbDocument(
            id = "doc",
            name = "Drawing",
            boards = listOf(Board("root", "Root", BoardKind.CANVAS, RectPx(0, 0, 100, 100))),
            layers = listOf(Layer("layer", "Paint", LayerKind.PAINT, cels = listOf(Cel("shared")))),
        )
        val before = BoardDocumentOps.createSprite(
            root, "Sheet", RectPx(-200, -100, 300, 300), SpriteGrid(3, 2, 100, 150), ::ids,
        ).doc
        val id = before.activeBoardId!!
        // Typing the BOARD size (305 x 310): counts stay 3 x 2, each cell edge snaps to whole px
        // via the drag maths anchored top-left: 5/3 = 1.67 -> 2, 10/2 = 5 -> 102 x 155.
        val resized = BoardDocumentOps.resize(before, id, RectPx(-200, -100, 305, 310))
        assertEquals(SpriteGrid(cols = 3, rows = 2, cellW = 102, cellH = 155), resized.boards.last().grid)
        assertEquals(RectPx(-200, -100, 306, 310), resized.boards.last().rect)
        assertEquals(resized, BoardDocumentOps.resizeTyped(before, id, 305, 310))
        assertEquals(before.layers, resized.layers, "geometry edits move no paint")
        assertTrue(DocOps.validate(resized).isEmpty())
        // Typing the CELL size goes through bySize over the board rect, so counts are re-derived:
        // 300 / 101 = 2 Int-divides, and the column count re-flows from 3 to 2. The stepper IS the
        // setter here (count retention for typed cell dims lives in the resize/drag path above and
        // in the Android form, not in SpriteBoard.stepped).
        val sprite = SpriteBoard(nonSquare())
        assertEquals(sprite.bySize(101, 150), sprite.stepped(SpriteBoard.Axis.CELL_W, 1))
        assertEquals(SpriteGrid(cols = 2, rows = 2, cellW = 101, cellH = 150), sprite.stepped(SpriteBoard.Axis.CELL_W, 1))
        // One px the other way stays inside the whole-cell boundary, so counts are retained.
        assertEquals(SpriteGrid(cols = 3, rows = 2, cellW = 99, cellH = 150), sprite.stepped(SpriteBoard.Axis.CELL_W, -1))
        // Typing the COUNT re-fills the board: 4 columns of 300 px is 75, height untouched at 150.
        assertEquals(sprite.byCount(4, 2), sprite.stepped(SpriteBoard.Axis.COLS, 1))
        assertEquals(SpriteGrid(cols = 4, rows = 2, cellW = 75, cellH = 150), sprite.stepped(SpriteBoard.Axis.COLS, 1))
    }

    // ── 12. set absorbs into fit; create requires exact fit ───────────────────

    @Test
    fun setSpriteGridAbsorbsWhileCreateSpriteRequiresExactFit() {
        var serial = 0
        fun ids() = "grid-fit-${++serial}"
        val root = JbDocument(
            id = "doc",
            name = "Drawing",
            boards = listOf(Board("root", "Root", BoardKind.CANVAS, RectPx(0, 0, 100, 100))),
            layers = listOf(Layer("layer", "Paint", LayerKind.PAINT, cels = listOf(Cel("shared")))),
        )
        // Creation demands a whole grid: 5 x 100 = 500 != 300 is refused, never fitted.
        assertFailsWith<DocException> {
            BoardDocumentOps.createSprite(root, "Bad", RectPx(-200, -100, 300, 300), SpriteGrid(5, 2, 100, 100), ::ids)
        }
        val before = BoardDocumentOps.createSprite(
            root, "Sheet", RectPx(-200, -100, 300, 300), SpriteGrid(3, 2, 100, 150), ::ids,
        ).doc
        val id = before.activeBoardId!!
        // Setting a grid re-fits the board to it at the same top-left: the board follows the cells.
        val set = BoardDocumentOps.setSpriteGrid(before, id, SpriteGrid(5, 2, 100, 100))
        assertEquals(RectPx(-200, -100, 500, 200), set.boards.last().rect)
        assertEquals(SpriteGrid(5, 2, 100, 100), set.boards.last().grid)
        assertEquals(before.layers, set.layers)
        assertTrue(DocOps.validate(set).isEmpty())
        // Raw zeros would clamp to 1 in the maths but are refused at the content boundary.
        assertFailsWith<DocException> {
            BoardDocumentOps.setSpriteGrid(before, id, SpriteGrid(0, 2, 100, 150))
        }
    }

    // ── 13. UI-safe clamping vs content-operation refusal ──────────────────────

    @Test
    fun contentOpsRefuseWhatUiMathsClamps() {
        // A legacy over-cap grid (5000 cols): DocOps.validate rule 5 only checks >= 1, so it is
        // SILENT here — pinned as a gap, not fixed by this contract.
        val overCap = paintDoc(
            spriteBoard(RectPx(0, 0, 5000, 10), SpriteGrid(cols = 5000, rows = 1, cellW = 1, cellH = 10), locked = true),
        )
        assertTrue(DocOps.validate(overCap).isEmpty(), "rule 5 does not cap cells or cell px")
        // Content refuses it; the maths clamps it to a 4096-col grid with no allocation.
        assertFailsWith<DocException> { SpriteCellOps.swap(overCap, "b-sprite", 0, 1) }
        assertEquals(RectPx(0, 0, 1, 10), SpriteGridMath.cellRect(overCap.boards.single(), 0))
        assertEquals(RectPx(4095, 0, 1, 10), SpriteGridMath.cellRect(overCap.boards.single(), 4095))
        assertFailsWith<IllegalArgumentException> { SpriteGridMath.cellRect(overCap.boards.single(), 4096) }
        // A grid BIGGER than its board is refused by content ops (cells would hang off the board).
        val protruding = paintDoc(
            spriteBoard(RectPx(-200, -100, 300, 300), SpriteGrid(cols = 3, rows = 2, cellW = 101, cellH = 150), locked = true),
        )
        assertFailsWith<DocException> { SpriteCellOps.swap(protruding, "b-sprite", 0, 5) }
        // A board bigger than its grid (spare strip) is NOT refused: the swap proceeds and every
        // transfer stays inside the grid box [-200, 100) x [-100, 200); the strip is never addressed.
        val spare = paintDoc(
            spriteBoard(RectPx(-200, -100, 310, 320), SpriteGrid(cols = 3, rows = 2, cellW = 100, cellH = 150), locked = true),
        )
        val change = SpriteCellOps.swap(spare, "b-sprite", 0, 5)
        assertSame(spare, change.doc)
        assertTrue(change.transfers.isNotEmpty())
        for (transfer in change.transfers) {
            val r = transfer.destinationRect
            assertTrue(r.x >= -200 && r.y >= -100 && r.x + r.w <= 100 && r.y + r.h <= 200, "strip pixel addressed: $r")
        }
    }

    // ── 14. no fractional cell reaches content ops ────────────────────────────

    @Test
    fun noFractionalCellReachesContentOps() {
        // The model is Int-only, so fractions can only arise mid-drag or mid-division, and both are
        // resolved before any content op: a 1 px drag over 3 columns is 0.33 px each and vanishes.
        assertEquals(
            SpriteGrid(cols = 3, rows = 2, cellW = 10, cellH = 10),
            SpriteGridMath.dragEdge(SpriteGrid(3, 2, 10, 10), SpriteGridMath.Edge.RIGHT, 1f, 0f),
        )
        // byCount floors the leftover OUTSIDE the grid rather than keeping a partial cell.
        val grid = SpriteGridMath.byCount(RectPx(0, 0, 500, 300), 3, 2)
        assertEquals(166, grid.cellW)
        assertEquals(498, grid.cols * grid.cellW, "2 px of board are outside the grid, not a fraction of a cell")
        // Fitting removes the leftover, and content ops on the fitted board address whole cells only.
        val fittedRect = SpriteGridMath.fitRect(RectPx(0, 0, 500, 300), grid)
        assertEquals(RectPx(0, 0, 498, 300), fittedRect)
        val doc = paintDoc(spriteBoard(fittedRect, grid, locked = true))
        val change = SpriteCellOps.swap(doc, "b-sprite", 0, 5)
        for (transfer in change.transfers) {
            val r = transfer.destinationRect
            assertTrue(r.x >= 0 && r.y >= 0 && r.x + r.w <= 498 && r.y + r.h <= 300, "fractional address: $r")
        }
    }
}

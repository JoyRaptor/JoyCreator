package cc.joycreator.joybrush.core.sprite

import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.DocJson
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.doc.SpriteGrid
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The sprite board (JB-4.01, the core half).
 *
 * The spec's cases first, in the spec's order, so a red test reads back against the spec without
 * hunting. [SpriteGridMathTest] in this same source set is the model for how these are written, and
 * the arithmetic is not re-derived here: where a test has a number of its own, the comment above the
 * assertion is where the working out is.
 *
 * The shape of the whole file is the Lead's `b` — a 500 x 300 SPRITE board at (0,0) carrying a grid
 * the TEST writes down itself — against the two ways a grid is made. Everything awkward in this row
 * comes from one fact: a board may be BIGGER than its grid, and the strip in between is real for as
 * long as nobody calls `fitted`.
 */
class SpriteBoardTest {

    // ── fixtures ───────────────────────────────────────────────────────────────

    private fun board(
        rect: RectPx,
        grid: SpriteGrid? = null,
        kind: BoardKind = BoardKind.SPRITE,
    ): Board = Board(id = "b-spr", name = "Sprite", kind = kind, rect = rect, grid = grid)

    /**
     * The Lead's fixture: 500 x 300 at (0,0) with a grid this test writes down itself, because the
     * whole row is about the relationship between the two. 7 x 4 of 64 is 448 x 256, so `b` is a
     * STALE board — 52 px of strip on the right, 44 px below — and test 1 proves it is fixture B.
     */
    private val b: Board =
        board(RectPx(0, 0, 500, 300), SpriteGrid(cols = 7, rows = 4, cellW = 64, cellH = 64))

    /**
     * A — DELIBERATELY NOT FITTED. `byCount(3, 2)` over 500 x 300 is 3 x 2 of 166 x 150, which is
     * 498 x 300: 2 px of board outside the grid. The smallest spare strip a board can have, and the
     * reason the old test 5 could not be built (see test 9).
     */
    private fun staleByCount(): Board =
        board(RectPx(0, 0, 500, 300), SpriteGridMath.byCount(RectPx(0, 0, 500, 300), 3, 2))

    /**
     * B — a spare strip WIDER than the 22 dp grab on both sides, so the two halves of the old test 5
     * can both be true at the same point. `bySize(64, 64)` leaves 52 px on the right and 44 px below.
     */
    private fun staleBySize(): Board =
        board(RectPx(0, 0, 500, 300), SpriteGridMath.bySize(RectPx(0, 0, 500, 300), 64, 64))

    /** 8 columns of 64 x 64 filling 512 x 256 exactly: the grid every drag in this file is tested on. */
    private fun eightByFour(): Board =
        board(RectPx(0, 0, 512, 256), SpriteGrid(cols = 8, rows = 4, cellW = 64, cellH = 64))

    /** The same board with a different stored grid — what a stepper actually does between presses. */
    private fun SpriteBoard.holding(grid: SpriteGrid): SpriteBoard =
        SpriteBoard(board.copy(grid = grid), density)

    private fun documentWith(board: Board): JbDocument =
        DocOps.newDocument("doc", "Test", 512, 256) { "fixed" }
            .copy(boards = listOf(board), activeBoardId = board.id)

    /** Segment lists are FloatArrays, which compare by IDENTITY, so they are compared by their numbers. */
    private fun assertSameLines(expected: List<FloatArray>, actual: List<FloatArray>, what: String) {
        assertEquals(expected.size, actual.size, "$what: ${actual.size} segments against ${expected.size}")
        for (i in expected.indices) {
            assertEquals(expected[i].toList(), actual[i].toList(), "$what: segment $i")
        }
    }

    // ── 1. by size ─────────────────────────────────────────────────────────────

    @Test
    fun bySizeMatchesTheMathsExactly() {
        val sprite = SpriteBoard(b)
        val grid = sprite.bySize(64, 64)
        // Against the maths FIRST, so a wrong `bySize` here cannot be excused by a wrong literal.
        assertEquals(SpriteGridMath.bySize(RectPx(0, 0, 500, 300), 64, 64), grid, "against SpriteGridMath")
        assertEquals(SpriteGrid(cols = 7, rows = 4, cellW = 64, cellH = 64), grid, "against the literal")
        // 7 whole cells of 64 fit 500 (7 x 64 = 448; 8 x 64 = 512 does not) and 4 rows fit 300
        // (4 x 64 = 256; 5 x 64 = 320 does not). What is left over is the spare strip.
        assertEquals(RectPx(0, 0, 448, 256), sprite.fitted(grid).rect)
        assertEquals(52, b.rect.w - 448, "the strip is 52 px wide on this board")
        assertEquals(44, b.rect.h - 256, "and 44 px tall")
        // The Lead's `b` and fixture B are the same board, which is why tests 6, 7, 8 and 18 may use
        // `b` and tests 10 to 13 may call it fixture B and both be talking about one thing.
        assertEquals(b.grid, sprite.bySize(64, 64))
        assertEquals(b.rect, SpriteBoard(staleBySize()).board.rect)
    }

    @Test
    fun bySizeLeavesTheRemainderOutsideTheGrid() {
        val rect = RectPx(0, 0, 512, 256)
        val sprite = SpriteBoard(board(rect))
        val grid = sprite.bySize(100, 100)
        // 5 x 2 and not 6 x 3: 6 x 100 is 600 > 512 and 3 x 100 is 300 > 256.
        assertEquals(SpriteGrid(cols = 5, rows = 2, cellW = 100, cellH = 100), grid)
        val fitted = sprite.fitted(grid)
        assertEquals(RectPx(0, 0, 500, 200), fitted.rect)
        // Two rects, because "the leftover" is only visible when the leftover is bigger than zero.
        assertNotEquals(rect, fitted.rect, "the leftover is gone from the board (Decision 2)")
        assertEquals(12, rect.w - fitted.rect.w, "512 - 5 x 100")
        assertEquals(56, rect.h - fitted.rect.h, "256 - 2 x 100")
    }

    // ── 2. by count ────────────────────────────────────────────────────────────

    @Test
    fun byCountMatchesTheMathsExactly() {
        val sprite = SpriteBoard(staleByCount())
        val grid = sprite.byCount(3, 2)
        // 500 / 3 = 166.67 floored, 300 / 2 = 150. The 2 px is the floor's, not a rounding error.
        assertEquals(SpriteGridMath.byCount(RectPx(0, 0, 500, 300), 3, 2), grid)
        assertEquals(SpriteGrid(cols = 3, rows = 2, cellW = 166, cellH = 150), grid)
        val fitted = sprite.fitted(grid)
        assertEquals(RectPx(0, 0, 498, 300), fitted.rect)
        assertNotEquals(RectPx(0, 0, 500, 300), fitted.rect, "the 2 px remainder is real until `fitted`")
        assertEquals(RectPx(0, 0, 500, 300), sprite.board.rect, "and asking for a grid moves no board")
    }

    // ── 3. every grid change fits ──────────────────────────────────────────────

    @Test
    fun everyGridChangeLeavesTheBoardExactlyTheSizeOfItsGrid() {
        // 20 combinations, three origins so a negative x and a positive y are in here too: a board's
        // top-left is where it is and `fitRect` keeps it there, so the SIZE is the only thing under
        // test and the origin only has to survive.
        val rects = listOf(RectPx(0, 0, 500, 300), RectPx(12, -8, 512, 256), RectPx(-40, 64, 300, 200))
        val cases = ArrayList<Pair<String, Pair<Board, SpriteGrid>>>(20)
        for (rect in rects) {
            val sprite = SpriteBoard(board(rect))
            for ((w, h) in listOf(64 to 64, 100 to 100, 37 to 21)) {
                val grid = sprite.bySize(w, h)
                cases += "bySize($w, $h) on $rect" to (sprite.fitted(grid) to grid)
            }
            for ((c, r) in listOf(3 to 2, 7 to 4, 1 to 1)) {
                val grid = sprite.byCount(c, r)
                cases += "byCount($c, $r) on $rect" to (sprite.fitted(grid) to grid)
            }
        }
        val drag = SpriteBoard(eightByFour())
        cases += "dragged(CORNER, 80, -50)" to drag.dragged(SpriteGridMath.Edge.CORNER, 80f, -50f)
        cases += "dragged(RIGHT, -1000000, 0)" to drag.dragged(SpriteGridMath.Edge.RIGHT, -1_000_000f, 0f)
        assertEquals(20, cases.size, "20 combinations of bySize, byCount and dragged")

        for ((what, pair) in cases) {
            val (after, grid) = pair
            // `==` on Ints and no tolerance: the board IS the grid, to the pixel.
            assertEquals(grid.cols * grid.cellW, after.rect.w, "$what: board width")
            assertEquals(grid.rows * grid.cellH, after.rect.h, "$what: board height")
            assertEquals(grid, after.grid, "$what: the board carries that very grid")
        }
    }

    // ── 4. a grid belongs to a SPRITE board and nowhere else ───────────────────

    @Test
    fun withGridRefusesABoardThatIsNotASpriteBoard() {
        val canvas = board(RectPx(0, 0, 500, 300), kind = BoardKind.CANVAS)
        val grid = SpriteGrid(cols = 3, rows = 2, cellW = 166, cellH = 150)
        val refused = assertFailsWith<IllegalArgumentException> { SpriteBoard(canvas).withGrid(grid) }
        assertTrue(
            refused.message.orEmpty().contains("CANVAS"),
            "the message names the kind, so a caller can see which board it was: ${refused.message}",
        )
        // A SPRITE board is not refused, and writing a grid does NOT resize the board: that is
        // `fitted`'s job, and the two are separate so a stale board can be made on purpose.
        val written = SpriteBoard(b).withGrid(grid)
        assertEquals(grid, written.grid)
        assertEquals(b.rect, written.rect)
    }

    // ── 5. the stepper ─────────────────────────────────────────────────────────

    @Test
    fun aStepUpAndAStepDownAreTheSameArithmeticAsTheSetters() {
        val size = SpriteBoard(b)
        assertEquals(size.bySize(65, 64), size.stepped(SpriteBoard.Axis.CELL_W, 1), "a step up")
        assertEquals(size.bySize(63, 64), size.stepped(SpriteBoard.Axis.CELL_W, -1), "a step down")
        val count = SpriteBoard(staleByCount())
        assertEquals(count.byCount(4, 2), count.stepped(SpriteBoard.Axis.COLS, 1), "a step up")
        assertEquals(count.byCount(2, 2), count.stepped(SpriteBoard.Axis.COLS, -1), "a step down")
        // And the axis the step did not touch comes out of the other setter untouched: 3 columns of
        // 500 is 166 either way, so a step on rows leaves cols bit-identical.
        val rows = count.stepped(SpriteBoard.Axis.ROWS, 1)
        assertEquals(3, rows.cols, "cols is bit-identical")
        assertEquals(count.byCount(3, 3), rows)
    }

    @Test
    fun aStepIsNeverZeroAndNeverANoOpOnAOneCountGrid() {
        val one = SpriteBoard(
            board(RectPx(0, 0, 500, 300), SpriteGridMath.byCount(RectPx(0, 0, 500, 300), 1, 1)),
        )
        val down = one.stepped(SpriteBoard.Axis.COLS, -1)
        // 0 columns is a grid with no reading order, and throwing would be a second answer to a
        // question SpriteGridMath has already answered. So: 1, and the cell is the whole board.
        assertEquals(1, down.cols, "not 0, and not a throw")
        assertEquals(1, down.rows)
        assertEquals(500, down.cellW)
        assertEquals(one.byCount(0, 1), down, "and it is exactly `colsOf`'s answer")
        assertEquals(1, one.stepped(SpriteBoard.Axis.ROWS, -1).rows, "and the same on rows")
    }

    @Test
    fun theStepperDoesNotTouchDensity() {
        val at = listOf(1f, 2f, 3f).map { SpriteBoard(b, density = it) }
        for (other in at.drop(1)) {
            assertEquals(at[0].bySize(64, 64), other.bySize(64, 64), "bySize at density ${other.density}")
            assertEquals(at[0].byCount(3, 2), other.byCount(3, 2), "byCount at density ${other.density}")
            assertEquals(
                at[0].stepped(SpriteBoard.Axis.CELL_W, 1),
                other.stepped(SpriteBoard.Axis.CELL_W, 1),
                "stepped at density ${other.density}",
            )
        }
        // Density is not IGNORED, it is a TOUCH number and it reaches exactly one of them. Without
        // these lines the five above would also pass on a class that dropped density altogether,
        // which is a different mistake with the same green.
        assertEquals(22f, SpriteBoard(b, density = 1f).edgeGrabPx)
        assertEquals(44f, SpriteBoard(b, density = 2f).edgeGrabPx)
        assertEquals(66f, SpriteBoard(b, density = 3f).edgeGrabPx)
        assertEquals(22f, SpriteBoard.EDGE_GRAB_PX_DP)
    }

    // ── 6. the spare strip, and the grab ───────────────────────────────────────

    @Test
    fun aTouchOnTheSpareStripIsNotACell() {
        val unfitted = staleByCount()
        val grid = unfitted.grid!!
        val fitted = RectPx(0, 0, 498, 300)
        // The "stale" claim is asserted, not assumed: 3 x 166 = 498 and the board is 500 wide.
        assertEquals(fitted, SpriteGridMath.fitRect(unfitted.rect, grid))
        assertNotEquals(unfitted.rect, fitted)

        val stale = SpriteBoard(unfitted)
        val onTheGrid = SpriteBoard(unfitted.copy(rect = fitted))
        // 499 is inside the BOARD and outside the grid, and the last column's last pixel is still a
        // cell — so the two answers are about the 2 px, not about the board.
        assertEquals(-1, stale.cellAt(499f, 10f), "499 is in the strip")
        assertEquals(2, stale.cellAt(497.999f, 10f), "the last column's last pixel is still cell 2")
        // On the FITTED board there is no strip at all, which is why this test could not be built on
        // one: 498 is the first x past the grid, and 497.999 is still cell 2.
        assertEquals(2, onTheGrid.cellAt(497.999f, 10f))
        assertEquals(-1, onTheGrid.cellAt(498f, 10f))

        // ...and `edgeAt` is NOT null at 499, and this is the derivation the old test 5 got wrong.
        // 499 is ONE document px from the grid's right edge and the grab is 22 dp, so a finger there
        // is inside the zone whatever shape the zone has. The handle is decided ONCE, at down
        // (Decision 4), and the drag that follows resizes the board onto the grid, which is
        // Decision 2. No implementation can answer null AND -1 at this point.
        assertEquals(SpriteGridMath.Edge.RIGHT, stale.edgeAt(499f, 10f, 1f), "1 px from a 22 dp grab")
        assertEquals(SpriteGridMath.Edge.RIGHT, onTheGrid.edgeAt(498f, 10f, 1f), "the edge is in its own zone")
    }

    @Test
    fun aTouchOutsideTheGrabIsNeitherAHandleNorACell() {
        val sprite = SpriteBoard(staleBySize())
        // 448 wide, 256 tall, on a 500 x 300 board: 52 px of strip on the right, 44 px below, and
        // both are wider than the 22 dp grab. That is the whole reason this fixture exists.
        assertEquals(52, b.rect.w - 448)
        assertEquals(44, b.rect.h - 256)
        // 32 px right of the grid's right edge: out of the grab, and not a cell. Both halves, at a
        // point where both can be true.
        assertNull(sprite.edgeAt(480f, 20f, 1f), "32 px out on a 22 px grab")
        assertEquals(-1, sprite.cellAt(480f, 20f), "7 columns of 64 is 448, so 480 is in the strip")
        // 12 px from the right edge: inside the grab, so the handle is there — and `cellAt` still
        // says -1, because the handle beats the cell and the strip is grabbable.
        assertEquals(SpriteGridMath.Edge.RIGHT, sprite.edgeAt(460f, 20f, 1f))
        assertEquals(-1, sprite.cellAt(460f, 20f))
        // The same two answers on the bottom edge: 34 px below is out, 14 px below is in.
        assertNull(sprite.edgeAt(20f, 290f, 1f), "34 px below the bottom edge")
        assertEquals(-1, sprite.cellAt(20f, 290f))
        assertEquals(SpriteGridMath.Edge.BOTTOM, sprite.edgeAt(20f, 270f, 1f), "14 px below")
        assertEquals(-1, sprite.cellAt(20f, 270f))
        // And on the FITTED board the strip is gone and the edge is inside its own zone.
        val onTheGrid = SpriteBoard(sprite.fitted(b.grid!!))
        assertEquals(RectPx(0, 0, 448, 256), onTheGrid.board.rect)
        assertEquals(-1, onTheGrid.cellAt(448f, 20f))
        assertEquals(SpriteGridMath.Edge.RIGHT, onTheGrid.edgeAt(448f, 20f, 1f))
    }

    @Test
    fun theCornerWinsOverBothEdgesAndTheEdgeItselfIsInsideItsOwnZone() {
        val sprite = SpriteBoard(staleBySize())
        assertEquals(SpriteGridMath.Edge.CORNER, sprite.edgeAt(448f, 256f, 1f), "the exact corner")
        // The next three separate the zones, and none of them is near the corner: a 22 dp two-sided
        // zone on each line makes the corner a 44 x 44 square, and anything inside that square is
        // CORNER by construction rather than by a tie-break.
        assertEquals(SpriteGridMath.Edge.RIGHT, sprite.edgeAt(448f, 230f, 1f), "26 px above the bottom edge")
        assertEquals(SpriteGridMath.Edge.BOTTOM, sprite.edgeAt(420f, 256f, 1f), "28 px left of the right edge")
        // 6 px above the corner, inside BOTH grabs. The draft asked for RIGHT here, which test 13
        // forbids and which "the corner is checked first" cannot produce. A corner that resolved to
        // an edge would resize one axis when the finger meant both.
        assertEquals(SpriteGridMath.Edge.CORNER, sprite.edgeAt(448f, 250f, 1f), "inside both grabs")
        // And away from both lines there is no handle at all: 480 is 32 px right of the grid's right
        // edge and 280 is 24 px below its bottom edge, so it is outside both grabs — and on no cell
        // either, though it is well inside the 500 x 300 board.
        assertNull(sprite.edgeAt(480f, 280f, 1f), "outside both grabs")
        assertEquals(-1, sprite.cellAt(480f, 280f), "and on no cell")
        // Test 5's refusal, on this same fixture: a SPRITE board is fine, a CANVAS board is not.
        val canvas = board(sprite.board.rect, kind = BoardKind.CANVAS)
        val refused = assertFailsWith<IllegalArgumentException> { SpriteBoard(canvas).withGrid(b.grid!!) }
        assertTrue(refused.message.orEmpty().contains("CANVAS"), "the refusal names the kind")
    }

    @Test
    fun theGrabIsTwoSidedOnTheEdgeLine() {
        val sprite = SpriteBoard(staleBySize())
        // A finger is wider than a 1 px line, and a zone that existed only OUTSIDE the grid could
        // not be hit at all: the finger would land on a cell first. So the zone is |delta| <= 22 on
        // both sides of each line, and 426 and 470 are exactly 22 from the edge at 448.
        assertEquals(SpriteGridMath.Edge.RIGHT, sprite.edgeAt(426f, 20f, 1f), "22 px INSIDE, on the last cell")
        assertEquals(6, sprite.cellAt(426f, 20f), "and that point is a cell: 426 / 64 = column 6")
        assertEquals(SpriteGridMath.Edge.RIGHT, sprite.edgeAt(470f, 20f, 1f), "22 px OUTSIDE, in the strip")
        assertEquals(-1, sprite.cellAt(470f, 20f), "and out there it is not a cell")
        // The same on the bottom edge, whose line is at 256: 234 is inside and 278 is outside.
        assertEquals(SpriteGridMath.Edge.BOTTOM, sprite.edgeAt(20f, 234f, 1f), "22 px inside")
        assertEquals(21, sprite.cellAt(20f, 234f), "234 / 64 = row 3, so index 3 * 7 + 0")
        assertEquals(SpriteGridMath.Edge.BOTTOM, sprite.edgeAt(20f, 278f, 1f), "22 px outside")
        assertEquals(-1, sprite.cellAt(20f, 278f), "and out there it is not a cell")
    }

    @Test
    fun aHandleIsDecidedOnceAndTheGridCannotBeGrabbedByBothAtOnce() {
        val sprite = SpriteBoard(staleBySize())
        val grab = SpriteBoard.EDGE_GRAB_PX_DP
        var corners = 0
        var rights = 0
        var bottoms = 0
        var nones = 0
        // 61 x 61 probes at 1 px steps around the corner at (448, 256) — wide enough to leave the
        // corner square on all four sides, so all four answers have to appear or nothing is proved.
        for (dx in -30..30) {
            for (dy in -30..30) {
                val x = 448 + dx
                val y = 256 + dy
                val at = sprite.edgeAt(x.toFloat(), y.toFloat(), 1f)
                val nearRight = abs(dx) <= grab
                val nearBottom = abs(dy) <= grab
                // A nullable return cannot be two edges, so the claim is the ORDER: every point within
                // the grab of BOTH lines is CORNER, and a point within one of them is that one edge.
                when {
                    nearRight && nearBottom -> {
                        assertEquals(SpriteGridMath.Edge.CORNER, at, "inside both grabs at $x,$y")
                        corners++
                    }
                    nearRight -> {
                        assertEquals(SpriteGridMath.Edge.RIGHT, at, "inside the right grab only at $x,$y")
                        rights++
                    }
                    nearBottom -> {
                        assertEquals(SpriteGridMath.Edge.BOTTOM, at, "inside the bottom grab only at $x,$y")
                        bottoms++
                    }
                    else -> {
                        assertNull(at, "outside both grabs at $x,$y")
                        nones++
                    }
                }
            }
        }
        // 45 x 45 in the corner square, 45 x 16 on each arm, 16 x 16 in the far corner. The counts are
        // written out because "no probe contradicted the rule" is also what a box lying entirely
        // inside the corner square would say.
        assertEquals(45 * 45, corners, "every point within 22 px of both lines")
        assertEquals(45 * 16, rights, "the right arm, below the corner square")
        assertEquals(16 * 45, bottoms, "the bottom arm, left of the corner square")
        assertEquals(16 * 16, nones, "and the far corner of the box")
        assertEquals(61 * 61, corners + rights + bottoms + nones, "every probe answered exactly once")
    }

    @Test
    fun theGrabIsSizedInScreenPixelsAndTheZoomIsNotInverted() {
        val sprite = SpriteBoard(staleBySize())
        // Every probe is pinned to y = 20: the grid's bottom edge is at 256, so 20 is 236 px above it
        // and no probe here can land in the corner square and turn this into a test of the corner
        // rule. The right edge is at 448.
        //
        // At screenPerDoc = 4 (zoomed IN) a document px is 4 screen px, so the 22 screen px grab is
        // 5.5 document px. Six document px away is 24 screen px and misses; five is 20 and hits.
        assertNull(sprite.edgeAt(454f, 20f, 4f), "6 doc px is 24 screen px")
        assertNull(sprite.edgeAt(455f, 20f, 4f), "7 doc px is 28 screen px")
        assertEquals(SpriteGridMath.Edge.RIGHT, sprite.edgeAt(453f, 20f, 4f), "5 doc px is 20 screen px")
        // At screenPerDoc = 0.25 (zoomed OUT) the same 22 screen px is 88 document px, so 80 hits and
        // 100 misses. DIVIDING by the zoom instead of multiplying inverts BOTH directions: 5 and 6
        // document px would become 1.25 and 1.5, so the two nulls above would answer RIGHT, and 80
        // document px would become 320 and answer null. Four of the six probes above are what
        // redden, which is why there are six.
        assertEquals(SpriteGridMath.Edge.RIGHT, sprite.edgeAt(528f, 20f, 0.25f), "80 doc px is 20 screen px")
        assertEquals(SpriteGridMath.Edge.RIGHT, sprite.edgeAt(470f, 20f, 0.25f), "22 doc px is 5.5 screen px")
        assertNull(sprite.edgeAt(548f, 20f, 0.25f), "100 doc px is 25 screen px")
        // Pass the zoom, never its inverse: the seam is a multiply (Decision 5). The same point with
        // the zoom the other way round is a different answer — 6 / 0.25 is 24 screen px, a miss —
        // so this line is here to make the inversion a red test rather than a note.
        assertEquals(SpriteGridMath.Edge.RIGHT, sprite.edgeAt(453f, 20f, 4f))
        assertEquals(SpriteGridMath.Edge.RIGHT, sprite.edgeAt(454f, 20f, 1f / 4f), "the inverted zoom")
    }

    // ── 7. dragging ────────────────────────────────────────────────────────────

    @Test
    fun theIgnoredAxisIsStillPassedAndStillIgnored() {
        val sprite = SpriteBoard(eightByFour())
        val start = sprite.board.grid!!

        val (cornerBoard, corner) = sprite.dragged(SpriteGridMath.Edge.CORNER, 80f, -50f)
        // 80 over 8 columns is 10 each; -50 over 4 rows is -12.5, which rounds AWAY from zero to -13.
        assertEquals(74, corner.cellW)
        assertEquals(51, corner.cellH)
        assertNotEquals(start, corner, "the corner moves both")
        assertEquals(RectPx(0, 0, 592, 204), cornerBoard.rect, "8 x 74 by 4 x 51")

        // The ignored axis is passed a value that would wreck the grid if it were read: -9999 on the
        // y of a RIGHT drag and +9999 on the x of a BOTTOM drag. 4.01a pins the same thing on
        // `dragEdge`; this pins it through the board, which is where a view's pre-zeroing would live
        // (Decision 6).
        val (rightBoard, right) = sprite.dragged(SpriteGridMath.Edge.RIGHT, 80f, -9_999f)
        assertEquals(74, right.cellW, "RIGHT uses dx")
        assertEquals(64, right.cellH, "and leaves cellH bit-identical")
        assertEquals(8, right.cols)
        assertEquals(4, right.rows)
        assertEquals(RectPx(0, 0, 592, 256), rightBoard.rect, "the returned board is fitRect'd")

        val (bottomBoard, bottom) = sprite.dragged(SpriteGridMath.Edge.BOTTOM, 9_999f, 80f)
        assertEquals(64, bottom.cellW, "BOTTOM uses dy")
        assertEquals(84, bottom.cellH, "80 / 4 = 20 each")
        assertEquals(RectPx(0, 0, 512, 336), bottomBoard.rect)
        // The pair agrees with itself: the board carries exactly the grid it was fitted to.
        for ((grid, after) in listOf(corner to cornerBoard, right to rightBoard, bottom to bottomBoard)) {
            assertEquals(grid, after.grid, "the board carries the dragged grid")
        }
    }

    @Test
    fun aCellIsNeverRefusedHereItIsClampedOnceInTheMaths() {
        val sprite = SpriteBoard(eightByFour())
        val (shortBoard, short) = sprite.dragged(SpriteGridMath.Edge.BOTTOM, 0f, -1_000_000f)
        // 64 - 250000 clamps to 1, and the clamp is the maths's and is tested there. Refusing here
        // would be a second answer to one question (Decision 7), so both numbers are given rather
        // than "it did not crash": 8 columns of 64 wide, 4 rows of 1 tall.
        assertEquals(1, short.cellH)
        assertEquals(64, short.cellW)
        assertEquals(4, short.rows)
        assertEquals(RectPx(0, 0, 512, 4), shortBoard.rect, "8 x 64 by 4 x 1, still fitRect'd")

        val (thinBoard, thin) = sprite.dragged(SpriteGridMath.Edge.RIGHT, -1_000_000f, 0f)
        assertEquals(1, thin.cellW)
        assertEquals(64, thin.cellH)
        assertEquals(8, thin.cols)
        assertEquals(RectPx(0, 0, 8, 256), thinBoard.rect, "8 x 1 by 4 x 64, still fitRect'd")

        // And the board this class was handed did not move for any of that.
        assertEquals(RectPx(0, 0, 512, 256), sprite.board.rect)
    }

    // ── 8. used, derived ───────────────────────────────────────────────────────

    @Test
    fun usedCellsAreComputedAndACellWithNothingInItIsNotUsed() {
        val cells = MutableList(40) { ByteArray(64 * 64 * 4) }
        cells[0][3] = 0x7F.toByte()
        cells[17][(63 * 64 + 63) * 4 + 3] = 0x01.toByte()
        cells[39][3] = 0xFF.toByte()
        val sprite = SpriteBoard(staleBySize())
        // The list is the list: `usedCellsOf` is handed 40 cells and told nothing about this board's
        // 28, and the index it answers with is the index in the list, which is reading order.
        assertEquals(40, cells.size)
        assertEquals(setOf(0, 17, 39), sprite.usedCellsOf(cells))

        // Erasing a cell makes it NOT used, with no flag to clear: that is what deriving it buys.
        cells[17] = ByteArray(64 * 64 * 4)
        assertEquals(setOf(0, 39), sprite.usedCellsOf(cells))

        // A single pixel of alpha 1 IS used. 1 is 1/255 of an ink, and it is the difference between
        // a stored flag (which would need a deliberate "emptied" state to agree with) and a
        // definition.
        cells[5] = ByteArray(64 * 64 * 4).also { it[3] = 0x01.toByte() }
        assertEquals(setOf(0, 5, 39), sprite.usedCellsOf(cells))

        // Opaque colour with no alpha is NOT ink, so a cell that is 0xFF everywhere except in the
        // alpha byte is not used. An "any non-zero byte" implementation would say it was, and that is
        // the one wrong answer this function could give on real pixels.
        cells[6] = ByteArray(64 * 64 * 4) { 0xFF.toByte() }.also { bytes ->
            for (p in 0 until bytes.size step 4) bytes[p + 3] = 0
        }
        assertEquals(setOf(0, 5, 39), sprite.usedCellsOf(cells), "opaque colour, transparent")

        // A cell of another size is used on its own terms: 8 x 8 is 256 bytes, not 16384.
        cells[7] = ByteArray(8 * 8 * 4).also { it[(4 * 8 + 4) * 4 + 3] = 0x80.toByte() }
        assertEquals(setOf(0, 5, 7, 39), sprite.usedCellsOf(cells))

        // And nothing at all is nothing at all.
        assertEquals(emptySet<Int>(), sprite.usedCellsOf(List(3) { ByteArray(64 * 64 * 4) }))
        assertEquals(emptySet<Int>(), sprite.usedCellsOf(emptyList()))
    }

    // ── 9. the four steppers are four claims ───────────────────────────────────

    @Test
    fun theFourSteppersAreIndependent() {
        val sprite = SpriteBoard(b)
        val start = b.grid!!
        // What "independent" can mean, and what it cannot, is worth the two blocks below.
        //
        // It means each axis moves exactly ONE argument of exactly ONE setter, and that is checkable
        // as an equality with the setter the spec says it goes through. Four claims, four equalities.
        assertEquals(sprite.bySize(65, 64), sprite.stepped(SpriteBoard.Axis.CELL_W, 1))
        assertEquals(sprite.bySize(64, 65), sprite.stepped(SpriteBoard.Axis.CELL_H, 1))
        assertEquals(sprite.byCount(8, 4), sprite.stepped(SpriteBoard.Axis.COLS, 1))
        assertEquals(sprite.byCount(7, 5), sprite.stepped(SpriteBoard.Axis.ROWS, 1))

        // On the two SIZE axes exactly one number of the grid moves and the other three are
        // bit-identical: 64 -> 65 on 500 px is still 7 columns (500 / 65 = 7.69) and on 300 px is
        // still 4 rows (300 / 65 = 4.6), so the count does not re-flow.
        val w = sprite.stepped(SpriteBoard.Axis.CELL_W, 1)
        assertEquals(start.cols, w.cols)
        assertEquals(start.rows, w.rows)
        assertEquals(start.cellH, w.cellH)
        assertEquals(65, w.cellW)
        assertNotEquals(start, w)
        val h = sprite.stepped(SpriteBoard.Axis.CELL_H, 1)
        assertEquals(start.cols, h.cols)
        assertEquals(start.rows, h.rows)
        assertEquals(start.cellW, h.cellW)
        assertEquals(65, h.cellH)
        assertNotEquals(start, h)

        // On the two COUNT axes only the COUNT moves and the other count is bit-identical. The
        // spec's wording — "each change exactly one of cellW, cellH, cols, rows" — is NOT reachable
        // here and is NOT asserted, because `byCount` divides the board between the columns and rows
        // it is given: 7 -> 8 columns on a 500 px board is 62 px and not 64, whatever a caller
        // wishes. A cell edge that stayed put would mean a 512 px grid on a 500 px board, and a
        // stepper is not allowed to grow the canvas. This is SpriteGridMath's rule and this class
        // does not second-guess it, so what is asserted is the claim that IS true: the count moved,
        // the other count did not, and the cell re-filled the board it was handed.
        val c = sprite.stepped(SpriteBoard.Axis.COLS, 1)
        assertEquals(start.cols + 1, c.cols)
        assertEquals(start.rows, c.rows, "the other count is bit-identical")
        assertEquals(500 / 8, c.cellW, "and the cell width re-filled the board: 62, not 64")
        assertEquals(300 / 4, c.cellH, "and the cell height with it: 75, not 64")
        val r = sprite.stepped(SpriteBoard.Axis.ROWS, 1)
        assertEquals(start.rows + 1, r.rows)
        assertEquals(start.cols, r.cols, "the other count is bit-identical")
        assertEquals(500 / 7, r.cellW, "and the cell width re-filled the board: 71, not 64")
        assertEquals(300 / 5, r.cellH, "and the cell height with it: 60, not 64")

        // And no step touched the board it was asked about, or leaked from one axis into another.
        assertEquals(start, b.grid)
        assertEquals(start.cols, w.cols)
        assertEquals(start.cols, h.cols)
        assertEquals(start.rows, c.rows)
        assertEquals(start.cols, r.cols)
    }

    // ── 10. steps both ways round ──────────────────────────────────────────────

    @Test
    fun aSteppersBothWaysRoundAreTheSameBoard() {
        val size = SpriteBoard(b)
        val sizeStart = size.bySize(64, 64)
        assertEquals(size.board.grid, sizeStart, "`b` and bySize(64, 64) are one grid")
        assertEquals(20, SIZE_STEPS.size, "20 step sequences against bySize(64, 64)")
        for ((i, sequence) in SIZE_STEPS.withIndex()) {
            val there = size.run(sequence)
            val back = size.holding(there).run(sequence.undone())
            assertEquals(sizeStart, back, "size sequence $i (${sequence.describe()}) came back elsewhere")
        }

        val count = SpriteBoard(staleByCount())
        val countStart = count.byCount(3, 2)
        assertEquals(count.board.grid, countStart, "fixture A and byCount(3, 2) are one grid")
        assertEquals(20, COUNT_STEPS.size, "20 step sequences against byCount(3, 2)")
        for ((i, sequence) in COUNT_STEPS.withIndex()) {
            val there = count.run(sequence)
            val back = count.holding(there).run(sequence.undone())
            val landsOn = sequence.landsOn
            if (landsOn != null) {
                // The two sequences that hit the clamp are listed in the table WITH the grid the
                // round trip really lands on, so the clamp is visible rather than a surprise:
                // stepping a count down off 1 is a no-op, and so is undoing a step that did nothing.
                assertEquals(
                    count.byCount(landsOn.first, landsOn.second),
                    back,
                    "clamp sequence $i (${sequence.describe()}) must land exactly here",
                )
            } else {
                assertEquals(countStart, back, "count sequence $i (${sequence.describe()}) came back elsewhere")
            }
        }
    }

    // ── 11. the sub-grid ───────────────────────────────────────────────────────

    @Test
    fun aSubGridOfOneIsOffAndNotALineDownEveryCell() {
        val two = board(RectPx(0, 0, 128, 64), SpriteGrid(cols = 2, rows = 1, cellW = 64, cellH = 64))
        val sprite = SpriteBoard(two)
        // "Off" is an empty list. It is never a `div` of 1, which would be a line down the middle of
        // every cell and would then have to be special-cased everywhere it is drawn (Decision 11).
        for (div in listOf(1, 0, -3, 9, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertEquals(0, sprite.subGridLines(div).size, "div = $div is off, not a line down every cell")
        }
        // Counted, not described: 2 cells x (1 vertical + 1 horizontal), interiors only.
        assertEquals(4, sprite.subGridLines(2).size)
        // 2 cells x (7 vertical + 7 horizontal).
        assertEquals(28, sprite.subGridLines(8).size)
        // Verbatim: this class adds nothing to the maths, including nothing to the segment order.
        assertSameLines(SpriteGridMath.subGridLines(two, 2), sprite.subGridLines(2), "div = 2")
        assertSameLines(SpriteGridMath.subGridLines(two, 8), sprite.subGridLines(8), "div = 8")
        // A board with no grid has no sub-grid, and the 2..8 range is the maths's to hold.
        assertEquals(0, SpriteBoard(board(RectPx(0, 0, 128, 64))).subGridLines(4).size)
    }

    @Test
    fun theSubGridIsNotInTheDocument() {
        val doc = documentWith(staleBySize())
        val before = DocJson.encode(doc)
        val sprite = SpriteBoard(doc.boards.single())
        // Real lines are asked for, so the bytes below are compared across a call that DID something.
        assertTrue(sprite.subGridLines(3).isNotEmpty(), "div = 3 draws lines")
        assertTrue(sprite.subGridLines(1).isEmpty(), "and div = 1 is off")
        val after = DocJson.encode(doc)
        // The document's OWN BYTES, not a rendering of it: the sub-grid is a helper and is never in
        // the document, so a render would have been identical by construction and would have proved
        // nothing (Decision 12).
        assertEquals(before, after, "asking for lines changed the document")
        assertFalse(before.contains("subGrid"), "and the key is not in the format")
        assertFalse(before.contains("div"), "nor any 'div' of it")
        // The grid IS in the file, so this is not a document with nothing in it at all.
        assertTrue(before.contains("\"grid\""))
        assertTrue(before.contains("\"cellW\""))
    }

    // ── 12. nothing here mutates ───────────────────────────────────────────────

    @Test
    fun noFunctionInThisFileReturnsAMutatedDocument() {
        val original = staleBySize()
        val snapshot = original.copy()
        val sprite = SpriteBoard(original)
        val grid = SpriteGrid(cols = 3, rows = 2, cellW = 32, cellH = 32)

        val withGrid = sprite.withGrid(grid)
        val fitted = sprite.fitted(grid)
        val (draggedBoard, dragged) = sprite.dragged(SpriteGridMath.Edge.RIGHT, 40f, 40f)

        for ((what, after) in listOf("withGrid" to withGrid, "fitted" to fitted, "dragged" to draggedBoard)) {
            assertNotSame(original, after, "$what handed back the very board it was given")
            assertNotEquals(snapshot, after, "$what returned the board it was given unchanged")
        }
        // The claim itself: the board this class holds is untouched, field for field and by identity.
        // An undo stack holds documents, and a view that mutated one is a view the undo stack cannot
        // describe (Decision 13).
        assertEquals(snapshot, original, "the board this class was given was mutated")
        assertSame(original, sprite.board, "and it is not the same instance it was handed")
        assertEquals(b.grid, original.grid, "the stored grid is untouched too")
        assertEquals(500, original.rect.w, "and the spare strip is still there")
        // The three that write a board write a DIFFERENT one, grid and all.
        assertEquals(grid, withGrid.grid)
        assertEquals(grid, fitted.grid)
        assertEquals(dragged, draggedBoard.grid)
        assertEquals(7, dragged.cols)
    }

    // ── helpers for stepping ───────────────────────────────────────────────────

    /** One press of one `−`/`+`: an axis and how far. */
    private data class Step(val axis: SpriteBoard.Axis, val delta: Int)

    /**
     * A sequence of presses, plus — for the two that are not a round trip — the `cols`/`rows` the
     * round trip really lands on. Written down in the table, never inferred afterwards.
     */
    private data class Steps(val presses: List<Step>, val landsOn: Pair<Int, Int>? = null) {
        /** The same presses in reverse, each one the other way. */
        fun undone(): List<Step> = presses.reversed().map { Step(it.axis, -it.delta) }

        fun describe(): String = presses.joinToString(" ") {
            "${it.axis}${if (it.delta > 0) "+" else ""}${it.delta}"
        }
    }

    private fun presses(vararg s: Step) = Steps(s.toList())

    private fun w(delta: Int) = Step(SpriteBoard.Axis.CELL_W, delta)
    private fun h(delta: Int) = Step(SpriteBoard.Axis.CELL_H, delta)
    private fun c(delta: Int) = Step(SpriteBoard.Axis.COLS, delta)
    private fun r(delta: Int) = Step(SpriteBoard.Axis.ROWS, delta)

    /** Folds the presses, re-reading the board's grid each time, because that is what a view does. */
    private fun SpriteBoard.run(presses: List<Step>): SpriteGrid {
        var sprite = this
        for (step in presses) {
            sprite = sprite.holding(sprite.stepped(step.axis, step.delta))
        }
        return sprite.board.grid!!
    }

    private fun SpriteBoard.run(sequence: Steps): SpriteGrid = run(sequence.presses)

    /**
     * 20 sequences over `bySize(64, 64)` on 500 x 300, i.e. 7 x 4 of 64 x 64, and every one of them
     * comes back. The cell steps are held inside 63..71 wide and 61..75 tall on purpose, and that is
     * a fact about `bySize` and not about the stepper: 500 / 63 is 7.93 and 500 / 62 is 8.06, so a
     * step down to 62 re-flows the COLUMN COUNT to 8, and undoing the step lands on 7 — a different
     * board. The count table's two clamp rows are the same fact for `byCount`.
     */
    private val SIZE_STEPS = listOf(
        presses(w(1)),
        presses(w(-1)),
        presses(h(1)),
        presses(h(-1)),
        presses(w(1), w(1)),
        presses(w(2)),
        presses(w(3)),
        presses(w(7)),
        presses(h(2)),
        presses(h(3)),
        presses(h(10)),
        presses(h(11)),
        presses(w(1), h(1)),
        presses(w(1), w(-1), w(1)),
        presses(h(-1), h(1), h(-1)),
        presses(w(-1), w(2), w(-1)),
        presses(h(-3), h(5), h(-2)),
        presses(w(2), h(2), w(-1), h(-1)),
        presses(w(1), h(-1), h(1), w(-1), h(-1)),
        presses(w(-1), w(3), w(3)),
    )

    /**
     * 20 sequences over `byCount(3, 2)`, i.e. 3 x 2 of 166 x 150. Eighteen come back; the two that
     * step a count down off 1 do not, because 1 is the floor and undoing a step that did nothing is
     * not a step back. Both of those are listed with the grid the round trip really lands on.
     */
    private val COUNT_STEPS = listOf(
        presses(c(1)),
        presses(c(-1)),
        presses(r(1)),
        presses(r(-1)),
        presses(c(1), c(1)),
        presses(c(-1), c(-1)),
        presses(r(1), r(1)),
        presses(c(1), r(1)),
        presses(r(1), c(-1)),
        presses(c(3)),
        presses(c(2), r(-1), c(-1)),
        presses(c(-1), r(2), c(1)),
        presses(r(-1), c(1), r(1), c(-1)),
        presses(c(1), c(-1), c(2), c(-2)),
        presses(r(1), r(-1), r(2), r(-2)),
        presses(c(5), c(-5)),
        presses(r(1), c(-1), c(1), r(-1), c(1), c(-1)),
        presses(c(1), r(4), r(-4), c(-1)),
        // Clamp 1: 3 - 2 = 1, then 1 - 1 is a no-op at the floor. Undoing gives 1 + 1 = 2, then + 2 = 4.
        Steps(listOf(c(-2), c(-1)), landsOn = 4 to 2),
        // Clamp 2: 2 - 1 = 1, then 1 - 1 is a no-op. Undoing gives 1 + 1 = 2, then + 1 = 3.
        Steps(listOf(r(-1), r(-1)), landsOn = 3 to 3),
    )
}

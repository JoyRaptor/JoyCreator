# JB-4.01 — Sprite board: the grid by px or by cell count, the spare strip, the edge grab, sub-grids

| | |
|---|---|
| **Tier** | **T2** (the maths is already Built and reviewed; this row is the *board* that asks it questions, and every question is testable on a JVM) |
| **Status** | 🟦 **Ready** — core half only. R36's two rulings are inlined as Decisions 8 and 10. No Lead ruling is outstanding for the build. |
| **xr** | xr: openrouter/stealth/space-bunny-alpha 2026-09-29 — cut to the **core half** per R30; **the review's test-5 correction applied and the underlying contradiction resolved** (after `fitted(...)` there is no spare strip, and on the 2 px strip the old test could not have had *both* answers — see Decision 9 and tests 9/10); R36 Q1 ("used" is derived) made a real function in `:core` instead of a view field; R36 Q2 (a `−`/`+` stepper pair) moved from "the pill is D.02's" to a stepper whose arithmetic is here; `subGridDiv` **deleted** because keeping it would have meant restating `SpriteGridMath`'s private 2..8 clamp (old Decision 1 vs old Decision 8, in one file); `edgeAt` now takes **document** px (Decision 5 rewritten), which is what makes the spare-strip case expressible at all. |
| **xr (cross-review)** | xr: openrouter/stealth/space-bunny-alpha 2026-09-29 — **Ready.** Every `SpriteGridMath` / `DocModel` signature and both private `MIN/MAX_SUB_GRID_DIV` line numbers re-read and correct; the test-5 derivation (499 is 1 px from a 22 dp grab) is arithmetically sound and the two fixtures do separate the two answers. **Two tests contradicted each other and are fixed: test 11 asked for `RIGHT` at a point that is inside BOTH the right and the corner grab, which test 13 requires to be `CORNER`.** Test 12's two zone labels were swapped, and test 14's probe points are now pinned. Q1 left open for the Lead with the value named; Q2 confirmed. |
| **Needs** | JB-4.01a (`SpriteGridMath` — 🟩 Reviewed), JB-0.02 (`Board`, `SpriteGrid`, `RectPx`). **JB-2.01 is NOT needed by this row** — it is needed by the *view* half, and that half is not here. |
| **Owner area** | (1) NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/sprite/SpriteBoard.kt` · (2) NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/sprite/SpriteBoardTest.kt` · (3) NEW `joybrush/core/src/jvmTest/kotlin/cc/joycreator/joybrush/core/sprite/SpriteBoardHoldsNoGridArithmeticTest.kt` · **NOTHING ELSE.** In particular **NOT** `SpriteGridMath.kt` (Reviewed — a re-review follows any edit), **NOT** `SpriteGrid` / `Board` / `DocModel.kt`, **NOT** `JoyBrushActivity.kt` (R30 lock order), **NOT** anything in `joybrush-android/` or `app/`, no Gradle file. |
| **Estimated size** | ~170 lines of Kotlin, ~300 lines of tests |
| **Command** | `./gradlew -p joybrush :core:jvmTest` — `BUILD SUCCESSFUL`, 0 failures in `joybrush/core/build/test-results/jvmTest/` |

## What was CUT, and where it now lives

The Lead's review gave this row `⛔` because the **view** half needs JB-2.01, and split it: `⛔` for the
view, `🟦` for the core. The old spec promised a view, a grid pill in `JoyBrushActivity`, and a hit-test
that took **screen** coordinates. None of those can go to a free model — `JoyBrushActivity.kt` is R30's
lock order and every chrome row wants it. So the core half below is a NEW file that calls
`SpriteGridMath` and adds no arithmetic to it, and everything visual is gone. The table says where each
piece went, so the later row inherits the rulings instead of re-arguing them.

| Cut | What it was | Where it now lives |
|---|---|---|
| `SpriteGridView.kt` | the whole view: drawing the grid, the handles, the cell states, the sub-grid, the badges | the **view half of JB-4.01**, the Lead's, and it is `⛔` until JB-2.01 lands. **No spec for it exists yet.** |
| `EDIT JoyBrushActivity.kt` | the grid pill, the board's activation | same row. R30: it waits for JB-2.01's cluster, and no other row may bolt a control onto that file first. |
| Cell states — used = 1.5 dp `jb_state_selected` outline, playing = 2.5 dp `jb_state_live` ring, badge = cyan disc top-centre, pink when now (old D10) | drawing | the view half. Recorded as **Cut Decision C1** so it travels. *Why:* `JOYBRUSH_VISUAL_LANGUAGE.md` §4.3 records these as SpriteLab's exact states, and a person moving between the two apps should not learn a new colour language. |
| Grid drawn UNDER the art, at `jb_line`, sub-grid at 40% (old D16) | drawing | the view half, **Cut Decision C2**. |
| The grid pill (`NumPill`, a scrubbable number) | chrome | **R36 Q2 changes this**: the answer is a `−`/`+` **stepper pair** now, not the pill. The pill remains D.02's (`JOYBRUSH_VISUAL_LANGUAGE.md` §2 lists `NumPill` as TRAPPED, and D.02c's row already claims extracting a scrubbable number into `:studiokit`). The *arithmetic* of the stepper is in this row (Decision 12); the two buttons are the view half's. |
| The `OnGridGesture` / `OnEdgeDrag` callback types | the seam between the view and the board | **kept in core, renamed**: `GridGesture` and `SpriteGridMath.Edge` are the intent vocabulary, and a view that cannot invent a fourth gesture is the point of the sealed type. The `fun interface` adapters are the view half's. |
| "A long-press is `CellLongPressed`, a tap is `CellTapped`, and a long-press never also fires a tap" (old D12), "a second finger cancels" (old D13) | gesture wiring | the view half. The two *rules* are house rules already written down twice (JB-3.03 Decision 9, JB-2.02) and are **Cut Decisions C3/C4** below. There is no gesture state machine in this core half and the spec does not pretend otherwise: deciding "is this finger on a handle or on a cell" needs a finger, and the one thing this row does own — which handle, and how far — is `edgeAt`. |

**This row builds the grid's two setters, its fit rule, its handle hit test, its sub-grid and the
definition of a used cell. It does not draw a grid, and there is no way to reach the phone from it.**

## Goal

Blueprint §1 idea 9: *"Sprite grid overlay (drag onto art, sizes in px, cell count, tap cells in
order, play preview, export sheet or sequence)"*, with its change: *"make it a **Board** anchored in
the document, not a floating overlay, so it stays put, can sit beside other boards, and remembers its
settings."*

That change is already in the model — `Board.grid: SpriteGrid?` and `DocOps.validate` rule 5 ("a sprite
board with no grid is a plain rectangle by accident") — and **all of the arithmetic is Built and reviewed**
in JB-4.01a: `bySize`, `byCount`, `fitRect`, `cellRect`, `cellAt`, `dragEdge`, `subGridLines`.

**This spec does not re-derive a single number from JB-4.01a. It calls it.** Where 4.01a made a ruling —
which count gives way over the 4 096 cap, ties away from zero in the drag — that ruling stands and is
*invoked* here, not re-argued. Test J1 is the mechanical form of that sentence.

## Contract

### What this file calls — pasted verbatim from the landed source

`joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/sprite/SpriteGridMath.kt`:

```kotlin
object SpriteGridMath {

    const val MAX_CELLS = 4096

    /** The largest cell edge, in pixels, for the same reason [MAX_CELLS] is the largest cell count. */
    const val MAX_CELL_PX = 4096

    fun bySize(rect: RectPx, cellW: Int, cellH: Int): SpriteGrid

    fun byCount(rect: RectPx, cols: Int, rows: Int): SpriteGrid

    fun fitRect(rect: RectPx, grid: SpriteGrid): RectPx

    fun cellRect(board: Board, index: Int): RectPx

    fun cellAt(board: Board, x: Float, y: Float): Int

    enum class Edge { RIGHT, BOTTOM, CORNER }

    fun dragEdge(grid: SpriteGrid, edge: Edge, dx: Float, dy: Float): SpriteGrid

    fun subGridLines(board: Board, div: Int): List<FloatArray>
```

And from `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/DocModel.kt`:

```kotlin
@Serializable data class RectPx(val x: Int, val y: Int, val w: Int, val h: Int)

@Serializable enum class BoardKind { CANVAS, ANIMATION, SPRITE, PUPPET, CHARACTER }

@Serializable data class SpriteGrid(val cols: Int, val rows: Int, val cellW: Int, val cellH: Int)

@Serializable data class Board(
    val id: String,
    val name: String,
    val kind: BoardKind,
    val rect: RectPx,
    val clipToBoard: Boolean = false,
    val fps: Float = 12f,                   // ANIMATION only
    val frames: List<Frame> = emptyList(),  // ANIMATION only, in play order
    val grid: SpriteGrid? = null,           // SPRITE only
)
```

**`SpriteGrid` is four `Int`s and that is the whole of it** — there is no per-cell field of any kind on
it, which is what makes R36's "used is derived" a fact about the format rather than a preference. J2
pins it by reflection.

**`MIN_SUB_GRID_DIV = 2` and `MAX_SUB_GRID_DIV = 8` are `private` in `SpriteGridMath`
(`SpriteGridMath.kt:54,57`).** The old spec's `subGridDiv(div: Int): Int` existed to "clamp into 2..8",
which means restating two private constants — a second copy of a rule the maths already owns, and exactly
the copy Decision 1 forbids. **It is deleted.** `subGridLines(div)` already answers an empty list for a
`div` outside 2..8, so the "off" state is `subGridLines(div).isEmpty()` and there is nothing to clamp.

### The new file

`joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/sprite/SpriteBoard.kt`

```kotlin
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
 *     describe (Decision 15).
 *
 * The hit test takes DOCUMENT px, and the one screen number it needs — the zoom — is named
 * `screenPerDoc` and used for exactly one thing: sizing a touch tolerance. See Decision 5.
 */
class SpriteBoard(val board: Board, val density: Float = 1f) {

    /** The board with [grid] written into it. Refuses a board that is not a SPRITE board, in words. */
    fun withGrid(grid: SpriteGrid): Board

    /** The board resized to hold [grid] EXACTLY — [SpriteGridMath.fitRect], applied. */
    fun fitted(grid: SpriteGrid): Board

    // ---- the two ways a grid is set ----

    /**
     * "Cells of N px": [SpriteGridMath.bySize] over [board]'s rect, as many WHOLE cells as fit. A
     * partial cell at the right or bottom edge is not a cell anybody can draw a sprite in, so it is
     * not a cell, and what is left over stays outside the grid until [fitted] removes it.
     */
    fun bySize(cellW: Int, cellH: Int): SpriteGrid

    /**
     * "N columns × M rows": [SpriteGridMath.byCount] over [board]'s rect. The leftover (2 px of board
     * for 3 columns across 500) stays OUTSIDE the grid until [fitted] removes it.
     */
    fun byCount(cols: Int, rows: Int): SpriteGrid

    /**
     * The `−`/`+` stepper's arithmetic (R36 Q2), for the stepper the view half will draw: one DOCUMENT
     * px on a cell edge, or one whole column / row on a count. Both go straight back through [bySize]
     * / [byCount], so a step of `+1` on a count is `byCount(cols + 1, rows)` and nothing else.
     */
    fun stepped(axis: Axis, delta: Int): SpriteGrid

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
     * screen number ([Decision 5]).
     *
     * [screenPerDoc] is `ViewTransform.zoom`, screen px per doc px. It is used for ONE thing and one
     * thing only: the grab depth is a TOUCH distance, so it is compared in screen px —
     * `|xDoc - gridRight| * screenPerDoc <= edgeGrabPx`. **Pass the zoom, never its inverse.**
     *
     * Non-finite input, a non-positive or non-finite [screenPerDoc], or a board with no grid → null.
     */
    fun edgeAt(xDoc: Float, yDoc: Float, screenPerDoc: Float): SpriteGridMath.Edge?

    /** Which cell a finger-down is on, in document px — [SpriteGridMath.cellAt] verbatim. */
    fun cellAt(xDoc: Float, yDoc: Float): Int

    // ---- resizing by dragging an edge ----

    /**
     * The board and grid after a drag by ([dxDoc], [dyDoc]) DOCUMENT px on [edge], then `fitRect`'d.
     * [SpriteGridMath.dragEdge] splits the delta over the cells and rounds ties away from zero; this
     * class adds nothing.
     */
    fun dragged(edge: SpriteGridMath.Edge, dxDoc: Float, dyDoc: Float): Pair<Board, SpriteGrid>

    // ---- sub-grid ----

    /** The sub-grid lines in document px — [SpriteGridMath.subGridLines] verbatim. Empty for `div` < 2
     *  or `div` > 8, and empty for a board with no grid. "Off" IS an empty list; nothing is clamped. */
    fun subGridLines(div: Int): List<FloatArray>

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
     */
    fun usedCellsOf(cells: List<ByteArray>): Set<Int>

    companion object {
        /** Grab depth in DP, multiplied by density at the use site (R32). See Decision 5 and Q1. */
        const val EDGE_GRAB_PX_DP: Float = 22f
    }
}
```

```kotlin
// same file, after the class: the intent vocabulary. It is here so a view cannot invent a fourth
// gesture, and so the *names* of the two things this row owns are settled in one place.

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
```

## Decisions

1. **This spec calls `SpriteGridMath` and adds no arithmetic.** Every grid number is its answer; this
   file publishes exactly one number of its own, `EDGE_GRAB_PX_DP`, which is a touch distance and not a
   grid rule. *Why:* R23, and the `MAX_SIZE_PX` lesson — R19 made `SpriteGridMath.MAX_CELLS` and
   `MAX_CELL_PX` **public** precisely so a second copy could not appear, and a second copy that can
   drift is one nothing can catch. J1 is the mechanical form of this sentence. (It is also why
   `subGridDiv` is gone: clamping 2..8 here would have restated two `private` constants.)
2. **A board resized by any grid change is `fitRect`'d, always.** `bySize`, `byCount` and `dragged` all
   end in `fitRect`, so the board's rect is exactly the grid and an export of "this board" is the grid
   and not the old rect with a sliver hanging off it. *Why:* `fitRect`'s own KDoc says that is what it is
    for, and a sprite board whose rect is bigger than its grid exports a margin of empty cells. Tests 2,
    3, 4 and 16.
3. **The grid's right and bottom edges are the GRID's, not the board's.** On a board that has not been
   fitted the two differ, and it is the grid's edge a person means when they grab a handle.
   *Why:* `SpriteGridMath` KDoc fact 2 — "the board may be BIGGER than the grid: the spare strip on the
   right and bottom is simply outside the grid" — and a handle drawn on the board's edge would resize
   from the wrong line. Tests 9, 10.
4. **The handle is decided ONCE, at finger-down, and it beats the cell tap.** The grab zone is
   **two-sided on the edge line** (`|Δ| <= edgeGrabPx`), because a finger is wider than a 1 px line and
   a one-sided zone would be unreachable with a fingertip. The **corner is checked first**, so a finger
   at the corner is unambiguous by construction rather than by a tie-break. *Why:* JB-3.03 Decision 8 is
   the same rule for the same reason — a gesture that changes what it means halfway is how a tap becomes
   an accidental resize. The overlap the two-sided zone creates with the cell underneath is harmless
   *because the handle is decided once, at down, and wins; say so in the test. Tests 11, 12 and 13.
5. **The hit test takes DOCUMENT px, and the zoom's only job is to size the touch tolerance.** The old
   spec took screen px and expected the view to convert, which put a `× 1/zoom` (or `÷ zoom`) in a place
   with no tests. Now: the view divides once, at its own edge, and hands over document px;
   `screenPerDoc` is used for `|Δ| * screenPerDoc <= edgeGrabPx` and nothing else. *Why:* the same seam
   JB-2.16a and JB-3.03 draw, and R19's direction warning is one sentence of terror: **pass
   `view.zoom`, not `1f / view.zoom`.** Test 14 pins the direction with numbers that only come out one
   way round.
6. **`dragEdge`'s axis isolation is preserved and the view must not "help" it.** `Edge.RIGHT` uses `dx`
   only, `BOTTOM` uses `dy` only, `CORNER` both. The 4.01a suite already pins this with `∓9999` on the
   ignored axis, and this row re-pins it through `dragged` rather than trusting that. *Why:*
   pre-zeroing in the view is a second implementation of the rule, in a place with no tests. Test 15.
7. **A drag that would take a cell below 1 px is still allowed, and `dragEdge` clamps it to 1.** There is
   no "too small, ignoring that" in this spec. *Why:* the clamp is in the maths, it is tested there, and
   duplicating the refusal here would be a second answer to the same question. Test 16.
8. **"Used" is DERIVED from pixels and never stored (R36 Q1).** A cell is used when one of its pixels
   has alpha above 0. The document gains no field, `SpriteGrid` gains no field, and there is no
   parallel list that can disagree with the picture. *Why:* the model's whole philosophy is "pixels are
   the truth" for paint layers, and a parallel truth that can disagree is what `DocOps.validate` exists
   to prevent. The cost is honest: a caller must have the cells (rendered or packed) before it can
   answer, which is the view half's problem and this row's `usedCellsOf` is the answer to it. Tests 17,
   J2.
9. **The spare strip is REAL, it is outside the grid, and whether it is grabbable is a derived
   question, not a rule.** `SpriteGridMath.cellAt` answers −1 there; whether `edgeAt` answers a handle
   depends on how wide the strip is compared to the grab depth, and that is arithmetic a test derives
   rather than a decision this spec gets to make. See tests 9 and 10 — this is where the review's
   correction lives, and the two halves of the old test 5 were asking for mutually exclusive answers at
   the same point. *Why:* a board that is not `fitRect`'d is a stale board (Decision 2 says it should not
   survive a grid change), so the spare strip is a *stale* case — but a stale board is still a board a
   person can be looking at, and the strip must not silently swallow a tap meant for a cell.
10. **The `−`/`+` stepper changes a DOCUMENT px or a whole column/row, and nothing else (R36 Q2).** One
    px on `bySize`, one on `byCount`'s count. The numbers are document px, so there is no density in
    them — density belongs to touch targets, and this is a value, not a target. *Why:* a scrubbable
    number pill is D.02's and is not built (`JOYBRUSH_VISUAL_LANGUAGE.md` §2 lists `SpriteSheetEditor
    Activity.NumPill` as TRAPPED, and D.02c's row already claims the extraction), and the answer the
    Lead gave is a stepper **now**, because a board with no way to set its grid size is a board that does
    not work. **PROVISIONAL — Claude to confirm:** the step of 1 is the smallest step that is still a
    whole number of pixels, and the host may hold a button down to repeat. Tests 6, 7, 18 and 19.
11. **The sub-grid is 2..8 and "off" is an EMPTY list — never `div = 1` drawn as a line down the middle
    of every cell.** *Why:* `subGridLines` already returns an empty list for a `div` outside 2..8; the
    board must not invent a `1` to mean "on" and then have to special-case it. Test 20.
12. **The sub-grid is a HELPER and is never in an export** (blueprint §3). It is a `View` and never a
    layer, so this is true by construction — and test 21 pins it anyway, the same way JB-3.02 pins its
    rulers. *Why:* a line somebody used to aim with is a line in someone else's sprite.
13. **Nothing in this spec mutates the document.** The view emits intentions; the host applies them
    through `withGrid` / `fitted` and the document is immutable throughout. *Why:* the undo stack holds
    documents, and a view that mutated one would be a view the undo stack could not describe. Test 22.

### Cut Decisions — these rulings travel to the view half and must not be re-argued

- **C1 (old D10).** Cell states, in the app's own vocabulary, because SpriteLab's are the ones a person
  has seen: used = 1.5 dp `jb_state_selected` (cyan) outline; playing = 2.5 dp `jb_state_live` (pink)
  ring; order badge = a cyan disc top-centre, pink when that cell is the one playing
  (`JOYBRUSH_VISUAL_LANGUAGE.md` §4.3 records these as SpriteLab's exact states). *Why:* the visual
  language's own rule is that inside the board, cell states must match `SpriteGridEditorView` exactly.
  Note the badge is **JB-4.02's** roll, not this row's.
- **C2 (old D16).** The grid is drawn UNDER the art, never over it — `jb_line` for the cell borders and
  `jb_line` at 40% for the sub-grid. *Why:* a grid drawn over a sprite hides the sprite, and the visual
  language's `LINE` is a divider, not an ink.
- **C3 (old D12).** A long-press on a cell is `CellLongPressed` and a long-press does **not** also fire
  a tap. *Why:* the same rule as JB-3.02's pegs (blueprint §1(a)) and a double-fire is how a cell ends
  up in the play order twice by accident.
- **C4 (old D13).** A second finger cancels the grid gesture, exactly as it cancels the film strip's
  (JB-3.03 Decision 9). *Why:* the house rule, already written down twice. A second finger is a palm or
  a pinch; neither means "resize my sheet".
- **C5 (old D14).** The edge handle is drawn only when the grid is on, and a board of any other kind
  draws no grid at all. A board with `grid == null` is refused by `DocOps.validate` rule 5, so the
  view's "no grid" is a broken document, not a state; the view shows nothing and says nothing. *Why:*
  refuse in words, and the words are the validator's, at open time. **The core half's half of this is
  live and tested: `withGrid` refuses a non-SPRITE board (test 11).**

## Decision → Test map (every Decision is checkable)

| Decision | Pinned by |
|---|---|
| 1 (calls, never re-derives) | **J1**, **J2** |
| 2 (`fitRect` always) | 2, 3, 4, 16 |
| 3 (the GRID's edge, not the board's) | 9, 10 |
| 4 (decided at down; corner first) | 11, 12, 13 |
| 5 (document px; zoom sizes the tolerance) | 14 |
| 6 (axis isolation, not pre-zeroed) | 15 |
| 7 (no second refusal) | 7, 16 |
| 8 (used is derived) | 17, **J2** |
| 9 (the spare strip) | 9, 10 |
| 10 (stepper, 1 px / 1 count) | 6, 7, 18, 19 |
| 11 (off is empty, not `div = 1`) | 20 |
| 12 (never exported) | 21 |
| 13 (no mutation) | 22 |
| C5 (SPRITE only) | 5 |

## Tests

### `commonTest` — `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/sprite/SpriteBoardTest.kt`

**Fixture.** A SPRITE board `b` whose rect is **500 × 300 at (0,0)**, built with a `SpriteGrid` the test
itself sets, because the whole row is about the relationship between the two. `SpriteGridTest` in
`core/src/commonTest/…/sprite/SpriteGridMathTest.kt` is the same source set and is the model for how
these are written.

**Two boards, and the difference between them is the whole of the review's correction:**

```kotlin
// A — the Lead's fixture, and it is DELIBERATELY NOT FITTED.
private fun staleByCount(): Board = board(rect = RectPx(0, 0, 500, 300),
    grid = SpriteGridMath.byCount(RectPx(0, 0, 500, 300), 3, 2))   // 3 x 2 of 166 x 150 = 498 x 300

// B — a board with a spare strip WIDER than the 22 dp grab, so the two halves of the old test 5 can
// both be true at the same point. bySize leaves the remainder outside the grid: 7 x 4 of 64 = 448 x 256.
private fun staleBySize(): Board = board(rect = RectPx(0, 0, 500, 300),
    grid = SpriteGridMath.bySize(RectPx(0, 0, 500, 300), 64, 64))
```

1. `bySizeMatchesTheMathsExactly` — `bySize(64, 64)` on 500 × 300 → **7 × 4 of 64 × 64**, and
   `fitted(grid).rect` is **448 × 256**. Asserted **against `SpriteGridMath.bySize`** first and then
   against the literal, in that order, so the test fails if either side is wrong.
2. `bySizeLeavesTheRemainderOutsideTheGrid` — `bySize(100, 100)` on 512 × 256 → **5 × 2 of 100 × 100**,
   and `fitted` is **500 × 200**. The leftover **12 × 56** is gone from the board, because `fitRect`
   (Decision 2). A board that kept 512 × 256 here would be exporting 12 px of nothing. Two rects,
   because "the leftover" is only visible when the leftover is bigger than 0.
3. `byCountMatchesTheMathsExactly` — `byCount(3, 2)` on 500 × 300 → cells of **166 × 150**, and
   `fitted` is **498 × 300**. The 2 px remainder is real until `fitted` is applied.
4. `everyGridChangeLeavesTheBoardExactlyTheSizeOfItsGrid` — for 20 combinations of `bySize`,
   `byCount` and `dragged`, `board.rect.w == grid.cols * grid.cellW` and
   `board.rect.h == grid.rows * grid.cellH`, exactly, with `==` on `Int`s and no tolerance.
5. `withGridRefusesABoardThatIsNotASpriteBoard` — a CANVAS board `withGrid(grid)` throws with a message
   naming the kind. *Why:* a grid on a CANVAS board is a document the validator will complain about
   (`DocOps.validate` rule 5), and the refusal is the validator's rule said early, in words. The view
   half's "no grid on another kind of board" is C5; this is the half that can be pinned.
6. `aStepUpAndAStepDownAreTheSameArithmeticAsTheSetters` — `bySize(64, 64)` then `stepped(CELL_W, +1)`
   is `==` to `bySize(65, 64)`; `byCount(3, 2)` then `stepped(COLS, −1)` is `==` to `byCount(2, 2)`. Two
   axes each way, and `stepped(ROW, +1)` leaves `cols` bit-identical.
7. `aStepIsNeverZeroAndNeverANoOpOnAOneCountGrid` — `byCount(1, 1)` then `stepped(COLS, −1)` is
   **1**, not 0 or a throw: `SpriteGridMath.colsOf` clamps to 1 and the board must not invent a second
   refusal (Decision 7's reasoning, applied to the count).
8. `theStepperDoesNotTouchDensity` — the same board at density 1, 2 and 3 gives the same grids, because
   a cell edge is a document value and density is a touch thing. *Why:* the old spec's `edgeGrabPx` was
   the only density-aware number on this class and it is easy to let the creep.
9. **`aTouchOnTheSpareStripIsNotACell`** *(the review's test 5, rebuilt)* — **fixture A**, the unfitted
   500 × 300 board with `byCount(3, 2)`, a grid **498 wide**. Assert, in this order:
   - `SpriteGridMath.fitRect(rect, grid) == RectPx(0, 0, 498, 300)` **≠** `rect` — this is the
     "stale" claim, and it is asserted rather than assumed;
   - `cellAt(board, 499f, 10f) == −1` — 499 is inside the board and outside the grid;
   - `cellAt(board, 497.999f, 10f) == 2` — the last column's last pixel is still a cell;
   - and on the **fitted** board (rect 498 × 300, same grid) `cellAt(497.999f, 10f) == 2` and
     `cellAt(498f, 10f) == −1` — **there is no spare strip at all**, which is why the old test could not
     be built on a fitted board.
   **This test does NOT assert that `edgeAt` is null at 499, and here is the derivation of why it must
   not.** 499 is **1 px** from the grid's right edge, and the grab is **22 dp**. Whatever the zone's
   shape, a finger 1 px from the edge is inside it. So on fixture A `edgeAt(499f, 10f, 1f) == RIGHT`, and
   that is **correct**: the drag resizes the board down onto the grid, which is Decision 2. **The old
   test 5 asked for `edgeAt` → null and `cellAt` → −1 at the same point, and no implementation can
   satisfy both.**
10. **`aTouchOutsideTheGrabIsNeitherAHandleNorACell`** *(the other half, and it needs a wider strip)* —
    **fixture B**, the unfitted 500 × 300 board with `bySize(64, 64)`, a grid **448 × 256**, so the spare
    strip is **52 px** on the right and **44 px** below — both wider than the 22 dp grab. At density 1
    and `screenPerDoc = 1f`:
    - `(480f, 20f)` is 32 px right of the grid's right edge → `edgeAt == null` **and** `cellAt == −1`.
      Both halves, at a point where both can be true.
    - `(460f, 20f)` is 12 px from the right edge → `RIGHT` (inside the grab) even though
      `cellAt(460f, 20f) == −1` (7 columns of 64 is 448, so 460 is in the spare strip). **The handle
      beats the cell and the strip is grabbable** — Decision 4, and the named consequence of the
      two-sided zone.
    - `(20f, 290f)` is 34 px below the grid's bottom edge → `null`; `(20f, 270f)` is 14 px below →
      `BOTTOM`.
    - and the fitted board (448 × 256) has no spare at all: `cellAt(448f, 20f) == −1` while
      `edgeAt(448f, 20f, 1f) == RIGHT` — the edge itself is inside its own zone.
11. `theCornerWinsOverBothEdgesAndTheEdgeItselfIsInsideItsOwnZone` — **fixture B** (grid 448 × 256,
    grab 22): at the exact grid corner `(448f, 256f)` → `CORNER`; and then the three points that
    separate the zones, which **must not** be near the corner, because a 22 dp two-sided zone on each
    line makes the corner a 44 × 44 square and anything inside it is `CORNER`:
    - `(448f, 230f)` — on the right edge, **26 px above** the bottom edge, so outside the corner square
      → `RIGHT`;
    - `(420f, 256f)` — on the bottom edge, **28 px left of** the right edge → `BOTTOM`;
    - `(448f, 250f)` — 6 px above the corner, inside **both** grabs → **`CORNER`**, and this is the case
      the draft got wrong: it asked for `RIGHT` there, which test 13 forbids and which "the corner is
      checked first" cannot produce. A corner that resolves to an edge resizes only one axis, so the
      answer at the corner matters more than the answer near it.
    Plus test 5's refusal.
12. `theGrabIsTwoSidedOnTheEdgeLine` — **fixture B**, `screenPerDoc = 1f`: `edgeAt(426f, 20f, 1f) ==
    RIGHT` (**22 px INSIDE** the grid, on the last cell) as well as `edgeAt(470f, 20f, 1f) == RIGHT`
    (**22 px OUTSIDE** it, in the spare strip). The test's comment says why: a finger is wider than a
    1 px line, and a zone that exists only outside the grid could not be hit at all. (The draft had
    these two labels the wrong way round — 470 is outside a grid that ends at 448, not inside. The two
    expected values were right; only the words were not, and the words are what a reader copies.)
13. `aHandleIsDecidedOnceAndTheGridCannotBeGrabbedByBothAtOnce` — probe the corner region at 1 px
    steps: for every probe in a 30 × 30 box around (448, 256), `edgeAt` returns exactly one of
    `CORNER` / `RIGHT` / `BOTTOM` / `null` and never two, and `CORNER` is returned for every point that
    is within the grab of **both** lines. *Why:* that is Decision 4's "the corner is checked first
    because it is the intersection; a finger at the corner is unambiguous by construction rather than by
    a tie-break", as a test rather than a promise. **This is the test that made test 11 wrong**, and
    the two must be read together: the 44 × 44 corner square belongs to `CORNER` in its entirety, and
    the only way to reach `RIGHT` is to be outside the bottom grab or `BOTTOM` outside the right grab.
14. `theGrabIsSizedInScreenPixelsAndTheZoomIsNotInverted` — the direction of the conversion, with
    numbers that only come out one way round. **Every probe is pinned to `(x, 20f)`: 20 is 236 px above
    the grid's bottom edge, so no probe can accidentally land in the corner square and turn this into
    a test of `edgeAt`'s corner rule.** At density 1, `screenPerDoc = 4f` (zoomed IN, so a doc pixel is
    4 screen px and the 22 screen px grab is **5.5 doc px**): a finger **6 doc px** from the right edge
    (`x = 454f`) is 24 screen px away → `null`; **5 doc px** away (`x = 453f`) is 20 screen px → `RIGHT`.
    At `screenPerDoc = 0.25f` (zoomed OUT) the same 22 screen px is **88 doc px**: **80 doc px** away
    (`x = 528f`) → `RIGHT`, **100 doc px** away (`x = 548f`) → `null`. Both directions, and the comment
    says that dividing instead of multiplying inverts both.
15. `theIgnoredAxisIsStillPassedAndStillIgnored` — through `dragged`: `dragged(CORNER, 80f, −50f)` on an
    8 × 4 grid changes both; `dragged(RIGHT, 80f, −50f)` changes `cellW` and leaves `cellH`
    **bit-identical**, and the same for `BOTTOM`. Pinned with `∓9999` on the ignored axis, as 4.01a
    does. The returned board is `fitRect`'d, so the assertion is on the *pair*.
16. `aCellIsNeverRefusedHereItIsClampedOnceInTheMaths` — from an 8 × 4 grid of 64 × 64,
    `dragged(BOTTOM, 0f, −1_000_000f)` yields `cellH` of exactly **1** and a board of exactly
    **512 × 4** (8 columns × 64 wide, 4 rows × 1 tall); `dragged(RIGHT, −1_000_000f, 0f)` yields
    `cellW` of exactly **1** and a board of **8 × 256**. Neither throws, and the clamped board is
    still `fitRect`'d — which is why both numbers are given and not just "it did not crash".
17. `usedCellsAreComputedAndACellWithNothingInItIsNotUsed` — a list of **40 cells of `64 × 64` RGBA**
    (a list of its own — `usedCellsOf` is handed cells and told nothing about the fixture's 28, and the
    index it returns is the index in the list, which is reading order), all zeros but
    three, and `usedCellsOf` is exactly those three indices. Then two more cases that are the point of
    deriving it: erasing a cell (all zero again) makes it **not** used, and a cell holding a single
    pixel of alpha 1 **is** used. *Why:* the second is what a stored flag could not do without a
    deliberate-emptied state, and R36 ruled the derived answer.
18. `theFourSteppersAreIndependent` — from one board, `stepped(CELL_W, +1)`, `CELL_H`, `COLS` and `ROWS`
    each change exactly one of `grid.cellW`, `grid.cellH`, `grid.cols`, `grid.rows` and leave the other
    three **bit-identical**. Four assertions per axis, because "independent" is four claims.
19. `aSteppersBothWaysRoundAreTheSameBoard` — 20 random-ish step sequences (fixed seed, no randomness —
    a fixed list of deltas) applied to `bySize(64, 64)` and then undone in reverse return the **same**
    grid, and 20 sequences against `byCount(3, 2)` likewise, except where a clamp was hit (a count
    already at 1 stepping down) — those are listed in the test's own table so the clamp is visible rather
    than a surprise.
20. `aSubGridOfOneIsOffAndNotALineDownEveryCell` — `subGridLines(1)`, `subGridLines(0)` and
    `subGridLines(-3)` all return **empty**; `subGridLines(9)` empty; `subGridLines(2)` on a 2 × 1 grid
    of 64 gives **4** segments (2 cells × 1 vertical + 1 horizontal, interiors only) and
    `subGridLines(8)` gives **28** (2 × 7 × 2). Counted, not described.
21. `theSubGridIsNotInTheDocument` — `DocJson.encode(doc)` is **byte-identical** before and after asking
    for lines at `div = 1` and `div = 3`, and the encoded text does not contain the word "subGrid" or
    "div". *Why:* the old spec's version of this test rendered the document twice through
    `RegionRenderer` and compared the pixels — which are identical **by construction**, because the
    sub-grid is never in the document to be rendered. The render would have proved nothing; the
    document's own bytes are the thing the claim is about.
22. `noFunctionInThisFileReturnsAMutatedDocument` — the board passed to every `Board`-returning
    function is compared field for field with a copy taken before the call, and is `===` to it. This is
    the immutability claim as a test, and it is cheap.

### `jvmTest` — `joybrush/core/src/jvmTest/kotlin/cc/joycreator/joybrush/core/sprite/SpriteBoardHoldsNoGridArithmeticTest.kt`

**These two are here because they use Java reflection** (`::class.java`), which is not available in a
multiplatform `commonTest` source set. This is the Lead's slip 3, and it bit JB-1.07, JB-2.04, JB-2.12,
JB-2.16, JB-2.17, JB-2.23 and JB-3.04's specs: a test in `commonTest` that opens a file, or calls
`::class.java`, does not build.

**J1.** `thisClassHoldsNoGridArithmeticOfItsOwn` — `SpriteBoard::class` and its companion declare
exactly **one** constant, `EDGE_GRAB_PX_DP`, a `Float` of **22f**. No `Int` constant at all, so
`MAX_CELLS`, `MAX_CELL_PX` and the 2..8 sub-grid clamp cannot be restated here without this going red
— and `SpriteGridMath.MIN_SUB_GRID_DIV` / `MAX_SUB_GRID_DIV` being `private` is exactly why a
restatement would be a copy.

**J2.** `usedIsComputedAndNotStored` — `SpriteGrid::class.java.declaredFields` is exactly
`[cols, rows, cellW, cellH]`, all `int`, and no field of any `DocModel` type is a `Set` or a `List`.
That is R36's "used is derived" as a property of the **format**, not a preference of this row.

**Non-vacuity the builder must run and paste:** (1) drop the `fitted(...)` call from `bySize` / `byCount`
/ `dragged` and watch **test 4** fail; (2) make `subGridLines(1)` return `subGridLines(2)` and watch
**test 20** fail; (3) divide by `screenPerDoc` instead of multiplying in `edgeAt` and watch **test 14**
fail on both of its directions; (4) clamp `subGridDiv`'s 2..8 by hand — i.e. add a `subGridDiv(div)`
that returns `div.coerceIn(2, 8)` and have `subGridLines` use it — and watch **J1** fail. A helper that
has never been seen wrong is a helper nobody can rely on.

**Command:** `./gradlew -p joybrush :core:jvmTest` — `BUILD SUCCESSFUL`, 0 failures.

## Do not

- **Do not reimplement `SpriteGridMath`.** Not a clamp, not a round, not a cap, not the 2..8 sub-grid
  range. Call it. (R19 made `MAX_CELLS` and `MAX_CELL_PX` public for exactly this reason, and a builder
  "fixing" the sub-grid clamp here is the regression J1 exists to catch.)
- Do not change `SpriteGrid`, `Board` or the document format. This spec adds no field anywhere, and in
  particular adds no "used" flag (R36 Q1: derived — Decision 8).
- Do not hit-test in screen px below this layer. Convert once, at the view (Decision 5), and pass the
  zoom, not its inverse.
- Do not pre-zero the ignored axis in an edge-drag callback. Name the edge and pass both numbers; the
  maths decides which one counts (Decision 6).
- Do not reintroduce `subGridDiv`. "Off" is an empty list and there is nothing to clamp.
- Do not draw the grid OVER the art, and do not give a sub-grid a colour of its own (C2, the view half).
- Do not add a "snap to power of two", a centre line, a golden-ratio overlay or an art-nudge overlay.
  Not in the blueprint.
- Do not store a roll, a badge, a selection or a playhead. JB-4.02 owns the roll and it is session state.
- Do not create `SpriteGridView.kt` or touch `JoyBrushActivity.kt`. They are the view half and the Lead's.
- No hex literals anywhere. `JbColors.palette(context)` only — and that is a view-half rule anyway.

## Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest` output pasted, 0 failures
- [ ] the four non-vacuity runs pasted, each with the name of the test that went red
- [ ] `git status --short` shows **only** the three owner-area paths
- [ ] committed `JB-4.01: sprite board core`. **The ROADMAP row is the orchestrator's, not yours — do not
      edit `tasks/joybrush/ROADMAP.md`.** `specs/INDEX.md` was retired ("# Moved"), so there is no
      index line to update; report the new status in the build report and the orchestrator sets it.

## Stop rule

**Stop, set the row `⛔ Blocked`, and write the question in this file's Questions section — do not guess
and do not widen the owner area — if any of these happen:**

1. A signature in the "Contract" section does not match the landed file it is pasted from
   (`SpriteGridMath.kt`, `DocModel.kt`). The contract is authoritative over this spec.
2. Test 9's derivation does not hold on the landed `cellAt` — that is, if `cellAt(499f, 10f)` is not −1
   on an unfitted 500 × 300 board with a 498-wide grid. Then this spec's premise about the spare strip
   is wrong and the review's correction needs re-deriving from the code.
3. A test's expected value cannot be derived from the numbers in this spec. That means a design decision
   is missing, and a builder must not invent one.
4. Making something pass would need an edit to `SpriteGridMath.kt` (Reviewed — a re-review follows any
   edit), `DocModel.kt`, `DocOps.kt`, or anything outside the three owner-area paths — **especially**
   `JoyBrushActivity.kt`, which R30 reserves.
5. `usedCellsOf` turns out to need the renderer, the GL engine or a file to answer. Then "used" is not
   derivable in `:core` at all, which is a finding about R36's ruling and belongs to the Lead, not a
   licence to reach into another module.

## Questions

_(Spec writer: openrouter/stealth/space-bunny-alpha, 2026-09-29. The core half is decided and pinned by
22 `commonTest` cases and 2 `jvmTest` ones, plus four non-vacuity runs. **No question below blocks the
build.**)_

### Q1 — LEFT OPEN for the Lead, deliberately, and here is exactly what the spec currently uses.

**This spec uses `EDGE_GRAB_PX_DP = 22f`, one-sided on each line with the corner square to `CORNER`,
and every derived number in tests 9, 10, 11, 12, 13 and 14 follows from that one constant.** The Lead
should know that list, because a change of 12 dp does not touch a decision — it touches six tests.

The review says nothing about this row's grab depth, so the review's arithmetic does not settle it and
I have not ruled on it: R32's list — "peg pitch 44 dp, peg radius 14 dp, hit radius 22 dp, edge grab
12 dp" — reads as the **animation peg bar's** four numbers, and its "edge grab 12 dp" is very likely the
same 12 dp that JB-3.03's edge zone is half of (see that spec's Q1, where the cross-reviewer DID rule,
because the corrected-numbers section settles it there). So the two rows may or may not be meant to
share one depth. They are different surfaces with different targets, and this spec has kept 22, because
a grid edge is a 1 px line a finger is trying to find and a strip edge sits between two cells.

**If the Lead wants 12 dp instead, this is the whole change:** `EDGE_GRAB_PX_DP` alone, and then
fixture B's spare strips (52 px right, 44 px below) are still wider than the grab, so **tests 9 and 10
still pass unchanged** — which is the useful part, because the review's test-5 correction does not
depend on the depth. Tests 11, 12, 13 and 14 all move: the corner square becomes 24 × 24, test 11's
outside-the-corner probes must be 13 px and 14 px from the other line instead of 26 and 28, and test
12's two probes move from 426/470 to 436/460. Nothing else in the file changes, and no decision does.

### Q2 — PROVISIONAL, confirmed as written by the cross-reviewer (2026-09-29); still the Lead's to move.

Decision 10 takes R36's "a `−`/`+` stepper pair now" and rules the step to be one document px (or one
column / row). It is marked PROVISIONAL because it is a touch-feel question and therefore a phone
question. What a builder needs is only that **the arithmetic is a call to `bySize` / `byCount` with the
argument moved by the step** — so if the Lead wants the step to be 8 px, or a long-press to multiply
it, or the step to follow the zoom, only `Axis` and this Decision change and tests 6, 7, 18 and 19 move
with them. The real scrubbable `NumPill` stays D.02's and replaces this pair later. **Cross-reviewer:
this does not block the build and nothing here was changed** — it is already decided, derived and
reversible in one function, which is the bar a provisional decision has to clear.

### Q3 — for the Lead: the view half has no spec, and its `Needs` (2.01) is the project's bottleneck.

The review's tally says the bottleneck to anything visible on the phone is the chrome chain
(JB-0.09 → D.02a → D.02 → 2.01). This row is the clearest case: **the maths is Built, this core half is
Ready, and nothing can be seen** because the view needs 2.01. Recommend the Lead open the view-half
spec now, with its `Needs` stated honestly, so it is written and queued rather than rediscovered later.

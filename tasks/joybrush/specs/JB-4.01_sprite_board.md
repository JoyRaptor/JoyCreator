# JB-4.01 — Sprite board: the grid by pixel size or by cell count, sub-grids, and dragging an edge

| | |
|---|---|
| **Tier** | T2-V (the maths is already Built and reviewed; this is the board that draws it, hit-tests it and lets it be resized) |
| **Status** | 🟦 **Ready.** The arithmetic is JB-4.01a's and is reviewed and cleared; every decision below is a *use* of it rather than a second opinion of it, and the three things this spec must not get wrong (the edge-drag axis, the whole-cell rule, the sub-grid as a helper that never exports) are all settled and pinned by tests. **No Lead ruling is outstanding** — Q1 and Q2 are recorded, flagged as non-blocking, and neither is needed to build. |
| **Needs** | 2.01, 4.01a (as the ROADMAP row states) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/sprite/SpriteBoard.kt` · NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/sprite/SpriteBoardTest.kt` · NEW `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/sprite/SpriteGridView.kt` · EDIT `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/JoyBrushActivity.kt` (a grid pill and the board's activation — nothing else in that file) |
| **Estimated size** | ~150 lines of Kotlin in `:core` + ~200 lines of tests, ~280 lines of view |
| **Command** | `./gradlew -p joybrush :core:jvmTest` — 0 failures. Then the watcher compiles `:androidkit:compileKotlin` and `:joybrush-android:compileDebugKotlin` green. |

## Goal

Blueprint §1 idea 9: *"Sprite grid overlay (drag onto art, sizes in px, cell count, tap cells in
order, play preview, export sheet or sequence)"*, with its change: *"make it a **Board** anchored in
the document, not a floating overlay, so it stays put, can sit beside other boards, and remembers its
settings."*

That change is already in the model — `Board.grid: SpriteGrid?` and `DocOps.validate` rule 5 ("a
sprite board with no grid is a plain rectangle by accident") — and **all of the arithmetic is Built
and reviewed** in JB-4.01a: `bySize`, `byCount`, `fitRect`, `cellRect`, `cellAt`, `dragEdge`,
`subGridLines`. This spec is the board: the view that draws those lines, the hit-testing that
answers "which cell", the two ways a person sets the grid, and the two handles that resize it.

**It does not re-derive a single number from JB-4.01a.** It calls it. Where JB-4.01a had to make a
ruling — which count gives way over the 4 096 cap, ties away from zero in the drag — that ruling
stands and is *invoked* here, not re-argued.

## Contract

```kotlin
package cc.joycreator.joybrush.core.sprite

import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.SpriteGrid

/**
 * The sprite board's STATE and the questions the UI asks it. Every grid number in this file comes
 * out of `SpriteGridMath`; none of it is computed here, and the test suite checks that.
 */
class SpriteBoard(val board: Board) {

    /** The board with [grid] written into it. Refuses a board that is not a SPRITE board. */
    fun withGrid(grid: SpriteGrid): Board

    /** The board resized to hold [grid] EXACTLY — `SpriteGridMath.fitRect`, applied. */
    fun fitted(grid: SpriteGrid): Board

    // ---- the two ways a grid is set ----

    /**
     * "Cells of N px": `SpriteGridMath.bySize(board.rect, cellW, cellH)`, and then `fitRect` so
     * the board grows or shrinks to the whole cells. A partial cell at the right or bottom edge is
     * not a cell anybody can draw a sprite in, so it is not a cell.
     */
    fun bySize(cellW: Int, cellH: Int): SpriteGrid

    /**
     * "N columns × M rows": `SpriteGridMath.byCount(board.rect, cols, rows)`, and then `fitRect`.
     * The leftover (2 px of board for 3 columns across 500) stays OUTSIDE the grid.
     */
    fun byCount(cols: Int, rows: Int): SpriteGrid

    // ---- resizing by dragging an edge ----

    /**
     * How close a finger-down must be, screen px (already × density), to the grid's right edge,
     * bottom edge or corner to grab it. Outside that, the same finger is a cell tap.
     */
    val edgeGrabPx: Float

    /**
     * Which handle a finger-down at screen (x, y) has hold of, given the view.
     * [screenPerDoc] is `ViewTransform.zoom` — screen px per doc px (R19/2.16a: the zoom, never
     * its inverse). Non-finite input → [SpriteGridMath.Edge] is not guessed; this returns null.
     */
    fun edgeAt(xScreen: Float, yScreen: Float, screenPerDoc: Float): SpriteGridMath.Edge?

    /**
     * The grid after a drag by (dx, dy) DOCUMENT px on [edge], then `fitRect`'d into a board.
     * `dragEdge` splits the delta over the cells and rounds ties away from zero; this class adds
     * nothing.
     */
    fun dragged(edge: SpriteGridMath.Edge, dxDoc: Float, dyDoc: Float): Pair<Board, SpriteGrid>

    // ---- sub-grid ----

    /** `div` clamped into 2..8 by `SpriteGridMath`; 1 means "off" and is not a sub-grid. */
    fun subGridDiv(div: Int): Int

    /** The sub-grid lines in document px — `SpriteGridMath.subGridLines`, verbatim. */
    fun subGridLines(div: Int): List<FloatArray>
}
```

```kotlin
// joybrush/androidkit/src/main/kotlin/.../androidkit/sprite/SpriteGridView.kt
class SpriteGridView(context: Context) : View(context) {
    fun setBoard(board: Board)
    /** The cell being worked on. Wears a 1.5 dp `jb_state_selected` ring. */
    fun setSelectedCell(index: Int)
    /** The cell playing right now, when a preview is running. Wears a 2.5 dp `jb_state_live` ring. */
    fun setPlayingCell(index: Int)
    /** Which cells carry a drawing. `null` = every cell does. */
    fun setUsedCells(used: Set<Int>?)
    /** The order badge per cell, from JB-4.02's roll. Empty = no badge. */
    fun setOrderBadges(badges: Map<Int, Int>)
    fun setSubGrid(div: Int)

    fun interface OnGridGesture { fun onGridGesture(g: GridGesture) }
    fun setOnGridGesture(l: OnGridGesture?)
    fun interface OnEdgeDrag { fun onEdgeDrag(edge: SpriteGridMath.Edge, dxDoc: Float, dyDoc: Float) }
    fun setOnEdgeDrag(l: OnEdgeDrag?)
}

sealed class GridGesture {
    object Begin : GridGesture()                 // a finger landed, no handle, no cell yet
    data class CellTapped(val index: Int) : GridGesture()
    data class CellLongPressed(val index: Int) : GridGesture()
    data class EdgeGrabbed(val edge: SpriteGridMath.Edge) : GridGesture()
    object Cancel : GridGesture()                // a second finger, or a lift that took no handle
}
```

## Decisions

1. **This spec calls `SpriteGridMath` and adds no arithmetic.** Every grid number is its answer.
   *Why:* R23, and the `MAX_SIZE_PX` lesson. `SpriteGridMath.MAX_CELLS`, `MAX_CELL_PX` and the
   rounding are **public and reviewed**; a second copy in the board is a copy that can drift, and
   nothing can catch it. Test 9 is the mechanical form of this sentence.
2. **A board resized by any grid change is `fitRect`'d, always.** `bySize`, `byCount` and
   `dragEdge` all end in `fitRect`, so the board's rect is exactly the grid and an export of "this
   board" is the grid and not the old rect with a sliver hanging off it. *Why:* `fitRect`'s own
   KDoc says this is what it is for, and a sprite board whose rect is bigger than its grid exports
   a margin of empty cells.
3. **The edge grab is decided ONCE, at finger-down, and is `edgeGrabPx` = 22 dp × density from the
   edge — INCLUSIVE at the edge, and the corner wins over the two edges.** The corner is checked
   first because it is the intersection; a finger at the corner is unambiguous by construction
   rather than by a tie-break. *Why:* JB-3.03 Decision 7 is the same rule for the same reason — a
   gesture that changes what it means halfway is how a tap becomes an accidental resize.
4. **A finger-down that is neither on a handle nor on a cell is `Cancel`, not a grab.** The spare
   strip right of or below the grid is real and is outside the grid (`cellAt` says −1 there), and a
   drag that started there must not silently resize something. *Why:* refuse in words, in the
   gesture vocabulary: `Cancel` is the word, and the view draws nothing.
5. **An edge drag reports `(dx, dy)` in DOCUMENT px and the host converts from screen.** The view
   divides by `screenPerDoc` at the edge of the view; the maths layer never sees a screen number.
   *Why:* the same seam JB-2.16a and JB-3.03 draw, and R19's direction warning is one sentence of
   terror: **pass `view.zoom`, not `1f / view.zoom`.**
6. **`dragEdge`'s axis isolation is preserved and the view must not "help" it.** `Edge.RIGHT` uses
   `dx` only, `BOTTOM` uses `dy` only, `CORNER` both. The 4.01a suite already pins this with
   `∓9999` on the ignored axis, and the view's `OnEdgeDrag` passes both numbers with the axis
   named rather than pre-zeroing one. *Why:* pre-zeroing in the view is a second implementation of
   the rule, in a place with no tests.
7. **A drag that would take a cell below 1 px is still allowed, and `dragEdge` clamps it to 1.**
   There is no "too small, ignoring that" in this spec. *Why:* the clamp is in the maths, it is
   tested there, and duplicating the refusal here would be a second answer to the same question.
8. **The sub-grid is 2..8, and "off" is `div < 2`, which returns an EMPTY list** — never `div = 1`
   drawn as a line down the middle of every cell. *Why:* `subGridLines` already returns an empty
   list for `div < 2`; the board must not invent a `1` to mean "on" and then have to special-case it.
9. **The sub-grid is a HELPER and is never in an export** (blueprint §3). It is a `View` and never a
   layer, so this is true by construction — and `subGridLinesAreNeverInAnExport` pins it anyway, the
   same way JB-3.02 pins its rulers. *Why:* a line somebody used to aim with is a line in someone
   else's sprite.
10. **Cell states, in the app's own vocabulary, because SpriteLab's are the ones a person has seen:**
    used = a 1.5 dp `jb_state_selected` (cyan) outline; playing = a 2.5 dp `jb_state_live` (pink)
    ring; order badge = a cyan disc top-centre, pink when that cell is the one playing
    (`JOYBRUSH_VISUAL_LANGUAGE.md` §4.3, which records these as SpriteLab's exact states).
    *Why:* the visual language's own rule is "inside the board, cell states must match
    `SpriteGridEditorView` exactly", and a person moving between the two apps should not learn a
    new colour language.
11. **A cell is USED if it has a non-transparent pixel in it, and "used" is computed from the
    rendered region, never stored.** A sheet of 40 cells where 3 have anything in them has 3 used
    cells. *Why:* the model has no per-cell content (`SpriteGrid` is four `Int`s), and inventing a
    per-cell flag is a document-format change. Deriving it is free at export time and it cannot go
    stale. See Q1 — this is the one place a ruling could reasonably go the other way.
12. **A tap on a cell is `CellTapped` and a LONG-press is `CellLongPressed`; a long-press does not
    also fire a tap.** *Why:* the same rule as JB-3.02's pegs (blueprint §1(a) — hover is the pen
    bonus, long-press is the finger's options menu) and a double-fire is how a cell ends up in the
    play order twice by accident.
13. **A second finger cancels the grid gesture**, exactly as it cancels the film strip's
    (JB-3.03 Decision 11). *Why:* the house rule, already written down twice. A second finger is a
    palm or a pinch; neither means "resize my sheet".
14. **The board's edge handle is drawn only when the grid is on**, and the grid is a
    `Board.kind == SPRITE` thing — a board of any other kind draws no grid at all. A board with
    `grid == null` is refused by `DocOps.validate` rule 5, so the view's "no grid" is a broken
    document, not a state; the view shows nothing and says nothing. *Why:* refuse in words, and the
    words are the validator's, at open time.
15. **Nothing in this spec mutates the document.** The view emits intentions
    (`OnEdgeDrag`, `CellTapped`); the host applies them through `SpriteBoard.withGrid` /
    `SpriteBoard.fitted` and the document is immutable throughout. *Why:* the undo stack holds
    documents, and a view that mutated one would be a view the undo stack could not describe.
16. **The grid is drawn UNDER the art, never over it**, at `jb_line` for the cell borders and
    `jb_line` at 40% for the sub-grid. *Why:* a grid drawn over a sprite hides the sprite, and the
    visual language's `LINE` is a divider, not an ink.

## Decision → Test map (every Decision is checkable)

| Decision | Pinned by |
|---|---|
| 1 (calls, never re-derives) | `thisClassHoldsNoGridArithmeticOfItsOwn` — reflection over `SpriteBoard`'s constants: no `Int`/`Float` cap that `SpriteGridMath` already publishes |
| 2 (`fitRect` always) | `everyGridChangeLeavesTheBoardExactlyTheSizeOfItsGrid` — for `bySize`, `byCount` and `dragEdge` |
| 3 (grab once, inclusive, corner first) | `theCornerWinsOverBothEdges`, `theEdgeItselfIsInsideItsOwnGrabZone`, `aSecondFingerDoesNotBecomeAResize` |
| 4 (outside → `Cancel`) | `aTouchOnTheSpareStripIsCancelAndNotAHandle` |
| 5 (document px at the maths seam) | `theEdgeDragArrivesInDocumentPixels` — a 44 screen px drag at zoom 4 is 11 doc px |
| 6 (axis isolation, not pre-zeroed) | `theIgnoredAxisIsStillPassedAndStillIgnored` — the view passes `∓9999` on the ignored axis and the grid is unchanged on it |
| 7 (no second refusal) | `aCellIsNeverRefusedHereItIsClampedOnceInTheMaths` |
| 8 (off is empty, not `div = 1`) | `aSubGridOfOneIsOffAndNotALineDownEveryCell` |
| 9 (never exported) | `subGridLinesAreNeverInAnExport` |
| 10 (SpriteLab's cell states) | `theCellStatesAreTheOnesSpriteLabUses` — cyan for used, pink for playing, ring not fill |
| 11 (used is derived) | `aCellWithNothingInItIsNotUsed`, `usedCellsAreComputedNotStored` (no field on `SpriteGrid` names them) |
| 12 (long-press does not also tap) | `aLongPressOnACellFiresNoTap` |
| 13 (second finger cancels) | `aSecondFingerEndsAGridGesture` |
| 14 (grid only on a SPRITE board) | `aBoardOfAnotherKindDrawsNoGrid` |
| 15 (no mutation) | `noFunctionInThisFileReturnsAMutatedDocument` — every `Board`-returning function's argument is untouched, asserted field for field |
| 16 (under the art, `LINE`) | `theGridIsDrawnUnderTheArtAndInTheLineColour` |

## Tests

`joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/sprite/SpriteBoardTest.kt`

1. `bySize` matches the maths exactly: `bySize(64, 64)` on a 512 × 256 rect → **8 × 4 of 64 × 64**,
   and the board after `fitted` is exactly 512 × 256. Asserted **against `SpriteGridMath.bySize`**
   and then against the literal, in that order, so the test fails if either side is wrong.
2. `bySize` with 100 × 100 → **5 × 2**, and the board becomes 500 × 200 — the leftover 12 × 56 is
   gone from the board, because `fitRect` (Decision 2). A board that kept 512 × 256 here would be
   exporting 12 px of nothing.
3. `byCount(3, 2)` on 500 × 300 → cells of 166 × 150, board becomes 498 × 300.
4. `everyGridChangeLeavesTheBoardExactlyTheSizeOfItsGrid`: for 20 combinations of `bySize`,
   `byCount` and `dragEdge`, `board.rect.w == grid.cols * grid.cellW` and
   `board.rect.h == grid.rows * grid.cellH`, exactly. Asserted with `==` on `Int`s, no tolerance.
5. `aTouchOnTheSpareStripIsCancelAndNotAHandle`: a 5 × 2 grid on a 500 × 300 rect leaves a 2 px
   strip at the right and none below; a finger at document x = 499 (inside the board, outside the
   grid) → `edgeAt` returns **null** and `SpriteGridMath.cellAt` returns −1. Both halves, because a
   strip that is grabbable by one and not the other is a bug.
6. `theCornerWinsOverBothEdges` / `theEdgeItselfIsInsideItsOwnGrabZone`: at the exact corner
   (screen 0 px from the grid's right edge AND 0 px from its bottom) the answer is `CORNER`. At the
   right edge, 3 px above the bottom, it is `RIGHT`. At the bottom edge, 3 px right of the right
   edge, it is `BOTTOM`. The three cases, because a corner that resolves to an edge is an
   off-by-one in the wrong direction and resizes only one axis.
7. `theEdgeDragArrivesInDocumentPixels`: a 44 screen px drag at `screenPerDoc = 4` is 11 doc px, and
   44 screen px at zoom 0.25 is 176 doc px. Both, and the direction of the conversion is named in
   the test's comment so a builder who inverts it fails on the number rather than on a feeling.
8. `theIgnoredAxisIsStillPassedAndStillIgnored`: `dragEdge(CORNER, 80f, -50f)` on an 8 × 4 grid
   changes both; `dragEdge(RIGHT, 80f, -50f)` changes `cellW` and leaves `cellH` **bit-identical**,
   and the same for `BOTTOM`. Pinned with a large ignored-axis value (`∓9999`) as 4.01a does.
9. `aSubGridOfOneIsOffAndNotALineDownEveryCell`: `subGridLines(1)` and `subGridLines(0)` and
   `subGridLines(-3)` all return **empty**; `subGridLines(9)` empty; `subGridLines(2)` on a 2 × 1
   grid of 64 → **4** segments at the midlines. Counted, not described.
10. `aCellIsNeverRefusedHereItIsClampedOnceInTheMaths`: dragging an edge far past zero yields a
    cell of exactly 1 and a board of exactly `cols × 1`, and **does not throw**.
11. `noFunctionInThisFileReturnsAMutatedDocument`: the board passed to every `Board`-returning
    function is compared field for field with a copy taken before the call. This is the
    immutability claim as a test, and it is cheap.
12. `thisClassHoldsNoGridArithmeticOfItsOwn`: `SpriteBoard::class` declares no `Int` or `Float`
    constant other than the `edgeGrabPx` base — the `MAX_CELLS` / `MAX_CELL_PX` / clamp values live
    in `SpriteGridMath` and are **not** restated here.
13. `usedCellsAreComputedNotStored`: `SpriteGrid` has four fields and none of them is a set; and a
    rendered region with alpha in three of forty cells yields exactly three used cells.
14. `aBoardOfAnotherKindDrawsNoGrid`: a board whose `kind` is `CANVAS` produces no grid lines and
    no handle, whether or not it has a `grid` value.
15. `subGridLinesAreNeverInAnExport`: `RegionRenderer.render` of the same document with and without
    a sub-grid produces byte-identical output. (The sub-grid is a `View`; the test says so.)
16. `aLongPressOnACellFiresNoTap` / `aSecondFingerEndsAGridGesture` /
    `theCellStatesAreTheOnesSpriteLabUses` / `theGridIsDrawnUnderTheArtAndInTheLineColour`:
    asserted against the view's own reported layout and gesture stream, so they check a contract
    rather than a comment.

**Non-vacuity the builder must run and paste:** drop the `fitted(...)` call from Decision 2 and
watch test 4 fail; then change `subGridLines(1)` to return `subGridLines(2)` and watch test 9 fail.
A helper that has never been seen wrong is a helper nobody can rely on.

Command: `./gradlew -p joybrush :core:jvmTest` — 0 failures.

## Do not

- **Do not reimplement `SpriteGridMath`.** Not a clamp, not a round, not a cap. Call it. (R19 made
  `MAX_SIZE_PX` public for exactly this reason and a builder "fixing" it again is a regression.)
- Do not change `SpriteGrid`, `Board` or the document format. This spec adds no field anywhere.
- Do not store a per-cell "used" flag. It is derived (Decision 11), and adding it is a format
  change — see Q1.
- Do not hit-test in screen px below the maths layer. Convert once, at the view (Decision 5).
- Do not pre-zero the ignored axis in an `OnEdgeDrag` callback. Name the edge and pass both.
- Do not draw the grid OVER the art, and do not give a sub-grid a colour of its own.
- Do not add a "snap to power of two", a centre line, a golden-ratio overlay or an art-nudge
  overlay. Not in the blueprint.
- No hex literals — `JbColors.palette(context)` only.

## Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest` output pasted, 0 failures
- [ ] the non-vacuity proof pasted (drop `fitted` → test 4 red; `div = 1` → test 9 red)
- [ ] watcher `build.log` shows `:androidkit:compileKotlin` and `:joybrush-android:compileDebugKotlin` EXECUTED inside `BUILD SUCCESSFUL`
- [ ] `git status --short` shows only the owner-area paths
- [ ] committed `JB-4.01: sprite board`; ROADMAP row set by the Lead

## Questions

_(Spec writer: openrouter/stealth/space-bunny-alpha, 2026-09-29. **Neither question blocks the
build** — both are flagged non-blocking and the code above does not depend on either answer.)_

### Q1 — for the Lead, non-blocking: should "used" be derived or stored?

Decision 11 derives it: a cell is used if its rendered pixels are not all transparent. That is free,
cannot go stale, and adds nothing to the document. The cost is that it costs a render to know, so
the board view cannot show "which cells are used" until something has rendered — the strip in
JB-4.02 will have to render cells on demand.

The alternative is a `used: Boolean` per cell, which means either a new field on `SpriteGrid` (a
document-format change, R3, and `SpriteGrid` is a four-`Int` value class in a reviewed contract) or
a parallel list. **A person who has drawn a sprite, then erased it, would see the cell stay "used"
with the second design** — which is a real annoyance and a real thing some people want (a cell you
intentionally emptied is still a frame you meant to include).

**My recommendation is derived**, because the model's whole philosophy is "pixels are the truth" for
paint layers and a parallel truth that can disagree is what `DocOps.validate` exists to prevent. But
"deliberately emptied is still a frame" is a real need and it is a **product** call, and if you want
it, it wants a document change and therefore its own row. **Yours to rule; nothing here waits on it.**

### Q2 — for the Lead, non-blocking: the grid pill's numbers, and where they are dragged

The board needs a way to say "64 px" or "8 × 4", and the blueprint's rule is that values change by
dragging on the control (§3.5). The natural control is a **scrubbable number** —
`SpriteSheetEditorActivity.NumPill`, which `JOYBRUSH_VISUAL_LANGUAGE.md` §2 lists as **TRAPPED**
("highest-value extraction for Joy Brush") and D.02c's row already claims the extraction of a
"scrubbable number" into `:studiokit`.

**So the pill is D.02b's / D.02's, not this spec's**, and this spec deliberately does not build one.
The view exposes `OnGridGesture` and the maths exposes `bySize` / `byCount`, and the host wires
whatever number control exists on the day. **Is that right, or should JB-4.01 build a plain
`±`/`−` stepper pair now and let the real pill replace it later?** My inclination is the stepper
pair — it is twenty lines, it is testable, and a board with no way to set its grid size is a board
that does not work. Recorded so it is a decision rather than an omission.

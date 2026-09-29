# JB-3.03 — Film strip: sprockets, the ± actions, drag-a-frame's-edge to hold it, finger scrub

| | |
|---|---|
| **Tier** | T2-V (the layout maths and the gesture rules are plain T2 and are tested in `:core`; the drawing is the vision half) |
| **Status** | 🟨 **Draft.** Everything below is decided and a T2 builder can build and test `:core` today. Two things are not mine to decide: whether the strip's frame THUMBNAILS come from the GL engine or from a CPU render (Q1 — it decides which module owns a new interface), and what a long-press on a frame opens (Q2). Both are one-line answers. |
| **Needs** | 3.01 (as the ROADMAP row states) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/anim/FilmStrip.kt` · NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/anim/FilmStripTest.kt` · NEW `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/anim/FilmStripView.kt` · EDIT `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/JoyBrushActivity.kt` (host the strip, wire the callbacks — nothing else in that file) |
| **Estimated size** | ~320 lines of Kotlin in `:core` + ~320 lines of tests, ~300 lines of view |
| **Command** | `./gradlew -p joybrush :core:jvmTest` — 0 failures. Then the watcher compiles `:androidkit:compileKotlin` and `:joybrush-android:compileDebugKotlin` green. |

## Goal

Blueprint §1 idea 10, and specifically its change (b), which is the whole point of this row:

> **"A frame's cell width shows how long it is held, and you drag its edge to hold it longer"** —
> FlipaClip, the #1 animation app (82M installs), has refused its users this for 12 years (R6).

So the strip is not a row of equal thumbnails. **A frame's cell is `holdFrames × TICK_PX` wide.**
A frame held for 3 ticks is three times as wide as one held for a tick, and dragging its right edge
changes that number and nothing else. Everything else the strip does — sprockets, the ± actions, the
finger scrub — is in service of that one sentence.

## The seam, stated first because it is the bug this project keeps shipping

Every arithmetic decision in this file is taken **from `AnimOps.frameStartsMs` and `AnimOps.totalDurationMs`
and never from a second evaluation of `holdFrames × 1000 / fps`**. `2 × (1000.0 / 12.0)` is **not**
bit-identical to `2000.0 / 12.0`, and a boundary one ulp out is on the wrong side of the
`start ≤ position < start + duration` comparison that decides which frame is under a finger. The
3.05a test file already writes this out at length; it applies here verbatim, and `FilmStripTest`
indexes `frameStartsMs` the same way.

And the second seam, which is the one that bites hardest and which this spec exists to prevent:

> **The strip MUST NOT drive itself from `PlaybackClock.nextChangeMs`.** At a backward boundary of a
> PING_PONG leg the frame change **has no minimum** — `nextChangeMs` hands back the boundary itself
> as an *infimum* (JB-3.05a Decision 3 / its Questions §4), and a caller that sleeps until it and
> then re-asks without comparing frame indices **spins forever**. That is correct behaviour of the
> clock and a bug in the caller.

The strip never asks the clock anything. The strip maps **a finger's x to a frame** and a frame's
**id** to a cell. Playback is a different row (JB-3.05) and a different loop. See Decision 2.

## Contract

```kotlin
package cc.joycreator.joybrush.core.anim

import cc.joycreator.joybrush.core.doc.Board

/**
 * The film strip's geometry and gesture rules, as pure maths. No Android, no clock, no document
 * mutation: every function that changes the document returns a NEW document and a list of
 * instructions, exactly like `AnimOps` does.
 *
 * The strip's state is a PLAYHEAD, which is a frame ID and never an index. Every operation in this
 * file that can remove a frame re-anchors the playhead by ID first. See Decision 1.
 */
class FilmStrip(val board: Board, val density: Float = 1f) {

    /** Screen px per tick of hold (already × density). One tick is 44 dp. */
    val tickPx: Float get() = TICK_PX_BASE * density

    /** How close a finger-down must be, screen px, to a frame's RIGHT edge to grab that edge. */
    val edgeGrabPx: Float get() = EDGE_GRAB_BASE * density

    /**
     * Cell [frameIndex]'s left edge in STRIP coordinates, strip px from the strip's own left end.
     * Cell 0's left edge is 0. Derived from `AnimOps.frameStartsMs` and the board's own
     * `totalDurationMs` — never from a second `hold * 1000 / fps`.
     */
    fun cellLeft(frameIndex: Int): Float

    /** Cell width: `holdFrames × tickPx`, never less than one tick. */
    fun cellWidth(frameIndex: Int): Float

    /** Total strip width: the last cell's right edge. */
    fun stripWidth(): Float

    /**
     * Which frame's cell contains strip coordinate [xPx]. HALF-OPEN: `x` in
     * `[cellLeft(i), cellLeft(i) + cellWidth(i))` is frame i, so the exact pixel on a boundary
     * belongs to the frame that STARTS there. Before the first cell → 0; past the last cell's
     * right edge → the last frame. A non-finite x → the last frame.
     */
    fun frameAt(xPx: Float): Int

    /**
     * Which frame's RIGHT EDGE a finger-down at [xPx] has hold of, or -1.
     * The grab zone is `[edgeLeft - edgeGrabPx, edgeLeft]` — to the LEFT of the edge only, and
     * INCLUDING the edge itself. Frame 0's right edge is a real edge; the strip's own left end
     * (x = 0) is not, because there is no frame before it to lengthen.
     */
    fun edgeAt(xPx: Float): Int

    /**
     * The playhead after the frame [frameId] is deleted, given the board that results.
     * Anchored by ID: the frame that now sits where the deleted one was, or the new last frame if
     * the deleted one was the last. See Decision 1.
     */
    fun playheadAfterDelete(boardAfter: Board, deletedFrameId: String, playheadFrameId: String?): String?

    // ---- the four actions. Each returns a NEW board; none of them mutates. ----

    /** `+` on frame [frameId]: a BLANK frame after it (`AnimOps.addFrame(BLANK)`). */
    fun addBlank(board: Board, frameId: String, ids: () -> String): AnimResult

    /** `+` long-press "Duplicate": a DUPLICATE frame after it — a new cel with a copy of the pixels. */
    fun addDuplicate(board: Board, frameId: String, ids: () -> String): AnimResult

    /** `+` long-press "Link": a LINK frame after it — the SAME cel, no pixel work. */
    fun addLink(board: Board, frameId: String, ids: () -> String): AnimResult

    /**
     * The edge drag, live. [dxPx] is the TOTAL screen offset since the finger went down, positive
     * right. Returns the frame's new hold, clamped by `AnimOps.setHold` to 1..999 — **this class
     * never clamps the hold itself and never writes its own 999**; it calls `setHold` and reads
     * `holdFrames` back out of the returned document.
     */
    fun heldAfterDrag(board: Board, frameId: String, dxPx: Float): Int

    companion object {
        /** Screen px per tick before density. 44 dp — the house touch floor. */
        const val TICK_PX_BASE: Float = 88f
        /** Edge grab depth before density, screen px. 12 dp either side of the edge's centre. */
        const val EDGE_GRAB_BASE: Float = 24f
    }
}
```

```kotlin
// joybrush/androidkit/src/main/kotlin/.../androidkit/anim/FilmStripView.kt
class FilmStripView(context: Context) : View(context) {
    /** Thumbnail per frame id, or null for a frame the host has not rendered yet. */
    fun setThumbnail(frameId: String, bitmap: Bitmap?)
    fun setBoard(board: Board)
    fun setPlayhead(frameId: String?)
    fun setOnion(on: Boolean, past: Int, future: Int)
    fun interface OnAction { fun onAction(a: Action) }
    fun setOnAction(l: OnAction?)
}

sealed class Action {
    object Play : Action()
    object Next : Action()
    object Previous : Action()
    data class AddBlank(val afterFrameId: String) : Action()
    data class AddDuplicate(val afterFrameId: String) : Action()
    data class AddLink(val afterFrameId: String) : Action()
    data class Delete(val frameId: String) : Action()
    data class SetHold(val frameId: String, val holdFrames: Int) : Action()
    data class ScrubTo(val frameId: String) : Action()
    data class LongPressMenu(val frameId: String) : Action()
}
```

## Decisions

1. **The playhead is a frame ID, and every operation re-anchors it by ID before it re-anchors by
   index.** Deleting the frame the playhead is on moves the playhead to **the frame now at the
   deleted frame's old index**, or to the new last frame if the deleted one WAS the last.
   Deleting any OTHER frame leaves the playhead alone, by ID, whatever the indices did.
   *Why:* the playhead is a frame, and an index is a position that a delete has just renumbered.
   An index playhead lands one frame past the end of a board whenever the frame before it is
   deleted, and this is the exact off-by-one the project keeps shipping.
2. **The strip NEVER asks `PlaybackClock` anything.** It maps x → frame and frame → cell, and
   nothing else. It never sleeps, never holds a clock and never calls `nextChangeMs`. *Why:*
   JB-3.05a Questions §4 — at a backward boundary `nextChangeMs` is an **infimum**, so "sleep until
   `nextChangeMs` and ask again" spins forever at every falling edge of a PING_PONG leg. A scrub is
   a finger's position, not a time, and keeping the two apart is what stops the strip from
   inheriting that trap when the player lands. **If JB-3.05 ever drives a playhead marker along this
   strip, the marker is drawn from `frameIndexAt` and the wake is scheduled with a frame comparison,
   never with `nextChangeMs` alone.**
3. **Cell width is `holdFrames × tickPx`, and `tickPx` is 44 dp × density.** A one-tick frame is
   44 dp — pressable, and the same floor the peg bar uses. A ten-tick frame is 440 dp and the strip
   scrolls. *Why:* a cell that does not scale with its hold would not be showing the hold at all,
   which is the entire premise.
4. **A frame held 999 ticks is 43 956 dp wide and the strip still lays it out.** No cap on the drawn
   width; the cap is on the hold (`AnimOps.setHold` clamps 1..999) and the strip scrolls. *Why:*
   clamping the drawn width would make a long-held frame look short, which is a lie.
5. **`frameAt` is HALF-OPEN, `[cellLeft(i), cellLeft(i) + cellWidth(i))`.** The exact pixel on a
   boundary belongs to the frame that STARTS there. Before the strip → frame 0; past the end → the
   last frame; a non-finite x → the last frame. *Why:* the same rule as `AnimOps.frameAt` and as
   `PlaybackClock.slotAt`. Three implementations of "which frame is showing" that disagree by one
   frame at a boundary is exactly the failure this project has already paid for twice.
6. **`edgeAt` grabs only to the LEFT of a frame's right edge, `in [edgeLeft − edgeGrabPx,
   edgeLeft]`, and the zone INCLUDES the edge.** There is no "left edge" grab zone at all, so the
   two zones can never overlap and no tie-break is needed. *Why:* a left-edge zone would overlap the
   previous frame's right-edge zone at every interior boundary and the winner would have to be
   decided by an arbitrary tie. One zone, no tie.
7. **A finger-down is an EDGE GRAB if `edgeAt(x) >= 0`, otherwise a SCRUB — and that decision is made
   ONCE, at finger-down, and never revisited for the life of the gesture.** *Why:* a gesture that
   changes what it means halfway is how a scrub becomes an accidental hold change. The blueprint's
   rule for the whole app: a mode is fixed at `begin` (JB-3.08a Decision 3 is the same rule for the
   same reason).
8. **The edge drag's new hold is computed by CALLING `AnimOps.setHold` and reading
   `holdFrames` back out of the document it returns.** `FilmStrip` contains **no copy of 999** and
   no copy of 1. *Why:* `MAX_HOLD_FRAMES` is `private` in `AnimOps`, so a local copy could drift and
   nothing could catch it — the same trap R19 already fixed once for `SizeOpacityDrag.MAX_SIZE`.
   Reading back also means the drawn width is always the width the model will keep, even when the
   model clamped.
9. **The drag's step is `round(dxPx / tickPx)` with ties away from zero**, and the new hold is
   `holdAtDown + step`. Ties away from zero, deliberately **not** Kotlin's `round` (ties-to-even) —
   the same ruling and the same reason as `SpriteGridMath.dragEdge`'s `roundedPx`, and for the same
   reason: a person dragging under a finger expects the value it looks nearest, and 0.5 px is a tie
   only by arithmetic. *Why:* one rounding idiom in the project, not two.
10. **Dragging LEFT past the frame's own start clamps to 1 and the cell stops shrinking.** Dragging
    right past 999 clamps to 999. Both are read back from the document (Decision 8), so the cell
    stops moving and the finger keeps going — the strip is showing the model's answer, not a
    number it invented. *Why:* silent clamping is the house no; a *visible* clamp that comes from
    the model is the model being honest.
11. **A second finger cancels the whole strip gesture.** During a scrub, the second finger's arrival
    ends the gesture with **no** frame change committed and the playhead where it was. During an edge
    drag it ends the gesture with **no** `SetHold` — the document is untouched, so the drag can be
    simply abandoned. *Why:* the house rule for a second finger is already written down twice (JB-0.07
    cancels a finger stroke; JB-2.02 hands the stream to the gesture machine) and the strip is no
    exception. A second finger is a palm, or a pinch, and neither means "change my frame".
12. **A `+` TAP adds a BLANK frame. The LONG-PRESS opens a menu with three items: Blank, Duplicate,
    Link.** Blank is the tap because it is the one that cannot be wrong, and Duplicate is one
    long-press away because it is the one people want most. *Why:* the blueprint lists "± buttons to
    add/duplicate/extend/delete" and does not rank them; ranking is a product decision and this is
    mine to make provisionally (Q2).
13. **"Extend" is the drag, not a button.** The menu's hold item is `Hold +1` / `Hold −1`, which is
    the *accessible* version of the drag for anyone who cannot drag an edge, and it goes through the
    same `AnimOps.setHold` and the same read-back. One code path, two affordances. *Why:* two
    affordances for one number is right; two code paths for one number is how they drift.
14. **Delete is `jb_state_destroy` filled, and it is refused in words when the board has one frame**
    — `AnimOps.deleteFrame` already throws for exactly that, and the strip's `−` is **disabled**
    (not silent) when `board.frames.size == 1`, because a control that is visibly dead teaches and
    a control that is silently dead is a bug report. *Why:* refuse in words, do not clamp.
15. **The strip scrolls horizontally and the playhead is kept visible by the HOST after every
    change**, not by the strip mid-gesture. *Why:* auto-scrolling during a drag moves the pixels
    under a finger, and the drag is the one gesture in this app that must be perfectly still.
16. **Sprockets are drawn in the strip's top and bottom rails, 4 per cell at a fixed spacing, and
    they do NOT scale with the cell.** Real film has four perforations per frame regardless of how
    long the frame is held — a held frame is *the same frame held longer*, not more frames. *Why:*
    this is what "sprockets should do what sprockets do" (blueprint §1 idea 10) means, and it is the
    one place where a decorative scaling would be actively misleading about the timing.
17. **The playhead wears `jb_state_live` as a 2.5 dp ring; onion ghosts are JB-3.04's and the strip
    only shows their frame indices in the rails.** *Why:* a coloured ring already means a state
    (D.01), and the live ring is the one state this board owns.
18. **The `±` button is drawn at each cell's LEFT cap**, not its right, so it never sits under the
    drag zone (Decision 6) and can never be mistaken for an edge. *Why:* the edge is the special
    gesture on this strip; nothing else may live where the edge is.

## Decision → Test map (every Decision is checkable)

| Decision | Pinned by |
|---|---|
| 1 (playhead is an ID) | `deletingTheFrameUnderThePlayheadMovesItToTheFrameThatReplacedIt`, `deletingTheLastFrameLeavesThePlayheadOnTheNewLast`, `deletingAnyOtherFrameLeavesThePlayheadAlone` |
| 2 (no clock) | `theStripNeverConsultsThePlaybackClock` — the file's import list and public API contain no `PlaybackClock`; asserted by reflection over the class's declared methods |
| 3 (width = hold × tick) | `aCellIsAsWideAsItsHold` (holds 1/2/3/10 on a 12 fps board), `aOneTickFrameIsTheTouchFloor` |
| 4 (999 draws) | `aNineHundredAndNinetyNineTickFrameStillDrawsItsWholeWidth` |
| 5 (half-open) | `thePixelOnABoundaryBelongsToTheFrameThatStartsThere`, `beforeTheStripIsFrameZero`, `pastTheEndIsTheLastFrame`, `aNaNXIsTheLastFrame` |
| 6 (one zone, left of the edge) | `anEdgeIsGrabbedFromItsLeftOnly`, `theEdgeItselfIsInsideItsOwnZone`, `thereIsNoLeftEdgeZoneSoTwoZonesCannotOverlap` |
| 7 (decided at down, never revisited) | `aDragThatCrossesAnEdgeMidGestureDoesNotBecomeAnEdgeGrab` |
| 8 (no local 999) | `theStripHasNoCopyOfTheHoldClamp` — reflection over `FilmStrip.Companion` constants, plus a test that a 5000-tick drag reads back 999 |
| 9 (ties away from zero) | `theDragStepRoundsTiesAwayFromZero` (the ±0.5 case the reviewer found missing in 4.01a) |
| 10 (clamps visibly) | `draggingLeftStopsAtOneTickAndTheCellStopsShrinking`, `draggingRightStopsAtTheModelsOwnLimit` |
| 11 (second finger cancels) | `aSecondFingerEndsAScrubWithNothingCommitted`, `aSecondFingerEndsAnEdgeDragWithNoHoldChange` |
| 12 (tap = blank, hold = menu) | `theAddButtonAddsBlankOnATap` |
| 13 (one hold code path) | `theMenuHoldButtonAndTheEdgeDragProduceTheSameHold` |
| 14 (delete disabled at one frame) | `theDeleteButtonIsDeadOnAOneFrameBoardAndTheModelAlsoRefuses` |
| 15 (no auto-scroll mid-gesture) | `theStripDoesNotScrollWhileADragIsInProgress` |
| 16 (sprockets fixed) | `sprocketCountIsFourPerFrameAndDoesNotScaleWithTheHold` |
| 17 (playhead ring) | `thePlayheadIsARingInTheLiveColourAndNotAFill` |
| 18 (± on the left cap) | `theAddButtonIsOnTheLeftCapSoItIsNeverInTheEdgeZone` |

## Tests

`joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/anim/FilmStripTest.kt`

Fixture: a 12 fps ANIMATION board with holds `[1, 2, 1, 3]` and frame ids `f0..f3`, built with
`DocOps.newDocument` + `AnimOps`, exactly as `PlaybackClockTest` builds its own. **All boundaries are
taken by indexing `AnimOps.frameStartsMs(board)`, and the end of the board is
`AnimOps.totalDurationMs(board)` — never by accumulating `hold * 1000 / fps`.** That is the rule the
whole file runs on and it is written into the file's own KDoc.

1. `aCellIsAsWideAsItsHold` at density 1 (`tickPx` 88): f0 = 88, f1 = 176, f2 = 88, f3 = 264, and
   `cellLeft` = 0, 88, 264, 352. Strip width 616 = the sum, and it is **not** proportional to
   `totalDurationMs` unless the fps is 12 — assert that explicitly, because a strip that sized cells
   by wall time instead of by ticks would pass the first assertion and fail this one.
2. `aOneTickFrameIsTheTouchFloor` at density 3: `tickPx` = 264, so a 1-hold cell is 264 screen px.
3. `aNineHundredAndNinetyNineTickFrameStillDrawsItsWholeWidth`: `setHold(999)` then `cellWidth` =
   88 912 — no cap, no exception.
4. `thePixelOnABoundaryBelongsToTheFrameThatStartsThere`: `frameAt(88.0f)` = 1, `frameAt(87.999f)`
   = 0, `frameAt(263.999f)` = 1, `frameAt(264.0f)` = 2. Every one of the four, because a boundary
   tested from one side only is a boundary that can be off by one in the direction nobody looked.
5. `beforeTheStripIsFrameZero` / `pastTheEndIsTheLastFrame` / `aNaNXIsTheLastFrame`:
   `frameAt(-1f)` = 0, `frameAt(1e9f)` = 3, `frameAt(NaN)` = 3, `frameAt(+∞)` = 3.
6. `anEdgeIsGrabbedFromItsLeftOnly` with `edgeGrabPx` = 24: `edgeAt(88f)` = 0 (the edge itself is
   inside), `edgeAt(64f)` = 0, `edgeAt(87.999f)` = 0, `edgeAt(88.001f)` = **−1**. And `edgeAt(0f)`
   = −1, because the strip's left end is not a frame's right edge.
7. `thereIsNoLeftEdgeZoneSoTwoZonesCannotOverlap`: for every pair of consecutive frames, the grab
   zones are disjoint **as sets** — assert `edgeAt` is a function (each x yields one answer, and the
   two zones never both contain it) across 2000 probes over the whole strip.
8. `theDragStepRoundsTiesAwayFromZero`: at density 1, a drag of exactly `tickPx / 2` = 44 px adds
   **+1**, and −44 px subtracts **1**. `kotlin.math.round` would give 0 for both (ties-to-even) — the
   test says so in its comment, because that is the difference between this test and a tautology.
9. `draggingLeftStopsAtOneTickAndTheCellStopsShrinking`: f1 (hold 2) dragged −1000 px → hold 1, and
   `cellWidth` then equals 88, unchanged by a further −1000 px.
10. `draggingRightStopsAtTheModelsOwnLimit`: f0 dragged +10 000 px → `setHold` returns a document
    whose `f0.holdFrames` is **999**, and the test asserts against `AnimOps`, not a literal in this
    file. (If `MAX_HOLD_FRAMES` ever moves, this test follows it and
    `theStripHasNoCopyOfTheHoldClamp` is the one that notices a second copy appearing.)
11. `theMenuHoldButtonAndTheEdgeDragProduceTheSameHold`: `heldAfterDrag(board, "f0", 3f × tickPx)`
    and `setHold(board, "f0", 4)` produce byte-equal documents.
12. `deletingTheFrameUnderThePlayheadMovesItToTheFrameThatReplacedIt`: 4 frames, playhead on `f1`;
    delete `f1` → playhead `f2` (the frame now at index 1), **not** `f3`.
13. `deletingTheLastFrameLeavesThePlayheadOnTheNewLast`: playhead on `f2`, delete `f3` → playhead is
    still `f2` (unchanged, by ID).
14. `deletingAnyOtherFrameLeavesThePlayheadAlone`: playhead on `f3`, delete `f0` → `f3`, and the
    test also checks it against a *renumbered index* (which would say 2) so the difference is named.
15. `aSecondFingerEndsAScrubWithNothingCommitted`: the strip's `Action.ScrubTo` fires for frames 0
    and 1, the second finger lands, a further move over frame 3 fires **nothing**, and the board
    equals the board at finger-down. Structural assertions, not generation counters.
16. `aDragThatCrossesAnEdgeMidGestureDoesNotBecomeAnEdgeGrab`: finger-down at x = 500 (not in any
    zone), drag to x = 90 (which IS frame 0's edge zone) → still a scrub, still `ScrubTo`, never a
    `SetHold`. This is Decision 7 and the most important test in the file.
17. `theAddButtonAddsBlankOnATap` / `addLink` shares a cel: `addLink` produces a board with one more
    frame and **no** new cel and no `CopyCel`; `addDuplicate` produces a new cel and a `CopyCel`.
    `DocOps.validate` clean after each.
18. `theDeleteButtonIsDeadOnAOneFrameBoardAndTheModelAlsoRefuses`: on a 1-frame board the strip
    reports delete unavailable **and** `AnimOps.deleteFrame` throws — the two must agree, or the UI
    is offering something the model will refuse.
19. `aSecondFingerEndsAnEdgeDragWithNoHoldChange`: as 15, for the drag. The document is `==` the one
    at finger-down, field for field.
20. `sprocketCountIsFourPerFrameAndDoesNotScaleWithTheHold`: 4 sprockets for a 1-hold frame and
    **4** for a 3-hold frame. (Not 12. The count is per frame, and the test says why.)
21. `theStripNeverConsultsThePlaybackClock`: `FilmStrip::class.java.declaredMethods` and
    `declaredFields` contain no reference to `PlaybackClock` or to `nextChangeMs`. This is the
    mechanical form of Decision 2, and it is the test that would catch somebody "optimising" the
    scrub through the clock later.
22. `theStripHasNoCopyOfTheHoldClamp`: `FilmStrip.Companion` declares no `Int` constant; the only
    numeric constants are `TICK_PX_BASE` and `EDGE_GRAB_BASE`. If a builder adds `MAX_HOLD = 999`
    this test goes red, which is the entire point (R19's lesson about `MAX_SIZE_PX`).
23. `theAddButtonIsOnTheLeftCapSoItIsNeverInTheEdgeZone`: the view's reported `±` rect for each
    frame does not intersect that frame's grab zone `[edgeLeft − 24, edgeLeft]`, for every frame.
24. `thePlayheadIsARingInTheLiveColourAndNotAFill` / `theStripDoesNotScrollWhileADragIsInProgress`:
    asserted against the view's own reported layout, so they are checks on the drawing contract and
    not on a comment.

Command: `./gradlew -p joybrush :core:jvmTest` — 0 failures.

## Do not

- **Do not call `PlaybackClock` from this file, or from the view.** Decision 2, and
  `theStripNeverConsultsThePlaybackClock` is the test. Playback is JB-3.05.
- Do not add a `MAX_HOLD_FRAMES`, a `MIN_HOLD_FRAMES` or any other copy of `AnimOps`' private
  numbers. Call the model, read the answer back.
- Do not re-derive `holdFrames * 1000.0 / fps` anywhere. `frameStartsMs` / `totalDurationMs`, indexed.
- Do not auto-scroll during a gesture.
- Do not draw onion ghosts. JB-3.04 owns them and this strip only reserves the rails.
- Do not touch `AnimOps.kt`. It is called, never changed (its own spec's "Do not").
- No hex literals — `JbColors.palette(context)` only.

## Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest` output pasted, 0 failures
- [ ] watcher `build.log` shows `:androidkit:compileKotlin` and `:joybrush-android:compileDebugKotlin` EXECUTED inside `BUILD SUCCESSFUL`
- [ ] `git status --short` shows only the four owner-area paths
- [ ] committed `JB-3.03: film strip`; ROADMAP row set by the Lead

## Questions

_(Spec writer: openrouter/stealth/space-bunny-alpha, 2026-09-29. The maths and every gesture rule
are decided and pinned. Two answers are needed and neither changes a single line of `:core`.)_

### Q1 — for the Lead: where do the frame THUMBNAILS come from?

`FilmStripView.setThumbnail(frameId, bitmap)` is a hole in this spec on purpose, and which module
fills it decides whether this row is T2 or T1 and whether it can land at all.

The three candidates, and what each costs:

- **(a) Read back from the GL engine.** `GlPaintEngine` already has a tile read path (`readTile`,
  used by JB-0.08b's snapshot) but it has no "render this frame at this size" call, and adding one
  means touching the T1 engine that R14 just stabilised. It is also the only option that is
  free at draw time.
- **(b) CPU render through `RegionRenderer`** (JB-2.13a, Built). It works today, needs no engine
  change, and is correct by construction — the thumbnail is the export. It costs a
  `w × h × 4` buffer per frame on the CPU, so a 40-frame board at 512² is 40 MB of transient
  allocation on a background thread, and the region budget `MAX_REGION_PX` applies per frame.
- **(c) No thumbnails in this row.** The strip draws numbered cells; the host supplies thumbnails
  when it can. This makes JB-3.03 buildable and testable today and defers the whole question to
  whoever wires the board to the engine.

**I would take (c) for JB-3.03 and open the thumbnail question as its own row**, because (a) is
engine work in the one file this project has just been told to leave alone and (b) is a memory
budget decision on a phone. But the row as written says "film strip", and a strip of numbered grey
boxes is not what the owner pictured. **Ruling: (a), (b), or (c)?**

### Q2 — for the Lead: what does a long-press on a frame open, and is tap-add-blank right?

Decision 12 rules tap = add BLANK and long-press = a three-item menu (Blank / Duplicate / Link),
plus `Hold ±1` and `Delete` on the same menu. The blueprint lists "± buttons to add/duplicate/extend/
delete" without ranking them, and the ranking is a product decision I have made provisionally
because leaving it blank is not an option.

1. **Is blank the right tap?** FlipaClip's `+` duplicates by default. My reasoning was that blank is
   the one that cannot be wrong and duplicate is one long-press away. If you would rather the tap
   duplicate (matching the app the blueprint cites by name), that is one constant.
2. **Should the menu be a sheet (`SheetKit`/`ObjectDrawer`, once `:studiokit` exists) or a plain
   `AlertDialog`?** JB-2.13b's precedent is "a plain `AlertDialog` is fine — the real chrome restyles
   it later". I have assumed that.
3. **Should long-press on the frame BODY (not the `+`) open the same menu, or do something else?**
   I ruled it opens the same menu for the frame under the finger, because a long-press on a frame
   means "about this frame" and there is nothing else it could mean.

### Q3 — for the Lead, and it is a warning rather than a question

**JB-3.05 (playback) must not be written as "sleep until `nextChangeMs`".** I have put that in
Decision 2 here so the two specs cannot be read independently and get it wrong, and
`theStripNeverConsultsThePlaybackClock` is the test that keeps the strip honest. But the strip is
only the easy half: a player that drives a playhead marker along this strip and schedules its redraw
from `nextChangeMs` will spin at every falling edge of a PING_PONG leg, because there the answer is
an **infimum** and not a minimum. JB-3.05a Questions §4 says exactly this and I am repeating it
rather than trusting that it was read. **Recommend JB-3.05 own a pure `FrameStepper` in `:core` with
the frame comparison inside it, so the loop is testable without a device.**

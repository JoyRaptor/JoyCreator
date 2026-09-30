# JB-3.03 — Film strip: the cell is the hold, drag a frame's edge to hold it, finger scrub

| | |
|---|---|
| **Tier** | **T2** (the core half is pure arithmetic over `Board`, testable on any JVM) |
| **Status** | 🟦 **Ready** — core half only. R32 units, R33 controls, and the review's corrected numbers are all applied. No Lead ruling is outstanding for the build. |
| **xr** | xr: openrouter/stealth/space-bunny-alpha 2026-09-29 — cut to the **core half** per R30; R32 units applied (tick 44 dp, edge grab one-sided 24 dp, both × density at the use site); R33 applied (`+` tap = **DUPLICATE**, the peg bar owns PLAY, no `Action.Play`); the review's corrected numbers used throughout (44/88/44/132, 0/44/132/176, strip 308, 999 × 44 = 43 956 — the old test 3 wrote 88 912); **the `AnimOps` calls in the old contract did not match the landed signatures and are now pasted verbatim**; strip thumbnails answered by R33 (numbered cells) and moved to JB-3.03b. |
| **xr (cross-review)** | xr: openrouter/stealth/space-bunny-alpha 2026-09-29 — **Ready.** Every `AnimOps` signature, every `MAX/MIN_HOLD_FRAMES` line number and every corrected width/left/strip figure re-checked against the landed files and all correct, as is the claim that the old `addBlank(board, …)` contract has nothing in the landed API to call
(`AnimOps` has no `addBlank`, and there is no `Board` → `JbDocument` bridge in the owner area).
 **Six test numbers were wrong and are fixed** (tests 7, 8, 10, 12, 13, 22) — the worst is test 13, which asked for 999 from a drag too short to reach it. One gap closed: how a test reads a hold back off a *returned* document. Q1 RULED (24 dp one-sided). |
| **Needs** | JB-3.01 (`Board`, `Frame`, `AnimOps` — all Built). JB-3.05a is **Built** and its `FrameStepper` is what the view half drives; this row does not need it and does not touch it. |
| **Owner area** | (1) NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/anim/FilmStrip.kt` · (2) NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/anim/FilmStripTest.kt` · (3) NEW `joybrush/core/src/jvmTest/kotlin/cc/joycreator/joybrush/core/anim/FilmStripNoSecondCopyTest.kt` · **NOTHING ELSE.** In particular **NOT** `JoyBrushActivity.kt` (R30 lock order — the Lead's, and it wants JB-2.01's cluster first), **NOT** `AnimOps.kt`, **NOT** `PlaybackClock.kt`, **NOT** `FrameStepper.kt`, **NOT** anything in `joybrush-android/` or `app/`, no Gradle file, no build file. |
| **Estimated size** | ~230 lines of Kotlin, ~330 lines of tests |
| **Command** | `./gradlew -p joybrush :core:jvmTest` — `BUILD SUCCESSFUL`, 0 failures in `joybrush/core/build/test-results/jvmTest/` |

## What was CUT, and where it now lives

The Lead's review split this row because the old spec promised pure maths in `core/` **and** a view plus a
`JoyBrushActivity` panel, and R30 reserves the second for the Lead. A spec that promises both cannot be
dispatched to a free model, because the builder would have to touch a reserved file. **Everything in the
table below is gone from this file.** It is listed so the later row inherits the rulings instead of
re-arguing them, and so nobody reading this row believes the strip is finished when it is not.

| Cut | What it was | Where it now lives |
|---|---|---|
| `FilmStripView.kt` | the whole view: drawing, sprockets, scroll, the `+`/`−` cap, the long-press menu widget | the **view half of JB-3.03**, the Lead's, with the 2.01 chrome cluster. **The spec does not exist yet** — say so rather than pretend. |
| `EDIT JoyBrushActivity.kt` | hosting the strip, wiring the callbacks | same row, and R30 says it waits for **JB-2.01** anyway. |
| Sprockets (old Decision 16: 4 per frame, fixed spacing, never scaling with the hold) | drawing | the view half. The ruling is recorded in **Cut Decision C4** below and travels with the row. |
| Playhead ring / onion rails (old Decision 17) | drawing | the view half (JB-3.04 owns the ghosts). |
| `+` drawn on the cell's left cap (old Decision 18) | drawing | the view half. The *reason* it moved there — never in the edge zone — is Cut Decision C5. |
| `Action.Play` | — | **deleted outright, not moved**: R33 gives PLAY and MODE to the peg bar, so the strip must not carry a second one. The strip's only stepper is prev/next. |
| `Action.LongPressMenu` | the menu as an intent | **folded into the model operations.** R33 names the menu's four items (Blank / Link / Hold ± / Delete) and every one of them is a `AnimOps` call that this spec now provides and tests. The menu *widget* is the view half's. |
| No auto-scroll mid-gesture (old Decision 15) | scroll policy | the view half. The core half's gesture refuses to move anything on its own, which is the part that can be pinned. |
| **Strip thumbnails** | where the bitmaps come from | **JB-3.03b**, a `⚪ Outline` row on the board: *"Film-strip thumbnails via a CPU render"*, Needs 3.03. **Its spec file does not exist yet** (`specs/JB-3.03b_strip_thumbnails.md` is not on disk) — I could not check it, and the ROADMAP link is currently a dead link. Until it lands, the strip draws **numbered cells** and the host may supply bitmaps. |
| `AlertDialog` vs `SheetKit` for the long-press menu (old Q2.2) | chrome | the view half. `SheetKit` is D.02, not built. |

**This row is Ready, and it builds the strip's geometry, its gesture rules and its five document
operations. It does not put a film strip on the phone.** That is the view half, and it is not a
near-term thing: it is behind JB-2.01.

## Goal

Blueprint §1 idea 10, and specifically its change (b), which is the whole point of this row:

> **"A frame's cell width shows how long it is held, and you drag its edge to hold it longer"** —
> FlipaClip, the #1 animation app (82M installs), has refused its users this for 12 years (R6).

So the strip is not a row of equal thumbnails. **A frame's cell is `holdFrames × TICK_PX_DP × density`
wide.** A frame held 3 ticks is three times as wide as one held a tick, and dragging its right edge
changes that number and nothing else. Everything else here — the half-open hit test, the one-sided edge
grab, the scrub, the playhead re-anchoring — is in service of that one sentence.

## The seam, stated first, and it is the OPPOSITE of what the old spec claimed

The old spec opened with "every arithmetic decision is taken from `AnimOps.frameStartsMs`". **That is
false, and building to it would have produced a strip measured in milliseconds.** The strip's x axis is
**ticks**, which is the model's own unit, so a cell is an exact number of ticks wide and no millisecond
appears anywhere in this file. The seam is therefore a one-way door with a name:

> **No millisecond number may enter the strip.** A caller holding an elapsed time converts it to an
> index first — `FrameStepper.frameOnScreen(elapsedMs)` (JB-3.05a, Built) — and then maps index to cell.
> The reverse direction (a hold → a width) is `holdFrames × tickPx` and nothing else.

This matters because the two spellings are not interchangeable: `44 dp` per tick is a *screen* distance
that does not change with the board's fps, while a millisecond-proportional strip would change with it.
Decision 4 and test 2 pin the difference at 24 fps, where the two disagree by a factor of two.

The second seam is the one that bites hardest, and it is why this row still says it even though the
strip no longer touches a clock:

> **The strip MUST NOT drive itself from `PlaybackClock.nextChangeMs`.** At a backward boundary of a
> PING_PONG leg the frame change **has no minimum** — `nextChangeMs` hands back the boundary itself as
> an *infimum* (`PlaybackClock.nextChangeMs`'s own KDoc; JB-3.05a Decision 3 / Questions §4) — and a
> caller that sleeps until it and then re-asks without comparing frame indices **spins forever**. That
> is correct behaviour of the clock and a bug in the caller.

`theStripNeverConsultsThePlaybackClock` (**J1**, in `jvmTest`) is the mechanical form of that sentence and
is the reason it survives a cut that removed every line of clock code from the spec.

## Contract

### What this file calls — pasted verbatim from the landed source

`joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/AnimOps.kt`:

```kotlin
enum class NewFrame {
    /** An empty cel. Nothing has been drawn on the frame yet. */
    BLANK,

    /** A new cel holding a COPY of the source frame's cel — [CelWork.CopyCel] for the engine. */
    DUPLICATE,

    /**
     * The source frame's cel ITSELF, shared: a second frame id pointing at the same cel. No new cel
     * and no pixel work. This is how "hold" is stored, and deleting either frame keeps the cel for
     * the other.
     */
    LINK,
}

data class AnimResult(val doc: JbDocument, val work: List<CelWork>)

    fun addFrame(
        doc: JbDocument,
        boardId: String,
        afterFrameId: String?,
        mode: NewFrame,
        ids: () -> String,
    ): AnimResult

    fun deleteFrame(doc: JbDocument, boardId: String, frameId: String): AnimResult

    fun setHold(doc: JbDocument, boardId: String, frameId: String, holdFrames: Int): JbDocument

    fun frameStartsMs(board: Board): List<Double>

    fun totalDurationMs(board: Board): Double
```

`CelWork` is a sealed class with `CopyCel(layerId, fromCelId, toCelId)` and `DropCel(layerId, celId)`; a
`DUPLICATE` returns exactly one `CopyCel` per animated layer, a `BLANK` and a `LINK` return none.

**`MAX_HOLD_FRAMES = 999` and `MIN_HOLD_FRAMES = 1` are `private` in `AnimOps`** (`AnimOps.kt:100`,
`:117`). This file may not restate them, so every clamp below is done by **calling `setHold` and reading
`holdFrames` back out of the document it returns**. **J2** is the reflection that notices a second copy.

> **Correction to the old contract, and it would not have compiled.** It declared
> `addBlank(board: Board, frameId: String, ids: () -> String): AnimResult` and
> `setHold(board: Board, …)`. The landed functions take a **`JbDocument` and a `boardId`**, and
> `setHold` returns a `JbDocument`, not an `AnimResult`. A builder who wrote the old signatures would
> have had to invent a `Board` → `JbDocument` bridge in the owner area, which does not exist.

### The new file

`joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/anim/FilmStrip.kt`

```kotlin
package cc.joycreator.joybrush.core.anim

import cc.joycreator.joybrush.core.doc.AnimOps
import cc.joycreator.joybrush.core.doc.AnimResult
import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.DocException
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.NewFrame
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The film strip's geometry, its gesture rules and its five document operations, as pure maths.
 * No Android, no view, no clock, no document mutation: every operation that changes the document
 * returns a NEW document, exactly like `AnimOps` does.
 *
 * THE STRIP'S X AXIS IS TICKS, NOT MILLISECONDS. A cell is `holdFrames × tickPx` wide and that is
 * the whole geometry; no `Double` in milliseconds appears in this file, on purpose — see the
 * spec's seam section. A caller with an elapsed time converts it to an index first, through
 * `FrameStepper.frameOnScreen`, and never multiplies a duration by a pixel rate.
 *
 * The strip's state that ISN'T the document is a PLAYHEAD, which is a frame ID and never an index:
 * `playheadAfterDelete` re-anchors it by ID, and every gesture that can remove a frame says which
 * frame the playhead is on. See Decision 1.
 */
class FilmStrip(val board: Board, val density: Float = 1f) {

    /** Screen px per tick of hold. 44 dp × density (R32). */
    val tickPx: Float get() = TICK_PX_DP * density

    /** Screen px a finger-down may be from a frame's right edge and still grab it. 24 dp × density. */
    val edgeGrabPx: Float get() = EDGE_GRAB_PX_DP * density

    /**
     * Cell [frameIndex]'s left edge in STRIP coordinates, strip px from the strip's own left end.
     * Cell 0's left edge is 0. The sum of the widths before it — ticks, never ms.
     */
    fun cellLeft(frameIndex: Int): Float

    /** Cell width: `holdFrames × tickPx`, which is never less than one tick. */
    fun cellWidth(frameIndex: Int): Float

    /** Total strip width: the last cell's right edge, i.e. the sum of every cell's width. */
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
     *
     * The grab zone is `[edgeLeft - edgeGrabPx, edgeLeft]` — to the LEFT of the edge only, and
     * INCLUDING the edge itself. Frame 0's right edge is a real edge; the strip's own left end
     * (x = 0) is not, because there is no frame before it to lengthen. There is no "left edge"
     * zone at all, so the zones of two frames can never overlap and no tie-break exists.
     */
    fun edgeAt(xPx: Float): Int

    /**
     * The playhead after the frame [deletedFrameId] is deleted, given the board that results.
     * Anchored by ID: the frame that now sits where the deleted one was, or the new last frame if
     * the deleted one was the last. See Decision 1.
     */
    fun playheadAfterDelete(boardAfter: Board, deletedFrameId: String, playheadFrameId: String?): String?

    /**
     * Whether delete is available. `false` exactly when [AnimOps.deleteFrame] will refuse, which is
     * a board with one frame — so a view can disable the control instead of offering something the
     * model will throw at.
     */
    fun canDelete(): Boolean = board.frames.size > 1

    // ---- the five document operations. Each returns a NEW document; none of them mutates. ----

    /**
     * The `+` TAP, which is **DUPLICATE** (R33: what animators do most; FlipaClip is the named
     * reference). A new cel holding a copy of the pixels: one `CelWork.CopyCel` per animated layer.
     * The long-press menu's BLANK and LINK are the same call with the other two `NewFrame` values.
     */
    fun addAfter(
        doc: JbDocument,
        boardId: String,
        afterFrameId: String?,
        mode: NewFrame,
        ids: () -> String,
    ): AnimResult = AnimOps.addFrame(doc, boardId, afterFrameId, mode, ids)

    /** The `−`: `AnimOps.deleteFrame` verbatim, including its refusal in words on a one-frame board. */
    fun delete(doc: JbDocument, boardId: String, frameId: String): AnimResult =
        AnimOps.deleteFrame(doc, boardId, frameId)

    /**
     * The edge drag, COMMITTED: [dxPx] is the TOTAL screen offset since the finger went down,
     * positive right. The step is `round(dxPx / tickPx)` with ties away from zero and the new hold is
     * `holdAtDown + step`; the clamp is `AnimOps.setHold`'s and this class reads `holdFrames` back out
     * of the document that comes back. **This class holds no copy of 1 and no copy of 999.**
     */
    fun setHoldByDrag(
        doc: JbDocument,
        boardId: String,
        frameId: String,
        holdAtDown: Int,
        dxPx: Float,
    ): JbDocument

    /**
     * The long-press menu's HOLD ±1 on one entry: `setHold(hold + delta)`, read back the same way.
     * One code path with [setHoldByDrag] (Decision 13).
     */
    fun setHoldByBump(
        doc: JbDocument,
        boardId: String,
        frameId: String,
        holdAtDown: Int,
        delta: Int,
    ): JbDocument

    /** The hold of [frameId] in [doc], which is how "read it back" is spelled. */
    fun holdOf(doc: JbDocument, boardId: String, frameId: String): Int

    /**
     * The playhead one step along, for the strip's own prev/next. Clamped at the ends and **never
     * wrapped** — wrapping is playback's business and the strip is not playback (Decision 12).
     */
    fun stepPlayhead(frameId: String?, delta: Int): String?

    companion object {
        /** Screen px per tick, in DP. Density is applied at the use site (R32). */
        const val TICK_PX_DP: Float = 44f

        /**
         * How deep the edge grab zone is, in DP, to the LEFT of a frame's right edge. Density is
         * applied at the use site (R32). 24, not 12: the zone is ONE-SIDED (Decision 6), so 24 dp of
         * depth is what the old "12 dp either side of the edge" constant became — see Decision 5.
         */
        const val EDGE_GRAB_PX_DP: Float = 24f
    }
}
```

```kotlin
// same file, second half: the gesture. It is HERE, not in the view, because three of the strip's
// rules (decided once at finger-down; a second finger cancels; one document write per gesture) are
// only checkable if something other than a View owns the gesture.

/** What a finger-down has hold of, decided ONCE. See Decision 7. */
sealed class Grab {
    /** An edge: the frame whose right edge is being dragged, and its hold at finger-down. */
    data class Edge(val frameIndex: Int, val holdAtDown: Int) : Grab()

    /** Not an edge — the finger is scrubbing to whichever frame is under it. */
    object Scrub : Grab()
}

/** What a lift, or a second finger, produces. Nothing here is a view type. */
sealed class StripStep {
    /** The playhead moves. Session state, not a document: no document is produced. */
    data class PlayheadTo(val frameId: String) : StripStep()

    /**
     * The hold changed. [doc] is a NEW document; the one the gesture was given is untouched and is
     * still what the undo stack holds. There is exactly ONE of these per gesture.
     */
    data class HoldChanged(val doc: JbDocument, val frameId: String, val holdFrames: Int) : StripStep()

    /** A second finger, or a lift that changed nothing. The document is not touched at all. */
    object Nothing : StripStep()
}

/**
 * One finger's gesture on the strip. Pure: it holds the document it was GIVEN and the x it went down
 * at, and every answer is a function of those. It never mutates, never asks a clock and never
 * decides anything twice.
 */
class FilmStripGesture(
    val doc: JbDocument,
    val boardId: String,
    val density: Float = 1f,
) {

    /** A finger-down at strip coordinate [xPx]. Decides edge-grab vs scrub ONCE, for the whole gesture. */
    fun down(xPx: Float): Grab

    /**
     * The frame a move to [xPx] would playhead to, for a SCRUB. A non-finite [xPx] returns the frame
     * last published, so a pointer that has not reported a position moves nothing (Decision 6).
     */
    fun previewFrameAt(xPx: Float): Int

    /**
     * The hold a lift at [xPx] would commit, for an EDGE GRAB, WITHOUT committing it: the live
     * preview the cell width is drawn from. The document is not touched.
     */
    fun previewHoldAt(xPx: Float): Int

    /** The lift. The ONE place a gesture writes, and only for an edge grab. */
    fun up(xPx: Float): StripStep

    /** A second finger. [StripStep.Nothing], always — the gesture is over and nothing was written. */
    fun cancel(): StripStep = StripStep.Nothing
}
```

## Decisions

1. **The playhead is a frame ID, and a delete re-anchors it by ID before it re-anchors by index.**
   Deleting the frame the playhead is on moves it to **the frame now at the deleted frame's old
   index**, or to the new last frame if the deleted one WAS the last. Deleting any OTHER frame leaves
   the playhead alone, by ID, whatever the indices did. *Why:* the playhead is a frame, and an index
   is a position a delete has just renumbered. An index playhead lands one frame past the end of a
   board whenever the frame before it is deleted, and this is the exact off-by-one the project keeps
   shipping. Tests 16 and 17.
2. **The strip's x axis is ticks, and no millisecond appears in this file.** `cellWidth` is
   `holdFrames × tickPx`; `cellLeft` is the sum of the widths before it. *Why:* the cell is the hold —
   that is the premise — and a millisecond-proportional strip would make the width a function of the
   board's fps, so the same hold would be a different number of pixels on a different board. Test 2
   pins the difference at 24 fps, where the two answers are 44 and 22.
3. **Density is applied at the use site, and the constants are in DP (R32).** `TICK_PX_DP = 44f`,
   `EDGE_GRAB_PX_DP = 24f`; `tickPx` and `edgeGrabPx` multiply by the `density` the strip was built
   with. *Why:* a `const` cannot be "already × density" — density is a runtime value. The old spec
   declared `TICK_PX_BASE = 88f` as "already × density" while also calling it 44 dp, which is both
   impossible and twice the size.
4. **A frame held 999 ticks is 43 956 dp wide and the strip still lays it out.** No cap on the drawn
   width; the cap is on the hold (`AnimOps.setHold` clamps 1..999, a private number this file does not
   copy) and the strip scrolls. *Why:* clamping the drawn width would make a long-held frame look
   short, which is a lie. Test 4.
5. **The edge grab zone is 24 dp deep and ONE-SIDED — to the left of the edge, including the edge.**
   *Why:* two sides would put frame *i*'s left-edge zone on top of frame *i−1*'s right-edge zone at
   every interior boundary, and the winner would have to be decided by an arbitrary tie. One zone, no
   tie. **The 24 is the Lead's number, not R32's:** R32 lists "edge grab 12 dp", which is the old
   constant's *half* — the old spec's zone was 24 px wide and CENTRED ("12 dp either side of the
   edge's centre"), so `edgeAt(44.001f)` would have been 0. The Lead's corrected numbers say
   `edgeAt(44.001f) = −1`, which is one-sided, and `edgeAt(20f) = 0`, which is 24 deep. Both cannot be
   true of a 12 dp zone, so the review's expected values govern and the constant is 24. **RULED by the
   cross-reviewer, with the arithmetic in Q1 below: 24 dp one-sided, because it is the only shape under
   which all four of the review's pinned values are simultaneously true. Q1.**
6. **`frameAt` is HALF-OPEN, `[cellLeft(i), cellLeft(i) + cellWidth(i))`.** The exact pixel on a
   boundary belongs to the frame that STARTS there. Before the strip → frame 0; past the end → the
   last frame; a non-finite x → the last frame. *Why:* the same rule as `AnimOps.frameAt`
   (`starts[i] <= timeMs`, breaking at the first start past it) and as `PlaybackClock.slotAt`. Three
   implementations of "which frame is showing" that disagree by one frame at a boundary is exactly the
   failure this project has already paid for twice. Tests 5, 6 and 9.
7. **A non-finite coordinate is never an edge and never moves a scrub.** `edgeAt(NaN) = −1` (a NaN is
   not inside any zone), and the gesture's `previewFrameAt` returns the frame it last published rather
   than `frameAt`'s "last frame" answer. *Why:* `SpriteGridMath.cellAt` already answers −1 for a
   non-finite point and says why — "a pen that reports NaN during a lift must not select a cell". A
   pointer that has not reported a position must not move a playhead either. `frameAt` keeps the total
   "last frame" answer because it is a pure lookup with no state to protect.
8. **A gesture is EDGE or SCRUB, decided ONCE at finger-down and never revisited.** *Why:* a gesture
   that changes what it means halfway is how a scrub becomes an accidental hold change. The blueprint's
   rule for the whole app: a mode is fixed at `begin` (JB-3.08a Decision 3 is the same rule for the same
   reason). Test 20 — the most important test in the file.
9. **A second finger cancels the whole strip gesture** (Decision 11 of the old spec, kept). During a
   scrub the second finger ends the gesture with no playhead move committed; during an edge drag it
   ends it with **no** `setHold` — the document was never touched, so the drag is simply abandoned.
   *Why:* the house rule is already written down twice (JB-0.07 cancels a finger stroke; JB-2.02 hands
   the stream to the gesture machine) and the strip is no exception. A second finger is a palm or a
   pinch, and neither means "change my frame". Tests 19–20.
10. **The edge drag is measured from the finger-down and committed ONCE, on the lift.** `previewHoldAt`
    is live and writes nothing; `up` produces the single `HoldChanged` for the whole gesture.
    *Why:* a document write per move would put one entry per frame of dragging on the undo stack, and
    a person who drags to a hold and then moves one pixel back would have to undo twice to get where
    they started. The old spec's `heldAfterDrag` was a pure query with no way to commit at all, which
    is why this needed deciding. Tests 21 and 22 pin it.
11. **The new hold is computed by CALLING `AnimOps.setHold` and reading `holdFrames` back out of the
    document it returns.** `FilmStrip` contains **no copy of 999 and no copy of 1.** *Why:*
    `MAX_HOLD_FRAMES` is `private` in `AnimOps` (`AnimOps.kt:100`), so a local copy could drift and
    nothing could catch it — the same trap R19 already fixed for `SizeOpacityDrag.MAX_SIZE`. Reading
    back also means the drawn width is always the width the model will keep, even when the model
    clamped. Test 13, and **J2**.
12. **The step is `round(dxPx / tickPx)` with ties away from zero, and the new hold is
    `holdAtDown + step`.** Deliberately **not** `kotlin.math.round`, which is ties-to-even. *Why:* one
    rounding idiom in the project, not two — this is the same ruling and the same reason as
    `SpriteGridMath.roundedPx` (`SpriteGridMath.kt:321-328`, `floor(v+0.5)` / `ceil(v−0.5)`) — and a
    person dragging under a finger expects the value it looks nearest. Half a tick is 22 px at density
    1, so the tie is a real one and not a hypothetical. **`roundedPx` is `private` in
    `SpriteGridMath`, so it is a precedent and not something to call: this file spells the two lines
    out itself.** Test 11.
13. **A cell is dragged to 1 by dragging left and to the model's own 999 by dragging right, and both
    clamps are read back from the document.** The cell stops moving and the finger keeps going. *Why:*
    silent clamping is the house no; a *visible* clamp that comes from the model is the model being
    honest. Tests 12 and 13.
14. **`+` is ONE control with two answers (R33): a TAP adds a DUPLICATE frame, a LONG-PRESS opens a
    menu of Blank / Link / Hold ± / Delete.** *Why:* R33 rules it, and the reason is in R33: duplicate
    is what animators do most and FlipaClip — the app the blueprint names — is the reference. The old
    spec had tap = blank on the reasoning that "blank cannot be wrong"; that is overruled. Every one of
    the four menu items is a model call this spec now provides, so the menu is four tested operations
    and no new code. **The phone check R33 attaches: if the owner finds duplicate surprising, it is one
    constant** (`NewFrame.DUPLICATE` in `addAfter`'s one call site). Tests 14 and 15.
15. **Delete is `jb_state_destroy` filled and is REFUSED IN WORDS when the board has one frame.**
    `AnimOps.deleteFrame` throws `DocException` for exactly that, so `canDelete()` is false in exactly
    the same case and the view can disable the control — visibly, not silently. *Why:* refuse in words,
    do not clamp; and a control that is visibly dead teaches, while one that is silently dead is a bug
    report. Test 23.
16. **The strip's only stepper is prev/next, and it does not wrap (R33).** The peg bar owns PLAY and
    MODE, so there is one saturated control, not two. `Action.Play` is deleted from this spec's
    vocabulary and is not in any form in the core half. *Why:* two play buttons on one screen is the
    thing R33 exists to prevent. Test 24.

### Cut Decisions — these rulings travel to the view half and must not be re-argued

- **C1 (old D15).** The strip scrolls horizontally and the playhead is kept visible by the **host**
  after every change, never by the strip mid-gesture. *Why:* auto-scrolling during a drag moves the
  pixels under a finger, and the drag is the one gesture in this app that must be perfectly still.
- **C2 (old D17).** The playhead wears `jb_state_live` as a 2.5 dp ring; onion ghosts are JB-3.04's
  and the strip only reserves the rails for their frame indices. *Why:* a coloured ring already means a
  state (D.01).
- **C3 (R33).** **Strip thumbnails are JB-3.03b, not this row.** This row's strip shows **numbered
  cells** and the host may supply bitmaps. *Why:* a GL read path means touching the engine R14 just
  stabilised, and a CPU render is a memory-budget decision on a phone. Both are the Lead's, both are
  questions about a phone, and neither is this builder's.
- **C4 (old D16).** Sprockets are drawn in the top and bottom rails, 4 per cell at a fixed spacing, and
  they do **not** scale with the cell. *Why:* real film has four perforations per frame however long
  the frame is held — a held frame is the same frame held longer, not more frames — and a decorative
  scaling would be actively misleading about the timing.
- **C5 (old D18).** The `+` cap is drawn at each cell's LEFT cap, not its right. *Why:* the edge is the
  special gesture on this strip; nothing else may live where the edge is, or it can be mistaken for one.

## Decision → Test map (every Decision is checkable)

| Decision | Pinned by |
|---|---|
| 1 (playhead is an ID) | 16, 17 |
| 2 (ticks, not ms) | 1, 2, 25 |
| 3 (dp × density) | 3, 7 |
| 4 (999 draws) | 4 |
| 5 (one-sided, 24 deep) | 7, 8 |
| 6 (half-open) | 5, 6, 9 |
| 7 (non-finite moves nothing) | 6, 10 |
| 8 (decided at down) | 20 |
| 9 (second finger cancels) | 18, 19 |
| 10 (one write, on the lift) | 21, 22 |
| 11 (no copy of the clamp) | 13, **J2** |
| 12 (ties away from zero) | 11 |
| 13 (visible clamps) | 12, 13 |
| 14 (`+` tap = duplicate) | 14, 15 |
| 15 (delete refused in words) | 23 |
| 16 (prev/next, no wrap, no play) | 24 |
| — (the seam: no clock, ever) | **J1** |

## Tests

### `commonTest` — `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/anim/FilmStripTest.kt`

**Fixture** (copied from `AnimOpsTest.kt:55-74`, which is the fixture `PlaybackClockTest` and the rest
of the row use — same shape, one ANIMATION board `b-anim` at 12 fps, one PAINT layer `l-anim` animated
in it, every frame showing cel `c-anim`):

```kotlin
private fun fixture(frames: List<Frame>): JbDocument   // exactly AnimOpsTest's, with these frames
private fun JbDocument.board(id: String = "b-anim"): Board = boards.first { it.id == id }
private fun JbDocument.frame(id: String): Frame = board().frames.first { it.id == id }
```

The board under test is **holds `[1, 2, 1, 3]`, frame ids `f0..f3`, 12 fps**, at **density 1** unless a
test says otherwise. Those seven numbers are the whole of test 1 and every other test reads them off it.

**How a test reads a hold that an operation changed — one rule, and it is not optional.**
`FilmStrip` is built from a `Board` it was GIVEN and it never re-reads anything, so the geometry of a
changed hold is a property of a **new strip built from the returned document**:

```kotlin
/** A strip rebuilt on the document the drag RETURNED — the only strip that has the new hold. */
private fun draggedStrip(doc: JbDocument, frameId: String, dxPx: Float): FilmStrip {
    val strip = FilmStrip(doc.board("b-anim"))
    val after = strip.setHoldByDrag(doc, "b-anim", frameId, strip.holdOf(doc, "b-anim", frameId), dxPx)
    return FilmStrip(after.board("b-anim"))
}
```

Spelled out, because test 12 and test 4 both depend on it: call `setHoldByDrag` / `setHoldByBump`,
take the returned `JbDocument`, read `board()` out of **it**, and build a second `FilmStrip` from that
board before asking it for a width. Asserting `cellWidth` on the ORIGINAL strip after a drag would read
the pre-drag hold (f1 is 88 px wide, not 44) and would pass while proving nothing.

**Every expected value below is DERIVED and the derivation is in the test's own comment.** A test that
cannot say where its number came from is a test that gets "fixed" to match whatever the code did.

1. `aCellIsAsWideAsItsHold` — density 1, so `tickPx` = 44 × 1 = 44. `holdFrames × 44`:
   f0 = **44**, f1 = **88**, f2 = **44**, f3 = **132**. `cellLeft` = **0, 44, 132, 176** (0; 0+44;
   44+88; 132+44). `stripWidth()` = **308**, asserted as the sum AND as `cellLeft(3) + cellWidth(3)`.
2. `aCellIsNotSizedByWallTime` — the same board at **24 fps** instead of 12. The fps has no effect on
   the strip: `cellWidth(3)` is still **132**, because a tick is 44 dp and not a duration. The comment
   names the other answer: frame 3 is 250 ms at 12 fps and 125 ms at 24 fps, so a millisecond-proportional
   strip would have given 264 and 132, and 132 is the number that must NOT be a coincidence of 12 fps.
3. `aOneTickFrameIsTheTouchFloorAtEveryDensity` — density 1 → 44; density 2.5 → **110**; density 3 →
   **132** (the Lead's number: 44 × 3). Three densities, because "44 dp" written once as a pixel is the
   slip R32 exists to stop.
4. `aNineHundredAndNinetyNineTickFrameStillDrawsItsWholeWidth` — `setHold(999)` through `AnimOps`
   first, build the strip on the document it **returns** (the reading rule above), then `cellWidth` =
   999 × 44 = **43 956** at density 1 and 999 × 132 = **131 868** at density 3.
   No cap, no exception, no `Float` overflow. **The old spec's Decision 4 said 43 956 and its test 3
   said 88 912 — the two contradicted each other inside one file, and 43 956 is the one the arithmetic
   supports.**
5. `thePixelOnABoundaryBelongsToTheFrameThatStartsThere` — the Lead's four, then the fourth boundary
   nobody had looked at: `frameAt(44f) = 1`, `frameAt(43.999f) = 0`, `frameAt(131.999f) = 1`,
   `frameAt(132f) = 2`, `frameAt(0f) = 0`, `frameAt(175.999f) = 2`, `frameAt(176f) = 3`. Every boundary
   from both sides, because a boundary tested from one side only is a boundary that can be off by one
   in the direction nobody looked.
6. `beforeTheStripIsFrameZeroPastTheEndIsTheLastFrameAndANaNIsTheLastFrame` —
   `frameAt(-1f) = 0`, `frameAt(1e9f) = 3`, `frameAt(Float.NaN) = 3`, `frameAt(Float.POSITIVE_INFINITY) = 3`.
7. `anEdgeIsGrabbedFromItsLeftOnly` — `edgeGrabPx` = 24, so the four zones are `[20,44]`, `[108,132]`,
   `[152,176]` and `[284,308]`: `edgeAt(44f) = 0` (the edge is inside its own zone),
   `edgeAt(20f) = 0` (24 dp to the left), `edgeAt(19.999f) = −1` (one thousandth further),
   `edgeAt(44.001f) = **−1**` (one thousandth to the RIGHT — this is the value that proves the zone is
   one-sided, and the old centred zone would have said 0), `edgeAt(0f) = −1` (the strip's left end is
   not a frame's right edge), and `edgeAt(307.999f) = **3**` — f3's right edge is at 308 and its zone
   is `[284,308]`, so a point inside that zone belongs to **frame 3**, the frame whose edge it is. The
   draft wrote `2` here, which is a different frame's zone and would have failed against the contract
   directly above it. **Assert all four numbers of the Lead's corrected list — 44 → 0, 20 → 0,
   44.001 → −1, 0 → −1 — in that order, because `44.001` is the one that fails if the zone is the
   wrong shape, and then assert `307.999 → 3`, which is the one that fails if the zone is attributed
   to the wrong frame.**
8. `thereIsNoLeftEdgeZoneSoTwoZonesCannotOverlap` — 2000 probes spread over the whole strip at
   density 1: every x yields exactly one answer, and for each of the three interior boundaries 44, 132
   and 176 the three probes `b − 0.001`, `b` and `b + 0.001` answer **`i`, `i`, `−1`** — where `i` is
   the index of the frame **whose right edge that boundary is** (0 at 44, 1 at 132, 2 at 176). *Why that
   triple and not `i−1, i, −1`:* the zone runs `[b − 24, b]`, so `b` itself is inside frame `i`'s own
   zone and `b − 0.001` is too; the first `−1` can only appear on the far side of `b`, and that is
   precisely the disjointness being claimed. A test that expects a different number here is asking for
   the zone of the frame that starts at `b`, which this contract does not have.
9. `theStripIsHalfOpenInExactlyTheSameWayTheModelIs` — for a range of probes, the frame the strip says
   is under x is the frame whose cell STARTS there: assert `strip.frameAt(cellLeft(i)) == i` for every
   i, which is the property a hit test is judged by (`SpriteGridMath.cellAt`'s own KDoc states the same
   property for cells, as `cellAt(cellRect(i).topLeft) == i`).
10. `aNonFiniteCoordinateIsNeitherAnEdgeNorAMove` — `edgeAt(NaN) = −1`, `edgeAt(−Inf) = −1`,
     `edgeAt(+Inf) = −1`; and, for the scrub half, **`down(0f)` first** — x = 0 is in no edge zone, so
     the grab is `Scrub` and the gesture's `lastPublished` is `frameAt(0f) = 0`. Then
     `previewFrameAt(NaN) = 0` and `previewFrameAt(+Inf) = 0`: the frame it last published, **not**
     `frameAt`'s answer, which for a non-finite x is the LAST frame (3) and is what a
     `previewFrameAt = frameAt` shortcut would return. **The gesture therefore has to go down somewhere
     other than the last frame, or this half of the test cannot fail** — a gesture that started on
     frame 3 would agree with both answers. Also assert `previewFrameAt(44f) = 1`, because a FINITE
     coordinate is a real position and a scrub preview must follow it: that is the other half of
     Decision 7, and without it a builder could satisfy this test with a preview that never moves.
     Nothing moves on a pointer that has not reported a position.

11. `theDragStepRoundsTiesAwayFromZero` — at density 1 the half tick is 44 / 2 = **22 px**, so a drag of
    exactly **+22** adds **+1** and **−22** subtracts **1**. `kotlin.math.round` would give 0 for both
    (ties-to-even) and the test says so in its comment, because that is the difference between this test
    and a tautology. Both directions, because "ties away from zero" is a claim about the negative side
    too.
12. `draggingLeftStopsAtOneTickAndTheCellStopsShrinking` — f1 (hold 2) dragged **−1000 px** → hold 1,
    and the same gesture dragged **−2000 px** → still 1. `step = round(1000/44) = 23` and
    `round(2000/44) = 45`, so the requested holds are `2 − 23 = −21` and `2 − 45 = −43` and both are
    clamped up to 1 by the model; the cell width read off a strip rebuilt from the returned document
    is **44** in both cases. (On the ORIGINAL strip it is still 88, which is the reading rule above.)
13. `draggingRightStopsAtTheModelsOwnLimit` — f0 (hold 1) dragged **+100 000 px**: `step =
    round(100000/44) = round(2272.7) = 2273`, so the strip asks for `1 + 2273 = 2274` and the answer
    read back out of the document is the model's own **999**. **The draft used +10 000 px, which never
    reaches the limit at all:** that is a step of 227 and a hold of 228, so it would have asserted 999
    against a code that was asked for 228 — the test would have failed, and "fixing" it to 228 would
    have quietly deleted the claim the test exists to make. **A drag only reaches the clamp once
    `holdAtDown + step > 999`, i.e. past about 43 900 px at density 1.** Assert it AGAINST `AnimOps`,
    not against a literal in this file: build `expected = AnimOps.setHold(doc, "b-anim", "f0", 12_345)`
    and assert the strip's read-back equals `expected`'s `holdFrames`. If `MAX_HOLD_FRAMES` ever moves,
    this test follows it and **J2** is the one that notices a second copy appearing.
14. `theAddButtonDuplicatesTheFrameUnderIt` — the `+` tap: one more frame, one more `CelWork.CopyCel`
    per animated layer, a NEW cel id, and `DocOps.validate(after.doc)` **empty** (assert the shape, then
    the validation, in that order — a test that only checks the shape passes just as happily on a
    broken document).
15. `theMenuIsFourModelOperationsAndNothingElse` — BLANK: +1 frame, +1 cel, **no** `CelWork`; LINK: +1
    frame, **no** new cel, no `CelWork`, and the layer's `frameCel` maps the new frame to the SAME cel
    id as its source; HOLD ±: one code path (a `setHoldByBump(+1)` and a `setHoldByDrag(+tickPx)` from
    the same hold produce `==` documents); DELETE: the frame is gone and so is the cel only it showed.
    `DocOps.validate` clean after each.
16. `deletingTheFrameUnderThePlayheadMovesItToTheFrameThatReplacedIt` — 4 frames, playhead on `f1`;
    `AnimOps.deleteFrame` then `playheadAfterDelete(after, "f1", "f1")` = **"f2"** (the frame now at
    index 1), **not** `f3`.
17. `deletingTheLastFrameLeavesThePlayheadAloneAndSoDoesDeletingAnyOtherFrame` — playhead on `f2`,
    delete `f3` → still `f2`; playhead on `f3`, delete `f0` → `f3`. The test also records the **index**
    the playhead's frame now sits at (**2**), because that number is the whole thing the ID rule
    exists to stop depending on: an index playhead pinned at 3 is out of range on a three-frame board
    after this delete, and a clamp to 2 happens to name `f3` too — so this case records the hazard
    rather than separating the two behaviours. **Test 16 is the case that separates them**, and it is
    the one the decision cites.
18. `aSecondFingerEndsAScrubWithNothingCommitted` — `down(10)`, `previewFrameAt(44) = 1`, then
    `cancel()`, then `previewFrameAt(176)` is still **answered** (3) and `up(176)` is `StripStep.Nothing`,
    and the document the gesture holds is `===` the one it was given. `cancel()` is terminal, so the
    `up` after it is a no-op rather than a late playhead move.
19. `aSecondFingerEndsAnEdgeDragWithNoHoldChange` — `down(44)` is `Grab.Edge(0, holdAtDown = 1)`, move
    to 200 (`previewHoldAt(200)` = 1 + round(156/44) = 5), `cancel()`, `up(200)` is `StripStep.Nothing`,
    and the document is `===` the one at finger-down, field for field. The preview was 5 and the
    document still says 1, which is the whole claim.
20. `aDragThatCrossesAnEdgeMidGestureDoesNotBecomeAnEdgeGrab` — `down(200)`, which is in **no** zone
    (the zones are [20,44], [108,132], [152,176] and [284,308]), so the grab is `Scrub`. Move to
    **120**, which IS inside frame 1's zone, and `up(120)` is `PlayheadTo("f1")` and **no
    `HoldChanged`**. This is Decision 8 and the most important test in the file.
21. `theGestureWritesTheDocumentOnceAndOnlyOnTheLift` — an edge grab (`down(44)`) with FIVE moves
    (50, 100, 90, 130, 194) then `up(194)`: exactly one `HoldChanged`, its `doc` is `==` to
    `AnimOps.setHold(docAtDown, "b-anim", "f0", 4)`, and its `holdFrames` is 4. The COUNT is the point:
    every one of the five `previewHoldAt` calls is answered and writes nothing.
22. `aDragIsMeasuredFromTheFingerDownAndNotFromTheLastMove` — `down(44)`, moves to 50, 100, 150 and 194,
    `up(194)`: the offset is 194 − 44 = **150**, the step is `round(150/44)` = `floor(3.409 + 0.5)` = **3**,
    and the hold is `1 + 3` = **4**. The comment names both wrong answers with their own arithmetic,
    because those are the two bugs this test exists to catch:
    - **Summing every move's own offset from the finger-down**: `6 + 56 + 46 + 86 + 150` = 344 px →
      `round(344/44)` = 8 → a hold of **9**.
    - **Measuring from the last move only**: `194 − 130` = 64 px → `round(64/44)` = 1 → a hold of **2**.
    Neither is 4. (The draft's comment offered "summing the moves gives 10", which is not any of these
    — the deltas telescope to 150 and their absolute values sum to 344. A wrong derivation in a test
    comment is how the next reader "fixes" the right number to the wrong reason.)
23. `theDeleteButtonIsDeadOnAOneFrameBoardAndTheModelAlsoRefuses` — on a one-frame board `canDelete()`
    is `false` **and** `assertFailsWith<DocException> { strip.delete(doc, "b-anim", "f0") }`. Both
    halves, because a control that is offered when the model will throw is a bug report. On a two-frame
    board both are true the other way.
24. `aStepOfThePlayheadStopsAtTheEndsAndNeverWraps` — `stepPlayhead("f0", −1) = "f0"`,
    `stepPlayhead("f3", +1) = "f3"`, `stepPlayhead("f1", +1) = "f2"`, and `stepPlayhead(null, +1) = "f0"`.
    Wrapping would give `f3` for the first and is asserted not to.
25. `theCellWidthIsUnchangedByEveryOtherBoardProperty` — the same frames at fps 6, 12, 24 and 60 and
    every cell width is identical; and the strip's geometry is unaffected by the board's rect, the
    number of layers and the fps. *Why:* the strip is a function of `frames` and `density` and nothing
    else, and a test that says so is what stops somebody adding an fps term "for accuracy".

### `jvmTest` — `joybrush/core/src/jvmTest/kotlin/cc/joycreator/joybrush/core/anim/FilmStripNoSecondCopyTest.kt`

**These two are here and not in `commonTest` because they use Java reflection** (`::class.java`),
which is not available in a multiplatform `commonTest` source set. The same slip put source-level tests
in `commonTest` in JB-1.07, JB-2.04, JB-2.12, JB-2.16, JB-2.17, JB-2.23 and JB-3.04; it is the Lead's
slip 3 and it is a build failure, not a style note.

**J1.** `theStripNeverConsultsThePlaybackClock` — `FilmStrip::class.java.declaredMethods` and
`declaredFields`, and the same for `FilmStripGesture`, contain no reference to `PlaybackClock` or to
`nextChangeMs`; and neither file declares an import of it. This is the mechanical form of the seam, and
it is the test that would catch somebody "optimising" the scrub through the clock later.

**J2.** `theStripHasNoCopyOfTheHoldClamp` — `FilmStrip.Companion.declaredFields` holds exactly two
constants, `TICK_PX_DP` and `EDGE_GRAB_PX_DP`, both `Float`, both **44f and 24f**. If a builder adds
`MAX_HOLD = 999` this goes red, which is the entire point (R19's lesson about `MAX_SIZE_PX`).

**Non-vacuity the builder must run and paste:** (1) change `edgeAt`'s zone to be centred and watch
**test 7** fail on `edgeAt(44.001f)`; (1b) attribute each zone to the frame that **starts** at the
boundary instead of the frame whose edge it is, and watch **test 7** fail on `edgeAt(307.999f)` (3, not
2); (2) change the step to `kotlin.math.round` and watch **test 11** fail; (3) drop the read-back in
`setHoldByDrag` and return `holdAtDown + step` directly, and watch **test 13** fail with **2274**
where the model's answer is 999; (4) make `up` commit on every move and watch **test 21** fail on the
count of `HoldChanged`; (5) make `down` re-decide the grab on every move and watch **test 20** fail; (6)
build the post-drag width from the strip's OWN board instead of from a strip rebuilt on the returned
document, and watch **test 12** fail with 88 where it says 44. A helper that has never been seen wrong
is a helper nobody can rely on.

**Command:** `./gradlew -p joybrush :core:jvmTest` — `BUILD SUCCESSFUL`, 0 failures.

## Do not

- **Do not call `PlaybackClock` from this file, or let any caller reach it through one.** The seam, and
  **J1** is the test. Playback is JB-3.05 and it is Built.
- Do not add a `MAX_HOLD_FRAMES`, a `MIN_HOLD_FRAMES`, or any other copy of `AnimOps`' private numbers.
  Call the model, read the answer back.
- **Do not multiply a duration by a pixel rate.** No millisecond appears in this file; the strip is in
  ticks (Decision 2).
- Do not re-implement `AnimOps.addFrame` / `deleteFrame` / `setHold` with a `Board` argument. They take
  a `JbDocument` and a `boardId`; that is the landed signature and the contract above is pasted from it.
- Do not put PLAY or MODE anywhere in this file. The peg bar owns them (R33), and `Action.Play` is
  deleted, not deferred.
- Do not add a second way to change a hold. The edge drag and the menu's `Hold ±` are one function
  (Decision 13 of the old spec, kept).
- Do not change what a gesture means halfway, and do not write a second time. One decision at `down`,
  one write on lift.
- Do not ask the strip's geometry about a hold that has changed. `FilmStrip` reads the `Board` it was
  constructed with and nothing else; a changed hold lives in the **returned** document, so a test —
  and a view — rebuilds the strip on that document. Reading a width off the old strip is a stale
  number that looks right.
- Do not touch `AnimOps.kt`, `PlaybackClock.kt` or `FrameStepper.kt`. They are called, never changed.
- Do not create `FilmStripView.kt` or touch `JoyBrushActivity.kt`. They are the view half and the Lead's.
- No hex literals anywhere. `JbColors.palette(context)` only — and that is a view-half rule anyway.

## Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest` output pasted, 0 failures
- [ ] the six non-vacuity runs pasted, each with the name of the test that went red
- [ ] `git status --short` shows **only** the three owner-area paths
- [ ] committed `JB-3.03: film strip core`. **The ROADMAP row is the orchestrator's, not yours — do not
      edit `tasks/joybrush/ROADMAP.md`.** `specs/INDEX.md` was retired ("# Moved"), so there is no
      index line to update; report the new status in the build report and the orchestrator sets it.

## Stop rule

**Stop, set the row `⛔ Blocked`, and write the question in this file's Questions section — do not guess
and do not widen the owner area — if any of these happen:**

1. A signature in the "Contract" section above does not match the landed file it is pasted from
   (`AnimOps.kt`, `AnimOps.addFrame`, `AnimOps.setHold`, `NewFrame`, `AnimResult`). The contract is
   authoritative over this spec and this spec is wrong.
2. A test's expected value cannot be derived from the numbers in this spec. That means a design
   decision is missing, and a builder must not invent one.
3. Making something pass would need an edit to `AnimOps.kt`, `PlaybackClock.kt`, `FrameStepper.kt`,
   `DocModel.kt` or any file outside the three owner-area paths — **especially** `JoyBrushActivity.kt`,
   which R30 reserves.
4. `DocOps.validate` is unhappy with a document one of the five operations returned, and the cause is
   in `AnimOps` rather than in the call. Report it; do not repair it.
5. Anything here turns out to need an Android type, a `View`, a density read, or a file. That means the
   scope is wrong, and the answer is a question, not an import.

## Questions

_(Spec writer: openrouter/stealth/space-bunny-alpha, 2026-09-29. The core half is decided and pinned by
25 `commonTest` cases and 2 `jvmTest` ones, plus five non-vacuity runs. **No question below blocks the
build.** The old Q1 and Q2 are answered by R33 and restated above; the old Q3 is answered by the fact
that `FrameStepper` is Built.)_

### Q1 — RULED by the cross-reviewer (2026-09-29): `EDGE_GRAB_PX_DP = 24f`, one-sided.

**Ruling: the constant in this spec is 24 dp, one-sided, and every test in this file derives from it.**
Marked for the Lead's confirmation, but the build is not blocked on it, because the review's own
expected values settle it in one direction only. The arithmetic, run against R32's 12 dp:

| zone | `edgeAt(20f)` | `edgeAt(44f)` | `edgeAt(44.001f)` | `edgeAt(0f)` | fits the review? |
|---|---|---|---|---|---|
| **12 dp one-sided**, `[32,44]` | −1 | 0 | −1 | −1 | **no** — one of the four is wrong |
| **12 dp two-sided**, `[32,56]` | −1 | 0 | **0** | −1 | **no** — two of the four are wrong |
| **24 dp one-sided**, `[20,44]` | 0 | 0 | −1 | −1 | **yes — all four** |

Only the third shape satisfies the corrected-numbers section, which my brief names as the authority on
every expected value, and `edgeAt(20f) = 0` is the pin that kills both 12 dp readings: 20 is 24 px from
the edge. So 24 is not a preference I am making; it is the only depth under which the authority's own
four numbers are simultaneously true, and R32's "edge grab 12 dp" is the *half* of a 24 px zone that
the old, **centred** zone used ("12 dp either side of the edge's centre") — a different shape from the
one-sided zone Decision 5 rules.

**What the Lead changes if this ruling is wrong, and nothing else:** `EDGE_GRAB_PX_DP` alone, and then
every derived number in tests 7 and 8 and the four zones named in test 7. At 12 dp one-sided the zones
become `[32,44]`, `[120,132]`, `[164,176]`, `[296,308]` and test 7's `edgeAt(20f)` becomes −1 and
`edgeAt(19.999f)` becomes −1 too. There is no other decision in this spec that depends on it, which is
why a builder who gets it wrong fails loudly rather than quietly.

### Q2 — for the Lead: JB-3.03b has no spec, and the ROADMAP link to it is dead.

R33 puts the strip's thumbnails in **JB-3.03b**. That row is on the board as `⚪ Outline` and points at
`specs/JB-3.03b_strip_thumbnails.md`, **which does not exist on disk** — I checked. So today the strip
shows numbered cells (Decision C3) and nobody can build the thumbnail row from a spec. **Recommend the
Lead either open it or delete the link.** Until then this row does not depend on it and does not wait
for it.

### Q3 — for the Lead, a warning rather than a question, and it is the same one as the old Q3.

**The view half must not drive the playhead marker from `nextChangeMs`.** The core half cannot spin —
it never asks the clock — but the view half that draws a playhead marker along this strip is where the
trap is, and `FrameStepper.nextWakeMs` (`FrameStepper.kt:109-120`) is the answer that already landed:
it is strictly greater than the elapsed, including at a backward boundary where the clock hands back
`nextChangeMs(t) == t` bit for bit. Write that into the view row's contract when it is written.

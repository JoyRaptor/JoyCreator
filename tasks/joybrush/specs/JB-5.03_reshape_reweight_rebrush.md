# JB-5.03 — Reshape, re-weight and re-brush a stroke you have already drawn

| | |
|---|---|
| **Tier** | T2 |
| **Status** | see ROADMAP.md |
| **Needs** | JB-5.01 (`InkReplay`, `InkRaster` — the ink layer this edits), JB-5.03a (`StrokeEdit`, `StrokeRecord` v2), JB-5.02 (`StrokePicker`) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/vector/InkEditSession.kt`, NEW `.../commonTest/.../vector/InkEditSessionTest.kt`. **Nothing else.** |
| **Estimated size** | ~230 lines + ~230 lines of tests |
| **Command** | `./gradlew -p joybrush :core:jvmTest` — 0 failures |

## Goal

R20, verbatim: *"Every vector brush is swappable after drawing (owner; 'as in Concepts'). On an INK
layer a stroke is its recording, so selecting one or MANY strokes and picking another pen redraws them
as if drawn with that pen: pencil → fill pen gives the solid shape (ends joined), fill pen → pencil
gives the outline stroke."*

JB-5.03a built the three pure edits (`StrokeEdit.reshape` / `reweight` / `rebrush`, plus `recolor`).
This spec is the **session** that turns a finger into them: what is selected, what a drag does, what one
undo step covers, and what happens when the brush you picked cannot draw an ink line.

It is a pure state machine in core with no Android in it, so every rule below is a test rather than a
manual check. The view wiring is out of scope (Q4).

## Contract (verbatim)

```kotlin
package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.stroke.StrokeEdit
import cc.joycreator.joybrush.core.stroke.StrokeRecord

/** How far a reshape grab reaches, in SCREEN px, converted once to doc px by the caller. */
const val RESHAPE_GRAB_SCREEN_PX = 24.0

/**
 * One finished edit, as the undo stack holds it: every record this gesture replaced, and what it was
 * before. [before] is empty for a deletion; a restored stroke comes back with the id it had.
 */
data class InkEditStep(val celId: String, val before: Map<String, StrokeRecord>)

sealed interface InkEditResult {
    /** Nothing changed and nothing is wrong: the drag was inside the slop, or the gesture was cancelled. */
    data object NoChange : InkEditResult
    /** Records changed, one undo step. */
    data class Changed(val celId: String, val records: Map<String, StrokeRecord>) : InkEditResult
    /** Refused, with a sentence a screen can show. Nothing changed. */
    data class Refused(val reason: String) : InkEditResult
}

class InkEditSession(
    /** Every record in the cel, keyed by id, in drawing order (last drawn = on top). */
    initial: List<StrokeRecord>,
    val celId: String,
) {
    /** What is selected now, in drawing order. Empty = nothing. */
    val selection: List<String>

    /** The lines the picker sees. One [InkLine] per record, built from its replayed dabs. */
    fun lines(brushOf: (StrokeRecord) -> BrushPreset?): List<InkLine>

    /** A tap: pick the line under (x, y) doc px and select it alone (Decision 2). */
    fun tap(x: Double, y: Double, timeMs: Double, screenPerDoc: Double, brushOf: (StrokeRecord) -> BrushPreset?): InkEditResult

    /** A rubber band around (x0,y0)-(x1,y1) doc px: every line it touches joins the selection. */
    fun band(x0: Double, y0: Double, x1: Double, y1: Double, brushOf: (StrokeRecord) -> BrushPreset?): InkEditResult

    /** Drag begins: remembers the pre-drag records of everything selected. A second call restarts it. */
    fun beginDrag(): InkEditResult

    /** Drag moves by (dx, dy) doc px. Reshapes the grab and every other selected line, the same way. */
    fun drag(x: Double, y: Double, dx: Double, dy: Float): InkEditResult

    /** Drag ends: commits ONE undo step. Returns [NoChange] when nothing moved. */
    fun endDrag(): InkEditResult

    /** The finger or pen went down a second time: the whole drag is undone and the session forgets it. */
    fun cancelDrag(): InkEditResult

    /** Multiply every selected line's `widthScale` by [factor]. One undo step. */
    fun reweight(factor: Float): InkEditResult

    /** Re-draw every selected line with [brush]. ONE undo step for all of them (R20). */
    fun rebrush(brush: BrushPreset): InkEditResult

    /** Recolour every selected line. One undo step. */
    fun recolor(argb: Int): InkEditResult

    /** Empty the selection. Never a change to the drawing, so never an undo step. */
    fun clearSelection()

    /** The undo stack, oldest first. */
    val undo: List<InkEditStep>
}
```

## Decisions

1. **The session owns the records and nothing else.** It holds a `Map<String, StrokeRecord>` and
   applies `StrokeEdit`'s four pure functions. It does not replay dabs, does not raster, does not know
   about `GlPaintEngine` or undo-in-tiles. `JB-0.01`'s finding established that the engine's undo
   stack holds tile changes and **never a `StrokeRecord`**, so an ink edit needs its own undo and this
   is where it lives — as `InkEditStep`, which is records and nothing else.
2. **A tap selects one line; a band selects many.** `tap` delegates to `StrokePicker.pick`, which
   already implements the cluster/cycle rules, and the result **replaces** the selection. `band`
   adds. Two different gestures because Concepts' are two different gestures, and because a tap that
   added to a selection could never take anything away again.
3. **The eraser of ink is not this session's job.** JB-5.11 deletes lines. This session's delete is
   only ever an *edit* of a line — there is no "delete" verb, so there is no destructive gesture to
   get wrong (blueprint §3.5: "No destructive gestures").
4. **A reshape drag moves every selected line, about the grab.** `drag` calls
   `StrokeEdit.reshape(record, grabX, grabY, dx, dy, radius)` on **every** selected record. Each
   reshapes about the same grab point, so a selection of ten lines follows the finger together. This is
   what "selecting many strokes and dragging" has to mean; the alternative (each line grabbing its own
   nearest sample) makes a selection move in ten directions at once and is not a decision I am willing
   to make quietly, so it is **Q2**.
5. **The grab radius is 24 SCREEN px, divided by the zoom once.** `StrokeEdit.reshape` takes doc px,
   so the session receives `radius = (RESHAPE_GRAB_SCREEN_PX / screenPerDoc).toFloat()` at
   `beginDrag` and holds it. Screen px for the same reason `StrokePicker` uses them (its own file
   note): 24 px is a finger, not a document fact, and it must be the same finger-feel at every zoom.
6. **A whole drag is ONE undo step, committed at `endDrag`.** `beginDrag` snapshots, `drag` mutates,
   `endDrag` pushes one `InkEditStep` — or `NoChange` if the snapshot equals the result. Live
   re-rendering during the drag is the caller's business; the session is told nothing about frames.
7. **A second finger cancels the whole drag, restoring the snapshot exactly.** Not "the last frame" —
   the records. `cancelDrag` puts `beginDrag`'s snapshot back and forgets it, so `endDrag` afterwards
   pushes nothing. This is the same cancel the drawing view already does for a finger stroke, and it
   is the only way to leave a reshape.
8. **Re-weight is multiplicative and clamped by `StrokeEdit`.** `reweight(factor)` passes the factor
   straight through; `StrokeEdit.reweight` clamps `widthScale` to 0.05..20 and leaves a non-finite
   factor alone. **Non-finite factor → `Refused`**, so the session never reports a change it did not
   make. One step for the whole selection.
9. **Re-brush is ONE step for the whole selection** (R20 says so in as many words) and it is refused
   **in words** when the brush cannot draw an ink line. `stamp` and `fill` go through; `smudge` and
   `wet` return `Refused` and **change nothing** — including not changing the selection. The message
   names the brush and the reason: *"Smudge reads the pixels under the pen, and an ink layer has none —
   it holds the strokes. Try Ink, Pencil or the Fill pen."* A refused brush must leave the drawing
   byte-identical, which is a test.
10. **The seed survives every edit.** `StrokeEdit.rebrush` already keeps it, so the scatter of a
    re-brushed line lands in the same places if the new brush scatters the same way. The session adds
    nothing and must not re-seed anything. `reweight`/`reshape` likewise.
11. **A band is a rectangle in DOC px** — axis aligned, corners given by the caller in whatever order
    the finger drew them, so `(x0,y0)-(x1,y1)` is normalised inside. A line joins the selection when
    **any of its points** is inside the rectangle, tested on the points `InkReplay` produces, not on
    its bounding box (a bounding box would catch a line that passes near the band but nowhere near it).
12. **`lines()` builds an `InkLine` per record from `InkReplay.dabs`, and a record that cannot be
    replayed is simply not a line.** A missing brush or a `smudge` brush means the stroke cannot be
    picked, moved or re-brushed — because there is nothing to redraw it with. It is not an exception
    here; `refusal()` in `InkReplay` is how the UI says why, on a different screen.
13. **`undo` is a bounded log, newest last, and nothing else in this file grows.** Cap: **50 steps**;
    dropping the oldest. 50 is a guess with a reason (a session is minutes long, and the records of a
    whole ink layer are small) and it is stated so it can be changed in one line.

## Steps

1. Tests first, from Decisions 1–13.
2. `InkEditSession.kt`.

## Tests

**`InkEditSessionTest`** (a 4-stroke fixture: two 10-sample horizontal lines at y=10 and y=50, two
vertical at x=10 and x=200, ids `a`…`d`, drawn in that order, all `stamp`)

1. **Tap selects one and replaces (D2):** tap on `a` → selection `["a"]`; tap on `c` → `["c"]`, not
   `["a","c"]`.
2. **Band selects many, in drawing order (D2, D11):** band over the whole canvas → all four ids in
   drawing order. Band given corner-swapped `(200,50)-(10,10)` gives the identical selection. Band over
   only `a` → `["a"]` only.
3. **A band is points, not a bounding box (D11, non-vacuity):** a long diagonal line whose bounding box
   straddles an empty band but whose every point lies outside it is **not** selected.
4. **A drag moves every selected line (D4):** select `a` and `c`; `beginDrag`, `drag(10, 10, 5, 0)`,
   `endDrag` → both records moved 5 px in x; selection unchanged; `undo` has exactly one step.
5. **One drag is one undo step (D6):** after four `drag` calls and one `endDrag`, `undo.size == 1`
   whose `before` holds the ORIGINAL two records, and restoring it puts both back exactly.
6. **A drag that moved nothing commits nothing (D6):** `drag` with dx = dy = 0 then `endDrag` →
   `NoChange` and `undo.size == 0`.
7. **A second finger undoes the whole drag (D7):** after two `drag` calls, `cancelDrag` restores both
   records exactly; a following `endDrag` returns `NoChange` and leaves `undo` empty. A drag with
   `cancelDrag` **then** more `drag` calls must not resurrect the cancelled state.
8. **The grab radius is screen px (D5):** at `screenPerDoc = 1` a 24-doc-px radius moves a sample 20
   px from the grab by less than 1% of the offset; at `screenPerDoc = 8` the radius is 3 doc px and a
   sample 20 px away does **not** move at all. Non-vacuity: at zoom 8, a sample 2 doc px away still
   moves by nearly the full offset.
9. **Re-brush is one step for the selection, and keeps the seed (D9, D10):** select `a` and `c`,
   `rebrush(fillBrush)` → one `Changed`, one `InkEditStep`, both records' `brushId` changed and both
   `seed`s unchanged.
10. **`smudge` and `wet` are refused in words and change nothing (D9):** the returned
    `InkEditResult.Refused.reason` contains the brush's name; `undo.size` is unchanged; every record in
    the session is `==` to its value before the call (this is the assertion that catches a builder who
    mutates first and checks later).
11. **A missing brush makes a line unpickable (D12):** a session holding one record whose brush resolves
    to `null` — `tap` on it returns `NoChange`, the selection stays empty, and `lines()` has four
    entries where the fixture has five records.
12. **Re-weight (D8):** `reweight(2f)` then `reweight(0.5f)` on the same line → `widthScale` back to 1
    within 1e-6; `reweight(1000f)` → 20 (clamped by `StrokeEdit`); `reweight(Float.NaN)` →
    `Refused` and no step.
13. **Recolour is one step and touches only the colour (D9's shape):** the samples, `seed`,
    `widthScale` and `id` are all `==` before and after.
14. **The undo log is bounded (D13):** 60 `reweight` steps leave `undo.size == 50`, and the surviving
    steps are the newest 50 (assert by id/content, not by size).

**Command:** `./gradlew -p joybrush :core:jvmTest`. Passing = `BUILD SUCCESSFUL`, 0 failures in
`joybrush/core/build/test-results/jvmTest/`.

## Do not

- Do not edit `stroke/`, `vector/InkReplay.kt` or `vector/InkRaster.kt` — you consume them.
- Do not add a GPU path, a view, an overlay or a toast. This file is state and arithmetic.
- Do not give `beginDrag`/`drag`/`endDrag` a frame or a timestamp parameter; the "one drag, one step"
  rule is enforced by the API's shape, not by discipline.
- Do not clamp a re-brush that you could instead refuse. Refusal is the answer, and it must be total.
- Do not add a "delete selected" verb (Decision 3).

## Definition of done

- [ ] tests pass (paste output)
- [ ] only owner-area files changed (paste `git status --short`)
- [ ] committed as `JB-5.03: ink edit session`; pushed
- [ ] ROADMAP row → 🟧 Built

## Questions

**Q1 — BLOCKING-ish, and it decides where one line of the UI lives. R20 says a `smudge`/`wet` brush is
"refused in words" while ink strokes are selected. This spec does the refusing (Decision 9) and gives
you the sentence. What it cannot do is decide WHEN the refusal should be shown:** at the moment the
brush is tapped (so the picker visibly refuses), or only when a re-brush is attempted (so the picker
stays permissive and the error lands after the fact). My Decision 9 assumes the second, because the
first needs the brush picker — which is JB-1.05b's `BrushLibrary` inside `JbCanvasView`, a file
under the board's serialised app-file order and outside this owner area. **If you want the refusal at
pick time, that is a different owner area and I should say so in the spec rather than let a builder
find it.**

**Q2 — Decision 4: does a reshape drag of a MULTI-line selection move every line about the SAME grab
point?** That makes a selection move as one object. The alternative — each line grabbing its own
nearest sample — keeps each line's shape intact but moves a selection in several directions at once,
which I do not believe is what anyone wants and cannot prove. If you disagree, Decision 4 and test 4 are
the two places to change and nothing else moves.

**Q3 — Decision 13's 50-step log is a guess.** Ink records are small (a 2 000-sample stroke is ~66 KB),
so 50 steps of a large drawing could be tens of MB. **Should the budget be in steps or in bytes?**
`UndoLog` in core already budgets in bytes with a `release` hook; this log has no hook because an ink
record is pure value with nothing to release. I took the simple answer (steps) and named the number so
it can be changed in one line.

**Q4 — the view wiring is not here and nobody owns it yet.** This spec has no Android. Somebody still
has to draw the selection halo, route a finger drag to `drag`, run the re-render after every `Changed`,
and push the step onto the app's history. That is a second task (JB-5.03 is the T2 row on the board;
the wiring would be a `JB-5.03b` in `androidkit`, and it will collide with whatever is in
`JbCanvasView` at the time). **Should I write that spec too, or leave it for the Lead to schedule?**

# JB-2.05 — Selection and transform: one gesture to start, a live box, tap outside commits

| | |
|---|---|
| **Tier** | **T1** (it spans core, the engine, the view and the Studio's transform overlay) |
| **Status** | 📝 Draft spec — see ROADMAP.md |
| **Needs** | JB-0.07 (`GlPaintEngine` — Built), JB-2.02 (`ViewTransform` — Built), JB-2.05a (`SelectionMask` — Built), JB-2.05b (`Homography`, `Resample` — Built) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/select/SelectionState.kt`; NEW `.../commonTest/.../select/SelectionStateTest.kt`; EDIT `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt` (multi-layer compositing helpers only: `layerTilesOf`, `replaceLayerTiles`); NEW `.../androidkit/select/TransformSession.kt`; EDIT `.../androidkit/JbCanvasView.kt` (the selection/transform hooks listed in Decision 6) |
| **Estimated size** | ~250 lines of core + ~220 lines of tests; ~450 lines of the session; ~120 lines of engine helpers |

> **This file did not exist.** The ROADMAP row for JB-2.05 linked `specs/JB-2.05_selection_transform_ui.md`
> and that path was dead — the row is ⚪ Outline, its four "Needs" are all `🟧 Built`, and it is the
> row every later Phase 2 row waits on. **The link is the bug; this is the spec.**

## Goal

Blueprint §3.5, verbatim: *"**Selection done right** (the thing Infinite Painter gets wrong): start
it in one gesture (hold the S Pen button and loop); the transform box appears immediately; two
fingers *inside* the box transform it, outside they move the canvas; painting stays inside the
selection; the selection survives operations and can be reselected. One row of actions, nothing
hidden in a sideways scroll. A tap outside commits."*

And OWNER_CONSTRAINTS: *"Transform/selection: tapping outside commits"* (Concepts style). *"...the
transform box appears immediately."*

The maths exists (JB-2.05a's mask, JB-2.05b's homography and resampler). **What is missing is the
gesture, the state machine, the box, and the commit** — and the four phrases "survives operations",
"can be reselected", "painting stays inside the selection" and "tap outside commits" are four
separate promises that each need a rule or a builder will guess differently.

## Contract

```kotlin
package cc.joycreator.joybrush.core.select

import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.shape.Pt

/**
 * The document's selection. ONE object, and every promise about surviving is a fact about this
 * class rather than about a caller's bookkeeping. Lives as long as the screen.
 */
class SelectionState {
    sealed class Phase {
        /** Nothing selected. */
        object Idle : Phase()
        /** A mask exists; no transform box is live. */
        data class Selected(val mask: SelectionMask) : Phase()
        /** A transform box is live and moving; nothing is committed until the box is committed. */
        data class Transforming(val mask: SelectionMask, val quad: List<Pt>) : Phase()
    }

    var phase: Phase
        private set

    /** Starts a lasso from [points] (doc px, document order). The box appears IMMEDIATELY. */
    fun beginLasso(points: List<Pt>)

    /** The four corners of the live box, top-left, top-right, bottom-right, bottom-left, or null. */
    fun quad(): List<Pt>?

    /**
     * Applies a new quad. The PIXELS are not moved here: [Homography.fromQuads] and `Resample.warp`
     * do that (JB-2.05b), and this only records what the box now says. A degenerate quad (three
     * corners collinear, or non-finite) is REFUSED IN WORDS and the box does not move.
     */
    fun setQuad(q: List<Pt>): String?

    /**
     * Commits: returns the lift+transform this selection's move produces, and leaves [phase] Idle.
     * null when there is nothing to commit (no selection, or the quad is the identity) — "no change"
     * and "an empty change" are the same answer and neither is an undo step.
     */
    fun commit(): Commit?

    /** ABANDONS the box and keeps the selection, undoing the move. Owner: "Undo reverts." */
    fun cancelTransform()

    /** Whether a point is inside the selection's coverage. Painting inside the selection is allowed. */
    fun covers(x: Int, y: Int): Boolean
}

data class Commit(
    val layerId: String,
    /** Lift with [Resample.lift] → (lifted, remaining). */
    val lifted: Map<Long, ByteArray>,
    /** What stays behind: the pixels the selection did not take. */
    val remaining: Map<Long, ByteArray>,
    /** The homography the box describes, for [Resample.warp]. */
    val h: Homography,
)

/**
 * R20: only `stamp` and `fill` engines on an INK layer. A selection is not a third kind of thing:
 * selecting on an INK layer selects STROKES, so this is the one place the two layer kinds differ.
 */
object SelectionTargets {
    fun canSelect(kind: LayerKind): Boolean
    fun refusalFor(kind: LayerKind): String?
}
```

`TransformSession` (androidkit) owns the touch: the **Studio's own `TransformOverlayView`** from
D.02, which speaks **QUADS** — so the pixels move through `Homography.fromQuads`, which is exactly
why JB-2.05b is a homography and not an affine map. R23: share, do not copy.

## Decisions

1. **One gesture starts it: hold the S Pen's barrel button and loop.** The loop is a lasso
   (`SelectionMask.polygon`, non-zero, 4 × 4 anti-aliased — JB-2.05a). The pen keeps drawing a
   stroke as it loops; **the stroke is cancelled when the loop closes** and only the selection
   survives. One gesture, no mode, no toolbar — the owner named this as the thing Infinite Painter
   gets wrong.
2. **The box appears on the FIRST closed loop, before the pen lifts.** Blueprint: "the transform box
   appears immediately." Not on lift, not on a second gesture.
3. **Two fingers INSIDE the box transform it; two fingers OUTSIDE move the canvas.** This is a
   geometric test on the finger centroid, evaluated at the moment the second finger lands, and
   **it is latched for the whole gesture** — a pinch that drifts out of the box mid-way does not
   change its mind half way through, for the same reason JB-2.16a Decision 1 latches the axis.
4. **Painting stays INSIDE the selection.** A stroke begun outside the selection paints normally
   (the selection is not a clip for the whole canvas). A stroke begun inside is clipped to the
   selection: the samples outside it are dropped before they reach the dabber, and a stroke that
   leaves the selection and comes back continues (it is not cut into pieces). Clipping is by
   *coverage*, so a soft lasso edge is a soft clip, and `coverage == 0` is the only drop.
5. **A tap outside commits. A tap inside does nothing at all** — not "commits", not "deselects". The
   owner said "a tap outside commits"; a tap inside the box is the most likely mis-tap while moving
   handles and must not throw the move away.
6. **The S Pen barrel button tap still means ERASE with the current brush** (blueprint §3.5, and
   JB-2.06b/JB-1.05b both rely on it), so the loop gesture is **button + movement** and a
   button + tap with no loop is the eraser. The disambiguator is: did it close a loop? Under
   `40 × density` screen px of travel it did not.
7. **Commit is ONE undo step, and it is the lift.** `Resample.lift` splits the layer's pixels into
   `lifted` (inside the mask) and `remaining` (outside). `remaining` goes back into the layer with
   `replaceTiles`; `lifted` is warped by `h` and written into a **new layer placed directly above**,
   which is what makes the moved pixels separable from what they came off. Both are one
   `UndoLog.Step` (the engine's `replaceTiles` takes a whole map, so the commit is one call per
   layer and the two together are one history event).
8. **The selection SURVIVES the commit** and the new layer. Blueprint: "the selection survives
   operations and can be reselected." So after a commit, `phase` returns to `Selected` with the
   mask **transformed by `h`** — not cleared, not reset. A second nudge therefore moves the moved
   pixels again, not the original ones.
9. **Undo reverts the MOVE, not the selection.** "Undo reverts" (OWNER_CONSTRAINTS). So undo of a
   commit restores the pixels and the selection's mask is re-derived by pushing `h.inverse()` onto
   the mask history — the selection is a **stack of homographies**, not a single mask, and undo
   pops it. This is the only way "survives operations" and "undo reverts" can both be true.
10. **A selection cannot cross layers.** A selection belongs to ONE layer; a new loop while one is
    live replaces it. The mask is layer-relative because `Resample.lift` splits ONE layer's tiles,
    and a mask applied to two layers would be two selections pretending to be one.
11. **R20: on an INK layer, selecting selects STROKES, and a move moves strokes** (`StrokeRecord`s
    re-warped, not pixels). `canSelect(INK)` is true from this spec's point of view and the pixels
    path is refused in words until JB-5.01/5.03 land. `SelectionTargets` is the one place that says
    which, so a builder cannot answer it twice.
12. **Commit is refused, in words, for a box that would not fit.** `Resample.warp` returns an EMPTY
    map for a singular matrix, a corner at infinity, or a destination over `MAX_SELECT_SPAN` — and
    JB-2.05b's Q2 established that "empty" is ALSO the answer for "nothing there", so **this spec
    closes that gap by checking the destination span BEFORE calling `warp`**, and says so in words
    ("this move is too large to make") rather than committing a selection that vanished.

## Tests

`SelectionStateTest` (JVM, `:core:jvmTest`) — the four survival promises:
1. `beginLasso` with a triangle of 3 points → `phase` is `Selected`, and `quad()` is **non-null
   immediately** (Decision 2, "appears immediately" — asserted without a lift, because that is the
   whole promise).
2. `setQuad` with three collinear corners → **returns a sentence and the box does not move**
   (Decision: refuse, not clamp).
3. `commit()` with the identity quad → **null** (no undo step for nothing).
4. **Survives operations:** commit a 10-px translate, then `phase` is `Selected` and the mask's
   bounds have moved by 10 — not reset, not cleared.
5. **Undo reverts the move, not the selection:** commit, pop, and the bounds are back where they
   were while `phase` is still `Selected`.
6. `covers` on a soft lasso edge returns the intermediate coverage and 0 outside, never a
   boolean-rounded answer.
7. `beginLasso` with 2 points, or a NaN point → `Idle`, no throw (JB-2.05a Decision 7's "garbage in
   gives EMPTY", carried through).
8. `commit()` on a mask whose destination span exceeds `MAX_SELECT_SPAN` → **a sentence, and
   `phase` is unchanged** (Decision 12 — this is the test that closes JB-2.05b's Q2 hole).
9. `canSelect(PAINT)` true; `engineAllowedOn` is NOT re-implemented here — assert
   `SelectionTargets` answers for both kinds without a second copy of R20's rule (a source check
   that `SelectionState.kt` contains no `BlendMode`/`Engine` literal).

The touch half (`TransformSession`) is verified on the sandbox phone, listed in the owner check,
and **not pretended into a JVM test**.

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures. Then
`./gradlew -p joybrush :androidkit:compileKotlin :androidkit:test` and the watcher green.

## Owner check (Note 9, with the S Pen)

Hold the barrel button, draw a circle over a painted area → a dashed cyan marching line, and the
transform box is there the instant the loop closes; release the pen and the selection stays. Two
fingers inside the box → scale; outside → the page pans; start a pinch inside and drift out → it
keeps transforming. Drag a handle, then **tap outside** → the move lands as ONE undo step; undo puts
the pixels back and the selection is still there. Tap INSIDE the box → nothing happens. Paint a
stroke starting inside the selection → it stops at the soft edge; start outside → it paints
normally. With the pen down in a loop, tap the barrel quickly with no loop → erase, not select.
Nudge: a tiny drag inside the box moves 1 screen px at zoom 1 and a quarter of that at zoom 4.

## Do not

- **Do not touch `SelectionMask`, `Homography` or `Resample`.** They are Built and reviewed. If one
  of them cannot do something this spec needs, it is a question, not an edit.
- Do not write your own handles, spin arc, loupe or corner-pin. R23: `TransformOverlayView` from
  D.02, unmodified. If the overlay's `Host` cannot express "my quads are document px", that is a
  D.02a/D.02 question.
- Do not implement warp (`JB-2.05c`, R17) and do not do ink-stroke moving (JB-5.03).
- Do not clear the selection on any operation — that is the exact bug blueprint §3.5 names.
- Never run gradle on the owner's PC.

## Definition of done

- [ ] tests pass (paste)
- [ ] `git status --short` shows only owner-area files
- [ ] watcher green
- [ ] owner check noted
- [ ] committed `JB-2.05: selection and transform`
- [ ] ROADMAP row → 🟧 Built

## Questions

_(Spec writer, `openrouter/stealth/space-bunny-alpha`, 2026-09-29. T1 because the honest version of
this row touches four modules and the Studio's overlay, and because Decisions 8/9 (survives +
undo reverts) are a design of mine that the Lead should see before a builder implements it.)_

### 🔴 For the Lead

1. **Decision 8 + Decision 9, together, are the interesting part and they are mine.** "The selection
   survives operations" and "undo reverts" only coexist if the selection is a **stack of
   homographies** rather than one mask. The alternative is to re-derive the mask from the layer
   after every undo, which is a rasterisation per undo and is wrong the moment the layer was
   repainted. My cost: a selection's memory is a list of 3×3 matrices, unbounded in principle. I
   capped it at **64 entries** with the oldest dropped (and a dropped entry means the selection
   restarts from the current pixels) — a number I invented. Confirm the model, and rule the cap.
2. **"Painting stays inside the selection" — does that mean clipped, or blocked?** I read it as
   clipped (samples outside the coverage are dropped; inside continues) because the alternative
   makes the selection a fence and a painter who starts a stroke at the edge of a selection cannot
   finish it. But "stays inside" could equally mean "the app refuses to let you paint outside",
   which is Concepts' actual behaviour and is the safer reading if the person is going to be
   annoyed. **This is a feel decision and I got the blueprint's words to support my reading
   weakly.** If you know what Concepts does, that settles it.
3. **The moved pixels land in a NEW LAYER (Decision 7).** That is what makes a second move work and
   what makes undo cheap, and it is also what will surprise a person who has five layers and finds
   six. The alternative is to move pixels back into the SAME layer with the vacated area left
   transparent, which keeps the layer count honest and makes repeated moves accumulate resampling
   loss in one layer. I chose the new layer. **Is that acceptable, or does the layer list need to
   stay flat?**
4. **Tier.** The roadmap says T1 and I have kept it. If a T2 builder can do this against
   `TransformOverlayView`'s `Host` interface, say so and it becomes T2 — the file is long but most
   of it is plumbing.

### Low-risk, ruled provisionally

5. **A tap inside the box does nothing** (Decision 5) — not even commit, not even deselect.
6. **The inside/outside test is latched at the second finger's landing** (Decision 3).
7. **A selection cannot cross layers; a new loop replaces the old one** (Decision 10).
8. **Under 40 dp of travel, a button-held pen is an eraser and not a selection** (Decision 6) — a
   number I chose, on the same "was it a deliberate gesture" principle as JB-2.16a's 12 dp.

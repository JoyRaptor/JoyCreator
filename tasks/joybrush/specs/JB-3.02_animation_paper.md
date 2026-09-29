# JB-3.02 — Animation paper: the peg bar that doubles as buttons, and the pixel rulers

| | |
|---|---|
| **Tier** | T2-V (a vision/design model; the maths half is plain T2 and is tested in `:core`) |
| **Status** | 🟨 **Draft.** The maths (peg layout, hit test, ruler ladder) is fully decided below and a T2 builder can build and test it today. What is *not* decided is where the peg bar sits relative to JB-2.01's control cluster, and 2.01 has no spec — see **Q1**. Read **Q1** before starting; if the Lead's answer is "the peg bar IS the animation board's button row and 2.01 re-hosts it", this becomes 🟦 Ready with no other edit. |
| **Needs** | 3.01, 2.01 (as the ROADMAP row states) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/anim/Paper.kt` · NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/anim/PaperTest.kt` · NEW `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/anim/PaperOverlayView.kt` · EDIT `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/JoyBrushActivity.kt` (host the overlay and wire the peg callbacks — nothing else in that file) |
| **Estimated size** | ~230 lines of Kotlin in `:core` + ~200 lines of tests, ~180 lines of view |
| **Command** | `./gradlew -p joybrush :core:jvmTest` — 0 failures. Then the watcher compiles `:androidkit:compileKotlin` and `:joybrush-android:compileDebugKotlin` green. |

## Goal

Blueprint §1 idea 10, the "animation paper": a board of film pinned to a desk. Two things make it
read as *paper* rather than as a toolbar with a ruler on it, and both are here:

- **A peg bar that doubles as buttons.** The pegs of a music box are not decoration — pressing one
  is the only thing they do. So the bar is a row of physical pegs and every peg IS a button, in the
  same sense that a piano key is both. It carries the controls that belong to no one frame: play,
  play mode, onion skin, cadence, export.
- **Pixel rulers.** Two edges, marked in *document* pixels, because on the animation board a pixel is
  the unit everything is measured in — a cell is 64 px, a hold is 3 ticks, a board is 512 px wide.
  Never an export, like every other helper (blueprint §3).

The frame-scoped controls (the ± actions, drag-to-hold, scrub) are **JB-3.03's** and are not in this
spec. The onion skin's own component is **JB-3.04's**.

## Contract

```kotlin
package cc.joycreator.joybrush.core.anim

import cc.joycreator.joybrush.core.doc.Board

/**
 * The animation paper's two pieces of geometry. Pure: no Android, no clock, no file.
 *
 * Every length that is a FEEL number is in SCREEN px already multiplied by `density`, because these
 * are laid out on a phone and a number that has to be divided by the density at every call site is
 * a number that will be divided wrong once. Every length that is a MEASURE is in DOCUMENT px, and
 * is an Int or a Long because a coordinate on an unbounded canvas is not a Float (R19).
 */
object Paper {

    /** Peg button radius, screen px (already × density). 14 dp → a 28 dp circle. */
    const val PEG_RADIUS_PX: Float = 28f

    /** Distance between peg centres, screen px (already × density). 44 dp — the house touch floor. */
    const val PEG_PITCH_PX: Float = 88f

    /** How close a finger-down must be to a peg's CENTRE, screen px, to press it. */
    const val PEG_HIT_RADIUS_PX: Float = 44f

    /**
     * Where each peg sits, left to right, in a bar [barWidthPx] wide.
     * Pegs are centred as a block and the block never runs off either end; the bar is padded, not
     * clipped, because a clipped peg is a peg nobody can press. Always at least one peg.
     */
    fun pegCentres(count: Int, barWidthPx: Float): FloatArray

    /** The index of the peg under [xPx], or -1. Ties (`<=`) go to the NEAREST peg, lower index first. */
    fun pegAt(xPx: Float, count: Int, barWidthPx: Float): Int

    /**
     * The pixel rulers' tick positions along one axis, as DOCUMENT px RELATIVE TO THE BOARD'S
     * TOP-LEFT CORNER, ascending. The step is the finest of 1/2/5 × 10^k whose on-screen spacing
     * is at least [MIN_LABEL_PX]; [screenPerDoc] is `ViewTransform.zoom` (screen px per doc px —
     * R19/2.16a: pass the zoom, never its inverse).
     */
    fun ticks(viewStartDoc: Double, viewEndDoc: Double, boardOriginDoc: Double,
              screenPerDoc: Float, density: Float): LongArray

    /** The label a tick prints. Whole pixels, no decimal point, no thousands separator. */
    fun label(tickDocRelToOrigin: Long): String
}
```

```kotlin
// joybrush/androidkit/src/main/kotlin/.../androidkit/anim/PaperOverlayView.kt
//
// A transparent View drawn OVER the canvas. It owns no state: every peg's pressed state and
// every drawn colour is a parameter of draw(), so the view cannot disagree with the model.
class PaperOverlayView(context: Context) : View(context) {
    /** Set false to hide the peg bar only; the rulers have their own switch. */
    var showPegBar: Boolean = true
    var showRulers: Boolean = true

    fun interface OnPeg { fun onPeg(peg: Peg) }
    fun setOnPeg(l: OnPeg?)

    /** Repaint this whenever the view transform changes. The host owns the trigger. */
    fun refresh()
}

enum class Peg { PLAY, MODE, ONION, CADENCE, EXPORT }
```

## Decisions

1. **The peg bar carries exactly five pegs, in this order: `PLAY, MODE, ONION, CADENCE, EXPORT`.**
   Fixed, not a list the host may extend: a peg bar whose contents grow is a peg bar nobody can
   design, and the ORDER is the layout. *These are the board-level controls; the frame-level ones
   (± add / duplicate / link / delete, hold ±) are JB-3.03's and live on the film strip.*
2. **Press semantics, straight from blueprint §1(a)**: **tap = the action**, **long-press = that
   peg's options**, **hover (pen or mouse) = its label**. There is no hover menu, because hover does
   not exist for fingers. *Why:* the blueprint already ruled this and re-deciding it would be
   re-litigating.
3. **A peg is a circle of radius `PEG_RADIUS_PX` drawn in the board's identity gradient**
   (`JbColors.boardGradient(context, ANIMATION)` — aqua→lime, "the colour of what it feeds"), with
   the peg's glyph punched out in `ON_GO`. It is a FILL, never a ring: a coloured ring already means
   a state, and a state colour must never be a section accent. *Why:* D.01's rule and
   `JOYBRUSH_VISUAL_LANGUAGE.md` §4.3.
4. **`ONION` wears the board gradient only while onion skin is ON**, and `PALM`-free `jb_raised`
   with a `jb_line` ring while off. `PLAY` is the one peg allowed to be "action" — while playing it
   wears `studio_action_pill` (aqua→lime, the app's own), and it is the ONLY saturated action control
   on the animation board. *Why:* "one saturated control per screen" (`JOYBRUSH_VISUAL_LANGUAGE.md`
   §1.4), and a Play button that looks the same whether it is playing is a control that lies.
5. **A peg is 44 dp of touch target, hit-tested against its CENTRE within `PEG_HIT_RADIUS_PX`
   (44 dp), with ties (`<=`) to the LOWER index.** Hit-testing the centre rather than the drawn disc
   is deliberate: the drawn disc is 28 dp, the target is 44 dp, and a hit test that matched the
   drawing would make every peg a 28 dp target on a phone. *Why:* `JOYBRUSH_VISUAL_LANGUAGE.md` §1.9
   — "visual ≠ touch size".
6. **Pegs are centred as a block and the bar pads rather than clips.** `pegCentres` puts the block
   of `count` pegs at pitch `PEG_PITCH_PX` in the middle of `barWidthPx`; if the bar is narrower than
   the block the pitch shrinks to `barWidthPx / count` (never below `PEG_RADIUS_PX × 2`) and the pegs
   stay pressed against the ends. `count < 1` gives one peg. *Why:* a peg scrolled off the end of
   the bar is a control that exists and cannot be reached.
7. **Rulers are DOCUMENT pixels measured from the ACTIVE BOARD'S top-left corner, not from the
   document's (0,0).** On a board you measure a frame, and a frame's corner is the board's corner.
   The ruler says so: its zero tick is drawn heavier than the others. *Why:* the board may sit at a
   negative x or y on the unbounded canvas, and a ruler reading −1400 would be true and useless.
8. **The ruler ladder is 1 / 2 / 5 × 10^k, choosing the FINEST step whose on-screen spacing is at
   least `MIN_LABEL_PX = 48 dp × density`.** Not "round the step" — the step is chosen from the
   ladder by comparison, so no rounding epsilon can put a tick half a label off. A non-finite or
   ≤ 0 `screenPerDoc` yields **no ticks** (a ruler drawn with a broken zoom is a grey block).
9. **Ticks are at `boardOrigin + k × step` for integer k, and k ranges over NEGATIVE integers too.**
   A tick is therefore at or BELOW a negative coordinate, never at the origin plus a positive count:
   at step 2 the ticks near −3 are −4 and −2, never −2 and 0 only. *Why:* this is the same
   floor-not-truncate bug the project keeps meeting at a seam, and on a ruler it is visible.
10. **Rulers are overlays and are never in an export.** They are a `View` over the canvas, not a
    layer, so this is true by construction — and `RulerTicksTest` pins it anyway by asserting the
    export path (`RegionRenderer`) never sees them. *Why:* blueprint §3, "helpers never export".
11. **`PixelRuler` is a NEW type and is NOT `guide.Guide.Ruler`.** `Guide.Ruler` (JB-2.12a) is a
    *magnetic straight edge the pen runs along*. This is a measuring scale. Same word, opposite
    thing, and a builder who finds `Guide.Ruler` first will wire the wrong one. *Why:* the name
    collision is real and silent.
12. **The ruler's zero is the board origin, and the board origin moves with the view.** The overlay
    reads `JbCanvasView.view` (public) and converts screen→doc itself; it never caches a zoom. A
    zoom therefore re-ladders the ruler on the next frame with no state to invalidate.
    *Why:* a cached zoom is a ruler that lies after a pinch.
13. **The peg bar is one row, 56 dp tall, docked to the BOTTOM of the canvas, above whatever
    JB-2.01 puts there.** This is the **provisional** answer to Q1 and follows the precedent
    D.02c already set ("Joy Brush placement (for now): … JB-2.01's real chrome will move it beside
    the tools (same View, re-hosted)"). If the Lead rules otherwise, only `JoyBrushActivity`'s
    layout changes — `Paper` and `PaperOverlayView` do not.
14. **The rulers are 24 dp strips along the top and left, inside the canvas, and they are hidden
    entirely while a stroke is in progress** (a ruler crossing a wet stroke is a ruler somebody
    smears). *Why:* same instinct as palm rejection — a transient overlay must never touch the art.
15. **Peg callbacks carry the peg and nothing else.** No state, no document, no lambda soup: the
    host decides what ONION means. *Why:* the view must be testable by drawing it and the host by
    calling it.

## Decision → Test map (every Decision is checkable)

| Decision | Pinned by |
|---|---|
| 1 (five pegs, fixed order) | `thePegBarHasFivePegsInAFixedOrder` (counts the enum's entries and their ordinals) |
| 2 (tap / long-press / hover) | `pressSemanticsAreTheOnesTheBlueprintRuled` — a comment-free constant table `Paper.Press` the view reads; the test asserts tap≠long-press≠hover are three distinct cases |
| 3 (fill, not ring) | `aPegIsAFillAndNeverARing` — the view's draw list has no ring for an identity colour (asserted against the token pair it asks for) |
| 4 (ONION off-state, PLAY is the one action) | `onlyPlayWearsTheActionGradient` |
| 5 (hit test, 44 dp, ties low) | `pegAtHitsTheCentreNotTheDrawing`, `twoPegsEquidistantGoToTheLowerIndex` |
| 6 (centre the block, pad not clip) | `pegsAreCentredAndNeverClippedInANarrowBar`, `aBarNarrowerThanTheBlockShrinksThePitch`, `zeroPegsIsOnePeg` |
| 7 (board-relative zero) | `theRulerIsMeasuredFromTheBoardNotTheDocument` (board at (−1400, 300)) |
| 8 (1/2/5 ladder, finest ≥ 48 dp) | `theRulerStepComesFromTheLadderNotFromARounding` (at 8× zoom-out a step of 3 or 7 is impossible) |
| 9 (negative k floors) | `negativeTicksAreAtOrBelowTheCoordinateNotAbove` (step 2 across −3) |
| 10 (never exported) | `helpersAreNeverInAnExport` |
| 11 (not `Guide.Ruler`) | `thePixelRulerIsNotTheTracerRuler` — asserts the two types are unrelated and that `Paper.ticks` needs no `Guide` |
| 12 (no cached zoom) | `aZoomChangeReladdersTheRuler` (same view, two zooms, two different steps) |
| 13 (bottom row, 56 dp) | `thePegBarIsOneRowAtTheBottom` (the overlay reports its bar rect) |
| 14 (hidden during a stroke) | `theRulersHideWhileAStrokeIsInProgress` |
| 15 (callback carries the peg) | `aPegCallbackCarriesThePegAndNothingElse` |

## Tests

`joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/anim/PaperTest.kt`

1. `pegCentres` on a 600 px bar with 5 pegs: centres at 256, 300, 344, 388, 432 — block centred,
   pitch 88, symmetric about 300. With 1 peg: exactly one at 300. With 0: one peg at 300.
2. `pegCentres` on a 200 px bar with 5 pegs: pitch 40, first centre 20, last 180 — **no peg centre is
   outside the bar and no peg is clipped**.
3. `pegAt(300, 5, 600)` = 2. `pegAt(300 ± 44)` = 2 and 2 (the tie goes to the peg it is on);
   `pegAt(300 + 88 − 0.001)` = 3. `pegAt(0, 5, 600)` = 0. `pegAt(-1, …)` = -1,
   `pegAt(1e9f, …)` = -1, `pegAt(NaN, …)` = -1.
4. `ticks` at zoom 1, density 3, label floor 144 px: view 0..600 over a board at 0 → step 100
   (100 × 1 = 100 < 144, so 200 is the finest that fits — check the ladder answer by hand in the
   test comment). Every returned tick is a multiple of the step **and** of nothing else: 150 and 175
   are impossible.
5. `ticks` at zoom 0.05 (min zoom): the step is 1000, and the tick COUNT is ≤ 2 for a 600 px view —
   a ladder step that produced 600 ticks would be a grey block.
6. `ticks` at zoom 64: the step is 1.
7. `screenPerDoc` = 0, −1, NaN, +∞ → **empty array**, no exception.
8. `ticks` across a NEGATIVE board origin, step 2: for a view covering −3..3 the ticks are
   `…, -4, -2, 0, 2, 4, …` and `-2` is present while `+2` alone would be the truncation bug.
9. `label(0)` = `"0"`, `label(-1400)` = `"-1400"`, `label(512)` = `"512"`. No decimal point, no
   comma, no unit.
10. `theRulerIsMeasuredFromTheBoardNotTheDocument`: two boards at (0,0) and (−1400, 300), same view
    → the tick ARRAYS are identical, because both are board-relative. A document-relative ruler
    would differ by 1400 and fail.
11. `aZoomChangeReladdersTheRuler`: the same `Paper` at zoom 1 and zoom 0.1 gives steps 100 and 1000
    from the same inputs — the ladder is a function of the zoom, not of history.
12. `theRulerStepComesFromTheLadder`: for 20 zooms, every returned step divided by its largest
    power of ten is 1, 2 or 5. `0.03` and `7` are impossible by construction and the test says so.
13. `helpersAreNeverInAnExport`: a `RegionRenderer.render` of a document with nothing but a layer
    behind a "ruler" produces bytes identical to the same document with the ruler not mentioned —
    the ruler is a View and never a tile. (Pins that no future builder adds rulers to a layer.)
14. `aPegCallbackCarriesThePegAndNothingElse`: `OnPeg.onPeg` takes one `Peg` and nothing else —
    asserted by calling it and by the signature having a single parameter.

Command: `./gradlew -p joybrush :core:jvmTest` — 0 failures.

## Do not

- **Do not touch `DocModel.kt`, `DocJson.kt` or `JbArchive.kt`.** This spec adds no field to the
  document; the peg bar and the rulers are screen things and stay screen things.
- Do not draw the film strip, the sprockets, the ± actions or the onion ghosts. JB-3.03 and JB-3.04
  own those; two views drawing the same pixels is how preview and export start disagreeing.
- Do not wire `MODE`, `CADENCE` or `EXPORT` to anything. This spec delivers the bar; the callbacks
  fire and the host decides.
- No hex literals — every colour is a `JbColors.palette(context)` field (D.01).
- Do not read `view.zoom` into a field. Read it, use it, drop it (Decision 12).
- Do not add a hover menu. Hover is a label (Decision 2).

## Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest` output pasted, 0 failures
- [ ] watcher `build.log` shows `:androidkit:compileKotlin` and `:joybrush-android:compileDebugKotlin` EXECUTED inside `BUILD SUCCESSFUL`
- [ ] `git status --short` shows only the four owner-area paths
- [ ] committed `JB-3.02: animation paper`; ROADMAP row set by the Lead

## Questions

_(Spec writer: openrouter/stealth/space-bunny-alpha, 2026-09-29. Two are the Lead's and one is a
correction to the ROADMAP row. The maths is decided and pinned; what is missing is a host.)_

### Q1 — for the Lead: where does the peg bar sit, and does 2.01 have to exist first?

JB-3.02's ROADMAP row says **Needs 3.01, 2.01**, and **JB-2.01 ("Screen chrome: control cluster,
thumb rail, drawers-on-phone / popovers-on-tablet") is `⚪ Outline` with no spec**, and its own needs
(JB-0.09 `⚪ Outline`, D.02 Lead-owned and not dispatched) are even further out. The peg bar *is* a
button row, so "build the animation board's buttons before the screen chrome exists" is a real
ordering question, not a formality.

I have **ruled provisionally** (Decision 13) following the precedent D.02c set: the peg bar is one
56 dp row docked to the bottom of the canvas, and JB-2.01 re-hosts the same `PaperOverlayView` when
it lands. That keeps `:core` and `PaperOverlayView` independent of the chrome, so a later ruling costs
one layout block in `JoyBrushActivity` and nothing else.

**What I need ruled:**
1. Is the provisional placement accepted, or does JB-3.02 wait for JB-2.01?
2. If it waits, is the row's "Needs" right? A peg bar that *is* the animation board's button row
   arguably does not need the general chrome at all — it needs a slot for it.
3. Does the peg bar persist when the UI is hidden (4-finger tap, JB-2.02)? My ruling: **no** — the
   peg bar is chrome, and hidden chrome is hidden chrome. Say if you want it to survive as a
   one-hand control.

### Q2 — for the Lead: "pixel rulers" — is one ruler enough, and what about a centre line?

Blueprint §1 idea 10 says "pixel rulers" without saying how many or where. I ruled **two strips, top
and left, document pixels from the board's top-left** (Decisions 7, 8, 14). Two questions I did not
decide because they are product, not maths:

- **Should the rulers be draggable to move the board's origin** (the old Photoshop behaviour, where
  you grab the ruler and slide the page)? It is a lovely idea and it is a *gesture on the canvas*,
  which is the one place this project has already decided fingers navigate and never draw
  (owner's ruling, and R5). My answer would be "no, and never on the ruler either", but it is yours.
- **Should a 0-th and centre line be drawn heavier**, or is the zero tick enough? I ruled zero-only,
  because a centre line on a board that is not an odd number of cells is a lie about where the
  middle is.

### Q3 — for the Lead, and it is a correction to my own spec's premise

`paper.Paper.ticks` is the *scale*. The **tracer** ruler a pen runs along is
`guide.Guide.Ruler` from JB-2.12a, and it already exists, is already tested, and is a completely
different object. Decision 11 forbids confusing them, but the collision is in the *vocabulary*: the
blueprint lists "ruler" in §1 idea 10 (pixel scale) and §3 (pen tracer) in the same document, and
JB-2.12a's KDoc quotes the second. I named the new one `Paper` rather than `PixelRuler` to keep the
word out of the type name entirely.

**Is that the naming you want?** The alternative is to rename JB-2.12a's `Guide.Ruler` to
`Guide.StraightEdge` — which is a change to a reviewed spec's contract and outside this spec's owner
area, so I did not do it. I would rather the Lead renamed it in one edit than have two `Ruler`s in
the codebase forever.

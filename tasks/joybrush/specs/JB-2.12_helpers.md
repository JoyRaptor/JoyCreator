# JB-2.12 — Helpers on screen: the grid, perspective guides and the shape tracers

| | |
|---|---|
| **Tier** | T2-V |
| **Status** | 📝 Draft spec — see ROADMAP.md |
| **Needs** | JB-2.01 (screen chrome — Draft), JB-2.12a (`Guide`, `GuideSnapper`, `GuideLines` — Built 🟧) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/guide/GuideSettings.kt`; NEW `.../commonTest/.../guide/GuideSettingsTest.kt`; NEW `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/guide/GuideOverlayView.kt`; EDIT `joybrush-android/.../JoyBrushActivity.kt` (the Helpers panel host) |
| **Estimated size** | ~140 lines of core + ~130 lines of tests; ~300 lines of overlay + the drag handles |

## Goal

Blueprint §3: *"**Helpers are overlays**, never part of the art: grids, perspective guides, and shape
tracers (ruler, ellipse) that the pen can run along. **They never export.**"*

JB-2.12a built all of that as maths — what lines to draw, and how a stroke is pulled onto them. This
row is the half a person touches: turn a helper on, place it, drag it, size it, see it locked, and
turn it off. Plus the promise in the blueprint's own sentence that a builder will otherwise forget:

> **They never export.** Not in PNG, not in PSD, not in OpenRaster, not in the undo stack, not in
> the document. A grid is a `GuideLines` result drawn on a `View`; it is not a tile, and a tile is
> the only thing any exporter can read.

## Contract

```kotlin
package cc.joycreator.joybrush.core.guide

import cc.joycreator.joybrush.core.shape.Pt

/**
 * What the person has switched on and how it is placed. A `Guide` is pure geometry (JB-2.12a); this
 * is the state around it, and it is pure so a builder cannot put a grid in a tile by accident.
 */
data class GuideSettings(
    val grid: Guide.Grid? = null,
    val isometric: Guide.Isometric? = null,
    val perspective: Guide.Perspective? = null,
    val tracers: List<Guide> = emptyList(),          // Ruler and EllipseTracer, any number
    val snapEnabled: Boolean = true,
) {
    /** Everything on, in the order they compete (JB-2.12a Decision 4: a tracer beats a direction). */
    fun all(): List<Guide>

    /** The reason a settings object may not be used, in words, or null. Never a silent fix-up. */
    fun refusalFor(): String?

    companion object {
        /**
         * The grid a NEW document starts with: 100 doc px, origin (0,0), angle 0. Not null — a
         * grid is off by being invisible, and "off" is a separate switch.
         */
        val DEFAULT: GuideSettings

        /**
         * A grid whose spacing is on screen at least [minScreenPx] apart, by doubling the spacing
         * until it is. The thin-every-2nd-lines behaviour of `GuideLines` is the last resort; a
         * 2 dp grid on a 0.2× zoom is a grey block whichever of the two you use.
         */
        fun withMinScreenSpacing(g: Guide.Grid, zoom: Float, minScreenPx: Float = 8f): Guide.Grid
    }
}
```

## Decisions

1. **Helpers live on a `View` and nowhere else.** One transparent `GuideOverlayView` above the GL
   view (the same one-off overlay discipline as JB-2.03a and JB-2.06b — **one overlay, and this is
   it**). It draws `GuideLines.visible(...)` output and nothing else; it has no reference to the
   engine, to `JbCanvasView` or to any document. **The test that makes "never exported" true is a
   test on the code's shape, not on an export** — see Tests 6/7, and read Decision 2.
2. **A guide's state is per SESSION, not in the document.** A grid is an aid to drawing, not part of
   the drawing (blueprint §3's "never part of the art"). It is not in `JbDocument`, so it is not in
   `document.json`, so it cannot round-trip and cannot be exported. Consequence stated plainly:
   **a person who sets up a perspective guide, leaves and comes back has to set it up again**, and
   that is the trade the blueprint's sentence makes. Reversible, but a product decision.
3. **Snapping is ON by default and can be switched off** while the guide stays visible — a person
   who wants the lines without the pull has to be able to have that, and the two are separate
   decisions in JB-2.12a (lines vs `GuideSnapper`).
4. **A tracer is placed by dragging its handle, not by a dialog.** Ruler: drag anywhere → the line
   from the down point to the up point. EllipseTracer: drag a box for the bounds, then drag the two
   axis handles for rx/ry and the centre dot for the centre. Iso-style: the two ends are draggable
   independently, which is what makes it a straight edge rather than a decoration.
5. **The perspective guide's vanishing points are dragged to where they are, on screen**, and the
   overlay draws a ray from each so the drag has a handle. This is the rule JB-2.12a's review
   *fixed* (the snap direction is measured at the START, and the rays are anchored at each vanishing
   point) — so a point dragged far off the screen is fine, and the rays that do not reach the view
   produce no segments (JB-2.12a Decision 6's own answer, not a new one).
6. **A locked guide draws in `GUIDE` violet and a locked tracer draws with a 1 dp brighter outline**;
   the snap line itself is a 1 px `GUIDE` hairline, never a filled shape. State colours are not
   touched (visual language §1.3: `GUIDE` violet is a hint, `ARMED` cyan is "selected", and a guide
   is not selected).
7. **The grid spacing control is a SCRUB NUMBER, not a stepper**, because "one drag per decade" is
   the useful thing and D.02b's shared scrubbable number is the component for it. If D.02b has not
   landed this row uses a plain slider and says so in the commit.
8. **`withMinScreenSpacing` doubles, never divides.** Halving a spacing to make lines appear is how
   you get a 0.05 px grid; doubling is monotone and terminates (spacing ≥ 8 screen px within ~20
   doublings from any legal start). A non-finite or ≤ 0 zoom is read as 1, the same guard
   `SizeOpacityDrag` and `Nudge` use — **the same guard, not a third copy of it.**

## Tests

`GuideSettingsTest` (JVM, `:core:jvmTest`):
1. `DEFAULT` has a grid and nothing else; `all()` returns the guides in competition order
   (tracers first, then direction guides) — the order JB-2.12a Decision 4 says wins.
2. `refusalFor()` returns null for a legal set, and a **sentence naming the offending guide** for:
   a `Grid` with `spacing <= 0`, a `Perspective` with 0 or 4+ vanishing points (JB-2.12a's own
   contract is 1–3), and a non-finite origin. It never silently repairs one.
3. `withMinScreenSpacing(100 px, zoom 0.2)` → 400 (8 screen px exactly); at zoom 1 → 100; at zoom 4
   → 100. **At exactly 8 screen px it stops** (asserted with `<`, not `<=`, and the test says so).
4. Non-finite / 0 / negative zoom → treated as 1 and named as such.
5. `all()` never returns the same guide twice when a tracer is in both `tracers` and elsewhere
   (identity, not equality).
6. **`aGuideIsNeverPartOfTheDocument`:** a `GuideSettings` with a grid and a tracer, run through
   `DocJson.encode` / `decode` of a document that was made while those helpers were on, produces a
   byte-identical `document.json` to the same document with the helpers off. This is the test that
   makes blueprint §3's "never part of the art" **checkable** rather than a promise in a comment.
7. **And the exporter side, as a source-level check:** `GuideOverlayView.kt` and
   `GuideSettings.kt` contain no reference to `GlPaintEngine`, `writeTile`, `replaceTiles`,
   `JbDocument` or `TileSource`. The rule is "a guide is a `View`, and a tile is the only thing an
   exporter reads", and this is the test for the first half of that sentence.
8. The overlay's segment count for a 0.001 px grid at zoom 1 is ≤ 2000 (JB-2.12a Decision 6's cap,
   re-asserted at the UI's door — the maths caps it, and a builder adding a second line-drawing
   path could undo that).

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures. Then the watcher green.

## Owner check (Note 9, then Tab S8)

Switch on the grid → lines appear; drag the spacing number → they coarsen; zoom out to where they
would go solid → they thin instead. Draw a line along one → it snaps, and the line turns `GUIDE`
violet while locked. Turn snapping off → the lines stay, the pull goes. Place a ruler, draw near it →
the stroke rides it. Place a perspective guide and drag a vanishing point far off the right edge →
rays fan out from it; draw a line → the snap follows the guide, not the pen's current heading. Draw
inside the grid, then Export PNG → the exported file has no grid, no ruler, no rays, and the same
bytes as it would with every helper off.

## Do not

- **Do not put a guide in the document, a tile, or an export.** That is the entire point (Decision 2)
  and test 6 exists to prove it.
- Do not edit `Guide.kt`, `GuideSnapper.kt` or `GuideLines.kt` — they are Built and the second one
  has a review history. If a helper needs a shape they do not have, it is a question.
- Do not re-implement snapping, thinning, or the perspective snap direction. JB-2.12a's review
  *found and fixed* the direction bug; a second implementation is how it comes back.
- Do not add a French curve or any tracer `Guide` does not have (blueprint §3 names ruler and
  ellipse; the enum is the contract).
- Never run gradle on the owner's PC.

## Definition of done

- [ ] tests pass (paste)
- [ ] `git status --short` shows only owner-area files
- [ ] watcher green
- [ ] owner check noted (phone + Tab S8)
- [ ] committed `JB-2.12: helpers`
- [ ] ROADMAP row → 🟧 Built

## Questions

_(Spec writer, `openrouter/stealth/space-bunny-alpha`, 2026-09-29.)_

### 🔴 For the Lead

1. **Guides are per SESSION and are NOT saved (Decision 2).** The blueprint's "never part of the art"
   is unambiguous about exports, and I have read it as also excluding the document — which means a
   perspective setup is rebuilt every session. **The other reading is real:** save the helper
   settings in the document but never export them, which is one more `DOC_VERSION` question and a
   rule nobody would break, because an exporter reads tiles and not settings. Which do you want?
   The cost of mine is the owner rebuilding a 3-vanishing-point setup every time he opens the file.
2. **Snapping ON by default (Decision 3).** Snapping is the reason the helpers exist, but it is also
   the reason a stroke can go somewhere a person did not mean, and JB-2.12a's Direction Guides
   commit a stroke for its whole length once they lock. On by default is Procreate's answer; off by
   default is "never surprise me". I went with on + one obvious off switch.
3. **The ellipse tracer's two axis handles + a centre dot (Decision 4).** JB-2.12a's `Guide` carries
   `center`, `rx`, `ry`, `rotation`, so a full editor is five handles (centre, two radii, rotation).
   I specced three. Is rotation on a tracer something you want, or is it noise on a phone?
4. **Depends on JB-2.01, which is Draft.** Both this row and 2.01 build panels. If 2.01's ruling on
   where panels live changes, this row's Helpers panel moves. Nothing else here depends on it — the
   overlay and the state are independent — so this row could be taken as soon as the Lead rules on
   2.01's question 2.

### Low-risk, ruled provisionally

5. **Grid spacing doubles to keep 8 screen px, never halves** (Decision 8).
6. **A tracer beats a direction guide** (Decision 1) — JB-2.12a Decision 4's rule, carried, not
   re-decided.
7. **The snap hairline is `GUIDE` violet 1 px, never `ARMED` cyan** (Decision 6): a snap line is a
   hint and cyan means "this is the thing you have selected".

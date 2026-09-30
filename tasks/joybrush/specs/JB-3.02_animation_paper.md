# JB-3.02 — Animation paper: the peg bar that doubles as buttons, and the pixel rulers (CORE HALF)

| | |
|---|---|
| **Tier** | T2 (pure maths in `:core`) |
| **Status** | 🟦 **Ready.** This is the **core half only** (the Lead's review: 🔧→🟦 "core half"). The view — the overlay `View`, the peg bar's placement, and the `JoyBrushActivity` wiring — is cut and named in **Cut from this spec** below. The core half does not wait for JB-2.01: it needs a board's rectangle and a zoom, and nothing else. |
| **Who** | original spec writer · **xr: openrouter/stealth/space-bunny-alpha 2026-09-29** — the review's four fixes applied and re-derived: **R32** (every constant is dp, multiplied by density at the use site; the draft wrote "44 dp" as `const val 88` and declared it "already × density", which is impossible because density is a runtime value), and the **corrected numbers** from the review's "Corrected numbers" section for tests 1, 2, 3, 4, 5 and 11 — every one of them now carries its derivation in the test, not just the answer. **R33**: the peg bar owns PLAY and MODE, the strip has prev/next, and there is **one** saturated control — now pinned by a core test instead of a view assertion. **A fifth wrong number found while re-deriving: the draft's `pegAt(0, 5, 600) = 0` is wrong on the draft's own centres** (212 px from the nearest peg, so −1). **A name collision found in the tree**: `cc.joycreator.joybrush.core.doc.Paper` already exists and is serialised, so the draft's `core.anim.Paper` is a Question (Q2) and the type is provisionally `PaperGeometry`. **Two tokens the draft names do not exist** (`ON_GO`; and `studio_action_pill` is an app drawable `androidkit` cannot see) — the core half names a *style* and the view maps it. Tests stayed in `commonTest`: none of them opens a file. |
| **Depends on** | JB-3.01 (Built 🟧) · the document model (JB-0.02, Built) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/anim/PaperGeometry.kt` · NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/anim/PaperGeometryTest.kt` · **nothing else.** Two files. |
| **Estimated size** | ~180 lines of core + ~260 lines of tests |
| **Command** | `./gradlew -p joybrush :core:jvmTest` — 0 failures. |

## Goal

Blueprint §1 idea 10, the "animation paper": a board of film pinned to a desk. Two things make it
read as *paper* rather than as a toolbar with a ruler on it, and both are here:

- **A peg bar that doubles as buttons.** The pegs of a music box are not decoration — pressing one is
  the only thing they do. **R33: the peg bar owns PLAY and MODE** (the owner's own idea: the pegs ARE
  the buttons), and the film strip has prev/next and nothing else. So this row fixes the set of pegs,
  their order, their geometry, their hit test and which one — exactly one — is allowed to look like an
  action.
- **Pixel rulers.** Two edges marked in *document* pixels, because on the animation board a pixel is
  the unit everything is measured in — a cell is 64 px, a hold is 3 ticks, a board is 512 px wide.
  Never an export, like every other helper (blueprint §3).

The frame-scoped controls are **JB-3.03's** and are not in this spec. The onion ghosts are
**JB-3.04's**. This row's owner area touches neither.

## Cut from this spec, and where it now lives

| Cut | Where it lives now |
|---|---|
| `PaperOverlayView` — the transparent `View`, its draw list, `OnPeg`, `refresh()` | **JB-3.02b — the Lead's row.** It is a view, and R30 item 1 puts `JoyBrushActivity.kt` in the chrome queue. |
| The `JoyBrushActivity.kt` edit (host the overlay, wire the callbacks) | **JB-3.02b.** R30 item 1 names 3.02 explicitly: it waits for JB-2.01's cluster. |
| **Where the peg bar sits** — the draft's Decision 13, "one 56 dp row docked to the bottom, above whatever JB-2.01 puts there" | **JB-3.02b, and Q1 is still the Lead's open question.** The draft's whole Status paragraph was gated on it ("Read Q1 before starting"); **that gate is gone for the core half, because the maths does not care where the bar is.** It is not gone for the view. |
| The rulers' 24 dp strips and hiding them while a stroke is in progress (draft Decision 14's behaviour) | **JB-3.02b.** The *number* stays in core as `RULER_THICKNESS_DP` — one number, one place, the same reason `PEG_HIT_RADIUS_DP` is 22 and not 14 (Decision 1) — while the drawing and the hiding are the view's. |
| The colour mapping — which token each `PegStyle` becomes | **JB-3.02b.** See Q5: two of the draft's three names do not exist. |
| Q2 — "should the ruler be draggable to move the board's origin", "should a centre line be drawn heavier" | **The Lead's, and still open.** Carried forward verbatim in **Questions**. They are gestures and a look, not maths, and the zero-tick-heavier half of the draft's answer stays in the view row. |
| The film strip's ± actions, drag-to-hold and scrub | **JB-3.03.** R33 also rules the strip's `+` = DUPLICATE, which is 3.03's to build. |

## Contract

```kotlin
package cc.joycreator.joybrush.core.anim

/**
 * How a peg is allowed to LOOK, by name only. **The mapping from a style to a colour token belongs to
 * the view** (JB-3.02b): `JbColors` lives in the app module, not in `core`, so a core type that named
 * a colour would not compile. `IDENTITY` is a fill in the board's own gradient, `STATE_RING` is a
 * raised fill with a state ring, `ACTION` is the app's action pill — and exactly one peg may be
 * `ACTION` (Decision 4).
 */
enum class PegStyle { IDENTITY, STATE_RING, ACTION }

/** The three press semantics blueprint §1(a) already ruled. Named in core; mapped in the view. */
enum class PegPress { TAP, LONG_PRESS, HOVER }

/**
 * The five board-level controls, in order (R33: the peg bar owns PLAY and MODE).
 * APPEND-ONLY if this ever grows, and a growth needs a layout review — Decision 2.
 */
enum class Peg { PLAY, MODE, ONION, CADENCE, EXPORT }

/**
 * The animation paper's geometry. Pure: no Android, no clock, no file.
 *
 * ## UNITS (R32) — the rule this file exists to enforce
 *
 * **Every constant below is in DP and is multiplied by `density` at the use site.** A `const val`
 * cannot be "already × density": density is a runtime value, so a constant that claimed to be scaled
 * would be scaled by whatever the machine that built it happened to be. The draft wrote "44 dp" as
 * `88f` with a comment saying it was already multiplied, which is both twice the size and
 * unachievable. The precedent is landed: `SizeOpacityDrag` keeps `SIZE_PER_DOUBLING_DP = 160f`,
 * `LOCK_TRAVEL_DP = 12f` and multiplies by its guarded `dp` field at each use
 * (`SizeOpacityDrag.kt:97, 125, 156-159`).
 *
 * A length that is a MEASURE is in DOCUMENT px and is an Int or a Long, because a coordinate on an
 * unbounded canvas is not a Float (R19).
 */
object PaperGeometry {

    /** Distance between peg centres, in dp: the house touch floor (R32). */
    const val PEG_PITCH_DP = 44f

    /** A peg's drawn radius, in dp. Half the pitch, so neighbouring pegs touch and never overlap. */
    const val PEG_RADIUS_DP = 14f

    /**
     * How close a finger-down must be to a peg's CENTRE, in dp, to press it. Deliberately larger
     * than the drawn radius: visual ≠ touch size (`JOYBRUSH_VISUAL_LANGUAGE.md` §1.9).
     */
    const val PEG_HIT_RADIUS_DP = 22f

    /** The finest on-screen spacing a ruler tick may have, in dp (Decision 5). */
    const val MIN_LABEL_DP = 48f

    /** Thickness of a ruler strip, in dp. The number is here so it is typed once; the strip is 3.02b's. */
    const val RULER_THICKNESS_DP = 24f

    /**
     * Where each peg sits, left to right, in a bar [barWidthPx] screen px wide, at [density].
     *
     * ALWAYS [count] values, ascending, and the block is ALWAYS centred on `barWidthPx / 2`. A
     * `count` below 1 is treated as 1. Never a non-finite number.
     */
    fun pegCentres(count: Int, barWidthPx: Float, density: Float = 1f): FloatArray

    /**
     * The index of the peg whose CENTRE is within [PEG_HIT_RADIUS_DP] × [density] of [xPx], or -1.
     * An equal distance to two pegs goes to the LOWER index. `count` is coerced exactly as
     * [pegCentres] coerces it, so a hit test can never disagree with the layout it hits against.
     */
    fun pegAt(xPx: Float, count: Int, barWidthPx: Float, density: Float = 1f): Int

    /**
     * The pixel rulers' tick positions along one axis, as DOCUMENT px RELATIVE TO THE BOARD'S
     * TOP-LEFT CORNER, ascending, for every tick whose position lies within
     * `[viewStartDoc, viewEndDoc]`. Both bounds and [boardOriginDoc] are document px
     * (`board.rect.x.toDouble()` and so on).
     *
     * [screenPerDoc] is `ViewTransform.zoom` — screen px per document px, never its inverse (R19,
     * JB-2.16a). The step is the finest of 1/2/5 × 10^k whose on-screen spacing is at least
     * [MIN_LABEL_DP] × [density] (Decision 5).
     *
     * A non-finite or ≤ 0 [screenPerDoc], a non-finite or inverted view, or a non-finite
     * [boardOriginDoc] all give an **empty array**, never an exception.
     */
    fun ticks(viewStartDoc: Double, viewEndDoc: Double, boardOriginDoc: Double,
              screenPerDoc: Float, density: Float = 1f): LongArray

    /** The label a tick prints. Whole pixels, no decimal point, no thousands separator, no unit. */
    fun label(tickDocRelToOrigin: Long): String

    /**
     * How a peg is allowed to look, given what is switched on. The one place the "one saturated
     * control" rule exists, so it is a test and not a review comment (Decision 4).
     */
    fun style(peg: Peg, active: Boolean): PegStyle
}
```

**Naming note (Q2, provisional):** the type is `PaperGeometry` and not `Paper` because
`cc.joycreator.joybrush.core.doc.Paper` **already exists** (`DocModel.kt:38-43`, `@Serializable`,
four fields, the document's paper *setting*). Two `Paper`s in one module is legal Kotlin and still a
trap: any file that imports both — and the animation board's host very likely already touches
`doc.paper.color` — gets an ambiguity, and the loser is whichever import a builder deletes. Rename if
you prefer; it is one word in three places.

## Decisions

1. **Every layout constant is dp, and `density` is multiplied in at the use site (R32).** `density`
   is guarded the way `SizeOpacityDrag` guards its own: not finite or ≤ 0 → `1f`
   (`SizeOpacityDrag.kt:58`). A zero density divides by zero in the tolerance and turns the whole bar
   into NaN, which is a bug that only appears on one device class.
2. **The peg bar carries exactly five pegs, in this order: `PLAY, MODE, ONION, CADENCE, EXPORT`.**
   Fixed, not a list the host may extend: a peg bar whose contents grow is a peg bar nobody can
   design, and the order *is* the layout. **R33: this bar owns PLAY and MODE** — the pegs are the
   buttons — and the film strip has prev/next and nothing else, so there is exactly one place on the
   animation board that can start playback. The frame-level controls (± add / duplicate / link /
   delete, hold ±) are JB-3.03's.
3. **Press semantics are blueprint §1(a)'s and are not re-decided here: tap = the action, long-press
   = that peg's options, hover (pen or mouse) = its label.** There is no hover menu, because hover
   does not exist for fingers. Core names the three cases ([PegPress]); turning a `MotionEvent` into
   one is 3.02b's.
4. **`PLAY` is the ONE saturated control on the animation board, and `style()` is where that is
   enforced.** `style(PLAY, active = true) == ACTION`; `style(PLAY, active = false) == STATE_RING` —
   a Play button that looks identical whether it is playing is a control that lies. `ONION` wears
   `IDENTITY` while onion skin is on and `STATE_RING` while off. `MODE`, `CADENCE` and `EXPORT` are
   `IDENTITY` either way. *Why a core function and not a view assertion:* the draft pinned this with
   a test on the view's draw list, which is a test the Lead's row has to write; putting the rule where
   the enum lives makes it a test a free model can write, and a builder who adds a sixth peg gets a
   red test instead of a second gradient.
5. **A peg is a FILL, never a ring, unless it is showing a state.** A coloured ring already means a
   state, and a state colour must never be a section accent — D.01's rule and
   `JOYBRUSH_VISUAL_LANGUAGE.md` §4.3. That is why the enum is a *style* and not a colour: the
   colours are the Lead's to bind (Q5).
6. **Pegs are centred as a block, and the pitch shrinks rather than the bar clipping.** The pitch is
   [PEG_PITCH_DP] × density unless the block will not fit, and then it is `barWidthPx / count`,
   **never below 2 × [PEG_RADIUS_DP] × density**; the first centre is `barWidthPx / 2 −
   (count − 1) × pitch / 2` in *both* cases, so the block is centred either way. A peg scrolled off
   the end of the bar is a control that exists and cannot be reached. The floor is a physical
   statement — two pegs closer than their own diameter would overlap — so a bar too narrow even for
   the floor is a bar that must scroll, and the block then overflows **symmetrically** rather than to
   the right.
7. **Hit-testing is against the peg's CENTRE within [PEG_HIT_RADIUS_DP] × density, ties to the LOWER
   index.** Against the centre and not the drawn disc, because the disc is 14 dp and a phone target
   is 22 dp and above. Ties to the lower index because a finger exactly between two pegs is nearer to
   the one it came from, and "nearest" must not depend on scan order.
8. **Rulers are DOCUMENT pixels measured from the ACTIVE BOARD'S top-left corner, not the
   document's (0,0).** On a board you measure a frame, and a frame's corner is the board's corner. A
   board may sit at a negative x or y on the unbounded canvas, and a ruler reading −1400 would be
   true and useless.
9. **The ladder is 1 / 2 / 5 × 10^k, choosing the FINEST step whose ON-SCREEN spacing is at least
   [MIN_LABEL_DP] × density — and the ladder starts at 1, never below.** Not "round the step": the
   step is compared against the ladder, so no rounding epsilon can put a tick half a label off.
   Starting at 1 is what makes the returned `Long` honest — a tick is a whole document pixel, and a
   half-pixel ruler is a rendering bug waiting for a device with a 1.5 density. A non-finite or
   inverted input gives no ticks, because a ruler drawn with a broken zoom is a grey block.
10. **Ticks are at `boardOrigin + k × step` for every integer k, NEGATIVE included.** A tick is
    therefore at or BELOW a negative coordinate, never at the origin plus a positive count: at step 2,
    a view running from −3 has a tick at −2. This is the same floor-not-truncate bug the project keeps
    meeting at a seam, and on a ruler it is visible.
11. **There is no cap on the tick count, and the ladder is why one is not needed.** Adjacent ticks
    are at least 48 dp apart on screen, so a view `W` screen px wide holds at most `W / (48 × density)
    + 1` of them — about 43 on a 2000 px phone. The draft's test 5 wanted "≤ 2" and was wrong by an
    order of magnitude; the corrected derivation is in the tests.
12. **The zoom is read per frame and never cached, and this row cannot cache it anyway** — the
    functions take the zoom as an argument. A zoom therefore re-ladders the ruler with no state to
    invalidate, which is the whole of the draft's Decision 12 in one parameter.
13. **This type is NOT `guide.Guide.Ruler` and says nothing about a pen tracer.** `Guide.Ruler`
    (JB-2.12a, Built) is a *magnetic straight edge the pen runs along*; this is a measuring scale.
    Same word, opposite thing, and a builder who finds `Guide.Ruler` first will wire the wrong one.
    See Q4.
14. **Rulers and pegs are overlays and are never in an export.** True by construction — they are a
    `View` over the canvas, not a layer, and this row adds **no field to the document at all** (so
    no `DOC_VERSION` bump, and R31's unknown-key rule is untouched). Test 16 pins the first half from
    `core` anyway, because a builder who later decides to put a ruler in a layer should have to
    delete the test to do it.

## Tests

**`joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/anim/PaperGeometryTest.kt`** —
**`commonTest`, and none of these moves to `jvmTest`.** The standing rule (JB-1.07's Decision 8) is
that a test which opens a file is a `jvmTest` test, because `commonTest` cannot. **No test in this
suite opens a file:** they are arithmetic on numbers, plus one that calls `RegionRenderer.render` with
an in-memory `TileSource` — and that is exactly how the landed `RegionRendererTest` does it, in
`commonTest`, because `TileSource` is a `fun interface` (`RegionRenderer.kt:24-26`). The three view
tests the draft had (`thePegBarIsOneRowAtTheBottom`, `theRulersHideWhileAStrokeIsInProgress`,
`aPegCallbackCarriesThePegAndNothingElse`) are cut with the view and are listed by name in **Cut from
this spec** so nobody goes looking for them.

Density is 1 and zoom 1 unless a test says otherwise. Every expected value below is derived in the
test's own comment, because a corrected number with no derivation is a number the next reader has to
trust.

1. **`pegCentres`, 600 px bar, 5 pegs, density 1 → `212, 256, 300, 344, 388`.**
   Derivation: the preferred pitch is 44 and the block spans `4 × 44 = 176`; with `176 + 2 × 14 = 204
   ≤ 600` the pitch stays 44; the first centre is `300 − 176 / 2 = 212`, the rest 44 apart, the last
   `212 + 176 = 388 = 300 + 88`. **Symmetric about 300.** (The draft's `256, 300, 344, 388, 432` is
   not centred on 300 — it is centred on 344 — and it does not follow from its own pitch of 88
   either; with the old 88 pitch the correct centres would have been `124, 212, 300, 388, 476`.) With
   1 peg: exactly one, at 300. With 0 and with −3: one peg at 300.
2. **`pegCentres`, 200 px bar, 5 pegs, density 1 → pitch 40, `20, 60, 100, 140, 180`.**
   Derivation: `176 + 28 = 204 > 200`, so the pitch shrinks to `200 / 5 = 40`; the floor is
   `2 × 14 = 28` and `40 ≥ 28`, so the shrink is legal; the first centre is `100 − 4 × 40 / 2 = 20`.
   Assert all three of: no centre outside `[0, 200]`, the first `≥ 14` and the last `≤ 186` (so no peg
   is clipped), and the pitch `≥ 28`. Also: a 28 px bar, 5 pegs → the floor binds, pitch 28, and the
   block overflows **symmetrically** about 14 (centres `−42, −14, 14, 42, 70`) rather than running off
   the right — that last assertion is Decision 6's whole content.
3. **`pegAt`, 600 px bar, 5 pegs, density 1 — against the centres from test 1.**
   - `pegAt(300, …) = 2` (dead centre of peg 2).
   - `pegAt(322, …) = 2` — 22 from peg 2 (at 300) and 22 from peg 3 (at 344), the hit radius exactly,
     so it is a tie and the **lower index wins**. *(This is the review's corrected value; the draft
     said `pegAt(300 ± 44) = 2 and 2` on 88-px radii, which no longer exists.)*
   - `pegAt(323, …) = 3` — 23 from peg 2 is out, 21 from peg 3 is in.
   - `pegAt(190, …) = 0` (exactly 22 from 212) and `pegAt(189.9f, …) = −1` (21.9 — just inside the
     radius would still be a hit, so the boundary case is the one *outside* it that matters).
   - `pegAt(278, …) = 1` — 22 from 256 and 22 from 300: the same tie one gap further on.
   - `pegAt(0, …) = −1` — **the nearest centre is 212 px away, not 22.** *(The draft asserted `0`
     here, and it was wrong on the draft's own centres too: 256 px from the nearest of
     `256, 300, 344, 388, 432`.)*
   - `pegAt(−1f, …)`, `pegAt(1e9f, …)`, `pegAt(Float.NaN, …)` = `−1`, no exception.
4. **Density is applied at the use site, not baked into a constant.** 600 px bar, 5 pegs, **density 3**:
   pitch `44 × 3 = 132`, radius `42`, hit radius `66`. Derivation: `4 × 132 = 528` and
   `528 + 84 = 612 > 600`, so the pitch shrinks to `600 / 5 = 120`, above the floor `2 × 42 = 84`;
   first centre `300 − 240 = 60` → `60, 180, 300, 420, 540`. Then `pegAt(180) = 1`,
   `pegAt(246) = 1` (exactly 66 from 180), `pegAt(300 − 66 = 234) = 1` (54 from peg 1, 66 from peg 2
   — a tie, lower index), and `pegAt(247) = 2`. At density 1 the same bar gives 212…388, so the two
   densities cannot be the same code with a different literal.
5. **`ticks` at zoom 1, density 3 → step 200.** Derivation: the floor is `48 × 3 = 144` screen px; a
   step of 100 is `100 × 1 = 100` screen px, which is short; 200 is `200 ≥ 144`; 250 and 300 are not on
   the ladder, so **200 is the finest that fits**. *(The draft's headline said 100, which its own
   parenthetical arithmetic contradicts.)* Over a doc view `0..600` on a board at 0 the ticks are
   exactly `0, 200, 400, 600` — four of them — and every one is a multiple of 200, so 150 and 175 are
   impossible by construction.
6. **`ticks` at zoom 0.05 (`ViewTransform.MIN_ZOOM`), density 1 → step 1000, and the count is 12
   steps, not "≤ 2".** Derivation: floor 48 screen px; a step of `s` occupies `s × 0.05` screen px, so
   `s ≥ 960`; on the ladder, 500 gives 25 (too close) and 1000 gives 50 (fits), so **1000**. A
   600-screen-px view at that zoom spans `600 / 0.05 = 12 000` document px = **12 step intervals**
   (13 ruled lines if both ends are counted). *(The draft asserted "the tick COUNT is ≤ 2 for a 600 px
   view", which is not what the ladder gives; the corrected figure is 12 and the reason there is no
   cap is Decision 11.)* Assert the array has 13 entries for a closed view and that they are
   `0, 1000, … 12 000`.
7. **`ticks` at zoom 64 (`ViewTransform.MAX_ZOOM`) → step 1.** Derivation: `s × 64 ≥ 48` gives
   `s ≥ 0.75`; the ladder starts at 1 (Decision 9), so the step is 1 and every document pixel in view
   is a tick.
8. **Garbage in, nothing out.** `screenPerDoc` of `0f`, `−1f`, `Float.NaN`, `Float.POSITIVE_INFINITY`
   → an empty array, no exception. A view of `600..0` (inverted) → empty. `Double.NaN` in any of the
   three document arguments → empty. `density` of `0f` or `−1f` → treated as 1, so the step is the
   density-1 answer and not an exception.
9. **Negative `k` floors, they do not truncate.** Step 2 needs `2 × screenPerDoc ≥ 48`, so the test
   uses `screenPerDoc = 24f` (1 × 24 = 24 is short, 2 × 24 = 48 fits exactly). A doc view of `−3..3`
   relative to the board gives exactly `−2, 0, 2`; assert that **`−2` is present**, because `k ≥ 0`
   would have produced `0, 2` and a view that starts at a negative coordinate would have no tick near
   its left edge.
10. **`label`:** `0` → `"0"`, `−1400` → `"-1400"`, `512` → `"512"`, `Long.MIN_VALUE` →
    `"-9223372036854775808"`. No decimal point, no comma, no unit, and no `-0`.
11. **The ruler is measured from the board, not the document.** Two boards, at `(0, 0)` and
    `(−1400, 300)`, with views chosen so both cover board-relative `−3..3`, give **identical arrays**.
    A document-relative ruler would differ by 1400 on x and 300 on y and fail.
12. **A zoom change re-ladders the ruler; the step is a function of the zoom, not of history.**
    Density 1, one fixed view, called twice: at zoom 1 the step is **50** (20 × 1 = 20 < 48, 50 ≥ 48)
    and at zoom 0.1 it is **500** (500 × 0.1 = 50 ≥ 48, while 50 × 0.1 = 5 does not). Then the same
    two calls at **density 2** (floor 96) give **100 and 1000**. *(These are the review's corrected
    values: the draft claimed 100 and 1000 at density 1, which is the density-2 answer written on the
    density-1 test — the unit slip showing up in a test table.)*
13. **Every step is on the ladder.** 40 zooms × 3 densities × 3 board origins: the returned step
    divided by its largest power of ten is 1, 2 or 5, and the step is ≥ 1. `0.03` and `7` are
    impossible by construction, and this is the test that says so.
14. **One saturated control, and it is PLAY.** For all five pegs × both values of `active`: the
    number of `PegStyle.ACTION` results is exactly 1, and it is `style(PLAY, true)`. Also
    `style(PLAY, false) == STATE_RING`, `style(ONION, true) == IDENTITY`,
    `style(ONION, false) == STATE_RING`, and `style(MODE | CADENCE | EXPORT, _) == IDENTITY`.
    Enumerate the enum in the test, so a sixth peg turns this red instead of quietly adding a
    gradient.
15. **The peg set is the five, in that order.** `Peg.entries.map { it.name }` equals
    `["PLAY", "MODE", "ONION", "CADENCE", "EXPORT"]` — the house idiom from `EnumFreezeTest`
    (`:41`), asserted against the whole list so a reorder or a rename fails. This is R33's "the peg
    bar owns PLAY and MODE" made checkable, and the assertion that nothing here is a transport
    control: there is no loop peg, no stop peg and no prev/next peg, because the strip has prev/next
    and this bar has play (JB-3.05's Decisions 13–14 were deleted for exactly this reason). **Do not
    add `Peg` to `EnumFreezeTest`** — that file pins the *serialised* enums for R3's version rule
    (`EnumFreezeTest.kt:14-32`), `Peg` never reaches a file, and that file is outside this row's
    owner area.
16. **Helpers are never in an export.** Render the same one-layer document with
    `RegionRenderer.render(doc, tileSource, rect, frameId = null, paper = null)` twice — once plainly
    and once after computing `pegCentres` and `ticks` for that board — and assert the two byte arrays
    are **identical**. `TileSource` is a `fun interface`, so the fake is one line. This is what pins
    "the ruler is a View and never a tile", from `core`, without a device.

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures. (In a worktree of your own, per
R43; never on the owner's PC.)

## Do not

- **Do not touch `DocModel.kt`, `DocJson.kt` or `JbArchive.kt`.** This row adds no field to the
  document; the peg bar and the rulers are screen things and stay screen things. No `DOC_VERSION` bump
  (R30 item 3 assigns versions at landing, and there is nothing here to version).
- **Do not edit `JoyBrushActivity.kt`, any other app file, or create a `View`.** The owner area is
  two new files in `:core`. The view is JB-3.02b, and R30 item 1 queues 3.02 behind JB-2.01.
- **Do not write a `const val` in pixels.** A constant in `core` is dp, and `density` is multiplied at
  the use site (R32, Decision 1). This is the exact slip the draft made and the review caught.
- **Do not describe, or plan around, a second saturated control.** R33: the peg bar owns PLAY and
  MODE; the strip has prev/next. One `ACTION` peg, and test 14 will tell you if you have two.
- Do not re-type `48f`, `44f`, `14f` or `22f` anywhere else; they are public here so the view can
  read them.
- Do not put a file-reading test in this suite. It does not read files; if you add one that does, it
  belongs in `jvmTest` and probably in another row.
- Do not add a `GestureDetector`, a `MotionEvent`, a `Context`, a `Paint` or a `Drawable` to
  `commonMain`. If you feel the need for one, that is the proof that JB-3.02b's work has leaked in.
- Do not add a hover menu (Decision 3), a rotate, or a scrollable bar. A bar that scrolls is a
  consequence of too many pegs, and there are five.

## Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest` output pasted, 0 failures
- [ ] `git status --short` shows exactly the two owner-area paths, both `A` (added), nothing else
- [ ] the file has no import outside `cc.joycreator.joybrush.core` and `kotlin.math`
- [ ] `git diff --cached --stat` shows the two files and no `build.gradle.kts`
- [ ] committed `JB-3.02: animation paper core (peg bar geometry, ruler ladder)`
- [ ] ROADMAP row set by the Lead (this row does not edit ROADMAP.md)

## Stop rule

**Stop and write in Questions; do not guess, if any of these is true when you start:**

- `MIN_LABEL_DP = 48f` needs to be something else, or the ladder needs to start below 1. Both are the
  Lead's (Q6) — the second one changes what a `Long` tick means, which is a contract.
- `pegCentres` has to return fewer pegs than asked for, or a peg may be clipped. Decision 6 says no,
  and if you cannot honour that in a narrow bar, write down the bar width where it breaks.
- A test needs `ViewTransform.MIN_ZOOM` or `MAX_ZOOM` to be different from 0.05 and 64. Those are
  landed (`ViewTransform.kt:156, 159`); if they have moved, the derived numbers in tests 6 and 7 move
  with them and the Lead should know the table changed.
- You think a peg needs to be pressable by something other than a centre hit test — a lasso, a
  drag-to-press, a hover-only button. That is a gesture and belongs in JB-3.02b.

## Questions

_(Spec writer: `openrouter/stealth/space-bunny-alpha`, 2026-09-29. The draft's Q1 (placement), Q2
(rulers) and Q3 (the `Ruler` name) are carried forward; Q2, Q4 and Q5 are new and come from reading
the tree, and Q6 asks the one thing R32 did not say.)_

### 🔴 For the Lead

1. **Where does the peg bar sit, and does JB-2.01 have to exist first?** *(the draft's Q1, kept
   because the core half does not answer it)* The ROADMAP row says **Needs 3.01, 2.01**, and **JB-2.01
   ("Screen chrome") is `⚪ Outline` with no spec**, and its own needs (JB-0.09 `⚪ Outline`, D.02
   Lead-owned and not dispatched) are further out still. The peg bar *is* a button row, so "build the
   animation board's buttons before the screen chrome exists" is a real ordering question. The draft
   ruled provisionally, following the precedent D.02c set: the peg bar is one 56 dp row docked to the
   bottom of the canvas, and JB-2.01 re-hosts the same overlay when it lands. R30 item 1 says 3.02
   waits for JB-2.01's cluster — that is a reason **the view** is not dispatchable, not a reason the
   maths is not, and this row's core half no longer depends on the answer.
   - Is the provisional placement accepted, or does **JB-3.02b** wait for JB-2.01?
   - If it waits, is the row's "Needs" right? A peg bar that *is* the animation board's button row
     arguably does not need the general chrome at all — it needs a slot for it.
   - Does the peg bar persist when the UI is hidden (4-finger tap, JB-2.02)? The draft's ruling was
     **no**: the peg bar is chrome, and hidden chrome is hidden chrome.
2. **`core.anim.Paper` collides with the landed `core.doc.Paper`, so the type is provisionally
   `PaperGeometry`.** `DocModel.kt:38-43` declares `@Serializable data class Paper(val color, val
   textureId, val textureScale, val includeInExport)` and `JbDocument.paper` holds one. The draft
   named its new type `Paper` and argued for the name (Q4 below). I renamed it because the animation
   board's host is exactly the file that already reads `doc.paper.color`, and an ambiguous import
   there gets resolved by whoever is in a hurry. **If you want `Paper`, say so and it is one word in
   three places** — nothing depends on it yet. If you would rather the word went the other way,
   renaming `Guide.Ruler` (Q4) does not help: the collision is with `doc.Paper`, not with it.
3. **Q2 — "pixel rulers": is one ruler enough, and what about a centre line?** *(carried forward
   verbatim)* Blueprint §1 idea 10 says "pixel rulers" without saying how many or where. I ruled
   **two strips, top and left, document pixels from the board's top-left** (Decisions 8, 9 and the
   cut list). Two questions I did not decide because they are product, not maths:
   - **Should the rulers be draggable to move the board's origin** (the old Photoshop behaviour, where
     you grab the ruler and slide the page)? It is a lovely idea and it is a *gesture on the canvas*,
     which is the one place this project has already decided fingers navigate and never draw (owner's
     ruling, and R5). My answer would be "no, and never on the ruler either", but it is yours.
   - **Should a 0-th and centre line be drawn heavier**, or is the zero tick enough? I ruled
     zero-only, because a centre line on a board that is not an odd number of cells is a lie about
     where the middle is. *(The "zero tick is drawn heavier" half is a view statement and now lives
     in JB-3.02b; the maths it needs is the board-relative zero, which is decided and pinned by test
     11.)*
4. **`PixelRuler` vs `Guide.Ruler` — the vocabulary collision is still yours to settle.**
   *(carried forward verbatim)* `guide.Guide.Ruler` from JB-2.12a is the *tracer* ruler a pen runs
   along; it already exists, is already tested, and is a completely different object. Decision 13
   forbids confusing them, but the collision is in the *vocabulary*: the blueprint lists "ruler" in
   §1 idea 10 (pixel scale) and §3 (pen tracer) in the same document, and JB-2.12a's KDoc quotes the
   second. **Is `PaperGeometry` the naming you want?** The alternative is to rename JB-2.12a's
   `Guide.Ruler` to `Guide.StraightEdge` — a change to a reviewed spec's contract, outside this row's
   owner area, so I did not do it. I would rather you renamed it in one edit than have two `Ruler`s
   in the codebase forever.
5. **Two of the three colour names in the draft do not exist, and the third is not `core`'s to name.**
   *(new)* The draft's Decisions 3 and 4 dress pegs in `ON_GO` and `studio_action_pill`. I read
   `JbColors.kt`: the `Palette` class (`:116-155`) has **no `ON_GO`**, and there is no such token
   anywhere in the tree. `studio_action_pill` **is** real, but it is an app drawable
   (`app/src/main/res/drawable/`, used by `Studio.java:139` and `SheetKit.java:598`) — and
   `JbColors`'s own KDoc says why `androidkit` cannot simply reach it: "an Android library cannot see
   the consuming app's resources, and `tools/check_joybrush_tokens.py` fails the build's review if a
   copy drifts." So the `ACTION` gradient is a **host or D.02** decision. That is why this row pins
   `PegStyle` — a name with no colour in it — and hands the binding to JB-3.02b. **Which token pair
   should `ACTION` and `IDENTITY` actually be on the phone?** The obvious candidates from the landed
   `Palette` are `boardAnimationStart/End` for `IDENTITY` (that is what `boardGradient(context,
   BoardKind.ANIMATION)` returns) and `stateLive` for a `STATE_RING`'s ring colour. I have not ruled
   it, because it is a look decision and a phone one.
6. **Is 48 dp the right minimum label spacing, and may the ladder start below 1?** *(new)* R32 fixed
   the five geometry constants and did not mention the ruler's label floor, so the draft's 48 dp
   stands — and the review's own corrected test 4 uses 144 px at density 3, which is 48 × 3, so the
   authority is consistent with keeping it. The step in that test is 200 under either reading (100 is
   short of both 132 and 144), so **test 5's answer does not depend on this**. The second half is
   sharper: the ladder starts at 1 so that a tick is a whole document pixel and the return type can be
   `Long` (R19). If a future device needed a half-pixel ruler, that is a `Double` return and a
   contract change, and it should be your decision rather than a builder's.

### Low-risk, ruled provisionally (PROVISIONAL — Claude to confirm)

7. **The type is `PaperGeometry` and the file is `PaperGeometry.kt`** (Q2).
8. **Top-level `Peg`, `PegPress` and `PegStyle` in the same file**, rather than nested inside the
   object, so the view writes `Peg.PLAY` and not `PaperGeometry.Peg.PLAY`. `Peg` and `PegStyle` do not
   collide with anything in the tree (checked).
9. **The tick bounds are `Double` and the returned ticks are `Long`**, with the board origin arriving
   as `board.rect.x.toDouble()`. `RectPx` is `Int` (`DocModel.kt:36`), so a `Long` origin would work
   too; `Double` is what the draft had and it makes the negative-origin test read as arithmetic
   rather than as casts.
10. **`RULER_THICKNESS_DP = 24f` is published from core even though the strip is not drawn here** — so
    the number exists once and the view reads it (the same reason `PEG_HIT_RADIUS_DP` is 22 and not
    14).

# JB-5.03 — Reshape, re-weight and re-brush a stroke you have already drawn

| | |
|---|---|
| **Tier** | T2 |
| **Status** | 🟦 Ready |
| **xr** | stealth/space-bunny-alpha 2026-09-29 — Lead review fixes applied: the `dy: Float` contract typo resolved to `Double` throughout (Decision 3, with the reasoning and the landed `StrokeEdit.reshape` signature pasted), test 8's three wrong falloff numbers replaced with the derived `(1−u²)²` figures, R37's Q1-Q4 answers inlined as Decisions 9/4/13 and *Not in this spec*. |
| **Needs** | JB-5.01 (`InkReplay`, `InkRaster` — Built first; this is the only hard dependency), JB-5.03a (`StrokeEdit`, `StrokeRecord` v2 — **already Built**), JB-5.02 (`StrokePicker` — already Built) |
| **Owner area** | (1) NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/vector/InkEditSession.kt` · (2) NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/vector/InkEditSessionTest.kt` · **NOTHING ELSE.** Not `stroke/`, not `InkReplay.kt`, not `InkRaster.kt`, not `StrokePicker.kt`, not `InkGeometry.kt` — all four are consumed, never edited. |
| **Estimated size** | ~240 lines of code, ~260 lines of tests |
| **Command** | `./gradlew -p joybrush :core:jvmTest` — `BUILD SUCCESSFUL`, 0 failures in `joybrush/core/build/test-results/jvmTest/` |

## Goal

R20, verbatim: *"Every vector brush is swappable after drawing (owner; 'as in Concepts'). On an INK
layer a stroke is its recording, so selecting one or MANY strokes and picking another pen redraws them
as if drawn with that pen: pencil → fill pen gives the solid shape (ends joined), fill pen → pencil
gives the outline stroke."*

JB-5.03a built the three pure edits. This spec is the **session** that turns a finger into them: what
is selected, what a drag does, what one undo step covers, and what happens when the brush you picked
cannot draw an ink line. It is a pure state machine in `:core` with no Android in it, so every rule
below is a test rather than a manual check. The view wiring is **not here** (see *Not in this spec*).

## Contract (verbatim)

### What this spec calls, pasted from the landed code

`StrokeEdit` — `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/stroke/StrokeEdit.kt`:

```kotlin
object StrokeEdit {

    /** The narrowest a re-weighted line may get: below this it is a scratch, not a line. */
    const val MIN_WIDTH_SCALE = 0.05f

    /** The widest. [StrokeCodec] reads this same constant, so the file and the edit cannot disagree. */
    const val MAX_WIDTH_SCALE = 20f

    /**
     * The engines that may draw an INK layer, in R20's words: … Only [INK_ENGINES] may be named
     * here in practice; a `smudge`/`wet` brush cannot be refused by this function, because it is
     * handed a brush ID and knows nothing about engines. The caller that has the
     * [BrushPreset] asks [drawsInkLines] and says the refusal in words (R20).
     */
    val INK_ENGINES: Set<String> = setOf("stamp", ENGINE_FILL)

    /** Whether a brush with this `engine` can draw an ink line. See [INK_ENGINES]. */
    fun drawsInkLines(engine: String): Boolean = engine in INK_ENGINES

    fun reshape(
        r: StrokeRecord,
        grabX: Float,
        grabY: Float,
        dx: Float,
        dy: Float,
        radius: Float,
    ): StrokeRecord

    fun reweight(r: StrokeRecord, factor: Float): StrokeRecord

    fun rebrush(r: StrokeRecord, brushId: String): StrokeRecord = r.copy(brushId = brushId)

    fun recolor(r: StrokeRecord, argb: Int): StrokeRecord = r.copy(colorArgb = argb)
}
```

`reshape`'s falloff, from its own KDoc, is the arithmetic test 8 rests on:

> The falloff is `w = (1 − (s/R)²)²` for arc length `s < R` and 0 beyond, where `s` is measured along
> the stroke from the grabbed sample in BOTH directions.

**`stroke/StrokeEdit.kt` is READ-ONLY for this spec.** If a signature above is wrong, it goes in
Questions; the builder does not change it.

### The new file

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

    /** The undo stack, oldest first. Capped at [MAX_UNDO_STEPS]; the oldest steps are dropped. */
    val undo: List<InkEditStep>

    /** The lines the picker sees. One [InkLine] per record that can be replayed, in drawing order. */
    fun lines(brushOf: (StrokeRecord) -> BrushPreset?): List<InkLine>

    /**
     * A tap: pick the line under (x, y) doc px and select it alone (Decision 2).
     * @return [Changed] with the new selection, [NoChange] when the tap means nothing, or
     *   [Refused] for a coordinate that is not a number.
     */
    fun tap(
        x: Double, y: Double, timeMs: Double, screenPerDoc: Double,
        brushOf: (StrokeRecord) -> BrushPreset?,
    ): InkEditResult

    /** A rubber band around (x0,y0)-(x1,y1) doc px: every line it touches joins the selection. */
    fun band(x0: Double, y0: Double, x1: Double, y1: Double, brushOf: (StrokeRecord) -> BrushPreset?): InkEditResult

    /**
     * Drag begins: remembers the pre-drag records of everything selected, and fixes THE grab point
     * (Decision 4) and the reach (Decision 5).
     *
     * @return [Changed] with the current records so the caller can re-render the halo, or
     *   [NoChange] when nothing is selected — a drag with no selection is not a refusal, it is a
     *   drag that cannot reshape anything, and the caller is told so by a result it can ignore.
     * @throws IllegalArgumentException if [screenPerDoc] is not a positive finite zoom. That is a
     *   caller bug with no sensible fallback inside a gesture, and it is checked here so it cannot
     *   reach `radius` as `NaN` and silently reshape nothing forever. ([StrokePicker] reads a bad
     *   zoom as 1:1 because a *tolerance* still has a usable answer at 1:1; a *division* does not.)
     */
    fun beginDrag(grabX: Double, grabY: Double, screenPerDoc: Double): InkEditResult

    /**
     * Drag moves by (dx, dy) doc px. Reshapes about THE grab point fixed by [beginDrag] and about
     * nothing else, so every selected line follows the finger together.
     *
     * [dx] and [dy] are [Double] because every other coordinate in this class is; they are
     * narrowed to [Float] at the [StrokeEdit.reshape] call (Decision 3).
     *
     * @return [Changed] with the reshaped records, or [NoChange] when there is no drag in progress.
     *   A non-finite `dx`/`dy` is [NoChange]: [StrokeEdit.reshape] returns the record untouched for
     *   one, and the session must not report a change it did not make.
     */
    fun drag(dx: Double, dy: Double): InkEditResult

    /** Drag ends: commits ONE undo step. Returns [NoChange] when nothing moved. */
    fun endDrag(): InkEditResult

    /** The finger or pen went down a second time: the whole drag is undone and the session forgets it. */
    fun cancelDrag(): InkEditResult

    /** Multiply every selected line's `widthScale` by [factor]. One undo step. */
    fun reweight(factor: Float): InkEditResult

    /**
     * Re-draw every selected line with [brush]. ONE undo step for all of them (R20).
     *
     * The refusal is [InkEditSession.rebrushRefusal]'s, verbatim, and it is total: a refused brush
     * leaves the drawing byte-identical and the selection unchanged.
     */
    fun rebrush(brush: BrushPreset): InkEditResult

    /** Recolour every selected line. One undo step. */
    fun recolor(argb: Int): InkEditResult

    /** Empty the selection. Never a change to the drawing, so never an undo step. */
    fun clearSelection()

    companion object {
        /** RULED (R37, Q3): 50 steps. See [MAX_UNDO_STEPS]'s KDoc for the reasoning and the number. */
        const val MAX_UNDO_STEPS = 50

        /**
         * Why [brush] cannot re-brush an ink line, in words, or `null` when it can.
         *
         * RULED (R37, Q1): the brush picker asks THIS, at pick time, and greys the brush out with
         * the sentence. It is a companion function precisely so the picker can ask it without
         * building a session and without a selection — the UI's job is to grey a brush, and it must
         * be able to do that before anything is selected.
         *
         * The decision is [StrokeEdit.drawsInkLines], not a list of engine names written here.
         *
         * The sentence names the brush and the reason, so a person is not left looking at a greyed
         * row with no explanation:
         *
         *     "Smudge reads the pixels under the pen, and an ink layer has none — it holds the
         *      strokes. Try Ink, Pencil or the Fill pen."
         */
        fun rebrushRefusal(brush: BrushPreset): String?
    }
}
```

## Decisions

1. **The session owns the records and nothing else.** It holds a `Map<String, StrokeRecord>` in
   drawing order and applies `StrokeEdit`'s four pure functions. It does not replay dabs beyond what
   `lines()` needs, does not raster, and does not know about `GlPaintEngine` or undo-in-tiles.
   `JB-0.01`'s review established that the engine's undo stack holds tile changes and **never a
   `StrokeRecord`**, so an ink edit needs its own undo and this is where it lives — as
   `InkEditStep`, records and nothing else.

2. **A tap selects one line; a band selects many.** `tap` delegates to `StrokePicker.pick`, which
   already implements the cluster/cycle rules, and the result **replaces** the selection. `band` adds.
   Two different gestures because Concepts' are two different gestures, and because a tap that added
   to a selection could never take anything away again.

3. **`drag`'s offset is `Double`, and the narrowing happens once, at the call.**
   **This is the correction to the contract typo, and the way it is resolved:** the previous draft of
   this spec declared `drag(x: Double, y: Double, dx: Double, dy: Float)` — a half-`Double` signature
   that cannot compile without an implicit widening and cannot be read without asking which side
   converts. RULED (R37): `dy: Float` → `Double`. So **every** coordinate in `InkEditSession` is
   `Double`, matching `StrokePicker.pick`, `VectorEraser`'s `Piece.from`/`to`, `EraserPath`'s
   `xs`/`ys`/`radius` and `InkLine`'s arrays — one unit, one type, throughout `vector`. `StrokeEdit.reshape` takes
   `Float` and is not changed by this spec, so the session narrows `grabX`, `grabY`, `dx`, `dy` and
   `radius` to `Float` in one private call, exactly once per reshape.
   **Why at the session and not the other way round:** `StrokeEdit` is landed, tested and owned by
   JB-5.03a; `InkEditSession` is new. The cast belongs on the new side of the boundary, and the cost
   is one `toFloat()` per argument at 24 doc px of reach — far below `Float`'s ~7 significant digits
   at any zoom a person can hold a pen at. `beginDrag` narrows `grabX`/`grabY` once for the whole
   drag, so it is narrowed once rather than per frame.
   The previous signature also carried `x` and `y` — the finger's current position — which nothing
   read: the grab point is fixed by `beginDrag` (Decision 4), so the current position is the
   caller's business and keeping it on the signature would suggest the session uses it. It is gone.

4. **A reshape drag moves every selected line, about THE ONE grab point. RULED (R37, Q2).**
   `drag` calls `StrokeEdit.reshape(record, grabX, grabY, dx, dy, radius)` on **every** selected
   record, with the same `grabX`/`grabY` for all of them, so a selection of ten lines follows the
   finger together as one object. The alternative — each line grabbing its own nearest sample —
   keeps each line's shape intact but moves a selection in ten directions at once, and no product
   decision in this project's history has wanted that. The grab point is the point the finger went
   down on, captured once by `beginDrag` and then fixed: if it followed the finger, a drag would
   shear the selection as it moved rather than carry it.

5. **The grab radius is 24 SCREEN px, divided by the zoom once.** `StrokeEdit.reshape` takes doc px,
   so `beginDrag` computes `radius = (RESHAPE_GRAB_SCREEN_PX / screenPerDoc).toFloat()` and holds it
   for the whole drag. Screen px for the same reason `StrokePicker` uses them (its own file note):
   24 px is a finger, not a document fact, and it must be the same finger-feel at every zoom.

6. **A whole drag is ONE undo step, committed at `endDrag`.** `beginDrag` snapshots, `drag` mutates,
   `endDrag` pushes one `InkEditStep` — or `NoChange` if the snapshot equals the result, compared
   record by record so that a drag that moved nothing cannot commit. Live re-rendering during the
   drag is the caller's business; the session is told nothing about frames.

7. **A second finger cancels the whole drag, restoring the snapshot exactly.** Not "the last frame" —
   the records. `cancelDrag` puts `beginDrag`'s snapshot back and forgets the grab, so `endDrag`
   afterwards pushes nothing and a further `drag` does nothing. This is the same cancel the drawing
   view already does for a finger stroke, and it is the only way to leave a reshape.

8. **Re-weight is multiplicative and clamped by `StrokeEdit`.** `reweight(factor)` passes the factor
   straight through; `StrokeEdit.reweight` clamps `widthScale` to `MIN_WIDTH_SCALE`..`MAX_WIDTH_SCALE`
   (0.05..20) and leaves a non-finite factor alone. **A non-finite factor is `Refused`**, so the
   session never reports a change it did not make. One step for the whole selection, and
   `NoChange` (not a step) when the selection is empty.

9. **Re-brush is ONE step for the whole selection, refused in words, and the refusal has two doors.**
   RULED (R37, Q1): the refusal is shown **at pick time** — the picker calls
   `InkEditSession.rebrushRefusal(brush)` and greys the brush with the sentence, so a person is told
   before they commit, not after — **and this session's own refusal stays as the backstop**, because
   a UI that forgets to ask must still not paint a smudge onto an ink layer. Both doors return the
   **same sentence from the same function**; there is no second copy of the wording. `stamp` and
   `fill` go through (`StrokeEdit.drawsInkLines`); `smudge` and `wet` return `Refused` and **change
   nothing** — including not changing the selection. `rebrush` on an empty selection is `NoChange`.

10. **The seed survives every edit.** `StrokeEdit.rebrush` is `r.copy(brushId = …)`, so the seed,
    the samples, `widthScale` and `colorArgb` are kept by construction. The session adds nothing and
    must not re-seed anything. `reweight` and `reshape` are `copy` too, for the same reason.

11. **A band is a rectangle in DOC px**, axis-aligned, corners given by the caller in whatever order
    the finger drew them, so `(x0,y0)-(x1,y1)` is normalised inside. A line joins the selection when
    **any of its points** is inside the rectangle, tested on the **dab centres `InkReplay.dabs`
    produces** — not on the line's bounding box (a bounding box would catch a long diagonal that
    passes nowhere near the band) and not on the ribbon's edges (a band is a coarse gesture; testing
    the edges would select a line whose ink stops a pixel short of the band). A `NaN` or infinite
    corner is `Refused`; the rectangle is otherwise total, including an empty one (nothing is inside
    it, so nothing joins).

12. **`lines()` builds an `InkLine` per record, and a record that cannot be replayed is simply not a
    line.** `InkLine(id, xs = dabs.map { it.x }, ys = dabs.map { it.y }, halfWidths = dabs.map
    { it.radius })` — one point per dab, in stroke order, the half-width being that dab's own radius.
    A missing brush or a `smudge` brush means the stroke cannot be picked, moved or re-brushed,
    because there is nothing to redraw it with. It is not an exception here: `InkReplay.refusal` is
    how the UI says why, on a different screen. `InkLine` requires at least one point
    (`InkGeometry.kt:30`), so a recording that replays to **no** dabs — zero samples, or a `fill`
    record, whose dabs are empty by Decision 12 of JB-5.01 — is not a line either. That is correct
    and it is a real limitation: **a `fill` recording cannot be reshaped by this session.** It is
    stated in Questions so the Lead can rule on it rather than have a builder discover it.

13. **The undo log is a bounded list, newest last, capped at 50 steps. RULED (R37, Q3): steps, 50.**
    The number and the unit are the Lead's. It is a step count rather than a byte budget because
    `UndoLog` budgets in bytes with a `release` hook and an ink record is pure value with nothing to
    release — there is no resource to free, so a byte budget would be a number with no mechanism
    behind it. Fifty is generous for a gesture session measured in minutes, and it is
    `MAX_UNDO_STEPS`, one line, if the owner ever wants it different.

## Steps

1. Tests first, written from Decisions 1–13, into `InkEditSessionTest.kt`.
2. `InkEditSession.kt`.
3. Run the command. If it is green, stop. **There is no view, no overlay and no wiring step.**

## Tests

`./gradlew -p joybrush :core:jvmTest`. Passing = `BUILD SUCCESSFUL` and 0 failures in
`joybrush/core/build/test-results/jvmTest/`. The whole file lives in `commonTest` — **none of these
tests opens a file**, so none may be moved to `jvmTest` or given a `java.io` import.

**Fixtures.**
`inkBrush(id = "joybrush.ink", engine = "stamp", size = Param(6f), tip = TipSpec(corner = 2f,
hardness = Param(1f)), spacing = 0.04f, accumulate = "wash")` — no scatter, no size jitter, so
`InkReplay.dabs` is fully predictable. `fillBrush()` and `smudgeBrush()` are the same with `engine`
changed.
`rec(id, n, x0, y0, dx, dy)` builds a record of `n` `PenSample`s starting at `(x0, y0)` and stepping
`(dx, dy)`, `seed = 7L`, `brushId = "joybrush.ink"`, `smoothing = 0f`, `screenPerDoc = 1f`.

**The four-stroke fixture** (tests 1–10, 12–14), ids in drawing order:
`a` = 10 samples `(0,10) … (9,10)` · `b` = 10 samples `(0,50) … (9,50)` · `c` = 10 samples
`(10,0) … (10,9)` · `d` = 10 samples `(200,0) … (200,9)`. Every line is 9 doc px long and its dabs
have radius 3 (`size.base = 6`, no pressure curve), so two lines 40 doc px apart never touch.

1. **Tap selects one and replaces (D2).** `tap(4.0, 10.0, 0.0, 1.0)` → `selection == ["a"]`;
   `tap(10.0, 4.0, 0.0, 1.0)` → `["c"]`, not `["a","c"]`. Non-vacuity: a third tap on empty paper at
   `(150.0, 150.0)` → `NoChange` **and the selection becomes empty**, which is the half of "replaces"
   that the first assertion alone does not reach.
2. **Band selects many, in drawing order (D2, D11).** Band over `(0.0, 0.0)-(300.0, 100.0)` → all
   four ids in drawing order. The corner-swapped `(300.0, 100.0)-(0.0, 0.0)` gives the identical
   selection. A band over only `a` — `(0.0, 0.0)-(20.0, 20.0)` — gives `["a"]` only, and **adds** to
   an existing `["c"]` rather than replacing it.
3. **A band is points, not a bounding box (D11, non-vacuity).** A fifth record `e`: 10 samples
   stepping `(9, 9)` from `(0, 0)`, so its bounding box is `(0,0)-(81,81)` and it passes straight
   through the band of test 2. Band over `(20.0, 20.0)-(60.0, 60.0)`: `e`'s dabs are at
   `(0,0), (9,9), (18,18), (27,27), (36,36), (45,45), …` — only `(36,36)` and `(45,45)` are inside,
   so `e` IS selected and a bbox test would agree here. The load-bearing case is the band
   `(0.0, 0.0)-(20.0, 20.0)`: `e`'s dabs at `(0,0)`, `(9,9)` and `(18,18)` are all inside, but assert
   the negative too — band `(28.0, 0.0)-(35.0, 7.0)` contains **no** dab of `e` (the nearest are
   `(27,27)` and `(36,36)`, both 20+ away in y) while `e`'s bounding box covers it completely. `e` is
   **not** selected. A builder who tested the bounding box fails here and nowhere else.
4. **A drag moves every selected line, and moves it GRADED (D3, D4).** Select `a` and `c`.
   `beginDrag(4.0, 10.0, 1.0)` → radius 24, grab `(4, 10)`. `drag(5.0, 0.0)` → `endDrag()`.
   - `a`'s sample **0** is 4 doc px of arc from the grab (the grab landed on sample 4, which is at
     `(4,10)` and so has `s = 0` and `w = 1`). `u = 4/24 = 1/6`, `u² = 1/36`, `w = (35/36)² =
     1225/1296 = 0.9452161`, so it moved `5 × 0.9452161 = 4.726080` px, asserted to 1e-3.
   - `a`'s sample **4** is the grabbed one: `w = 1`, moved exactly `5.0`.
   - `a`'s sample **9** is 5 of arc: `u = 5/24`, `u² = 25/576`, `w = (551/576)² = 0.9150775`, moved
     `4.575` to 1e-3 — **strictly less than sample 0's**, which is the monotonicity `StrokeEdit`'s KDoc
     promises and the thing a constant-offset bug would break.
   - `c` is a **different line** whose nearest sample to `(4,10)` is `(10,9)` (distance
     `√(36+1) = 6.083`; the next, `(10,8)`, is `6.325`). That sample moved exactly `5.0` — so the two
     lines share ONE grab point, and `c` is dragged sideways, which is the point of Decision 4.
   - The selection is unchanged, and `undo.size == 1`.
   **The corrected assertion, and what was wrong:** the previous draft said "both records moved 5 px
   in x", which is false for every sample except the grabbed one. With `u = s/R` and `w = (1−u²)²`,
   a sample 9 doc px away on a 24 doc px grab moves `w = (1 − (9/24)²)² = 0.7385` of the offset, not
   all of it. Asserting "the record moved 5 px" would either fail or, worse, pass against a builder
   who moved every sample by the full offset.
5. **One drag is one undo step (D6).** After four `drag` calls and one `endDrag`, `undo.size == 1`
   whose `before` holds the ORIGINAL two records, and restoring it puts both back exactly.
6. **A drag that moved nothing commits nothing (D6).** `beginDrag(4.0, 10.0, 1.0)`,
   `drag(0.0, 0.0)`, `endDrag()` → `NoChange` and `undo.size == 0`. And a `drag` with no
   `beginDrag` → `NoChange` and no step.
7. **A second finger undoes the whole drag (D7).** After two `drag` calls, `cancelDrag()` restores
   both records exactly; a following `endDrag()` returns `NoChange` and leaves `undo` empty. And a
   `drag` **after** `cancelDrag()` must not resurrect the cancelled state: it returns `NoChange` and
   the records are still the restored ones. Non-vacuity: assert the restored records are `==` to the
   originals, not merely "different from the dragged ones".
8. **The grab radius is screen px, and the falloff is `(1 − u²)²` over ARC (D5).** Fixture: a
   400-sample line 1 doc px apart along `y = 0`; the grab is sample 0, so a sample's arc from the
   grab is its index.
   - **`screenPerDoc = 1`** → radius 24. Sample 20: `u = 20/24 = 0.8333333`, `u² = 0.6944444`,
     `1 − u² = 0.3055556`, `w = 0.0933642`. With `dx = 8`, it moved `8 × 0.0933642 = 0.7469` px —
     **9.34 % of the offset, not "under 1 %"** (the previous draft's figure, which is wrong by an
     order of magnitude and would have hidden a falloff that is far too generous).
   - **`screenPerDoc = 8`** → radius `24/8 = 3`. Sample 20: `u = 20/3 = 6.67 ≥ 1`, so `w = 0` and it
     **does not move at all** — the shape ends, it does not fade out over a long tail.
   - **Same zoom, sample 2**: `u = 2/3 = 0.6666667`, `u² = 0.4444444`, `1 − u² = 0.5555556`,
     `w = 0.3086420` — it moved `8 × 0.3086420 = 2.4691` px, **30.86 % of the offset, not "nearly the
     full offset"** (the previous draft's second wrong figure, and the one that would have hidden a
     falloff that is far too tight).
   - **Same zoom, a second fixture of 800 samples 0.5 doc px apart** so a sample 0.5 away exists:
     `u = 0.5/3 = 0.1666667`, `u² = 0.0277778`, `1 − u² = 0.9722222`, `w = 0.9452160` — **94.52 %
     of the offset**. The three figures together (94.5 %, 30.9 %, 0 %) are the falloff's shape at one
     zoom, and they are why the 30.9 % case is the interesting one: it is neither "nearly full" nor
     "nothing", and a builder who guessed will get one of those two wrong.
   - Monotonicity: at radius 3 the moved distances for samples 0.5, 1, 2, 3 and 4 doc px away are
     non-increasing, and the sample at exactly 3 (`u = 1`, `w = 0`) and the one at 4 (`u > 1`, `w = 0`)
     are both exactly unmoved. That is `(1 − u²)²` reaching zero at `u = 1` and staying there, and it
     is what makes the edge of the grab a `C²` join rather than a visible seam.
9. **`beginDrag` refuses a zoom it cannot use (D5).** `beginDrag(0.0, 10.0, 0.0)`,
   `beginDrag(4.0, 10.0, -1.0)`, `beginDrag(4.0, 10.0, Double.NaN)` and
   `beginDrag(4.0, 10.0, Double.POSITIVE_INFINITY)` each throw `IllegalArgumentException` **before**
   any snapshot is taken, so the session is still draggable afterwards. A zoom of exactly `1.0` and
   `16.0` do not throw, and the second gives `radius = 1.5` doc px — which is the whole reason the
   radius is screen px.
10. **Re-brush is one step for the selection, and keeps the seed (D9, D10).** Select `a` and `c`;
    `rebrush(fillBrush())` → one `Changed`, one `InkEditStep`, both records' `brushId` is the fill
    brush's id, and both `seed`s are still `7L`. Non-vacuity: assert the **sample lists** are `==` to
    before, so a builder who re-smoothed or re-sampled fails here.
11. **The refusal has two doors and one sentence (D9).**
    - `InkEditSession.rebrushRefusal(smudgeBrush())` returns a string containing the brush's `name`
      (`"Smudge"`); `rebrushRefusal(inkBrush())` and `rebrushRefusal(fillBrush())` return `null`.
    - The same call works with **no session and no selection** — it is a companion function, so a UI
      can grey a brush before anything is selected. Assert that by calling it from a bare
      `InkEditSession(emptyList(), "cel-1")` and from no session at all.
    - `rebrush(smudgeBrush())` → `Refused` whose `reason` is **exactly** `rebrushRefusal`'s string;
      `undo.size` unchanged; **every** record in the session `==` to its value before the call, and
      the selection unchanged. This is the assertion that catches a builder who mutates first and
      checks later.
    - A wet brush gives the same two results. The refusal is total.
12. **A missing brush makes a line unpickable (D12).** A five-record session: `a`…`d` plus `e`, whose
    brush resolves to `null`. `lines()` has **four** entries and `session.selection` after a `tap` on
    `e` is empty with a `NoChange` result. The count is asserted by id, not by size alone, so a
    builder who dropped the wrong line fails.
13. **Re-weight (D8).** Select `a`; `reweight(2f)` then `reweight(0.5f)` → `widthScale` back to 1f
    within 1e-6, **one** undo step for the pair's two calls (two steps, one per call — asserted as
    `undo.size == 2`, so "one step for the whole selection" is distinguished from "one step per
    line"). `reweight(1000f)` → `20f` exactly (`MAX_WIDTH_SCALE`). `reweight(0.0001f)` → `0.05f`
    exactly (`MIN_WIDTH_SCALE`). `reweight(Float.NaN)` and `reweight(Float.POSITIVE_INFINITY)` →
    `Refused`, no step, every record `==`. And `reweight(2f)` with an **empty** selection →
    `NoChange`, `undo.size == 0`.
14. **Recolour is one step and touches only the colour (D9's shape).** The samples, `seed`,
    `widthScale`, `smoothing`, `screenPerDoc` and `id` are all `==` before and after; only
    `colorArgb` differs.
15. **The undo log is bounded at 50 (D13).** Select `a`; 60 `reweight(1.01f)` calls → `undo.size ==
    50`, and the surviving steps are **the newest 50**, asserted by content: the oldest surviving
    step's `before` holds `a` with `widthScale` equal to `1.01f.pow(10)` (the value after ten
    reweights) and the newest holds `1.01f.pow(59)`. Assert by value, not by size — a builder who
    kept the OLDEST 50 would pass a size-only assertion.
16. **Band and tap totality (D2, D11).** A band with a `NaN` corner, and a `tap` at `Double.NaN`, are
    each `Refused` with the session unchanged — a `NaN` reaching `PointInPolygon` arithmetic is how a
    selection silently empties itself. An **empty** band (`x0 == x1 && y0 == y1`) is `NoChange`: it
    contains nothing, so nothing joins, and it is not an error.

## Do not

- Do **not** edit `stroke/`, `vector/InkReplay.kt`, `vector/InkRaster.kt`, `vector/InkGeometry.kt` or
  `vector/StrokePicker.kt`. You consume all five. If one of them is wrong, that is a Question.
- Do **not** add a GPU path, a view, an overlay, a halo, a toast or a `MotionEvent`. This file is
  state and arithmetic.
- Do **not** give `beginDrag`/`drag`/`endDrag` a frame, a timestamp or a callback parameter. The
  "one drag, one step" rule is enforced by the API's shape, not by discipline.
- Do **not** re-list the engine names. Ask `StrokeEdit.drawsInkLines(engine)`; it is the one place
  that says which, and a fifth engine is a change in one file.
- Do **not** write the refusal sentence twice. `rebrushRefusal` is the only copy, and the picker and
  the session both call it.
- Do **not** clamp a re-brush that you could instead refuse. Refusal is the answer, and it must be
  total: the drawing byte-identical, the selection unchanged.
- Do **not** add a "delete selected" verb (Decision 3). JB-5.11 deletes lines; this session only
  ever *edits* one, so there is no destructive gesture to get wrong.
- Do **not** add a `Float` parameter. Decision 3, and the whole reason this contract changed.

## Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest` is green; the output is pasted into the report.
- [ ] Only the two owner-area files changed; `git status --short` is pasted into the report.
- [ ] Two non-vacuity mutations were run and are reported: making `rebrush` mutate before it
      refuses, and making `reweight` ignore `MIN_WIDTH_SCALE`. Both must redden at least one test.
- [ ] Committed as `JB-5.03: ink edit session`; pushed.
- [ ] ROADMAP row → 🟧 Built — awaiting T1 review.

## Stop rule

**Stop and write a question in `## Questions` if any of these is true; do not guess.**

1. Implementing a decision above needs a signature change in this contract **or** in any landed file
   (`StrokeEdit`, `InkLine`, `StrokePicker`, `InkReplay`). Every one of those has an owner that is
   not this row.
2. A derived number in a test does not come out as derived. R9: an expected value may change only
   with its derivation written into the test — never to make a run go green. Test 8's three falloff
   figures are the ones most likely to be "adjusted", and they are the ones that must not be.
3. Replaying a record twice in the same run gives different dab positions, or `lines()` gives two
   different `InkLine`s for one record. That is a defect in `InkReplay` or `InkLine`, and it is
   reported, not worked around.
4. A selection of more than one line cannot be moved as one object with one grab point, and the
   answer looks like it needs per-line grabbing. Stop and ask; Decision 4 is the Lead's ruling and
   this is the place it would be quietly overturned.
5. Anything here needs `JbCanvasView`, `GlPaintEngine` or a Gradle file.

## Questions — for the Lead

*(Nothing blocks this row. Both items are stated as decisions above and are listed here so they are
visible rather than buried.)*

**Q1 — a `fill` recording is not a line, so this session cannot reshape one (Decision 12).**
`StrokeEdit.rebrush` makes pencil → fill pen a one-call change (R20's headline case), and R21 makes
the fill pen a brush whose stroke is a filled shape. But JB-5.01's Decision 12 gives a `fill` record
**no dabs** — its raster is `InkRaster.fill` on `FillPen.outline` — so `lines()` cannot build an
`InkLine` for it, and Decision 12 here drops it. Consequence: after pencil → fill pen, the shape
cannot be selected, dragged or reshaped by this session until something gives it a centreline.
**The decision taken here is to drop it and say so**, because the alternative — inventing a
centreline for a shape (the outline's own polyline, say) — is a design choice about what a fill
stroke *is*, and R41 is still settling the fill pen's own fill kinds. **What I need ruled:** should a
`fill` recording be re-brushable back to a pencil (R20 says yes, and `StrokeEdit.rebrush` already
allows it) while remaining un-reshapable here — or is the fix for 5.11/5.04 to give a fill recording
a real centreline? One line either way in Decision 12; not this row's to choose.

**Q2 — the view wiring is the Lead's, and it is not written here (R37, Q4).** Somebody still has to
draw the selection halo, route a finger drag to `beginDrag`/`drag`/`endDrag`, re-render after every
`Changed`, and push the step onto the app's history. That work touches `JbCanvasView`, which R30 item
2 reserves for the Lead in the order `… → 1.06 → 5.01b → …`, and the two-file split is the Lead's
call. **No `JB-5.03b` spec is created by this row** and none should be dispatched. Flagging only that
the wiring will need `JbCanvasView` in the same window as JB-5.01b, and is therefore best scheduled
with it.

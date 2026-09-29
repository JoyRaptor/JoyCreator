# JB-5.01 — Ink layer: replay a recording into dabs, and rasterise dabs

| | |
|---|---|
| **Tier** | T2 |
| **Status** | 🟦 Ready |
| **xr** | stealth/space-bunny-alpha 2026-09-29 — Lead review fixes applied: `render.MAX_REGION_PX` reference corrected and pasted, the `JbCanvasView` constant edit scoped out of the T2 half (owed to 5.01b), R37's Q1-Q4 answers inlined as Decisions 2/3/16/14. |
| **Needs** | JB-0.04 (`StrokeRecord`, `StrokeCodec`), JB-0.07 (`RefCanvas`, `DabPlacer`), JB-1.05 (`BrushDabber`, `Scatter`, `FillPen`), JB-2.05a (`SelectionMask.polygon`), JB-2.07a (`MaskPaint`) |
| **Owner area** | (1) NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/vector/InkReplay.kt` · (2) NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/vector/InkRaster.kt` · (3) NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/vector/InkReplayTest.kt` · (4) NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/vector/InkRasterTest.kt` · (5) EDIT `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/brush/Scatter.kt` — **one added `const val SALT`, one added KDoc paragraph, nothing else in that file changes** · **NOTHING ELSE. In particular NOT `JbCanvasView.kt`, NOT `GlPaintEngine.kt`, NOT anything in `stroke/`, NOT any Gradle file.** |
| **Estimated size** | ~300 lines of code, ~300 lines of tests |
| **Command** | `./gradlew -p joybrush :core:jvmTest` — `BUILD SUCCESSFUL`, 0 failures in `joybrush/core/build/test-results/jvmTest/` |

## Goal

Blueprint §2: on a PAINT layer the pixels are the truth; on an **INK layer the recording is**. The
same drawing zoomed to 16× is drawn again from the recording rather than magnified, so the line is as
smooth up there as it was at 1×. And because the recording names the brush, a line can afterwards be
given a different brush, width or colour (R20) — which is JB-5.03's job and is only possible because
this spec makes the recording the thing that is drawn.

This spec builds **the replay and the raster only**, as two pure functions in `:core`, so both are
testable on any JVM with no GL context, no phone and no Android. R37 split the row: the view's
capture of a `StrokeRecord` per stroke, and the GL side, are **JB-5.01b and are the Lead's**. Nothing
here touches a file in R30's lock order, so this row can run beside almost anything.

## Contract (verbatim)

### The constant this spec reads — it is TOP-LEVEL, not a member

```kotlin
package cc.joycreator.joybrush.core.render

// joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/render/RegionRenderer.kt:89
// TOP-LEVEL const. It is NOT RegionRenderer.MAX_REGION_PX — that name does not compile.
const val MAX_REGION_PX = 8_388_608L
```

Referenced from this spec's files as:

```kotlin
import cc.joycreator.joybrush.core.render.MAX_REGION_PX
```

**The asymmetry, so nobody "fixes" it the wrong way.** `MAX_REGION_PX` is top-level, and
`TILE_BYTES` **is** a member of the `RegionRenderer` object (`RegionRenderer.kt:176`):

```kotlin
object RegionRenderer {
    /** Bytes in one tile: 256 x 256 x 4. */
    const val TILE_BYTES = TILE_SIZE * TILE_SIZE * 4
```

So `import …render.MAX_REGION_PX` and `RegionRenderer.TILE_BYTES` are both correct, and they look
like the same kind of thing. They are not.

### The two new files

```kotlin
package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.paint.Accumulate
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.TipShape
import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.stroke.StrokeRecord

/** Thrown when a recording cannot be replayed. The message is the whole error report. */
class InkReplayException(message: String) : Exception(message)

object InkReplay {

    /**
     * The dabs one recording draws, in stroke order, exactly as the pen drew them the first time.
     *
     * Tessellates in DOCUMENT space using [record]'s own `smoothing` and `screenPerDoc`, seeds
     * [cc.joycreator.joybrush.core.brush.BrushDabber] and the scatter stream with
     * [StrokeRecord.seed], and expands scatter through the same
     * [cc.joycreator.joybrush.core.brush.Scatter] the drawing view used, with the same
     * [cc.joycreator.joybrush.core.brush.Scatter.SALT].
     *
     * Each dab's radius is multiplied by [StrokeRecord.widthScale] (Decision 9).
     *
     * `engine == "fill"` records draw NO dabs — a fill is a shape, and its raster is
     * [InkRaster.fill]'s business. Every other engine than "stamp" or "fill" is REFUSED by name
     * (Decision 6).
     *
     * @throws InkReplayException if `brush` is null, or its engine cannot draw an ink line.
     */
    fun dabs(record: StrokeRecord, brush: BrushPreset?): List<Dab>

    /** Every problem that stops [record] from being drawn, in words. Empty = it draws. */
    fun refusal(record: StrokeRecord, brush: BrushPreset?): String?
}

object InkRaster {

    /**
     * Premultiplied RGBA8 bytes, `width * height * 4`, row 0 = the destination's TOP row
     * (`RegionRenderer.renderPremultiplied`'s layout, which is also the tile layout).
     *
     * @param docX,docY the destination rectangle's top-left in DOCUMENT px. Every dab is placed at
     *   `((dab.x - docX) * scale, (dab.y - docY) * scale)` in destination px and drawn with radius
     *   `dab.radius * scale`; destination pixel `(px, py)` samples the document at
     *   `(docX + (px + 0.5) / scale, docY + (py + 0.5) / scale)`.
     * @return `null` for a destination of more than `MAX_REGION_PX` pixels, a non-finite or
     *   non-positive `scale`, or a `width`/`height` that overflows `Int` when multiplied.
     * @throws IllegalArgumentException if `width` or `height` is negative (a caller bug, refused in
     *   words exactly as `RegionRenderer.requireSize` refuses it).
     */
    fun stamps(
        dabs: List<Dab>,
        tip: TipShape,
        accumulate: Accumulate,
        opacity: Float,
        colorRgb: Int,
        docX: Int,
        docY: Int,
        width: Int,
        height: Int,
        scale: Float,
    ): ByteArray?

    /** [stamps] in premultiplied 0..1 floats — Decision 6's test seam, and the only place the
     *  accumulation and the commit are written down. `stamps` is this plus a quantisation. */
    internal fun premultiplied(
        dabs: List<Dab>,
        tip: TipShape,
        accumulate: Accumulate,
        opacity: Float,
        colorRgb: Int,
        docX: Int,
        docY: Int,
        width: Int,
        height: Int,
        scale: Float,
    ): FloatArray?

    /**
     * The fill pen's shape: the closed polygon [cc.joycreator.joybrush.core.brush.FillPen.outline]
     * gives, filled NON-ZERO, so a loop drawn twice is still solid. The same `docX, docY, scale` and
     * the same return contract as [stamps]; `colorRgb`'s own alpha byte is the opacity.
     */
    fun fill(
        outline: List<Pt>,
        colorRgb: Int,
        docX: Int,
        docY: Int,
        width: Int,
        height: Int,
        scale: Float,
    ): ByteArray?
}
```

`stamps`' ten parameters are **`RefCanvas.beginStroke`'s stroke state (`tip`, `accumulate`,
`opacity`, colour) plus a region and a scale**, in that order. That is deliberate: Decision 6's
parity test is then a transcription of a test that already exists, not a design.

## Decisions

1. **One replay, one answer.** `dabs` is a pure function of `(record, brush)`. Nothing about the
   current zoom, the screen, the frame or the device enters it. Two calls with the same arguments
   produce field-identical `Dab` lists — the promise JB-0.04's codec exists to keep, and what
   "the recording is the truth" means in practice.

2. **The seed comes from the record, and from nowhere else. RULED (R37, Q1), shape (a).**
   `BrushDabber(brush, record.seed)` and `SplitMix(record.seed xor Scatter.SALT)`.
   **Who generates the seed is the view's job, at stroke start, and it stores it in the record; from
   that moment the seed is the record's alone and nothing else may invent one.** That part is
   JB-5.01b's (the Lead's), and this spec does not care: it reads `record.seed` and nothing else.
   There is no `SystemClock` in `:core` and none may be added — a clock in the replay is a stroke
   that draws differently every time it is opened.
   - `Scatter.SALT` does not exist yet. `SCATTER_SALT` is `JbCanvasView`'s constant
     (`JbCanvasView.kt:651`, `const val SCATTER_SALT = 0x5CA7L`), and the view reads
     `SystemClock.uptimeMillis()` into it at `JbCanvasView.kt:326-329`. Decision 5 moves the value
     to core; it does **not** touch the view.

3. **Tessellation is in DOCUMENT space, at the record's own spacing. RULED (R37, Q2): an ink line's
   randomness is a property of the RECORDING, not of the viewing zoom.** Dabs are placed by
   `DabPlacer` at `spacing × diameter` doc px, exactly as the live drawing path does
   (`JbCanvasView.kt:330`: `DabPlacer(spacing = d.spacing, look = d::look)`).
    **The consequence, derived, because it is the whole reason the ruling went this way.** Two dabs
    of radius `r` with centres `d` apart leave a scallop at the midpoint of depth
    `r − √(r² − (d/2)²) = r(1 − √(1 − (d/2r)²))`.

    **The nominal step is not the real step, and this correction (R44 item 3) is the whole point.**
    `DabPlacer.emit` returns `max(2r × spacing, minSpacingPx)` and `minSpacingPx` defaults to
    **0.5 doc px**, so for Ink — `spacing = 0.04`, `size.base = 6` — the nominal step is
    `2 × 3 × 0.04 = 0.24` doc px and the **floor binds**: the real step is **0.5**. Computing at the
    nominal step, as this decision originally did, is 4.4× optimistic.

    At the real step, with `r = 3` and `d = 0.5`: `3 − √(9 − 0.25²) = 0.01044` doc px, which as a
    fraction of the **diameter** `2r` is `0.00174 × diameter`. For Ink that is **0.167 screen px at
    16×** — a sixth of a pixel, not the fortieth this decision once claimed.

    **The ruling's conclusion is unchanged and in fact stronger.** Sub-pixel on a hard edge that
    antialiasing softens and that a ~500-fold bead overlap sits on top of is invisible by a wider
    margin than 0.038 was. What was wrong was the number, not the judgement. `InkReplayTest`
    asserts the floor directly (0.5 for the shipped brush) and, on a 20 px brush where the spacing
    binds rather than the floor, the 0.8 that makes a wrong spacing go red — which is what makes
    this derivation testable rather than decorative.

4. **Crispness is the rasteriser, not the tessellation.** `stamps` evaluates `TipMath.coverage`
   over the DESTINATION's own pixel grid with the radius multiplied by `scale`. Zoom changes the
   resolution of an edge and nothing else. The testable property is in test 7.

5. **A shared constant is never copied — and the core half of this spec does NOT need the view.**
   `SCATTER_SALT` moves to `core/brush/Scatter.kt` as `Scatter.SALT` **with its value unchanged**.
   The one-line `JbCanvasView` edit that makes the view read it is **not in this spec's owner area**
   and is **not needed for this spec to be built or tested**: until that edit lands, the view's
   constant and core's hold the same number, and this spec's test 1 pins core's value to the literal
   `0x5CA7L` with `JbCanvasView.SCATTER_SALT` named in the failure message. The structural fix — one
   literal instead of two — is **owed to the Lead, inside JB-5.01b**, because R30 item 2 reserves
   `GlPaintEngine.kt` and `JbCanvasView.kt` for the Lead in the order `2.20b → 1.05c → 1.06 → 5.01b`.
   The exact owed edit, when the Lead takes 5.01b, is: delete
   `JbCanvasView.kt:651` (`const val SCATTER_SALT = 0x5CA7L`) and change
   `JbCanvasView.kt:329` from `SplitMix(seed xor SCATTER_SALT)` to `SplitMix(seed xor Scatter.SALT)`,
   adding `import cc.joycreator.joybrush.core.brush.Scatter` if it is not already there. Two lines,
   one file, zero behaviour change.

6. **The ink layer's maths is `RefCanvas`'s maths, not a second copy — and the contract says so by
   construction.** Same accumulation `s′ = cap·cov + s·(1−cov)` with `cov = coverage × d.flow`, same
   commit `out = colour·a + dst·(1−a)` with `a = s × k`, same premultiplied layout. **Test 8 proves
   it**, and the parameters of `stamps` are `RefCanvas.beginStroke`'s, so the proof is a
   transcription rather than an act of faith. `premultiplied` is `internal` and is the **only** place
   the accumulation and the commit are written; `stamps` is that plus a quantisation
   (`(v × 255f + 0.5f).toInt().coerceIn(0, 255).toByte()`, `RegionRenderer.kt:377`).
   The commit factor is `k = alphaOf(colorRgb) × (if (accumulate == Accumulate.BUILD_UP) opacity
   else 1f)`, one line, and the alpha factor is there because `StrokeRecord` carries its colour with
   an alpha and nothing else per-record.

7. **Only `stamp` and `fill` draw an ink line** (R20). `dabs` refuses `"smudge"` and `"wet"` with
   `InkReplayException` naming the engine and the stroke id, because both read the pixels
   *underneath* and an ink layer has no pixels underneath — it has recordings. The set is not
   re-listed here: `StrokeEdit.INK_ENGINES` already is "the one place that says WHICH brushes may"
   (its own KDoc, `StrokeEdit.kt:44`), so `dabs` asks `StrokeEdit.drawsInkLines(engine)` and a fifth
   engine is a change in one file. `refusal()` is the same sentence without the throw, for a UI that
   wants to grey a stroke out.

8. **A recording whose brush is missing is refused, in words, naming both.**
   `InkReplayException("stroke \"s3\" was drawn with brush \"ink-pen\", which is not in this
   document's library")`. It is not drawn as a default nib: a line that suddenly appears in the
   wrong brush is worse than a line that is visibly missing and says why.

9. **`widthScale` is applied, because it exists and nothing else applies it.** `StrokeRecord`
   (`StrokeRecord.kt:42`) carries `widthScale: Float` and its KDoc says it is "a multiplier on the
   brush's size for THIS line". `BrushDabber` computes a radius from the preset alone, so a replay
   that ignored `widthScale` would make JB-5.03's `reweight` change a number and not the drawing.
    `InkReplay` multiplies each dab's radius by `record.widthScale` **after** `DabPlacer` has
    produced it, clamped to `0f..DabPlacer.MAX_RADIUS_PX` — the same guard `DabPlacer.emit` applies
    (`DabPlacer.kt:78`), and for the same reason: a file that was never validated must not make a
    radius that cannot be allocated. `DabPlacer`'s own `step = 2r × spacing` was computed from the
    UNSCALED radius, so a re-weighted line's dabs are further apart as well as fatter, which is what
    the live path would have done with a bigger brush. Test 9 pins it.

    **R44 item 3 — THE TEST WINS, and this decision's sentence is what was wrong.** A `widthScale`
    of `1e30` gives `3 × 1e30 = 3e30`, which is a perfectly good **finite** float, so the safe-zero
    rule (below) does not apply and the clamp answers `DabPlacer.MAX_RADIUS_PX` = 2048. The original
    text of this decision implied the safe-zero rule caught it, and test 9 said otherwise; the
    builder implemented the decision and was right to flag the contradiction rather than pick the
    reading that made its test pass.

    The two rules are disjoint and both are right, which is what this decision now says:
    - **NON-FINITE** (`NaN`, `±Infinity`) → `0f`. A radius that is not a number cannot be allocated
      and cannot be reasoned about, so it contributes nothing.
    - **FINITE but out of range** → **clamped** to `0f..MAX_RADIUS_PX`. `3e30` is a real number that
      is merely too big, and the honest answer is the biggest radius that can exist.

10. **The replay walks the live path's own steps, in the live path's own order.** A `StrokeSmoother`
    (not `StrokeSmoother.smoothAll` — the class, so `droppedSamples` is readable and the batching is
    the same as `JbCanvasView.feed`'s) driven with `add` per sample and `finish` at the end; then one
    `DabPlacer(spacing = dabber.spacing, look = dabber::look)`; then **one**
    `Scatter.expand(placed, preset.scatter, SplitMix(record.seed xor Scatter.SALT))` over the whole
    dab list. Feeding `Scatter` the whole stroke at once rather than batch by batch is stated by
    `JbCanvasView.kt:403-405` and by `Scatter`'s own class note to give the same leaves, because
    every draw a scatter makes is in the same order whatever the batches were.

11. **The scatter's `DabInputs` is the view's, verbatim, and this is not negotiable.**
    `JbCanvasView.kt:418-428`:
    ```kotlin
    private fun dabInputsOf(dab: Dab) = DabInputs(
        pressure = dab.pressure, tilt = Float.NaN, speedPxPerS = Float.NaN,
        direction = Float.NaN, lean = Float.NaN, distancePx = Float.NaN,
        random = Float.NaN, strokeRandom = Float.NaN, barrel = Float.NaN,
    )
    ```
    A `Dab` carries only `pressure` (`Dab.kt:10-18`), so every other input is unknowable from a
    replay, and a curve keyed on tilt or speed must read its base rather than a number invented
    here. `InkReplay` writes this same lambda. It is eight lines of duplication and it is
    deliberate: inventing real values for tilt or speed would make a replayed stroke scatter
    differently from the one that was drawn, which is the one thing this file must never do.

12. **A `fill` recording draws no dabs and no pixels from `stamps`.** Its raster is
    `InkRaster.fill` on `FillPen.outline(record.samples, record.smoothing, record.screenPerDoc)`.
    `dabs` returns an empty list for a fill record and never throws. The **fill rule is not written
    here either**: `SelectionMask.polygon` (JB-2.05a) is the repo's one non-zero fill, and
    `MaskPaint.pixel(FILL, …)` (JB-2.07a) is the repo's one composite; `fill` calls both and owns no
    arithmetic of its own. `MaskPaint.pixel` ignores `argb`'s own alpha and takes `opacity`
    separately, so `fill` passes `opacity = alphaOf(colorRgb)`.

13. **An empty recording draws nothing and says nothing.** Zero samples, or all samples dropped by
    `StrokeSmoother` (its `droppedSamples` counter, `StrokeSmoother.kt:121`, is the same
    faithful-drop contract as everywhere else), gives an empty `Dab` list and an empty raster. Not
    an exception: a pen that landed once and reported two identical positions is ordinary, not
    broken. Note that a recording of **one** sample is not empty — `DabPlacer` emits a dab for the
    first sample unconditionally (`DabPlacer.kt:56-61`) — so a dot draws a dot.

14. **No dab cap. RULED (R37, Q4).** `dabs` returns a `List<Dab>` and so allocates, and a very long
    recording at a tiny spacing is a lot of dabs. There is no cap, because capping dabs means
    refusing a stroke somebody drew, and that belongs on `spacing` if it belongs anywhere (JB-0.03b's
    lower bound is what makes the extreme reachable) rather than on the ink path. The dab count is
    nonetheless **bounded by the placer, not by luck**: `DabPlacer.emit` returns
    `max(2r × spacing, minSpacingPx)` and `minSpacingPx` defaults to `0.5f` doc px, so a stroke of
    `L` doc px never places more than `L / 0.5` dabs however small the brush is. A 2 000-sample
    stroke is at most a few thousand dabs. Test 10 pins the rule that makes this true.

15. **`MAX_REGION_PX` is the cap and it is REFERENCED, never copied.** Both raster functions return
    `null` above it, checked in `Long` on `width × height` exactly as `RegionRenderer.requireSize`
    does, so a destination that cannot be allocated is refused instead of raising
    `NegativeArraySizeException` from the one function every ink export will call.

16. **The 0..1 `hardness` rule is NOT this spec's.** RULED (R37, Q3): it lands in `BrushValidate` in
    the tiny row **JB-0.03c**. `TipMath.coverage` already clamps (`TipMath.kt:34`,
    `tip.hardness.coerceIn(0f, 1f)`), so a brush carrying `"hardness": {"base": 1e30}` draws a
    hard edge and cannot crash, and this spec adds no second rule about it. Do not "helpfully" refuse
    such a recording here.

## Steps

1. Tests first, written from Decisions 1–16, in `InkReplayTest.kt` and `InkRasterTest.kt`.
2. `Scatter.kt`: add `const val SALT = 0x5CA7L` — the value `JbCanvasView.SCATTER_SALT` already
   holds (`JbCanvasView.kt:651`), moved and not retyped — with a KDoc saying what it is for and that
   moving it is what makes a replay match the drawing. Change nothing else in that file.
3. `InkReplay.kt`.
4. `InkRaster.kt`.
5. Run the command. If it is green, stop. **The view is not this spec** and there is no step 6.

## Tests

`./gradlew -p joybrush :core:jvmTest`. Passing = `BUILD SUCCESSFUL` and 0 failures in
`joybrush/core/build/test-results/jvmTest/`. The whole file lives in `commonTest` — **none of these
tests opens a file**, so none of them may be moved to `jvmTest` or given a `java.io` import.

**Fixtures.** `inkBrush(...)` = `BrushPreset(id = "joybrush.ink", name = "Ink", engine = "stamp",
size = Param(6f), tip = TipSpec(corner = 2f, hardness = Param(1f)), spacing = 0.04f,
accumulate = "wash")` — the shipped Ink's numbers with `hardness` raised to 1 so a test's edge is
predictable. `scatterBrush()` = the same with `scatter = ScatterSpec(amount = Param(0.5f), count =
4)`. `smudgeBrush()` / `wetBrush()` = the same with `engine` changed. `line(n, step, y)` = a
`StrokeRecord` of `n` `PenSample`s along `x = 0, step, 2·step, …` at height `y`, `seed = 7L`,
`brushId = "joybrush.ink"`, `smoothing = 0f`, `screenPerDoc = 1f`.

**`InkReplayTest`**
1. **The salt is the one the view uses (D5).** `assertEquals(0x5CA7L, Scatter.SALT, "core's salt
   must equal JbCanvasView.SCATTER_SALT (JbCanvasView.kt:651) — if you changed one, change the
   other")`. This is the drift tripwire while the two literals are still two literals; the Lead's
   one-line edit in 5.01b is what removes the need for it.
2. **Determinism (D1).** `dabs(r, b)` called twice gives `==` on every field of every `Dab` —
   `assertEquals(list, list)` on the `List<Dab>` itself, which is field-wise because `Dab` is a data
   class. Non-vacuity: the two calls use two different `InkReplay` invocations, so anything cached
   in an `object` would show up.
3. **The seed is the record's and only the record's (D2).** Two records identical except `seed`
   produce dab lists of the **same length** (the stream advances identically) and **different
   positions** with `scatterBrush()`. Non-vacuity, both halves: with `scatter.amount = 0f` the two
   lists are `==`, so a difference in the second half is a seed effect and not the brush; and the
   dab list is not simply a translation of the other (assert at least one dab is > 0.5 doc px from
   where the un-scattered dab would be, so "different" cannot be satisfied by a constant offset).
4. **The replay is the live path's arithmetic (D10).** With a preset carrying a pressure curve on
   `size` and `smoothing = 0f`, replaying a 200-sample line and driving `RefCanvas` by hand with
   `DabPlacer` + `BrushDabber` built the same way gives the identical dab list. This is the test that
   fails if `InkReplay` smooths with `smoothAll`, re-orders the scatter, or asks the dabber twice
   per dab.
5. **The scatter's `DabInputs` is the view's (D11).** A preset whose `scatter.amount` is driven by
   `tilt` (curve `[[0, 0], [1, 1]]`) produces the **base** amount, not one scaled by pressure:
   replaying the same samples at pressure 0.2 and at pressure 1.0 gives dab lists that are `==`
   except in radius, and no offset at all. A builder who filled `tilt` with a real number fails
   this.
6. **`fill` draws no dabs and does not throw (D12).** A record whose brush has `engine = "fill"`
   returns an empty list. Non-vacuity: the same record's samples, fed to `FillPen.outline`, give ≥ 3
   points — so the empty list is the engine rule and not an accident of the fixture.
7. **`smudge` and `wet` are refused by name (D7).** `assertFailsWith<InkReplayException>`; the
   message contains `"smudge"` / `"wet"` and the stroke id. And `refusal()` returns that same
   message, while `refusal()` for a `"stamp"` brush with a real preset returns **null** — the
   original spec's test said `dabs` returns `null` there, which is a type error: `dabs` returns
   `List<Dab>`, and `refusal` is the nullable one.
8. **A missing brush is refused, naming both (D8).** Message contains the stroke id and the brush id.
9. **`widthScale` thickens the replay (D9).** The same record with `widthScale = 1f` and with
   `widthScale = 2f` gives the same dab count and every radius exactly twice (within 1e-6 relative).
   **Then the three degenerate values, and R44 item 3 settled the split between them — the
   NON-FINITE ones go to `0f` and the finite-but-huge one is CLAMPED, not zeroed:**
   - `widthScale = 0f` → radii of `0f`.
   - `widthScale = Float.NaN` and `Float.POSITIVE_INFINITY` → radii of `0f`. These are not numbers,
     so there is nothing to clamp them to.
   - `widthScale = 1e30f` → radii of **`DabPlacer.MAX_RADIUS_PX` (2048)**, not `0f`. `3 × 1e30` is
     finite; the honest answer is the largest radius that can be allocated. This is what the shipped
     `InkReplayTest` already asserted, and the test was right where this spec's prose was not.
   `NaN` is read as `0f` and the result clamped, the same "a broken number is read as a safe one" rule
   as `DabPlacer.emit` and `StrokeEdit.reweight`.
10. **The dab count is bounded by the placer, not by a cap (D10, D14).** A line of 400 samples 100 doc
    px apart (39 900 doc px of travel) at `spacing = 0.0001f` with `minPx = 1f` on the tip produces
    `dabs.size ≤ 39_900 / 0.5 + 1` — the arithmetic is `max(2r × spacing, minSpacingPx)`, and
    `minSpacingPx` is what stops a tiny brush placing a dab every document pixel. Assert the bound,
    not an exact count.
11. **Empty and all-dropped recordings (D13).** Zero samples → empty list, no throw. A recording
    whose only sample has a non-finite `x` → empty list, no throw (the smoother drops it and counts
    it in `droppedSamples`). A recording of exactly **one** valid sample → **one** dab, because
    `DabPlacer` emits for the first sample unconditionally. These three together are the difference
    between "a dot draws a dot" and "a dot is silently nothing".

**`InkRasterTest`**
12. **Parity with `RefCanvas`, in floats (D6).** 60 samples 4 doc px apart along `y = 0`, diameter 6,
    `hardness = 1`, `TipShape(corner = 2f, hardness = 1f, minPx = 1f)`, `WASH`, opacity 1,
    `colorRgb = 0xFF1B1B22.toInt()`, destination `(docX = 0, docY = 0, 256 × 256, scale = 1f)`.
    Drive `RefCanvas(tileSize = 256)` with `beginStroke("ink", r, g, b, 1f, WASH, NORMAL, tip)`,
    `addDabs(same dabs)`, `endStroke()`, and assert `InkRaster.premultiplied(…)` equals
    `RefCanvas.tiles("ink")[Tiles.key(0, 0)]` **element by element, with no tolerance** — both are
    FloatArrays built by the same expression in the same order. Non-vacuity: assert the destination
    is not all zeros, so a function that returns zeros cannot pass. (`Tiles.key` is
    `cc.joycreator.joybrush.core.paint.Tiles.key`.)
13. **`stamps` is `premultiplied` plus a quantisation (D6).** The same fixture: every byte of
    `stamps` equals `(premultiplied[i] × 255f + 0.5f).toInt().coerceIn(0, 255).toByte()` for all
    four channels, and the alpha of a fully covered interior pixel is exactly `255`.
14. **No gap at any scale (D4).** A straight 400-sample line (4 doc px apart, `y = 0`), the same hard
    tip, rendered at scales 0.25, 0.5, 1, 2, 4, 8, 16 into a destination of
    `(ceil(length × scale) + 8) × (ceil(8 × scale))` with `docX = -4, docY = -4` so the stroke is
    fully inside at every scale. At every scale, **no destination pixel whose centre lies within
    0.5 destination px of the stroke's centreline has alpha 0**, and the covered run of the
    centreline row is **contiguous** from the first to the last covered pixel. Derivation for why
    this is the right property and not a tautology: consecutive dabs are `2r × spacing` doc px apart,
    which at scale `s` is `0.24s` destination px, so at every scale in the list the beads overlap
    many times over and a gap can only come from a rasteriser that stamps once and stops. The
    sharp form of the claim is that this is TRUE for a re-raster at 16× and FALSE for a magnified
    bitmap, and the non-vacuity assertion is that a nearest-neighbour 16× blow-up of the scale-1
    raster has a **different** covered-pixel count — a bitmap's edge is a staircase.
15. **The destination is addressed, not assumed (D6).** With `docX = 100, docY = 200`, a single dab
    at document (100, 200) with radius 3 covers destination pixel (0, 0)'s neighbourhood and nothing
    at all if `docX` is 101: the alpha sum of the whole destination is byte-identical only for the
    matching origin. Plus: `docX`/`docY` far from the origin (say `1_000_000`) give the same bytes as
    `docX = 0, docY = 0` for a dab moved by the same amount — i.e. the origin is a subtraction, not
    a modulo, and a 32-bit document coordinate does not wrap.
16. **The raster budget (D15).** `1 × (MAX_REGION_PX + 1)` returns `null` from `stamps` and from
    `fill`; `1 × MAX_REGION_PX` does **not** return null for a 1-px-tall stroke; and a **negative**
    `width` throws `IllegalArgumentException` while `width = 0` returns an empty array. The
    at-the-cap case allocates ~160 MiB and is the same fixture `RegionRendererTest` already runs
    (`RegionRendererTest.kt:1059`, `listOf(1L to MAX_REGION_PX, MAX_REGION_PX to 1L)`); if the
    machine cannot afford it, that test is skipped with a printed reason and the over-cap case — the
    one that is about the code — still runs. *(The cap is
    referenced, so this test fails loudly if someone raises the constant.)*
17. **A bad number never poisons a pixel (D4, D15).** `scale = Float.NaN`, `scale = 0f`,
    `scale = Float.POSITIVE_INFINITY` and `scale = -1f` all return `null`. A `Dab` with a non-finite
    `x` or `y` is **skipped**, and the destination is still the pure transparent array; a `Dab` with a
    non-finite `radius` is drawn as radius `0` (no coverage) and the others are unaffected. A NaN
    `opacity` is read as `1f`, as `MaskPaint.opacityOf` does.
18. **`fill` is non-zero, and it is JB-2.05a's fill (D12).** A polygon traced twice (a loop drawn
    twice) has the **same raster bytes** as the same polygon traced once — that is what non-zero
    winding means and `SelectionMask.polygon` already guarantees it, so a builder who wrote an
    even-odd fill fails here. A polygon of fewer than 3 points rasterises to all zeros. A polygon
    whose bounding box is wider than `SelectionMask.MAX_SELECT_SPAN` (16 384 doc px) rasterises to
    all zeros, because `polygon` returns `SelectionMask.EMPTY` for it — **stated here so it is a
    decision and not a surprise**: the fill pen is refused by the lasso's own cap, and the ceiling is
    the repo's, not this spec's.
19. **`fill` composites through `MaskPaint` (D12).** A `fill` of colour `0x80FF0000` over a
    destination of all zeros gives premultiplied bytes whose alpha is `128` and whose red is `128` —
    i.e. `MaskPaint.pixel(FILL, …)`'s answer, not `colour` written into three channels. A second
    fixture with `colorRgb = 0x00FF0000` (alpha 0) gives all zeros: `fill`'s opacity is the colour's
    own alpha byte.

## Do not

- Do **not** edit `StrokeRecord.kt`, `StrokeCodec.kt` or anything else in `stroke/` — JB-5.03a owns
  that folder, and the board's parallel-safety note says nothing else touching `stroke/` runs beside
  it.
- Do **not** edit `JbCanvasView.kt`. Not the salt, not the seed, not anything. R30 item 2 reserves it
  for the Lead, and Decision 5 says which two lines the Lead changes and when. A T2 row that "just"
  fixes the constant breaks a lock the board depends on.
- Do **not** add a `SystemClock`, a `Random`, a counter or any other source of entropy. If a test
  needs two different strokes, it uses two different `seed` values.
- Do **not** invent `DabInputs` values. `pressure` only; everything else `Float.NaN` (Decision 11).
- Do not write a second tip-coverage function, a second accumulate, a second commit, a second tile
  layout, a second scatter or a second non-zero fill. All six exist. Tests 5, 12, 13, 18 and 19 exist
  to catch you adding a seventh.
- Do not let a `smudge` or `wet` brush draw an ink line "because it is only a preview".
- Do not refuse a recording for a `hardness` outside 0..1. That is JB-0.03c (Decision 16).
- Do not cap the dab count, and do not add a `spacing` rule here.
- No `android.*` or `java.*` imports in `commonMain`. No GL, no `EraserPath`, no `InkLine` — this
  spec does not know the eraser or the picker exist.

## Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest` is green; the output is pasted into the report.
- [ ] Only the five owner-area files changed; `git status --short` is pasted into the report.
- [ ] `Scatter.SALT` is the only edit to `Scatter.kt`, and `git diff joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/brush/Scatter.kt` is one added constant plus KDoc.
- [ ] Two non-vacuity mutations were run and are reported: changing `Scatter.SALT`'s literal, and
      changing `premultiplied`'s commit factor. Both must redden at least one test.
- [ ] Committed as `JB-5.01: ink replay and raster`; pushed.
- [ ] ROADMAP row → 🟧 Built — awaiting T1 review.

## Stop rule

**Stop and write a question in `## Questions` if any of these is true; do not guess.**

1. A decision above cannot be implemented without changing a signature in the contract. The contract
   is what the Lead and 5.01b will read; a builder does not get to reshape it.
2. Two landed files disagree about the same fact (two salts, two fill rules, two tip layouts). Name
   both files and both lines; do not pick a winner.
3. A number in a test above does not come out as derived — particularly any scallop, falloff or
   coverage figure. R9: an expected value may change only with its derivation written into the test.
4. Something in this spec needs `JbCanvasView`, `GlPaintEngine`, a Gradle file or `stroke/`. Those
   are not this spec's, whatever the temptation.
5. `StrokeSmoother`, `BrushDabber`, `DabPlacer` or `Scatter` will not replay a stroke
   deterministically — twice in a row, in one run, on one machine. That is a defect in landed work
   and the report, not something to work around in `InkReplay`.

## Not in this spec (R37)

**JB-5.01b — the Lead's, T1, and its spec is not written here.** It is: the view generating the seed
at stroke start and storing it in the `StrokeRecord`; the view capturing one record per stroke; the
GL side; and the two-line `JbCanvasView` salt edit from Decision 5. It sits in R30's lock order after
JB-1.06.

# JB-5.01 — Ink layer: the strokes are the truth, and the picture is drawn from them

| | |
|---|---|
| **Tier** | T1 (it decides the replay contract) + T2 for the maths in it |
| **Status** | 🟨 Draft — one Lead ruling is missing and it is the row's whole headline (Q1) |
| **Needs** | 0.07 (`GlPaintEngine`), 1.05 (`BrushDabber`, `Scatter`, `FillPen`) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/vector/InkReplay.kt`, NEW `.../vector/InkRaster.kt`, NEW `.../commonTest/.../vector/InkReplayTest.kt`, NEW `.../commonTest/.../vector/InkRasterTest.kt`; EDIT `core/…/brush/Scatter.kt` (one `const val SALT`, Decision 11); EDIT `joybrush/androidkit/…/JbCanvasView.kt` (**that one constant only**, Decision 11). Nothing else. |
| **Estimated size** | ~260 lines + ~260 lines of tests |
| **Command** | `./gradlew -p joybrush :core:jvmTest` — 0 failures |

## Goal

Blueprint §2: on a PAINT layer the pixels are the truth; on an **INK layer the recording is**. The
same drawing, zoomed to 8×, is re-drawn from the recordings rather than magnified, so the line is as
smooth there as it was at 1×. And because the recording names the brush, the line can be given a
different brush, width or colour afterwards (R20) — which is JB-5.03's job and is only possible
because this spec makes the recording the thing that is drawn.

This spec builds **the replay and the raster only**, as two pure functions in core, so both are
testable on any JVM with no GL context and no phone. The GPU and view wiring is explicitly NOT here
(Decision 12) — it depends on the ruling in Q1.

## Contract (verbatim)

```kotlin
package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.stroke.StrokeRecord

/** Thrown when a recording cannot be replayed. The message is the whole error report. */
class InkReplayException(message: String) : Exception(message)

object InkReplay {
    /**
     * The dabs one recording draws, in stroke order, exactly as the pen drew them the first time.
     *
     * Tessellates in DOCUMENT space using [record]'s own `smoothing` and `screenPerDoc`, seeds
     * [BrushDabber] and the scatter stream with [StrokeRecord.seed], and expands scatter through the
     * same [Scatter] the drawing view used. Pure: the same record and preset give the same list,
     * forever, on every platform.
     *
     * `engine == "fill"` records draw NO dabs — a fill is a shape, and its raster is
     * [InkRaster.fill]'s business, not this one's. Every other engine than "stamp" or "fill" is
     * REFUSED by name (Decision 6).
     *
     * @throws InkReplayException if `brush` is null, or its engine cannot draw an ink line.
     */
    fun dabs(record: StrokeRecord, brush: BrushPreset?): List<Dab>

    /** Every problem that stops [record] from being drawn, in words. Empty = it draws. */
    fun refusal(record: StrokeRecord, brush: BrushPreset?): String?
}

object InkRaster {
    /** Premultiplied RGBA8 bytes, 4·w·h, row 0 = the TOP destination row (RegionRenderer's layout). */
    fun stamps(
        dabs: List<Dab>, colorRgb: Int, width: Int, height: Int, scale: Float,
    ): ByteArray?

    /** The fill pen's shape: the closed polygon [FillPen.outline] gives, filled non-zero. */
    fun fill(outline: List<Pt>, colorRgb: Int, width: Int, height: Int, scale: Float): ByteArray?
}
```

Both `raster` functions return **null** when the destination is larger than
`RegionRenderer.MAX_REGION_PX` — that constant is the cap and it is *referenced*, never copied
(R19), so the two cannot drift.

## Decisions

1. **One replay, one answer.** `dabs` is a pure function of `(record, brush)`. Nothing about the
   current zoom, the screen, the frame or the device enters it. Two calls with the same arguments
   produce byte-identical `Dab` lists — this is the promise JB-0.04's codec exists to keep, and it is
   what "the recording is the truth" means in practice.
2. **The seed comes from the record, and from nowhere else.** `BrushDabber(brush, record.seed)` and
   `SplitMix(record.seed xor Scatter.SALT)`. **This is R13's ruling, already made**: where the seed
   comes from is a presentation question, and what replay needs is that the *same* seed is in the
   record. Decision 12 is about who puts it there, which is not decided; what is decided is that
   `InkReplay` never invents one.
   - `Scatter.SALT` does not exist yet — `SCATTER_SALT` is `JbCanvasView`'s private constant
     (`JbCanvasView.kt:636`). **Moving it to `core` is part of this spec's owner area and is not
     optional** (Decision 11), because a second literal is how a scattered stroke stops replaying the
     same way (R19).
3. **Tessellation is in DOCUMENT space, at the record's own spacing.** Dabs are placed by
   `DabPlacer` at `spacing × diameter` doc px, exactly as the live drawing path does. Consequence,
   stated so nobody is surprised: a coarse brush shows its dab spacing when zoomed far enough. The
   alternative — spacing in SCREEN space — would change how many dabs a stroke has, and the dab
   count is what walks the random stream, so it would change every seeded jitter. That is a
   reproducibility-for-smoothness trade and it is **Q2**.
4. **Crispness is the rasteriser, not the tessellation.** `stamps` evaluates `TipMath.coverage` over
   the DESTINATION's own pixel grid with the dab's radius multiplied by `scale`. So zoom changes the
   resolution of an edge and nothing else. The property that makes this testable: **no transparent
   pixel on a recording's centreline, at any scale from 0.25× to 16×** (Test 7). A magnified
   bitmap fails this the moment a dab's edge lands between two pixels.
5. **The ink layer's maths is `RefCanvas`'s maths, not a second copy.** Same accumulation
   `s' = cap·cov + s·(1−cov)`, same commit `out = colour·a + dst·(1−a)`, same premultiplied RGBA8
   layout (`RegionRenderer.TILE_BYTES`). **Test 8 proves it**: `InkRaster.stamps` and `RefCanvas`
   agree **pixel for pixel** on a plain stamp stroke. A parity test, not a comment (the JB-2.20a
   pattern).
6. **Only `stamp` and `fill` draw an ink line** (R20). `dabs` refuses `"smudge"` and `"wet"` with
   `InkReplayException` naming the engine and the stroke id, because both read the pixels *underneath*
   and an ink layer has no pixels underneath — it has recordings. This closes the gap JB-1.08a's Q6
   reported: the engine↔`LayerKind` rule exists nowhere, and this is the first place it can.
   `refusal()` is the same sentence without the throw, for a UI that wants to grey a stroke out.
7. **A recording whose brush is missing is refused, in words, naming both.**
   `InkReplayException("stroke \"s3\" was drawn with brush \"ink-pen\", which is not in this
   document's library")`. It is not drawn as a default nib: a line that suddenly appears in the wrong
   brush is worse than a line that is visibly missing and says why.
8. **`colorRgb` is the RECORD's colour, not the brush's** — `StrokeRecord.colorArgb` once JB-5.03a
   lands. Until it lands, `colorArgb` does not exist, so for this spec `stamps` takes the colour as a
   parameter and the caller passes `0xFF000000` from the brush. **This is the one place JB-5.01
   touches JB-5.03a's contract, and it is a read, not a write**: JB-5.03a owns `StrokeRecord`, this
   spec does not edit it.
9. **A `fill` recording draws no dabs and no pixels from `stamps`.** Its raster is `InkRaster.fill`
   on `FillPen.outline(record.samples, record.smoothing, record.screenPerDoc)`, filled non-zero, so
   a loop drawn twice is still solid (JB-2.05a's rule, reused — not re-implemented). `dabs` returns
   an empty list for a fill record and never throws.
10. **An empty recording draws nothing and says nothing.** Zero samples, or all samples dropped by
    `StrokeSmoother` (its `droppedSamples` counter is the same faithful-drop contract as everywhere
    else), gives an empty `Dab` list and an empty raster. Not an exception: a pen that landed once
    and reported two identical positions is ordinary, not broken.
11. **A shared constant is never copied.** This spec's only numeric bounds are
    `RegionRenderer.MAX_REGION_PX` (raster size) and `BrushValidate.MAX_SIZE_PX` (which the
    `maxRadius` guard already reads). `SCATTER_SALT` moves from `JbCanvasView` to
    `core/brush/Scatter.kt` as `Scatter.SALT`; `JbCanvasView` is updated to read it. That edit is
    **one line in `androidkit` and it is in this spec's owner area** — a scatter replayed with the
    wrong salt is a different drawing, and R19 is the rule that stops the drift.
12. **The view wiring is NOT this spec.** Where `StrokeRecord`s are captured, and therefore where the
    seed is chosen and stored, is **Q1** and it is a Lead ruling. Today `JbCanvasView.startStroke`
    takes `val seed = SystemClock.uptimeMillis()` (`JbCanvasView.kt:313`), feeds it to the dabber and
    to the scatter stream, and **never records it** — and today no code anywhere constructs a
    `StrokeRecord` at all (verified: the type appears only in the codec, the model and its tests).
    So `InkReplay` can be built and fully tested against records built by hand, and the row cannot be
    called done until Q1 is answered.

## Steps

1. Tests first, written from Decisions 1–11.
2. `Scatter.kt`: add `const val SALT = 0x5CA7L` (the value `JbCanvasView` already uses, moved, not
   retyped) with a KDoc saying why it exists and that moving it is what makes replay match drawing.
3. `JbCanvasView.kt`: `SCATTER_SALT` becomes `Scatter.SALT`. Delete the private constant.
4. `InkReplay.kt`.
5. `InkRaster.kt`.

## Tests

**`InkReplayTest`**
1. **Determinism (D1):** `dabs(r, b)` called twice gives `contentEquals` on every field of every
   `Dab`.
2. **The seed is the record's and only the record's (D2):** two records identical except `seed`
   produce dab lists of the **same length** (the stream advances identically) and **different
   positions** when the brush scatters. Non-vacuity: with `scatter.amount = 0f` the two lists are
   equal, so a failure of test 2's second half is a real seed effect and not the brush.
3. **`fill` draws no dabs and does not throw (D9):** a record whose brush has `engine = "fill"`
   returns an empty list.
4. **`smudge` and `wet` are refused by name (D6):** `assertFailsWith<InkReplayException>` and the
   message contains the engine word and the stroke id. `refusal()` returns that same message and
   `dabs` for a `"stamp"` brush with a real preset returns `null`.
5. **A missing brush is refused, naming both (D7):** message contains the stroke id and the brush id.
6. **Empty and all-dropped recordings (D10):** zero samples → empty list, no throw; a recording whose
   only sample has a non-finite `x` → empty list, no throw.

**`InkRasterTest`**
7. **No gap at any scale (D4):** a straight 400-sample stroke, a hard tip (`hardness = 1`,
   `corner = 2`, `minPx = 1`), rendered at scales 0.25, 0.5, 1, 2, 4, 8 and 16 into a
   `length·scale × 3·scale` destination — the destination's centre row is **opaque (alpha 255) for
   every pixel across the stroke's length** at every scale, and no pixel outside the destination's
   bounds is read or written. This is the crispness property; a magnified bitmap fails it.
8. **Parity with `RefCanvas` (D5):** 60 samples along a straight line, a stamp brush, scale 1 —
   `InkRaster.stamps` equals `RefCanvas`'s committed layer bytes **byte for byte** over the whole
   rectangle. This is the test that makes "no second engine" true rather than claimed.
9. **Scatter really scatters (non-vacuity for D2):** with `scatter.amount = 0.5f` and `count = 4`,
   `dabs` returns 4× the length and the four copies of one input dab are **not** co-located
   (max pairwise distance > 1 doc px). A builder whose scatter call was skipped fails here, not only
   in test 2.
10. **`fill` is non-zero (D9):** a polygon traced twice (a loop drawn twice) has the same raster
    bytes as the same polygon traced once; a polygon of fewer than 3 points rasterises to all zeros.
11. **The raster budget (D11):** a destination of `MAX_REGION_PX + 1` pixels returns `null` from both
    `stamps` and `fill`; one of exactly `MAX_REGION_PX` does not return null for a 1-px-tall stroke.
    *(The cap is referenced, so this test fails loudly if someone raises the constant.)*
12. **A non-finite scale never poisons a pixel (D4):** `scale = Float.NaN` and `scale = 0f` return
    `null`, not garbage bytes.

**Command:** `./gradlew -p joybrush :core:jvmTest`. Passing = `BUILD SUCCESSFUL` and
`joybrush/core/build/test-results/jvmTest/` with 0 failures.

## Do not

- Do **not** edit `StrokeRecord.kt`, `StrokeCodec.kt` or anything in `stroke/` — JB-5.03a owns that
  folder and the board says nothing else touching `stroke/` runs beside it.
- Do **not** touch `JbCanvasView` beyond the one `SCATTER_SALT` line. Q1 is not a licence to wire the
  view.
- Do not write a second tip-coverage function, a second accumulate, a second tile layout or a second
  scatter. All four exist; test 8 and test 9 exist to catch you adding a fifth.
- Do not let a `smudge` or `wet` brush draw an ink line "because it is only a preview".
- No Android or `java.*` imports in `commonMain`. No GL.

## Definition of done

- [ ] tests pass (paste `:core:jvmTest` output)
- [ ] only owner-area files changed (paste `git status --short`)
- [ ] committed as `JB-5.01: ink replay and raster`; pushed
- [ ] ROADMAP row → 🟧 Built **once Q1 is answered**; until then it stays Draft

## Questions — for the Lead

**Q1. THE RULING THIS ROW IS WAITING FOR: where does a stroke's seed come from, and who writes the
recording?**

Today `JbCanvasView.startStroke` reads `SystemClock.uptimeMillis()` into a local (`JbCanvasView.kt:313`),
gives it to the `BrushDabber` and to `SplitMix(seed xor SCATTER_SALT)`, and drops it. No code in the
tree constructs a `StrokeRecord` at all. So an ink layer cannot exist yet, and the blueprint's
"strokes are recordings" promise (blueprint §2; JB-1.05b's Finding 1, filed MAJOR, downgraded to
MINOR by R13) has no producer.

R13 ruled the *consumer* side: "the `StrokeRecord` gets the exact seed the `BrushDabber` used (the view
keeps it for the stroke's life)". That is what Decision 2 implements. What it does **not** say is
where the seed is *born*, and that has three shapes with different consequences:

- **(a) Keep `uptimeMillis()`, thread it into the record.** Closest to today's drawing path — the seed
  a stroke is replayed with is bit-for-bit the one its dabs were made with, including for every
  stroke already drawn. It needs the view to hold the seed for the stroke's life and hand it over at
  pen-up, and it makes `StrokeRecord` construction a view responsibility.
- **(b) Derive the seed from the stroke's own id** (a hash of the record's `id`, say). Then a record
  is self-contained: reopen, copy between documents, paste into another layer, and it replays
  identically with nothing remembered anywhere. It does **not** reproduce the jitter of strokes drawn
  before it, which is invisible today (no records exist) and irreversible later.
- **(c) A counter or a document-scoped sequence.** Reproducible per document, not across documents.

My read is that this is a Lead call because it decides where a responsibility lives (the view or the
model), and because (a) and (b) disagree about what "the same stroke" means. **What the ruling must
cover:** which of (a)/(b)/(c); whether `JbCanvasView` or core owns it; whether ink-layer stroke
capture is its own task (it is a real chunk of view work, and the board's app-file serialisation
order matters if it lands beside JB-2.06b / JB-2.03a, which both edit `JbCanvasView`); and whether
JB-5.01 stays one row or splits into "replay + raster (T2, buildable now)" and "capture in the view
(T1)".

**Q2. Is crispness worth breaking bit-exact replay?** Decision 3 tessellates in document space, so a
`spacing = 0.2` brush shows its beads at 16×. Placing dabs in SCREEN space instead (spacing
`min(spacing, 1/screenPerDoc)`) removes the beads — and changes the dab count at every zoom, which
changes what `SplitMix` hands out, so the same recording draws a *different* scatter at 4× than at
1×. That is exactly the promise `SplitMix`'s own KDoc makes ("the same seed gives the same numbers")
and `StrokeCodec`'s ("byte for byte"). **The ruling I need:** is an ink line's randomness a property
of the recording (my Decision 3) or of the viewing zoom? My answer is the recording, and I have
written the spec that way — but it is a visible product choice, not a technicality.

**Q3. `TipShape.hardness` is not validated in 0..1, and this spec is the first renderer that feeds a
whole layer's worth of it from an imported brush.** JB-8.03's Q10 reached the same wall and referred
it to JB-0.03b, which has not landed it. `InkRaster` clamps (`TipMath.coverage` already does
`coerceIn(0f, 1f)`), so this cannot crash — but a brush carrying `"hardness": {"base": 1e30}` will
draw a hard edge with no word said. **Ruling needed:** does the 0..1 range rule land in JB-0.03b
before Phase 8, or does this spec refuse a recording whose brush has a non-`0..1` hardness with a
sentence?

**Q4. Low risk, ruled provisionally — say if you disagree.** `dabs` returns a `List<Dab>` and so
allocates. A 2 000-sample recording at `spacing = 0.005` is ~10⁶ dabs, which is a stall, not a crash.
I have **not** put a cap on it, because capping dabs means refusing a stroke somebody drew and I would
rather that be your call than mine. Provisionally: no cap in this spec; if you want one it belongs on
`spacing` (`BrushValidate` rule 4's lower bound of 0.005 is what makes it reachable) rather than on
the ink path.

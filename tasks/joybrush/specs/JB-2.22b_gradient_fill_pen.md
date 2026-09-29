# JB-2.22b — The fill pen with a gradient fill: "set a shape as this"

| | |
|---|---|
| **Tier** | T2 |
| **Status** | 📝 Draft spec — see ROADMAP.md |
| **Needs** | JB-2.22 (`Ramp`, `GradientPaint`, the editor), JB-1.08a (the fill pen, `BRUSH_VERSION = 2` — Built 🟧) |
| **Owner area** | EDIT `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/brush/BrushPreset.kt` (a `fill` field), `BrushJson.kt` (version 3), `BrushValidate.kt`; NEW `.../brush/FillPenFill.kt`; EDIT `.../commonTest/.../brush/BrushTest.kt`, `.../doc/EnumFreezeTest.kt`; NEW `joybrush/brushes/fill-gradient/brush.json` + its line in `brushes/index.txt`; EDIT `joybrush-android/.../tools/GradientTool.kt` (the "set as this" action) |
| **Estimated size** | ~150 lines of core + ~180 lines of tests + one preset file |

## Goal

The owner's phrase, quoted in the ROADMAP row: *"Fill pen with a **gradient fill** ('set a shape as
this', owner)."*

Two halves, and the second half is the owner's words rather than mine, so it is worth being precise
about what they mean:

1. **A fill-pen stroke can be filled with a gradient** instead of a flat colour — so the same
   brush-driven, re-brushable, "ends joined" shape (R20, R21) also takes a gradient. The brush file
   gains a `fill`, and the file format gains a version (R3).
2. **"Set a shape as this"** — a shape that is already in the drawing becomes the current gradient.
   This is the *one* half of this row that is a genuine open question, and it is Question 1 below,
   because "shape" could mean the fill pen's own recorded stroke and I do not know which.

## Contract

```kotlin
package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.gradient.Ramp

/**
 * A fill pen's paint. `SOLID` is the whole of JB-1.08a's behaviour; `GRADIENT` is this row.
 *
 * The ramp lives IN the brush file, so a gradient fill pen is a portable brush like any other
 * (blueprint §3.2: "a small folder … Pure data: shareable, importable, safe").
 */
@Serializable data class BrushFill(
    val kind: String = "solid",        // "solid" | "gradient"
    val ramp: RampData? = null,        // null unless kind == "gradient"
    val placement: String = "linear",  // "linear" | "radial" | "curve"
    val angleDeg: Float = 0f,          // the shape's own orientation; 0 = along the stroke
)

@Serializable data class RampData(
    val stops: List<StopData>,         // position 0..1, argb
    val bias: Float = 0.5f,
    val mirror: Boolean = false,
    val flip: Boolean = false,
    val bands: Int = 0,
) { @Serializable data class StopData(val position: Float, val argb: Int) }
```

`BRUSH_VERSION` becomes **3**; `BrushJson.encode` writes `"version": 3` **only** for a brush that
uses a gradient fill, and `decode` accepts 1, 2 and 3 — the same minimal-bump discipline JB-1.08a's
Q2 ruled for version 2.

## Decisions

1. **The ramp lives in the brush FILE, not in the document and not in a session.** A gradient fill
   pen is then shareable and importable like any other brush, which is blueprint §3.2's promise and
   the reason it is worth a version bump. The cost: every fill-pen stroke written with it carries
   the ramp, and a 20-stop ramp is a few hundred bytes per brush, not per stroke.
2. **The gradient follows the STROKE, in the stroke's own frame.** `FillPen.outline` gives the
   closed shape; the gradient runs from the first point to the last point's region — i.e. along the
   direction the person drew, at `angleDeg` from that. **A gradient across a shape the person drew
   in a circle therefore runs around it**, which is almost always what "fill this shape with a
   gradient" means, and is the one reading that needs no extra gesture. `placement = "radial"` centres
   it on the outline's centroid instead, and `"curve"` bows it. See Question 2.
3. **The fill is computed ONCE, at the lift, and rasterised through `GradientPaint`.** The live
   preview while the pen is down shows the SHAPE in the ramp's first colour at 60 % (the same
   treatment JB-2.06b gives the flat fill pen), and the real gradient appears on lift. A live
   gradient preview means re-rasterising the outline every frame, which is affordable for small
   shapes and not for a 10 000-point one — and a preview that lags is worse than a preview that
   approximates.
4. **R20 is untouched and still true: only `stamp` and `fill` on an INK layer.** A gradient fill pen
   is a `fill` engine, so it works on an ink layer the moment JB-5.01 renders one, and it is refused
   on a smudge/wet brush pick (JB-1.08a's Q6, referred and implemented in JB-2.04).
5. **"Set a shape as this" is a single action in the gradient editor, and it needs no new maths.** It
   is: take the current selection (a `SelectionMask`, from a lasso or from the fill pen's own
   outline), read the colours that are actually there along the placement's axis, and build a `Ramp`
   from them. **A shape's colours become the gradient** — which is the reading of the owner's phrase
   I have used, and it is the one that needs no new format and no new storage.
   **A shape's OUTLINE becomes a gradient's PLACEMENT** is the other reading; see Question 1.
6. **The ramp that "set a shape as this" produces is edited in the shared editor and lives wherever
   the caller puts it** — in the current fill pen's brush copy, or in the gradient tool's last ramp
   (JB-2.22's). **It is never written into the document as a shared object** (JB-2.22's Decision 2
   keeps gradients out of the document), and it is never written into an existing stroke: a stroke
   that is already rasterised stays rasterised. Re-doing it means drawing it again, or re-brushing
   it on an ink layer once JB-5.03 lands.
7. **A gradient fill pen with a malformed ramp is REFUSED in words at decode and at validate** —
   never a ramp that silently reads as a single colour. Stops out of order, a stop outside 0..1, a
   `kind` that is neither `"solid"` nor `"gradient"`, `bands < 0`, `bias` non-finite: all problems,
   in a list, the way `BrushValidate` already does everything. **A `GRADIENT` with a null ramp is a
   problem, not a solid fill** — the same reasoning JB-1.08a Q4 used for `engine "fill"` needing
   version 2.
8. **The shipped preset** `joybrush/brushes/fill-gradient/brush.json`: id `fill-gradient`, name
   "Fill pen (gradient)", engine `fill`, `size.base = 8` (JB-1.08a's ruled number, for the same
   reason: the key must be there for the file to decode), `opacity.base = 1`, `smoothing = 0.3`,
   `blend = "normal"`, `license CC0`, and a **five-stop blue→white→transparent** ramp, which is the
   one that shows the feature off (it has a hard edge, a soft edge and an alpha stop in three
   colours).

## Tests

Added to `BrushTest.kt` (where JB-1.08a's own format tests live — its Q5 explains why that is the
file, and this row follows it rather than inventing a new one):

1. **Version discipline (R3):** the new preset encodes `"version": 3`; the **fill pen encodes
   `"version": 2`** and the ink preset still encodes `"version": 1"` — **all three asserted, because
   "ordinary brushes stay readable by older builds" is only true if it is tested.**
2. `decode` accepts 1, 2 and 3. A **v2 file that uses a gradient fill is REFUSED** with the exact
   sentence *"a gradient fill needs brush version 3"* — the same minimal-bump shape as JB-1.08a's
   *"engine \"fill\" needs brush version 2"*, and asserted by equality.
3. **The preset round-trips** through `encode` → `decode` with identical stops, bias, mirror, flip,
   bands and placement — including a stop at 0.0 and one at 1.0 (the endpoints are where an
   off-by-one shows).
4. **Validation, one problem per line:** stops out of order; a stop at 1.4; `kind = "sideways"`;
   `kind = "gradient"` with a null ramp; `bands = -1`; `bias = NaN`; an empty stop list. **Each is a
   named line and the test asserts the count and the wording** — `BrushValidate`'s existing
   contract is one problem per line and this row must not blur two into one sentence.
5. **R20 still holds:** `fill-gradient` is an `INNER`-compatible engine (`fill`) and
   `engineAllowedOn("fill", INK)` is true (JB-2.04's `LayerRules`); a smudge brush is still refused
   there.
6. **Raster maths (new `FillPenFillTest`):** a square outline from `FillPen.outline` with a
   black→white `Ramp` at `placement = "linear"` and `angleDeg = 0` → the first drawn point's end is
   black and the last point's end is white, and every pixel is on the ramp. The same with
   `angleDeg = 90` → the gradient runs across, and the two results are **the transpose of each
   other** (a strong, non-tautological assertion: it fails if either axis is ignored).
7. `placement = "radial"` centres on the outline's centroid and the centre pixel is the first stop.
8. A gradient fill pen over an **opaque** existing pixel with `opaque = false` composites
   source-over and the result is **premultiplied** (`colour ≤ alpha`) — the invariant from
   `GradientPaint`, asserted again here because this is a second caller.
9. **"Set a shape as this":** a lasso over a region that fades from black to white → the produced
   `Ramp` has ≥ 2 stops and its ends are within 2/255 of black and white at the sampled positions.
   And a mask that is entirely one colour produces a **one-stop ramp** — which is legal, and is
   exactly what a flat region is.
10. `ShippedBrushFilesTest` covers the new folder exactly as it covers the other two (a preset that
    is not in `index.txt` is a preset nobody can pick).

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures.

## Owner check (Note 9)

Pick the gradient fill pen, drag a closed loop → the shape previews in the ramp's first colour and
lands as a gradient running the way you drew. Redraw it the other way round → the gradient runs the
other way. Set a shape as this (lasso a photo-like gradient region, then the action) → the editor
opens holding that region's colours, and the next fill pen you draw uses them. Export the brush and
re-import it → it still has its gradient. Open the file in an older Joy Brush → it says the file
needs a newer version, in words.

## Do not

- **Do not add a live gradient object to the document** (Decision 6). JB-2.22's Decision 2 keeps
  gradients out of it and this row does not reopen that.
- Do not edit `FillPen.outline` — it is JB-1.08a's, reviewed, and the second review re-derived its
  outline maths independently. This row consumes it.
- Do not re-rasterise the gradient live on every pen frame (Decision 3).
- Do not write the ramp into a stroke, a layer or the document.
- Do not widen the version bump: `"version": 3` only for a gradient fill, exactly as JB-1.08a's
  ruling Q2 required for version 2. An ordinary brush that starts writing 3 stops every brush
  anybody already has from opening in an old build.
- Never run gradle on the owner's PC.

## Definition of done

- [ ] tests pass (paste)
- [ ] `git status --short` shows only owner-area files
- [ ] committed `JB-2.22b: gradient fill pen`
- [ ] ROADMAP row → 🟧 Built

## Questions

_(Spec writer, `openrouter/stealth/space-bunny-alpha`, 2026-09-29. The owner's five words — "set a
shape as this" — are the only genuinely ambiguous thing in this row and I have not resolved it by
guessing.)_

### 🔴 For the Lead — "set a shape as this" has two readings and they are different features

1. **Reading A (Decision 5, what I have specced): the shape's COLOURS become the gradient.** You
   lasso a region of a drawing, tap "set as this", and the gradient editor opens holding that
   region's colours — so you can then re-use the drawing's own palette, or tweak a captured
   gradient. **Needs no new format, no new storage, and no new maths.**
   **Reading B: the shape becomes the gradient's PLACEMENT** — "set this shape as the gradient", so
   the gradient is then *shaped* by that outline wherever it is next drawn, rather than clipped by
   it. That is closer to "set a shape as this" read literally, and it is a **mask-carrying
   gradient**: the shape travels with the gradient.
   **Reading B is a much bigger thing** — it needs a mask in the ramp, a new version, and a place to
   live that is not the document (or a `DOC_VERSION` bump and one). **Which did the owner mean?**
   I have built A because B is a different feature with a different blast radius, and because A is
   useful on its own. But I am not confident, and a builder handed this spec will build A.
2. **Which shape?** Reading A works on any `SelectionMask` — a lasso, a rect, an ellipse, or the
   fill pen's own outline. **Does "shape" here mean specifically a fill-pen stroke (R20's swappable
   vector shape), or any selection?** I have used "any selection", which is the more useful reading
   and the one the machinery already supports.
3. **Where does the captured ramp GO?** Decision 6 offers two homes (the current fill pen's brush
   copy, or JB-2.22's last ramp) and does not choose. My inclination: **both** — the action sets the
   tool's last ramp AND, if the active brush is a fill pen, its `fill.ramp`, so the next stroke
   uses it without a second tap. **Confirm, or say the action is tool-level only.**
4. **The gradient runs ALONG the stroke (Decision 2), and for a circle that means round it.** The
   alternative — across the shape's bounding box — is what a designer would expect and what a
   painter drawing a lasso-and-fill would not. **This is a look decision and it is a one-line
   change once someone has seen it wrong on the phone.** I went with the stroke's own direction
   because the shape *is* the stroke (R20) and the gradient following the mark is the more
   surprising-and-pleasing of the two.
5. **The preset's ramp is mine** (Decision 8): blue → white → transparent, three colours, five stops.
   It exists so the feature is visible the moment the brush is picked. **Change it freely** — it is
   one file and one test.

### Low-risk, ruled provisionally

6. **Preview is the ramp's first colour at 60 %, and the gradient lands on the lift** (Decision 3).
7. **A one-stop ramp is legal** (test 9) — a flat region is a flat gradient, and refusing it would
   be refusing the most common case.
8. **`BRUSH_VERSION = 3` only for a gradient fill** (Decision 1 and its "Do not").

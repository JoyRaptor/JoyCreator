# JB-2.22 — The gradient tool: the app's own gradient editor, and drag-to-place gradients

| | |
|---|---|
| **Tier** | T2 |
| **Status** | 📝 Draft spec — see ROADMAP.md |
| **Needs** | D.05 (`GradientRamp`, `GradientRampEditorView` — "THE standard gradient editor, app-wide" — into `:studiokit`) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/gradient/GradientEval.kt`, NEW `.../gradient/GradientPaint.kt`; NEW `.../commonTest/.../gradient/GradientEvalTest.kt`, `GradientPaintTest.kt`; NEW `tools/gradient-golden/gen_gradient_golden.sh` + `GenGradientGolden.java`; NEW `joybrush-android/.../tools/GradientTool.kt` (the drag state machine) |
| **Estimated size** | ~260 lines of core + ~230 lines of tests; ~60 lines of tooling; ~250 lines of the drag machine |

## Goal

ROADMAP JB-2.22: *"Gradient tool: app-wide gradient editor bar (`GradientRampEditorView`) + drag to
place **linear / radial / curve** gradients, **baked into the layer**, clipped to a selection or to a
fill-pen shape."*

Three parts, and they are separable:

1. **The editor** is the Studio's `GradientRampEditorView` — D.05 moves it and calls it *"THE
   standard gradient editor, app-wide"*. R23: share it, do not build a second one.
2. **Placement** is a drag: pull a line, pull a circle out of a point, or pull a curve with a
   centre and a direction. Three kinds, one gesture, distinguished by the drag.
3. **The maths** — evaluating a ramp at a position, and painting a region through it — is Joy
   Brush's, because Joy Brush's core is pure Kotlin and cannot call the Studio's Java (R23, the same
   wall JB-2.20a hit).

## Contract

```kotlin
package cc.joycreator.joybrush.core.gradient

/** A ramp, as a plain list of stops. NO Java, so the core can hold it. */
data class RampStop(val position: Float, val argb: Int)   // position 0..1, sorted

/** What the Studio's `GradientRamp` becomes, and what a fill-pen brush file carries. */
data class Ramp(
    val stops: List<RampStop>,
    val bias: Float = 0.5f,        // the Studio's ramp bias
    val mirror: Boolean = false,
    val flip: Boolean = false,
    val bands: Int = 0,            // posterise the ramp into N bands; 0 = continuous
)

/** Where the gradient goes. All in DOCUMENT px; the drag is in screen px and converted once. */
sealed class GradientPlacement {
    /** From [a] to [b]: t = projection onto the segment. A zero-length segment is a refusal. */
    data class Linear(val a: Pt, val b: Pt) : GradientPlacement()
    /** From [centre] out to [edge]: t = distance / radius. A radius ≤ 0 is a refusal. */
    data class Radial(val centre: Pt, val edge: Pt) : GradientPlacement()
    /** From [a] to [b] along a curve with [strength] (0 = linear, ±1 = the strongest bow). */
    data class Curve(val a: Pt, val b: Pt, val strength: Float) : GradientPlacement()
}

object GradientEval {
    /** The colour at [t] in 0..1, straight sRGB, alpha included. Unclamped t is clamped. */
    fun at(ramp: Ramp, t: Float): Int
    /** The position in 0..1 for a document point under [placement]. May be outside 0..1. */
    fun parameter(placement: GradientPlacement, p: Pt): Float
    fun refusalFor(placement: GradientPlacement): String?
}

/** Bakes the gradient into tiles. The same shape as `MaskPaint.apply`, for the same reason. */
object GradientPaint {
    /**
     * @param mask when non-null, only these pixels are painted (a selection or a fill-pen shape).
     * @param opaque when true, the painted colour REPLACES the pixel (alpha from the ramp), so a
     *   gradient can be laid down opaquely. When false the gradient's own alpha composites
     *   source-over, and a ramp with transparency fades into what is under it.
     */
    fun apply(
        layer: Map<Long, ByteArray>,
        mask: SelectionMask?,
        ramp: Ramp,
        placement: GradientPlacement,
        opacity: Float,
        opaque: Boolean,
    ): Map<Long, ByteArray?>
}
```

## Decisions

1. **`GradientRampEditorView` is used unchanged.** D.05 moves it, D.05b moves its resources, and it
   is the app-wide editor. Joy Brush's part is: feed it a `GradientRamp` on open, read one on close,
   and convert to/from `Ramp`. **The converter is a generated-or-golden-checked translation, not a
   hand copy** — the same discipline as JB-2.20a, for the same reason (R23: Joy Brush's core cannot
   CALL the Java, so where it needs the same maths it must be **proven** equal, never copied by
   eye). `tools/gradient-golden/gen_gradient_golden.sh` runs the Studio's `GradientRamp` over a
   fixed-seed set of positions and commits the answers; `--check` byte-compares.
2. **Baked means baked.** The gradient is rasterised into the layer's tiles and there is **no
   gradient object left in the document** — the same word as "baked" everywhere else in this project
   (JB-2.05b's warp bakes pixels; `MaskPaint` bakes paint). Consequence stated plainly: **you
   cannot drag a baked gradient afterwards**; the only way to change it is to re-drag it, which
   paints again. That is a real limitation and it is what "baked" means. See Questions.
3. **The three placements, one gesture, decided at lift, never mid-gesture.** The drag starts at
   `A` and ends at `B`, and the kind comes from the gesture's own shape, latched at lift for the
   same reason JB-2.16a latches its axis:
   * a **LINEAR** drag — any drag, by default;
   * a **RADIAL** drag — the drag started with the second finger down, or the drag was a long
     press-then-drag; (see Questions — this one is a guess);
   * a **CURVE** drag — the drag started inside the existing gradient's own band and pulled
     perpendicular to it. **Or: a mode chip in the editor bar.** See Questions 3; I have specced
   the chip because a gesture-only rule is a gesture nobody can discover.
4. **A live preview during the drag, on the overlay** — the ramp's colours along the line you are
   pulling, at 60 % opacity, on the same one transparent overlay View as JB-2.06b/2.03a/2.12.
   **Nothing is written to the engine until the lift**, so a cancelled drag costs nothing and adds
   no undo step.
5. **`GradientPaint` is `MaskPaint`'s shape, deliberately** (R21 keeps `MaskPaint` alive as the
   fill pen's raster maths, and JB-2.14c/2.05 lean on the same "return only the tiles that changed,
   `null` means delete" convention). One convention for "edit a layer's pixels", so a builder
   writing a fourth pixel editor does not invent a fifth. `MaskPaint.apply` is **not** modified and
   not called — the maths is different (a ramp, not a flat colour) and sharing the code would couple
   two things the project has deliberately kept apart.
6. **Clipping to a selection or a fill-pen shape is the SAME `mask` parameter**, because both are a
   `SelectionMask` (JB-2.05a) by the time they arrive: a lasso IS a `SelectionMask.polygon`, and a
   fill-pen stroke's outline IS a `SelectionMask.polygon` of `FillPen.outline`. **No new type, no
   new path** — and the two sources are indistinguishable here, which is correct, because they mean
   the same thing.
7. **`opaque` is a real choice, not a convenience.** A gradient with a transparent stop, painted
   opaquely, gives a hard cut at the stop (a "duotone band"); painted source-over, it fades into
   what is under it. The editor bar has the switch and **it defaults to source-over**, because
   that is what a gradient on a drawing usually is.
8. **Refuse, don't clamp, the three degenerate placements** (Decision: `refusalFor`): a zero-length
   line, a radial edge at the centre, and a non-finite point anywhere. These come from a gesture
   that produced no drag, and a refusal in words ("drag out from the centre") is what a person needs;
   silently painting a single colour is the answer nobody mistakes for a bug. **The ramp's own
   values ARE clamped** (a stop at 1.4 clamps, `bias` outside 0..1 clamps) because those come off
   sliders — the same "slider overshoot" rule `FillOptions` uses.
9. **`bands` is the ramp's posterise, and it is `GradientRamp`'s own field** — D.05 lists it, so it
   is carried across rather than reinvented, and it is one of the reasons the golden check matters.
10. **Linear stops interpolate in premultiplied straight sRGB with the alpha channel as a fourth
    component** — i.e. the same shape as `Resample.BILINEAR` (JB-2.05b Decision 3) and the same
    premultiplied discipline as every pixel path in the project. **The golden check is what decides
    whether the Studio agrees**; if the Studio interpolates in a different space, that is a Lead
    question and not something a builder tunes until the test goes green (R9's standing rule: an
    expected value changes only with its derivation written into the test).
11. **A gradient fills the whole layer's extent unless a mask says otherwise.** Not the visible
    screen rect and not the board: the layer's tiles. That is what makes a baked gradient behave
    like the paint around it when the page is panned. It is also why the destination span is bounded
    and refused (Decision 12).

## Tests

`GradientEvalTest` (JVM, `:core:jvmTest`):
1. **The golden check:** every available position/ramp pair from `tools/gradient-golden` matches
   `GradientEval.at` within 1e-6, and `--check` exits 0. **If the Studio's `GradientRamp` has no
   callable Java evaluation, the golden is a transcription of the documented behaviour and the
   tolerance is 1/255** — which is stated in the generated header and in Questions, never chosen to
   make a run green.
2. **Endpoints and corners:** a two-stop ramp at t=0, 0.5, 1 gives the stops and the exact midpoint;
   a 1-stop ramp gives that colour at every t; t outside 0..1 clamps.
3. **`bias` really biases:** a ramp whose bias is 0.25 reaches its second stop at t = 0.25 — and the
   test derives the expected position rather than reading it from the table.
4. **`mirror` and `flip`:** `mirror` reflects t about 0.5 (so t=0 and t=1 give the same colour);
   `flip` reverses the stop ORDER (so t=0 gives what was the last stop). Asserted separately — they
   are different operations and conflating them is easy.
5. **`bands`:** `bands = 4` on a continuous ramp gives at most 4 distinct colours over t ∈ 0..1, and
   `bands = 0` is continuous. Asserted by counting distinct outputs, not by reading the table.
6. **Placement parameters:** a Linear from (0,0) to (100,0): t at (25,0) is 0.25; t at (50,30) is
   **0.5** (projection ignores the perpendicular offset — the named case); t at (150,0) is **1.5**,
   and `at` clamps it. A Radial centred (0,0) with edge (50,0): t at (25,0) is 0.5; t at (0,25) is
   also 0.5 (a circle, not an ellipse — the test says so in a comment). A Curve with strength 0
   equals its Linear exactly, at 40 points.
7. **Refusals:** a zero-length linear, a radial whose edge is its centre, and a `Pt(NaN, 0)` all
   return a **sentence naming the problem** and `GradientPaint` writes nothing.

`GradientPaintTest`:
8. **Bake:** a 100 × 100 layer, an opaque black→white linear across it, `opaque = true` → every
   pixel's colour is the ramp's and its alpha is the ramp's; with `opaque = false` over an empty
   layer the result is the same, and over an opaque red layer the right half blends toward red.
9. **Mask:** with a 10 × 10 `SelectionMask.rect` inside a 100 × 100 layer, only those 100 pixels
   change and `apply` returns only the tiles that differ. A mask of `EMPTY` → an **empty map**, not
   a full-layer repaint.
10. **Premultiplied invariants:** for every pixel, `colour ≤ alpha` (an un-premultiplied mix-up is a
    dark halo on a soft edge and nothing else) — asserted on the baked result.
11. **A tile that becomes empty is returned as `null`** (the `MaskPaint` convention, so
    `replaceTiles` deletes it) and a tile that did not change is **not returned at all**.
12. **The Long rule (R19):** a placement spanning more than `MAX_SELECT_SPAN` is refused **in words**
    before an Int multiplication, and a placement at `Int.MAX_VALUE` is refused — not wrapped, not
    allocated. The test states what the Int version would have computed.
13. **The round trip through the editor:** a `Ramp` → the Studio's `GradientRamp` → back → the same
    stops, bias, mirror, flip and bands. (Harness test, `:studiokit`'s sourcepath, like D.05's.)

**Command:** `bash tools/gradient-golden/gen_gradient_golden.sh --check` exits 0, and
`./gradlew -p joybrush :core:jvmTest` — 0 failures.

## Owner check (Note 9)

Open the gradient tool, drag across the canvas → a live gradient follows the finger, and on lift it
is in the layer. Drag again elsewhere → the second gradient is there too, on top. Lasso a region
first, then place a gradient → **only the lassoed region is painted**. Draw a closed shape with the
fill pen, place a gradient with the shape still selected → only the shape. Zoom out → the gradient is
where you put it (Decision 11). Export PNG → the gradient is in the file, in the right place.

## Do not

- **Do not build a gradient editor.** `GradientRampEditorView` is *THE* editor (D.05). R23.
- Do not modify `GradientRamp`, `GradientCurve` or `GradientRampEditorView`.
- Do not leave a gradient object in the document (Decision 2) — if you need one, that is a
  different feature and it needs its own version bump and its own export story.
- Do not modify `MaskPaint` (Decision 5) — share the shape, not the code.
- Do not tune an expected value to make a test pass. R9: the derivation goes in the test.
- Never run gradle on the owner's PC.

## Definition of done

- [ ] `gen_gradient_golden.sh --check` exits 0 (paste)
- [ ] tests pass (paste)
- [ ] `git status --short` shows only owner-area files
- [ ] owner check noted
- [ ] committed `JB-2.22: gradient tool`
- [ ] ROADMAP row → 🟧 Built

## Questions

_(Spec writer, `openrouter/stealth/space-bunny-alpha`, 2026-09-29.)_

### 🔴 For the Lead

1. **"Baked into the layer" means you cannot move a gradient afterwards** (Decision 2), and I want
   that on the record rather than discovered. Every other app that bakes behaves this way, and the
   word "baked" in the roadmap is doing real work. **The alternative is a live gradient object in
   the document** — movable, re-gradable, and a `DOC_VERSION` question, an export question (PNG has
   no such concept; PSD and ORA have their own) and a JB-2.13b "helpers are never exported" style
   question of its own. **I read "baked" as the first and I think it is right. Confirm.**
2. **Does `GradientRamp` have a Java method that samples a position, the way `BlendModes.blend`
   does?** This is the same question as JB-2.21's Question 1 and it decides whether the golden here
   is *proof* (the Studio's own bits, tolerance 1e-6) or a **transcription of documented behaviour**
   (tolerance 1/255). **Those are very different guarantees and I will not pretend the second is the
   first** — Decision 10 and test 1 say which one the run has.
3. **How is a CURVE gradient placed, and is a RADIAL one too?** Decision 3 is my weakest decision
   in this spec. Radial by second finger is a gesture I invented; a mode chip in the editor bar is
   discoverable and is what I have actually specced for CURVE. **The honest answer is probably a
   three-way chip — line / circle / curve — in the editor bar, and no gesture-only rule at all.**
   Say so and I will simplify Decision 3; leaving it half-gesture is the kind of half-measure that
   produces a feature nobody finds, which is exactly what JB-2.17 exists to prevent.
4. **The linear interpolation space (Decision 10).** I have specced premultiplied-straight, which
   is this project's house rule for every pixel path. If the Studio interpolates in linear-light or
   in straight sRGB, the golden check will fail and the Lead has to choose which is the authority —
   and R23 says the Studio is. **I have not pre-decided this because guessing it and being wrong
   would silently bake the wrong gradients into people's drawings.**
5. **A gradient clipped to a fill-pen shape: does the shape's own colour matter?** I read "clipped to
   … a fill-pen shape" as the shape being a MASK (Decision 6), so a shape filled with red and a
   gradient over it looks like a gradient inside a red shape. **The other reading is that the shape
   IS the gradient** — that is JB-2.22b ("set a shape as this"), and if you meant that here, this
   row and 2.22b are the same row.

### Low-risk, ruled provisionally

6. **A live preview on the overlay during the drag, nothing committed until the lift** (Decision 4).
7. **Placement kinds are latched at lift** (Decision 3), the same rule as JB-2.16a's axis lock.
8. **Degenerate placements are refused in words; ramp values from sliders are clamped** (Decision 8)
   — a gesture is a person, a slider is a control, and the two want opposite treatments.
9. **A gradient fills the layer's extent, not the screen and not the board** (Decision 11).

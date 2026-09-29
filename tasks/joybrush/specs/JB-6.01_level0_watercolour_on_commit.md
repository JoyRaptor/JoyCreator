# JB-6.01 — Level 0: the watercolour look, applied when the stroke commits

| | |
|---|---|
| **Tier** | T1 (it decides what `engine: "wet"` means and it edits `GlPaintEngine`'s reviewed commit path) |
| **Status** | 🟨 Draft — **only the Lead or a cross-reviewer may set this to `🟦 Ready`** (ROADMAP §3). Two contract questions are open (Q1 the brush version, Q2 the edge band's width) and the whole tuning table is the owner's (Q3) |
| **Needs** | JB-1.05 (the brush file drives the stroke) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paint/WetLook.kt` · NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/paint/WetLookTest.kt` · NEW `joybrush/shaders/jb_wet.glsl` · EDIT `joybrush/shaders/jb_commit.frag` (one `#include` + the wet block) · EDIT `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paint/RefCanvas.kt` (`endStroke`'s three lines) · EDIT `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt` (`beginStroke` signature, `setCommitUniforms`, `bindTextures`) · EDIT `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt` (`beginStrokeNow` passes `p.engine`) · EDIT `joybrush/core/src/commonTest/.../paint/PaintTest.kt` (additions only) |
| **Estimated size** | ~260 lines of Kotlin + ~90 lines of GLSL + ~330 lines of tests |

> **The three facts this spec is built on, all read off the tree, not off the blueprint.** They are
> here because each one bounds what Level 0 can honestly be.
>
> **1. `engine: "wet"` is an accepted word with nothing behind it.** `BrushPreset.engine`
> (`BrushPreset.kt:64`) documents `"stamp" | "smudge" | "wet" | "fill"`, and `BrushValidate.ENGINES`
> (`BrushValidate.kt:16`) accepts all four — but **nothing in the render path ever reads
> `preset.engine`.** `JbCanvasView.beginStrokeNow` reads `accumulate`, `blend`, `opacity` and `tip`,
> and stops. So a `brush.json` saying `"engine": "wet"` is validated, loaded, listed in the brush
> pill, and drawn as an ordinary stamp brush with an ordinary hard tip. That is a live defect, and
> this spec is the first row that has to confront it (Q1).
>
> **2. What `accumulate: "wash"` actually does today is one thing, and it is not wetness.**
> `BrushDabber.look` (`BrushDabber.kt:124`) gives every dab `cap = the brush's opacity curve,
> evaluated per dab`; the stroke buffer then does `s' = cap·d + s·(1−d)` (`jb_dab.frag`), so a
> stroke converges to that cap and never passes it; and `GlPaintEngine.setCommitUniforms` sends
> `u_strokeScale = 1` for a wash (`:481`). So: **`wash` is a per-stroke self-overlap ceiling — the
> Krita "alpha darken" behaviour (R3 §5.5) — and it is a genuinely good idea that is already
> shipped, already tested (`PaintTest.washNeverPassesItsOpacityEvenWhereTheStrokeCrossesItself`),
> and entirely orthogonal to water.** Two *different strokes* overlapping is untouched by it. There
> is no wet mask, no water, no drying, no pigment, no wet set, and no `engine` dispatch anywhere.
>
> **3. The commit is the only place with both images already bound.** `jb_commit.frag` is handed the
> existing layer tile AND the single-channel stroke buffer, and it is used **twice with the same
> maths** — to commit, and to preview the live stroke on screen. So a look computed there is free of
> any new texture, any scratch buffer, and any read-back, and the invariant *"what you see while
> drawing is exactly what lands on pen-up"* survives without a word being changed.

## Goal

Blueprint §4 Phase 6, Level 0: *"fake watercolour on commit (edge darkening, granulation, blooms —
~80% of the look)"*. In one paragraph: when a wash stroke is committed, the paint it lays down is
not a flat wash — it darkens into a line just inside its own rim, it is uneven because pigment
settles into the tooth of the paper, and it creeps a little past where the brush actually went.
Those three things, and only those three, are what separates a wash from an airbrush.

Everything here happens **once, at commit**, on the stroke buffer that already exists. There is no
simulation, no wet set, no drying, no second texture and no per-frame cost. That is the point: the
Note 9 is the performance floor, and this is the version of watercolour that costs one extra shader
block in a pass that already runs.

## Contract (verbatim)

### What already exists and is NOT changed (the builder reads these; they are the ground)

```kotlin
// core/paint/Dab.kt — the two accumulate modes, verbatim from the file's own KDoc
enum class Accumulate { WASH, BUILD_UP }
enum class StrokeBlend { NORMAL, ERASE }
data class TipShape(val aspect: Float = 0f, val corner: Float = 2f, val taper: Float = 0f,
                    val hardness: Float = 0.9f, val minPx: Float = 1f)

// core/paint/RefCanvas.kt — endStroke, verbatim
fun beginStroke(layer: String, red: Float, green: Float, blue: Float, opacity: Float,
                mode: Accumulate, blend: StrokeBlend, tip: TipShape)
fun addDabs(dabs: List<Dab>)
fun endStroke(): Int            // returns tiles changed; pushes ONE undo step
fun pixel(layer: String, x: Int, y: Int): FloatArray   // premultiplied RGBA floats 0..1
fun tiles(layer: String): Map<Long, FloatArray>

// core/paint/Tiles.kt
object Tiles { const val SIZE = 256; fun key(tx: Int, ty: Int): Long }

// core/brush/BrushPreset.kt
val engine: String = "stamp"    // "stamp" | "smudge" | "wet" | "fill"
```

```glsl
// shaders/jb_commit.frag — the whole file today. Three effects are added INSIDE main().
precision highp float;
uniform sampler2D u_layer;     // premultiplied RGBA tile (a 1x1 transparent texture if none)
uniform sampler2D u_stroke;    // stroke buffer, R channel
uniform vec3 u_color;          // brush colour, straight (not premultiplied)
uniform float u_strokeScale;   // opacity for BUILD_UP strokes, 1 for WASH
uniform int u_erase;           // 1 = erase
uniform float u_layerOpacity;
in vec2 v_uv;
out vec4 o_color;
void main() {
    vec4 dst = texture(u_layer, v_uv);
    float a = texture(u_stroke, v_uv).r * u_strokeScale;
    vec4 outc = (u_erase == 1) ? dst * (1.0 - a) : vec4(u_color * a, a) + dst * (1.0 - a);
    o_color = outc * u_layerOpacity;
}
```

### What is NEW. `core/paint/WetLook.kt` — all the maths, and all of it testable on a computer

```kotlin
package cc.joycreator.joybrush.core.paint

/**
 * The Level-0 watercolour look (JB-6.01), as PURE functions over the stroke buffer.
 *
 * These are the CPU twin of `joybrush/shaders/jb_wet.glsl`, exactly as TipMath is the twin of
 * jb_tip.glsl and GrainMath the twin of jb_grain.glsl: any change to the shader's maths is made
 * here too, in the same commit. Nothing here touches GL, and nothing here allocates per pixel.
 *
 * ALL THREE EFFECTS ARE OFF AT strength 0, and at strength 0 `edgeDarkened` and `bloomed` are the
 * IDENTITY on every array they are given. That is not a convenience: it is what makes the
 * shader-first landing safe (a build with the new shader and the old engine draws exactly what it
 * drew yesterday, because every new uniform defaults to 0) and it is pinned by Test 1.
 */
object WetLook {

    /**
     * The three effects' strengths and thresholds. ALL of these are TUNING, not contract: see the
     * Tuning section. The DEFAULTS here are the proposal in that section and are what a build ships
     * with. Constructed only from code (not from a brush file — see Decision 8), so a bad value is
     * a code bug, and this class refuses it in words rather than letting it draw a wrong picture.
     */
    data class Params(
        /** Edge darkening, 0..1. 0 = the rim is exactly the stroke's own value. */
        val edgeStrength: Float = 0f,
        /** Granulation, 0..1. 0 = perfectly even. */
        val granulation: Float = 0f,
        /** Bloom: the coverage at or above which a pixel is core, not fringe. 0..1. */
        val bloomFloor: Float = 0.25f,
        /** Bloom: how far outward from the core a pixel may be reached, DOCUMENT px. 0 = off. */
        val bloomReachPx: Float = 0f,
        /** Bloom: how much of the inward neighbour's surplus reaches the fringe. 0..1. */
        val bloomStrength: Float = 0f,
        /** True = the look runs. False = every function below is the identity. */
        val enabled: Boolean = false,
    ) {
        init {
            // Refused in words, never clamped. A NaN reaching a multiply here is the JB-1.05c
            // Decision 1 trap: GLSL clamp() on a NaN is undefined, so the value must never get there.
            for ((n, v) in listOf("edgeStrength" to edgeStrength, "granulation" to granulation,
                                  "bloomFloor" to bloomFloor, "bloomReachPx" to bloomReachPx,
                                  "bloomStrength" to bloomStrength)) {
                require(v.isFinite()) { "wet look: $n = $v is not a number" }
            }
            require(edgeStrength in 0f..1f) { "wet look: edgeStrength = $edgeStrength, want 0..1" }
            require(granulation in 0f..1f) { "wet look: granulation = $granulation, want 0..1" }
            require(bloomFloor in 0f..1f) { "wet look: bloomFloor = $bloomFloor, want 0..1" }
            require(bloomStrength in 0f..1f) { "wet look: bloomStrength = $bloomStrength, want 0..1" }
            require(bloomReachPx >= 0f) { "wet look: bloomReachPx = $bloomReachPx, want >= 0" }
            require(bloomReachPx == 0f || bloomStrength > 0f) {
                "wet look: bloomReachPx = $bloomReachPx needs a bloomStrength above 0"
            }
        }

        /** The identity, and the value every existing caller gets. */
        val OFF: Params get() = Params()
    }

    /**
     * EDGE DARKENING, step 1 of 2. The operator, over a 3x3 neighbourhood of the stroke mask
     * already read out of the buffer: `edge = max(centre - mean3x3(neighbours), 0)`, per tap.
     *
     * [mask] is the centre tile's coverage, row-major, [w] x [h], 0..1. [taps] is the same tile's
     * nine values in 3x3 order, centre FIRST — the order is part of the contract because the shader
     * binds them in that order. A neighbour that lies outside the tile, or outside the stroke's
     * tiles entirely, arrives as 0 (there is no paint there), which is the truth and not a default.
     *
     * Writes [count] floats into [out] and returns how many it wrote (nine, always). No allocation.
     */
    fun edgeTerm(mask: FloatArray, w: Int, h: Int, x: Int, y: Int,
                 taps: FloatArray, out: FloatArray): Int

    /** The mean of the nine taps. Public because the test states its own arithmetic against it. */
    fun mean3x3(taps: FloatArray): Float

    /**
     * EDGE DARKENING, step 2: how much this dab's coverage is multiplied by.
     * `a' = a * (1 + strength * edge)`.
     *
     * A WASH dab's coverage is at most the brush's own cap, so `a' <= a * (1 + strength)` is still
     * bounded by `cap * (1 + strength)` — the cap survives, and how much it survives by is exactly
     * the strength, which is why the strength is the owner's number and not the spec's (Q3).
     */
    fun edgeGain(edge: Float, strength: Float): Float

    /**
     * GRANULATION. `g = 1 + strength * (height - 0.5)`, where [height] is the paper texture sampled
     * at the DOCUMENT pixel (x, y) — canvas space, so the tooth of the paper does not travel with
     * the brush, exactly as `paperGrain` already works (JB-1.05c Decision 14).
     *
     * [height] is NOT computed here: sampling is the shader's job and the texture's, and this
     * function is the one multiplication, isolated so a test can state what it does. The
     * determinism rule is in Decision 4 and it is the caller's: the height at a given document
     * pixel must not depend on which tile, which stroke, or which order.
     */
    fun granulationGain(height: Float, strength: Float): Float

    /**
     * BLOOM. How much pigment a fringe pixel takes from its inward neighbour, and it is a MOVE,
     * not a scale — the caller's responsibility, because a move needs the neighbour.
     *
     * `b = strength * max(0, inward - self)`, and zero unless the pixel is FRINGE — that is,
     * `0 < self < floor` — or `floor <= 0` in which case every pixel with paint is fringe.
     *
     * The predicate is the whole of Decision 6, and its consequence is the best test in the file:
     * a region of UNIFORM coverage has `inward == self`, so `max(0, inward - self) == 0`, so a
     * uniform region NEVER blooms — at any floor, at any reach, at any strength. A bloom needs a
     * ramp, and a ramp is a gradient, and a disc has none.
     */
    fun bloomTake(inward: Float, self: Float, floor: Float, strength: Float): Float

    /**
     * How many pixels outward a bloom may reach, given the core it starts from. The rule is
     * "pixels whose coverage is below [floor], within [reachPx] of the nearest core pixel",
     * which is what [bloomTake]'s fringe test already selects; this function only converts the
     * reach into the number of taps the caller makes, and is public so a test can assert the
     * reach in DOCUMENT px without guessing (R10).
     */
    fun bloomReachInPx(reachPx: Float): Int
}
```

### The new shader file, `joybrush/shaders/jb_wet.glsl`

GLSL ES 3.00 functions only. **No `#version`, no `main`, no uniforms** (the same contract
`jb_grain.glsl` keeps). It is included by `jb_commit.frag` **after** nothing and before the output
statement, and its functions take the already-sampled values, so the sampling stays in
`jb_commit.frag` where the texture units are.

```glsl
// jb_wet.glsl — the Level-0 watercolour look (JB-6.01). SHARED by phone and PC Brush Lab.
// GLSL ES 3.00 functions only. No #version, NO main, NO uniforms. Include from jb_commit.frag.
// CPU twin: core/paint/WetLook.kt. Any change to this maths is made there too, in the same commit.

// The 3x3 mean of the nine stroke-mask taps already read from the nine bound textures.
// taps[0] is the CENTRE. A neighbour that does not exist was bound as a 1x1 zero, which is 0.
float jb_wetMean3x3(float taps[9]);

// EDGE DARKENING. `edge` is the positive part of (centre - local mean): a ring just INSIDE the
// rim of the stroke, zero in the middle of a flat wash and zero outside the paint.
float jb_wetEdge(float centre, float taps[9]);

// The dab's coverage after edge darkening. `a' = a * (1 + strength * edge)`.
float jb_wetEdgeGain(float a, float edge, float strength);

// GRANULATION. `g = 1 + strength * (height - 0.5)`, height sampled in CANVAS space.
float jb_wetGranulation(float height, float strength);

// BLOOM. A MOVE, not a scale. Zero unless the pixel is fringe (0 < self < floor) AND the inward
// neighbour carries more than it does. A uniform region therefore never blooms.
float jb_wetBloomTake(float inward, float self, float floor, float strength);
```

### The one signature change in the engine

```kotlin
// GlPaintEngine.kt — beginStroke, with ONE added defaulted parameter. Every existing call
// (including androidkit/src/test/.../GlContextLossTest.kt:141) keeps compiling unchanged.
fun beginStroke(layerId: String, argb: Int, opacity: Float, accumulate: Accumulate,
                blend: StrokeBlend, tip: TipShape,
                engine: String = "stamp")
```

`engine` is stored, and it is the **only** thing that decides whether the look runs:

```kotlin
// The look runs when, and only when: engine == "wet" AND blend == NORMAL.
// A wet ERASE stroke is bit-identical to today's: an eraser has no pigment to settle.
```

## Decisions

1. **The look lives inside `jb_commit.frag`, not in a new pass.** It is the only place in the engine
   where the layer tile and the stroke buffer are both already bound, and it is already used twice
   with identical maths — commit and live preview. Putting it there costs **no new texture, no
   scratch buffer, no read-back and no new undo step**, and it preserves `GlPaintEngine`'s own
   stated invariant (*"what you see while drawing is exactly what lands on pen-up"*) for free. A
   separate pass would need a full-resolution scratch texture per tile *and* a second copy of the
   commit maths, and the preview would then have to run it too or stop matching.

2. **The look is gated on `engine: "wet"`, and on nothing else.** Not on `accumulate` (Ink is a
   wash brush and must stay a hard-edged pen — see Decision 3), not on the layer, not on a new
   brush field (Decision 8). And **only for `blend == NORMAL`**: an eraser has no pigment, so
   granulating it would darken a hole.

3. **`accumulate: "wash"` keeps its meaning exactly, and is NOT the gate.** A wash brush's `cap` is
   the ceiling that makes a stroke stop darkening where it crosses itself, and that behaviour is
   `🟧 Built` and reviewed. It has nothing to say about whether the paint looks like water. A wet
   brush will normally be a `wash` brush (a wash that built up would granulate into mud), but that
   is a preset's choice, not the gate's. **The Ink preset is a `wash` brush and must be bit-identical
   after this spec** — Test 8 is that test, and it is the one that catches a builder who wired the
   look to `accumulate` instead of `engine`.

4. **Granulation's height field is a pure function of the DOCUMENT pixel, and there is no seed
   anywhere in it.** This is the determinism decision the whole effect rests on. Concretely:
   - The height is read from the **existing** paper cloud asset (`cloud_256.png` / `cloud_512.png` /
     `cloud_fine_256.png`, JB-1.03), tiled with `GL_REPEAT` at a **fixed pitch in document px**, the
     same rule as `paperGrain` (JB-1.05c Decision 6). It does **not** scale with the brush and does
     **not** rotate with the tip.
   - There is **no per-stroke seed, no per-dab seed, no frame counter and no `uptimeMillis`** in
     this path. The only seed in the system is the one inside `CloudNoise.generate`, which produced
     the asset once at build time; after that the field is a fixed function of `(x, y)`.
   - **Paper is paper**: two different wash strokes over the same square of document get the *same*
     granulation. That is not a simplification, it is the physical claim, and it is why a
     re-rendered or re-brushed stroke comes back looking the same (blueprint §2, "strokes are
     recordings").
   - **The test that can tell correct from plausible** is Test 5: commit the *same* stroke twice,
     into two layers, and assert the two tiles are **bit-identical**. A single stray use of a
     stroke seed, a frame counter or a clock breaks it, and nothing else would.

5. **Edge darkening's operator is `(centre − mean of the 3×3 neighbourhood)⁺`, and the nine taps
   come from nine bound textures.** The derivation, written out so it cannot be changed silently:
   take a 1-D coverage profile stepping from 1 inside the wash to 0 outside. At a pixel just inside
   the rim, `centre = 1` and the local mean is below 1 (it includes zeros from outside), so
   `centre − mean > 0`. At the centre of a wide wash, `centre = 1` and the mean is 1, so it is 0.
   Outside, both are 0. So the positive part is **a ring just inside the rim** — which is where a
   watercolour puts its pigment, and which is Curtis's `M − blur(M)` with a 3×3 box in place of the
   K×K Gaussian.
   **The ring's WIDTH is a consequence, not a setting, and this is the honest part:** a 3×3 operator
   is one texel wide, so on a *hard-edged* stroke the darkened band is at most two pixels. On a
   *soft-edged* stroke — which a wash is, because `tip.hardness` is low — the stroke buffer's own
   antialiased falloff spans several pixels of intermediate coverage, and the band is as wide as
   that falloff. **So the effect is strong exactly where it should be (soft washes) and negligible
   where it does not matter (hard pens).** Test 6 pins both halves of that sentence.
   Nine textures: the centre on unit 1 and the eight neighbours on units 2–9, with **a neighbour
   that does not exist bound to the engine's existing 1×1 zero texture** (the same trick
   `clearTex` already plays for a missing layer tile, `GlPaintEngine.kt:465`). OpenGL ES 3.0
   guarantees at least 16 combined fragment texture units, so nine is inside the floor.

6. **A bloom is a MOVE and an edge darkening is a MULTIPLY, and the two are told apart by
   conservation.** This is the decision that stops the two effects from being one shader with a
   different sign. The bloom predicate is `self is fringe (0 < self < bloomFloor) AND
   max(0, inward − self) > 0`, and the pigment is **subtracted from the inward pixel and added to
   the fringe pixel** — so the total premultiplied coverage over the region is **exactly**
   unchanged, and the coverage centroid moves **strictly outward**. Edge darkening multiplies and so
   conserves nothing: it raises the total. Test 7 asserts both halves of that contrast, and it is
   the assertion that proves the builder wrote two effects rather than one.
   **The corollary, which is free and total: a region of uniform coverage never blooms, at any
   floor, reach or strength**, because `inward == self`. A disc has no ramp, so a disc cannot
   bloom. That is Test 7a, and it is a property rather than a count.

7. **Level 0's bloom is inside one stroke, and that is a stated limit, not an oversight.** Rebelle's
   and R1's real bloom is "where a new stroke meets a still-damp previous stroke", and **at Level 0
   nothing is ever damp** — there is no wet set, and that is JB-6.02's whole subject. So Level 0's
   bloom is the *backrun within the stroke's own soft fringe*: the ramp of coverage at the trailing
   edge of a wet stroke, where the paint crept outward as it settled. It is the cheap 80 %, and
   **it cannot do a cross-stroke backrun** — a second wash laid into the first will still show a
   clean boundary between them until 6.02 lands. Stated here so nobody files it as a bug in week one.

8. **No brush file field, no document field, no `DocModel` change, and NO `BRUSH_VERSION` bump for
   the look itself.** The strengths are `WetLook.Params` constants in `core`, and `enabled` is
   implied by `engine == "wet"`. Nothing about the look is authored by a person in a file in this
   spec, so nothing in the serialised vocabulary changes and LEAD_RULINGS R3 is not triggered by
   the maths. **This is a deliberate narrowing:** the obvious next step is `"wet": { "edge": 0.6,
   "granulation": 0.35 }` in `brush.json`, and it is a real version bump and a real validator
   addition, and it belongs to whoever ships the first wet preset — which is not this row, because
   this row has no preset in it. Q1 is about the *other* half of the same question, which 6.01
   cannot avoid.

9. **Every length is a DOCUMENT px, and the document says so (R10).** `bloomReachPx` and any future
   reach are document px, like `size.base` (R10: brush size in document px is correct, so a stroke
   looks the same when you zoom back out). So at 4× zoom the bloom's reach is 4× wider on screen —
   **and so is the paper's tooth**, because the granulation is canvas-space too. The two scale
   together, which is the only reason a zoomed-in wash still looks like the same sheet of paper.
   Nothing in this spec is ever expressed in screen px.

10. **There is one size limit and it is `BrushValidate.MAX_SIZE_PX` (R19), imported and never
    retyped.** Nothing in this spec adds a size, but the rule is stated because 6.03 puts it under
    pressure: a dab may be at most `DabPlacer.MAX_RADIUS_PX` in radius and
    `BrushValidate.MAX_SIZE_PX` in diameter, and a third literal `4096` in this tree is a bug
    waiting to happen. Where a future reach needs a bound, it is expressed **as a fraction of
    `BrushValidate.MAX_SIZE_PX`** or not at all.

11. **The undo budget is untouched, and the effect is inside the existing step.** `endStroke`
    already renders each touched tile into a NEW texture and pushes one `UndoLog.Step` with the old
    texture as the snapshot. The look happens while that new texture is being rendered, so:
    - undoing a wet stroke restores the pixels from **before the look**, for free;
    - the undo cost is identical to a normal stroke's (one texture swap, no copying);
    - `heldBytes` is unchanged.
    Test 9 asserts the *before* tile is pre-effect, because a builder who applied the look at
    `readTile` time instead of at commit time would pass every other test here and fail this one.

12. **`RefCanvas.endStroke` grows the same three lines and keeps its signature.** The CPU reference
    is the golden answer the GPU is checked against (`RefCanvas`'s own class KDoc), and it is the
    only place this maths is testable in the cloud. The `Params` argument is a defaulted
    `WetLook.Params.OFF`, so **every one of the ~500 existing `:core:jvmTest` assertions keeps
    passing unchanged** — which is itself a test (Test 1).

13. **The nine-tap neighbour read is the one place this spec widens `GlPaintEngine`'s texture
    bindings, and it is done by adding, never by changing.** `bindTextures(layerTex, strokeTex)`
    becomes a call that also binds the eight neighbours on units 2–9; units 0 and 1 keep their
    current meanings, `jb_commit.frag`'s `u_layer` and `u_stroke` are untouched, and every other
    shader in the file is unaffected. `JB-1.05c`'s "Do not" forbids widening the **instance buffer**
    for a uniform; this is a different thing and it is the minimum that makes the operator exist.
    The neighbour set is computed from `Tiles.bucket`'s keys — a neighbour is any of the eight tile
    keys around the centre, present in the stroke map or not, and absent means the 1×1 zero.

14. **What the preview shows while drawing is the effect on the stroke so far, and that is
    correct.** The edge ring and the granulation are stable as the stroke grows; the bloom's reach
    grows with it. Nothing in the preview is faked and nothing is deferred to pen-up. The one
    honest caveat: the bloom can only move pigment within the *stroke buffer*, never out of it, so
    the preview's bloom never grows past what the brush itself laid down — which is precisely the
    Level-0 bloom of Decision 7 and is not a preview/commit divergence.

## Tuning — the owner's eye, NOT a contract

Everything in this table is a **look** decision, and a look decision is not a builder's to make.
The values are a **proposal**, chosen to be conservative enough that nothing is a smear on a first
phone run, and they are here so a builder is not stuck and so the owner has something concrete to
reject. **The tests do not assert any of these numbers.** The tests assert the *rules* the numbers
feed (Decisions 4, 5, 6), so a tuning pass is a value change with a green suite — which is the
only sane relationship between a look and a test.

| # | Parameter | Proposed default | What it looks like at that number | Where it lives |
|---|---|---|---|---|
| T1 | `edgeStrength` | **0.6** | A wash's rim reads clearly as a darker line, roughly 60 % stronger than the body's own value at the very edge. At 0.2 it is a rumour; at 1.0 it is an ink outline in a wash. | `WetLook.Params` default |
| T2 | `granulation` | **0.35** | Visible unevenness, tooth-of-the-paper strength. At 0.1 it is invisible at 1× zoom; at 1.0 the wash looks mouldy. | `WetLook.Params` default |
| T3 | granulation pitch | **96 document px** | The cloud repeats every 96 px, so a 300 px wash shows about three repeats across it. Too small and the repeat is legible as a pattern; too large and one wash shows no variation at all. | `WetLook`, one `const val` |
| T4 | `bloomFloor` | **0.25** | The core is the quarter of the wash that is properly opaque; everything above 25 % coverage and below 100 % is fringe. | `WetLook.Params` default |
| T5 | `bloomReachPx` | **3 document px** | The wash creeps at most 3 px past where the brush went. Above ~6 px the shape stops being the shape the person drew, which is a different product decision. | `WetLook.Params` default |
| T6 | `bloomStrength` | **0.5** | Half the inward surplus is carried out. | `WetLook.Params` default |

**Q3 (below) is the question this table exists for**: whether these six numbers are T1's to set
now or T3's to set on the Note 9 — and my recommendation is on the record there.

## Tests

`WetLookTest.kt` (NEW, `:core:jvmTest`) and additions to `PaintTest.kt`. **Every numbered Decision
has at least one case. No test asserts a number from the Tuning table** — each one asserts the rule
and then checks that raising the strength to 1 moves it the right way, which is what makes a tuning
pass safe.

1. **Strength 0 is the identity, on every function, bit for bit** (Decisions 1, 12, and the
   shader-first landing). `Params.OFF` → `edgeGain(a, 0f) == a` to the bit for a sweep of `a` and a
   sweep of `edge`; `granulationGain(h, 0f) == 1f`; `bloomTake(anything, anything, anything, 0f) == 0f`
   for a 64-point sweep. And then the one that really matters: **a `RefCanvas` stroke committed with
   `Params.OFF` produces a tile that is `==` (array equality, not "close") to the tile the same dabs
   produce today**, asserted against a literal expectation derived from the existing
   `washNeverPassesItsOpacityEvenWhereTheStrokeCrossesItself` arithmetic written out in the test.

2. **`RefCanvas` with the look ON still obeys the wash ceiling, raised by exactly the strength**
   (Decision 3). 40 dabs of flow 0.3, cap 0.5, `edgeStrength = 1.0`: the committed alpha is `> 0.5`
   somewhere on the rim and `<= 0.5 * (1 + 1.0) = 1.0` everywhere. The test writes
   `cap * (1 + strength)` in, and the `0.5 * (1 + 0.6)` case as the proposed default.

3. **A wash is darker on a ring just inside its rim than at its centre, and the difference is
   zero in the middle** (Decision 5). One dab, `hardness = 1` (hard) and one with `hardness = 0`
   (soft), each on a 64×64 tile. Assert: the maximum committed alpha is at a pixel whose distance
   from the dab centre is **less than** the radius (inside, not outside — the ring is *inside* the
   rim), and for the hard dab the darkened band is at most 2 px wide, and for the soft dab it is
   strictly wider. The measurements are written into the test with their arithmetic, per LEAD_RULINGS
   R9's standing rule.

4. **The edge term is zero on a flat field, and outside the paint** (Decision 5). Nine taps all
   equal to `t`: `edgeTerm == 0f` for any centre in `0..1` — that is the whole of "a wash's
   interior is untouched", and a builder who wrote `centre - taps[0]` or dropped the `+` passes
   every other test here and fails this one. And nine taps all 0: `edge == 0f`.

5. **Granulation is a pure function of the document pixel: commit the same stroke twice and get
   bit-identical tiles** (Decision 4). Two `RefCanvas`es, two layers, the *same* `List<Dab>`, the
   *same* `Params`, called at two different moments and with a different tile key under the dab
   (place the second one at `x + 1024`). Assert the two committed alpha fields are `==` array for
   array. Then the same dab drawn at `x` and at `x + Tiles.SIZE` gives the *same* granulation
   factor — which fails loudly if anyone sampled the texture in tile-local UV.

6. **The ring is inside, not outside, and its width follows the tip's own softness** (Decision 5,
   restated as its own case because it is the claim most likely to be got backwards). Sample the
   committed alpha along a ray from the dab centre outward and assert the maximum sits at a radius
   **strictly less than** the dab's, for both a hard and a soft tip.

7. **A bloom MOVES pigment and an edge darkening does not — and a uniform region never blooms**
   (Decisions 6, 7). Three parts, and each is a property:
   - **7a.** A disc of perfectly uniform coverage: `bloomTake` is `0f` for every pixel, at
     `floor = 0.0`, `0.25`, `0.9` and `1.0`, at reach 1, 3 and 40 px, at strength 0.1 and 1.0.
     Assert the count of non-zero takes is **exactly 0**. This cannot be satisfied by an
     implementation that forgets the fringe test or the gradient.
   - **7b.** A monotone ramp `mask[i] = max(0, 1 - i/40)` across a tile: the total premultiplied
     coverage after the bloom is **equal, to within 1e-6**, to the total before, and the coverage
     **centroid is strictly greater** afterwards. Both are the definition of a move.
   - **7c.** The same disc run through `edgeStrength = 1` and **no** bloom has a total that is
     **strictly greater** than before. That is the assertion that the two effects are different
     effects.

8. **The Ink preset is bit-identical, and a `wash` build-up difference cannot turn the look on**
   (Decisions 2, 3). Decode the **shipped** `joybrush/brushes/ink/brush.json` byte for byte the way
   `BrushTest` does it, drive it through `BrushDabber` → `DabPlacer` → `RefCanvas` with the same
   fixed seed, commit with `Params.OFF` (the only params a `"stamp"` engine ever gets), and assert
   the resulting tile equals the tile the same stroke produced with the look's code path removed —
   by running the same stroke through a canvas whose `engine` is `"stamp"`. Then assert the gate
   itself: `enabled` is true for `engine == "wet"` and false for `"stamp"`, `"smudge"` and `"fill"`,
   and false for `"wet"` when `blend == ERASE`.

9. **Undo restores pre-effect pixels, and the undo cost did not change** (Decisions 11, 12).
   Commit a wet stroke with the look ON, keep a reference to the `before` tile, then `undoStep()`
   and assert the layer holds the **pre-stroke** array by identity (`assertSame`, the technique
   `PaintTest.undoAndRedoSwapWholeTilesBack` already uses) — and that no tile in the restored layer
   is byte-equal to the committed one. `redoStep()` returns the **effect-bearing** tile by identity.

10. **The neighbour read is seam-free across tile boundaries** (Decisions 5, 13). A dab centred
    exactly on the four-tile corner `(256, 256)`, committed on a 2×2 block of tiles, must agree
    with the same dab committed entirely inside one tile: for every pixel of the corner dab, the
    alpha equals the one-tile reference to within 1e-6 **after being moved by the tile offsets**.
    This is the effect being seamless, and it is the extension of
    `PaintTest.strokesAcrossTileEdgesAreSeamless` — that existing test proves a plain dab is
    seamless, and a builder who reads only it will not see the nine-tap problem.

11. **`Params` refuses bad input in words, and never clamps** (Decision 1's construction, and the
    house idiom). `NaN`, `+Inf`, `−Inf`, `-0.1`, `1.1` on each of the five numbers, each one
    `require`-refused with a message naming the field **and the value**; `bloomReachPx = 3` with
    `bloomStrength = 0` refused with both numbers in the message. Swept in one test with the field
    name in the failure message, so a red run says which knob.

12. **No allocation in the per-pixel path** (blueprint §3.1: *"hot loops in the core must not
    allocate"*). `edgeTerm` writes into a caller-supplied `FloatArray` and returns a count; a test
    holds a reference to the array, calls it 10 000 times, and asserts the same instance came back
    each time and nothing else was handed out.

13. **The tuner can move every strength to 1.0 and the picture stays bounded** (the Tuning
    section's contract with the builder). With all of `edgeStrength`, `granulation`, `bloomStrength`
    at `1.0` and a non-zero reach, every committed alpha over a sweep of 500 random-but-seeded dabs
    is in `0..1` and **is not NaN**. This is the sweep JB-1.05c Test 12 does for grain, and it is
    the cheapest insurance against a tuning pass producing a black screen.

14. **The GPU half is a new case in `tools/shader_check.js`, and it is deferred without a browser**
    (LEAD_RULINGS R6). The file's `src()` already expands `#include` recursively, so
    `jb_wet.glsl` needs no change to the loader. The new case asserts `compiled: true` and that a
    stroke committed with all four new uniforms at 0 still gives **exactly `committedRGBA`
    `[127, 0, 0, 127]`** — the value the file already asserts today, so the default really is "no
    look" (Decision 1) — and that with `u_edgeStrength = 1` the same dab's rim pixel is strictly
    more opaque than the value it has with the strength at 0. If no Chrome is reachable the builder
    says so and marks the GPU half deferred; it is not reported as passed.

**Command:** `./gradlew -p joybrush :core:jvmTest` — BUILD SUCCESSFUL, 0 failures, and the pre-existing
count is **not one lower** (a lost test is a red suite only if something else moved, so quote the
number before and after in the report).

## Do not

- **Do not gate the look on `accumulate`.** Ink is a `wash` brush and must be byte-for-byte
  unchanged (Test 8). "Wash" means "this stroke does not darken where it crosses itself" and
  nothing else; the word is not a synonym for water.
- Do not add a brush-file field, a document field, a `WetSpec`, or a `BRUSH_VERSION` bump for the
  maths (Decision 8). If you believe a field is needed, that is Q1, not an edit.
- Do not add a pass, a scratch texture, a ping-pong or a read-back. The look is a block inside
  `jb_commit.frag` or it is not this spec.
- Do not read any clock, any frame counter or `uptimeMillis` in this path, and do not add a seed
  (Decision 4). Two commits of one stroke must be bit-identical; that is Test 5 and it is the
  property the whole replay story rests on.
- Do not rotate or scale the granulation with the brush or the tip. It is canvas space at a fixed
  document-px pitch, exactly as `paperGrain` (JB-1.05c Decisions 6, 14, 16).
- Do not re-type `4096`, `Tiles.SIZE` (256) or `DabPlacer.MAX_RADIUS_PX` (2048). Import them; R19
  is about `BrushValidate.MAX_SIZE_PX` and the same rule applies to every other shared number.
- Do not change `beginStroke`'s existing parameters, its order, or `GlPaintEngine`'s use of
  `u_layer`/`u_stroke` on units 0 and 1 (Decision 13). The new parameter is **appended with a
  default**, so `GlContextLossTest` and every other caller keep compiling.
- Do not apply the look at `readTile`/`writeTile` time (save, export, open). It belongs to the
  commit, so a saved file and an exported PNG contain the look and nothing has to recompute it.
- Do not attempt a cross-stroke backrun. There is no wet set at Level 0 (Decision 7) and inventing
  one here is JB-6.02.
- Do not assert a number from the Tuning table in a test. Assert the rule; the numbers move.

## Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest` green, output pasted, with the test count quoted
      **before and after** (it must not have gone down).
- [ ] `tools/shader_check.js` green, **or** the GPU half explicitly marked deferred with the reason
      (R6). Not reported as passed if there was no browser.
- [ ] `git status --short` shows only owner-area files.
- [ ] Committed as `JB-6.01: level 0 watercolour on commit`, pushed.
- [ ] ROADMAP's Phase 6 row for JB-6.01 updated (the Lead's file — see Q1's note on who may edit it).
- [ ] On the phone: a wet-brush stroke shows a darker rim, visible paper tooth, and a soft edge
      that creeps slightly; **Ink looks exactly as it did yesterday**.

## Questions

_(Spec writer, 2026-09-29. The `⚪ Outline` row: "JB-6.01 Level 0: watercolour look on commit (edge
darkening, granulation, bloom) | T1 | 1.05". Decisions 1–14 are complete and buildable. What is not
settled is a file-format question I am not allowed to invent, one cost trade-off, and the six
numbers.)_

**Q1 — for the Lead: implementing `engine: "wet"` at all is a version bump, and it collides with
JB-1.06.** R3 says a new word in a serialised file's vocabulary requires bumping the version, and
`BrushJson.wordsNeedingVersion` exists precisely so a build that predates a word **refuses** the
file rather than drawing it wrong. Today `engine: "wet"` is accepted, valid, and **inert** — it
draws as a stamp brush — and it is *not* in `wordsNeedingVersion`, so a version-1 file saying
`"engine": "wet"` loads cleanly on this build. The moment this spec makes the word mean something,
that silent mis-draw becomes the reason the rule exists. So `wordsNeedingVersion` gains
`engine "wet"` and `BRUSH_VERSION` becomes 3.

**But JB-1.06 Decision 8 already claims version 3 for `engine "smudge"` and `engine "push"`,** and
that spec is also a `🟨 Draft`. Two rows both moving `BRUSH_VERSION` to 3 will produce exactly the
drift R3 was written to prevent — and the JB-1.08a Q2 record is the proof that it happens in
practice (a *second* constant appeared beside the first). Options:

- **(a) 6.01 takes 3 and JB-1.06 moves to 4.** Ordering is then a hard constraint: 1.06 cannot land
  first without re-bumping. One edit per row, but the rows now have an order between them the board
  does not show.
- **(b) 6.01 does NOT touch the version, and this spec's engine word is made internal.** The look
  runs for a *new* preset that is introduced in a later row, and that row does the bump with the
  preset, once, covering `wet` and `smudge` together. **This is what I would do**, and the reason is
  the R19-style one: a version bump is a one-line change and it should happen in the row that
  *ships a file using the word*, not in the row that writes the maths behind it. The cost is that
  until that row lands, `engine: "wet"` is still silently inert — which is a real defect this spec
  does not fix, and I would rather say that plainly than paper over it.
- **(c) One combined format row** for every new brush word from here on, owned by nobody until the
  Lead assigns it.

**I have not chosen, because R3's rule is a Lead's rule and both (a) and (b) are defensible.**

**Q2 — the edge band's width is 1 texel, and widening it is a real cost.** Decision 5 makes the
operator a 3×3 mean, which is one texel wide. As the decision also says, the *band* is as wide as
the stroke buffer's own antialiased falloff, so on a soft wash (the only case that matters) it is
several pixels and looks right. But a caller who wants a genuinely wide dark rim — a 6 px line, the
kind you see in a real wash — cannot get it from this operator. The upgrade is a **separable box
blur of radius R**, which needs a second texture per tile, a second pass, and the same nine-tap
neighbour set read twice (so R may be up to 127 without more bindings). That is roughly +2× the
commit cost for wet tiles only, and it is a self-contained follow-on, not a redesign.

Do you want (i) the narrow 3×3 version as specced, with the wide rim as a later row, or (ii) the
separable blur in this row? **I have specified (i)** because a narrower spec that pins the rule is
worth more than a wider one with an unpinned cost, and because the wide rim is a *look* question
that should be answered with the look in front of you rather than in a shader.

**Q3 — are the six tuning numbers T1's or T3's?** The Tuning table above is a proposal, and I want
it on the record that **I do not think a spec writer should get to decide how dark a watercolour
edge is.** The owner's own words (blueprint §0, Phase 6's owner check) are *"washes you'd actually
use, at full speed on the Note 9"* — that is a judgement made by looking, on a phone, with a brush
in the hand. The three options:

- **(a) T1 sets them from the Tuning table now** and the owner may move them in JB-6.03's tuning
  pass. Risk: the first phone run shows six numbers nobody has seen, all at once.
- **(b) All six default to 0 (the look is wired but invisible), and JB-6.03 turns them on with the
  owner watching.** **This is my recommendation.** It costs nothing, it makes the shipping
  milestone provable (Ink is unchanged *and* a wet stroke is currently invisible, so no one is
  surprised), and it turns the owner check into the thing it is supposed to be. It also means this
  spec's tests cannot be accused of asserting a look.
- **(c) T3 sets them on the Note 9 and 6.01 ships with the table's values as a starting point only.**

Whichever you pick, the numbers are **Tuning** and no test asserts them — that part I am confident
about, and the tests are written so a tuning pass is a green run.

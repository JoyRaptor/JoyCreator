# JB-1.05c — Grain in the dab shader: tip texture + paper grain + the tilt-gradient uniforms

| | |
|---|---|
| **Tier** | T1 |
| **Status** | 🟨 Draft — **one Lead ruling is outstanding (Q1, the `level 0` endpoint) and it may change the maths.** Everything else is decided: Decisions 1–16 are buildable now, and a builder told to go ahead can finish the whole spec without waiting |
| **Needs** | 1.05b, 1.03 |
| **Owner area** | NEW `joybrush/shaders/jb_grain_sample.glsl` · EDIT `joybrush/shaders/jb_dab.frag` (include + the grain block in `main`) · EDIT `joybrush/shaders/jb_dab.vert` (**one** additive varying) · NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/grain/GrainMath.kt` (the CPU twin) · NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/grain/GrainMathTest.kt` · EDIT `joybrush/core/.../paint/Dab.kt` (two fields with defaults) · EDIT `joybrush/core/.../paint/DabPlacer.kt` (two assignments) · EDIT `joybrush/core/.../brush/BrushDabber.kt` (two first-dab captures) · EDIT `joybrush/core/.../brush/BrushValidate.kt` (nothing to add — the fields exist; see Decision 12) · EDIT `joybrush/androidkit/build.gradle.kts` (package `assets/`) · EDIT `joybrush/androidkit/.../gl/GlPaintEngine.kt` (upload the two grain textures, set the uniforms) · EDIT `joybrush/androidkit/.../JbCanvasView.kt` (hand the grain numbers to `beginStroke`/`addDabs`) |
| **Estimated size** | ~90 lines of GLSL + ~170 lines of Kotlin + ~280 lines of tests |

> **Read this first.** Both adversarial reviewers of JB-1.02 (mimo and muse-spark, independently)
> ruled that `jb_grain.glsl` **must not be included from `jb_dab.frag` until the NaN row exists.**
> The proof is in their files and it is short: `jb_grainLevel` computes
> `plane = tiltGradient * tiltAmount * dot(localN, leanDir)`, and IEEE says `0 * NaN = NaN`, so **any**
> NaN among `tiltAmount` or `leanDir` poisons `level` no matter what the other factors are — while
> GLSL `clamp`/`min`/`max` on a NaN is *undefined*, so the shader cannot be relied on to launder it.
> A finger reports `tilt = NaN` and `azimuth = NaN` by `PenSample`'s own contract
> (`MotionEventSamples` emits exactly that), and **the shipped `pencil` preset sets
> `paperGrain.tiltGradient: 0.6`** — so the first wiring that hands raw pen channels to this
> function breaks pencil-on-finger completely while every pen test passes. That row is
> **Decision 1** below, and it is a Decision, not a note. Both reviewers also asked for a CPU-side
> twin with a finger test; that is `GrainMath` and Test 1.

## Goal

Blueprint §1 idea 2, the owner's own: two textures, not one. A **tip texture** sampled in *dab
space*, so it turns with the brush (bristle clumps). A **paper grain** sampled in *canvas space*,
so it stays put — "pencil and charcoal only read as 'on paper' if the grain stays put". Both are
run through JB-1.02's height threshold, and the **tilt-aimed gradient** — the thing neither
Photoshop's texture depth nor Krita's Height mode has — is what makes a pencil shade towards the
side the pen leans to.

Today `jb_grain.glsl` is three functions with no caller, and the pencil's `paperGrain` block is a
file that validates, ships, and draws nothing. This spec wires it.

## Contract (verbatim)

### New: `joybrush/shaders/jb_grain_sample.glsl`

```glsl
// GLSL ES 3.00 functions only. NO #version, NO main. Include AFTER jb_tip.glsl and jb_grain.glsl.
uniform sampler2D u_tipGrain;        // tip texture  (dab space)
uniform sampler2D u_paperGrain;      // paper grain  (canvas space)
uniform float u_tipGrainPitchPx;     // document px per repeat; <= 0 means "this grain is off"
uniform float u_paperGrainPitchPx;   // document px per repeat; <= 0 means "this grain is off"

// Tip texture at a DAB-SPACE offset, so it turns with the tip.
// Pitch <= 0 returns 1.0 — "takes paint everywhere" — which is exactly the disabled grain.
float jb_tipGrainHeight(vec2 offsetPx, float angle);

// Paper grain at a CANVAS-SPACE point, so it does not move with the tip.
// Pitch <= 0 returns 1.0, the same reason.
float jb_paperGrainHeight(vec2 docPx);
```

### The CPU twin, verbatim — this is what the tests run

```kotlin
package cc.joycreator.joybrush.core.grain

/**
 * CPU twin of joybrush/shaders/jb_grain.glsl + jb_grain_sample.glsl (JB-1.05c).
 * Any change to the shader's maths is made here too, in the same commit (the rule TipMath follows).
 */
object GrainMath {
    const val TILT_FLAT = 1.5707963267948966f   // PI/2

    /** sin(tilt) with the NaN row: a channel that is not a number means "no tilt sensor", = upright. */
    fun tiltAmount(tiltRad: Float): Float

    /** cos(azimuth) / sin(azimuth), the same NaN row: no azimuth means NO lean, which is (0, 0). */
    fun leanX(azimuthRad: Float): Float
    fun leanY(azimuthRad: Float): Float

    /** jb_grainLevel, with localN already in the tip's frame and leanDir already in that frame. */
    fun grainLevel(
        depth: Float, tipCov: Float,
        localNx: Float, localNy: Float,
        leanX: Float, leanY: Float,
        tiltAmount: Float, tiltGradient: Float, radial: Float,
    ): Float

    /** jb_heightCoverage. `edge` is floored at 1e-3 inside, exactly as the shader does. */
    fun heightCoverage(height: Float, level: Float, edge: Float): Float

    /** jb_grainedCoverage. */
    fun grainedCoverage(tipCov: Float, grainCov: Float): Float

    /** Texture coordinates for the tip texture. Writes two floats into [out] (size >= 2). */
    fun tipGrainUv(offsetPxX: Float, offsetPxY: Float, angle: Float, pitchPx: Float, out: FloatArray)

    /** Texture coordinates for the paper grain. Writes two floats into [out] (size >= 2). */
    fun paperGrainUv(docX: Float, docY: Float, pitchPx: Float, out: FloatArray)
}
```

## Steps

1. Write `GrainMathTest.kt` first, from the Tests section. It will not compile.
2. Write `GrainMath.kt`. Green.
3. Write `jb_grain_sample.glsl` and the `jb_dab.frag` / `jb_dab.vert` edits.
4. Extend `tools/shader_check.js` with a grain case (Decision 15).
5. The engine and view wiring: `Dab`/`DabPlacer`/`BrushDabber` → `GlPaintEngine` → `JbCanvasView`,
   plus the `build.gradle.kts` resource line.
6. Run the commands in **Tests** and paste the output.

## Decisions

1. **THE NaN ROW. A pen with no tilt gets no tilt gradient, and a pen with no azimuth gets no lean
   — and the guard is in the VALUES, never in the shader.** Two helpers, and `GrainMath` uses them
   so the guard cannot be forgotten:
   - `tiltAmount(tilt)`: `tilt.isFinite() ? sin(tilt.coerceIn(0f, TILT_FLAT)) : 0f`
   - `leanX/leanY(azimuth)`: `(azimuth.isFinite()) ? (cos, sin) : (0f, 0f)`
   **Both** channels are guarded, and guarding only tilt is not enough: `dot(localN, (NaN, NaN))` is
   NaN just as surely as `sin(NaN)` is, and a `tiltGradient` of 0 does not save it because the
   multiplication is `0 * NaN`. `+Infinity` and `−Infinity` take the same branch as NaN — a channel
   that is not a finite number is a channel the device did not report, and `PenSample`'s own
   contract says those are NaN and never a made-up value; there is no reading of "tilt = ∞".
   The fallbacks are truthful rather than convenient: `sin(0) = 0` **is** an upright pen, and a
   zero lean vector **is** no lean, so neither invents a value.
   **And the guard is a Kotlin-side substitution, not a GLSL one**, because GLSL leaves
   `clamp`/`min`/`max` on a NaN undefined — the shader may not be relied on to clean up after a
   bad input, so the bad input never arrives. `u_tiltAmount` and `u_leanDir` are uploaded as the
   guarded values.
   The same rule is applied to `depth`: a `depth` that is not finite reads as that grain's own
   `GrainSpec.depth.base` (Decision 12), for the same reason. `edge`, `tiltGradient` and `radial`
   are plain file numbers already ranged by `BrushValidate` rules 14, and are used as they are.
   **This is a Decision and not a footnote because two independent adversarial reviewers made it
   the gate for this exact file, and the case that breaks — the shipped pencil, on a finger — is
   the most ordinary thing a person will ever do.**

2. **`jb_grain.glsl` is NOT edited.** Its three functions keep their bodies byte for byte, and the
   new file is a fourth, separate include. Two reasons: it is `🟩 Reviewed (xr)` and re-opening it
   re-opens a gate that was closed; and its header's contract — *"sampling is the caller's job; these
   functions get the height value already sampled"* — is a good contract that this wiring respects
   rather than breaks. The samplers and uniforms live in `jb_grain_sample.glsl`, which is new and
   carries no contract anybody has reviewed yet. **If the Lead rules Q1 as "change the `+0.5`
   semantics", that is the one exception and it is an edit to `jb_grain.glsl` made in the same
   commit, with `GrainMath` and Test 3 moved with it.**

3. **`jb_dab.frag` includes, in order: `jb_tip.glsl`, `jb_grain.glsl`, `jb_grain_sample.glsl`.**
   The grain block goes after `float tipCov = jb_tipCoverage(v_offset, t);` and before the output,
   and it multiplies `d` — the amount this dab deposits — so the stroke buffer's
   `s' = cap·d + s·(1 − d)` and the whole flow/opacity cap machinery are **untouched**. Grain
   decides *how much* a dab deposits where, which is a scaling of `d`, and nothing about
   accumulate mode, blend, or the commit maths changes. That also means the CPU reference
   `RefCanvas.stamp` needs no change for this spec.

4. **`localN` and the lean direction are in the TIP'S FRAME, and the lean is rotated into it.**
   `localN = rotate(v_offset, −v_angle) / v_radius`, using the *identical* rotation `jb_tipCoverage`
   uses (`c = cos(a), s = sin(a); q = (c·ox + s·oy, −s·ox + c·oy)`), and the document-space lean
   vector is rotated by the same `−v_angle` before the dot product. `jb_grain.glsl`'s header
   already says `localN` and `leanDir` are "in the SAME frame"; this is where that is honoured, and
   it is easy to get wrong in a way that only shows on a non-round tip. For a **round** tip
   (`corner = 2`, `aspect = 0`) it makes no difference, which is why a pen-only test never sees it.
   `v_radius` is the radius, not the diameter, matching the shader's own note; `DabPlacer` floors
   it above 0, so the divide is safe.
   `localN` deliberately ignores the tip's `aspect` and `taper`: the header says "roughly −1..1 each
   axis" and normalising by the radius keeps the tilt plane and the dome the same shape whatever the
   tip is stretched to. Documented, not accidental.

5. **The two grains combine by MINIMUM coverage; a disabled grain is exactly 1.** A brush may enable
   both. Each enabled grain computes its own `level` (from its own `depth`, `tiltGradient`, `radial`
   — they are separate `GrainSpec`s) and its own `heightCoverage` (from its own `edge` and its own
   height), and the dab's grain is `min(tipGrain, paperGrain)`, with an off grain contributing the
   constant 1. Minimum, not product and not a sum, because it is **commutative and associative**: the
   two textures cannot be applied in the wrong order, there is no order to get wrong, and "paint
   lands only where both agree" is the sentence the header's "grain decides WHERE paint lands"
   already means. A product would darken two overlapping grains to the square of their coverage for
   no reason a person could describe.
   The pencil enables only `paperGrain`, so it is unaffected by this decision in practice.

6. **The pitch is a DOCUMENT-px distance and it is a fixed physical size — it does NOT scale with
   the brush.** `pitchPx` is in document px for both textures, so zooming does not change the grain
   and a bigger brush reveals *more* clumps instead of the same few clumps blown up (which is what
   a tip-relative pitch gives, and it is why a pencil's tooth vanishes as you press harder).
   The value comes from `GrainSpec.scale` — **whose meaning is currently defined nowhere in the
   tree.** `BrushValidate` bounds it to "above 0 and at most 64" and the KDoc says nothing, and the
   shipped pencil leaves it at the default `1`, which today means nothing at all. Provisional
   definition, so a builder is not stuck:
   **`pitchPx = GRAIN_UNIT_PX / scale`, with `const val GRAIN_UNIT_PX = 64f` document px.** So
   `scale = 1` → a 64 px pitch, `scale = 8` → 8 px (a plausible paper tooth), and the validated top
   of the range, `scale = 64` → a 1 px pitch, which is the smallest that is not degenerate. The
   pencil's default of 1 gives a 64 px pitch, which is coarse for a pencil — **so the pencil preset
   is expected to gain an explicit `"scale"` when it is tuned, and that is JB-1.07's job, not
   this one's.** This is Question 2, and the alternative reading ("repeats across the tip's own
   diameter") would make `scale = 1` mean "one repeat per brush width", which is a very different
   picture and would need the shipped pencil's default changed.

7. **`source: "cloud"` with a null `image` means `cloud_256.png`; a non-null `image` names the
   asset.** The three shipped assets are `cloud_256.png`, `cloud_512.png`, `cloud_fine_256.png`
   (JB-1.03, `WriteGrainAssets`). There is no field anywhere that says *which* cloud a
   `"source": "cloud"` grain wants, which is a real gap — Question 3. Provisional: the null case
   takes `cloud_256.png` (the coarsest, which is what a tip texture wants; a pencil's paper grain
   wants `"image": "cloud_fine_256.png"`), and any other value is resolved as a file name inside
   the assets folder. **This adds no new word and no new field to the file format, so LEAD_RULINGS
   R3 is not triggered and `BRUSH_VERSION` stays 2** — a test asserts exactly that, so a later
   builder who adds a field cannot do it quietly.

8. **Both samplers are `GL_REPEAT` with mipmaps and `LINEAR_MIPMAP_LINEAR`.** Repeat, because the
   assets are tileable by construction (JB-1.03's own `tileable` test) and `CLAMP_TO_EDGE` would
   put a hard seam at the texture's border. Mipmaps because a paper grain at zoom 0.25 without them
   aliases into noise, and the mip chain of a tileable texture is itself tileable. `GL_LINEAR`
   (min) and `GL_LINEAR` (mag), `GL_REPEAT` on both axes. Anisotropy is left at the driver default
   and named here as a possible later improvement, not done now.

9. **The new uniforms' GL defaults are today's behaviour, so the shader can land before the
   engine.** `glGetUniformLocation` returns −1 for a uniform the compiler removed, and
   `glUniform*(-1, …)` is a documented no-op, so adding uniforms is safe. More importantly: a
   uniform left at its default must mean "no grain". `u_useGrain` defaults to 0, and both pitches
   default to 0, which `jb_*GrainHeight` reads as "off". **So a build with the new `jb_dab.frag`
   and the old `GlPaintEngine` draws exactly what it drew yesterday.** A shader-first landing is
   therefore safe, and it is how this should be merged.

10. **The grain numbers are per-STROKE, except the pen's tilt and lean, which are per-BATCH.**
    - `u_depthTip`, `u_edgeTip`, `u_tiltGradientTip`, `u_radialTip` and the four `paperGrain`
      equivalents: one value for the whole stroke. `edge`, `tiltGradient` and `radial` are plain
      file numbers, so that is automatic. `depth` is a `Param` and could have a pressure curve on
      it, and it is evaluated **at the first dab and then fixed** — the identical treatment
      `BrushDabber` already gives `strokeHardness` and `strokeOpacity`, and for the identical
      reason: the shader takes them as uniforms. The honest cost: a grain depth that followed
      pressure *within* one stroke becomes a whole-stroke constant. See Question 4 for the
      alternative and what it costs.
    - `u_tiltAmount` and `u_leanDir`: uploaded per `addDabs` call, from the batch's last sample.
      Tilt is a first-class input on the owner's own hardware (OWNER_CONSTRAINTS, 2026-09-28) and
      "shade towards the side the pen leans to" is only real if it can change *during* a stroke, so
      making it a whole-stroke constant would gut the effect. Batch granularity is 8–16 ms of pen
      motion, which is one frame — the same resolution the fast path itself has. To get it,
      `Dab` gains `tilt` and `azimuth` fields **defaulting to `Float.NaN`** (so every existing
      `Dab(...)` construction still compiles and every existing test still passes) and `DabPlacer`
      sets them from the interpolated sample; `GlPaintEngine.addDabs` reads `dabs.last()`.
      A `Dab` is not written to a tile, a `StrokeRecord` or a file, so this is a one-stroke-lifetime
      channel, not a format change.

11. **The CPU twin allocates nothing in its scalar functions, and is not in the render path.**
    `tiltAmount`, `leanX`, `leanY`, `grainLevel`, `heightCoverage` and `grainedCoverage` are pure
    scalar functions — blueprint §3.1's "hot loops in the core must not allocate" is respected even
    though these are not hot. The two UV functions write into a caller-supplied `FloatArray` and
    `require` `out.size >= 2` in words, rather than returning a new array, so a CPU tool can call
    them per pixel without garbage. The twin exists for **tests and CPU tools** (thumbnails, the PC
    Brush Lab's checks, JB-1.20); the phone renders on the GPU and never calls it.

12. **Nothing is added to `BrushValidate`, and the grain is validated by the rules that already
    exist.** Rule 14 already ranges `scale`, `edge`, `tiltGradient` and `radial` for **both**
    grains, and rule 16 already requires a finite base for both `tipTexture.depth` and
    `paperGrain.depth` (they are in `paramsOf`, so they cannot be the next NaN to slip through).
    A brush that enables a grain with a **null `image`** is legal and means `cloud_256.png`
    (Decision 7). A grain that is `enabled: false` is still fully ranged, which is rule 14's
    existing wording and is deliberate. **If the Lead's answer to Q2 or Q3 adds a field, that IS a
    R3 version bump, and it must move `BrushPreset.version`'s default and `EnumFreezeTest` in the
    same edit** — the exact trap JB-1.08a's Q2 records.

13. **The tip texture is sampled in dab space from the tip's own frame, so it turns with the brush
    AND scales with it in the only sense that matters.** `jb_tipGrainUv` rotates the offset by
    `−angle` and divides by the pitch: the texture is a fixed pitch in document px, laid over the
    dab in the tip's frame. So the same brush at the same size lays the same clumps over the same
    parts of its own tip on every stroke — which is what makes a brush's marks *repeatable* and
    what makes a recorded stroke re-drawable (blueprint §2, "strokes are recordings"). It is
    deterministic because it is a function of `(offset, angle, pitch)` and nothing else: no random,
    no time, no frame counter.

14. **The paper grain is sampled in canvas space from the dab's document position, and
    `jb_dab.vert` must carry it.** Canvas space needs the fragment's absolute document position, and
    `jb_dab.frag` today has only `v_offset` (the offset from the dab centre). So `jb_dab.vert` gets
    **one** additive varying — `out vec2 v_dabCentre;` set to `a_dab.xy;` — and the fragment
    computes `v_dabCentre + v_offset`. That is the whole edit to the vertex shader: the five
    existing varyings and the position maths are untouched, so JB-0.07's maths cannot change, and
    the paper grain at the same document point is the same texel no matter which dab covers it or
    what angle the brush is at. **That invariance is the entire point of the two textures
    (blueprint §1 idea 2(b)) and it is what Test 10 pins.**

15. **The GPU half of the proof is `tools/shader_check.js`, extended with one grain case — and if
    there is no browser, that step is deferred to the Lead exactly as LEAD_RULINGS R6 allows.** The
    cloud proof is `GrainMathTest` (which is a *transcription* of the GLSL, so it proves the maths
    and not the wiring). The check to add compiles the grain path in WebGL2 and asserts, for one
    known setup, that (a) with both pitches 0 the committed pixel equals today's
    `committedRGBA = [127,0,0,127]` — i.e. the default really is "no grain", Decision 9 — and (b)
    with a paper grain at a 1 px pitch and `depth = 0`, a fragment over a bright texel is strictly
    darker than the same fragment with the grain off. The file's own `src()` already expands
    `#include` recursively, so the new include needs no change to the loader. R6's rule applies
    verbatim: if no Chrome is reachable, the builder says so and does not claim the GPU half ran.

16. **Both `localN` and the tip texture's UV use the *same* `−angle` rotation, and the paper grain
    uses none.** Three frames in one shader is the hazard here, and they are: dab space (rotated
    by the tip's angle), the tip's local normal space (the same rotation, divided by the radius),
    and canvas space (no rotation at all). Decision 4 and Decision 14 pin the first two; Test 9 and
    Test 10 pin all three against each other, because a shader that rotates the paper grain by the
    tip's angle still looks *almost* right on a straight stroke and is obviously wrong on a curve.

## Tests

`GrainMathTest.kt` in `:core:jvmTest`. **Every Decision above has at least one case here.**

1. **THE NaN ROW — the case both reviewers demanded, first, by name.** Decode the **shipped**
   `joybrush/brushes/pencil/brush.json` (inline, byte for byte, the way `BrushTest` does it), take
   its `paperGrain` numbers (`depth` base 1 with the pressure curve `[[0,0.2],[1,0.9]]`, `edge`
   0.25, `tiltGradient` 0.6), and evaluate `grainLevel` at a **finger's** `PenSample`
   (`tilt = NaN`, `azimuth = NaN`, `pressure = 1`) over a grid of 25 points across the tip's
   bounding square. Assert: **every level is finite**, and for a 512×512 cloud sampled through the
   *actual* asset the resulting `grainedCoverage` is above 0 somewhere (a finger pencil still draws
   — the failure mode is "every dab NaN", which is what this test exists to make impossible).
   Then the same grid with `tilt = 0.5, azimuth = 0.9` (a real pen) must give a **different** answer
   at the lean-side point, so the test cannot pass by grain being off entirely.
2. **The NaN row, channel by channel.** `tiltAmount`: `NaN`, `+Inf`, `−Inf` → exactly `0f`;
   `0f` → `0f`; `TILT_FLAT` → `1f`; `2.0` (beyond flat) → `1f` (clamped by the sensor's range, not
   by the guard). `leanX/leanY`: `NaN`, `+Inf`, `−Inf` → both exactly `0f`; `0f` → `(1f, 0f)`;
   `π/2` → `(0f, 1f)` within 1e-6. And the load-bearing one: with `tiltAmount = 0f` **and**
   `leanX = leanY = 0f`, `grainLevel` is finite for every `localN` in `[-1.5, 1.5]²` on a 0.25 grid —
   because `0 · NaN` is the trap, and this asserts the multiplication is never reached with a NaN.
3. **Both endpoints of `heightCoverage`, pinned at the values the maths actually produces — not at
   the values the header claims.** With `edge = 0.25`: at `level = 0` and `height = 1` the result
   is **exactly `0.5f`, and the same `0.5f` for `edge = 0.01` and `edge = 1.0`** (the `+0.5`
   half-band overhangs the top of the height domain, so it does not depend on `edge` at all — this
   is mimo's F2 arithmetic, written into a test so it cannot change without a ruling). At
   `level = 1` and `height = 0` the result is **exactly `0.5f`**, and at `height = 0.5 · edge` it is
   `1f`. Write the derivation of each into the test, per LEAD_RULINGS R9's standing rule.
   Also: `edge = 0` uses the `1e-3` floor and does not divide by zero; `height` outside `0..1` and
   `level` outside `0..1` both clamp to `0..1`.
4. **The lean side, and the sign.** `tiltGradient = +0.6`, `tiltAmount = sin(π/4)`, `localN` on the
   lean side vs. the same length on the opposite side: the lean side's `level` is **strictly
   greater**, by exactly `2 · 0.6 · sin(π/4) · |dot|`. At `tiltAmount = 0f` the two are **equal to
   the bit** (the multiply by zero is exact, not a small number). `tiltGradient < 0` reverses it.
5. **`radial` drops the rim.** The level at `localN = (0,0)` is unchanged by `radial` (the dome is
   0), and at `|localN| = 1` it is lower by exactly `radial`, on any direction.
6. **`depth · tipCov`.** At `tipCov = 0` the level is `plane − dome` — the header's claim that a
   soft edge presses less deep — and the test writes that out. At `tipCov = 1` and
   `depth = 0.6` the level is `0.6 + plane − dome`.
7. **Grain never leaks past the tip's edge.** `grainedCoverage(tipCov, g)` is exactly `0f` for
   `tipCov ≤ 0.06f` (the `smoothstep(0, 0.06, ·)` gate), for **every** `g` including `g = 1f`, and
   equals `g` for `tipCov ≥ 1f`. This is the invariant both reviewers listed as verified-good, and
   it is the reason grain scales `d` rather than replacing it.
8. **The two grains combine by minimum; an off grain is exactly 1.** `min(0.3, 0.8) == 0.3`;
   `min(0.8, 0.3) == 0.3` (order-independence is the point); with either grain off the result is
   the other's value, bit for bit; with both off it is `1f` and the dab is unchanged.
9. **The tip's frame (Decision 4 + 16).** With `angle = π/2`, a fragment at document offset
   `(+r, 0)` and one at `(0, +r)` are mirror images **in the tip's frame**, so with a document-space
   lean of `(1, 0)` the `(0, +r)` point gets the higher level. At `angle = 0` the `(+r, 0)` point
   does. Written as a two-line table in the test so the failure is legible.
10. **The two UV frames against each other — the three-frame hazard (Decision 16).** At
    `angle = 0` and pitch 8, `tipGrainUv` at offset `(0,0)` is `(0.5, 0.5)`; at `(8, 0)` it is
    `(1.5, 0.5)`, i.e. **exactly one repeat**, which is what the pitch means; at
    `angle = π/2, offset = (0, 8)` it is `(1.5, 0.5)` too (the rotation is in the tip's frame).
    `paperGrainUv(8, 0, 8)` is `(1.5, 0.0)`, and — the assertion that matters —
    `paperGrainUv` is **identical for every `angle`**: same document point, same texel, whatever
    the brush is doing. Negative document coordinates give negative UVs (which is what `GL_REPEAT`
    wants, and the case that would break with `CLAMP_TO_EDGE`).
11. **The pitch formula (Decision 6).** `scale = 1 → 64 px`, `scale = 8 → 8 px`, `scale = 64 →
    1 px`, each read through the shipped `GrainSpec` default and `GRAIN_UNIT_PX`. And
    `scale = 0` / `NaN` / negative is a *refused brush* (rule 14), not a pitch — the helper
    `pitchPxFor(scale)` requires a positive finite value and names the number in the message.
12. **No NaN, ever (sweep).** Over a grid of inputs — `depth`, `tipCov`, `localN`, `lean`,
    `tiltAmount`, `tiltGradient`, `radial` in `{0, 0.5, 1, 1e999-as-Infinity, NaN, −1}`,
    `height` in `{0, 0.5, 1, NaN}`, `edge` in `{0, 0.3, 1, NaN}` — every result of
    `grainLevel`, `heightCoverage` and `grainedCoverage` is in `0..1` and is **not NaN**. A single
    `assertFalse(result.isNaN())` over the sweep; the test states that the one place a NaN may
    appear is inside a `GrainSpec` that never got through `BrushValidate`.
13. **R3 is not triggered (Decision 7).** `BRUSH_VERSION == 2`, and a preset with both grains
    enabled, a `scale`, an `image` and a tilt-driven `depth` curve **encodes to version 2** and
    round-trips (`BrushJson.decode(BrushJson.encode(p)) == p`). If a later change adds a field and
    this test does not move, the change was made wrongly.
14. **The default is today's behaviour (Decision 9).** A `GrainSpec` that is entirely
    default-valued — `enabled = false`, `scale = 1f`, `image = null` — produces pitch `0` for both
    grains, i.e. both off. There is a named function for it (`pitchPxFor(spec): 0f when
    !spec.enabled`) and this is its test. This is the assertion that makes the shader-first landing
    safe, and it is cheap to write and expensive to lose.

**Command:** `./gradlew -p joybrush :core:jvmTest` — BUILD SUCCESSFUL, 0 failures.

**GPU command (Decision 15):** `cd joybrush/tools && node shader_check.js` with a Chrome
reachable — `"compiled": true`, `committedRGBA` **exactly** `[127,0,0,127]` (unchanged from today,
proving Decision 9), and the new `grainDarkens` field `true`. If no browser is reachable, say so
and mark the GPU half deferred (R6); do not report it as passed.

## Do not

- **Do not hand raw `tilt` / `azimuth` to the shader.** Decision 1 is the gate both reviewers set,
  and the case that breaks is the shipped pencil on a finger.
- Do not add a NaN guard *inside* the GLSL and skip the Kotlin one. GLSL `clamp` on a NaN is
  undefined; the value must never arrive.
- Do not edit `jb_grain.glsl`'s three existing functions, and do not move sampling into them. The
  header's contract is that the caller samples; respect it (Decision 2).
- Do not rotate the paper grain by anything. It is canvas space, and the tip's angle is not part of
  its definition.
- Do not make the tip texture scale with the brush (Decision 6) — that is the difference between
  bristle clumps and a blurry blob.
- Do not widen the per-dab instance buffer. `Dab` carries `tilt`/`azimuth` in Kotlin only; the six
  floats `GlPaintEngine` uploads and `jb_dab.vert`'s attribute layout are unchanged, because
  `JB-0.07` is `🟧 Built` and its maths is a reviewed contract.
- Do not change `flow`, `cap`, `accumulate`, `blend` or the commit maths. Grain scales `d` and
  nothing else.
- Do not run a headless browser and claim it if there is not one (R6). Do not copy a constant
  instead of importing it: `Tiles.SIZE` / `TILE_SIZE`, `BrushValidate.MAX_SIZE_PX` and
  `BRUSH_VERSION` are all imported, never retyped.

## Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest` green, with Test 1 present and passing (paste output).
- [ ] `node shader_check.js` green, or the GPU half explicitly marked deferred with the reason.
- [ ] `git status --short` shows only owner-area files.
- [ ] Committed as `JB-1.05c: grain in the dab shader`, pushed.
- [ ] The pencil on a **finger** has been drawn on the phone and shows grain, not a blank stroke.

## Questions

_(Spec writer, 2026-09-29. `⚪ Outline` row: "JB-1.05c Grain in the dab shader: tip texture + paper
grain textures, tilt gradient uniforms (JB-1.02 maths) | T1 | 1.05b, 1.03". The NaN row both
reviewers demanded is **Decision 1**, decided. Q1 is the one thing blocking a clean `🟦 Ready`.)_

**Q1 — for the Lead: the `level 0` endpoint does not do what its comment says, and this is the
wiring commit, so it is now or never.** `jb_grain.glsl:19-20` documents `level 0` as "nothing takes
paint". The maths gives **exactly 0.5 coverage for the brightest texel, at every `edge`**:
`threshold = 1 − 0 = 1`, so `(height − 1)/w + 0.5` is `0.5` at `height = 1`, and the `+0.5` half-band
overhangs the top of the `[0,1]` height domain with no clamp room above it. It is not
hypothetical: **JB-1.03 normalises every cloud so `max == 1` exactly** (its own contract, pinned
by `heightsRunFromZeroToOne`), so a `height = 1` texel exists in every shipped asset, and with a
feather-light touch through a zeroed pressure curve a pencil prints up to 50 % alpha speckle at
"nothing takes paint". The same arithmetic says `level 1` is not "everything takes paint" either:
a texel at `height = 0` gets `0.5`. Both are in Test 3, pinned to the values the maths produces.
Three answers, one sentence each:
  - **(a) Accept and fix the comment:** "0 and 1 are the ends of the *threshold*; within half an
    edge band of either end the coverage is 0.5, so a dry-brush speckle at the lightest touch is
    intended." No maths changes; `jb_grain.glsl`'s header is edited; this spec ships.
  - **(b) Close it:** change the `+0.5` semantics so `level = 0` really is nothing and `level = 1`
    really is everything, re-verify both endpoints, and move `GrainMath` and Test 3 in the same
    commit. This is the one edit that touches the reviewed file (Decision 2's stated exception).
  - **(c) Accept as-is** and note it as a known half-band.
  I have not chosen, because (b) changes a formula two reviewers have already read and the
  artistic reading ("a dry brush *should* speckle at a feather touch") is a call about what the
  pencil is for, which is not mine.

**Q2 — `GrainSpec.scale` has no meaning anywhere, and I have provisionally given it one.** The
field is in `GrainSpec`, `BrushValidate` bounds it to "above 0 and at most 64", the KDoc says
nothing, and the shipped pencil leaves it at the default `1` — so today it is a number that
validates and does nothing. Decision 6 provisionally defines `pitchPx = 64 / scale` in **document
px** (a fixed physical grain that does not grow with the brush). The alternative, which the field's
name and its 0..64 range equally support, is **repeats across the tip's own diameter** — so
`scale = 1` means "one repeat per brush width", and the grain is defined *relative to the brush*,
which is how MyPaint's and Photoshop's texture-depth scales behave and how the owner may have been
imagining it. The two are not the same picture at all: relative-to-tip makes a pressed pencil lose
its tooth entirely. Whichever you rule, the shipped `pencil/brush.json` will need an explicit
`"scale"` (default `1` means a 64 px pitch under my reading, which is coarse for a pencil) — that
edit belongs to JB-1.07 and I have not made it.

**Q3 — `"source": "cloud"` cannot say *which* cloud.** There are three shipped assets
(`cloud_256`, `cloud_512`, `cloud_fine_256`) and no field that picks one; `GrainSpec.image` is
documented as "path inside the brush folder when source == image". Decision 7 provisionally reads a
non-null `image` as an asset name whichever the `source` is, and a null one as `cloud_256.png` —
which is why **no new word and no new field is needed and R3 is not triggered (Test 13)**. If you
want a real `cloud` enum instead, that is a new serialised constant, `BRUSH_VERSION` becomes 3, and
`BrushPreset.version`'s default and `EnumFreezeTest` move in the same edit (Decision 12). Your
call; the cheap reading is the one written.

**Q4 — per-dab or per-stroke grain depth, and it is a feel question.** Decision 10 fixes `depth` at
the first dab, the same way `strokeHardness` and `strokeOpacity` already are, because the shader
takes it as a uniform. The cost is real: a pencil's dry texture cannot break up as pressure lightens
*within* one stroke, which is arguably the most pencil-like thing a pencil does. The fix is a wider
per-dab instance attribute — `jb_dab.vert` gains a third instance vector and `GlPaintEngine` uploads
9 floats instead of 6 — which is a change to JB-0.07's buffer layout, a `🟧 Built` contract, for one
uniform. **My recommendation is to ship per-stroke now and put per-dab on the list for the first
grain complaint from the owner**, because the pencil preset can put its depth curve's whole range
into `edge` and `depth.base` instead, and a per-dab attribute is a change to the reviewed engine.
If you disagree, say so now: the `Dab`/`DabPlacer` edits are the same either way and only the
buffer grows.

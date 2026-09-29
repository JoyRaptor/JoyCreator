# JB-6.03 — The one great brush: tip for detail, belly for washes

| | |
|---|---|
| **Tier** | T1 + T3 (the numbers are the owner's; blueprint §4's Phase 6 owner check is *"washes you'd actually use"*, which is a judgement made with the brush in the hand) |
| **Status** | 🟨 Draft — **only the Lead or a cross-reviewer may set this to `🟦 Ready`** (ROADMAP §3). Q1 is a change to a `🟧 Built` contract and Q3 is the name a person will see |
| **Needs** | JB-6.01, JB-6.02 |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/brush/TuftTip.kt` · NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/brush/TuftTipTest.kt` · NEW `joybrush/core/src/jvmTest/kotlin/cc/joycreator/joybrush/core/brush/TheGreatBrushTest.kt` · EDIT `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paint/Dab.kt` (**two defaulted fields**) · EDIT `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paint/DabPlacer.kt` (**one line, the same NaN-sentinel treatment `cap` already has**) · EDIT `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/brush/BrushDabber.kt` (two evaluations + two assignments) · EDIT `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/brush/BrushPreset.kt` (`TipSpec` gains two defaulted `Param`s) · EDIT `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/brush/BrushValidate.kt` (**two new numbers must be finite**) · NEW `joybrush/brushes/wash/brush.json` · EDIT `joybrush/brushes/index.txt` (**one line — see Q3**) · EDIT `joybrush/shaders/jb_dab.vert` (**one new attribute, appended**) · EDIT `joybrush/shaders/jb_dab.frag` (**two uniforms and one line**) · EDIT `joybrush/androidkit/.../gl/GlPaintEngine.kt` (`fillInstances` only) · EDIT `joybrush/androidkit/.../JbCanvasView.kt` (`beginStrokeNow`'s `TipShape`) |
| **Estimated size** | ~150 lines of Kotlin + ~350 lines of JSON + ~380 lines of tests |

> **The one thing this row has to do, and why it cannot be a preset.** R2 §4.3's own words: *"the
> richness comes from wielding one deformable tuft: tip for hairlines, belly for washes, side for
> wipe strokes, dryness for scratchy texture. It does not come from presets."* The engine cannot
> do the first of those today, and the reason is written down in three places:
>
> - `TipShape` is **five numbers uploaded as uniforms** — `GlPaintEngine.addDabs` sets `u_aspect`,
>   `u_corner`, `u_taper`, `u_hardness`, `u_minPx` once per batch (`:352–356`). One stroke, one
>   tip shape.
> - `BrushDabber.strokeHardness` is **evaluated at the first dab and then fixed**, and its KDoc
>   says why: *"Hardness for the whole stroke, as the shader takes it: one uniform, so it is
>   evaluated at the FIRST dab and then fixed."*
> - JB-1.05c Decision 10 hit the same wall for grain and **deferred it for exactly this reason**,
>   and its "Do not" list forbids the fix: *"Do not widen the per-dab instance buffer. `Dab`
>   carries `tilt`/`azimuth` in Kotlin only; the six floats `GlPaintEngine` uploads and
>   `jb_dab.vert`'s attribute layout are unchanged, because `JB-0.07` is `🟧 Built` and its maths
>   is a reviewed contract."*
>
> **This is the row that pays that debt, and paying it is the whole of "tip for detail, belly for
> washes".** A hard-edged small dab is a hairline; a **soft-edged** small dab is a smudge-blob. The
> brush has to get *harder* as it gets smaller, and one hardness per stroke cannot do that. Q1 is
> the formal request to change a reviewed contract, and it is not optional — everything else here
> follows from it.

## Goal

One brush that is a pen when the pen is light and a wash when it is pressed, with no mode, no
preset switch and no second brush in the pill. That is blueprint §1 idea 7 (*"Expresii proves one
great brush beats a library"*) and the owner-facing sentence in R2 §4.4: *"One brush = many
brushes."* The behaviour is small enough to state in three properties:

1. **The lightest touch is a hairline** — a few pixels wide, hard-edged, and it draws.
2. **The hardest press is a flat wash** — a couple of hundred pixels wide, soft-edged, so its rim
   feathers and Level 0's edge darkening has something to sit on.
3. **Neither end is a separate tool.** The transition is one continuous function of pressure, and
   the coverage footprint is **centred on the pen sample at every pressure**, so the mark never
   slides ahead of the cursor — the complaint R2 §4.4 records against Expresii itself, which this
   design removes by construction rather than by tuning.

## Contract (verbatim)

### What already exists and is NOT changed

```kotlin
// core/paint/Dab.kt — the record a dab is, and the NaN-sentinel rule this spec extends
data class Dab(
    val x: Float, val y: Float,
    val radius: Float,
    val angle: Float = 0f,
    val flow: Float = 1f,
    val cap: Float = 1f,
    val pressure: Float = 1f,
    // ADDED BY THIS SPEC, both defaulting to NaN, both meaning "use the stroke's":
    val hardness: Float = Float.NaN,
    val aspect: Float = Float.NaN,
)

/** The tip shape, mirroring JbTip in joybrush/shaders/jb_tip.glsl. */
data class TipShape(val aspect: Float = 0f, val corner: Float = 2f, val taper: Float = 0f,
                    val hardness: Float = 0.9f, val minPx: Float = 1f)

/** Paint coverage at an offset, 0..1 — the CPU twin of jb_tipCoverage. */
object TipMath { fun coverage(dx: Float, dy: Float, radiusPx: Float, angle: Float, tip: TipShape): Float }

/** What the brush decides about ONE dab. `cap: NaN` already means "use the placer's". */
data class DabLook(val radius: Float, val angle: Float = 0f, val flow: Float = 1f, val cap: Float = Float.NaN)
                    // ADDED BY THIS SPEC, same NaN convention:
//                    val hardness: Float = Float.NaN, val aspect: Float = Float.NaN
```

```json
// joybrush/brushes/ink/brush.json — the shape a preset has, byte for byte
{ "format": "joybrush.brush", "version": 1, "id": "joybrush.ink", "name": "Ink",
  "engine": "stamp", "tip": { "corner": 2, "hardness": { "base": 0.95 } },
  "size": { "base": 6, "inputs": [ { "input": "pressure", "curve": [ [0, 0.15], [1, 1] ] } ] },
  "opacity": { "base": 1 }, "flow": { "base": 1 }, "spacing": 0.04,
  "accumulate": "wash", "blend": "normal", "smoothing": 0.35, "license": "CC0" }
```

### What is NEW: `core/brush/TuftTip.kt` — the tip-vs-belly law, and it is three functions

```kotlin
package cc.joycreator.joybrush.core.brush

/**
 * The one great brush's tip: a pressure-driven pair of (radius, hardness) (JB-6.03).
 *
 * This is R2 §4.4's tuft footprint reduced to the only two properties a 2-D engine can carry, and
 * the reduction is stated rather than implied: the real tuft is a 3-D deformable body solved with
 * SQP, and blueprint §5 forbids shipping one (US 9,030,464, "the brush component outputs a 3D
 * model of the tool"). What survives the reduction is the sentence that matters — *light touch,
 * small and hard; hard press, wide and soft* — and that IS what a hairline and a wash are.
 *
 * Every function is a pure function of numbers, takes NO time, and is the CPU twin of
 * jb_dab.frag's use of the new attributes. Determinism is therefore free: a stroke replayed with
 * the same seed gets the same dabs, which is blueprint §2's "strokes are recordings" and R20's
 * re-brush rule.
 */
object TuftTip {

    /** Smallest ratio of tip radius to belly radius. Below this the hairline stops being one. */
    const val MIN_TIP_FRACTION = 0.005f

    /**
     * THE radius law, straight from R2 §4.3 with the two terms that survive:
     * `w_max = R · sqrt(d) · (1 + spread · d)`, where `d` is the penetration proxy (pressure after
     * the file's curve) and `R` the belly radius. **The sqrt is the load-bearing part**: it says
     * area grows roughly LINEARLY with pressure — twice the pigment when you press twice as hard —
     * which is what a loaded brush does and what a linear radius law (the obvious one) does not.
     * The `(1 + spread · d)` term is R2's lateral spread: under pressure the footprint widens
     * faster than it lengthens.
     *
     * The floor is the tip: `radius = max(that, R · tipFraction)`, so pressure 0 is a hairline and
     * not a nothing. R2's own `r_tip` with its `tipSharpness` term, reduced to one number.
     *
     * [bellyRadiusPx] is a RADIUS in DOCUMENT px (R10). Every number is refused in words if it is
     * not finite or not in range, never clamped — the JB-1.05c Decision 1 trap, and the same
     * house idiom as everywhere else in the engine.
     */
    fun radiusAt(bellyRadiusPx: Float, d: Float, spread: Float, tipFraction: Float): Float

    /**
     * THE hardness law — and it is the whole claim of this row. Linear from [atLight] at d = 0 to
     * [atFull] at d = 1, so a brush that gets softer as it presses has `atFull < atLight`, and
     * **the shipped preset must have `atFull < atLight` strictly.** That is Test 5, and it is a
     * property of the brush rather than a number: a "great brush" whose belly is as hard as its
     * tip is two brushes wearing one name, and a test on the property says so in one line.
     *
     * [d] outside 0..1 is CLAMPED, because it is the output of a dynamics curve and the curve
     * already documents that it is not clamped here ("clamping is per setting and belongs to
     * whoever owns the setting", Dynamics' class KDoc). Everything else is refused.
     */
    fun hardnessAt(d: Float, atLight: Float, atFull: Float): Float

    /** The same linear law for [aspect]: 0 is as designed, so a wash that flattens is one number. */
    fun aspectAt(d: Float, atLight: Float, atFull: Float): Float

    /** Every number a brush's tip carries, in one object, refused in words at construction. */
    data class Spec(
        /** Belly DIAMETER at full press, DOCUMENT px (the file's `size.base` is a diameter too). */
        val bellySizePx: Float,
        /** Tip radius as a fraction of the belly radius, in MIN_TIP_FRACTION..1. */
        val tipFraction: Float,
        /** 0..1 lateral spread under pressure. */
        val spread: Float,
        /** Hardness at a feather touch. 0 = soft from the centre, 1 = a hard edge. */
        val hardnessAtLight: Float,
        /** Hardness at full press. MUST be below hardnessAtLight for this brush. */
        val hardnessAtFull: Float,
        /** Aspect at a feather touch, -1..1. */
        val aspectAtLight: Float = 0f,
        /** Aspect at full press, -1..1. */
        val aspectAtFull: Float = 0f,
        /** 0..1; the floor on a dab's radius, from the file's `tip.minPx`. */
        val minPx: Float = 1f,
    ) {
        init { /* every number finite and in range; the message names the field AND the value */ }

        val bellyRadiusPx: Float get() = bellySizePx * 0.5f

        /** The dab's radius at one penetration, applying the MIN_PX floor last. */
        fun radius(d: Float): Float
    }
}
```

### The brush file: the two new `TipSpec` fields

```kotlin
// core/brush/BrushPreset.kt — TipSpec gains TWO defaulted Params. A `Param`, like every other
// numeric brush setting in this format, so both can carry a pressure curve and neither is a
// special case. This is the FIRST `TipSpec` field that is per-dab, and that is the point of it.
@Serializable data class TipSpec(
    // …every existing field, unchanged, in declaration order, all with their existing defaults…
    /** Hardness at a feather touch. Per DAB. Replaces `hardness` for a per-dab tip. */
    val hardnessLight: Param = Param(0.9f),
    /** Hardness at full press. Per DAB. For this brush it is BELOW hardnessLight. */
    val hardnessFull: Param = Param(0.9f),
    /** Tip radius as a fraction of the belly radius, 0.005..1. Per dab through the size law. */
    val tipFraction: Param = Param(0.02f),
    /** Lateral spread under pressure, 0..1. */
    val spread: Param = Param(0.4f),
)
```

**`hardnessLight` and `hardnessFull` default to the same value (`0.9f`), which is the whole of the
backward compatibility**: a brush that sets neither gets a constant hardness, exactly as today, and
`Dab.hardness` is `NaN` for every one of its dabs, so `GlPaintEngine` uploads the stroke's uniform
and `jb_dab.frag`'s new attribute is not even read (Decision 3). **`ink` and `pencil` are
byte-identical after this spec** — Test 6 — and the shipped `ink` preset sets
`"hardness": {"base": 0.95}`, which still means the same thing it means today.

## Decisions

1. **The dab's tip shape becomes per-dab, and the instance buffer grows from 6 floats to 8.**
   `layout(location = 3) in vec2 a_tip;` — `(hardness, aspect)` — with the stride going from 24 to
   32, and `glVertexAttribPointer(3, 2, GL_FLOAT, false, 32, 24)`. **Attributes 0, 1 and 2 are not
   touched, and neither is `jb_dab.frag`'s existing body**; `fillInstances` writes 8 floats per
   instance instead of 6 and nothing else about the dab pass changes. This is a deliberate,
   documented change to JB-0.07's buffer layout, which is the one thing JB-1.05c's "Do not" list
   protects — Q1 is the formal request, and this Decision is the argument for it: **a single
   hardness per stroke cannot be a hairline at one end and a wash at the other, and that sentence
   is the whole row.**
   **Why 8 and not 12.** One `vec2` for the two numbers this row needs. If JB-1.05c's per-dab
   grain depth lands later it adds its own attribute and changes the stride again — one row, one
   edit, one set of tests, which is the R3 discipline applied to a buffer rather than a file. The
   alternative (anticipating 1.05c's slot and widening to 12 now) is precisely the *second
   constant* mistake `EnumFreezeTest` and the JB-1.08a Q2 record exist to prevent.

2. **`Dab.hardness` and `Dab.aspect` are `Float.NaN`-defaulted, meaning "use the stroke's".** This
   is the `DabLook.cap` convention already in `DabPlacer` (`cap = if (l.cap.isNaN()) cap else …`),
   applied twice. It means **every existing `Dab(x, y, radius, angle, flow, cap, pressure)`
   construction in the tree still compiles and every existing test still passes**, and a brush that
   sets neither new field takes exactly today's path: `DabPlacer.emit` substitutes the placer's
   stroke value, `fillInstances` writes it, and the result is the same pixels. NaN is the right
   sentinel and not a fudge: `PenSample`'s class KDoc already establishes that "a channel the
   device did not report" is `NaN` and never a made-up value, and this is the same statement for
   "this brush did not ask".

3. **A brush that sets neither new field is bit-identical to today's, all the way through the GPU.**
   The default `TipSpec` has `hardnessLight == hardnessFull == 0.9f`, so `dab.hardness` is the
   stroke's uniform value, and `jb_dab.frag` multiplies the uniform by `1.0`. Test 6 pins the
   Ink preset's pixels; Test 6b pins the *uniform* path, which is the half a cloud test cannot see.

4. **The preset has no grain, no scatter and no jitter, and that is a Decision.** "Tip for detail"
   is a hard small dab and "belly for washes" is a soft large one; adding a tip texture on top
   would make the belly look dirty, and JB-1.07's Test 9 already holds the line that **Pencil is
   the only shipped preset with grain enabled**. Granulation for a wash comes from the paper tooth
   at commit (JB-6.01), which is a different thing at a different scale, and the great brush must
   not also carry a second one.

5. **The pressure curve does the shaping; `TuftTip` does not re-shape it.** `size.base` keeps its
   meaning (the belly DIAMETER at full press — `BrushDabber`'s own comment already says *"Size is
   a DIAMETER in the file; a radius is what a dab carries"*), and the file's existing pressure
   curve on `size` is what carries *the last* of the range. `TuftTip.Spec.radius(d)` then applies
   the sqrt law, the spread term and the tip floor to whatever `Dynamics.eval` returned. So a
   preset can still put a hand-drawn curve on `size`, and the two compose rather than fight — and
   the test that matters is that **radius is monotone non-decreasing in pressure whichever way the
   curve is drawn**, because a curve that dips would make a stroke pinch, and a pinch is the one
   artefact nobody can explain to a person. Test 3.

6. **The footprint is CENTRED on the pen sample at every pressure, always.** R2 §4.3 offers
   "Anchor: tip" and "Anchor: centre" as two settings, and adds the reason: the tip-anchored one
   exists to fix *"the 'dab slides ahead of the cursor' complaint"*. **This spec does not add the
   setting.** Today's engine already stamps every dab at the sample position itself
   (`DabPlacer.emit` → `Dab(x = s.x, y = s.y, …)`), and a footprint centred on the sample **cannot**
   slide ahead of it, at any pressure, at any size, with no extra state and nothing to tune. A
   teardrop that grows *behind* the contact point is the thing that needs the anchor argument; a
   symmetric footprint that simply gets wider is not. **Test 4 computes the coverage-weighted
   centroid of a dab at three pressures and asserts it is the sample point to within 0.05 px** — so
   if a future builder adds the anchor, the test that catches it is already written and named.

7. **The belly is a RADIUS in document px and the one limit is `BrushValidate.MAX_SIZE_PX`, imported
   and never retyped (R19, R10).** Three consumers hold this number: `BrushValidate.MAX_SIZE_PX`
   (the validator), `SizeOpacityDrag.MAX_SIZE` (the drag control, which R19 already made *be* the
   constant rather than a copy of it), and now this preset. **A fourth literal `4096` in this
   project is a bug with a person's brush in it**, and the reason R19 made the constant public in
   the first place. Test 7 asserts the preset against `BrushValidate.MAX_SIZE_PX` read from the
   constant, never a literal, and additionally asserts `SizeOpacityDrag.MAX_SIZE ===` it — which is
   the drift check, restated here because this is the row where a wash brush is large enough for
   somebody to notice.
   And R10 explicitly: the belly is a **document** px number, so a 240 px wash is 240 px on screen
   at zoom 1 and 960 at 4×, and the paper's tooth and the edge rim scale with it (JB-6.01
   Decisions 4 and 9). The size control shows the on-screen circle at its true screen size and
   writes document px; the preset is in the same units as what the control writes.

8. **The preset is `engine: "wet"`, which is what makes it need 6.01 and 6.02 and not before.** It
   is also why this row's `Needs` is honest rather than decorative: without them the file would
   validate, load, appear in the pill and draw a flat hard-edged stamp brush. Its `accumulate` is
   `"wash"`, so the stroke also gets the ceiling that stops it darkening on itself (JB-6.01
   Decision 3), and its `blend` is `"normal"`.

9. **The file's `version` is whatever `BrushJson.versionFor` says, and is never typed by hand.**
   It depends on JB-6.01's Q1, which is still open, and typing the number into this spec would
   freeze an answer nobody has given. So the test reads the number **out of `BrushJson.encode`**
   and asserts the file on disk says the same, and that the file carries the **lowest** version that
   can express it (JB-1.07 Decision 5's rule, which is also the one that keeps `ink` at version 1
   through a version bump). A builder therefore cannot get this wrong by forgetting a number.

10. **A finger must still be able to draw with it** (JB-1.05c Decision 1, carried, because the
    great brush is the one brush everyone will try with a finger first). `PenSample`'s contract is
    that a channel the device cannot report is `NaN` — a finger has pressure but no tilt, and
    **pressure is not NaN**, so the size law is always driven by a real number. The failure this
    spec has to avoid is a *curve* returning NaN (`Dynamics.eval` returns `base + Σ` with NaN
    inputs skipped, so a curve on `pressure` with a finger is fine; a curve on `tilt` would make
    the whole product NaN and `DabPlacer` would drop the dab to radius 0 and the stroke would
    vanish). Decision: **the shipped preset's two hardness Params and its `tipFraction` carry
    NO curves at all** — a curve on any of them is a preset bug, and Test 8 asserts they are
    curve-free. That is a constraint on the preset, not on the format, and it is the cheapest
    possible place to forbid the trap.

11. **The dabber's random stream does not move.** `BrushDabber.look` draws **exactly three** values
    per dab, in a fixed order, "even when the file sets both jitters to zero", because the count is
    part of the brush's identity; and `Scatter.expand` draws `1 + 2n` per input dab. This spec
    evaluates two more `Param`s per dab and therefore **adds no draws**. Test 9 proves it: the same
    seed over the same samples produces the same sequence of random values before and after this
    change, checked by running the dabber with a recording stub and comparing the `random` /
    `sizeDraw` / `angleDraw` triples dab for dab. A change to the dab count is the exact class of
    bug that changes every mark in the app and is invisible in a screenshot.

12. **No `TipShape` growth.** `TipShape` stays five numbers and keeps its meaning: it is still the
    *stroke's* tip, and `strokeHardness` still exists and still carries a brush that sets neither
    new field. `TipShape` is the shader's uniform record and it is not the place for a per-dab
    value; growing it would put a second, competing answer to "what hardness is this stroke" into
    the type that everything else already reads.

## Tuning — the owner's eye, NOT a contract

The whole of this row is look, so almost all of it is Tuning. **No test asserts a number from this
table** — each test asserts a *property* (a hairline draws, a belly is soft, width is monotone,
the footprint is centred) so a tuning pass is a value change with a green suite. The values below
are a proposal; Q3 is the question of whether the name is mine to propose at all.

| # | Parameter | Proposed default | What it is | Where it lives |
|---|---|---|---|---|
| T1 | `size.base` (belly DIAMETER, document px) | **240** | A wash that covers a thumbnail's worth of a page at zoom 1. Under `BrushValidate.MAX_SIZE_PX` (4096) by a wide margin, which is deliberate — a brush near the size limit is a brush whose edge work is 17× the cost. | `wash/brush.json` |
| T2 | `tipFraction` | **0.02** | Tip radius = 2 % of the belly radius, so the lightest touch is a **≈ 5 px line** and the hardest press is 240 px: a 48× range from one brush. | `wash/brush.json` |
| T3 | `spread` | **0.4** | At full press the footprint is 1.4× the plain sqrt law, so the belly widens faster than a simple area law predicts. | `wash/brush.json` |
| T4 | `hardnessLight` | **0.9** | The hairline is a hard edge. At 0.6 the lightest touch is already a smudge-blob and the whole row has failed. | `wash/brush.json` |
| T5 | `hardnessFull` | **0.15** | The belly is soft all the way out, which is what gives the rim somewhere to feather and somewhere for JB-6.01's edge darkening to sit. **Must be below T4.** | `wash/brush.json` |
| T6 | `aspectLight` / `aspectFull` | **0 / −0.25** | Round at the tip, a little short-and-wide at the belly — R2's lateral spreading. | `wash/brush.json` |
| T7 | `spacing` | **0.02** | Tight enough that the belly has no dotting at 240 px and the hairline has no gaps at 5 px. | `wash/brush.json` |
| T8 | `flow` / `opacity` | **0.35 / 0.85** | A soft flow so a single dab is faint and a pass builds; a wash ceiling below 1 so the Level-0 effects have somewhere to go. | `wash/brush.json` |
| T9 | `smoothing` | **0.25** | Between Ink's 0.35 and Pencil's 0.2. | `wash/brush.json` |

## Tests

`TuftTipTest.kt` (NEW, `commonTest`) and `TheGreatBrushTest.kt` (NEW, **`jvmTest`** — it reads the
preset from disk, the rule JB-1.07 Decision 8 set: **no inline copies of a preset in `commonTest`**).
**Every numbered Decision has at least one case. No test asserts a number from the Tuning table.**

1. **`TuftTip.Spec` refuses every bad number, in words, and never clamps** (Decisions 1, and the
   house idiom). `NaN`, `+Inf`, `−Inf` and an out-of-range value on each of the eight fields, each
   `require`-refused with a message naming **the field and the value**. Then the one that is a
   Decision rather than hygiene: **`hardnessAtFull >= hardnessAtLight` is refused**, with a message
   that says what it is in plain words (*"hardnessAtFull … must be below hardnessAtLight: a brush
   that is as hard at full press as at a feather touch is two brushes wearing one name"*). That
   message is the row's whole claim, kept where the next person will read it.

2. **The three shapes of the brush, as properties** (Decisions 5, 10, T4/T5).
   - **Tip:** at `d = 0` the radius is `bellyRadius × tipFraction` **exactly** (the floor, not the
     sqrt law), the hardness is `hardnessAtLight` exactly, and `TipMath.coverage(0, 0, r, 0, tip)`
     is `> 0.9` — a hard little disc. And **it draws**: at the shipped `tipFraction` the dab is
     `>= tip.minPx` wide, so it is a line and not a speck.
   - **Belly:** at `d = 1` the radius is `bellyRadius × 1 × (1 + spread)` exactly, the hardness is
     `hardnessAtFull` exactly, and `TipMath.coverage` at `0.9 × radius` is **strictly between 0 and
     1** — a soft rim, not a hard one. The exact expected values are written out with their
     arithmetic, per LEAD_RULINGS R9's standing rule.
   - **The ratio:** the belly radius is at least **10×** the tip radius. Ten is the smallest number
     that makes "one brush" true; a builder who types `tipFraction: 0.2` and passes every other
     test here has shipped two brushes and this is the line that says so.

3. **Radius is monotone non-decreasing in pressure** (Decision 5), swept over `d` at 0.01 steps
   for eight `tipFraction`/`spread` combinations including both ends of their ranges. And the
   property that actually matters: **it is monotone even when the file's `size` curve is not** —
   feed `TuftTip.Spec.radius` a `d` sequence that goes 0 → 0.4 → 0.2 → 1 (a curve with a dip, which
   `BrushValidate` allows because it only ranges the *base*) and assert the resulting dabs are
   passed through `DabPlacer`, whose `emit` **rejects a radius below the previous one**? — no, it
   does not, and it should not. So the assertion is on the *shipped preset* only: through
   `BrushDabber` + `DabPlacer` over a 100-sample stroke with rising pressure, **every dab's radius
   is `>=` the one before it**, and the test says in its message that a dip is a pinch.

4. **THE FOOTPRINT NEVER SLIDES (Decision 6).** For one dab at `d ∈ {0.05, 0.5, 1.0}` on a
   `TipMath` grid of ±`2 × radius` at 0.25 px steps, compute the coverage-weighted centroid and
   assert it is within **0.05 px** of the dab centre **on both axes** — and write the sum out in
   the test. Then the same three dabs with `aspectAtFull = −0.25` (a short-and-wide belly), because
   an asymmetric tip is where a centroid drifts and a round tip hides it. This is the test that
   answers R2's *"the dab slides ahead of the cursor"* complaint by construction, and it is the one
   I would most want a future builder to have to read.

5. **The shipped preset really is one brush** (Decisions 1, 5, T2, T4/T5). Read
   `joybrush/brushes/wash/brush.json` from disk, `BrushJson.decodeChecked` it (which is the door
   that both decodes **and** validates, `BrushJson`'s own class KDoc), and assert:
   - `engine == "wet"`, `accumulate == "wash"`, `blend == "normal"` (Decision 8);
   - `tip.hardnessLight.base > tip.hardnessFull.base`, **strictly** (Decision 1's message, as data);
   - `tip.tipFraction.base * size.base.base / 2` is at least 1 px, so the hairline exists
     (Decision 2's "tip end");
   - `size.base.base * 0.5` is at least 10× that (Test 2's ratio);
   - `tipTexture.enabled == false && paperGrain.enabled == false` (Decision 4);
   - `scatter.amount.base == 0f && scatter.count == 1 && sizeJitter == 0f && angleJitter == 0f`
     (Decision 4 — one brush, not a spray can);
   - the preset is a **stable, reproducible render**: the same file, the same seed and the same
     100 samples produce the identical `List<Dab>`, twice, asserted with `==` on the data classes.

6. **The other shipped brushes are untouched, byte for byte** (Decisions 2, 3). `ink/brush.json` and
   `pencil/brush.json` on disk are **unchanged files** (the test hashes them and says so in the
   message if not), and the Ink preset driven through `BrushDabber` → `DabPlacer` → `RefCanvas` at
   a fixed seed produces a tile that is `==` (array equality) to the tile it produces with the
   new fields defaulted. Then **6b, the half a cloud test cannot see**: assert
   `Dab(…).hardness` is `NaN` for a dab the Ink preset produced — i.e. the NaN sentinel really is
   what a brush that asks for nothing carries, and `fillInstances` is therefore writing today's six
   values into today's shader with a seventh slot it never reads.

7. **One brush-size limit, and it is the constant (Decisions 7, R19).**
   `size.base.base > 0f && size.base.base <= BrushValidate.MAX_SIZE_PX` — the constant **imported**,
   and the test file contains no literal `4096` (a companion assertion greps its own source for the
   literal, so the check is that the number is read, not that the value happens to be right). Then
   the drift check: `assertEquals(BrushValidate.MAX_SIZE_PX, SizeOpacityDrag.MAX_SIZE)` and
   `assertEquals(BrushValidate.MAX_SIZE_PX, DabPlacer.MAX_RADIUS_PX * 2f)`. **Three consumers, one
   constant, and this row is the one that would notice a fourth.**

8. **A finger draws with it, and no curve is allowed to poison it** (Decision 10). Decode the
   preset and assert **`tip.hardnessLight.inputs`, `tip.hardnessFull.inputs`, `tip.tipFraction.inputs`
   and `tip.spread.inputs` are all empty** — the Decision, as data. Then build a dabber over a
   40-sample finger stroke (`pressure = 1`, `tilt = NaN`, `azimuth = NaN`, `tool = FINGER`) and
   assert every dab is **finite in every field** and that at least one dab has `radius > 0`. Then
   the sweep from JB-1.05c Test 12: over a grid of `d`, `tipFraction`, `spread` and hardness values
   including `NaN`, `±Inf` and out-of-range, every returned radius and hardness is finite and in
   range, or a refusal was thrown — never a silent NaN.

9. **The random stream did not move** (Decision 11). A recording dabber stub that captures the
   `(random, sizeDraw, angleDraw)` triple it received for each of 200 dabs — by way of a
   `Param` whose curve reads the `random` input and records it — produces the **same 200 triples in
   the same order** with the new fields at their defaults and with them set. The count is three per
   dab, asserted, and the failure message says that a change here re-draws every mark in the app.

10. **The version on disk is whatever `BrushJson` writes, and it is the lowest that works**
    (Decision 9, JB-1.07 Decision 5). `BrushJson.encode(preset)` contains the same `"version"` the
    file has, `preset.version == BrushJson.versionFor(preset)`, and — the assertion that is easy to
    lose and impossible to notice — **a brush that uses no new word still encodes as the version it
    encodes as today.** If JB-6.01's Q1 ruled (a) and the wash file is version 3, this test goes
    green; if it ruled (b) and the file is version 2, it also goes green. **The builder never types
    the number and the test cannot go stale on it.**

11. **The file is a real preset: on disk, in `index.txt`, and not a copy of another one** (Decisions
    4, 8). Two-way set equality between the directories under `joybrush/brushes` that hold a
    `brush.json` and the names in `index.txt`; and for every pair of shipped presets at least one of
    `{engine, accumulate, blend, tip.corner, size.base, flow.base, opacity.base}` differs from the
    wash brush's. A new folder not in `index.txt` validates, round-trips and is never seen by
    anybody — JB-1.07's Decision 7 — and this is the test that makes the wash brush a brush.

12. **The GPU half: one new case in `tools/shader_check.js`, deferred without a browser** (R6).
    Assert `compiled: true` and that the existing dab case's numbers are **unchanged** — the file's
    header says "Pass = `compiled`:true, strokeCentre ≈ 0.5 (never above), committedRGBA ≈
    [127,0,0,127]", and all three must still hold, because the new attribute defaults to the
    uniforms' values. Then one new assertion: the same dab uploaded with `a_tip = (0.05, −0.25)`
    commits a pixel whose rim coverage is strictly softer than the same dab with
    `a_tip = (0.95, 0.0)`, sampled at `0.9 × radius`. If no Chrome is reachable, say so and mark the
    GPU half deferred; do not report it as passed.

**Command:** `./gradlew -p joybrush :core:jvmTest` — BUILD SUCCESSFUL, 0 failures, with the count
quoted before and after.

## Do not

- **Do not make the footprint offset from the sample.** Decision 6 removes R2's "anchor" setting on
  purpose; Test 4 is the named test that catches it.
- Do not add a 3-D tuft, a spine, a solver, `ψ` twist, plasticity, pore resistance or a split map.
  Blueprint §5 rules the 3-D brush model out (US 9,030,464) and R2 §4.3 is explicitly a *proposal*
  to be reduced, not a list to implement. Round brushes ignore twist entirely (R2: *"Expresii's
  lesson"*).
- Do not add grain or scatter to the wash preset (Decision 4), and do not enable it on any other
  shipped preset either.
- Do not add draws to `BrushDabber` or to `Scatter` (Decision 11). Three per dab, and `1 + 2n` per
  input dab, forever.
- Do not put a per-dab value in `TipShape` (Decision 12), and do not remove or re-purpose
  `strokeHardness` — a brush that sets neither new field still needs it.
- Do not re-type `4096` anywhere, and do not add a fourth consumer of the brush-size limit that
  does not import `BrushValidate.MAX_SIZE_PX` (Decision 7, R19). Same for `Tiles.SIZE`,
  `DabPlacer.MAX_RADIUS_PX` and `BRUSH_VERSION`.
- Do not type a `"version"` into `wash/brush.json` from this spec (Decision 9). Read it out of
  `BrushJson.encode` in the test and let the file be whatever that says.
- Do not give the tip or the belly a curve on `tilt`, `lean` or `barrel` (Decision 10). Those are
  `NaN` on the Note's own pen for some channels and on every finger, and `Dynamics.eval`'s own
  contract is that the *result* is not clamped there.
- Do not change `size.base`'s meaning: it is a **diameter**, in **document** px, and the belly's
  full-press diameter (R10, and `BrushDabber`'s existing comment).
- Do not add a mode switch, a "wash mode", or a second brush to `index.txt` to cover the case where
  the great brush does not do both jobs. That is the failure the row exists to prevent.

## Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest` green, output pasted, count quoted before and after.
- [ ] `tools/shader_check.js` green, **or** the GPU half explicitly marked deferred with the reason
      (R6).
- [ ] `git status --short` shows only owner-area files.
- [ ] Committed as `JB-6.03: the one great brush`, pushed.
- [ ] ROADMAP's Phase 6 row for JB-6.03 updated (the Lead's file).
- [ ] On the phone: light → a hairline you can draw a whisker with; hard → a wash that covers a
      palm; **one brush, no switch**; the mark never sits ahead of the pen; Ink and Pencil are
      indistinguishable from yesterday; and a wash drawn with it shows the Level-0 rim and paper
      tooth and settles for about half a second.

## Questions

_(Spec writer, 2026-09-29. The `⚪ Outline` row: "JB-6.03 The one great brush (tip for detail, belly
for washes) | T1 | 6.02". Decisions 1–12 are complete and buildable. Three things are not mine.)_

**Q1 — this changes a `🟧 Built` contract, and two other rows want the same edit.**
JB-0.07's dab instance layout (6 floats, 3 attributes, stride 24) is what a `🟧 Built` row left and
what JB-1.05c's "Do not" list explicitly protects. Decision 1 widens it to 8 floats. I believe the
change is necessary and minimal — **one appended `vec2`, three untouched attributes, one stride** —
but "minimal and necessary" is still a reviewer's word, not mine, and this is a `🟧 Built` contract.

There is also a **collision nobody has noticed yet, and it is worth a line of the board**: JB-1.05c
Q4 asks the same question from the other side — should grain `depth` be per-dab or per-stroke? —
and its answer is *the same buffer widening*, for *a different number*. Both specs were written
without knowing the other existed, and both are `🟨 Draft`. Options:

- **(a) 6.03 widens to 8 now; JB-1.05c widens to 12 (or adds a `vec3`) when it lands.** One row, one
  edit, one set of tests — the R3 discipline. Cost: two stride changes, and the second one has to
  find 6.03's files.
- **(b) One combined row** that widens the buffer once and carries both numbers, and both specs
  depend on it. Cleaner in the file, and it puts two unrelated questions in one change.
- **(c) 6.01–6.03 keep the current tip as a stroke uniform and the great brush ships with a
  compromise** — one hardness for the whole stroke, so the hairline is a small *soft* dab and the
  belly a large *soft* dab. **I am listing this because it is the option that ships, and it does
  not deliver the row.** I do not recommend it: a soft small dab is a smudge, not a hairline, and
  the owner will see a smudge.

**I have specified (a).** My recommendation is that 6.03 and JB-1.05c are **not dispatched at the
same time** and that the second one to land is expected to re-read the first.

**Q2 — does the great brush need a colour gradient along its length ("tip in dark, heel in
light")?** R8 §3C and R2 §4.4 both list it, and it is one of the owner's most distinctive brushes in
other apps; the R8 note is also explicit that it is fine patent-wise ("a loading tray UI that is
separate from the painting" and "no mode that sweeps over the painting sampling it without changing
it"). It is **not** in this spec, because it needs a *per-dab colour*, and `Dab` has no colour
field — the whole engine takes one `u_color` per stroke. So it is a third per-dab value, a third
attribute, and a **document** question: does the layer's tile still hold premultiplied RGBA, or
does the wet engine's per-tile state carry a colour (R1 §6.1's `Pigment16F`)? **I have left it out
deliberately**, because adding a per-dab colour to a `🟧 Built` record to serve a feature nobody has
asked for in Joy Brush yet is the wrong order of work. If you want it, it wants its own row after
6.02, and it is not a small one.

**Q3 — the name, and where it sits in the list.** The folder, the `id` and the `name` a person taps
are all "wash" in my proposal, which matches blueprint §1 idea 7's list (*"inking, washing, easy
fills"*) and R2 §4.4's preset table. But the brush is not only a wash — it is the brush you would
pick for a confident line as well, and a person looking for it in a pill that says "Ink" and
"Wash" will not know which is the interesting one. Options: (a) **`wash`** as proposed, sitting
after `softair` in the painting-brush group; (b) a name that says what it is — *"Big brush"*, or
*"One brush"*, or the name Expresii users know; (c) name it after the behaviour — *"Tip & belly"*
— which nobody will remember by the third day.
**I have specified (a) and the placement after `softair`**, and I am flagging it because
`index.txt` is **also** on JB-1.07's owner area and JB-1.07 writes the whole file including the
order. **Only one of the two rows may write it.** If JB-1.07 lands first, 6.03 adds one line; if
6.03 lands first, JB-1.07 must read this spec's order rather than its own proposal. That is a
scheduling fact the board does not currently show, and it is the third thing in this spec that
somebody will otherwise discover twice.

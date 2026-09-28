# JB-0.03 — Brush preset format (brush.json) and the dynamics evaluator

| | |
|---|---|
| **Tier** | T2 (no vision needed) |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-0.01 (done: `Curve`), and the serialization build edit from JB-0.02 (if 0.02 is not built yet, make the SAME exact edit — it is identical and idempotent) |
| **Owner area** | `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/brush/` (new), `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/brush/` (new), `joybrush/brushes/` (new folder: the two example files below) |
| **Estimated size** | ~400 lines + ~300 lines of tests |

## Goal
A brush is a small, shareable data file. This spec defines it, parses it, validates it, and
evaluates every brush setting from the pen's inputs. The phone and the PC Brush Lab both read these
files; imported Photoshop/Procreate brushes are converted into them later (Phase 8).

## Model (decided)
Every numeric setting is a **Param**: a base value, plus zero or more *(input → curve)* pairs.
Each pair's curve output is combined with the base:
- `combine = "multiply"` (default): value = base × Π curve(input)
- `combine = "add"`: value = base + Σ curve(input)
Then clamped to the setting's range. Curves are the existing `Curve` class (points in 0..1 → any y).

**Inputs** (all normalised to 0..1 before the curve):
| id | meaning | normalisation |
|---|---|---|
| `pressure` | pen pressure | as is |
| `tilt` | 0 upright … 1 flat | tilt / (π/2) |
| `speed` | stroke speed | min(speed_px_per_s / 3000, 1) |
| `direction` | stroke direction | (angle + π) / 2π |
| `lean` | pen lean direction (azimuth) | (azimuth + π) / 2π |
| `attack` | angle between lean and stroke direction | abs(wrapped difference) / π |
| `distance` | distance along the stroke | min(distance_px / 1000, 1) |
| `random` | new random value every dab | 0..1 |
| `strokeRandom` | one random value per stroke | 0..1 |
| `barrel` | barrel rotation | (barrel + π) / 2π |
An input that is NaN (device lacks it) makes that pair contribute **nothing** (×1 for multiply,
+0 for add).

## Contract (verbatim)
```kotlin
package cc.joycreator.joybrush.core.brush

@Serializable enum class BrushInput { pressure, tilt, speed, direction, lean, attack, distance, random, strokeRandom, barrel }
@Serializable data class InputCurve(val input: BrushInput, val curve: List<List<Float>>) // [[x,y],…]
@Serializable data class Param(val base: Float, val inputs: List<InputCurve> = emptyList(), val combine: String = "multiply")

@Serializable data class TipSpec(
    val source: String = "procedural",   // "procedural" | "image"
    val image: String? = null,           // path inside the brush folder when source == image
    val corner: Float = 2f,              // superellipse exponent (jb_tip.glsl)
    val taper: Float = 0f,
    val aspect: Float = 0f,
    val angle: Param = Param(0f),        // degrees
    val followDirection: Boolean = false,// tip turns with DirectionTracker's output
    val hardness: Param = Param(0.9f),
    val minPx: Float = 1f,
)

@Serializable data class GrainSpec(
    val enabled: Boolean = false,
    val source: String = "cloud",        // "cloud" | "image"
    val image: String? = null,
    val scale: Float = 1f,
    val depth: Param = Param(1f),        // jb_grainLevel depth
    val edge: Float = 0.3f,              // jb_heightCoverage edge
    val tiltGradient: Float = 0f,
    val radial: Float = 0f,
)

@Serializable data class ScatterSpec(val amount: Param = Param(0f), val count: Int = 1, val countJitter: Float = 0f, val bothAxes: Boolean = true)

@Serializable data class ColorJitter(val hue: Float = 0f, val saturation: Float = 0f, val value: Float = 0f, val perStroke: Boolean = false)

@Serializable data class BrushPreset(
    val format: String = "joybrush.brush",
    val version: Int = 1,
    val id: String,
    val name: String,
    val engine: String = "stamp",        // "stamp" | "smudge" | "wet" (later phases)
    val tip: TipSpec = TipSpec(),
    val size: Param,                     // diameter in px
    val opacity: Param = Param(1f),      // ceiling for the whole stroke
    val flow: Param = Param(1f),         // per dab
    val spacing: Float = 0.04f,          // fraction of diameter
    val tipTexture: GrainSpec = GrainSpec(),   // dab space: turns with the tip
    val paperGrain: GrainSpec = GrainSpec(),   // canvas space: stays put
    val scatter: ScatterSpec = ScatterSpec(),
    val sizeJitter: Float = 0f,
    val angleJitter: Float = 0f,         // degrees
    val color: ColorJitter = ColorJitter(),
    val accumulate: String = "wash",     // "wash" (never darker than opacity) | "buildup"
    val blend: String = "normal",        // "normal" | "erase"
    val smoothing: Float = 0.3f,
    val license: String = "CC0",
    val author: String = "",
    val sourceFormat: String = "native", // "native" | "abr" | "procreate" | "myb" | "kpp"
    val extensions: Map<String, String> = emptyMap(), // lossless storage of imported raw settings
)

/** Everything the evaluator needs about one dab. NaN = not available. */
data class DabInputs(
    val pressure: Float, val tilt: Float, val speedPxPerS: Float, val direction: Float,
    val lean: Float, val distancePx: Float, val random: Float, val strokeRandom: Float, val barrel: Float,
)

object BrushJson {
    fun decode(json: String): BrushPreset   // unknown keys ignored; throws BrushException
    fun encode(p: BrushPreset): String      // pretty, stable
}
class BrushException(message: String) : Exception(message)

object Dynamics {
    fun normalise(input: BrushInput, d: DabInputs): Float   // per the table; NaN passes through
    fun eval(param: Param, d: DabInputs): Float             // base combined with its curves
}
object BrushValidate { fun validate(p: BrushPreset): List<String> }
```
Build `Curve` objects from `InputCurve.curve` once and cache them per Param instance (identity map)
— eval() runs for every dab.

**Validation:** format/version (newer = error), id non-empty, size.base > 0, spacing in 0.005..5,
corner in 0.5..64, taper 0..1, aspect −1..1, every curve has ≥ 1 point and each point has exactly 2
numbers, combine is "multiply" or "add", engine/accumulate/blend in their sets, image paths set when
source == "image".

## Example files to add (and test-load)
`joybrush/brushes/ink/brush.json` — hard round ink: size base 6 with pressure curve
`[[0,0.15],[1,1]]`, hardness 0.95, flow 1, opacity 1, wash, spacing 0.04, smoothing 0.35.
`joybrush/brushes/pencil/brush.json` — pencil: size 3 with pressure `[[0,0.6],[1,1]]`, opacity with
pressure `[[0,0.1],[1,0.9]]`, paperGrain enabled (cloud, depth with pressure `[[0,0.2],[1,0.9]]`,
edge 0.25, tiltGradient 0.6), tip aspect 0, corner 2, tip followDirection false, smoothing 0.2.

## Tests (`brush/BrushTest.kt`)
1. Both example files decode, validate clean, and re-encode → decode equal.
2. Unknown keys ignored; version 2 → validate error.
3. eval: base only; one multiply curve (pressure 0.5 on `[[0,0],[1,1]]` with base 10 → 5); two
   multiply inputs; add combine; NaN input contributes nothing in both modes.
4. normalise: each input's formula at a few values; attack wraps (lean π−0.1, direction −π+0.1 → small).
5. Performance: 1,000,000 eval() calls with 2 curves finish in < 2 s on the JVM (generous).
6. Validation: one failing case per rule.

**Command:** `./gradlew -p joybrush :core:jvmTest` — pass = BUILD SUCCESSFUL, BrushTest 0 failures.

## Do not
- Do not render anything. Do not add inputs or fields. No Android/java imports in commonMain.
- Keep enum constant names lowercase exactly as written (they are the JSON spelling).

## Definition of done
Tests pass (paste) · only owner-area files changed · commit `JB-0.03: brush preset + dynamics` ·
ROADMAP row → 🟧 Built.

## Questions
*(raised by the builder of JB-0.03, 2026-09-28 — none of these blocked the spec as written; each is a
gap the builder was told not to guess at. The engine is `🟧 Built`; these need a ruling before the
specs that meet them.)*

1. **Should there be a limit on how much curve a brush file may carry?** The validation list has no
   count rule, and `Compiled.of` builds one 256-float lookup table per curve on the render thread.
   A file with 10,000 inputs on `size` would allocate ~10 MB and then loop 10,000 times *per dab*.
   This is reachable: JB-1.21 hot-reloads brush files over Wi-Fi and Phase 8 imports `.abr`/`.myb`/
   `.kpp` from other people's folders. Suggested: cap inputs per Param and points per curve, and say
   so in the validation list.
2. **Curve x outside 0..1, and the `source` sets.** The model says curve points are "in 0..1", but
   the rule list only asks for ≥1 point of exactly 2 numbers, so `x = -5` passes and is silently
   clamped into a ramp. Same for `TipSpec.source` / `GrainSpec.source`: only `"image"` is looked at,
   so a typo renders as procedural with no message. Should these be rules?
3. **Non-finite numbers.** Nothing in the list ranges `smoothing`, `minPx`, `sizeJitter`,
   `angleJitter`, `countJitter`, `tip.angle` or the grain settings, so a `"hardness": {"base": 1e999}`
   (which decodes to Infinity) passes validation. `BrushJson.encode` now turns that into a
   `BrushException` instead of a raw library error, but should validation say it first?
4. **`version` below 1** is read with v1 assumptions. "newer = error" is all the spec asks; a ruling on
   older-than-1 would be cheap to add later.
5. **Should the example files be read from disk by a test?** `commonTest` cannot open a file and
   `src/jvmTest` is outside this spec's owner area, so `BrushTest` carries byte-identical copies of
   `joybrush/brushes/{ink,pencil}/brush.json` (verified identical today). A JVM-only test that reads
   the real files would make drift impossible — may a later spec own that?
6. **For JB-1.04, not for this spec:** `DabPlacer` (JB-0.07) calls `radiusOf(sample)` **twice per
   dab** — once in `dabAt` and again in `step` (`DabPlacer.kt:49,50,56,59`). Anything that advances
   state inside those lambdas (a distance-along-stroke counter, an RNG) will double-count, and
   `PenSample` carries no distance, speed or random at all, so four of the ten inputs have no source
   yet. Whoever wires the dynamics up needs the lambdas called exactly once per dab, with a
   `DabInputs` (or the dab index and accumulated distance) passed in.

---

## Lead rulings 2026-09-28 — all six answered (folded in as JB-0.03b)

The rulings live in `LEAD_RULINGS.md`; they are copied here so this spec reads on its own. **JB-0.03b**
(`specs/JB-0.03b_brush_validation_hardening.md`) is the code that carries them: every ruling became a
rule in `BrushValidate` with its own test, and the range table below is what `validate` now asks.

1. **Curve budget (Q1).** At most **8 `inputs` per `Param`** and **64 points per curve**. Each input
   is a `Curve` — a 256-float lookup table — built once and evaluated on *every dab of every stroke*,
   so a file with 10,000 inputs on `size` is a denial of service on the render thread, not just a big
   file. The caps are in `validate`, not in the loader, so they hold for every path that reads a file.
2. **Curve points are numbers, and sources are words (Q2).** Every point's **x is in 0..1** — it is
   where the input sits, and `Curve.eval` clamps it, so an x of −5 is a ramp nobody drew — and
   **every y is finite** (y is the setting's own value, so it is not ranged here). `tip.source` ∈
   {`procedural`, `image`}; `tipTexture.source` and `paperGrain.source` ∈ {`cloud`, `image`} — a typo
   used to render as procedural with nothing said at all.
3. **Every number in a file must be finite (Q3)**, including every `Param` base, plus a range for
   every setting that had none:

   | Field | Range |
   |---|---|
   | `size.base` | above 0 and ≤ 4096 |
   | `smoothing` | 0..1 |
   | `tip.minPx` | 0.25..16 |
   | `sizeJitter` | 0..1 |
   | `angleJitter` | 0..360 |
   | `scatter.count` | 1..16 |
   | `scatter.countJitter` | 0..1 |
   | grain scale (`tipTexture`, `paperGrain`) | above 0 and ≤ 64 |
   | grain edge | 0..1 |
   | grain tiltGradient | −4..4 |
   | grain radial | 0..4 |
   | color hue/saturation/value jitter | 0..1 |

   Range rules are written `v in lo..hi`, which fails NaN and ±Infinity as well as the numbers
   outside it. Where the lower bound is *excluded* ("above 0"), the rule is
   `!(v > lo) || v > hi`, which fails the same three — a bare `!(v > 0f)` lets +Infinity through,
   which is exactly how a `"base": 1e999` size once reached the dab loop. Seven `Param` bases are not
   in the table (`opacity`, `flow`, `tip.angle`, `tip.hardness`, the two grain depths,
   `scatter.amount`); for those, "must be finite" is the whole rule until they are ranged.
4. **`version < 1` is an error (Q4)** — `"unknown brush version N"`. A file claiming version 0 would
   be read with v1's rules, and refusing it costs nothing.
5. **The shipped files are read from disk (Q5).** `ShippedBrushFilesTest` (jvmTest) finds the
   `joybrush/` folder the way `WriteGrainAssets` does and decodes + validates **every**
   `joybrush/brushes/*/brush.json` with zero problems, so the two example files cannot drift out of
   the rules. `BrushTest`'s byte-identical inline copies stay — commonTest cannot open a file.
6. **Already done by the Lead (Q6, for JB-1.04):** `DabPlacer` now asks the brush exactly once per
   dab, passing distance and index — see `DabLook` in `paint/DabPlacer.kt`.

**Plus, from the first adversarial review (Lead ruling R1).** A brush file with
`"size": {"base": 1e999}` decodes to `+Inf`; `Dynamics.eval` is deliberately unclamped, so the size
evaluated to `+Inf`, the dab loop placed one dab and then stepped by `Inf` forever, and the stroke
drew one dot and silently swallowed the rest. That is fixed in **two** places:

- **Engine (done by the Lead):** `DabPlacer` clamps every dab to finite values (radius 0..2048 px,
  flow 0..1, cap 0..1, angle finite) and the step to the next dab is always finite, so a bad brush
  can no longer hang or freeze a stroke. Test: `anInfiniteBrushSizeCannotFreezeTheStroke`.
- **Validation (JB-0.03b):** the `size.base` row above, so the file is *refused and named* before
  the engine is ever asked. Test: `anInfiniteBrushSizeIsRefusedBeforeItReachesTheEngine`.



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

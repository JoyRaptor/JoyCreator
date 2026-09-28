# JB-1.04 — BrushDabber: a brush file drives every dab (pressure, tilt, speed, randomness)

| | |
|---|---|
| **Tier** | T2 |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-0.03 (Built), JB-0.07 (Built: `DabPlacer`, `DabLook`, `DirectionTracker`) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/brush/BrushDabber.kt`, NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/brush/SplitMix.kt`, NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/brush/BrushDabberTest.kt` |
| **Estimated size** | ~200 lines + ~200 lines of tests |

## Goal
Today the drawing view uses a hard-coded brush. This connects a `BrushPreset` (brush.json) to the
dab placer, so every dab's size, angle, flow and opacity come from the brush file's curves and the
pen — the thing that makes "Ink", "Pencil" etc. behave differently.

## Existing contracts (verbatim, do not change)
```kotlin
// paint/DabPlacer.kt
data class DabLook(val radius: Float, val angle: Float = 0f, val flow: Float = 1f, val cap: Float = Float.NaN)
class DabPlacer(spacing: Float, look: (sample: PenSample, distancePx: Float, index: Int) -> DabLook,
                cap: Float = 1f, minSpacingPx: Float = 0.5f)   // look is called exactly once per dab, in order
// brush/Dynamics.kt
data class DabInputs(val pressure: Float, val tilt: Float, val speedPxPerS: Float, val direction: Float,
    val lean: Float, val distancePx: Float, val random: Float, val strokeRandom: Float, val barrel: Float)
object Dynamics { fun eval(param: Param, d: DabInputs): Float }
// input/DirectionTracker.kt
class DirectionTracker(lengthScale: Float = 6f, tiltThreshold: Float = 0.17f, minStep: Float = 0.25f) {
    fun update(s: PenSample): Float }
```

## The API to build
```kotlin
package cc.joycreator.joybrush.core.brush

/** Deterministic 64-bit generator (SplitMix64). Same seed → same numbers on every platform. */
class SplitMix(seed: Long) { fun nextLong(): Long; fun nextFloat(): Float /* [0,1), top 24 bits / 2^24 */ }

/** One per stroke. */
class BrushDabber(val preset: BrushPreset, seed: Long) {
    /** Pass as DabPlacer's `look`. */
    fun look(sample: PenSample, distancePx: Float, index: Int): DabLook
    /** For DabPlacer's `spacing`. */
    val spacing: Float
    /** Hardness for the whole stroke (the shader takes it as a uniform): evaluated at the FIRST dab. */
    val strokeHardness: Float   // valid after the first look(); before that = preset.tip.hardness.base
    /** BUILD_UP strokes: opacity evaluated at the first dab, applied on commit. WASH: 1. */
    val strokeOpacity: Float
}
```

## Decisions
1. **Randomness:** one `SplitMix(seed)`. `strokeRandom` = its first `nextFloat()` (drawn in the
   constructor). Every `look()` draws exactly **three** floats in this order: `random` (the
   `DabInputs.random`), size-jitter float, angle-jitter float. Never draw a different count, or
   replays diverge.
2. **Speed:** from consecutive `look()` calls: `raw = Δdistance / Δtime × 1000` (px/s, where Δtime =
   `sample.timeMs` difference; if Δtime ≤ 0 keep the previous speed). Smoothed: `speed += (raw −
   speed) × (1 − exp(−Δtime / 50ms))`. First dab: speed 0.
3. **Direction:** a `DirectionTracker()` owned by the dabber, fed every sample passed to `look()`;
   `direction` input = its output. **Lean** = `sample.azimuth`; **tilt** = `sample.tilt`; **barrel** =
   `sample.barrel` (NaN stays NaN — Dynamics treats NaN as "contributes nothing").
4. **Radius:** `d = Dynamics.eval(preset.size, in)`; `d *= 1 + preset.sizeJitter × (2·j1 − 1)`;
   `radius = max(d, preset.tip.minPx) / 2`.
5. **Angle (radians):** `rad(Dynamics.eval(preset.tip.angle, in))` + (if `tip.followDirection` the
   tracker's direction) + `rad(preset.angleJitter) × (2·j2 − 1)`.
6. **Flow:** `Dynamics.eval(preset.flow, in)` clamped 0..1.
7. **Cap:** if `preset.accumulate == "wash"`: `Dynamics.eval(preset.opacity, in)` clamped 0..1 per dab
   (this is "fade by pressure"); if `"buildup"`: `cap = 1` and `strokeOpacity` = the opacity evaluated
   at the first dab.
8. `spacing = preset.spacing`.

## Tests (`BrushDabberTest.kt`)
Use the pencil and ink presets from `joybrush/brushes/` (inline copies, as BrushTest does).
1. Same seed + same samples through a `DabPlacer` → identical dab lists; different seed → different
   (for a preset with sizeJitter 0.3).
2. Ink: pressure 0 → radius = 0.15 × base/2 (per its curve), pressure 1 → base/2.
3. Pencil in wash mode: cap follows its opacity pressure curve per dab.
4. `followDirection` true: a straight stroke along +y gives angles ≈ π/2 after the first few dabs.
5. Speed: samples 10 px apart, 10 ms apart → speed converges towards 1000 px/s.
6. NaN tilt with a tilt-driven Param → the Param equals its base.
7. `look()` called N times draws exactly 3N + 1 floats (check by replaying a `SplitMix(seed)`).
8. buildup preset: every cap is 1 and `strokeOpacity` equals the first dab's evaluated opacity.

**Command:** `./gradlew -p joybrush :core:jvmTest` — BUILD SUCCESSFUL, 0 failures.

## Do not
Do not change DabPlacer, Dynamics, BrushPreset or the drawing view (wiring into `JbCanvasView` is
JB-1.05b). No `kotlin.random` (its algorithm is not promised across versions).

## Definition of done
Tests pass (paste) · only owner-area files · commit `JB-1.04: brush dabber` · ROADMAP row → 🟧 Built.

## Questions

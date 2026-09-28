# JB-1.05a — Scatter and count (from natural jitter to "leaves")

| | |
|---|---|
| **Tier** | T2 |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-1.04 (uses `SplitMix`) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/brush/Scatter.kt`, NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/brush/ScatterTest.kt` |
| **Estimated size** | ~100 lines + ~120 lines of tests |

## Goal
The owner: "a scatter slider which can make your lines have slightly natural jitter, but if you crank
it all the way up it scatters like leaves." Each placed dab becomes `count` dabs, each pushed off the
stroke by a random amount.

## Contract
```kotlin
package cc.joycreator.joybrush.core.brush
object Scatter {
    /**
     * Expands dabs in order. For each input dab: n = spec.count, reduced by countJitter (see below),
     * at least 1. Each copy is offset by (u, v) × amount × diameter, where amount = Dynamics.eval(spec.amount, inputsOf(dab)).
     * bothAxes = false → offset only ACROSS the stroke direction (dab.angle + π/2); true → across AND along.
     * Returns a new list; input dabs are not modified. Deterministic for a given SplitMix state.
     */
    fun expand(dabs: List<Dab>, spec: ScatterSpec, rng: SplitMix, inputsOf: (Dab) -> DabInputs): List<Dab>
}
```

## Decisions
1. Per input dab draw, in this order: `c` (count jitter), then for each copy `a`, `b`.
2. `n = max(1, round(spec.count × (1 − spec.countJitter × c)))`.
3. Offsets: `across = (2a − 1)`, `along = if (bothAxes) (2b − 1) else 0` (still draw `b` either way, so
   the random stream does not depend on the setting). Offset vector = across × perpendicular + along ×
   tangent, where tangent = (cos angle, sin angle) of the dab, scaled by `amount × 2 × radius`.
4. amount 0 and count 1 → output equals input exactly (same objects' values).
5. Copies keep every other field of the source dab.

## Tests
1. amount 0, count 1 → identical list. 2. count 3 → 3× dabs. 3. bothAxes false → every offset is
perpendicular to the dab angle (dot product with the tangent ≈ 0). 4. offsets are bounded by
amount × diameter. 5. deterministic with equal seeds; countJitter 1 can reduce n to 1 but never 0.
6. The number of random draws per input dab is 1 + 2n regardless of bothAxes.

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures.

## Do not
No changes outside the owner area.

## Definition of done
Tests pass (paste) · commit `JB-1.05a: scatter` · ROADMAP row → 🟧 Built.

## Questions

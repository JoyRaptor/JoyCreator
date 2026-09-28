# JB-1.03 — Procedural tileable "cloud" grain texture (deterministic)

| | |
|---|---|
| **Tier** | T2 (no vision needed) |
| **Status** | see ROADMAP.md |
| **Depends on** | none |
| **Owner area** | `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/grain/CloudNoise.kt` (new), `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/grain/CloudNoiseTest.kt` (new), `joybrush/core/src/jvmTest/kotlin/cc/joycreator/joybrush/core/grain/WriteGrainAssets.kt` (new), `joybrush/assets/grain/` (generated PNGs) |
| **Estimated size** | ~150 lines + ~120 lines of tests |

## Goal
The owner's dry-brush idea thresholds a grayscale "clouds" texture (like Photoshop's Render Clouds)
against pressure and tilt (`joybrush/shaders/jb_grain.glsl`). This spec makes that texture: seamless
when tiled, identical on every platform, and shipped as PNG files the phone and the PC Brush Lab
both load.

## Contract (verbatim)
```kotlin
package cc.joycreator.joybrush.core.grain
object CloudNoise {
    /**
     * size×size heights in 0..1, row-major, TILEABLE (wraps seamlessly at both edges).
     * Fractal value noise: [octaves] layers, base lattice = [baseCells] cells across, each octave doubles
     * the cells and multiplies amplitude by [persistence]. Result normalised so min = 0 and max = 1.
     */
    fun generate(size: Int, seed: Long, octaves: Int = 5, baseCells: Int = 4, persistence: Float = 0.5f): FloatArray
}
```

## Decisions
1. Value noise on a periodic lattice: lattice value at integer (i, j) for an octave with `c` cells =
   `hash(seed, octave, i mod c, j mod c)` → 0..1. Periodicity comes from the `mod c`.
2. Hash: 64-bit SplitMix64 of `seed xor (octave * 0x9E3779B97F4A7C15) xor (i * 0xBF58476D1CE4E5B9) xor (j * 0x94D049BB133111EB)`,
   take the top 24 bits / 2^24. (Pure Kotlin Long arithmetic — identical everywhere.)
3. Interpolation: smoothstep-weighted bilinear (`t*t*(3-2t)`).
4. `size` must be a multiple of `baseCells * 2^(octaves-1)`; otherwise throw IllegalArgumentException.
5. Assets written by `WriteGrainAssets` (a jvmTest that ALWAYS runs and overwrites):
   `joybrush/assets/grain/cloud_256.png` (size 256, seed 1), `cloud_512.png` (512, seed 1),
   `cloud_fine_256.png` (256, seed 2, baseCells 16, octaves 3). 8-bit grayscale via `javax.imageio`
   (allowed in jvmTest only). Path: resolve from the working directory upward until a folder named
   `joybrush` containing `settings.gradle.kts` is found.

## Tests (`CloudNoiseTest.kt`)
1. Deterministic: same args → identical arrays; different seed → different.
2. Range: min == 0, max == 1 exactly (after normalisation), all finite.
3. Tileable: for every row, |v[row][0] − v[row][size−1]| is no bigger than the largest
   neighbour-to-neighbour difference in the interior (same for columns) — i.e. the wrap seam is
   no rougher than the texture itself.
4. Bad size throws.
5. Mean is between 0.35 and 0.65 for seeds 1..5 (not lopsided).

**Command:** `./gradlew -p joybrush :core:jvmTest` — pass = BUILD SUCCESSFUL, suite 0 failures, and
the three PNGs exist (paste `ls -l joybrush/assets/grain`).

## Do not
No `java.*` in commonMain. Don't use `kotlin.random.Random` for the lattice (its algorithm is not a
promise across versions) — use the hash above.

## Definition of done
Tests pass (paste) · PNGs committed · only owner-area files changed · commit
`JB-1.03: cloud grain texture` · ROADMAP row → 🟧 Built.

## Questions

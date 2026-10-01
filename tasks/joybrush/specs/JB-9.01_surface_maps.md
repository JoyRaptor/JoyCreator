# JB-9.01 — Surface maps: height → slopes ("normal map") → packed RGBA, in core

| | |
|---|---|
| **Tier** | T1 (pure core, cloud-testable) |
| **Status** | 🟦 Ready (paper specialist, 2026-10-01) |
| **Builder** | OpenCode free agent |
| **Depends on** | none |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paper/SurfaceMaps.kt`, NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/paper/SurfaceMapsTest.kt`, NEW `joybrush/core/src/jvmTest/kotlin/cc/joycreator/joybrush/core/paper/SurfaceAssetTest.kt` |
| **Estimated size** | ~120 lines + ~180 lines of tests |

## Goal
Every paper surface, and every texture an imported brush brings, is a height map. Brushes need its **slopes**
(which way each spot faces) so dry paint can catch the faces that meet the stroke (R10 §5). This row is the one
function that turns a height map into slopes and packs them into the GPU texture layout. The offline tool
`joybrush/tools/paper/pack.py` already does this in Python. This row is its Kotlin twin, and the shipped asset is the golden.

## Contract (verbatim)
```kotlin
package cc.joycreator.joybrush.core.paper

object SurfaceMaps {
    /** Divisor that makes a ramp h = k·x report a slope of exactly k (Scharr weights 3,10,3 over 2 texels). */
    const val SCHARR_NORM = 32f

    /**
     * Slopes of a TILEABLE height map (wraps at every edge: the left neighbour of x = 0 is x = w-1).
     * height: w*h values 0..1, row-major, row 0 = top. Returns (dx, dy) in height units per texel:
     *   dx = (3·(H[y-1][x+1] − H[y-1][x-1]) + 10·(H[y][x+1] − H[y][x-1]) + 3·(H[y+1][x+1] − H[y+1][x-1])) / 32
     *   dy = (3·(H[y+1][x-1] − H[y-1][x-1]) + 10·(H[y+1][x] − H[y-1][x]) + 3·(H[y+1][x+1] − H[y-1][x+1])) / 32
     * (y grows DOWN, so dy > 0 means the ground rises toward the bottom of the picture.)
     */
    fun slopes(height: FloatArray, w: Int, h: Int): Pair<FloatArray, FloatArray>

    /** The 99.9th percentile of |dx| ∪ |dy|, rounded UP to a multiple of 0.001 (pack.py's default). */
    fun defaultSlopeRange(dx: FloatArray, dy: FloatArray): Float

    /** One slope as a byte: round(255 · clamp(0.5 + 0.5·s/slopeRange, 0, 1)). */
    fun encodeSlope(s: Float, slopeRange: Float): Int
    /** Inverse of encodeSlope (byte 0..255 → slope). 128 ↦ +0.5/255·2·slopeRange ≈ 0, NOT exactly 0: documented, tested. */
    fun decodeSlope(b: Int, slopeRange: Float): Float

    /**
     * The GPU surface layout, RGBA8 row-major: R = encodeSlope(dx), G = encodeSlope(dy), B = the height byte as given,
     * A = round(255·h²) where h = heightByte/255. Input is greyscale BYTES (what a PNG holds), so B round-trips exactly.
     */
    fun pack(heightBytes: ByteArray, w: Int, h: Int, slopeRange: Float): ByteArray
}
```

## Decisions already made
1. **Scharr, not Sobel.** Better rotational symmetry, and our deposit depends on direction (R10 §5).
2. **Slopes, not unit normals, are stored.** Slopes add and filter linearly (Mikkelsen 2020/2022). A normal is `normalize(−dx, −dy, 1)` wherever lighting needs one.
3. **A = h²** gives the mips the surface's roughness (variance = A − B²).
4. **Wrap at every edge.** Surfaces are tileable by construction. A non-tileable input still wraps (that is the importer's problem, JB-9.11).
5. Any `w, h ≥ 3`. Not necessarily square or a power of two (imported Photoshop patterns are any size).
6. `slopeRange ≤ 0` or non-finite → `IllegalArgumentException` with that word in the message.

## Steps
1. Write the tests below first. 2. Implement `slopes` with integer index wrapping `(x + w) % w`. 3. `pack`, `encodeSlope`, `decodeSlope`, `defaultSlopeRange`. 4. Run.

## Tests
`SurfaceMapsTest` (commonTest):
1. Constant height 0.4 on 8×8 → every dx, dy == 0f exactly.
2. Ramp along x on a wrapped 64×4 sine: `h = 0.5 + 0.5·sin(2πx/64)`. dx at x is within 1e-3 of the central-difference value `(h[x+1] − h[x−1])/2`. dy is 0 within 1e-6.
3. Wrap: a single bump at (0,0) on 8×8 gives non-zero dx at x = 7 (the wrap neighbour), with the sign: dx(7, 0) > 0, dx(1, 0) < 0.
4. Direction convention: height rising toward the bottom (`h = y/8` on 8×8 interior rows 2..5) gives dy > 0 there.
5. `encodeSlope(0, r)` == 128. `encodeSlope(r, r)` == 255. `encodeSlope(−r, r)` == 0. `encodeSlope(10r, r)` == 255 (clamped).
6. `decodeSlope(encodeSlope(s, r), r)` is within `r/255` of s for s in −r..r in 101 steps.
7. `pack`: B channel bytes == input bytes. A == round(255·(b/255)²) for b in {0, 1, 128, 254, 255}.
8. `slopeRange = 0` → IllegalArgumentException containing "slopeRange".

`SurfaceAssetTest` (jvmTest; it reads the shipped asset, so it is the cross-check against pack.py):
9. Decode `joybrush/assets/paper/surface_pulp_artisan.png` (javax.imageio), take its B channel as the height bytes, and
   `pack(B, 512, 512, slopeRange = 0.099f)` (the catalogue value) must equal the file's RGBA byte for byte, ±1 per channel.
   Report the count of ±1 differences (expected: rounding only).

**Command:** in your worktree (never the main folder; R43): `./gradlew --no-watch-fs -p joybrush :core:jvmTest --rerun-tasks`.
Passing = BUILD SUCCESSFUL and your counts read from `joybrush/core/build/test-results/jvmTest/*.xml`.

## Do not
- Do not normalise the height (min→0, max→1) inside `pack`. B must round-trip.
- Do not use Sobel weights (1,2,1). Do not divide by 8 or 16. The test's ramp pins /32.
- Do not touch the shaders or androidkit. That is JB-9.03.

## Definition of done
- [ ] tests pass (paste counts) · [ ] mutation check: change 10→2 in the kernel and see test 2 go red; say so
- [ ] only owner-area files changed (`git status --short`) · [ ] commit "JB-9.01: …", rebased on origin/joy-creator, pushed
- [ ] ROADMAP row JB-9.01 set to 🟧 Built with the command and counts

## Questions

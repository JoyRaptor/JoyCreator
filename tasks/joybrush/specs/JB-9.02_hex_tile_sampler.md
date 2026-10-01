# JB-9.02 — Hex-tile paper sampler: the no-repeat read, CPU twin (the canonical maths)

| | |
|---|---|
| **Tier** | T1 (pure core) |
| **Status** | 🟦 Ready (paper specialist, 2026-10-01) |
| **Builder** | OpenCode free agent |
| **Depends on** | JB-9.01 (uses `SurfaceMaps.decodeSlope`) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paper/HexTile.kt`, NEW `…/core/paper/PaperTexture.kt`, NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/paper/HexTileTest.kt` |
| **Estimated size** | ~150 lines + ~200 lines of tests |

## Goal
Today's paper repeats every ~43 px and the eye sees the grid (R10 `R10_img/repeat_test.jpg`). This row defines, ONCE,
the maths that reads a paper texture so it never visibly repeats: three reads on a hexagonal lattice, each hex with
its own random offset (and, for papers without a direction, its own rotation), blended (Mikkelsen 2022, JCGT 11(3)).
**This file is the contract.** The GLSL in JB-9.03 (`joybrush/shaders/jb_paper.glsl`) is its line-for-line twin.
The CPU twin also renders paper for PNG export (JB-9.06) and is how tests pin the shader.

## Contract (verbatim)
```kotlin
package cc.joycreator.joybrush.core.paper

/** A decoded tileable texture: RGBA8, row-major, row 0 = top. */
class PaperTexture(val w: Int, val h: Int, val rgba: ByteArray) {
    /** Bilinear, wrapping, at TEXEL coordinates (texel centres at i + 0.5). Returns 4 channels 0..1 into [out]. */
    fun bilinear(tx: Double, ty: Double, out: FloatArray)
}

object HexTile {
    const val HEX_GAMMA = 3f                 // weight contrast: w^3, renormalised
    val SQRT3: Double                        // = sqrt(3.0)

    /** Integer hash → [0, 1). lowbias32 (Wellons); identical in GLSL ES 3.00 with uint maths. */
    fun hash(i: Int, j: Int, k: Int): Float

    /** The three lattice vertices around texel point (px, py) for hex size [hexTexels], and their weights (sum 1, before gamma). */
    fun lattice(px: Double, py: Double, hexTexels: Double): Lattice
    class Lattice(val vi: IntArray /*3*/, val vj: IntArray /*3*/, val w: FloatArray /*3*/)

    /**
     * Sample [tex] at TEXEL point (px, py), no visible repeat.
     * Returns, into [out]: [0] = dh/dx, [1] = dh/dy  (height per TEXEL, in the PAPER's frame, i.e. un-rotated),
     *                      [2] = height 0..1,   [3] = height² 0..1.
     * [slopeRange] decodes R,G (SurfaceMaps.decodeSlope). [rotatable] = false → no per-hex rotation (weaves, laid lines, papyrus).
     */
    fun sampleSurface(tex: PaperTexture, px: Double, py: Double, hexTexels: Double,
                      rotatable: Boolean, slopeRange: Float, out: FloatArray)

    /** Same lattice, offsets and weights, for a LOOK texture (RGB colour; A ignored). Returns r,g,b 0..1 into [out]. */
    fun sampleLook(tex: PaperTexture, px: Double, py: Double, hexTexels: Double, rotatable: Boolean, out: FloatArray)
}
```

### The maths (the GLSL twin must match each line)
```
hash(i,j,k):  x = (i * 73856093) xor (j * 19349663) xor (k * 83492791)        // 32-bit wrap, as uint
              x ^= x >>> 16; x *= 0x7feb352d; x ^= x >>> 15; x *= 0x846ca68b; x ^= x >>> 16
              return (x >>> 8) / 16777216.0                                    // 24 bits: exact in float

lattice(p, H):  q = p / H
                a = q.x − q.y / √3 ;  b = 2·q.y / √3
                i0 = floor(a), j0 = floor(b), fa = a − i0, fb = b − j0
                if (fa + fb > 1):  verts (i0+1, j0+1), (i0+1, j0), (i0, j0+1);  w = (fa+fb−1, 1−fb, 1−fa)
                else:              verts (i0, j0),     (i0+1, j0), (i0, j0+1);  w = (1−fa−fb, fa, fb)

vertex centre (texels): c = H · (i + j/2, j·√3/2)
per vertex:  offset = (hash(i,j,1), hash(i,j,2)) · (tex.w, tex.h)
             θ = rotatable ? hash(i,j,3) · 2π : 0
             t = R(θ)·(p − c) + c + offset          // R(θ) = [[cos −sin],[sin cos]]
             s = bilinear(tex, t)                    // 4 channels
             slope_tex = (decode(s.r), decode(s.g));  slope = R(−θ)·slope_tex   // back into the paper frame
weights:     w'_k = w_k^HEX_GAMMA / Σ w^HEX_GAMMA
result:      Σ w'_k · (slope_k.x, slope_k.y, s_k.b, s_k.a)        // convex: B and A stay a valid mean and 2nd moment
```

## Decisions already made
1. **Hex lattice, three reads.** Mikkelsen's practical hex-tiling. It blends slopes correctly because slopes are linear.
2. **Convex blend for all four channels** (no variance-preserving formula). That keeps A − B² a real variance, which the zoom maths in JB-9.06 needs. HEX_GAMMA = 3 keeps most of the contrast.
3. **Integer hash, not `fract(sin(…))`.** sin differs between GPUs. lowbias32 with 24-bit output is exact on both sides.
4. **Rotation turns the slopes back** (`R(−θ)`), or a rotated hex would face the wrong way and the directional deposit (R10 §5) would be wrong.
5. **Texel coordinates in, texel slopes out.** Doc px → texels (`/ texelPx`) and slopes per texel → per doc px (`/ texelPx`) are the caller's job (JB-9.03/9.06).
6. `Double` for coordinates on the CPU (far from the origin on an endless canvas).

## Tests (`HexTileTest`, commonTest; write first)
1. `hash` golden: hash(0,0,0), hash(1,0,0), hash(0,1,0), hash(−1,−1,1), hash(123456,−654321,3): compute the five values by hand from the formula in the test (a local reference implementation written independently, with `UInt`) and assert equality. Also assert every value in [0,1).
2. Weights: for 200 seeded points, weights ≥ 0 and sum to 1 within 1e-6.
3. Continuity: along a line crossing many hex edges (200 samples at 0.25-texel steps), `sampleSurface` height never jumps by more than 0.06 between neighbours on a smooth test texture (16×16 bilinear gradient wrap). No seams.
4. Vertex centre round trip: for (i, j) in −3..3, `lattice(c(i,j) + tiny)` contains (i, j) with weight > 0.99 before gamma.
5. **No repeat:** on a 32×32 random-byte texture, the correlation between `sampleSurface` heights over a 256×256 grid and the same grid shifted by exactly the texture period (32 texels) is < 0.3. With plain tiling it would be 1.0 (assert that too, as the control).
6. Rotation off: `rotatable = false` on a texture whose R channel encodes slope +r everywhere and G = 128 → every sample returns dx ≈ +r (within 2r/255), dy ≈ 0.
7. Rotation on: same texture, `rotatable = true`, sampled at hex CENTRES (points where one gamma weight > 0.98): √(dx² + dy²) is within 5% of r, and the 40 sample directions fall in at least 6 of 8 octant bins. Proves slopes are rotated, not dropped. (Between centres, blending different directions legitimately shortens the vector.)
8. `sampleLook` on a constant-colour texture returns that colour at 50 seeded points within 1/255.

**Command:** `./gradlew --no-watch-fs -p joybrush :core:jvmTest --rerun-tasks` in YOUR worktree (R43). Paste counts from the XML.

## Do not
- Do not "simplify" the lattice to a square grid. A square grid's diagonals show.
- Do not normalise the blended slope vector. Blending is linear.
- Do not use `kotlin.random`. Everything comes from `hash`.
- Do not edit `joybrush/shaders/*`. That is JB-9.03.

## Definition of done
- [ ] tests pass with counts · [ ] mutation: drop `R(−θ)` → test 7 or 6 goes red; say which
- [ ] owner-area only · [ ] commit "JB-9.02: …", rebased, pushed · [ ] ROADMAP row → 🟧 Built + command + counts

## Questions

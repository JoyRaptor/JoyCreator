# JB-2.05b — Transform maths: the quad→quad homography and resampling tiles through it

| | |
|---|---|
| **Tier** | T2 |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-2.05a (`SelectionMask`) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/select/Homography.kt`, NEW `.../select/Resample.kt`, NEW tests `.../commonTest/.../select/HomographyTest.kt`, `ResampleTest.kt` |
| **Estimated size** | ~260 lines + ~260 lines of tests |

> **Revised 2026-09-29 (before dispatch).** The first draft had its own transform box and handles.
> The owner already has a transform tool he likes in the Studio (`TransformOverlayView`: scale / tilt
> / free / corner-pin handles, spin arc, two-finger transform, loupe), and D.02 makes it shareable.
> So Joy Brush uses THAT for the handles, and this spec is only the maths it needs underneath: the
> overlay speaks in QUADS (four corners), so pixels must be moved quad → quad, which is a
> homography (it covers move, scale, rotate, skew AND perspective/corner-pin in one).

## Contract
```kotlin
package cc.joycreator.joybrush.core.select

import cc.joycreator.joybrush.core.shape.Pt

/** A 3×3 projective map, row-major m[0..8]: (x, y, 1) → (x', y', w'), result (x'/w', y'/w'). */
class Homography(val m: DoubleArray) {
    fun apply(p: Pt): Pt                     // NaN point when w' ≈ 0 (behind the horizon)
    fun then(next: Homography): Homography   // this first, then next
    fun inverse(): Homography?               // null when singular
    val isAffine: Boolean                    // m[6] == m[7] == 0 (within 1e-12)
    companion object {
        val IDENTITY: Homography
        /** The map taking src[i] → dst[i] for the four corners (TL, TR, BR, BL). Null if degenerate. */
        fun fromQuads(src: List<Pt>, dst: List<Pt>): Homography?
        fun translate(dx: Double, dy: Double): Homography
        fun rotate(radians: Double, about: Pt): Homography
        fun scale(sx: Double, sy: Double, about: Pt): Homography
    }
}

object Resample {
    enum class Filter { NEAREST, BILINEAR }
    /** Split [src] by [mask]: lifted = src × m, remaining = src − lifted, premultiplied RGBA8 tiles. */
    fun lift(src: Map<Long, ByteArray>, mask: SelectionMask): Pair<Map<Long, ByteArray>, Map<Long, ByteArray>>
    /** [src] mapped through [h] into fresh tiles. All-zero tiles are never returned. */
    fun warp(src: Map<Long, ByteArray>, h: Homography, filter: Filter): Map<Long, ByteArray>
    /** Premultiplied source-over of [top] onto [bottom], per tile. Inputs are not modified. */
    fun over(bottom: Map<Long, ByteArray>, top: Map<Long, ByteArray>): Map<Long, ByteArray>
}
```
Tiles: `Tiles.SIZE² × 4` bytes, premultiplied RGBA8, row 0 = top, keyed by `Tiles.key` — the layout
of `GlPaintEngine.readTile` / `replaceTiles`.

## Decisions
1. `fromQuads`: the standard 8-unknown linear solve (h33 = 1), Gaussian elimination with partial
   pivoting in Double. Degenerate (three corners collinear, non-finite) → null. The Studio overlay
   refuses non-convex quads already; this function must still not throw on them.
2. `warp` is INVERSE mapping: for each destination pixel centre `(x + 0.5, y + 0.5)`, map through
   `h.inverse()` to the source and sample there. Destination tiles visited = those touched by the
   bounding box of the source's non-empty tiles mapped through `h` (their four corners each); if any
   mapped corner is NaN/∞ (a perspective beyond the horizon) → refuse with an empty result (the
   overlay never produces that from a convex quad).
3. BILINEAR on premultiplied channels, outside the source = transparent, round to nearest.
   **Minification:** where one destination pixel covers more than 2 source pixels (estimate from the
   Jacobian at the tile's centre), average a 3 × 3 grid of bilinear samples across the pixel instead
   of one — so shrinking to a third does not sparkle. NEAREST: the source pixel whose square holds
   the point (for pixel art; no minification averaging).
4. **Exactness** (so "move it and put it back" is lossless): an integer translation copies bytes
   exactly with either filter; a multiple-of-90° rotation about a point that maps pixel centres to
   pixel centres, with NEAREST, is exact.
5. `lift`: `lifted = round(v × m / 255)`, `remaining = v − lifted` per channel, so
   `over(remaining, lifted)` = the original ±1.

## Tests
1. `fromQuads` of a unit square onto itself = IDENTITY (1e-9); onto a translated / rotated / scaled
   square = the matching factory; onto a trapezoid maps all four corners within 1e-6; collinear → null.
2. `inverse` composes to IDENTITY within 1e-9; `then` order matters (checked on a point).
3. warp: identity = byte-identical; translate (300, −17) = byte-identical shifted across tile seams;
   rotate 90° NEAREST exact on a test pattern; 2× BILINEAR of a 2-px checker gives the expected
   in-between values; no all-zero tiles in any output.
4. Minification: a 1-px black/white checker shrunk to 1/3 comes out uniform grey (every pixel within
   ±24 of 128) — without the 3 × 3 averaging it would alias to stripes.
5. Perspective: a square warped to a trapezoid (corner-pin): a pixel at the source's centre lands at
   the trapezoid's diagonal intersection (the projective centre), within 1 px.
6. `lift` then `over(remaining, lifted)` = original ±1 for a soft mask.
7. Rotate 30° then −30° BILINEAR: mean absolute error < 3 per channel inside the shape.

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures.

## Do not
Touch no existing file. No handles, no touch code, no GL (the Studio overlay and JB-2.05 own those).

## Definition of done
Tests pass (paste) · commit `JB-2.05b: homography and resample` · ROADMAP row → 🟧 Built.

## Questions

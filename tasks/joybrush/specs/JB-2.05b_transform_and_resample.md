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

*Asked by the builder. Implemented as written in every case; the three below are the ones where the
written contract and the arithmetic disagree, and none of them is resolved by guessing.*

**Q1 — Decision 5's `±1` is not achievable with a source-over, and it matters.** Source-over is
`out = B + T(1 - B/255)`, which returns `v` only when `B = 0`; for any nonzero remainder the
topped-up part is short by `L·R/255`. Worked, not estimated: at `v = 255` and coverage 128 the
split is `lifted = (255·128+127)/255 = 128` and `remaining = 127`, and the composite is
`127 + (128·128+127)/255 = 127 + 64 = 191` — **64 short of 255**, and the alpha channel
(`128 → 191`) is as wrong as the colour. It is exact for a 0/255 mask, so a marquee is fine and a
soft lasso is not.
The invariant that *does* hold with no rounding anywhere is `lifted + remaining == v` per channel,
which is what `ResampleTest.liftSplitsTheBytesExactly` asserts, and the composite for coverage 128
is asserted as exactly 191 so the gap is a red test rather than a comment.
**Ruling needed:** is the intended composite a plain clamped ADD (`lifted + remaining`, which is
`Blend.ADD` and would be exact) rather than source-over? If it really is source-over, Decision 5
should say "the original where the mask is 0 or 255, and darker where it is soft", because a
caller reading `±1` will ship a soft selection that visibly fades.

**Q2 — `warp` refuses three ways and all three are an empty map.** Decision 2 names two (a corner
at infinity, and the derived destination being unallocatable). I also refuse a **singular matrix**,
because there is no inverse to sample through and the alternative is a NaN byte in a tile. And for
"unallocatable" I picked the destination span cap of `MAX_SELECT_SPAN` (16384 px, the same number
`SelectionMask` uses, and a 64 × 64-tile budget) rather than inventing a second budget.
The consequence to rule on: **an empty map is also the answer for an empty source**, so a caller
cannot tell "refused" from "nothing there". If a caller outside this spec has to show the person
"that is too big to move", it needs a separate signal — a thrown `RegionException`, or a nullable
return, or a boolean out-parameter. The contract as written gives it no way to.

**Q3 — the `w ≈ 0` threshold and the minification threshold are mine, not yours.** `Homography.apply`
treats `|w| < 1e-9` as the horizon (1e-9 of a pixel is a hundred million times below anything a
person can drag), and `Resample` uses the same 1e-9 plus `σmax > 2` for the 3 × 3 grid. Both are
one-line changes and both are guesses at intent. In particular `σmax > 2` is the Nyquist limit: a
factor of 2.0 exactly does **NOT** take the nine-sample path — the test is `span > MINIFY_SIGMA`
with `MINIFY_SIGMA = 2.0`, which is strict, so exactly 2.0 is single-sampled and the grid starts
strictly above it. (Corrected 2026-09-29: the earlier wording here claimed the opposite.)

**Also worth a ruling, lower stakes:** Decision 3's minification is judged from the Jacobian at the
**tile's** centre (one 2 × 2 determinant per 256² tile rather than per pixel). For a corner-pin the
factor genuinely changes across the picture, so a large shrink on one side of the quad and a
magnification on the other will use one decision for both. Per-pixel is affordable if a case for it
appears.

**Q4 — `over` was implementing a DESTINATION-over, and one spec sentence was wrong with it.**
Found 2026-09-29 by an adversarial review of the built code; the code, not the spec, was at fault,
and it is now fixed. `Resample.over` computed `out = bottom + top (1 - bottom_alpha/255)` — a
per-channel factor read from the BACKDROP, with the operands swapped. Source-over is
`out = top + bottom (1 - top_alpha/255)`: one factor, the source's alpha, the source's operands,
which is what `glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)` at `GlPaintEngine.kt:279` and `:367`
has always meant. The KDoc already claimed those semantics, so the code contradicted its own
documentation. Two consequences a person could see: a half-transparent premultiplied black
(0,0,0,128) over opaque white came out **255 white** instead of **127 grey**, and an opaque top
could be painted *under* an opaque backdrop instead of over it. Fixed, with the identities pinned
in `ResampleTest.overHasTheThreeIdentitiesSourceOverMustHave` and the arithmetic cross-checked
byte for byte against a second model of the same blend written in normalised Double
(`ResampleTest.overAgreesWithASecondModelOfTheSameBlend`).

**Q1 IS UNCHANGED AND STILL OPEN.** Two of the four assertions in
`ResampleTest.overCompositesSourceOverAndLeavesItsInputsAlone` had to change, and neither of them
touched Q1: opaque blue over half-transparent red now answers red `0` (was `128`), and opaque black
over an opaque mid grey now answers black `0,0,0` (was `10,20,30`). **Q1's own number is not
affected** — the 128/127 lift split still composites to exactly `191` under the corrected formula,
because the two forms coincide on a symmetric split. Only the derivation changes:
`128 + round(127 × 127 / 255) = 128 + 63 = 191`, not `127 + round(128 × 128 / 255) = 127 + 64 = 191`.
The gap is the same 64 either way, `±1` is still unachievable for a source-over, and the red gap is
deliberately left exactly as it was. Q1 is not resolved here and no test was adjusted to make it
green.

**Q5 — `Homography.then`'s KDoc has the order backwards, and the file is outside the fix's owner
area.** `Homography.kt:69-71` says `translate(10, 0).then(scale(2, 2))` "scales first and moves
second". The implementation is `Homography(mul(next.m, m))`, i.e. `q * p`, i.e. `p` first — so it
is translate first, and `HomographyTest` already asserts the numbers that prove it: (1, 1) goes to
`(12, 2)` in that order and `(22, 2)` in the other, and the class KDoc at `HomographyTest.kt:31-33`
says so in as many words. The one-line comment fix is not made here because `Homography.kt` is not
in this fix's owner area; please correct the sentence to "moves first, then scales".

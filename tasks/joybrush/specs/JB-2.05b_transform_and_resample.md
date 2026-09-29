# JB-2.05b — Transform maths: the affine, the transform box's handles, and resampling tiles

| | |
|---|---|
| **Tier** | T2 |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-2.05a (`SelectionMask`), JB-2.02 (`ViewTransform`) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/select/Affine.kt`, NEW `.../select/TransformBox.kt`, NEW `.../select/Resample.kt`, NEW tests `.../commonTest/.../select/AffineTest.kt`, `TransformBoxTest.kt`, `ResampleTest.kt` |
| **Estimated size** | ~380 lines + ~320 lines of tests |

## Goal
The owner's selection rules (blueprint §3.5): the transform box appears at once; dragging its
handles scales/rotates; two fingers INSIDE the box transform the content; tap outside commits.
This spec is the maths under that: no touch handling, no GL. The Lead's JB-2.05 wires it up.

## Contract
```kotlin
package cc.joycreator.joybrush.core.select

import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.view.ViewTransform

/** x' = a·x + c·y + tx ; y' = b·x + d·y + ty  (document px). */
data class Affine(val a: Double, val b: Double, val c: Double, val d: Double, val tx: Double, val ty: Double) {
    fun apply(p: Pt): Pt
    fun then(next: Affine): Affine          // this first, then next
    fun inverse(): Affine?                  // null when |det| < 1e-9
    companion object {
        val IDENTITY: Affine
        fun translate(dx: Double, dy: Double): Affine
        fun scale(sx: Double, sy: Double, about: Pt): Affine
        fun rotate(radians: Double, about: Pt): Affine
    }
}

enum class Handle { NONE, MOVE, CORNER_TL, CORNER_TR, CORNER_BR, CORNER_BL, EDGE_T, EDGE_R, EDGE_B, EDGE_L, ROTATE }

/** The box around the lifted pixels: their untransformed [bounds] plus the transform so far. */
data class TransformBox(val bounds: RectPx, val affine: Affine = Affine.IDENTITY) {
    /** Corners TL, TR, BR, BL in DOCUMENT px (after the affine). */
    fun corners(): List<Pt>
    /** Which handle is under a SCREEN point. Corners and edges win over MOVE; outside the box → NONE. */
    fun hitTest(screenX: Float, screenY: Float, view: ViewTransform): Handle
    /** The box after dragging [handle] from [fromDoc] to [toDoc] (document px), starting from THIS box. */
    fun drag(handle: Handle, fromDoc: Pt, toDoc: Pt, freeform: Boolean = false): TransformBox
    /** Two fingers inside the box: the same maths as ViewTransform.applyPinch, applied to the content. */
    fun pinch(oldC: Pt, newC: Pt, scale: Double, dRotation: Double): TransformBox
}

object Resample {
    enum class Filter { NEAREST, BILINEAR }
    /** Split [src] by [mask]: lifted = src × m, remaining = src × (1 − m), per channel, premultiplied RGBA8. */
    fun lift(src: Map<Long, ByteArray>, mask: SelectionMask): Pair<Map<Long, ByteArray>, Map<Long, ByteArray>>
    /** [src] moved by [affine] into fresh tiles. All-zero tiles are never returned. */
    fun transform(src: Map<Long, ByteArray>, affine: Affine, filter: Filter): Map<Long, ByteArray>
    /** Premultiplied source-over of [top] onto [bottom], per tile. Neither input is modified. */
    fun over(bottom: Map<Long, ByteArray>, top: Map<Long, ByteArray>): Map<Long, ByteArray>
}
```
Tiles are `Tiles.SIZE² × 4` bytes, premultiplied RGBA8, row 0 = top — the same layout
`GlPaintEngine.readTile` / `writeTile` use — keyed by `Tiles.key(tx, ty)`.

## Decisions
1. **Handle sizes are in SCREEN px** (so they are finger-sized at any zoom): a handle is hit within
   24 screen px of its point. The rotate handle sits 36 screen px beyond the top-edge midpoint,
   perpendicular to the (transformed) top edge. Priority when several are within reach: ROTATE,
   corners, edges, then MOVE (strictly inside the box). Nothing within reach and outside → NONE
   (the caller commits on a tap there — owner's "tap outside commits").
2. **Corner drag:** scales about the OPPOSITE corner in the box's own (rotated) frame. Default is
   UNIFORM: with anchor A (opposite corner), old corner C and new corner C', the factor is
   `((C' − A)·(C − A)) / |C − A|²` (the projection onto the diagonal).
   `freeform = true` scales x and y independently. **Edge drag:** one axis, about the opposite edge.
   Dragging past the opposite side flips (negative scale is allowed — it is a mirror).
3. **Rotate:** about the box centre by the angle swept around it. Snap: within 4° of a multiple of
   45°, the rotation is set exactly to that multiple.
4. **Move:** translate by `toDoc − fromDoc`.
5. **Resample `transform`:** inverse mapping — for each destination pixel centre `(x+0.5, y+0.5)`,
   map through `affine.inverse()` to source and sample. BILINEAR: weights on the four nearest pixel
   centres, premultiplied channels, outside = transparent; round to nearest. NEAREST: the pixel whose
   square contains the point. Only destination tiles touched by the transformed bounds of the
   source's non-empty tiles are visited. Singular affine → empty result.
6. **Exactness rules** (these are what make "move it and put it back" lossless):
   - A pure integer translation (a = d = 1, b = c = 0, tx and ty integers) copies bytes exactly with
     EITHER filter.
   - A rotation by a multiple of 90° about a point on the pixel grid (integer or half-integer
     coordinates as appropriate so pixel centres map to pixel centres) with NEAREST is exact.
7. `lift`: `lifted = round(v × m / 255)`, `remaining = v − lifted` per channel, so
   `over(remaining, lifted)` gives back the original within ±1 per channel.

## Tests
1. Affine: `then` order (translate then rotate ≠ rotate then translate, checked on a point);
   `inverse` of a random non-singular affine composes to IDENTITY within 1e-9; singular → null.
2. Box hit-test at zoom 1 and zoom 4: the same SCREEN offset from a corner hits it at both zooms;
   a point 30 screen px outside → NONE; centre → MOVE; the rotate handle above a 30°-rotated box.
3. Corner drag uniform: box 100×50, drag BR by (+100, +10) → factor (200·100 + 60·50) / 12500 = 1.84
   → 184 × 92, TL unchanged. Freeform → 200 × 60.
4. Edge drag R past the left edge → negative width (mirrored), TL/BL corners unchanged.
5. Rotate drag of 43° → exactly 45° (snap); 30° → 30°.
6. Resample: identity = byte-identical; translate (300, −17) = byte-identical shifted, across tile
   seams; rotate 90° about (128, 128) NEAREST = exact rotation of a test pattern; 2× BILINEAR of a
   2-pixel checker → the expected in-between values; no all-zero tiles in any output.
7. `lift` then `over(remaining, lifted)` = original ±1 for a soft (feathered) mask.
8. Rotate 30° then −30° BILINEAR: mean absolute error < 3 per channel inside the shape (it blurs a
   little, and this pins how much).

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures.

## Do not
Touch no existing file (ViewTransform is read, not changed). No GL, no Android.

## Definition of done
Tests pass (paste) · commit `JB-2.05b: transform and resample` · ROADMAP row → 🟧 Built.

## Questions

# JB-2.07a — Lasso fill maths: draw a loop, the inside becomes paint (fill, erase, or paint behind)

| | |
|---|---|
| **Tier** | T2 |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-2.05a (`SelectionMask.polygon`), JB-2.06a (`FloodFill` mask shape) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/fill/MaskPaint.kt`, NEW `.../commonTest/.../fill/MaskPaintTest.kt` |
| **Estimated size** | ~120 lines + ~180 lines of tests |

## Goal
The owner: "fill pens (the type with lasso-type behaviour) are very handy." Draw a loop with the pen
and the inside fills with the colour — the fastest way to block in flat colour, and (in BEHIND mode)
to colour line art without covering the lines. The same maths applies a flood-fill result (JB-2.06b),
so both fill tools paint through ONE function.

## Contract
```kotlin
package cc.joycreator.joybrush.core.fill

import cc.joycreator.joybrush.core.select.SelectionMask

enum class MaskPaintMode { FILL, ERASE, BEHIND }

object MaskPaint {
    /**
     * Paints [argb] (straight sRGB; its alpha is ignored) at [opacity] through [mask] onto a layer's
     * tiles. Returns ONLY the tiles that changed, as their new contents — null means "the tile is now
     * empty, delete it" — ready for GlPaintEngine.replaceTiles.
     * @param layer the layer's current tiles (premultiplied RGBA8, Tiles.SIZE², keyed by Tiles.key).
     */
    fun apply(layer: Map<Long, ByteArray>, mask: SelectionMask, argb: Int, opacity: Float,
              mode: MaskPaintMode): Map<Long, ByteArray?>
}
```

## Decisions
1. Per pixel, `a = coverage/255 × opacity` (opacity clamped 0..1, NaN → 1). Premultiplied source
   `S = (r·a, g·a, b·a, a)`. Destination `D`.
   - **FILL** (source-over): `D' = S + D × (1 − a)`.
   - **ERASE** (destination-out): `D' = D × (1 − a)`.
   - **BEHIND** (destination-over): `D' = D + S × (1 − D.a)` — paint goes only where the layer is
     transparent or partly so; lines already on the layer stay on top. This is what makes lasso
     fill useful on the SAME layer as the line art.
   Round to nearest; clamp 0..255; keep premultiplied invariants (colour ≤ alpha).
2. Only tiles where the mask has coverage are visited. A tile that becomes all-zero is returned as
   null (delete). A tile that did not change (e.g. BEHIND over fully opaque paint, ERASE on empty) is
   NOT returned — so undo records nothing for it.
3. A new tile (mask over empty canvas) is created only for FILL and BEHIND.
4. The lasso itself is `SelectionMask.polygon(points)` — non-zero winding, 4 × 4 anti-aliased edge
   (JB-2.05a). No gap closing, no tolerance: a lasso fills exactly what you drew round.

## Tests
1. FILL a 10 × 10 square mask in opaque red on an empty layer → 100 pixels (255,0,0,255), one new tile.
2. FILL at opacity 0.5 over opaque blue → (128,0,128,255) ±1.
3. ERASE the same square out of an opaque layer → those pixels 0; ERASE a whole tile's area →
   that tile returned as null.
4. BEHIND over a layer with a black 1-px line through the square → the line's pixels unchanged,
   the rest red; soft line-edge pixels (alpha 128) get red only in their transparent share.
5. Nothing changes → empty result (ERASE on empty; BEHIND under fully opaque).
6. A lasso crossing a tile seam at x = 256 paints both tiles with no seam artefact.
7. Soft mask edge (coverage 128) FILL on empty → alpha 128, colour premultiplied (128,0,0,128).

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures.

## Do not
Touch no existing file. No Android, no GL.

## Definition of done
Tests pass (paste) · commit `JB-2.07a: mask paint (lasso fill)` · ROADMAP row → 🟧 Built.

## Questions

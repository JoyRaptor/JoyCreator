# JB-2.13a — RegionRenderer: flatten any rectangle of the document to pixels (CPU)

| | |
|---|---|
| **Tier** | T2 |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-0.02 (Built) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/render/RegionRenderer.kt`, NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/render/Blend.kt`, NEW `.../commonTest/.../render/RegionRendererTest.kt` |
| **Estimated size** | ~250 lines + ~200 lines of tests |

## Goal
Every export (PNG, OpenRaster, GIF, video, sprite sheet, thumbnails) needs "the picture inside this
rectangle, at this frame, with or without paper". One tested CPU function does it, so every export
agrees with every other.

## Contract
```kotlin
package cc.joycreator.joybrush.core.render

/** Supplies a tile's premultiplied RGBA8 pixels (262,144 bytes, row 0 = top) or null if empty. */
fun interface TileSource { fun tile(layerId: String, celId: String, tx: Int, ty: Int): ByteArray? }

object RegionRenderer {
    /**
     * Composites the visible layers of [doc] (bottom → top, each through its cel for [frameId] via
     * DocOps.celFor) inside [rect], using each layer's opacity and blend mode.
     * @param paper if non-null (#RRGGBB) the result is composited over that opaque colour first.
     * @return STRAIGHT (un-premultiplied) RGBA8, rect.w × rect.h × 4 bytes, row 0 = top.
     */
    fun render(doc: JbDocument, tiles: TileSource, rect: RectPx, frameId: String?, paper: String?): ByteArray

    /** Same, but premultiplied float RGBA (for further compositing / tests). */
    fun renderPremultiplied(doc: JbDocument, tiles: TileSource, rect: RectPx, frameId: String?, paper: String?): FloatArray
}
```
`Blend.kt`: one function per `BlendMode` on premultiplied colour `(s, d) → out`, the standard
separable formulas (W3C Compositing Level 1), all with source-over alpha `a = sa + da(1 − sa)`:
NORMAL, MULTIPLY, SCREEN, OVERLAY, ADD (clamped), DARKEN, LIGHTEN. `ERASE_BELOW` = destination-out
(`out = d × (1 − sa)`).

## Decisions
1. Work in float, 0..1. Convert bytes /255; output bytes = round(clamp(v) × 255).
2. Layer opacity multiplies the layer's premultiplied pixel before blending.
3. Invisible layers are skipped. Missing tiles are transparent.
4. Un-premultiply at the end: straight = premult / alpha (alpha 0 → rgb 0).
5. Rects may cross tile boundaries and may have negative coordinates.

## Tests
1. One NORMAL layer, one opaque red tile, rect inside → all red, alpha 255.
2. Rect straddling 4 tiles incl. negative coordinates → correct pixels at each tile edge.
3. Paper `#FFFFFF` under a 50%-alpha black layer → mid grey, alpha 255; paper null → black at alpha 128.
4. Each blend mode against a known pair of colours matches the W3C formula (compute expected by hand).
5. Layer opacity 0.5 halves alpha. Invisible layer ignored.
6. Animated layer: frame 1 vs frame 2 pick different cels.

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures.

## Definition of done
Tests pass (paste) · commit `JB-2.13a: region renderer` · ROADMAP row → 🟧 Built.

## Questions

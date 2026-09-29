# JB-2.06b — Fill on the canvas: tap fill, and the fill pen drawing on paint layers (on the phone now)

| | |
|---|---|
| **Tier** | T2 + T3 phone check |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-2.06a (`FloodFill`), JB-2.07a (`MaskPaint`), JB-2.05a (`SelectionMask`), JB-1.08a (`FillPen`, fill brush), JB-2.13a (`RegionRenderer`), `GlPaintEngine.replaceTiles` (Lead, done) |
| **Owner area** | NEW `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/tools/FillTools.kt`; EDIT `.../androidkit/JbCanvasView.kt` (a `tool` property; routing a fill-engine brush's strokes; nothing else); EDIT `joybrush-android/.../JoyBrushActivity.kt` (one "Tool" pill) |
| **Estimated size** | ~240 lines |

> **Revised 2026-09-29 (owner):** lasso fill is not a tool, it is a PEN — the fill brush of JB-1.08a,
> picked from the brush pill like Ink or Pencil. This spec draws it on PAINT layers; on ink layers the
> same recording is drawn by the Lead's JB-5.01, which is what makes re-brushing between the fill pen
> and a pencil work both ways.

## Contract
```kotlin
package cc.joycreator.joybrush.androidkit.tools
enum class CanvasTool { BRUSH, FILL }
// JbCanvasView gains:  var tool: CanvasTool = CanvasTool.BRUSH   (UI thread)
//                      var fillOptions: FillOptions = FillOptions(gapClosePx = 2)
```

## Decisions
1. **Who draws:** exactly who draws with the brush today — the pen always; fingers only before a pen
   is seen. Two-finger gestures unchanged.
2. **Tap FILL (tool = FILL):** a pen-down/up shorter than 8 screen px at doc point P:
   - Region = the visible document area (doc px from the view transform) grown by 64 px, refused
     with a toast "Zoom in to fill this area" if over `RegionRenderer.MAX_REGION_PX`.
   - Reference = `RegionRenderer.render` of all visible layers, no paper, from tile bytes read on
     the GL thread (`readTile`), then handed to a background thread — never read GL off the GL thread.
   - `FloodFill.fill(…, fillOptions)` → `SelectionMask.fromMask` →
     `MaskPaint.apply(activeLayerTiles, mask, colour, 1f, FILL)` → on the GL thread
     `engine.replaceTiles(layer, result)` = one undo step → `reportHistory()`. A second tap while a
     fill is still working is ignored.
3. **The FILL PEN (tool = BRUSH, current brush has `engine == "fill"`):** the stroke is recorded like
   any stroke (same `PenSample`s, same smoother). No dabs are placed. While the pen is down the view
   shows the live shape — `FillPen.outline` of the samples so far, closed — as a filled `Path` in the
   brush colour at the brush opacity × 0.6, with a 1 dp outline, on one transparent overlay View above
   the GL view (create it; JB-2.03a will reuse it). On pen-up: `SelectionMask.polygon(outline)` →
   `MaskPaint.apply` with mode FILL (`blend normal`), BEHIND (`blend behind`) or ERASE (`blend erase`,
   or the pen's eraser end) → `replaceTiles` → the overlay clears in the same frame the tiles land.
   Outline enclosing < 4 screen px² → nothing happens (a tap with the fill pen is not a fill).
4. **Tool pill** (JoyBrushActivity) shows "Brush" / "Fill" and toggles on tap. The fill pen is
   chosen from the existing brush pill (it appears once `brushes/index.txt` lists it — JB-1.08a).
5. Colour = the current colour (brush file's, or the colour pill's once JB-2.03a lands).
6. Never while a stroke is in progress (`strokeInProgress`) — assert it.

## Verification
- `./gradlew -p joybrush :androidkit:compileKotlin :androidkit:test` green (cloud: add
  `-Pjoybrush.androidJar`); watcher compiles `joybrush-android` green.
- **Owner check on the Note 9:** draw a circle with a small gap in Ink; Tool → Fill; tap inside →
  fills, no leak, no pale ring; one undo removes it. Tool → Brush, brush pill → Fill pen: draw a loop
  → a solid shape appears as you draw and stays when you lift. Flip the pen to its eraser end and loop
  → the inside is erased.

## Do not
Change no stamp-brush behaviour. No new engine code (use `replaceTiles`). No GL reads off the GL thread.

## Definition of done
Builds green (paste) · owner check noted · commit `JB-2.06b: tap fill and fill pen on paint layers` ·
ROADMAP row → 🟧 Built.

## Questions

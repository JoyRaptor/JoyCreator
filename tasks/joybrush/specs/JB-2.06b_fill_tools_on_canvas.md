# JB-2.06b — Fill tools on the canvas: tap fill and lasso fill (with erase and behind), on the phone now

| | |
|---|---|
| **Tier** | T2 + T3 phone check |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-2.06a (`FloodFill`), JB-2.07a (`MaskPaint`), JB-2.05a (`SelectionMask`), JB-2.13a (`RegionRenderer`), `GlPaintEngine.replaceTiles` (Lead, done) |
| **Owner area** | NEW `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/tools/FillTools.kt`; EDIT `.../androidkit/JbCanvasView.kt` (a `tool` property + routing of pen/finger-draw events to the fill tools; nothing else); EDIT `joybrush-android/.../JoyBrushActivity.kt` (one "Tool" pill) |
| **Estimated size** | ~260 lines |

## Goal
Get "easy fills" (owner) into his hand on the Note 9 now, before the full screen chrome (JB-2.01)
exists: a temporary **Tool** pill cycles Brush → Fill → Lasso fill → Lasso erase. The chrome will
later re-host the same tools; nothing here is throw-away except the pill.

## Contract
```kotlin
package cc.joycreator.joybrush.androidkit.tools
enum class CanvasTool { BRUSH, FILL, LASSO_FILL, LASSO_ERASE }
// JbCanvasView gains:  var tool: CanvasTool = CanvasTool.BRUSH   (UI thread)
//                      var fillOptions: FillOptions = FillOptions(gapClosePx = 2)
//                      var lassoBehind: Boolean = false            (LASSO_FILL paints BEHIND)
```

## Decisions
1. **Who draws with the tool:** exactly who draws with the brush today — the pen always; fingers only
   before a pen is seen (owner's ruling). The pen's eraser end keeps erasing with the brush whatever
   the tool is. Two-finger gestures are unchanged.
2. **FILL (tap):** on pen-up of a stroke shorter than 8 screen px (a tap) at doc point P:
   - Region = the part of the document visible on screen (doc px, from the view transform), expanded
     by 64 px, clamped to `RegionRenderer.MAX_REGION_PX` (refuse with a toast "Zoom in to fill this
     area" if larger).
   - Reference image = `RegionRenderer.render` of ALL visible layers, no paper, through a `TileSource`
     that reads the engine's tiles (GL thread: `readTile` each needed tile first, then hand the bytes
     to a background thread — never read GL from the background).
   - `FloodFill.fill(…, seed = P − region origin, fillOptions)` → `SelectionMask.fromMask` →
     `MaskPaint.apply(activeLayerTiles, mask, brushColour, 1f, FILL)` on the background thread →
     back on the GL thread `engine.replaceTiles(layer, result)` → one undo step → `reportHistory()`.
   - While it works (> 150 ms), show nothing new; a second tap during a fill is ignored.
3. **LASSO_FILL / LASSO_ERASE:** while the pen is down, collect its points (document px, raw, not
   smoothed — a lasso should go where the pen went) and draw the outline live with the brush colour
   at 60 % over a 15 % fill (a simple overlay `Path` on a transparent View above the GL view is fine).
   On pen-up: if fewer than 3 points or the loop encloses less than 4 screen px², do nothing.
   Else `SelectionMask.polygon` → `MaskPaint.apply(… , mode = FILL / BEHIND (if lassoBehind) / ERASE)`
   → `replaceTiles` as above. The outline disappears when the paint appears (same frame).
4. The **Tool pill** (JoyBrushActivity) shows the current tool's name and cycles on tap. Long-press
   on it while LASSO_FILL toggles `lassoBehind` and the label reads "Lasso fill (behind)".
5. Colour = the brush's current colour (the colour pill comes with JB-2.03a).
6. A fill or lasso never happens while a stroke is in progress (`strokeInProgress`) — they are
   separate touches by construction; assert it.

## Verification
- `./gradlew -p joybrush :androidkit:compileKotlin :androidkit:test` green (the cloud can run it with
  `-Pjoybrush.androidJar`); the watcher compiles `joybrush-android` green.
- **Owner check on the Note 9 (T3):** draw a rough circle with a small gap with the pen; Tool → Fill;
  tap inside → it fills, no leak, no pale ring against the line; undo removes it in one press.
  Tool → Lasso fill: draw a loop → it fills; long-press the pill for "behind", lasso over your
  lines → colour goes under the lines. Tool → Lasso erase: loop → the inside is erased.

## Do not
Change no brush behaviour. No new engine code (use `replaceTiles`). Do not read GL off the GL thread.

## Definition of done
Builds green (paste) · owner check noted · commit `JB-2.06b: fill tools on the canvas` · ROADMAP row → 🟧 Built.

## Questions

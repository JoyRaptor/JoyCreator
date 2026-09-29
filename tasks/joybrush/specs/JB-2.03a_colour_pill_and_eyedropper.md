# JB-2.03a — Colour: the Studio's colour picker behind a colour pill, and the eyedropper

| | |
|---|---|
| **Tier** | T2 + T3 phone check |
| **Status** | see ROADMAP.md |
| **Depends on** | D.02 (`ColorPickerDialog` in `:studiokit`), JB-2.13a (`RegionRenderer`) |
| **Owner area** | EDIT `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt` (`colorArgb`, the eyedropper gesture, `onColorPicked`); NEW `.../androidkit/tools/Eyedropper.kt`; EDIT `joybrush-android/.../JoyBrushActivity.kt` (a colour swatch pill) |
| **Estimated size** | ~200 lines |

## Goal
Painting needs a colour choice. The owner likes the Studio's colour drawer (hue ring, S/B triangle,
hex, swatches, app-wide RECENTS), so Joy Brush uses THAT one — the menu way. And the quick way,
from the blueprint's gesture rules: **long-press on the canvas = eyedropper**, and **S Pen button
tap = eyedropper**. (The "drag off the swatch" picker is JB-2.03, with the real chrome.)

## Contract
```kotlin
// JbCanvasView gains (UI thread):
var colorArgb: Int? = null            // null = the brush file's own colour (b.argb); set = override
var onColorPicked: ((Int) -> Unit)? = null   // eyedropper result, after colorArgb is already set
```
Every place that passes `b.argb` to `engine.beginStroke` (and the fill tools of JB-2.06b, if landed)
uses `colorArgb ?: b.argb`.

## Decisions
1. **Colour pill** (JoyBrushActivity): a round swatch showing the current colour (`colorArgb ?:
   brush colour`). Tap → `ColorPickerDialog.show(ctx, "Colour", current, allowNone = false, onLive,
   onPicked)`; `onLive` sets `canvas.colorArgb` so the next stroke uses it even before "Set". The
   dialog is a Material `BottomSheetDialog`: JoyBrushActivity is a plain `Activity`, so pass a
   `ContextThemeWrapper(this, <the app's Material theme the Studio uses>)` — find the theme the
   Studio's activity declares in the manifest and use the same one. Say which in the commit.
2. **Long-press eyedropper (pen, or finger while fingers draw):** the pen goes down and stays within
   6 screen px of where it went down for 450 ms → this is an eyedropper, not a stroke: cancel the
   stroke (`cancelStroke()` — nothing is committed, the dot vanishes), then sample. While still held,
   moving the pen resamples live under it; lifting sets the colour. A press that moved > 6 px before
   450 ms is a stroke as always. (This is NOT hold-to-shape: that is draw-THEN-hold, JB-2.11. The two
   are told apart by whether the pen moved first.)
3. **S Pen button tap:** a pen touch with `BUTTON_STYLUS_PRIMARY` held that lifts within 250 ms having
   moved < 6 screen px → eyedropper at that point; no stroke. (Never bind the button while hovering —
   Samsung's Air Command owns that.)
4. **What is sampled:** the colour you SEE — all visible layers composited over the paper
   (`RegionRenderer.render(doc, tiles, RectPx(x, y, 1, 1), frameId = null, paper = paper hex)`), the
   1 × 1 pixel under the pen. Tiles are read on the GL thread (`readTile` of the one tile), rendered
   there (1 pixel is cheap). Result alpha is forced to 255.
5. **Feedback while held:** a ring (outer 44 dp, 6 dp thick) around the pen tip, top half the NEW
   colour, bottom half the current one — the convention every painter knows. Drawn on the same
   transparent overlay View JB-2.06b adds (create it here if 2.06b has not landed; one overlay only).
6. The picked colour also goes into the picker's app-wide recents, if `ColorPickerDialog` exposes a
   way to add one (read it; if not, do NOT change the dialog — note it in Questions).

## Verification
- `./gradlew -p joybrush :androidkit:compileKotlin :androidkit:test` green; watcher compiles
  `joybrush-android` green.
- **Owner check on the Note 9:** tap the swatch → the Studio's colour drawer opens; pick orange →
  strokes are orange while the drawer is still open. Long-press on a blue stroke → ring shows blue;
  lift → next stroke is blue. S Pen button + quick tap on the paper → white.

## Do not
Do not modify `ColorPickerDialog` (it is shared with the Studio). Do not change brush files.

## Definition of done
Builds green (paste) · owner check noted · commit `JB-2.03a: colour pill and eyedropper` ·
ROADMAP row → 🟧 Built.

## Questions

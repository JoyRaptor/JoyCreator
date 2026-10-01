# JB-9.07 — The Paper swatch under the layers, and its sheet

| | |
|---|---|
| **Tier** | T2-V + T3 phone check |
| **Status** | 🟦 Ready (paper specialist, 2026-10-01) |
| **Builder** | Joy Brush Lead (owns all Joy Brush UI). If the Lead is on cooldown, Codex, under the hot-file rule of JB-9.03 |
| **Depends on** | JB-9.04, JB-9.05, JB-9.06 (`setPaper`); JB-9.10 for more than one paper to choose from (build with what exists) |
| **Owner area** | EDIT `joybrush-android/.../chrome/LayerColumnView.kt` (the swatch cell); NEW `joybrush-android/.../chrome/PaperSheetView.kt`; EDIT `JoyBrushActivity.kt` (open the sheet; undo; save into the document); NEW `joybrush/core/.../paper/PaperPreviews.kt` if a pure helper is needed |
| **Estimated size** | ~350 lines |

## Goal
Owner P7, verbatim intent: "a little swatch at the bottom of the layer menu … you can see it even though it's pretty out of the
way, and it shows up physically being lower than all the layers, which helps the user have a good mental map." (Infinite Painter's
placement, not Concepts' gear.) Tapping it opens the Paper sheet; the reference layout is Concepts' Canvas screen (round previews
in rows, owner screenshot 2026-10-01) with Infinite Painter's sliders (Color · Texture · Depth · Opacity · Scale).

## Contract
- **Swatch cell:** the LAST cell of `LayerColumnView`, below the bottom layer, the same width as a layer cell and about half its height,
  showing a live crop of the paper (look + relief at the current settings). It is not draggable, cannot be selected as a paint
  target, and has no ring. Hover label (`ChromeKit.label`): "Paper — tap to change". Tap → the sheet.
- **Sheet** (bottom sheet, leaves the canvas visible and drawable like the Tune sheet):
  1. **Background** row: round previews, one per catalogue look, plus **Colour** (opens the shared colour picker, R16; sets
     `lookId = null, color`), plus **None / transparent** (shows the checkerboard and turns Include-in-export off).
  2. **Surface** row: round previews of each catalogue surface lit from the upper left, plus **Smooth** (`textureId = null`).
     Choosing a background also selects its `defaultSurface`. Choosing a surface afterwards overrides that.
  3. **Tint** colour well (shared picker; long-press to clear back to the look's own colours).
  4. Sliders with value read-outs: **Show** (0–100), **Bite** (0–100), **Scale** (25–400%).
  5. Toggles: **Light** (relief lighting), **Include in export**.
- Every control has a hover label. Every change applies live (`canvas.paper = PaperState.resolve(...)`).
  **One undo per sheet visit** (open → changes → close collapses to ONE undo step; memory: one press, one step), and that
  step never touches a tile.
- Preview circles are rendered by `PaperRaster` (core) into small bitmaps off the UI thread and cached by (look, surface, tint).

## Decisions already made
1. Paper choice is a DOCUMENT setting saved in the file (JB-9.05), not an app preference. The last-used paper seeds NEW drawings (app pref).
2. Phone first: everything reachable one-handed at 548 dp. Rows scroll sideways like the Concepts screenshot.
3. Design language (memory: greys fade, colour guides; drawers ~55% see-through, Frost = blur only).
4. dp values as drawn; never inflated.

## Tests
Robolectric/instrumented as the existing chrome views do: the swatch is the last child; tapping opens the sheet; choosing
a background sets look + default surface; one sheet visit = one undo step; every control has a non-empty label.

## Do not
Do not put the paper in the layer stack model. Do not install without the Lead + owner's go (owner presses Home first).

## Definition of done
- [ ] tests pass (paste) · [ ] screenshots from the Note 9 (`adb exec-out screencap -p`) of the swatch and the sheet · [ ] pushed · [ ] ROADMAP → 🟧 Built — awaiting the owner

## Questions

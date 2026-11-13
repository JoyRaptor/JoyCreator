# SpriteLab: web tool vs phone editor (AN-10, 2026-11-13)

Phone-first rule: every web feature either reaches the phone or gets a written "no". Compared
`tools/spritelab/SpriteLab.html` (W) with `ui/faditor/sprite/SpriteSheetEditorActivity.java` (P).
Evidence is keyword hits found by a read-only research pass; rows marked "unconfirmed" were not read
in context. Nothing here was tried on a phone by this pass.

Most of the web tool is already on the phone (grid detect and numbers, key colour, swap/ripple, suspects,
sequence chips with drag/hold, scrub, loop modes, fps, backgrounds, onion skin, difference view, pinch/twist
a frame, pivot, per-cell transforms as data, auto-centre/plant feet/copy to all/drift report, undo/redo, name/tags/
viseme, save clip, bake with fits, frames export, .sprite.json in and out, hover labels).

## What is missing or partial

| Web feature | Phone state | Plan |
|---|---|---|
| Sources rail (grey out / restore each merged sheet) | partial: merge is a list, remove with "Put back" | Chip strip above the grid, tap greys, hold restores (FUTURE_PLANS A4 / AN-12) |
| Chip range select and range shading | no | Owner ruling needed (D-21, AN-04); gesture idea: long-press a chip, drag a bracket. Or no |
| Preview zoom + Fit-preview | no | Add a pill and a Fit button; pinch is already cell scale |
| On-preview handles (corner scale, rotate stalk, pivot crosshair) | no | No: pinch/twist/pivot-drag already do it on touch. Revisit only on request |
| Onion colours per side | unconfirmed | Two swatches, hold = colour list |
| Keyboard shortcuts (arrows, Shift x10, Space, Delete, undo) | no; touch nudge only | Add onKeyDown mirroring the web: plugged-in keyboards are real on Android |
| Bulk Name: "from clips", "copy to other sheets" | partial: base + number only | Add the two missing buttons |
| Sheet pinch-zoom of the grid | unconfirmed | Check SpriteGridEditorView; add if missing |
| Bake columns / padding pills | unconfirmed | Check; add if missing |
| Save/Open `.spritelab.json` project file | no | No, by design: the phone saves in the project. Optional export for desktop round trips |
| Key colour picker samples the wrong pixel after a reorder | bug (FUTURE_PLANS A5) | Fix |
| GIF/WebP preview, anchor ghost, sheet from video | not built (A3) | AN-11 |

## Phone-only (web lacks)
Joybot round trip (AI edits the sheet, editor reloads); dead-image relink and "Put sheet back";
sequence and board import, avatar and mesh overlay, drag-pivot on the sheet.

The README's "nothing desktop-only except Sources rail and range" is optimistic: preview zoom, handles,
keyboard and bulk-name extras are also web-only today.

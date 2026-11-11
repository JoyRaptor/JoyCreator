# Wet sheet: Note 9 receipts (2026-11-10 and 11-11)

Debug bench runs on the Note 9 (`JoyBrushBenchActivity`, `--ez jb_wet_sheet true`), filmed by screenshot about every two
seconds. Every picture here shows the app's own canvas only. Design: `../../specs/WET_SHEET_20261110.md`; card:
`../../../jobs/ATLAS-SOLID_never_refuse.md`. These are an agent's checks, not the owner's acceptance.

| Picture | What it shows | Numbers from the same run's log |
|---|---|---|
| `owner_page_31pct_fast.jpg` | The owner's page replayed: nine marks at 31 % zoom, tilted, Water detail Fast; last frame after the print | 0.98 to 1.0 of the clock, 34 to 60 frames a second; 272 tiles printed; sheet 448 x 896 cells, 85 MB (before the margin) |
| `owner_page_31pct_detailed.jpg` | The same page on the Detailed grid, final build | 0.55 to 1.2 of the clock (mostly 0.8 to 0.95), 18 to 55 frames a second; 274 tiles printed; sheet 720 x 1376 cells, 218 MB |
| `undo_redo_two_strokes.jpg` | Two wet strokes; undo, undo, redo, redo; save (print); undo; redo | each press one step; the print travels with its stroke |
| `quick_stroke_before_after.jpg` | Quick wash strokes before and after the cap on a frame's dabs | the scallops, one a frame, are gone |
| `view_moves_whole_wash_rule.jpg` | Zoom in on a wet wash wider than the screen (it sets whole), paint, zoom out (the new wash is carried, still wet), paint, save | "the wash sets", "sheet carried to cells of 4.25 px"; no line where the screen's edge was |
| `pencil_wash_oil_wash.jpg` | Pencil, wash, oil, wash again over each other on one layer; save, undo, redo | 12 strokes, 0 refusals; the wash was printed when the oil began |
| `grain_none_half_full_at_100pct.jpg` | A wash painted at 31 %, printed, seen at 100 %: no grain, half strength (the default), full | at 100 % the print is pixel for pixel the overlay; at 31 % about 1 % of the wash's pixels change at the print, at most 27 in 255 |

Measured without a picture kept:

- **Print against overlay, pixel by pixel** (run `q1`: dry the sheet, hold it 7 s as an overlay, print): consecutive
  screenshots across the print differ by at most 1 in 255, no pixel by more than 2.
- **A wash drying by itself** (run `nat1`, a flat Round wash, Fast): full pace for about 25 s, then 7 frames a second;
  "the wash has dried" and "printed: 12 tiles" 2 min 56 s after the stroke.
- **A frame's cost** (tilted wash over the whole Fast sheet, one step, `log.tag.JoyBrushWaterProf`): look 17 ms, flux 16,
  update 11, bead 6 before; look 2.5, flux 12, update 10, bead 5 after the sheet's lean look and the flux's edge guard.
- **The lab's reference** (`tools/media_render.js "test=flood2&tiltS=8"`): bit for bit the same picture (0 of 810 000 px)
  after the flux guard, the dab's third channel, the cap and the rim (all off or exact there).
- **Half-and-half carry, rejected** (run `rim1`): a wet wash cut at the new sheet's edge, one part kept wet and the other
  printed, left a faint line there once water flowed across the cut. Replaced by the whole-wash rule.

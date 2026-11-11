# ATLAS-SOLID: realistic paint never stops an artist for a technical reason

**Milestone:** M1  ·  **State:** 🟡  ·  **Owner:** Claude Fable 5.2 session "atlas solid" (2026-11-09/10), branch `claude/atlas-solid-20261109`  ·  **Checked:** 2026-11-10 against `origin/joy-creator` `93c1cf76f`

## Why it matters (for the user)
The owner's three bars for JoyBrush (2026-11-09): his wife (a skilled artist, impatient with technology), his toddlers (too
young to recover when something breaks) and himself (a professional). None of them should ever read about memory, megabytes
or "areas", lose a stroke, or see a black square. Pencil, watercolour and oil must simply keep painting.

## Done when
- [x] A drawing whose realistic-paint room is full keeps painting: old paint sets, nothing is refused.
- [x] A small mark far from older paint does not fail.
- [x] A tap leaves a dot with oil and watercolour brushes.
- [x] No raw error text and no megabytes in any sentence an artist can read from the paint engine.
- [ ] The owner paints a long session on the Note 9 and the Note 20 and meets no refusal (his own test).
- [ ] The cause of the 10-31 lost tiles and the failed tile copy is reproduced, or shown gone by a long stress run.
- [ ] Saves stream to disk so the room can grow past 315 MB (then old paint sets far later).
- [ ] Symmetry works with pencil, watercolour and oil in the atlas (today: refused in words).
- [x] STATE.md rows (5.6 and section 9).

## Where it stands (2026-11-10)
On branch `claude/atlas-solid-20261109`. Receipt with the numbers: [ATLAS_SOLID_20261110.md](../joybrush/reviews/ATLAS_SOLID_20261110.md).

| Change | What the artist gets | Proof |
|---|---|---|
| Old paint sets to make room (`MediaRoom`, `GlPaintEngine.setOldMedia`) | A full drawing keeps painting. The least recently painted paint, farthest from the pen, keeps its exact picture and stops being wet-workable. It is part of the stroke's own undo step. | JVM tests; Note 9 probe `roomMade`; Note 9 long sessions with a tiny room |
| The atlas hands back what it only caches when the room is tight (`MediaAtlas.yieldForRoom`) | The same on a phone whose room is smaller than the atlas | Note 9: 40 oil strokes, 24 MB room: 24 refused before, 0 after |
| Far cached tiles are let go (`MediaAtlasPlacement.farToLetGo`, `MediaAtlas.letGoFar`); far walls stay out of the table | A mark far from old paint no longer fails (second review, E7) | JVM tests; Note 9 probe `farMark` |
| A still press reaches the brush (`MediaSpline.finish`) | A tap leaves its dot (second review, E6) | JVM tests (Oil round lays a step). Not looked at on the phone |
| `GraphicsMemoryException`; a tile the driver cannot draw into is refused when made; spare textures given back; later atlases half the size; a failed atlas is retried smaller | No black squares from a tile born without memory; realistic paint is no longer switched off for the session when the big atlas cannot be made | Compiles; existing probes pass. **The failure itself has not been reproduced**, so this path has not run on a phone |
| Sentences | "That stroke stopped early. What it painted is kept: carry on from there." replaces raw error text; no megabytes or "memory" | JVM test on the sentences |

## 2026-11-10 later: water speed, lost squares

- **Water speed** measured and explained: [WATER_SPEED_20261110.md](../joybrush/reviews/WATER_SPEED_20261110.md). The graphics chip is
  the limit (4-11 water steps a second, 3-9 % of real time). A longer time step goes unstable. Shipped: a **Water detail**
  switch in the ⋯ menu (Detailed, default; Fast = 2 x 2 water cells, drips about 4x as fast), all 7 watercolour brushes clean
  in it. The road to about 20x is written there; it trades fine water detail while water runs, which is the owner's call.
- **Lost squares:** 180 mixed strokes over 12.5 minutes at the phone's own room (oil, knife, scraper, wash, round, 6B,
  tilted): 0 tile errors, 0 failed copies, 0 GL errors, no crash. Four Home-and-return cycles with the drawing on screen: no
  black squares. Not reproduced; cause still open. Two further cycles were disturbed by something else using the phone
  (screens not kept).

## 2026-11-10 night: the wet sheet (watercolour rebuilt)

The owner tested the Water detail switch and the fixes above and called watercolour "still unacceptable": hairline seams,
box cut-outs, flat patches, strokes that would not run and then did, tile marks when going back over paint, and all of it
slow. Cause: he paints zoomed out (31 %), eight or nine marks wet 85 to 92 tiles, the atlas holds 62, and every overflow
rule shows as a square or frozen water. Water in tiles cannot feel like paper there. So watercolour was rebuilt:
**design and limits in [WET_SHEET_20261110.md](../joybrush/specs/WET_SHEET_20261110.md)**; the old engine stays one tap
away (⋯ menu, "Watercolour: Wet sheet (new) / In the layer (old)").

| What | Proof on the Note 9 (debug bench, the owner's own page replayed) |
|---|---|
| Wet watercolour on one screen-shaped sheet over the layer; no tiles in the water | Nine marks at 31 % zoom, tilted: all nine run at once, no box, no seam, no frozen band (films `o1`, `o2`) |
| Speed | The same page, final build: about 1.0 of the clock at 34 to 60 frames a second on Water detail Fast (the phone's setting); 0.6 to 1.0 at 20 to 40 frames a second on Detailed. Before: 0.03 to 0.09 |
| Printed into the layer when dry, exactly as shown | Dry sheet against its print, pixel by pixel: no pixel differs by more than 1 in 255 (film `q1`) |
| A wash that dries by itself prints itself; the phone rests meanwhile | A flat wash: full pace for about 25 s, then 7 frames a second, dried and printed 2 min 56 s after the stroke (run `nat1`) |
| Undo and redo by stroke, the print travelling with its stroke | Two wet strokes: undo, undo, redo, redo, save, undo, redo: each press one step, each picture right (film `u1`) |
| A loaded brush no longer takes its own wash back ("painting and then erasing") | The hole where a wash doubles back is gone (films `p1` before, `q1` after) |
| A quick stroke is laid as evenly as a slow one | The scallops, one a frame, along a 0.3 s wash stroke are gone (films `even1` before, `even2` after) |
| The view moves: the wash comes along whole, or sets whole | Zoom out: carried, still wet (film `carry1`). Zoom in on a wash wider than the screen: it sets, no line where the screen's edge was (film `rule1`; the half-and-half version left a line, film `rim1`) |
| Mixed media on one layer | Pencil, wash, oil, wash again over each other, save, undo, redo: 12 strokes, none refused, washes glaze the pencil and each other (film `mix1`) |
| The profile of a frame (big tilted wash, one step) | Before: look 17 ms, flux 16, update 11, bead 6. After the sheet's lean look and the flux's edge guard: look 2.5, flux 12, update 10, bead 5 |
| Sheet memory | 218 MB Detailed (720 x 1376 cells), 118 MB Fast (544 x 992) |

Films and the measured lines: [evidence/wet-sheet-20261111](../joybrush/evidence/wet-sheet-20261111/README.md).

Still to prove or build is in "What is left".

## 2026-11-11: the owner's first answers, and the autosave

- **Paper grain: half** stays the default (the owner). A more-menu row steps None / Half / Full.
- **A little waviness in a wash** (the owner: "a tiny bit of scalloped arcs are nice ... just not so uniform and more
  subtle"): spec rule 12, `u_capSlack`. More-menu row **Wash: Even / A little wavy (default) / Wavy**. Note 9 film of the
  three: `wash_even_little_wavy.jpg`. His own try with the row is owed.
- **A save no longer sets a wet wash** (was ledger C1; spec rule 9): the autosave, a copy and an export stamp the wash
  into stand-in tiles for the readback only. Note 9 run `sw1`: saved 1.5 s after the strokes ("saved as it stands: 10
  tiles ... still wet"), the drips ran on for the next 7 s; then dried on the sheet, saved again (sum of all tile bytes
  54195908), really printed, saved again: 54195908, the same 16 tiles. Film `save_while_wet_keeps_running.jpg`.
  Leaving the screen still sets the wash for good.
- **"Dry the wash now"** in the more menu while a wash is wet (a hair dryer). Built; menu row not tapped on the phone by me.
- **Oils looked at, not changed.** Four oil brushes crossing at 100 % and at 31 % on the Note 9 (runs `oil100`, `oil31`):
  16 strokes each, none refused, no square or seam seen in the frames; a brush that crosses blue carries it on (yellow
  turns green, red turns maroon). Whether that reads as real oil is the owner's eye: asked.

## What is left
- [ ] **Wet sheet: owner's own test** on the Note 9 (his page, his hand, tilt), then the Note 20.
- [x] Wet sheet: the paper's grain at full resolution ("sharp when dry"): built 11-11, half strength by default; the owner
      picks the strength from the comparison (evidence `grain_none_half_full_at_100pct.jpg`). A ⋯ menu row for it is not built.
- [ ] Wet sheet: a wash wider than the screen sets when the view moves and the painter paints again (spec, rule 10). Keeping
      it all wet needs one simulation that spans both views.
- [ ] Wet sheet: the tilted flux and update passes are now most of a frame (12 + 10 of about 36 ms a step on a big wash).
      On Detailed the owner's whole page runs at 0.6 to 1.0 of the clock; the cheap tilt path of the WATER_SPEED road is next.
- [ ] Wet sheet: 218 MB of graphics memory on Detailed beside the atlas. Not seen to fail on the Note 9; measure on a
      long mixed session, and let the atlas's engine sleep while only the sheet paints.
- [ ] Wet sheet: the small follow-ups in [LEDGER_SMALL_FIXES.md](../joybrush/LEDGER_SMALL_FIXES.md) section C.
- [ ] Owner test on the Note 9 build, then the Note 20; owner compares Water detail Fast and Detailed with tilt.
- [ ] Water speed steps 1-3 of the WATER_SPEED road (cheap tilt path, 4 x 4 running water, step only moving water).
- [ ] A stress run long enough to meet the 10-31 tile failures again (or not), reading `JoyBrushTiles` and `JoyBrushMediaMemory`.
- [ ] When running water is pinned far from the pen, the stroke is still refused in words ("too far from paint that is still wet"). Rare; the water should be set aside instead.
- [ ] Streaming saves (second review E4), then raise `LayerBudget.MEDIA_RAM_SHARE`.
- [ ] Memory admission for the atlas itself (second review E3): the check before making water still counts the old 1024 window.
- [ ] Symmetry for realistic paint in the atlas.
- [ ] A tile boundary can show where set paint meets live thick paint that is re-lit. Not seen in the runs; ask the owner to look.

## Specs, handoffs and evidence
- [Second review](../joybrush/reviews/CODEX_REVIEW_WAVE2_20261101.md) E3, E4, E6, E7; [MEDIA-SPEED card](MEDIA-SPEED_large_brushes.md) for the 10-31 failures.
- Evidence folder: `tasks/joybrush/evidence/atlas-solid-20261110/`.

## Files and traps
- `GlPaintEngine.writableMediaTiles` (hot file): setting old paint happens there, before anything is swapped. Never offer a tile the
  media tool holds (`mediaRoomKeep`): written back after its payload set, it would lose its ground.
- `MediaAtlas.hold`, `flush`; `MediaAtlasPlacement.table` no longer grows for far walls.
- The bench's `jb_media_budget_mb` now sticks (`JbCanvasView.mediaBudgetHeld`); before, the screen reset it after one stroke, so
  earlier small-room runs through the real screen did not test what they meant to.

# ATLAS-SOLID: realistic paint never stops an artist for a technical reason

**Milestone:** M1  ·  **State:** 🟡  ·  **Owner:** Claude Fable 5.2 session "atlas solid" (2026-11-09/10), branch `claude/atlas-solid-20261109`  ·  **Checked:** 2026-11-13 (the 11-13 section below)

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
- [~] The cause of the 10-31 lost tiles and the failed tile copy is reproduced, or shown gone by a long stress run. 11-13:
      the likely cause was already fixed on 11-01 (see below), a starvation bench finds nothing, and any failure is now
      kept in a file on the phone. Not reproduced, so not proven gone.
- [ ] Saves stream to disk so the room can grow past 315 MB (then old paint sets far later).
- [~] Symmetry works with pencil, watercolour and oil in the atlas. Built 11-11 for pencil and oil (2a23c082e, every copy its own lane, no pin) and the wet sheet carries watercolour; not yet used by the owner (checked 11-13).
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

## 2026-11-13: the owner's list, items 2 to 8 ("break from the knife and get 2-8 done")

| # | Item | Where it stands | Proof, and what is not proven |
|---|---|---|---|
| 2 | Oil that moves as one body | Hair brushes pull the colour of wet paint along; on by default, old way one tap away. Card [OIL-KNIFE](OIL-KNIFE_real_knife_and_glop.md) | PC lab sheets; Note 9: Oil flat, Oil round and All-round each alone, no failed frame, within 6 % a step. The Fan blender does not pull: it left a row of beads in a fast stroke. Thick paint heaped and squeezed is not built (ledger E2) |
| 3 | A bigger wet-paint room (streaming saves) | **Not started.** A painted oil tile is about 1.28 MB (two paint stores and its ground), so the 315 MB room is about 246 tiles wet at once; past that old paint sets (never a refusal). Growing the room needs saves that stream tile by tile through `JbArchive`, `readContents` and open: a multi-day job in the save path | Second review E4. The Codex claim on `JoyBrushActivity` save/restore is dead (orchestrator, 11-13): whoever takes this claims it on the BOARD |
| 4 | A wet wash wider than the screen when the view moves | The part still in view stays wet; the rest is parted off through a soft band and sets (spec rule 10) | Note 9 films: a stroke painted after zooming in runs into the still-wet wash; no line at the join (brightness measured along each band); undo and redo across the parting go one press a step. The kept part goes on spreading while the rest has set, so their texture differs a little at the edge of the view |
| 5 | Watercolour at full speed on the fine grid | No engine change. Measured with the profiler off: three Round strokes 39 frames a second at 0.94 of the clock, three Wash strokes 33 at 0.76, the nine-band page at 31 % 31 at 0.85 | My 11-11 figure of 0.33 was wrong: the profiler's own waits were left on (a setprop lasts until reboot). A mask of live blocks was tried and taken out (spec rule 6) |
| 6 | The wet sheet on animation frames, and on layers with editable lines | **Frames: built** (spec rule 17). **Layers with lines: open, no owner**: the recorded-line engine draws them and Codex has left | Note 9 matrix on an animation board, final build: watercolour 161047 px, oil 98839, pencil 28741, all in the frame's own cel, none on the shared plane inside the board, undo exact; canvas and sprite boards work (morning build) |
| 7 | The 10-31 lost squares | The Engine Lead's 11-01 fix is the likely cure: a slept engine's textures were deleted twice (`MediaLayerEngine.release`). New: every tile failure is written to `files/joybrush-incidents.log` with what the engine held (`TileIncidents`), and a bench takes graphics memory away first (`jb_gpu_hog_mb`) | 2 and 3 GB of noise held (and 1 to 3 GB of flat colour on the morning build), then oil strokes and a save: no failed frame, no incident line. All 23 realistic-paint brushes, two strokes each: none. Not reproduced |
| 8 | Symmetry for realistic paint in the atlas | Works: every copy is its own lane, nothing is pinned or refused | Note 9 pictures: Oil round, 6B pencil and watercolour on the wet sheet, six copies each |

### 11-14 and 11-15: a reopened drawing keeps its paint again (the paint-store save)

**Found 11-14.** After oil, knife and pencil strokes a save on the Note 9 held 35 picture tiles and 0 paint store
tiles. `GlPaintEngine.mediaPlaneOfCel` (10-11, JB-2.40 slice 3a) was `layers[layerId]?.let { planeOf(it, celId) } ?: celId`:
for a layer's shared cel `planeOf` answers null (the shared plane), the `?:` took that for "no such layer" and handed back
the cel id, and the save listed the stores of a frame plane that does not exist. From 10-11 to 11-15 only animation
frames saved their pencil, watercolour and oil state; every ordinary layer saved its picture alone. **Drawings saved in
that time open as pictures with their paint set** (they look the same; old paint cannot be blended, lifted or scraped).
Opening was always right. The archive's unit tests feed it by hand and never went through that function.

**Fixed 11-15, measured first** (bench then-step `savefile`: readback, `JbArchive.save` to a scratch file, open, every
store tile compared, loaded onto the canvas, read back again). Note 9, a page of Oil flat zigzags:

| What a save carried | Picture tiles | Store tiles (raw) | Pen waits (readback) | Written in | On disk | Heap (of 512 MB) | Read again | Shown again |
|---|---|---|---|---|---|---|---|---|
| All of it, deflate 6 | 50 | 100 (50 MB) | 0.4 s | 4.5 s | 21 MB | 80 MB | 1.7 s | 0.6 s |
| All of it, deflate 6 | 136 | 272 (136 MB) | 1.1 s | 11.3 s | 55 MB | 191 MB | 4.6 s | 1.5 s |
| All of it, deflate 6 (a full room) | 262 | 524 (262 MB) | 2.0 s | 19.4 s | 94 MB | 347 MB | 7.8 s | 2.8 s |
| All of it, deflate 1 | 264 | 528 (264 MB) | 1.9 s | 14.1 s | 98 MB | 352 MB | 8.1 s | 3.1 s |
| The newest 128 MB, deflate 1 (**as shipped**) | 264 | 256 (128 MB) | 1.1 s | 8.8 s | 53 MB | 211 MB | 4.4 s | 1.4 s |
| The newest 64 MB, deflate 1 | 264 | 128 (64 MB) | 0.6 s | 6.8 s | 31 MB | 147 MB | 2.8 s | 0.8 s |

In every row all store tiles and all picture tiles came back byte for byte after save, open and save again.

What landed with it:
- `mediaPlaneOfCel` answers null for the shared cel.
- **A save keeps the newest paint as paint, within a budget** (`LayerBudget.savePaintBudgetBytes`: a quarter of the app's
  heap, at most 128 MB; `MediaRoom.newestWithin`, `GlPaintEngine.mediaTilesToSave`, `JbCanvasView.savePaintBudgetBytes`).
  Older paint is saved as its picture and opens as set paint, exactly as paint the room has set. 128 MB is about 100
  painted oil tiles (two and a half Note 9 screens at 100 %).
- Paint store tiles deflate at best speed (`ArchiveMediaDeflate`); pictures as before. Any level reads back the same,
  so old and new files and old and new builds open each other's.
- One staging buffer and one float array for every store tile read back or uploaded (`reuseMediaStaging`): a save of 31
  oil tiles had left 124 MB of garbage behind it.
- The save's 15 s limit (`SNAPSHOT_TIMEOUT_MS`) covers the readback only; the write has none. A long write keeps the
  one save thread busy, and the working copy's recovery copy and folder copy are each the file's size.

### Streaming saves: what is left, in the order to build it (handoff, 11-15)

Where it stands: the paint is saved again, within a budget (above). What is left is making a save cost what CHANGED
instead of what the drawing holds, so the budget can rise to the whole room and the room itself can grow
(`LayerBudget.MEDIA_RAM_SHARE`, 256 MB for every 6 GB: it is that size because a save and an open each hold everything in
the 512 MB heap: `JbCanvasView.snapshot` -> `readContents` -> `BoardSnapshot.capture` -> `JbArchive.save`, and
`JbArchive.read` -> `JbCanvasView.load`). The save is safe as it is (`.tmp`, sync, `.bak`, rename; refusals before any
byte is written): this is about room and time, not about losing work.

Where the time goes (the table above): reading a store tile back is about 4 ms with the pen waiting; deflating it at
best speed about 36 ms a megabyte on the save thread; the pictures of a full page about 4.5 s at level 6; reading a
file again 30 ms a megabyte; showing it 11 ms a megabyte. A stroke changes five to twenty tiles; a save redoes all of
them.

1. **A save that rereads and rewrites only what changed.** The engine already stamps every paint tile it writes
   (`GlPaintEngine.mediaWritten`, `mediaClock`); picture tiles need the same stamp. Keep, from the last save of this
   drawing, an index of each entry (name, stamp, crc, sizes, where it lies in the working file). A new save reads back
   only tiles whose stamp moved, deflates those, and copies every other entry's compressed bytes straight from the old
   working file into the new one: a zip's local entries can be copied byte for byte, but `java.util.zip` cannot do it,
   so this needs a small zip writer of our own (local header, data, central directory; no zip64 below 4 GB and 65,535
   entries, and `MAX_ENTRIES` is 20,000). Same file format, same readers. The new file is still written whole to `.tmp`
   and renamed, so the atomic rule holds. `JbContents` today means "every tile as bytes" to its users
   (`recovery.rememberWorking`, `DrawingLibrary.onSaved` for the thumbnail, `writeCopy`, `DrawingHistory.preserve`,
   exports): give the working save its own narrower path and leave those on the full snapshot.
   Prove it with: `JbArchiveTest`'s corpus read by the stock `ZipInputStream` and `ZipFile`; a save, a stroke, a save,
   an open compared tile for tile (bench `savefile`); a writer killed half-way leaving the old file (JBX-37's test).
2. **Open as it is read**: list the entries (`ZipFile`), then inflate and upload in batches, so opening holds a batch
   and not the drawing. `JbCanvasView.load` uploads everything in one GL task today.
3. **Then** raise the save's budget to the room, then `MEDIA_RAM_SHARE` and the read limits (`MAX_TOTAL_BYTES` 1 GiB,
   `MAX_ENTRIES` 20,000), and count the atlas's own memory in the admission check (second review E3).
4. Small and separate: `DrawingLibrary`'s after-save task keeps the whole snapshot alive only to read its id; exports
   read the paint stores they never use (second review E4).

Tests that guard the save today: `JbArchiveTest` (34), `JbArchiveCompletenessTest` (refusals before any output),
`JbArchiveMediaTest`, `MediaSaveTest`, `BoardSnapshotTest`, `WorkingSaveBarrierTest`, `MediaRoomTest` (the budget's
choice), `MediaBudgetTest`.

How to measure (anyone, about ten minutes on the Note 9): install a debug build, then start the bench with
`--es jb_bench band0,...,band9 --es jb_bench_brushes "media:paste:Oil flat" --ez jb_media_atlas true --ez jb_wet_sheet true
--ez jb_bench_clear false --es jb_bench_zoom 0.36 --es jb_bench_then 'wait 1500;savefile;wait 40000'` and read the three
`then: savefile` lines in logcat (tag `JoyBrushPerf`). `savepaint <MB>` and `savelevel <0-9>` between two `savefile`
steps change the budget and the deflate level; `jb_bench_zoom` 0.87 / 0.5 / 0.36 gives about 50 / 136 / 264 tiles.

### Also found by the incident recorder (11-14), fixed 11-15

`files/joybrush-incidents.log` on the Note 9 held one line: "a media frame failed ... A board cannot change during a
stroke", from `reportMediaHistory` -> `reportHistory` -> `setBoardDocument`. A wash dried and printed itself (the undo
stack changed) while a plain stroke was being drawn; republishing the board under that stroke was refused, the refusal
was caught as a failed media frame, and the water's engine was let go. `reportMediaHistory` now waits for the stroke to
end. Found by reading; not reproduced on the phone.

Pictures and the numbers behind this table: [evidence/atlas-2to8-20261113](../joybrush/evidence/atlas-2to8-20261113/README.md).

## What is left
- [ ] **Wet sheet: owner's own test** on the Note 9 (his page, his hand, tilt), then the Note 20.
- [x] Wet sheet: the paper's grain at full resolution ("sharp when dry"): built 11-11, half strength by default; the owner
      picks the strength from the comparison (evidence `grain_none_half_full_at_100pct.jpg`). A ⋯ menu row for it is not built.
- [~] Wet sheet: a wash wider than the screen (spec, rule 10). Since 11-13 the part in view stays wet and the rest is
      parted off softly. Keeping all of it wet needs one simulation that spans both views.
- [ ] Wet sheet: the tilted flux and update passes are now most of a frame (12 + 10 of about 36 ms a step on a big wash).
      On Detailed the owner's whole page runs at 0.6 to 1.0 of the clock; the cheap tilt path of the WATER_SPEED road is next.
- [ ] Wet sheet: 218 MB of graphics memory on Detailed beside the atlas. Not seen to fail on the Note 9; measure on a
      long mixed session, and let the atlas's engine sleep while only the sheet paints.
- [ ] Wet sheet: the small follow-ups in [LEDGER_SMALL_FIXES.md](../joybrush/LEDGER_SMALL_FIXES.md) section C.
- [ ] Owner test on the Note 9 build, then the Note 20; owner compares Water detail Fast and Detailed with tilt.
- [ ] Water speed steps 1-3 of the WATER_SPEED road (cheap tilt path, 4 x 4 running water, step only moving water).
- [ ] A stress run long enough to meet the 10-31 tile failures again (or not), reading `JoyBrushTiles` and `JoyBrushMediaMemory`.
- [ ] When running water is pinned far from the pen, the stroke is still refused in words ("too far from paint that is still wet"). Rare; the water should be set aside instead.
- [ ] **Streaming saves** (second review E4), then raise `LayerBudget.MEDIA_RAM_SHARE`. Launch-critical with the lost
      squares (orchestrator, 11-13: work loss matters more than speed). Multi-day, in the save path; not started.
- [ ] **Wet sheet on layers with editable lines** (ledger C6): open, no owner.
- [ ] Memory admission for the atlas itself (second review E3): the check before making water still counts the old 1024 window.
- [x] Symmetry for realistic paint in the atlas (11-11 code, 11-13 Note 9 pictures). The owner's own use is owed.
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

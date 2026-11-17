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
| 6 | The wet sheet on animation frames, and on layers with editable lines | **Frames: built** (spec rule 17). **Layers with lines: built 11-16** (section "11-16: watercolour on a layer with editable lines"); before that: open, no owner: the recorded-line engine draws them and Codex has left | Note 9 matrix on an animation board, final build: watercolour 161047 px, oil 98839, pencil 28741, all in the frame's own cel, none on the shared plane inside the board, undo exact; canvas and sprite boards work (morning build) |
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

### 11-15 later: the pen waits half as long, and a save that rewrites only what changed

Both measured on the Note 9 on a full page of oil (264 picture tiles, 256 store tiles, 128 MB of stores) and on a small
page of a dried wash, oil, knife and pencil (33 picture tiles, 111 store tiles):

| | Full page | Small mixed page |
|---|---|---|
| Readback, store tiles read as full floats and rounded (before) | 1.46 to 1.48 s | 0.46 s |
| Readback, store tiles read as the half floats they are (`readMediaAsHalves`; the driver names RGBA / HALF_FLOAT as its own format) | 0.55 to 0.58 s | 0.22 s |
| The same bytes? (bench `savecmp`) | byte for byte | byte for byte |
| `JbArchive.saveCarrying`, first save (everything compressed) | 6.9 s | 1.3 s |
| the same drawing saved again (521 of 522 entries carried) | **0.69 s** | 0.35 s |
| after one undo (83 entries changed, 412 carried) | **1.28 s** | 0.58 s |
| Opened again and compared (bench `savecarry`) | all tiles exact, three times | all tiles exact, three times |

`ZipCarry` (io/ZipCarry.kt) is the zip writer that carries an entry from the older file as its compressed bytes lie;
`JbArchive.saveCarrying(file, contents, memo)` is the save built on it. An entry is carried only when the memo of the
last save knows it, its bytes have the same length, CRC-32 and Adler-32, the file's directory agrees, and the
compressed bytes read from the file have the checksum they had when written; a file the memo does not remember, or
bytes that went bad on the disk, are written afresh (`JbArchiveCarryTest`, `ZipCarryTest`: 11 tests). The file is the
archive `save` makes, entry for entry, through the same `.tmp` and rename.

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

1. **A save that rewrites only what changed: built (above), and since 11-15 evening the screen's working save calls
   it** (`JoyBrushActivity`, `SaveDest.Working`, one `SaveMemo` per screen; `ArchiveCarrying.on = false` is the way
   back; logcat tag `JoyBrushSave` says how many entries were carried). Checked on the Note 9 in the real screen, on a
   new drawing given a copy of a heavy painted page (59 MB, 1,190 entries): first autosave 18.0 s (nothing to carry
   from yet), the next 0.85 s (1,188 carried), the save on Home 1.8 s. The owner then drew on it with the pencil and
   an oil brush: the file grew to 1,532 entries (140 picture tiles, 67/67/68 paint-store tiles), every entry reads, and
   all 1,188 old entries are bit for bit what they were (same CRC, same packed size) against the page they were copied
   from. Not checked: the picture compared by eye after reopening. In the same change, **two screens on one drawing**
   (bug hunt 11-15): `WorkingFileOwners`, the screen that showed a file last is the one that writes it, the older one
   skips its save and loads the file again when it comes back; unit-tested (`WorkingFileOwnersTest`), NOT reproduced
   on a phone. Left: (b) the recovery copy and the Joy Creator folder copy are still whole
   byte copies of the file after every save; (c) **rereading only what changed: built 11-16** (section "11-16: a save reads
   back only the tiles that changed" below). The budget (`LayerBudget.savePaintBudgetBytes`) is still set by the first
   save of a screen and the save on leaving, which read everything, and by the heap.
2. **Open as it is read**: list the entries (`ZipFile`), then inflate and upload in batches, so opening holds a batch
   and not the drawing. `JbCanvasView.load` uploads everything in one GL task today. **11-16: built for the paint state** (section "2026-11-16: open as it is read"
   below: the media tiles stay in the file until the canvas takes them tile by tile; still one GL task; the picture
   tiles are still read whole).
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

### 11-15 night: no stroke lost when the screen is rebuilt or the graphics die (JBX-37)

**The gate.** `--es jb_bench_life <tag>` runs the stroke replay as a REAL drawing on a cache file of its own (real
autosaves, pause save, recovery copy; never in the Library). Then-steps: `digest <label>` (one checksum over every
picture tile and one over every paint store tile, logcat `JoyBrushPerf`, `bench/LifeDigest`), `recreate` (Android builds
the screen again; the new screen opens the file and logs `digest rebuilt`), `cover` (another screen over it: pause,
stop, save; Back returns), `savenow` (the working save, so the next step meets it half written).
`--ez joybrush_test_recreate_context true` makes the pause destroy the graphics context for real. Two digests that
agree are the same tiles with the same bytes.

**What it found (Note 9, a full page of oil, 264 MB of paint).** The screen built again while the graphics were busy:
the save on the way out never got its answer ("the drawing did not answer in time" 15 s later), the new screen said
"The last save didn't finish" and opened the save before it. Cause: on this phone the window's stop destroys the
canvas's surface at once (pause, stop, surface gone within 15 ms, all in one turn of the UI thread); a save's read is
queued for the next frame, and no frame runs without a surface. The same hole was open for a save that waits behind
one still being written when the person presses Home: it started after the view stopped drawing and got no answer.

**The fix (`JbCanvasView`, `JoyBrushActivity`).** The frames already owed run before the surface goes
(`surfaceDestroyed` waits for them, at most 3 s). When the pause's save cannot start at once, the drawing is read right
then and kept (`keepPausePicture`); a save that starts once the view no longer draws is answered from it; it is dropped
on resume and when no save is waiting. Answers go to the main thread itself, not through a view that may be off its
window. A closing screen with nothing kept refuses at once instead of after 15 s.

**Proof, Note 9, build c6c2ab3fa, digests equal in every case (pictures and paint stores):**

| Case | Result |
|---|---|
| Ink + pencil + oil, screen built again | same (15 + 57 tiles) |
| Ink + pencil + oil, graphics lost on pause, restored | same; restore 0.95 s |
| Full page of oil, screen built again mid-read (the failing case) | same (264 + 256 tiles) |
| Full page of oil, graphics lost on pause, restored | same; restore 5.9 s |
| Full page of oil: save, undo, leave while that save is still writing, graphics lost | same as after the undo: the second save came from the kept picture |

**A save that fails leaves the older file** (`JbArchiveCarryTest.aSaveThatFailsWhileWritingLeavesTheOlderFileAndNothingElse`):
a full disk, memory running out and an unforeseen error, each forced after the new file is written, for both kinds of
save: the file is byte for byte what it was, no `.tmp` is left (an out-of-memory used to leave one: 26 MB was found
beside a drawing on the Note 9), and the next save is whole and still carries.

Also here: the pinned reference photo is read on a thread of its own (bug hunt C7: a cloud-only photo held up opening
and every save).

Not covered: the process killed while a save is being written (the file is safe by construction, the newest marks are
not in it); graphics lost while the person is painting in front (restores the last autosave, up to 30 s old, and says
so); two screens on one drawing is unit-tested only.

### Also found by the incident recorder (11-14), fixed 11-15

`files/joybrush-incidents.log` on the Note 9 held one line: "a media frame failed ... A board cannot change during a
stroke", from `reportMediaHistory` -> `reportHistory` -> `setBoardDocument`. A wash dried and printed itself (the undo
stack changed) while a plain stroke was being drawn; republishing the board under that stroke was refused, the refusal
was caught as a failed media frame, and the water's engine was let go. `reportMediaHistory` now waits for the stroke to
end. Found by reading; not reproduced on the phone.

Pictures and the numbers behind this table: [evidence/atlas-2to8-20261113](../joybrush/evidence/atlas-2to8-20261113/README.md).

### 11-16: a save reads back only the tiles that changed (JBX-36, the rest of "streaming saves" item 1)

**In plain words.** An autosave used to read the whole drawing back from the graphics chip while the pen waited: 0.45 s
on a full page of oil. Now the chip is asked for a fingerprint of every tile, which is quick, and only the tiles whose
fingerprint moved are read. The rest are taken from the file, as the save already did for writing.

- **Why fingerprints and not a list of what was written.** The plan was a stamp set wherever a tile is written. The
  engine writes tiles in more than forty places and others would be added; one forgotten stamp would save an old tile
  silently. A fingerprint is of the tile's CONTENT, so there is nothing to forget (`core/paint/TileFingerprint`, 128
  bits; the chip runs the same arithmetic in `gl/TileHasher`, three short passes, 64 tiles at a time).
- **How the file is trusted.** The save remembers, for each tile it wrote, the fingerprint the canvas gave with the
  bytes (`JbArchive.SaveMemo`). A tile comes without bytes only when its fingerprint now is the one the canvas last
  read it with (`JbCanvasView.snapshotSparse`, `SparseContents`), and the file stands in for it only when the last save
  holds that same fingerprint AND the file passes every check a carried tile already had to pass. Otherwise the save
  writes nothing (`JbArchiveNeedsBytes`), the file is as it was, and the screen reads the whole drawing once
  (`JoyBrushActivity.beginSave`). **The file format is unchanged**: the same archive, entry for entry.
- **When everything is still read.** The first save of a screen, the save on leaving (it also makes the lobby
  thumbnail, which a save without all the bytes cannot), a drawing with editable lines, a phone whose driver cannot
  fingerprint, and any copy or export. `ArchiveCarrying.rereadChangedOnly = false` is the way back.
- **Note 9, build ea7bf56b8 (bench steps `printcheck`, `savesparse`).**

| | Full page of oil (264 picture tiles, 256 paint tiles) | Light page (ink, pencil, watercolour, oil; 56 tiles) |
|---|---|---|
| The chip's fingerprints against the same arithmetic on the CPU | 0 of 804 tiles differ | 0 of 67 differ |
| Nothing changed: the pen waits | 130 ms (a whole readback: 479 ms) | 34 ms (75 ms) |
| A big undo or redo (106 tiles changed; 24 on the light page) | 351 to 376 ms (423 to 426 ms) | 81 to 87 ms (69 to 71 ms) |
| The first save of a screen (everything read, fingerprints on top) | 670 to 688 ms (about 500 ms) | 139 ms (about 110 ms) |
| Every tile left unread, against a whole readback taken right after | EXACT, 5 of 5 | EXACT, 3 of 3 |
| The file written, read again | 0 of 520 tiles differ, every time | 0 of 56, every time |

  In the real drawing screen (the lifecycle gate, heavy page): four autosaves read 108 to 336 of 526 tiles and took the
  rest from the file, then the graphics were taken away and the drawing was restored from that file: the same picture
  and paint as before (digests `92d0aae9` / `6460c2e2` both sides).
- **Unit tests.** `TileFingerprintTest` (any one changed byte, swapped texels, two flipped signs), `JbArchiveCarryTest`
  (the file made is the one a full save makes; no memo, a tile changed since, a save that gave no fingerprints, another
  file, a damaged carried tile: all refuse and leave the file), `BoardSnapshotSparseTest`.
- **What it does not do, said plainly.** It is about twice to three times quicker when little changed, which is the
  usual autosave; it is no quicker when a hundred tiles change at once, and the first save of a screen is about 180 ms
  slower on a full page. Fingerprinting costs about 0.24 ms a tile; a stroke's own list of touched tiles could skip
  most of that, but it would have to be proven complete first. Two different tiles sharing a fingerprint is possible in
  principle (128 bits) and has not been seen (793 different tiles, 793 fingerprints). Not tried on the Fold or any
  other graphics chip: a driver that cannot do it reads everything, but a driver that computes it WRONGLY the same way
  twice is what `printcheck` exists to catch, and it has only been run on the Note 9.

### 11-16: the rest of JBX-37 (killed while saving, how much a crash can cost, other waits on the graphics thread)

**In plain words.** Three things were left open on 11-15 night. Two are now proven or made smaller; one is as it was.

- **The app is killed while a save is being written: proven safe on the Note 9.** A full page of oil, a save asked
  for, and the app force-stopped the moment the new file began to grow (three rounds, build 8cb1d0672). Twice the kill
  landed mid-write: the drawing's file was byte for byte the one from before (same MD5), all 522 entries read, and a
  half-written `.tmp` was left beside it, which the next save overwrites. Once the save had already finished: the new
  file read whole and the one before it was the `.bak`. What a kill costs is the marks since the last finished save,
  never the file.
- **How much that is: halved.** The autosave came 30 s after the last change and every 60 s while painting. It now
  comes after 15 s and every 30 s (`AUTOSAVE_AFTER_MS`, `AUTOSAVE_WHILE_PAINTING_MS` in `JoyBrushActivity`). That is
  affordable since JBX-36: a save reads and writes what changed (about 0.13 s of waiting pen and 1 s of writing on a
  full page, where it was 0.45 s and 9 s). The same halving applies to graphics lost while the person is painting, which
  restores the last save. The copy in the Joy Creator folder is the whole file written again, so it follows at most
  once a minute and after every save that read everything (the first of a screen, and leaving it); a copy that failed is
  tried again at the next save (`DrawingLibrary.onSaved`).
- **Lifecycle gate on that build (f3bfc1eee), five cases, digests equal in all five**: full page rebuilt
  (`a4082fce`/`d62df07e`), full page graphics lost (`572b31e0`/`31e5a6dc`), full page left while a save was being
  written then graphics lost (`5ed44782`/`c4341ca0`), light page rebuilt (`9f070948`/`83ab6b90`), light page graphics
  lost (`748ab8e0`/`e3e8af00`). The log shows two autosaves during each painting run, about 30 s apart.
- **Other requests that wait for the graphics thread (read through, no change needed).** `JbCanvasView.load` and
  `submitLineEdit` run even when the graphics were lost and always answer; the screen's restore has its own 30 s
  timeout and version check; a colour sample or a preview that never answers costs nothing. The drain that runs these
  commands now survives one of them failing (bug-hunt session, 60322b90b).
- **Still open.** Graphics lost while the pen is down still loses up to the last 15 to 30 s (nothing can be read from
  graphics that are gone; only a save can shrink it). The two-screens guard still has no phone reproduction: the
  drawing screen cannot be started twice from outside the app. Not measured: what the more frequent saves cost in
  battery and heat over a long session (each is about 1 s of one core and about 100 MB written on a full page).

### 11-16: watercolour on a layer with editable lines (ledger C6; built by the bug-hunt session, finished and landed by the engine session)

**In plain words.** Until now, picking a watercolour brush on a layer that held editable lines first turned those lines
into paint ("became paint", its own undo step), because the wet wash had nowhere to go among lines. Now the wash goes on
the same layer and the lines stay lines: they can still be moved, recoloured or deleted afterwards, and the wash is
redrawn over them.

- **How.** When the wet sheet prints its wash into a layer whose plane holds items, each printed tile also keeps the
  wash ALONE as a pixel slab on top of the items in time order, the way a fill is kept
  (`GlPaintEngine.overlaySlabs`, called from `printOverlay`; `slabTile` merges it into the slab under it when that is
  plain paint too, so slabs do not pile up). A save taken while the wash is wet holds it the same way for the length of
  the save (`withOverlayStamped`). `JbCanvasView.startStroke` no longer freezes the lines for watercolour, and
  `MediaCanvas.begin` lets the sheet take a layer with lines. Watercolour the sheet does NOT take (a blank animation
  frame, a recorded scene) is refused in words on such a layer rather than reaching the old water among lines.
- **The saved file.** No new kind of entry: the wash is an ordinary pixel slab, as a fill has been since v10. Old
  drawings are read by the same reader.
- **Proof: bench case `washOverLinesProbe`** (an ink line, a wash of the first built-in watercolour across it, on the
  app's own canvas). Note 9, build 0f3dc40ea, pass with no problems: the line stays a line through the wash, Dry now,
  undo, redo and both reopens; recolouring the line and back redraws its tiles from the layer's items with 0 px off
  after Dry now, after redo, after undo, and after each reopen; undo of the wash is one press and leaves the line alone
  (0 px off); redo straight after is the dried picture exactly (0 px); a save of the DRIED wash opens to the dried
  picture exactly (0 px); a save taken while WET opens within what the wash itself moved around that save (41,610 and
  24,373 px from the reads just before and after it, which are 54,457 px apart; the wash is 181,204 px). The Fold run
  (bug-hunt session, one run, earlier form of the bench) had the same 0 px results. `wetSheetFailureProbe` still passes.
- **Two test faults found on the way, not app faults.** The first form of the bench pressed Redo after two new edits
  (which empty what there is to redo) and so "found" 180,231 px off; and it held the reopened wet save against a single
  read of a wash that was still moving. Both are corrected in the bench.
- **Not covered.** On a layer WITH lines: no memory running out during the print, no view move that parts a wet wash
  (`cutCarry`), no second wash over the first, no animation frame or mask, no moving or deleting the line under the wash
  (only recolour), no board edit while the wash is wet, and no real pen. (The lifecycle gate was run after landing: next section.) The look of a wash on a line
  layer has not been compared by eye with one on a plain layer. The owner has not tried it.

### 11-16: editable watercolour lines through the lifecycle gate (JBX-42 / L9 "View save, reopen, recovery")

**In plain words.** The "Watercolour line" brush draws lines that stay editable. Until now nobody had checked, in the
real drawing screen, that they come back as the same lines with the same picture after Android rebuilds the screen or
takes the graphics away. They do, also when an ink line, a watercolour line and a wet-sheet wash share one layer.

- **The gate.** The lifecycle gate (`jb_bench_life`, JBX-37) with `--ez jb_editable_lines true`, so every stroke is
  kept as a line, and a digest that now also counts what a line drawing is saved as: the looks of the tiles lines cross,
  the paint slabs between lines, and the lines' own records (`LifeDigest`: `N looks`, `N slabs`, `N editable lines`,
  each with a checksum; the records through `StrokeCodec`). The wash is dried in place first (`dry`), because leaving the
  screen sets a wet wash and a wet picture cannot equal a set one.
- **Note 9, build 2cad57534, four cases, every number equal before and after:**

| Case | Looks | Slabs | Editable lines |
|---|---|---|---|
| Watercolour lines, screen built again | 12, `b56b04b5` | none | 9, `34f0ac6c` |
| Watercolour lines, graphics lost and restored | 12, `439cfcda` | none | 9, `306d916e` |
| Ink line + watercolour line + wet-sheet wash on ONE layer, screen built again | 18, `6c1300c3` | 18, `5c26e7f9` | 18, `7b7e9bba` |
| The same, graphics lost and restored | 18, `5fc3d2b2` | 18, `cd2b8733` | 18, `906602a7` |

  The two mixed cases are also the lifecycle check that the wash-on-line-layers work (section above) had not had: its
  slabs come back among the lines.
- **Two faults in the test found on the way, not in the app.** The real screen puts its own "Editable lines" choice back
  between strokes, so the first runs saved plain paint and proved nothing about lines (the bench now sets the switch at
  every pen-down). And a wet wash compared with its set self differs, as it should.
- **On an animation board too (11-16, build 34e33d60f).** The bench lays an animation board over the middle of the page
  before the strokes (`--es jb_bench_board animation`, the app's own board operation), so they cross its first frame and
  the shared canvas around it; then a blank second frame is added and shown (`addframe`), so what was painted on frame 1
  is no longer on the screen when the drawing is saved. Four cases, every number equal before and after:

| Case | Looks | Slabs | Editable lines |
|---|---|---|---|
| Watercolour lines across the board, screen built again | 12, `c4df6706` | none | 9, `b9173380` |
| The same, graphics lost and restored | 12, `c47b5417` | none | 9, `e3acbc3f` |
| Ink line + watercolour line + wet-sheet wash across the board, screen built again | 18, `9aa3683f` | 18, `003547d8` | 18, `38983045` |
| The same, graphics lost and restored | 18, `72e81867` | 18, `560ca22b` | 18, `d442cd3f` |

  Not told apart by this check: which of the lines sit on the frame's own cel and which on the shared canvas (the digest
  covers every cel, it does not say which). Not done: a line edited on a frame after the reopen, playback, onion skin.
- **Stroke timings of the Watercolour line brush, same runs (finger-like replay, Note 9; each run a fresh process).**
  The first stroke after an INSTALL waited 1.37 s for its first frame, once (the phone compiling the brush's programs
  for the first time); after an ordinary start the first frame comes in 1 to 16 ms. In every run the first stroke has
  one slow frame of 0.2 to 0.4 s; later strokes have a slowest frame of 60 to 290 ms. All through, a stroke of about a
  second has 6 to 30 frames longer than 34 ms (the ink pen on the same page: none). So the line draws correctly but
  not smoothly on the Note 9. For comparison the wet-sheet Round's first stroke has one frame of about 1 s (the sheet
  being made, known). These are the first recorded numbers for L9's "cold first stroke" and "performance"; nothing was
  tuned.
- **The stutter: fixed the same day (build efc4e8a41).** A stroke spent its frames in one place: every batch of dabs
  redid the brush's whole finish over every 256 px tile the dabs fell on (`GlPaintEngine.addWatercolourDabs` ->
  `WatercolourPass.finishInto`), though a finish only reads the coverage under a pixel and up to the edge and gravity
  widths away. It now redoes only the dabs' bounds grown by that reach (`WatercolourPass.reach`), on every stroke tile
  the rectangle touches (the old way missed a neighbour's rim until its next dab). The lift refinishes everything, as
  before, so the finished stroke is the one it always was. Note 9, same bench (`band0,band1,band2` x 3, brush
  `joybrush.watercolour`), old way by `--ez jb_wc_whole_tiles true` on the same build, each measured twice:

| | Old way | New way |
|---|---|---|
| Work in a frame, median | 61.1 and 59.5 ms | 13.5 and 13.4 ms |
| The dab step, median | 55.3 and 54.1 ms | 6.8 and 6.8 ms |
| Frames in a stroke | about 20 | about 85 |
| Frames over 34 ms in a stroke | 3 to 26 | 0 to 1 |
| Slowest single frame in a stroke | 68 to 515 ms | 188 to 331 ms |

  **Exactness, checked on the phone:** with `--ez jb_wc_finish_check true` every partial finish is followed by a
  whole-tile finish of the same tiles and the two are compared by fingerprint: 4,701 tile finishes, 0 differ.
  **The lift, same day (build 52ffadde2).** The one slow frame left in every stroke was the lift (phase
  `JB.paint.commit`, about 180 ms): the fade there runs between two pictures and both were finished whole for every
  tile of the stroke. The unpooled one is now a copy of the stroke tiles, which the change above keeps exact, and the
  pooled one is finished only where the stroke lies on each tile (`WatercolourPass.prepareFade`, the engine's
  `wcWithin`). Checked the same way: 120 lift pictures against whole ones, 0 differ (and the 4,701 partial finishes
  again, 0 differ). Slowest frame in a stroke: 188 to 331 ms before, 38 to 169 ms after, on the bench's wide zigzags,
  which fill most of their tiles; a thin line gains more.
  **What is left:** that one frame of about 0.15 s at the lift (the pooled finish itself), and the 1.4 s first stroke
  after an install. A stroke on a Tile board still finishes whole tiles (its wrap-around was left alone).
- **Still open for L9 (not done here).** the fade matrix and anything that needs a real S Pen (tilt, the pooling fade at pen-up, a
  drawn curve); **the grainy preset: owner's eye owed** (no file records an approved look; the orchestrator is asking
  him for a reference or a verdict); width redo exactness after the screens lane's 11-16 change; rebrush, delete,
  duplicate.

### 11-15 late: three Codex audit findings on the wet sheet (ENG-01, ENG-02, R2-JB-02)

**In plain words.** Three ways a watercolour wash could be lost or dried by something other than the painter, all found by
Codex reading the code, all still true this morning, all fixed.

- **A cancelled stroke could wipe the wash under it (ENG-01).** When the phone had no graphics memory to keep a copy of
  the wash for the next stroke's cancel, the stroke painted anyway, and cancelling it (a second finger) emptied the whole
  sheet. Now the older undo copies are given up first; if that is not enough the wash is set into the layer, where it is
  safe, and the stroke starts on a clean sheet. The price in that rare moment: the wash dries early and undo cannot go
  back past it.
- **Undo or redo could make the wash vanish (ENG-02).** After the canvas was turned between strokes, going back needed
  the sheet in its other shape; if that could not be made the history had already moved and the wash was gone from the
  screen. Now the history asks the sheet first, for undo and redo, and everything that can fail is done before anything
  moves. No memory: the press does nothing, the picture is unchanged, and the next press works once memory is back.
- **Renaming or locking a board dried the wash (R2-JB-02).** Every board edit set the wet wash. Now only edits that move
  paint or frames do (`core/doc/BoardChangePaint`, unit-tested).
- **Proof.** New bench case `wetSheetFailureProbe` (`WetSheetFailureProbe`, the real engine with the two failures
  injected), Note 9, build 18c20fc83, two runs the same: cancel with no copy, 277 of 83,098 wash pixels differ (0.3%, the
  early drying) and 2 stray; refused undo and refused redo change 0 pixels and then go through. All unit tests pass.
- **Not covered.** The old losses were confirmed by reading the code, not made to happen on the old build. The board
  fix has no phone check by eye. The double failure named in the routing file (`tasks/audits/codex/ROUTING_20261115.md`).

## 2026-11-15: the cause of the 10-31 lost squares (JBX-35), by the "Launch bug hunt" session

**In plain words.** On 10-31 four squares of the owner's painting turned black and a save stopped with "Board tile copy
failed". The cause is now made to happen on purpose on both test phones, and today's code does not do it.

- **What happened.** The realistic-paint engine went to sleep and freed its working pictures. A stroke then made new
  squares, and the phone's graphics driver gave them the very names just freed. When the paint engine was later closed it
  freed its sleeping pictures a SECOND time (`MediaLayerEngine.release`, fixed 11-01), which took the memory of the new
  squares that now held those names.
- **Why the 11-01 check did not show it.** Its test names were not the newest ones. The driver hands a freed name
  straight back only when it was among the newest (2000 of 2000 tries, 32 of 32 in a batch) and never in an
  oldest-first churn (0 of 50,000).
- **Proof (bench case `mediaFailureProbe`, fields `nameReuse` and `doubleDelete`; Fold = Adreno 730, Note 9 = Adreno 630,
  the same numbers on both).** 24 test squares all landed on just-freed names. The 10-31 order of events killed 24 of 24.
  Today's order killed 0.
- **A second fault found on the way, fixed here (8a48d687e, with the engine session's OK).** The check that runs before
  every save (`GlPaintEngine.repairLostTiles`) found only the FIRST dead square: 1 of 24, leaving 23 dead for the save to
  trip over. A square whose name is gone makes the driver refuse the test and keep the previous square in place, so the
  rest looked healthy. It now asks the driver about the name and the attach as well: 24 of 24 found and mended on both
  phones, none left dead, and the older `lostTileProbe` still passes. `tileReadable` got the same guard.
- **What this does not cover.** A mended square is blank: what it held is gone (the person is told, and each one is
  written to `files/joybrush-incidents.log`). The guard sees "name gone", not "name taken by another picture": only never
  freeing twice prevents that. No lost-square line has been written on the Note 9 since the log began on 11-13, but no
  long painting session has been run to look for one, and the owner's own pages have not been tried.
- **Places a second free could still come from, read and left alone (none seen to happen).** `WetSheet` History frees a
  snapshot when its count reaches zero or below, so an extra release would free twice (the counts read balanced);
  `MediaAtlasGl.release` has no guard against being called twice (its one caller clears the field);
  `GlPaintEngine.recycleLayerTex` cannot notice a stale retire after the name was handed out again.

**Handoff.** Branch `claude/jbx35-20261115`, landed on `joy-creator`. Files: `gl/media/TextureNameReuseProbe.kt`,
`gl/media/DoubleDeleteProbe.kt` (new), `gl/media/MediaFailureProbe.kt` (two report lines), `gl/GlPaintEngine.kt`
(`repairLostTiles`, `tileReadable` only). To rerun on a phone:
`phone.ps1 start com.fadcam.beta/com.fadcam.joybrush.JoyBrushBenchActivity --es jb_bench mediaFailureProbe --es jb_bench_brushes "'media:paste:Oil round'" --ei jb_bench_runs 1`
and read the `JoyBrushPerf` line. Next for whoever picks this up: a guard flag on the three places above if a second free
is ever seen, and a long mixed session reading the incident log.

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
- [x] The cause of the 10-31 tile failures (JBX-35): made to happen on purpose and shown fixed, 11-15 (section above).
- [ ] A long mixed painting session reading `files/joybrush-incidents.log`, to see whether any other cause of a lost square exists.
- [ ] When running water is pinned far from the pen, the stroke is still refused in words ("too far from paint that is still wet"). Rare; the water should be set aside instead.
- [ ] **Streaming saves** (second review E4), then raise `LayerBudget.MEDIA_RAM_SHARE`. Launch-critical with the lost
      squares (orchestrator, 11-13: work loss matters more than speed). Multi-day, in the save path; not started.
- [x] **Wet sheet on layers with editable lines** (ledger C6): built and landed 11-16, Note 9 and Fold bench; owner test owed (section "11-16: watercolour on a layer with editable lines").
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

## 2026-11-16: open as it is read, the paint state (streaming saves item 2, first half), handoff

**What changed.** A drawing's realistic-paint state (the media tiles, most of a painted drawing: about 3 MB a tile) is no
longer held whole in memory between reading the file and putting it on the canvas. `JbArchive.openDeferringMedia` reads
and checks every byte exactly as `open` does (the same code, so the same refusals in the same words, before anything is
shown), lets each media tile's bytes go once checked and notes its entry name, length and CRC. The contents it returns
hold `io/DeferredMedia` in place of the media map: each tile is read again from the held-open file when the canvas asks
for it and checked against the first reading (a difference is an error, never other bytes). `JbCanvasView.load` takes one
tile's stores at a time. **No change to the file format.** `JbArchive.open` and `read` behave as before.

**Where it is used:** the screen's own start-up open (`DrawingHistory.readWorking`), a drawing picked to open
(`StagedDrawing.read`) and the restore after lost graphics (`DrawingRecovery.read`). The recovery copy of a drawing opened
this way is a copy of its file (`DrawingRecovery.rememberOpened`), not a second save that would read every tile back.

**When the two readings cannot be matched** (the zip's directory does not say what the entries held, or the file cannot be
opened a second time), nothing is refused for it: the drawing is opened whole, the old way.

**A tile whose paint state cannot be read again during the load** (the disk failed after the check): that tile keeps its
picture and gets no paint state at all, never half; it is counted, written to the incident log and said to the person
("Part of this drawing's paint could not be read back..."). The pictures are still read whole before the load, so the
screen is never left with half a drawing; there is no second try from the `.bak` for those tiles. By reading only: this
path was not made to happen on a phone.

**Proof.** Unit tests, cloud, `jb-androidkit`: 1368 of 1368 (new `DeferredMediaTest`, 9 cases: same drawing as `open`,
same refusals and words, a save over the file, another file at the path, the directory check, the recovery copy, the file
gone). The lifecycle gate on the Note 9, build 96f5ce2fd, digests equal in all five cases (pictures and paint stores):

| Case | Result |
|---|---|
| Full page of oil (264 + 256 tiles), screen built again | same |
| Full page of oil, graphics lost on pause, restored | same; restore 6.9 s |
| Full page of oil, leaves while a save is being written, graphics lost | same; restore 7.0 s |
| Ink + pencil + oil (15 + 57 tiles), screen built again | same |
| Ink + pencil + oil, graphics lost on pause, restored | same; restore 1.1 s |

Before and after on the full oil page, same phone, same hour, the same script (the build before was e9f772e63):

| | Before | After |
|---|---|---|
| Restore after lost graphics | 6.0 s | 6.9 s |
| App's Java heap, highest 2 s sample, graphics-lost case | 291 MB | 224 MB |
| App's Java heap, highest 2 s sample, screen-built-again case | 479 MB | 454 MB |

The restore is about a second slower: the paint state is now inflated on the GL thread during the load instead of on the
file thread before it. It stays far under the screen's 30 s watchdogs. The heap figures are samples every 2 s over the
whole case (painting, saving, opening), so they are a rough ceiling, not the open alone; the screen-built-again peak is
set by the save on the way out, which this change does not touch.

**Limits.** One run of each case, Note 9 only. The bench `reload` step was not run (it loads a snapshot already in
memory, not a file). Not tried: the owner's own drawings, a drawing picked from the Library on a phone (unit-tested only),
a failing disk. The picture tiles, looks, slabs and strokes are still read whole.

**What is left of item 2.** (a) The picture tiles the same way (they are a twelfth of the paint state per tile, and
`LineOpenPlan` and the refusals read them, so it is a wider change). (b) **Raising the paint room will need the load split
over several GL tasks**: one task that inflates and uploads everything grows with the drawing and must stay under the 30 s
watchdogs (`recoveryTimeout`, and the Open path's `ui.postDelayed(watchdog, 30_000L)`); today 264 MB of paint restores in
about 7 s on the Note 9.

## 2026-11-16: pixels a board edit moves keep their paint state (Codex R2-JB-03), handoff

**What changed.** A duplicated animation frame (landed earlier today, e714eed45) and now every pixel a board edit MOVES
(sprite cells swapped, an animation board dragged, any `transfers` and `clears` of `applyBoardChange`) take their
realistic-paint state with them. Before, the picture moved and the paint under it was dropped ("until slice 3b carries
it"): pencil, oil and old-engine water could not go on working the moved part.

**How (`GlPaintEngine.applyBoardChange`).** Next to the pixel staging, each destination tile gets a whole staged set of
stores: copies of what it holds, and where it has no ground of its own, a ground made from its look before the edit (a
tile with no paint state has its look as its ground). The moved rectangle is copied into every store, shifted exactly as
the pixels are, from the live stores; a source with no paint state brings zeros and its look as the ground; a clear zeroes
the rectangle. Nothing is staged where neither side has paint state, so moving plain pixels costs nothing. After the
settle the staged stores take their tiles' places, one change per store tile (its `before` is what the settle dropped), in
the same undo step; they agree with their look, are stamped for the paint room like any written tile, and whoever holds a
copy of those tiles (the media window, the atlas) is told to reload (`reportMediaRestored`). `JbCanvasView.editBoards`
writes the newest paint back first (`mediaCanvas.releaseWindow(printSheet = false)`), only for an edit that moves, copies
or clears pixels; a rename or a lock leaves the atlas be.

**Proof, Fold and Note 9, build 295bbb0b5, the same results on both.**

| Bench case | Result |
|---|---|
| `cellPayloadProbe`, sprite cells swapped across a tile edge, paint state on one tile only (a throwaway engine) | 131072 pixels, every store and the ground, 0 wrong; 1 undo step; none dropped as stale; Undo and Redo exact |
| `cellPayloadProbe`, an animation board dragged onto part of itself across four tiles | 262144 pixels, 0 wrong; 1 undo step; Undo and Redo exact |
| `cellSwapLiveProbe`, the real canvas with the atlas: oil in the left cell, the cells swapped at once, oil again on the moved paint | the paint is in the right cell (11778 px on the Fold, 12067 on the Note 9), none left behind; the right cell HOLDS paint state; the second stroke paints, nothing refused; Undo of the stroke = the picture after the swap, Undo of the swap = the picture before, Redo of both exact (0 px off, four times) |
| `framePayloadProbe`, `nativeFrameDuplicateProbe` | still pass |

The oracle of `cellPayloadProbe` is one rule checked for every pixel: the paint state moves as the pixels do, and a tile
with none has its look as its ground.

**Limits, said plainly.**
- No save and reopen of moved paint was run. No watercolour, no real pen, no mask, no linked frames, one layer.
- No failure was injected (memory running out part way): by reading, everything made is taken out and given back.
- **No paint-room check for the staged stores.** The old stores stay in the undo step, so dragging a large painted board
  holds both until the step is trimmed.
- A paint-state tile with no picture tile under it is not moved (the slices come from the picture's tiles).
- The probes were not run against the code before the fix (the engine's own comment said the paint was dropped).
- The tested build is the commit before the last rebase onto `joy-creator`.
- For "open as it is read" (section above): a tool that runs the archive code on Windows cannot save over a file whose
  deferred paint state is still held open (Windows will not rename an open file; Android does). One unit test assumed
  otherwise and failed on the build PC; the engine session corrected the test.

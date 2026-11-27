# JBX-36 streaming saves: where it stands, and the plan (11-27)

Asked: are big drawings still saved whole in one go, or already streamed? Read against `joy-creator` at d2a631053.

## Verdict: half done; the half that is left is large

**Built since the row was written (11-13), all on `joy-creator`:**

| Piece | What it does | Where |
|---|---|---|
| Rewrite only what changed | An entry the last save wrote is carried from the old file as its compressed bytes lie; a full page saved again takes 0.7 s, not 6.9 s | `JbArchive.saveCarrying`, `ZipCarry`, `SaveMemo` (11-15) |
| Read back only what changed | The GPU fingerprints every tile; only tiles whose fingerprint moved are read back; the pen waits 130 ms on a full page, not 480 ms | `TileFingerprint`, `gl/TileHasher`, `snapshotSparse` (11-16) |
| The same after an open and on leaving | A drawing opened from its file saves by what changed from its first save on; the save on leaving reads with fingerprints too | 1e46ece1f, 86bead19a |
| Open as it is read | Paint stores, then picture tiles, stay in the file until the canvas takes them one at a time | `DeferredMedia`, c84c74c60 |
| Recovery copy | The working save is kept for a restore by holding the saved file open, not by copying it | ad164507d, e5d930c35 (JB-SAVE-IO) |

**Still true today, so the row stays open:**

- **A save still gathers everything it writes in memory, then writes it.** `readContentsSparse` reads every changed
  tile's bytes into `JbContents.tiles` / the media tile map in one GL task, and `saveCarrying` copies that map before
  writing (`JbArchive.kt` 666-671). The heap holds every changed byte at once. Usually that is small (five to twenty
  tiles a stroke), but in three cases it is everything: the first save of a new drawing, a big undo or redo, and any
  save without a memo (a copy, an export, a hand-off).
- That is why the caps are where they were: `SAVE_PAINT_MAX_BYTES` is 128 MB (paint beyond the newest 128 MB is saved as
  its picture and opens set), and `MEDIA_RAM_SHARE` gives 256 MB per 6 GB. Its own comment still says it "can't simply
  rise: a save holds every store in the app's 512 MB heap at once". Measured 11-15: all 264 MB of stores saved whole
  took 352 MB of the 512 MB heap.
- `JbArchive` read limits are unchanged (`MAX_TOTAL_BYTES` 1 GiB, `MAX_ENTRIES` 20,000), and the atlas's own memory
  is still not counted in admission (second review E3). Not read in depth today.

So, in plain words: a save is now quick when little changed, but a save where everything changed still has to fit the
whole paint room in memory at once. Until it doesn't, the room cannot grow and paint past 128 MB is kept as its picture.

## Why it is large, not a quick fix

A real streaming save reads tiles in batches and writes each batch before reading the next. Three things make that hard:

1. **The file must be one instant.** Today the readback is one GL task, so no stroke can land in the middle. Batches
   over several frames let a stroke land between them, giving a file with half a stroke. Stamping every tile a stroke
   writes was rejected on 11-16 for good reason: the engine writes tiles in more than forty places.
2. **Leaving the screen.** The save on leaving and `keepPausePicture` (JBX-37) read the whole drawing before the
   surface goes, because no frame runs after it. A save spread over frames has to finish before the graphics context can
   go, or keep what it has not read yet somewhere that survives.
3. **It touches every hot file at once:** `GlPaintEngine` (readback), `JbCanvasView` (snapshot, pause picture),
   `JbArchive` (writer), `JoyBrushActivity` (the save queue, `WorkingSaveBarrier`). Those are the paint engine and the
   save path, the two things the standing rules guard most.

## The plan (proposed; nothing built)

**Approach: freeze on the GPU, read in batches.** In the one GL task a save already takes, copy every tile the save must
read into a snapshot texture of its own (a GPU-to-GPU copy: graphics memory, no heap; its cost per tile is not measured yet and is the first number S2 takes). The drawing is then
free to change. A batch of snapshot textures (about 16 MB) is read back each frame and handed through a queue that holds
at most two batches to the save thread, which compresses and writes them, then frees them. The heap holds at most two
batches whatever the drawing's size. The file is the same archive, entry for entry, so nothing on the reading side
changes.

| Step | What | Done when |
|---|---|---|
| S1 | `JbArchive` writes from a tile source in batches (an interface: entries in order, bytes on request), carrying as now. The current map-based save becomes one such source | Failing first: a JVM test with a source of 300 fake 1 MB tiles and a heap meter fails on today's code (all 300 held) and passes at two batches or fewer; files byte-identical to the map save (`JbArchiveTest`, `JbArchiveCarryTest` all green) |
| S2 | GPU freeze: `GlPaintEngine` copies the tiles to read into a snapshot pool in one task; `readContentsSparse` reads from the pool in batches | Failing first: a stroke made between two batches does not reach the file (fake engine on the JVM; then the bench on the Note 9: save, stroke during the save, the file's digest equals the digest taken at the freeze) |
| S3 | Leaving and losing the graphics: the save on leaving drains the pool before the context goes (at most 3 s, as `surfaceDestroyed` already waits); if it can't, the save is refused and the last good file stands, as today | Note 9 lifecycle gate (`jb_bench_life`: recreate, cover, `joybrush_test_recreate_context`) on a full room: digests equal on both sides |
| S4 | **Kill mid-save**: the process killed while batches are being written | Failing-first unit test: the writer abandoned after N batches leaves the old file byte for byte and the next save cleans the `.tmp`. On the Note 9: `am kill` (not `force-stop`; never `monkey`) during a big save, then reopen: the older save opens whole |
| S5 | Raise `SAVE_PAINT_MAX_BYTES` to the room, then `MEDIA_RAM_SHARE`, then the read limits; count the atlas in admission (E3) | Each step measured on the Note 9 (heap peak, pen wait, write time, file size), as in the 11-15 table; a full room saved and reopened with every tile exact |

Each step lands alone, with all JoyBrush suites green in the cloud and the old path one switch away
(`ArchiveCarrying`-style), so main is never broken and a bad step can be turned off without a build.

**Before building:** the save path and the paint engine are claimed on BOARD by the "atlas solid" session (row dated
11-13, "when streaming saves start: I will say so here first"). That claim must be released or handed over first. The
orchestrator also has to say whether the leave-screen rule in S3 (refuse and keep the last good file, never a half
file) is acceptable, because it is the one place the artist could lose the newest few seconds of work.

## Progress

- **11-27, S1 landed (switch `ArchiveCarrying.streamTiles`, off):** the archive checks a drawing by tile lengths and
  takes each tile's bytes only when it writes them (`io/TileStream.kt`); a streamed drawing is held one tile at a time
  and the file has the same entries and bytes. `DeferredMedia` is a stream, so a just-opened drawing saves from its file
  tile by tile. Failing first: `JbArchiveStreamTest` (4 tests, all 120 tiles held at once before).
- **11-27, S4 landed (switch `ArchiveCarrying.finishInterruptedMove`, off):** a kill between the save's two moves (file
  moved to `.bak`, `.tmp` whole and synced, last move never ran) now opens the newest save from `.tmp` and finishes the
  move; before, it opened the save before it from `.bak`. A `.tmp` that does not open is never moved into place.
  `KillMidSaveTest`: that case failed first; a kill while writing and a half `.tmp` already kept the last save (guards).
  Still owed for S4: `am kill` during a big save on the Note 9, once S2 streams real saves.
- **S2** (the GPU freeze and batched readback) is next, with a brief for a fresh session. **S3** follows (owner 11-27:
  about 3 s of lost work at leaving is acceptable). **S5** waits for Note 9 measurements of S1-S4.

# Atlas A2 probe scout — 2026-10-27 (read-only)

Repo: `C:/Temp/jb-atlas-room-repair-20261025`, branch `lead/atlas-room-repair-20261025`, tip `52dbb2716`.
Tags: [verified in code] = read or grepped this session. [doc only] = stated in a document, not re-checked.
Paths below are under `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/` (short form `gl/media/...`) unless noted.

## 1. Park ledger, admission, commit, writeback: where it lives

All of this is in `gl/media/MediaAtlas.kt` (1112 lines) unless noted. The residency driver and the backing interface are abstract; the park ledger itself is GL-bound.

| Piece | Location | GL or abstract |
|---|---|---|
| `class MediaAtlas(paint: GlPaintEngine, media: MediaLayerEngine, renderLook, dryTiles, renderLooks)` | L29 | GL (concrete) [verified in code] |
| Ledger state: `parked` (key→park unit), `parkedNext`, `borrowedBlockTiles`, `groundFromLook`, `park: MediaWaterPark?`, `parkedTotal` | ~L55-66 | Maps only, but owned by GL class [verified in code] |
| `ParkOwnership`, `ParkAdmission` (snapshot + takenUnits/deferredUnits) | L67-84 | Pure data [verified in code] |
| `backing` = the real `MediaAtlasBacking` implementation | L426-438 | GL [verified in code] |
| `loadSlot` (payload stores, look, ground, paper) | L441 | GL [verified in code] |
| `beginParkAdmission` | L491 | Bookkeeping [verified in code] |
| `commitParkAdmission` (gives back consumed and deferred units) | L504-518 | Calls `MediaWaterPark.give`, HashMap ops, `ArrayList` [verified in code] |
| `prepareParkAdmissionCommit` (allocates `IntArray` release list, `checkCanGive`) | L521-540 | Pre-validation [verified in code] |
| `rollbackParkAdmission` (restores maps, `setResidency`, returns taken units, releases created pool) | L542-573 | Partly GL (`media.setResidency`, pool release) [verified in code] |
| `takeParkUnit` / `giveParkUnit` | L575-587 | Pool calls [verified in code] |
| `parkIfLive` (eviction: the victim goes to the park if its water runs) | L589-613 | GL via `media.parkSlot`/`parkHalf` [verified in code] |
| `blocks` = `WaterBlockHost` (`fitsWhole`, `enter`, `leave`, `stepDone` L651, `jobDone` L657) | L615-666 | Mixed [verified in code] |
| `writeBackSlot` (per-store `writeStoreTile`, then ground blit) | L711-727 | GL, **not atomic** [verified in code] |
| `flush` (frame boundary, calls `writeBackSlot` through `writeStores`) | L729 | GL [verified in code] |
| Global switch `MediaAtlasPark.units` (0 = off) | L1109 | Global [verified in code] |

Park pool: `gl/media/MediaWaterPark.kt`. `free = ArrayDeque<Int>()` (L16), `take()` (L25), `give()` with `require` (L30, can throw), `checkCanGive()` (L33), `grow()` creates GL textures (L44). The free list is pure logic, but the class is in an Android GL file, so no JVM test can construct it. [verified in code]

Park copies: `gl/media/MediaLayerEngine.kt` `parkSlot` (L507), `unparkSlot` (L523), `parkHalf` (L537), `loadHalf` (~L560), `parkCopy` (L571, GL blits). Slot count is `slotCount = (w/SIDE)*(h/SIDE) - 1` (L480). [verified in code]

Pure JVM (core): `core/media/MediaAtlasSlots.kt` (`begin` L46, `commit` L64, `cancel` L72), `core/media/MediaAtlasPlacement.kt` (`slotGrid` L78, `fits`), `core/media/WaterParkPlan.kt` (`bytesPerTile`, `units`). [verified in code]

## 2. Residency tests and their fake backing

- `androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaAtlasResidencyTest.kt`: 8 `@Test`s (L79, 87, 102, 117, 133, 141, 175, 193). JVM unit test, no GL. [verified in code]
- Fake: `FakeBacking` (L15-70). Models a page as a `String` in `pixels[slot]`, with `durable[page]` as the stored copy. Fault flags: `failLoad`, `failWrite`, `failEvicted`, `failPrepare`, `failBegin`, `failRollback`. [verified in code]
- The doc says these tests exercise the generic residency lifecycle, not the GL park ledger. Confirmed by reading the file. [verified in code] + [doc only] for the admission checkpoint, `ATLAS_ADMISSION_CHECKPOINT_20261026.md`.
- Also in the test tree: `MediaAtlasDriverTest.kt` (shader text only, 4 tests). Core commonTest: `MediaAtlasSlotsTest`, `WaterParkPlanTest`, `WaterBlocksTest`, `MediaAtlasPlacementTest`, `MediaAtlasWaterTest`. [verified in code]

What a real-GL backing would need: `MediaAtlasBacking` is already implemented by `MediaAtlas.backing` (L426). A GL test would need a live GLES context, so it cannot run on the JVM. It must run on device, through a bench case. [verified in code] (reasoning from the GL dependencies above)

## 3. Existing probe and bench cases

- `gl/media/MediaAtlasProbe.kt` (case `mediaAtlasProbe`): window, atlas, scrambled, broken, pasteCopy, banded, catchUp, parked. SIZE 768. Compares raw float bits (`readFloatBits`) and RGBA8 (`readBytes`) via `snapshot`/`diff` (L242-296). The `parked` kind uses `MediaWaterPark` and `e.parkSlot` directly (`parkRoundTrip`, ~L75). It does **not** use the `MediaAtlas` ledger. [verified in code]
- `gl/media/MediaAtlasBlockedProbe.kt` (case `mediaAtlasBlockedProbe`): 4x4 slots + zero row (L40), its own `ProbeBlockHost` world (L135), not `MediaAtlas`. Per-plane pixel counts in `compare` (L93). [verified in code]
- Dispatch: `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/bench/BenchRunner.kt` L118-133. Runners in `JbCanvasView.kt` L2819 and L2832, debug-only gate, `onGl`, use the private `engine` (L436) and `engine.mediaPaper()`. [verified in code]
- No hashing in the atlas probes. Raw bit compare only. SHA-256 is used by the Recorded* probes (`RecordedColdPayloadProbe.kt` L111), not the atlas ones. [verified in code]
- Canonical `b29573d9e`: has the `mediaAtlasProbe` case (BenchRunner L145), no `MediaAtlasBlockedProbe.kt`. [verified in code, `git cat-file`]
- Grep for "atlas" in BenchRunner and `androidkit/gl`: no case drives `MediaAtlas` park/unpark/commit/rollback directly. [verified in code]

Knobs that exist (BenchRunner Spec L47-72, intent parse L374-381):
- `jb_media_atlas` → `canvas.mediaAtlasEnabled`
- `jb_water_park_units` → `MediaAtlasPark.units` (park on)
- `jb_media_budget_mb` → `canvas.mediaBudgetBytes` (GlPaintEngine L5584, L5793). **This does not set atlas capacity.** It only refuses media writes past the budget.
- `jb_water_jobs`, `jb_atlas_rows`, `jb_drop_dry`

Forced capacity today: none in the app path. `MediaCanvas.kt` L444-458 sizes the atlas from memory (`MediaAtlasPlacement.slotGrid(avail, perSlot)`, `init(cols*SIDE, rows*SIDE)`). The only hard-coded small atlases are the two probes above. [verified in code]

## 4. Proposed probe (concrete)

Name: bench case `mediaAtlasLedgerProbe`. Pattern: copy `runMediaAtlasProbe` (JbCanvasView L2832) and `BenchRunner` L128-133.

Forced capacity, one new bench knob: `jb_atlas_slots=cols,rows`, read in `MediaCanvas.kt` L455 in place of `kept ?: fresh`, debug-only. Clamp to `cols*rows >= 4` (the zero slot plus enough for the frame). Log `atlas N x M slots`. Alternative that avoids the app path: build `MediaLayerEngine(atlasMode=true).init(cols*SIDE, rows*SIDE)` directly, as the blocked probe does.

Park: `jb_water_park_units` at least the wet footprint, so eviction parks rather than writes through.

Drive: real strokes through the real canvas, as `wideWash` and `marathon.sh` do, with the window engine run first as the reference (`jb_media_atlas false`). Each run is a fresh process.

Phases (each step compared, on Note 9 only):
1. Baseline: window, the same strokes, checksums after each `flush(stores=true)`.
2. Park on, capacity forced below the working set. Require `parkedTotal > 0` and `evictions > 0`. Log park occupancy. (Counters not exposed yet; expose `parkedTotal`, `park.used`, park refusals, and admission rollback counts.)
3. Odd and even steps, own → eviction → ring reload → flip → second eviction, and ring → own promotion (the repair doc's list). Dirty and clean victims, input and no-input boundaries.
4. Injected failures: the repair branch has **no** fault hook in `MediaAtlas` (grepped the park, commit, writeBack paths). A debug-only `MediaAtlasFault` consulted in `writeBackSlot`, `prepareParkAdmissionCommit`, `commitParkAdmission` is needed. Then require: cancelled admission leaves maps and pool identical, no GL error, retry succeeds.
5. Compare after every step against the window, pixel by pixel:
   - state planes (p0, p1, paper, w0, w1, flux, run) as raw float bits, via the `MediaAtlasProbe.snapshot` and `unscramble` pattern (L242, L253);
   - RGBA8 look via `readBytes`;
   - per-tile SHA-256 over the 256x256 bytes as a summary only, with the first mismatch coordinate for triage.
   Require 0 differing pixels, changed-wet-pixel count > 0, and `glGetError()` = 0 after every phase. Include negative and far tile coordinates and halo/corner transport.
6. Save, undo and reopen equivalence: not covered by this probe (doc says it needs separate production-host proof).

Cost estimate: one new Kotlin file (~250 lines, modelled on `MediaAtlasBlockedProbe`), a runner in JbCanvasView, a case in BenchRunner, one knob in MediaCanvas, one fault hook. Build and install are owed.

## 5. Open TODOs and gates in the repair branch docs

From `tasks/joybrush/reviews/ATLAS_PARK_OWNERSHIP_REPAIR_20261025.md` [doc only]:
- Compile the exact source; count the XML independently.
- Exercise the real `MediaAtlas` host, not only `ProbeBlockHost`, with forced nine usable resident slots and a seeded multi-block world.
- Prove own → eviction → ring reload → flip → second eviction, and ring → own promotion, for odd/even steps, dirty/clean victims, no-input/input.
- Compare raw current/next paint, paper, water, flux and run planes against canonical after each step; changed wet pixels, real evictions, zero GL errors; negative/far coordinates; halo/corner transport.
- Borrowed tiles never publish partial stores; FULL writes still occur.
- Interrupted jobs, pinned refusal and load failure preserve ownership.
- Phase/look coherence, P3 finished-evicted persistence, P4 exhaustion, resource admission.
- Save/undo/reopen equivalence and operator/look correctness, from independent production-host evidence.

From `ATLAS_ADMISSION_CHECKPOINT_20261026.md` [doc only]:
- Real park-ledger injected failure and allocator ownership proof.
- Commit hook no-throw / no-allocation guarantee. Code note: `commitParkAdmission` calls `MediaWaterPark.give` (`require`, can throw) and HashMap/ArrayDeque ops (allocation). `stepDone` L655 calls `p.give` without the `checkCanGive` pre-check. [verified in code]
- Atomic durable `writeBackSlot`. Code note: stores are written one at a time (L711-727), so a GL failure mid-way leaves a partial durable state. [verified in code]
- Production-host forced-capacity raw-plane GPU equality; phase/look publication; parked finished result persistence; save/reopen/undo; exhaustion/resource gates.

From `ATLAS_ROOM_20261023.md` [doc only]: P3 (look, stores, drying for parked water) and P4 (sizing) are not started. P2c design (§5e) says `MediaAtlas` is the block host; the code at L615-666 matches that design. [verified in code for the L615-666 match]

Coordinator note: the two A2 definitions in the scout (GL dry driver vs room/residency) still apply. This probe proves the room/residency one.

## Lead verification (2026-10-27)
Read MediaAtlas.kt:491-560 on 52dbb2716: `commitParkAdmission` can still throw (`checkNotNull`, and `MediaWaterPark.give` uses `require`), while `prepareParkAdmissionCommit` already pre-checks every release with `checkCanGive` and duplicate detection. So commit can only throw if state changes between prepare and commit. A no-throw commit means the pool keeps a `giveChecked` path that skips the re-check after prepare. The scout's probe shape (real MediaAtlas, a forced-slot knob, an injected-failure hook, raw-plane equality) is adopted as the A2 plan.

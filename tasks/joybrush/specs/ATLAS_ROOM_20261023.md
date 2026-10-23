# Atlas room: water that runs slower when there is a lot of it, never refuses (plan, 2026-10-23)

Helper 1 for the engine Lead (handoff 10-23 §4A). This is a plan only: no engine code until the Lead has checked it
against the water-jobs code.

## 0. The problem, in numbers

The refusal is "This watercolour stroke reaches further than this phone can hold at once".
- **Where it comes from:** `MediaAtlas.admitWater` → `MediaAtlasWater.plan` returns null.
- **Why:** running water must keep every wet tile, and the 8 tiles round it, resident in ONE atlas for every step
  (`mustStay`).
- **The arithmetic:**
  - A slot is 227 B/px, 14.9 MB (`MediaAtlasPlacement.BYTES_PER_PX`).
  - The Note 9 gets 24–64 slots, set by free memory when the engine is made (34–48 on most days).
  - So a 4 × 4-tile wet area plus its ring (6 × 6 = 36 slots) fills it.
  - Zoomed out, one stroke wets 4× the tiles per screen.

No per-byte saving closes that, because the wet area grows with zoom². The water has to stop needing all of itself at
once.

## 1. The idea: two levels, with steps run block by block

**L1, the working atlas.** Today's atlas, all 227 B/px planes, unchanged. It runs every pass.

**L2, the water park.** New. A compact GPU store holding only what a wet tile must keep exactly between steps, at full
precision:

| Plane | Format | B/px |
|---|---|---|
| p0, p1, paper | RGBA32F × 3 | 48 |
| w0, w1 | RGBA32F × 2 | 32 |
| flux | RGBA16F | 8 |
| run | RG16F | 4 |
| Brush input (only while it is pending) | RGBA16F + RG16F | 12 |

That is about **92 B/px, 6 MB a tile, 2.5× cheaper than a slot.**
- No scratch planes, bakes, bead, look or ground: those are rebuilt (bakes) or stored in the layer (ground, look).
- A parked tile does NOT need its ring parked.

**Block-major steps when the job does not fit L1.**
- A step is run block by block (a block = b × b tiles, b = 1–3 as room allows). For each block:
  1. Make the block and its 1-tile ring resident in L1, as the a-half (last finished step), from L2 or the layer.
  2. Run every stage of the step over sub-rectangles that shrink toward the block:
     - update over the block;
     - flux over block + `UPDATE_REACH`;
     - bead2/bead1/bead0 over that + `fluxReach` + the blur, in their own grids.
  3. Write back to L2 only the block's own b-half (water, flux, paint dst). The ring's temporaries are dropped.
- **Why halo recompute instead of the skewed stage sweep:**
  - No stage output ever crosses a block boundary, so nothing partial needs parking.
  - Each tile is loaded once per block it sits in or rings (2.25× at b = 2, 1.8× at b = 3).
  - The recomputed halo is mostly the bead, the cheapest stage (`JOB_WEIGHT` 0.4); flux gains only 4 px a side.
  - A skewed sweep over tile rows would need ~6 rows × the job's width resident at once: more than L1 at the zoom that
    refuses.
- **Per-tile step progress** (the Lead's "write back the right half"):
  - During step k, a tile's a-half stays intact (neighbouring blocks still read it as ring). Its b-half is written
    once, by its own block.
  - The water's global flip (`wc`) happens when every block of the step is done, exactly as today.
  - Mid-step, finished tiles hold both halves in L2: at most about one block row of them.
- **Exactness argument:**
  - Every pass is the same shader over the same pixels with the same uniforms. Stage uniforms keep describing the whole
    job rect, as bands do today: `runStage(area, band)`.
  - Every input a stage reads is the previous stage's output computed over a covering rect, or the a-half, which nothing
    writes during the step.
  - Redundant halo writes write identical values and are never kept. `banded` already proves sub-rect passes are
    bit-exact; this generalises bands to 2-D blocks with per-stage halos.

**When everything fits L1 (today's drawings), nothing changes:** the job runs in bands as now and L2 is never made.
Block mode switches on only where the refusal would have come.

**More than one job a frame** (the Lead's change):
- Admission is per item, not per frame: the frame's own passes (input dabs, paste, look) hold the pen's tiles; each job
  block holds its own block + ring while it runs.
- So a second job, a grown rect, or a job spanning many frames only adds items; it never adds to what one moment must
  hold.

## 2. What the person sees

- No refusal for water, zoomed in or out.
- With a lot of water out of core, the water runs slower: fewer steps per second.
  - Estimate: copies ~0.8 ms a tile load; a 60-tile pool ≈ 1.3–1.6× a step's time.
  - The pen stays at today's pace: the brush mark shows the frame it is drawn (water jobs).
- No creases:
  - pools are never cut by the pen or at tile edges;
  - a block's water is the same water, run in a different order;
  - the probe proves it bit for bit.
- Old paint is never flattened. Parked water keeps running, steered by tilt, and new strokes mix with it.

## 3. Phases (each proven before the next)

**P0. Measure (no code).** On the Note 9:
- `marathon.sh` and a zoomed-out wide wash (new bench case `wideWash`: one tilted Wash scribble over ~8 × 5 tiles at
  0.5× zoom).
- Log the atlas size (`atlas N x M slots (available=…)`), the refusal count and where (frame vs water), the peak wet
  tiles and ring tiles, and the water px-steps/s.
- Interleave A/B, pairs only. This is the baseline for P2/P3.

**P1. L2 and exact eviction.**
- New `gl/media/MediaWaterPark.kt`: compact textures, a slot list, and park/unpark per tile by GPU copy
  (`glCopyImageSubData`, GLES 3.2 on the Note 9; a blit-pass fallback).
- New pure `core/media/WaterParkPlan.kt` (JVM-tested): which planes, when both halves.
- `MediaAtlas.backing.writeBack` / `loadSlot`: a tile whose water is live goes to and comes from L2, not the f16
  stores. Today such a tile is never evicted ("water never leaves"); paused pools then can leave exactly.
- `MediaLayerEngine` gets slot ↔ park copy helpers only (no job code).
- **Proof:** new probe kind `parked` (L1 = minimum, paused pools forced out and back) == window, 0 px.
- **Room win alone:** paused and far pools stop costing slots.

**P2. Block-major steps.** Based on the Lead's push of `wetFrameInner` / `runJob`; I touch them only after it lands.
- New pure `core/media/WaterBlocks.kt` (JVM-tested):
  - blocks of a job rect;
  - each stage's sub-rect per block, from the reach constants (`UPDATE_REACH`, `fluxReach`, bead `m`, blur r);
  - residency per block (block + ring, minus `skip` and walls);
  - write-back set = the block's own tiles.
  - Tests: each stage's sub-rect covers every read of the next; union of update sub-rects == the job's cells with no
    overlap; walls respected.
- `MediaLayerEngine`:
  - `runJobInner` gains a block cursor beside band/stage (`WaterJob.block`);
  - `bandsOf` → `itemsOf` (bands when it fits, blocks when not);
  - `runStage` draws a sub-rect with the whole-area uniforms as now;
  - `stageArea` unchanged;
  - `startJob` asks the host whether the job fits (`onNeedWater` returns fit / out-of-core).
- `MediaAtlas`: `admitWater` returns "out-of-core" instead of refusing; `hold` per block; L2 holds the rest.
- Budget: a block is one item; its cost = its stages' px × `JOB_WEIGHT` + loads × a copy weight (measured).
- **Proof:** probe kind `blocked` (L1 forced to 9 slots: every step out of core, b = 1) == window, 0 px, on every probe
  stroke (tilted wash, drip tail, input mid-job, two jobs in one frame).

**P3. Look, stores and drying for parked water.**
- The water's look per block while its block + ring is resident: drawn into a parked look plane (4 B/px) and copied out
  to the layer all at once when every part is drawn. That is today's rule in `advanceWaterLook`, so tiles never show
  different moments.
- Stores at stroke end and `payOwedStores` from L2 (f32 → the layer's f16 store tiles, as `writeStoreTile` does from a
  slot).
- D1/D2 drying of parked tiles loads them first.
- **Proof:** probe == window 0 px with the look and stores compared; undo/redo after an out-of-core stroke restores bit
  for bit (existing undo-exact checks).

**P4. Sizing.**
- L1 shrinks to what a frame plus a block needs (≥ 24) when L2 is in use.
- L2 grows in chunks as memory allows (re-checked between strokes, so better days get more).
- Last resort when L2 is full: the existing whole-pool D1/D2, never by the pen, never a cut pool.
- A refusal stays only for a dry/paste frame wider than L1 (unchanged rule; `lookInParts` already covers the knife).

The handoff's cheaper levers (half-float w0/w1, a separate water atlas) stay parked. L2 already stores water at 92 B/px
without a precision risk, and half floats would need the owner's drying test.

## 4. Files I will touch

| File | Phase | What |
|---|---|---|
| core/media/WaterParkPlan.kt (new) | P1 | which planes, both halves when |
| core/media/WaterBlocks.kt (new) + tests | P2 | blocks, stage sub-rects, residency |
| gl/media/MediaWaterPark.kt (new) | P1 | L2 textures, park/unpark |
| gl/media/MediaAtlas.kt | P1–P4 | writeBack/loadSlot via L2; admitWater: out-of-core not refusal; hold per block; look parts from L2; stores from L2 |
| gl/media/MediaLayerEngine.kt | P1 (copy helpers), P2 (runJobInner / bandsOf / runStage / startJob, after the Lead's push) | |
| gl/media/MediaAtlasProbe.kt | P1, P2 | kinds `parked`, `blocked` |
| core/media/MediaAtlasWater.kt | P2, P4 | plan(): out-of-core before D1/D2 |
| bench (StrokeReplay `wideWash`, marathon logs) | P0 | |

Not touched: `wetFrameInner` until the Lead's change lands; window engine paths; shaders (none needed: sub-rects and
residency only).

## 5. How it is proved

- **Device probe `MediaAtlasProbe`:** window == atlas == scrambled == banded == parked == blocked, 0 px, after every
  phase.
- **JVM:** core `WaterBlocks` / `WaterParkPlan` tests; full suites (`:core:jvmTest :androidkit:test`, app).
- **Bench (Note 9, interleaved A/B pairs, slot count logged on every run):**
  - marathon and `wideWash`: refusals (target 0), strokes completed, peak wet tiles, L1/L2 sizes, water px-steps/s, and
    steps/s when out of core vs in core;
  - crowd: pen-to-frame p50/p95 (target: unchanged, ~30–50 ms);
  - every media brush flat + wet tilted (`ab3.sh`) vs a same-hour baseline: unchanged where it fits.
- **Owner's eye:** zoomed-out wash at his zoom, tilt-steering, and new strokes into old water. No creases, no refusals.

## 6. Risks

- **Copy cost.** GPU copies of 6 MB a tile; if `glCopyImageSubData` is slow on Adreno, use a blit pass. P1 measures it
  first.
- **Unified memory:** L2 comes from the same RAM, so the 700 MB reserve still applies, and P4's "last resort" is real
  on bad days.
- **Two-jobs-a-frame and grown rects:** covered by per-item admission. A job's rect is fixed at its start; a grown rect
  is the next job.
- **The look per block** must never show tiles at different steps: kept by the all-at-once copy-out, proven by the
  probe's look compare.

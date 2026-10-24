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

## 5b. The Lead's four questions (10-23), answered

**1. L2 cost per tile, mid-step.** The 92 B/px is ONE half (a tile at rest between steps).
- Mid-step, a tile whose block has run holds its a-half (still read as ring by the blocks after it) AND its b-half:
  - b-half = water 32 + flux 8 + run 4 + paint dst 48 = 92;
  - the input was eaten by step 0, so it is not in the b-half;
  - ≈ 184 B/px, 12 MB a tile.
- L2 is therefore a pool of 6 MB half-units.
  - A tile at rest takes 1 unit; a finished tile whose a-half is still read takes 2.
  - A tile's a-half is freed as soon as the last block whose ring covers it has run.
  - In raster order that is the block below-right, so the doubled set is at most (b + 1) tile rows × the job's width.
- Peak units = wet tiles + (b + 1) × W. For a 6 × 6 wash at b = 2: 36 + 18 = 54.
- Fix if that is too much: run blocks column-strip by column-strip, so W is the strip width, not the job width.

**2. One memory budget.**
- **Today:** L1 = slotGrid(free − 700 MB) at engine creation, everything beyond the reserve.
- **Split rule:** budget M = free − 700 MB (unchanged), shared between the two levels:
  - L1 = clamp(⌊0.45 M / 14.9 MB⌋, 24, 64) slots, made at engine creation as now;
  - L2 = what stays within M, allocated lazily in 6 MB units only when a pool does not fit L1.
  - Free memory is re-checked before each chunk of 8 units, and allocation never goes past M.
- **When nothing goes out of core:** L2 is never allocated, so 0.55 M sits unused. To keep today's behaviour there,
  L1 can instead be made at today's size and the engine re-created smaller (stores convert both ways already) the first
  time a pool needs L2, between strokes. **The Lead picks** (see the questions at the end).
- **On the Note 9** (today 34–48 slots, so M ≈ 507–715 MB):

  | | Today | With L2 |
  |---|---|---|
  | Wet tiles before whole-pool D1/D2, worst day (M 507 MB) | ~12–16 (4 × 3 wet + ring = 30 of 34) | ~26: L1 24 slots, L2 25 units (150 MB) |
  | Wet tiles before whole-pool D1/D2, best day (M 715 MB) | ~20 (5 × 4 + ring = 42 of 48) | ~50: L2 59 units |
  | Factor | 1× | 2–2.5× |

  The doubled set comes off the L2 units in both rows. That is about 1.5 screens of wet paint at 1:1, or one screen
  at ~0.7× zoom.
- **Honest limit: L2 alone does NOT make "never refuses" true at 0.5× zoom** (4× the wet tiles of 1:1). Past it, the
  last resorts are:
  - (i) whole-pool D1/D2 for quiet or oldest pools;
  - (ii) a slow tier: pools beyond L2 parked in CPU memory (glReadPixels into a direct buffer; same RAM budget, so
    only with M left) and run at a reduced rate;
  - (iii) the handoff's 4C: coarse water below 0.5× zoom (162 B/px, quarter-size water planes).
  P4 picks among these by measurement and the owner's eye. The refusal itself is removed in any case: (i) is always
  possible for water that is not under the pen.

**3. Copy cost and the speed out of core (to be measured first: P0/P1's `parkCopy` bench).**
- **Estimate before measuring:** Adreno 630 sustains ~10 GB/s for a texture copy, so ~1.2 ms per 6 MB half each way.
- **A 6 × 6 tilted wash (36 wet tiles, 2.36 M px per step), b = 2, 9 blocks:**
  - loads: 16 a block, with neighbour blocks sharing ring columns → ~100 loads + 36 b-half write-backs ≈ 160 ms of
    copies a step;
  - compute: the same step in core ≈ 100–165 ms (scaled from the crowd's 3.6 M px at 150–250 ms), plus ~25 % halo
    recompute;
  - **out of core ≈ 290–370 ms a step, against 100–165 in core: about 2.3–2.8× slower than the same pool in core.**
- **Against real time:**
  - a step is `wc0.dt` of water time (4 steps = one 30 fps frame of real time, so dt ≈ 8 ms);
  - in core, this pool already runs at ~1/12–1/20 of real time (the budget slices it, and `u_ageScale` keeps the
    drying in real time);
  - out of core: ~1/35–1/45 of real time.
- So it is **well below 1/4 real time**, and so is today's in-core wash of the same size. The fair yardstick is
  "out-of-core vs in-core for the same pool". If the measured copy cost puts that ratio above ~3×, I report it before
  building P2, with these alternatives:
  - bigger b with column strips;
  - copying only the planes each role needs (a ring tile needs only its a-half's read planes, about half);
  - the coarse tier for out-of-core pools.

## 5c. P1 result (Note 9, 10-23 19:02)

- Probe kind `parked`: every wet frame, all 9 tiles went to the park and came back in another slot.
  - p0, p1, paper, w0 and w1 were **bit-exact** on every stroke.
  - The look differed only because the probe left the ground behind. In the app the ground comes back from its `g`
    store; the probe now carries it, and a re-run is pending.
- Copy cost: **3.59 ms per tile, out and back**. That is 7 blits out, 13 in (both halves), plus the tile's paper bake:
  about 4.8 GB/s.
- The Lead accepted it: out of core only runs where main refuses today.

## 5d. P2 in detail (what each pass reads, from the shaders)

| Pass | Reads | Beyond its own pixel |
|---|---|---|
| bead 0 | w0 (a) | 2 x 2 px |
| bead 1, 2 | bead | ±r half cells in x, then y |
| flux | w0 (a), flux (a), p0 (src), bakes, bead | up to `fluxReach`, far drip taps up to ~48 px |
| update | w0, w1 (a), flux (b), run, p0, p1 (src) own, bakes, input own | ±1 and the radius-3 ring |

**A block's stage rects.** Each stage's rect is intersected with the stage's own area, as today:
- update = block ∩ cells;
- flux = (block + 4) ∩ cells;
- bead 2 = (flux / 2 + bead read reach) ∩ hr;
- bead 1 = (bead 2 + r in y) ∩ hr;
- bead 0 = (bead 1 + r in x) ∩ hr.

Uniforms keep describing the whole area, as bands do. Inside hr the bead values are identical to the global pass.
Within r of hr's edge both modes blur stale data, but the flux never reads there (the margin m = 3 + 2r exists for
exactly this), so stale slot contents never reach the water.

**What each tile needs loaded:**

| Tile | Planes | B/px |
|---|---|---|
| The block's own tiles | everything, into the a-half only | 92 |
| Ring tiles | w0, w1, flux (a-half), p0 (src) | 56 |

- Ring tiles skip p1, paper and run: no pass reads p1 or paper beyond its own pixel, and run is written by this
  block's flux before the update reads it.
- Bakes are made again for every load.
- The paint halves follow the step's parity (src = state[k & 1]), the water halves follow `wc`.
- The P0 ring copy (`copyP0Ring`) becomes per load: a ring tile's p0 goes into both paint halves.

**Probe `blocked`.** The 3 x 3 probe world cannot force eviction (a centre block's ring is the whole world). So a
separate probe pairs a 5 x 5-tile window with an atlas that has L1 = 9 slots + zero + the park, b = 1. Strokes go
through the middle 3 x 3, including the tilted wash, and the comparison covers the interior.

## 5e. P2c: the real atlas as the block host (design, 10-24)

The probe's `ProbeBlockHost` owns its whole world. In the app, `MediaAtlas` must be the host, inside its own rules:
slots, LRU, pins, stores, undo, look.

**Gap found in P1.** `MediaAtlasResidency.ensure` writes back a victim only when it is DIRTY. So a running pool
whose stores were just written (clean) and is then evicted never reaches `parkIfLive`: it comes back from its f16
stores without its flow. The fix: a backing hook for every victim (`evicted(slot, page)`, default no-op) that parks
live water whether or not the victim is dirty.

**Inside a block job, nothing may finish the job** (re-entry). `parkSlot` / `writeStoreTile` call
`finishWaterJob` / `finishWaterStep`, so during a block step an eviction:
- parks with `parkHalf`: current, plus next if the tile's block has run this step;
- skips the store write-back (the park holds the truth; the tile stays marked for its stores).

**Ownership.** `MediaAtlas` implements `WaterBlockHost`:
- **fitsWhole:** the job's tiles + ring + what the frame holds ≤ slots. Then today's path, unchanged.
- **enter:** own tiles loaded FULL (park if parked, else stores, as `loadSlot`); ring tiles that are not resident
  borrowed ring-only; all pinned until `leave`.
- **leave:** own tiles are done this step.
- **stepDone:** borrowed copies dropped without write-back, park next → current.
- **jobDone:** borrowed copies dropped.
- **Admission:** water that does not fit no longer refuses. `admitWater` returns only the frame's own tiles, and the
  job runs in blocks (D1, quiet pools drying whole, stays; D2 is no longer needed for room).

**Owed with P2c (P3).**
- The look of parked water: drawn per block while its tiles are resident, into the look texture, copied out together.
- Stores at a stroke's end for parked tiles, written from the park.
- Undo, release and forget drop the parks (as P1).

## 6. Risks

- **Copy cost.** GPU copies of 6 MB a tile; if `glCopyImageSubData` is slow on Adreno, use a blit pass. P1 measures it
  first.
- **Unified memory:** L2 comes from the same RAM, so the 700 MB reserve still applies, and P4's "last resort" is real
  on bad days.
- **Two-jobs-a-frame and grown rects:** covered by per-item admission. A job's rect is fixed at its start; a grown rect
  is the next job.
- **The look per block** must never show tiles at different steps: kept by the all-at-once copy-out, proven by the
  probe's look compare.

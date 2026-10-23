# JoyBrush engine Lead handoff, 2026-10-23 (realistic media: watercolour, oil, pencil)

Read this, then `tasks/joybrush/specs/WATER_JOBS_20261021.md`, `tasks/joybrush/HANDOFF_20261021_LEAD.md` (how the atlas
got fast), and the 10-20/10-21 sections of `tasks/lessons.md`. The owner is an artist, not a developer: report results as
plain emoji tables, make the engineering calls yourself, ask him only about how things should FEEL.

## 1. Where things stand (all on origin/joy-creator)

| commit | what |
|---|---|
| b64219da1 | Granulation reaches the shaders (`u_gran` was always 0 in the app); the atlas look drawn once per tile; coarse water (2x2 cells) built + probe-exact but OFF |
| 93333e61d | Atlas passes over scattered slots drawn one atlas row per render pass (the tiled GPU loaded/stored the whole box) |
| 73cd27e97 | **Water jobs**: the brush shows the frame it is drawn, the water catches up in budgeted slices |
| d0dd9be20 | Undo/redo/release write waiting dabs first; dry tiles' water let go; D3 set-aside; atlas up to 64 slots; atlas = default paint engine |

Phones: the Note 9 (sandbox, adb serial via `adb devices -l | grep SM_N960U`) has d0dd9be20. The owner's Note 20
(real projects; wireless debug, `adb mdns services` then `adb connect <ip:port>`) has 73cd27e97's build: it does NOT
have d0dd9be20 yet (atlas default, undo fix). Same debug signing key on both, `install -r` keeps data.
**The owner is testing water jobs now.** His checks (not yet reported): the zigzag-then-sweep crease, fast scribbling
(his 4-year-old), fingers pan after the pen has been seen, a media brush on a layer with editable lines just paints.

**How close to done (my estimate):** speed of the brush itself is solved (pen-to-screen ~30-50 ms typical on the crowded
bench). What is left before "wonderful": room (section 4A/4B, the biggest), zoomed-out painting, water look cost on big
washes, and whatever the owner's hands find. Roughly 1-2 weeks for a strong agent, most of it 4A.

## 2. How the water works now (read the code at these places)

- `MediaCanvas.frameInner` (gl/media/MediaCanvas.kt ~line 260): per frame, sets `e.frameRealMs`, the budget
  (`jobBudget: WaterJobBudget`, core/media/WaterJobBudget.kt; 60 % water / 40 % look, `WATER_SHARE`) for the atlas only.
- `MediaLayerEngine.wetFrameInner` (~1063): dabs go into input buffer `inNext` (two buffers `inTex/inFbos`); if no job,
  `startJob` (~1145): fixed rect, constants loaded once, `ran` = 2 steps when sliced (the old `WET_PX_STEPS` cap only for
  unbudgeted); `runJobInner` (~1242) runs stage x band items (`STAGE_BEAD0..2, FLUX, UPDATE, PAINT`, bands = 1 tile row,
  `JOB_WEIGHT` per px) until the budget is spent; the water flips (`wc`) only when a step's last stage is done; the input a
  step ate is cleared then. `finishWaterJob()` guards everything that reads/writes the state (dry, paste, erase, holdStill,
  stores, restore, sleep); `finishWaterStep()` (~1213) is the cheap one stores use (`current()` reads `finishedPaint()`).
  `settleInput` (~1222) = finish + one whole job for waiting input. Unbudgeted (window, probe) = exactly the old frames.
- The look (`renderLook(rects, …)` ~1615) adds input not yet in the water (`u_inA0/1, u_inB0/1`, `u_inputPending`) so
  marks show at once. `MediaAtlas.flush` (~429): the frame's own dabs' look with NO halo (`frameInput`, `Need.lookRect`),
  the water's look in parts within `lookBudget` (`advanceWaterLook` ~495), copied out together (`copyLookOut`).
- Stores at stroke end come from the last finished step; dabs still waiting are "owed" (`storesOwed`, `payOwedStores`
  ~530), written in place once absorbed, or before the next stroke / undo / redo / release (`MediaCanvas.settleOwed`).
- Room: `MediaAtlas.admitWater` (~185) -> `MediaAtlasWater.schedule` (core, ~177: whole pools pause while painting
  elsewhere; D3 `setAside` ~51: stillest far tiles of a too-big pool, never within a tile of the pen, become walls) ->
  `plan` (~216: D1 quiet pools dry whole, D2 oldest pool dries whole) -> refusal. `dropDryWater` (~716) frees dry tiles'
  water stores between strokes. Atlas size: `MediaAtlasPlacement.slotGrid` (free memory − 700 MB reserve) / 14.9 MB, 24..64.
- Device probe `MediaAtlasProbe`: window vs atlas vs scrambled vs broken vs pasteCopy vs **banded** (water in the
  smallest slices, finished each frame) must all be 0 px different. Run it after ANY engine change.

## 3. Tried — what worked, what did not (do not redo these)

| idea | result |
|---|---|
| ✅ Water jobs (brush first, water in budgeted slices) | crowd pen-to-frame p50 176-323 → 30-48 ms; every watercolour 4-10x |
| ✅ Frame's own look without halo / water margin | 200-450 k px a frame → 50-70 k |
| ✅ All look pieces in one `renderLook` call | look 42 → ~20 ms a frame (per-call setup + bench glFinish) |
| ✅ Passes by atlas row + glFlush (`MediaAtlasGl.drawByRows`, `rowPasses`=2) | flat crowd frames -25-35 %; knives/fan -25-40 %. A scissor per row WITHOUT the flush did nothing |
| ✅ Look once per tile (`writeLookOnce`) | halos overlapped up to 4x before |
| ✅ Granulation wired (`wetGran` from `WetBrush.gran`) | owner noticed it was missing; check every knob reaches its shader |
| ✅ Stores from the last finished step + owed write | pen-up hitch 1.9 s → none; next stroke's first frame 250-700 → 3-89 ms |
| ❌ Coarse water (2x2 cells, `jb_wet_coarse.glsl`, kept behind `jb_coarse_water`) | flux 5x cheaper but update/paint offset it; owner: blobbier ("left looks much better"); not reliably faster once the look was fixed. Candidate for ZOOMED-OUT only |
| ❌ Skip still water's look a few tiles a frame (10-21 night) | tiles at different moments = straight edges on screen |
| ❌ Cutting a pool at tile edges to make room (old D2) | the owner's creases; now pools only go whole (D3 is the last-resort exception, never by the pen) |
| ❌ Skip still water (sparse blocks) | measured: in a crowd ~75 % of blocks still flow 0.01-1 mm/s; little to skip |
| ❌ D3 set-aside on the Note 9 | rarely frees enough: each wet tile also needs its 8 neighbours (water reads 82 px, look reads 56 px) |
| ❌ Freeing dry tiles' water early | ~8 % of the drawing's room in the marathon (kept: harmless) |
| ❌ Half-res drip field, one unscissored wet input draw, look skip by tile (10-20/21) | see HANDOFF_20261021_LEAD.md |
| ⚠️ The first water-jobs build looked 30-59 ms because it skipped the dabs' look on most frames | always count px/pieces drawn beside the time |

## 4. Where I would go next (in order)

**0. Owner's first test of water jobs (10-23): water runs SLOWER than before (tilting hard used to make it race), lines a
little grainier, still runs out of room zoomed out; seams gone, generally better.**
- Slower water is a cap I left: a sliced job is 2 steps and `wetFrameInner` starts at most ONE job a frame (`if (job ==
  null) startJob…; runJob(budget)` returns when the job ends even with budget left), so water gets <= 2 steps (1/60 s) a
  frame; before it was 4 a frame (real time at 30 fps). Fix: after a job ends with budget left, start the next one in the
  same frame (loop in `wetFrameInner`, up to ~4 steps a frame or the budget), then re-measure the crowd bench (frames must
  stay ~50 ms) and let him tilt-test. Quick, do it first.
- Grainier lines = granulation now works (b64219da1; it was 0 before). Brush advanced settings have a Granulation knob;
  if he wants the old smoothness, lower the watercolour presets' `gran`, don't unwire it.

**A. Room: the atlas working space** (refusal "This watercolour stroke reaches further than this phone can hold at once").
The Note 9 gets 34-48 slots depending on free memory AT ENGINE CREATION (1.3-1.9 GB free swings run to run); a wet tile
needs its 8 neighbours, so ~a 4x4-tile wet area (one screen at 1:1) fills it. The real fix is **out-of-core water**: run a
step through the atlas in pieces. Jobs already run band by band; make residency per band (+ the reach rows) instead of the
whole pool. Hard parts: a tile mid-step has its new state in the other ping-pong half (track per-tile step progress, write
back the right half), and stages need neighbours' previous-stage output (sweep with a skew: bead → flux → update a row or
two behind). Water then runs slower when there is a lot of it, never refuses. Cheaper partial levers: half-float water
w0/w1 (−32 B/px; risk: evaporation steps ~1e-5 mm vanish in half precision at w ~0.1 — test drying), a separate water
atlas holding only wet tiles (paint-only neighbours for the look), re-sizing the atlas when memory frees up later.
**Bench:** `marathon.sh` (30 band strokes across the page, `jb_media_budget_mb`) — always log the slot count
(`atlas N x M slots (available=…)`) and interleave A/B runs, the second run of a pair gets less free memory.

**B. Room: the drawing's allowance** (owner hit "This phone has room for 315 MB…" after ~15 strokes zoomed out;
`LayerBudget.MEDIA_RAM_SHARE` 256 MB per 6 GB; a watercolour tile ~2.25 MB). The owner did NOT approve flattening old dry
paint to pixels: he values running water, tilting the phone to steer drips, and NEW STROKES INTERACTING WITH OLD ONES. So
prefer **spilling cold tiles' stores to disk** (the save format already writes `.f16` tiles) and reloading on demand over
flattening. Touches `GlPaintEngine.writableMediaTiles` / `mediaTile` / undo TileChange textures (shared hot file: check
LANES / the tile-speed helper before editing).

**C. Zoomed-out painting:** the wet area per stroke grows with zoom-out². Idea: switch the engine to coarse water
automatically below ~0.5x zoom (hysteresis, only between strokes; `MediaLayerEngine.coarseWaterDefault` + `MediaCanvas`
engine re-creation; stores convert both ways already). Needs the owner's eye at the zoom he paints.

**D. Water look cost on big washes** (Wash ~35-55 ms a frame of look in the bench): redraw only tiles whose water changed
meaningfully since their last look, but switch the whole set at once (never tile-by-tile moments).

**E. Water near the pen first:** a job covers its whole rectangle uniformly; give bands near the pen more steps (needs
care: different step counts across a boundary = the crease mechanism; maybe whole pools at different rates).

**F. Smaller:** tilted flux far drip taps ~2.5x the window per px; Oil round ~10 ms behind the window; tune
`WaterJobBudget` (fast 28 ms / slow 45 ms) and `WATER_SHARE` 0.6 by feel; `JOB_WEIGHT` / `LOOK_WEIGHT` from measured ns/px.

## 5. Rules that bit this lead

- Phones: never `adb shell monkey` (turns the owner's rotation lock off); launch with `am start -n` (resolve with
  `cmd package resolve-activity --brief`). Note 9 = sandbox (install freely, never uninstall/clear data). Note 20 = his
  real projects: install only when he asks, press Home first if Joy is in front (an install kills an unsaved drawing).
- Builds: `tasks/joybrush/lead-scripts/lead-build.sh <worktree> <timeout> <log> <tasks…>` (gradle lock). Never edit a
  worktree while it builds. Copy the APK only after `grep -q "BUILD SUCCESSFUL"` on the log, delete the old copy first.
  Unit suites: `-p joybrush :core:jvmTest :androidkit:test` (10 min, up to an hour when another session builds).
- Before main: device probe 0 px (incl. banded), JVM suites, and EVERY media brush flat + wet tilted vs a same-hour
  baseline (`bench/abj.sh`, `bench/ab3.sh`) — a watercolour-only check once let a 45 s palette-knife freeze through.
- Bench phase timers glFinish around every section: many small calls look far worse than they are. Phone speed swings
  ~2x run to run: interleave, compare pairs. The bench screenshot is the first brush's.
- Commit hook blocks device serials and the Windows user path in files. Don't push branches named symmetry*.
- After a push, fast-forward the owner's main folder (`git merge --ff-only origin/joy-creator` there); never merge over
  his or another session's local work.
- Python edits through heredocs break on some quoting in this Bash tool: write the script with the Write tool, run it.
- Kotlin `a + if (c) x else y + z` puts `+ z` inside the else: parenthesise each `if` in a `+` chain.

## 6. Tools

`tasks/joybrush/lead-scripts/water_profile.sh <outdir> <cases> <settle_ms> <id>` drives the bench over adb (phone lock,
atrace, logs, screenshot). The `bench/` scripts expect a scratch dir with `water_profile.sh` copied in (they `cd` to
C:/Temp/lead-1020; change it): `crowdc.sh <tag> <atlas> <coarse>` (+`XTRA=` extras) six wet strokes on one drawing;
`jobs.js <dir> <tag>` busy/gap/latency/first-frame per stroke; `phx.js <log> <brush>` phases by max; `abj.sh` / `abr.sh` /
`ab3.sh` + `abs3.js` every-brush A/B; `marathon*.sh` room tests. Bench extras: `jb_media_atlas`, `jb_water_jobs`,
`jb_atlas_rows`, `jb_coarse_water`, `jb_drop_dry`, `jb_media_budget_mb`, `jb_slope x,y`, `jb_bench_clear`,
`jb_paste_spacing`, `jb_water <A/B id>`; cases `topScribble`, `band0..band9`, `mediaAtlasProbe`, and the rest in
`StrokeReplay.case`. Refusals are logged as `refused: …` (JoyBrushPerf).

# Media Atlas v2 B3, steps 1+2 — long strokes, October 17, sonnet helper 1 (Opus)

Branch helper1/media-atlas-b1 (worktree C:/Temp/jb-helper1-atlas), on top of origin/joy-creator 353b068dc (B1/B2 already
in main as 1d1d4e19f). Plan and decisions: tasks/joybrush/specs/MEDIA_ATLAS_B3_PLAN_20261017.md (D1–D3 from the Lead).
Off by default: `MediaAtlasGl.enabled` is false, so MediaCanvas still builds a MediaWindow.

The owner's rule: **a stroke never stops partway because the atlas filled up.**

## What changed

- **A frame is admitted whole.** MediaCanvas takes all of a frame's input first. In atlas mode it then asks
  `MediaAtlas.admit` for the union of what the frame's passes will need: `MediaLayerEngine.dryNeed`, `pasteNeed`,
  `eraseNeed` and `wetNeed`, computed by the passes' own code (the passes now call the same functions), plus the look
  (the dirty box ⊕ halo + 1 ⊕ LOOK_REACH). All of it is held before the first pass draws. If it doesn't fit, the frame is
  refused in words before anything is drawn. A pass that asks past its admission is counted (`admissionMisses`) and
  logged.
- **Long strokes write older tiles back.** Room comes from A1's eviction: the least recently used tile that isn't held
  is written back into its RGBA16F copy-on-write stores, and its slot is reused. The rule is
  `MediaAtlasPlacement.fits`: this frame's tiles, plus what must stay, number at most 15.
- **Bug fixed on the way.** B2's `need()` loaded tiles in key order, so a load could evict a tile the same frame needed
  but hadn't pinned yet. `hold()` now pins the present tiles first, then loads the rest.
- `place()` no longer keeps its tiles held into the first frame, and `follow()` does nothing in the atlas (admission
  replaces it).
- Running water still holds every resident tile. Steps 3–5 make that per tile.
- **The window path is unchanged:** the same engine calls in the same order. Only the CPU-side taking of input moved
  earlier in `run()`.

## Proof

**tools/media_atlas_b3_check.js, full.** The oracle is one big window engine over a 1536² layer (6 × 6 = 36 tiles) that
never evicts. Its seeded state starts as f16, like the stores, and whenever the atlas evicts a tile, the oracle rounds
that tile through f16 at the same moment (the same GPU conversion). The atlas engines hold 15 of 16 slots and store the
layer in RGBA16F. Each frame is admitted, the look is rendered every frame, and the stroke ends with a full write-back.
Each pair runs in its own browser; a browser lost to memory pressure is retried once (this happened once, then passed).

PASS:

| Stroke | orderA | orderB | Evictions | Peak | Tiles crossed | noWriteBack | noReplay |
|---|---|---|---|---|---|---|---|
| dry point stick, snake | 0 | 0 | 25 | 15 | 36 | 1,055,307 | 0 |
| Proto Pencil tilted + smear, snake | 0 | 0 | 25 | 15 | 36 | 1,432,539 | 0 |
| dry side stick, back over evicted tiles | 0 | 0 | 35 | 15 | 36 | 1,179,231 | 3,824 |
| eraser, snake | 0 | 0 | 25 | 15 | 36 | 1,074,404 | 0 |
| oil round brush, snake | 0 | 0 | 25 | 15 | 36 | 1,610,868 | 0 |
| palette knife, back over evicted tiles | 0 | 0 | 35 | 15 | 36 | 1,560,251 | 3,282 |

- Never refused. Admission misses: 0. Compared: every state plane as raw float bits after the final write-back, and the
  look bytes over the whole layer.
- The noWriteBack control (evict without writing back) is caught on every stroke.
- The noReplay control (the oracle doesn't round evicted tiles) is caught only where a stroke comes back to tiles it
  evicted. That is expected: a tile left alone after eviction rounds the same at eviction and at the end. So the two
  loop-back strokes are what prove the reload is real.

JSON: MEDIA_ATLAS_B3_LONG_STROKES_20261017.json.

**B1 / B2:** no shader or harness input changed since they last passed (c07fe316e: B1 0 of 257.9M, B2 0 on 9 strokes),
and the Note 9 probe passed on 1d1d4e19f.

**Kotlin:** MediaAtlasPlacementTest 5/5, including a 40-tile walk over real MediaAtlasSlots (never refused, peak ≤ 15,
evictions happen). Full `:core:jvmTest` + `:androidkit:test` under the lock: 311 classes, 3398 tests, 0 failed, all fresh (3.64 GB free at start).

## Not yet

- Wet per tile (steps 3–5): water still pins every resident tile, so a long WET stroke can still fill the atlas.
- The temporal split of an oversize frame (step 6).
- The device long-stroke probe case.

## Steps 3-5: watercolour tile by tile (October 18) — proved headless, flag off

Code: 87129c46c (WIP) + 11e3cf34b. MediaAtlasWater (core) holds the rules; MediaAtlas applies them; the engine measures
the water (jb_wet_activity.frag, PBO + fence, never waits), dries tiles in place (dryTilesNow) and sees drained tiles as
walls (jb_media_atlas.glsl JB_WALL_BIT, jb_wall/jb_ownCell; jb_wet_flux outside()).

- What stays: tiles holding water (wet, damp, or unmeasured next to water) and every tile within 80 px (water's reach) +
  48 px (spread between measurements) of where it lies, measured in 16 px blocks. A pool at a tile corner keeps 4 tiles.
- Quiet (D1): the standing water's extent unchanged for 2 s and nothing faster than 1 mm/s. Measured in the harness: a
  settling pool's fastest outflow stays 0.2-0.4 mm/s for seconds (it feeds its drying edge), so "no flow" never happens;
  dampness creeps through the fibres long after, so it counts for residency, not motion.
- D2: the oldest water's measured tile farthest from the pen (never one the frame paints) dries in place and becomes a
  wall for the water's passes only: reads clamp to the reader's own tile (zero gradient, the window edge's rule), no flow
  in or out. Every other pass (look, pencil, oil) sees its real pigment.
- The look is recomputed over the water's extent + halo (every read held), plus where the frame drew.

Proof, tools/media_atlas_b3w_check.js --gpu (atlas and oracle on this PC's GPU, same browser, so still bit for bit):
the oracle is an unlimited atlas running the same rules against the same virtual budget of 15 from its own measurements.

| Scenario | orderA / orderB | Plans | Settled (D1) | Drained (D2) | Peak | Tiles |
|---|---|---|---|---|---|---|
| one long trail over 36 tiles | 0 / 0 px | 0 / 0 | 0 | 39 | 15 | 36 |
| three pools, 2.7 s pause, a fourth | 0 / 0 px | 0 / 0 | 1 | 10 | 15 | 35 |
| nine pools one after another | 0 / 0 px | 0 / 0 | 0 | 32 | 15 | 36 |

Never refused; 0 admission misses. Controls caught: settleMidFlow (quiet = 0 s) 42,951 px + 4 plan differences;
noWalls 43,698 px. B1 / B2 quick re-run after the wall shaders: 0 / 0 (walls off = the same bits). MediaAtlasWaterTest 8/8.
JSON: MEDIA_ATLAS_B3_WET_20261018.json.

**The seam (the Lead's question):** against an unlimited atlas that never settles (clean paper, MEDIA_ATLAS_B3_SEAM_20261018.json):
inside every pool that crosses a tile edge, the look difference at the edge columns (254-257) is no larger than inside the
tile (trail: 7.9-10.4 at the edge vs 8.5-11.7 inside; nine pools: 9.8-11.7 vs 9.6-11.2). What differs is where it should:
along each early-dried pool's rim (its dark edge had less time to form) and a faint even film. No line along a tile edge
(MEDIA_ATLAS_B3_SEAM_POOL_ZOOM_20261018.png: never-settles | atlas 15 | difference x4, the blue line is the tile edge).
The whole-layer numbers (mean 1.9-7.5, p99 65-157) are dominated by the harness's random ground, whose look is only
rendered where each engine recomputed it; a real layer's look always matches its paint.

**Product note:** with 15 slots one wet frame holds ~9 tiles (input + reach + look), so only about two recent pools run
together; a third, still moving, is drained (D2) early (32 tails in nine pools). The Lead's decision (Oct 18): scale the
slot count with the phone (clamp(budget / bytesPerSlot, 15, 64)) after measuring bytes per slot and free memory on the
Note 9, so D2 becomes a rare last resort.


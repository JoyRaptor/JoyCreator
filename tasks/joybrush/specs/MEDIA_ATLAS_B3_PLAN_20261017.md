# Media Atlas v2 B3 — plan for review (sonnet helper 1, October 17)

Base: helper1/media-atlas-b1 @ d3d3c981f. Flag stays off. The owner's requirement (via the Lead): **a stroke never
stops partway because the atlas filled up.** Refusal is legal only when the tiles one pass needs at once plus the active
wet tiles exceed 15; it is decided before the frame draws, never as a half-drawn frame.

## What exists (checked in code)

- A1's MediaAtlasSlots/MediaAtlasResidency already evict: `begin` picks the first unpinned resident (LRU order), and
  `ensure` loads into staging, then writes back the dirty victim (`writeBackSlot`, copy-on-write via
  `writableMediaTiles`, RGBA16F stores — the f16-at-rest rule). B2 never let it fire: `need()` pins every tile for the
  frame, and pins **every** resident while water runs.
- Wet is one rectangle per episode: `wetRect` = union of every dab rect since the water started, grown downhill by
  WetSpread (4 cm cap), simulated until 240 s after the last input (then `dryNow`). A long wet stroke therefore needs an
  ever-growing rect. The window build avoids that only by moving: water it leaves behind is stored and frozen, and
  resumes if the window comes back (`resumeWater` on load).
- Free mode still clamps WetSpread at layer px 0 (`max(0f, …)`): water cannot spread to negative layer coords.

## B3 pieces

1. **Frame-level admission (atomic per frame, not per pass).** Before a frame draws, MediaLayerEngine computes the
   frame's whole need — the union over its passes of rect ⊕ reach (dry: dab box ⊕ dryReach; paste: every step's square
   ⊕ 12; erase; wet: the active sim regions ⊕ 80; look: dirty ⊕ halo ⊕ 56) — and calls `need` once. MediaAtlas pins that
   set and evicts unpinned LRU tiles to make room. Later passes in the frame only assert residency. This replaces B2's
   per-pass refusal and closes the paste multi-step and look-after-draw gaps.
2. **Eviction of non-wet tiles mid-stroke.** Unpin at every frame end (already done). A tile is evictable when it is
   not in this frame's need and not active-wet. Dirty marking moves from `flush` to the pass that writes it, so a
   tile drawn in frame N and evicted in N+1 is always written back. Write-back uses the existing copy-on-write stores
   (first touch snapshots undo, as now); pending ground-from-look is written with it (already in `writeBackSlot`).
3. **Wet becomes per tile.** The episode keeps a set of active tiles, each with its own sim sub-rect: the dab rects
   that landed in it, grown by WetSpread **per painted tile** (the 4 cm cap measured from that tile's paint, not
   from the whole stroke's bounding box). The sim passes already draw per slot; each active tile draws its own
   sub-rect. The ring (`copyP0Ring`) and the 80 px read reach are made resident around each sub-rect ("ring
   residency"); ring tiles are pinned read-only for the frame.
4. **Wet activity query.** Every ~8 frames, one cheap reduction pass per active tile (256² → 16² → 1 texel: max water
   depth, max |flux|) is read back asynchronously (PBO + fence, never a stall). A tile whose water is quiet
   (flux ≈ 0 and depth ≤ the run-hold threshold) for 2 queries in a row, and is outside the current dab's reach, is
   **settled**.
5. **Never evict wet; make room by settling.** Wet tiles are never evicted while active. When the frame's need plus the
   active set exceeds 15, the oldest settled tiles finish drying in place (the existing `dryNow` maths restricted to that
   tile: evapOverride, one substep) and become ordinary dry tiles, which are then evictable. Their look is written first.
6. **Refusal, only when legal.** If the frame's need plus the tiles that are still flowing exceeds 15 after every settled
   tile has been dried, the frame is refused before drawing, in words. For dry, paste and erase this is impossible in
   practice (1 frame of dabs + reach ≤ 15); a single frame wider than that (a very fast flick across > 3 tiles) is split
   into consecutive sub-frames that each fit, each a full dab → apply sequence — deliberate, documented, and only on
   frames that would otherwise be refused.
7. **Free-mode spread at 0.** WetSpread's lower clamp is removed in free mode, so water can run left/up past layer px 0
   (tile keys are signed already).

## Decisions I need from you

- **D1, the wet trade-off in (5).** Recommended: settle = dry the quiet tile in place (dryNow per tile). Its pigment
  lands as it would at the end of the episode, just sooner for that tile. Alternative: freeze it (store water, resume
  if revisited), as the window does today on a move — but that is an eviction of wet through f16, which A1 says changes
  the trajectory, and which you ruled out.
- **D2, refusal when water still flows (6).** On steep tilt a long running drip can keep more than ~12 tiles flowing.
  Recommended: refuse that frame in words (legal under your rule). Alternative: dry the oldest flowing tile anyway (a
  drip is cut short, never refused).
- **D3, sub-frame split (6).** OK to split an oversize frame instead of refusing it?

## Proofs

- **Long strokes, headless (new tools/media_atlas_b3_check.js):** a 1-slot-per-tile window oracle on a big layer
  (e.g. 10 × 6 tiles) vs the atlas with 15 resident slots. Strokes crossing ≥ 30 tiles: dry point, dry side + smear,
  eraser, oil, knife, and wet flat and tilted. Pass: **never refused**; peak resident ≤ 15; evictions > 0; and
  bit-exact against the oracle, where the oracle replays the atlas's events at the same moments (f16-quantise a tile
  when the atlas evicts it; per-tile dryNow when the atlas settles it; the same per-tile sim sub-rects). Two placement
  orders, plus controls that must fail: no write-back on eviction, a missing ring, evicting a wet tile.
- **One path that revisits evicted tiles** (a loop back), so round-trip load/write-back is exercised, not just
  write-and-leave.
- **Existing harnesses:** B1 window parity and B2 free placement unchanged and still 0 (strokes that fit never evict
  or settle, so B3 must not change them by a bit).
- **Wet behaviour vs the window build:** lab-style renders of a long wet stroke (window build vs atlas), with the mean
  and p99 deltas, because per-tile settling deliberately differs from the window's freeze-on-move.
- **Kotlin:** unit tests for frame admission (union of needs, atomic, refusal text), per-tile WetSpread, the settle
  state machine and sub-frame splitting; full `:core:jvmTest` + `:androidkit:test` under the lock.
- **Device:** a long-stroke case in mediaAtlasProbe (dry and wet, never refused, timings for evict/load per frame).

## Order

Frame admission (1) and non-wet eviction (2), with the dry/paste/erase long-stroke proof → wet per-tile (3, 7) →
activity query and settling (4, 5) → refusal and sub-frames (6) → device probe case. Each step lands with its proof,
behind the flag.

## Decisions (the Lead)

- **October 17:** B3 plan approved. D1 = dry a whole quiet connected component in place, farthest from the pen first; never
  cut connected water. D2 = no refusal for a drip: the oldest water's far end dries in place, silently (logged), and is
  a mirror WALL for the water beside it (zero gradient, no flow, no dry front). D3 = split an oversize frame in TIME
  (sub-frames by dab order), never spatially.
- **October 18:** steps 3-5 accepted (20a1c2978); both rule changes approved:
  - residency follows where the water is: tiles within 80 px (its reach) + 48 px (spread between measurements) of the
    16 px blocks holding water or dampness, not a whole 3x3 ring;
  - "quiet" = the STANDING water's extent unchanged for 2 s and nothing faster than 1 mm/s (a settling pool's outflow stays
    0.2-0.4 mm/s feeding its drying edge, so "no flow" never comes; dampness counts for residency, not motion).
- **October 18:** D2 must be a rare last resort: slots scale with the phone, clamp(budget / bytesPerSlot, 15, 64), sized
  when the atlas is made (as windowFits sizes the window), target 4+ concurrent wet pools on the Note 9. Numbers (bytes
  per slot per store, free memory with a drawing open) are reported before any budget constant changes.

## October 19: the window patch, and the atlas parked

- **Shipped bug, fixed by the window patch (05136a021):** the media window dropped trailing dabs. For a watercolour or oil
  stroke it centred on the PEN (MediaWindowMath.placeFor puts the pen in its third tile), which can leave 128 px behind a
  fast pen; a frame's dabs trail back to where the pen was a frame ago (about 450 px in the bench's topScribble), and the
  ones past the window's edge were clipped: a straight cut through the stroke at a tile line, worst on a slow cold first
  frame. Now a frame whose dabs the usual follow would clip (MediaWindow.wouldClip, the dabs' own boxes, no margin) first
  takes one window centred on its dabs (holdWhole: one move, today's path, all its water steps); only a frame no window
  holds (wider than about 768 px of dabs) is drawn in parts, dabs in order, sharing the frame's water steps. Every other
  frame runs as before. Note 9, same hour, warm: tilted pen p95 +9%, max +7%; flat within run-to-run noise; no splits on
  topScribble; the cold first stroke and a 1500 px flick whole; Toast 0, KGSL 0. The window-edge seam at x 768 is nearly
  gone as a side effect (the window sits over the wash). Tried and dropped on the way: drying the tiles a moving window
  leaves (tile-shaped hard edges, boxed tilted drips), and splitting every frame over 512 px (pen-down frames 2-5x).
- **Atlas parked (off by default).** It no longer hangs the GPU or loses the stroke (the look read every texel through the
  page table: 3dbbb654b), compiles in about 1.5 s cold instead of 106 s (every per-slot pass on its 3x3 neighbour slots,
  ec5b11f39; the paste brush on 5x5, 034f028de), and B3w is exact (look reach and drain-time loads, c9c7b9254). On the
  Note 9 flat water runs about 2.3x the window, tilted about 5x: the wet flux pass alone is 3.2-3.7x in its atlas build and
  none of shared reads, precomputed offsets, no-wall builds or loop unrolling moved it (the stop rule). Finding it needs
  the Adreno shader profiler or a bisection of the flux body. Open: a screen-sized window (scoped, the owner's call).
- **Window patch on main (1445af733, after a hotfix):** the first version on main froze the palette knife (604 parts,
  45 s: paste steps measured by their old full square); main was reverted, and 53be96265 measures a step by docBounds and
  caps a frame at 8 parts (a frame needing more runs exactly as before). Checked on all 22 media brushes plus the 6 wet
  tilted, same hour as main: dry and flat wet/paste within noise or better, the knife fixed, tilted wet +13% p95 on
  single runs. **Known limit:** the cap fires on the big wet brushes on tilted paper (All-round once, Wash twice in one
  run; and Wash once on FLAT paper in a later run): a slow frame of a big wet brush whose dabs span wider than any window. Those frames still clip their trailing dabs, exactly as
  before the patch. Rule from now on: any window or media-frame change is phone-checked on the full brush list.
- **Cold first stroke after an install (b00b82667, the Lead's JbCanvasView):** the prewarm pump only ran when a drawing was
  LOADED, so a new drawing compiled all 14 media programs inside its first stroke (1.1-1.3 s on the Note 9 after an install).
  It now also starts when the canvas surface is made: the first stroke 1.5 s after opening ran 268 ms max, as warm.

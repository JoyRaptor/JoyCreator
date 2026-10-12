# Media atlas A1 — residency contract (Codex Lead, October 12)

This refines slice A1 of MEDIA_ATLAS_DESIGN.md. A1 proves allocation and failure-safe load/write-back through a fakeable
backend. It does not wire the atlas into painting; A2–A6 retain their shader, GPU parity and device gates.

## Residency and ownership

- A page is `(layerId, nullable planeId, Tiles.key(tx, ty))`. All stores of one page share a slot. Store identity belongs
  to each atlas texture, not to independent page-table entries: pigment, paper, water, ground and look cannot be evicted
  separately. Signed document tile coordinates are preserved; plane null is different from every non-null cel id.
- The page table is GL-thread confined and owns metadata only. Persistent tiles and undo retain their existing owners.
- Pins protect stroke/read dependencies and moving water from eviction. A1 does not infer inactivity from water deltas.
  Zero change in water alone does not prove pigment, evaporation, flux or the visible look are stationary.
- LRU order is deterministic. When every resident page is pinned, refuse without callbacks or changes.

## Replacement and failure

One physical slot is always reserved for staging, counted in the allocation. On a miss, reserve that free slot and an
unpinned victim if capacity is full. The old mapping and its contents remain visible throughout preparation.

1. Backend loads the entire incoming page into staging, clearing every missing state store and both ping-pong targets.
   Ground follows MediaWindow.loadKey: absent payload uses the existing look; payload without ground is transparent.
2. If the victim is dirty, write it back through an atomic backing transaction. Copy-on-write reservations, ground,
   changed stores and look belong to that transaction. Backend failure must not publish partial durable contents.
3. Only after both succeed does the page table publish the staged slot and free the old slot. No GL callback follows
   publication; stale bytes in a free slot are overwritten by the next complete load.

Failure in either callback cancels the reservation. Neither old mapping nor old physical contents change; failed staging
is invisible. The backend may not call back into residency mutations. Only one replacement may be pending, so even a
pin/dirty change during preparation fails instead of silently invalidating the chosen victim. Tokens are single-use.

## Memory and precision

`mediaBudgetBytes` currently limits durable payload, while LayerBudget.windowFits reserves tool memory separately.
Do not reinterpret that ceiling as a whole-process governor. A1 receives an explicit working allocation budget and the
actual per-slot bytes for all targets, including ping-pong, halo, look/ground, temporary inputs and any texture-grid
padding. Include the staging slot. Unknown extra textures must be reserved separately before allocation, never omitted.
Checked division/addition prevent integer overflow; refuse a budget one byte below the configured allocation.

Runtime simulation retains full-float state. Persistent stores retain current half-float formats and RGBA8 ground/look.
Evicting/reloading a live full-float page through half floats changes its trajectory: active simulation pages stay pinned.
Dirty settled-page write-back is the existing storage boundary, not permission to round between passes.

## Later-slice requirements discovered during A1

- Tile-board origins/extents are pixel-space values and may not align with the 256px document grid. Wrapping tile indices
  modulo a tile count is insufficient. A5 must split wrapped pixel rectangles, including fractional board coordinates,
  small/non-multiple dimensions and corners; it must not pretend A1 already supplies periodic sampling.
- Halo exchange uses one frozen input generation for every slot in a pass. Updating one slot before fetching another's
  halo would introduce scan-order dependence. Missing state is zero; ground/look require the rules above.
- Wet pages can spread into an initially absent neighbor. A3 must conservatively admit destination pages before a pass
  and atomically refuse the frame if dependencies do not fit. Moving only currently wet tiles reproduces the window edge.

## Acceptance

Core tests: distinct layers/cels and negative keys; unique bounded mappings; exact budget incl staging; deterministic LRU;
pin refusal; no publication before commit; single-use/foreign-token rejection; canceled reservation reusable; mutation
during replacement rejected. Androidkit fake tests: full load precedes write-back; callbacks see old mapping; failed load
or write-back preserves old contents/mappings/dirty flags; successful replacement keeps backing bytes and evicts once.
These are lifecycle proofs. GPU precision, full COW rollback, periodic halos and phone speed remain later gates.

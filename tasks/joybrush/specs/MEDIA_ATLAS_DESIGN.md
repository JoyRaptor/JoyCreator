# Media engine without a window: a tile atlas with halos (lead design, 2026-10-11)

## Why
The pencil, watercolour and oil engine simulates inside one 1024 px square "window" (`MediaWindow`, `MediaWindowMath`). Three
owner-visible problems come from that one decision:
1. **Creases in washes** (owner's screenshot, 2026-10-11): straight-edged lighter or darker rectangles.
   - Water stops dead at the window edge (`WetSpread`, `jb_wet_flux.frag` rect tests).
   - When the window moves, everything outside it freezes mid-drying while the inside keeps evolving.
2. **No media on Tile boards**, and no painting across the repeat. The window cannot wrap round a periodic tile.
3. **Cost**: `wetRect` only grows. The whole window-sized area simulates even where the water has settled, and big or fast
   strokes force window moves (flush plus 16-tile reload).

Making the window bigger does not scale: a wet window is about 236 MB at 1024². The Note 9 cannot hold 2048².

## The design
**State lives in a GPU tile atlas, not a window.**
- Each store (p0, p1, paper, w0, w1, flux, …) gets an atlas texture of N slots. Each slot is one 256² tile plus a halo of
  H px on every side, so slot size is (256 + 2H)². A page table maps (layer, plane, tileKey) to a slot.
- Slots are made on demand under the existing media ceiling (`mediaBudgetBytes`). An inactive slot is written back to the
  RGBA16F stores (exactly what `MediaWindow.flush` does today) and freed.
- **Passes run per resident tile**, over that slot's interior (viewport/scissor per slot, or instanced quads with a
  per-instance slot origin). The shaders keep their maths. They read neighbours with ordinary texel offsets, which land in
  the halo.
- **A halo exchange runs before each pass that reads neighbours.** For every resident tile, copy the 8 neighbour edge
  strips (width H) from the neighbours' slot interiors into this slot's halo (blits or one instanced copy pass).
  - A missing neighbour is zero: no water, no paint, which is what the stores hold there now.
  - H = the widest per-pass read radius: flux/update about 1–2 cells, the drying ring, bead blur ≤ 12 on tilted paper,
    look render 53 (the look gets its own wider exchange or a separate look halo).
  - Exchange cost is perimeter (4·256·H per tile), small next to the interior.
- **Tile boards wrap for free**: the neighbour of the board's rightmost tile is its leftmost, modulo the board's tile grid.
  Every media brush then paints and flows seamlessly across the repeat. That is the owner's "every brush works in Tile mode".
- **Activity**: a tile is resident while the stroke touches it, or while it holds moving water (a per-tile max-|Δw| reduction
  read back asynchronously, as in the perf plan). Settled tiles leave the atlas.
  - This is also the "only simulate where water is moving" speed-up. It is exact when the threshold is 0 (water exactly
    still). Any non-zero threshold is an owner A/B.
- **Look**: rendered per dirty tile region with the look halo exchanged first, so no tile edge is ever rendered without its
  neighbours. That removes the F3 class of seams by construction.

## What stays
- Every shader's maths, uniforms (`MediaUniforms.kt`) and precision rulings.
- The store formats, undo (copy-on-write per tile, one step per stroke), saving, the per-plane payload (JB-2.40 slice 3a)
  and the bench.
- Exactness: inside the old window's interior, with no window move, the atlas must give bit-identical results to today.
  Prove it in the headless checkers (`media_wet_exact_parity_check.js` pattern). It differs ONLY where today's output is
  wrong: window edges and frozen regions.

## Slices
| Slice | What | Proof |
|---|---|---|
| A1 | Atlas allocator, page table, slot write-back/load (CPU-testable allocator; GL behind fakes) | JVM tests |
| A2 | Halo exchange pass + per-slot pass driver for the DRY engine (pencil) | headless: atlas vs a large single window on a 3×3-tile stroke, 0 mismatches; broken control (no exchange) detected |
| A3 | Wet engine on the atlas (flux/update/bead) + water-activity residency | headless parity vs a large window; Note 9 bench |
| A4 | Paste (oil) on the atlas; look render with look halo | headless parity; bench |
| A5 | Tile-board wrap (periodic page-table neighbours) for media; then smudge/fill wrap | owner check: paint across the repeat |
| A6 | Remove `MediaWindow`; window-move counters retire | full suite + device A/B |

Owner checks: the crease screenshot scenario (a big wash spanning several tiles, then the pen moving away) shows no straight
lines. A watercolour stroke across a Tile board's edge runs seamlessly across the repeat.

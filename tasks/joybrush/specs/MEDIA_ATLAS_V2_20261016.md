# Media Atlas v2: page-table addressing, no halos (lead design, 2026-10-16)

Supersedes the halo-exchange plan in MEDIA_ATLAS_DESIGN.md (2026-10-11). Keeps A1 (`MediaAtlasSlots`,
`MediaAtlasResidency`: page table, LRU, transactional load/write-back) as the CPU side.

## Owner goal
Oil, watercolour and pencil anywhere on one layer, at any size, on every board including Tile. No creases where the
old 1024 px window ended or moved, water that keeps moving wherever it is wet, and the watercolour brush fast on the
Note 9.

## Why not halos
A halo exchange runs before every pass that reads neighbours. Watercolour runs 4 substeps × (flux, update, 3 bead) per
frame. With 8 strips × 16 slots, that is about 1,400 blits a frame, far over budget on Adreno 630. Building the halo by
copying into a second texture costs atlas-size bandwidth per pass. Writing and sampling the same image in one draw is a
feedback loop, which GLES does not define.

## The design
**The window's textures become the atlas.**
- The window is already 1024² = 4×4 slots of 256². Keep the very same textures and memory, and treat each 256² cell as
  a slot. A page table maps a layer tile to a slot.
- Today's window is the special case where the table is the identity over a 4×4 block.
- Memory per slot: dry about 108 B/px (7 MB), wet about 223 B/px (14.6 MB). The existing 128 / 236 MB tool
  reservations therefore stay 16 slots. Nothing new to budget.

**Shaders read through one address function**, `jb_at(ivec2 layerPx) -> ivec2 atlasTexel` (+ presence flag), in
`jb_media_common.glsl`.
- **Same tile as the fragment** (a flat per-instance tile) → own slot. This is the common case: no table read.
- **Another tile** → `texelFetch(u_pageTable, tile - u_pageOrigin)`, where the table is R16I, −1 = not resident.
- **Not resident** → the store holds nothing there: return zero. That is correct only because of the residency rule
  below.
- **Tile board** (`u_wrapSize > 0`) → wrap in pixel space into the board rect first:
  `q = wrapOrigin + mod(q − wrapOrigin, wrapSize)`. Unaligned boards work too.

Every `texelFetch(t, p)` becomes `texelFetch(t, jb_at(p))`, and `jb_bilinear` routes its 4 taps the same way. Every
`texture(t, uv)` same-pixel read (apply, copy, erase) becomes a `texelFetch` at the own texel. The bead field
(½ res) gets a ½-res table view, and its bilinear upsample is done by hand. **No physics, constants or precision change.**

**Pass driver.**
- Each pass is ONE instanced draw over the resident slots it must write. Per instance: slot origin (atlas px), tile
  origin (layer px), clip rect.
- The fragment derives `layerPx = gl_FragCoord.xy − slotOrigin + tileOrigin` exactly, not from interpolated
  varyings. The window path is moved to the same derivation first (one-time ulp shift, accepted).
- **Dab passes** (dry/wet/erase/paste) bin dabs per slot on the CPU, so a dab spanning slots is emitted once per
  slot. Order is preserved. A 6th instance vec4 carries the slot offset, and the fragment discards outside its slot.

**Residency rule (correctness, not speed).** A tile must be resident while it is:
- **written** by a pass, or
- **within any pass's read radius** of a written texel:
  - dry smear: `ceil(|u_smearPx|) + 3`;
  - flux / steer: the ring computed by `ringCells`;
  - bead: `3 + 2r` (½ res);
  - look shadow march: 53 px × scale.

A non-resident neighbour inside that radius would read as empty paint. The driver dilates the frame's write rect by
the max read radius, at tile granularity, and loads those tiles before drawing. If they do not fit, it refuses before
drawing (atomic), never clips.

**Wet residency.**
- A tile stays resident while it is wet. "Wet" is decided every 8 frames by one `GL_ANY_SAMPLES_PASSED` query per slot
  on a pass that discards where `w0 == 0 && flux == 0`, read back asynchronously.
- The 1-ring around wet tiles stays resident too: that is where water can flow next.
- Dry tiles leave, write back and stop costing anything. That is the "simulate only where water is" speed-up, and it
  is exact.
- **Wet slots are never evicted**: f32 → f16 would change the water's path. If a new tile needs a slot and every slot
  is wet, the least recently painted wet tile settles where it lies (today's window behaviour). Log it; this is rare.
- `WetSpread`'s 4 cm cap stays, applied per tile instead of per rectangle.

**Look.** Render straight into the layer's look tiles (`writableMediaTiles(.., MEDIA_LOOK)`), one draw per dirty look
tile, reading state through `jb_at`, with the look's read radius resident. No window look texture and no copy. This
removes seam class F3 by construction.

**Stores, undo, save.**
- `MediaWindow`'s write-back, ground-from-look, `restored`, `lookWritten` and the running-water rule (it extends the
  top undo step) all move to per-slot code in a new `MediaAtlas` class. Same `GlPaintEngine` calls, same
  copy-on-write, one undo step per stroke.
- Write-back is per tile at stroke end, at eviction and at the frame boundary (as `flush` does).

## Slices and proof
| Slice | What | Proof |
|---|---|---|
| B1 | `jb_at` + page-table texture + instanced per-slot driver, identity layout (atlas == today's window) | headless parity vs the current window engine, exact (or ulp-bounded, stated), every medium; broken control: table off by one tile must fail |
| B2 | Free placement: any tile → any slot, residency dilation, load/write-back per slot, `MediaAtlas` replaces `MediaWindow` | headless: scrambled slot order == identity order, bit for bit; 3×3 stroke across scattered slots vs a 1536² single-window oracle with a guard ring; missing-ring broken control fails |
| B3 | Wet activity queries, ring residency, never-evict-wet, per-tile spread cap | headless: wash spanning 6 tiles == big-window oracle; dry tiles leave; Note 9 bench (ms/frame vs today) |
| B4 | Look direct to look tiles | look RGBA8 exact vs today inside the old window; crease scenario screenshot |
| B5 | Tile-board wrap (address wrap + dab replication + write clip) | stroke across the repeat == the same stroke on an unwrapped periodic oracle |
| B6 | Delete `MediaWindow`/`MediaWindowMath` window-move code; counters retire | full suites + Note 9 acceptance; owner checks the crease scenario and a wash across a Tile edge |

Tooling: the headless WebGL2 checkers (`joybrush/tools/media_*_parity_check.js`, Playwright + Chromium) run the
app's GLSL. The Kotlin driver logic also gets JVM tests: dab binning, dilation, page table, wet set, using fakes in the
A1 style.

## Watercolour speed (owner wants to see A and B side by side)
- **A, instant brush**: the sim runs at most K substeps per frame, under a GPU-time budget. Water time may lag real
  time on slow phones, while the brush deposit is never delayed.
- **B, less detail**: substeps 2 (or 1) at full time step. The water is coarser and edges softer.

Ship both behind an overflow-menu toggle, so he can compare on the same phone. B3's "only where wet" applies to both.

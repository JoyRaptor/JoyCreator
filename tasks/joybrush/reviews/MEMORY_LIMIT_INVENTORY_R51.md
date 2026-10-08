# R51 memory-limit inventory

Source base: `b70bac3b72e69bdf60246ba0ca407bf511a50048`, the requested
`origin/joy-creator` snapshot. Read-only supporting research for the later governor row.
This report does not approve these limits, design a replacement, mark any roadmap row
complete, or describe changes made in other agents' worktrees after this base.
No builds, tests, device operations, or runtime-memory measurements were performed.

OpenCode Muse Spark 1.3 contributor-free at xhigh supplied the research draft; the
Codex coordinator reviewed source and arithmetic and rewrote unsupported claims.
The completed research run reported cost zero. Driver allocation overhead, JVM object
overhead, and measured peak residency remain unknown.

## Scope and evidence notation

Read R51, the Lead's queue, the one-layer brief sections 4.8/11, and JB-2.40.
R51 overrides the MEDIA kind/weighted slots and calls for one undo history. JB-2.40
retains the media ceiling and working-window reservation until the governor row.
Those are planned changes at this base: the existing slot APIs and MEDIA path still
exist. The governor design remains gated on JB-2.40 landing.

`LB:102-132` means the exact repository path registered as **LB**, lines 102-132
at the base above. Paths below are complete, without ellipses. Each evidence range
is source inspection, not evidence of a runtime test.

| ID | Exact repository-relative path |
|---|---|
| LB | `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/layers/LayerNames.kt` |
| UL | `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paint/UndoLog.kt` |
| GE | `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt` |
| LC | `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/LayerCompositor.kt` |
| GT | `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GrainTextures.kt` |
| PB | `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/PaperBackground.kt` |
| CV | `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt` |
| ACT | `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/JoyBrushActivity.kt` |
| MS | `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/media/MediaStores.kt` |
| WM | `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/media/MediaWindowMath.kt` |
| MC | `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaCanvas.kt` |
| MW | `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaWindow.kt` |
| ME | `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaLayerEngine.kt` |
| MT | `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaGl.kt` |
| IE | `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/vector/InkEditSession.kt` |
| RR | `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/render/RegionRenderer.kt` |
| BP | `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlBoardPreview.kt` |
| TP | `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/TilePainting.kt` |
| FF | `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/fill/FloodFill.kt` |
| FP | `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/tools/FillPenRaster.kt` |
| SM | `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/select/SelectionMask.kt` |
| AE | `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/AnimExport.kt` |
| PP | `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paper/PaperPreviews.kt` |
| JA | `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/JbArchive.kt` |
| RL | `tasks/joybrush/LEAD_RULINGS.md` |
| LD | `tasks/joybrush/LEAD_DESK.md` |
| BR | `tasks/joybrush/design/ONE_LAYER_MODEL_BRIEF_20261007.md` |
| SPEC | `tasks/joybrush/specs/JB-2.40_media_on_any_layer.md` |

## Admission and ownership ledgers

MiB means 1,048,576 bytes; KiB means 1,024 bytes. Source/UI text sometimes says
MB/GB where the arithmetic below uses MiB/GiB. A 256-square RGBA8 tile is
262,144 bytes = 256 KiB; RGBA16F is 524,288 bytes = 512 KiB. These are texel
payload sizes, not complete driver allocations.

| Limit/accountant | Literal or derived bound | Enforcement/refusal | Owner, lifetime, release | Sharing and accounting boundaries | Evidence |
|---|---|---|---|---|---|
| Layer admission | `floor(total RAM * .06 / (ceil(w/256)*ceil(h/256)*256*256*4))`, clamped 4-64. MEDIA consumes 7 slots: `(4+3*8)/4`. | `roomForAnother` refuses another layer when weighted slots exceed max; Activity supplies RAM/page dimensions. | A view-level admission estimate, not an allocation owner or byte ledger. Recomputed from dimensions/RAM; layer deletion changes the count. | Estimates a full-page layer; does not count actual sparse tiles, all animation cels, masks, undo, or the window. Do not add this estimate to measured resident bytes as though it were a separate allocation. | LB:102-132; CV:406-407,619-634; ACT:1135-1142 |
| Resident media payload | `max(64 MiB, total RAM * 256/(6*1024))`; 6 GiB gives 256 MiB. 64 MiB permits 128 store tiles; 256 MiB permits 512, not complete painted tiles with all stores. | `writableMediaTiles` counts missing float-store entries and refuses before swaps when their added texel bytes exceed the ceiling. Engine defaults to `Long.MAX_VALUE` until configured. | Engine sums entries in `layers[*].floats`; current stores belong to layers. Delete/restore/drop paths change residency; released textures may enter a pool rather than be immediately deleted. | Excludes RGBA8 look, undo-owned old versions, window targets and free pools. Replacing an existing entry can allocate a new copy while preserving the before texture for undo, without increasing this ledger. | LB:134-144; GE:1811-1814,1836-1885,1897-1914; CV:622-624; ACT:1140 |
| Working-window admission | Window is 4*256 = 1024 pixels square. Dry reservation `1024^2*128` = 128 MiB; wet `1024^2*(128+108)` = 236 MiB. | Needed reservation minus already-held reservation must fit `available - 2*low-memory threshold`. UI and GL-thread checks; wet upgrade charges the delta. | `heldBytes` reports 0/dry/wet reservation, not measured bytes. Window flushes then sleeps on release; idle release waits 10 seconds with pen up and water inactive. Programs/brush data may survive sleep. | Separate from the payload ceiling and undo. Shared framebuffer attachments are references to textures, not additional texel payload copies; driver/FBO metadata is not charged by this estimate. | LB:146-167; WM:16-18; MC:85-94,214-222; CV:1083-1091; MW:39-61; ME:195-215 |
| Tile undo/redo | Default 192 MiB. `heldBytes` counts undo before textures + redo after textures; RGBA8 256 KiB, float-store RGBA16F 512 KiB per tile. Equivalent to 768/384 tiles only for a homogeneous history. | Trim oldest undo while there is more than one step and held bytes exceed budget. Newest step is always retained, even above budget. Redo is discarded on a new edit. | Undo owns before tiles, redo owns after tiles, current layers own current tiles. Merge releases intermediate versions; clear releases history. Release goes through the texture pools. | Not a hard peak ceiling: pre-commit/stroke copies, document/stack metadata and newest-step overrun are outside its byte limit. Avoid counting a current tile again just because a step also records its handle. | UL:8-23,24-59,62-80,89-115,170-180; GE:139-142,339,2320-2332 |
| Running-water undo extension | No separate byte ceiling; extends/replaces the newest media step. Water spread is capped 40 mm downhill / 10 mm sideways and clipped to the window. | Duplicate addresses are refused in extension; replacement removes dried-out entries or an empty step. Normal undo trim still keeps newest. | Same tile ownership as UndoLog. Current writes and held before snapshots must be distinguished when a store dries. | Dropping a current water tile does **not** necessarily release every before snapshot. Do not claim it reduces both ledgers by the same amount. | UL:118-164; WM:46-70; GE:1897-1914; MW:86-116 |
| Editable-line history | 50 steps, no record-byte accountant. | Oldest step removed after 50. | `InkEditSession` stores value-record history; dropped records become GC candidates, with no texture-release hook. | Separate history from UndoLog at this base. R51 says it will join UndoLog; this report does not invent a record-byte cost. | IE:97-98,417-422,449-459; RL:749-777 |

## Working-window texture census

The declared reservation can be reconciled to texture payloads; it is not a measured
peak or a guarantee covering every resource used by the media engine.

| Resource | Payload arithmetic at 1024-square | Allocation/lifetime evidence |
|---|---|---|
| Two p0/p1/paper state sets | 6 RGBA32F * 16 B/px = 96 MiB | ME:174-181; MT:79-95 |
| Dry delta, paper bake, fluid bake, water bake, look | RGBA16F delta 8 + bake 8 + RGBA8 fluid 4 + RGBA16F water 8 + RGBA8 look 4 = 32 B/px, hence 32 MiB. Lazy resources may not all exist simultaneously on every path; reservation assumes their payload allowance. | ME:225-235,339-340; MT:79-95 |
| Wet additions | Four RGBA32F water targets 64 + two RGBA16F flux 16 + run 8 + two input targets 16 + two half-width/half-height RGBA16F bead targets 4 = 108 B/px, hence 108 MiB. | ME:238-253; MT:79-95 |

Dry total 96+32 = 128 MiB; wet total 128+108 = 236 MiB. FBO attachments reuse
these textures. Shader programs, brush LUT/cell textures, buffers and driver overhead
are not included by this calculation (ME:124-168,375-382,470-479). Sleep deletes
window textures/FBOs but retains programs and brush cells (ME:185-215).

Resident payload stores are different allocations: RGBA16F tile textures, made lazily
by medium (MS:17-43; GE:1985-1996). The working window loads/renders/flushes those
stores (MW:86-116,139-157). It is not merely a second reference to a layer tile.
JB-2.40's planned `#g` ground, dry-save rules and per-cel payload migration are not
already implemented at this base (SPEC:27-63,80-98).

## Frames, cached resources, render and export

| Allocation/path | Bound and enforcement | Owner, release, and counting risk | Evidence |
|---|---|---|---|
| Live paint, cel and projection maps | Layer stores `tiles`, per-cel maps, `projected`, and float stores. No aggregate live RGBA tile-byte ledger appears in the inspected engine ownership paths. Layer admission is not such a ledger. | Layer owns a deduplicated set of texture names; release visits that ownership set and masks. Frame references/visible projections are not necessarily copies. Count unique allocated names rather than multiplying logical frame references. This does not establish cross-frame content dedupe or an ahead-of-playhead cache. | GE:303-314,526-598,1335 |
| Free GPU texture pools | 64 RGBA8 layer tiles = 16 MiB; 16 RGBA16F float tiles = 8 MiB. Smudge pool keeps 16 RGBA8/RGBA16F tiles = 4/8 MiB; stamp pool keeps 32 R8/R16F tiles = 2/4 MiB. These are payload estimates at size256, not driver sizes. | Trimming deletes excess names; ordinary recycle retains allocation for reuse. Pools are outside media-resident and undo-held ledgers after ownership leaves those ledgers. Release deletes; context-loss forget clears invalid names. | GE:276-281,526-598,2298-2332 |
| Compositor/paper viewport targets | Each compositor instance has two RGBA8 targets,8*w*h bytes of payload; the engine has display and board compositor instances. Paper background has another RGBA8 viewport texture,4*w*h bytes. Their inspected allocation helpers have no byte-ledger admission. Dimensions/callers determine size; no universal combined bound is asserted. | Same-size targets are reused; resizing releases first. Explicit release deletes names/FBOs, context forget clears names. Copying target pixels into backdrop creates distinct texel payloads; attaching either to FBOs does not create another texel copy. | LC:6-45,63-90; PB:8-41; GE:272-274,593-596 |
| Grain/paper GPU asset cache | `byName` caches requested RGBA8 assets with generated mip chains; no byte/count eviction budget in the inspected cache. A1x1 placeholder is separate. Exact packaged-asset aggregate was not measured. | Bitmap decode, pixel array, expanded bytes and direct upload buffer coexist during upload. Bitmap is recycled; GPU names persist until release/context forget. Count GPU mips separately from CPU upload temporaries and paper-preview arrays. | GT:20-93 |
| Tiled-stroke guards | `MAX_TILES=256`, `MAX_COPIES=256`; board footprint and repeated brush copies are refused above caps. RGBA8 equivalents alone would be 64 MiB for256 tiles, but buffers/copy-on-write versions can coexist. | Per-operation shape/allocation guard, not a standing GPU ledger. Engine enforces tile footprint for tiled painting; do not generalize this into a document-wide tile ceiling. | TP:10-21,41-56; GE:1476,1519 |
| Onion/board crop | Current path draws up to two adjacent-frame ghosts sequentially through one RGBA8 crop. Crop `w*h <= 8,388,608` and each side must fit `GL_MAX_TEXTURE_SIZE`; target payload <=32 MiB. | Same-size `prepare` reuses its texture. A size change releases old resources before allocation; release/context forget clear it. No accumulation of two ghost crop textures in this path. The id-to-id preview map is not a decoded-frame cache; layer/cel/projection residency above still exists. | GE:346-369,2024-2047; BP:8-43 |
| Region rendering | `MAX_REGION_PX=8,388,608`. Result4 + float scratch16 B/px =160 MiB at cap, excluding other concurrently held resources. Paper blocks256*32*4 =32 KiB, not another whole-region paper image. Oversized region is refused by Long-size guard before allocation. | Per-call CPU arrays; caller retains returned bytes. Texture readback/source arrays and encoder buffers are additional allocations, not included in this20 B/px formula. | RR:46-120,208-209,475-487; paper block processing later in RR |
| Animation export | `MAX_EXPORT_PX=2*MAX_REGION_PX` =16,777,216 decoded pixels =64 MiB at4 B/px. Per-format decoded-pixel estimate checked before rendering. GIF retains frame data; PNG sequence counts one cell; sheet accounts for cells plus packed output. | Per-export retained arrays can coexist with the renderer's scratch/result and encoding buffers. This is an admission bound on the modeled decoded pixels, not total export peak. | AE:390-475; sequence lifetime in AE:180-242 |
| Paper preview cache | Default24 entries, each side<=128, exact RGBA length. Default persistent pixel payload <=24*128*128*4 =1.5 MiB. | LRU eviction and clear remove references; GC releases arrays. Cache insertion/hit and returned crop use copies, so temporary extra crops coexist. Configurable `maxEntries` means1.5 MiB is a default, not universal hard ceiling. | PP:12-31,44,61 |
| Flood fill | `MAX_FILL_PX=8,388,608`; region/workA/workB =24 MiB, plus caller's RGBA reference32 MiB at cap. Input count guard refuses larger work. | Transient arrays; caller reference is retained, not copied merely by being passed. KDoc separately discusses the seed stack;24 MiB does not include its adversarial peak or JVM overhead. | FF:36-65,211-212 |
| Fill pen | Source tiles<=64, destination tiles<=128, pixels<=4,194,304, samples<=32,768. At RGBA8 this is16 MiB source/32 MiB destination payload; these are separate caps, not one summed peak guarantee. | Per-gesture checks before polygon expansion/coverage allocation, plus later raster/tile checks. Coverage, readback, replacements and GL upload may coexist. | FP:17-48; subsequent bounds in FP |
| Selection coverage | Per-axis span<=16384; coverage tile256*256*1 =64 KiB. At full dense span,64*64 coverage tiles =256 MiB before overhead. Source KDoc's "at most4 MB" does not follow from its own span/unit constants. | Span guard is not a4 MiB byte ledger. Sparse allocation may lower actual usage; this arithmetic is a dense upper payload possibility, not a measured selection allocation. | SM:354-365 |
| Archive reads | Entries<=20,000; document/thumbnail<=32 MiB each; strokes/ignored entry<=64 MiB; aggregate read budget1 GiB; chunk8 KiB. Tile payload sizes are separately validated. | File/decoded-input guards, not a resident-memory governor. Individual buffers can be retained after reading and coexist with engine uploads; aggregate1 GiB does not imply a safe1 GiB heap allocation. | JA:123-131,329-425 |

## Review corrections and remaining evidence questions

Coordinator corrections to the worker draft:

- 64 MiB /512 KiB is128 store tiles, not134.
- Plain-over-media is Lead change2; one-history is Lead change4 in R51's numbered
  wording. Refer to the actual ruling rather than the draft's conflicting labels.
- Dropped water tiles need not immediately release undo before snapshots or pooled
  textures; count those owners separately.
- The engine holds cel/projection maps. Absence of a decoded onion cache does not
  establish absence of all frame-related residency or sharing.
- Removed unsupported total ORA peak claims, speculative import/paging claims,
  abbreviated source paths, and an incorrect1 MiB-per-stroke-tile assertion.
- Retained estimates as estimates and count/shape guards as guards, not measured
  byte ledgers. Fixed the window census to distinguish RGBA32F, RGBA16F, RGBA8 and
  quarter-area beads.

Open questions for the gated governor work, without proposing its architecture:

1. What driver/JVM overhead and simultaneous-operation peak occur on the Note9?
   Texture/array payload arithmetic cannot settle that.
2. How will live paint, masks, all cel/projection ownership, payload, window, pools,
   transient render/export and undo be reconciled without counting references twice?
   Current separate counters do not answer this.
3. How will record/document-history byte sizes be defined when the two histories
   merge, including newest-step overrun and retained value graphs?
4. Re-audit after JB-2.40: this base's media accountant only sums layer float maps;
   payload per cel and the added ground store will change the accounting domain.
5. Reconcile the selection KDoc and separately cost fill seed-stack worst cases;
   neither prose claim is a measured bound.

Lead inputs: RL:749-777; BR:320-328,455-464; SPEC:27-63,121-124;
LD:982-1024. No governor specification or integration is supplied by this report.

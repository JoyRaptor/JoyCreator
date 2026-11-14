# Select on painted pixels: move, scale, turn, cut, copy, paste (design, 2026-11-14)

| | |
|---|---|
| **Status** | DESIGN. **Engine review pending**: sent to the paint-engine session 11-14 (directly and through the orchestrator); it is landing its own batch, so its answers come later and go into §R. Until then P1 (core only) may start; no engine slice (P2 on) is built. Nothing here is built |
| **Roadmap rows** | `tasks/joybrush/ROADMAP.md` SEL-PHASE-B (this spec), SEL-C3 (outside fade: separate), SEL-D23 (notices: separate) |
| **Supersedes** | The unlanded October proposal `specs/SELECT_PIXELS_DESIGN_20261023.md` (commit `c1c6d172d`, only on `origin/lead/select-integration-20261024`). Its findings are kept here (§4.3 soft edges, §6 memory, §7 lines); that file stays where it is |
| **Builds on** | `specs/JB-5.20e_select_ui.md` (D9, D10, D12, D16-D20, H4/H5, the owner's §K), `specs/JB-2.05_selection_transform_ui.md` + Lead ruling (one layer, same layer, one undo step), Codex's handback `reviews/CODEX_ENGINE_HANDBACK_20261113.md` §3, `specs/WET_SHEET_20261110.md` |
| **Source read** | `joy-creator` at `cd35fe09d`; `GlPaintEngine.kt` and `JbCanvasView.kt` are unchanged up to `origin/joy-creator` of 11-14. Line numbers are that source's |

## 1. What the artist gets (plain words)

Today a loop around painted pixels shows marching ants and the words "Paint can't be moved or edited yet. Only lines can."
After this work, on the Note 9, with the pen or one finger:

- **Loop some paint, then drag it.** It moves at once and stays in its own layer, under the layers above it, as it will
  look when it lands. Corners scale it, the handle below the box turns it, the role ring flips it. Let go and it lands.
  The box stays, so the next drag moves it again.
- **Moving it again does not blur it.** However many times it is dragged, scaled and turned while it is still selected,
  each landing is drawn fresh from the paint as it was when first picked up, never from the last landing.
- **One press of Undo takes back one drag.** The paint and the box both go back. Undo again takes back the drag before.
- **The row above the selection** (the owner's K9 order) does what it says: Fill · Duplicate · Delete │ Flip horizontal ·
  Flip vertical │ More (Copy, Cut, Paste, Invert selection, Adjust colour). Each is one undo step.
- **Copy, Cut, Paste.** Copy keeps the paint for this session, across drawings. Paste puts it back as a selection in the
  same place if that is on screen, otherwise in the middle of the view, ready to move.
- **Realistic paint moves too** (pencil, oil, dried watercolour). It looks exactly the same where it lands. In the first
  version the moved paint, and paint in the same patch around it, has **set**: a new wet stroke paints over it rather than
  pushing it around, the way old paint already sets when the paint room fills up. A later slice keeps it fresh.
- **Wet watercolour is never moved while wet.** Moving or deleting paint on a layer with a wet wash dries the wash where it
  lies first, as every other edit of that layer already does. Only selecting does not dry it.
- **When the phone cannot hold it all,** the app says so in plain words and offers a way on (§6.3). It never shows an error
  code and never just refuses.
- **Save and reopen**: what landed is ordinary paint, saved as everything else is. The selection itself is not saved.

Not in this spec's slices: painting inside the selection only (H4 paint clip, §9 P9), the fade outside the selection
(SEL-C3), the first-use notices (SEL-D23).

## 2. What exists today (anchored)

| Piece | Where | State |
|---|---|---|
| Select tool's pixel path | `SelectTool.kt:736-743` lifts on select, `:271-319` box drag and commit, `:370` fill, `:406` delete | Wired to a host that refuses: shows the honest toast |
| Host contract | `SelectionHost.kt:84` `liftPixels`, `:87` `paintClip`, `:131-145` `LiftedPixels` (setTransform / commit / clear / fill / release) | Keep this contract; extend it (§3.6) |
| Host stubs | `CanvasSelectionHost.kt:465` `liftPixels = null`, `:468` `paintClip` unused, `:470` `dimRest {}` | The blockers Codex named |
| Selection shape | `SelectionState.kt:73` `Pixels(loop)`: a vector loop; `SelectionMask.kt:37` coverage 0..255, 256 px byte tiles, shared `FULL_TILE` for full interior tiles (`:396`); `Lasso.kt` 4x4 antialiasing | Reused as is |
| Mask size check | `SelectionMaskAdmission.kt:11` `MAX_COVERAGE_TILES = 64` over the loop's whole bounding box | **Refuses a full-screen loop at 100 %** on the Note 9 (72+ tiles). The cap is real today: `Lasso.rasterise` makes a 64 KB byte tile for every tile of the box (`Lasso.kt` tile map, then `finalise` `SelectionMask.kt:426` swaps full ones for `FULL_TILE`), so the peak is the whole box. Fixed in P1 |
| Box maths | `Homography.kt:145` `fromQuads`; `SelectTool.kt:920` | Reused |
| CPU lift/warp | `Resample.kt:57` `lift`, `:144` `warp` | Only its own test calls it. Kept as the JVM reference for tests, not the runtime path |
| Line lift (the pattern to copy) | `GlPaintEngine.kt:3596-3760`: tiles set aside (`originals`), stand-ins shown (`rest`), lifted part drawn through a matrix, `settleLift()` (`:3614`) called by every reader and writer, budget refused before allocating (`:3639`) | Reused as the lifecycle. **Its overlay is drawn above the whole stack** (`drawLiftOverlay`, called at `:7474`, `:7835`): wrong for paint under other layers, so pixels preview in their layer's own tiles (§4.2) |
| One-step tile write | `GlPaintEngine.kt:2117` `replaceTiles`: settles lift, refuses mid-stroke, calls `beforeLayerWrite` (`:2121`, the wet sheet prints), honours region slices, refuses tiles crossed by editable lines (`:2134`), pushes ONE step with `settlePlainWrites` (`:2186`) | The model for the commit. The commit is GPU-to-GPU (no CPU byte maps) but keeps all of these rules |
| Tiles | `GlPaintEngine.kt:496-525` `Layer`: `tiles`, `cels`, `floats`/`celFloats` (media payload), `plan` (region slices), `projected`; `planeTiles` `:2672`; copies `:2779-2782` | Plain RGBA8 premultiplied, copy-on-write: the old texture becomes the undo "before" |
| Media payload | `MediaStores.kt:22-54`: RGBA16F stores `p0, p1, paper, w0, w1` (512 KB a tile); the composited look is the RGBA8 tile (`MEDIA_LOOK`, `GlPaintEngine.kt:206`); `settlePlainWrites` `:6932` drops a written tile's payload in the same step; `setOldMedia` `:7202` | P4 rides on `settlePlainWrites`; P5 replaces it for selections |
| Wet sheet | `WetSheet.kt`, printed by `printOverlay` `:7650`; `beforeLayerWrite` `:7582` wired to `MediaCanvas.settleSheet` (`MediaCanvas.kt:70`, `:382`) | Untouched. The commit calls the existing `beforeLayerWrite` |
| Undo | `UndoLog.kt:87` `Step` (any number of tile, slab, line changes; `sheet` mark), `:411` trim oldest over budget (newest kept); engine budget 192 MB (`GlPaintEngine.kt:235`, `:601`) | A commit is one ordinary `Step` |
| Save | `JbCanvasView.kt:3058` `snapshot(keepWet)`, `:3285` `readContents`, `BoardSnapshot.kt:13`, `JbArchive.kt:100-109` (raw deflated `.rgba` tiles, `.f16` stores) | Untouched; no new archive field |
| Line clipboard | `LineSelectionClipboard.kt:12` (session object), `LineCopy.kt:99` budget (2048 lines, 16 MiB); `JoyBrushActivity.kt:3037` "Paint selections can't be copied yet." | Pixel clipboard sits beside it (§3.5) |
| Phone proof | `bench/SelectBench.kt` (lines only), `BenchRunner.kt:219` | New pixel steps join it (§9) |

**Media save, current state (paint-engine session, 11-14, relayed by the orchestrator; anchor checked:
`GlPaintEngine.kt:6885` `mediaPlaneOfCel` answers the cel id for a shared cel).** On ordinary layers the realistic-paint
stores have not been saved since 10-11: after a reopen, old media paint is a picture (set). Animation frames keep theirs.
The fix waits on save-size measurements and streaming saves (ATLAS-SOLID). **This spec works in both states and never
changes that line** (§5.3).

## 3. Decisions (what the engine does, in the order the artist meets it)

### 3.1 Selecting costs nothing; the first move lifts

A loop makes the mask and the box only. Nothing is read from the GPU, no tile changes, the wet sheet keeps running. The lift
happens at the **first** drag, flip, Delete, Duplicate, Fill, Cut or Copy. Today `SelectTool.kt:740` lifts on select; the
host's `liftPixels` keeps its signature but returns a handle whose GPU lift is deferred to the first `setTransform` or
action. Why: most loops are made to look, to fill, or (later) to paint inside, and a lift on a layer with a wet wash
would dry it just for selecting.

### 3.2 What a lift is

For each tile key the mask's coverage touches on the active plane (the layer's shared plane in P2; a frame's cel from P6):

- **source**: the plane's current texture for that key. **Borrowed**, not copied: it becomes the undo step's "before".
- **lifted** = source x coverage (premultiplied, so a soft edge lifts a soft share). One new RGBA8 texture.
- **rest** = source x (1 - coverage). One new RGBA8 texture.
- An empty source tile (no paint there) contributes nothing and allocates nothing.

The lifted set and the rest set are kept, unchanged, **for as long as the selection lives and nothing else writes the
layer** (§3.4). The coverage tiles live on the GPU as R8 (64 KB each, only for edge tiles; full interior tiles use a
constant).

### 3.3 Drag = preview in the layer, release = the same pixels committed

During a drag the box gives a homography `H` (document to document). For every **destination** key in
`keys(rest) ∪ keys(warp(lifted, H))` the engine renders, into a transient tile, `rest ∘ warp(lifted, H)` (source-over,
premultiplied, bilinear; §4.1 for exact cases) and swaps that tile into the plane in place of the real one, exactly as the
line lift swaps its `rest` tiles (`GlPaintEngine.kt:3667-3677`). The compositor then draws the layer as always: in its
place in the stack, with its blend mode, opacity and mask. No compositor change.

On release, `commit()` does **not** render again: the transient tiles of the last frame **are** the step's "after"
tiles. Preview and commit are the same pixels by construction (AGENT_START rule 5). The step is one `UndoLog.Step`
holding a `TileChange` per changed key (before = the borrowed source, after = the transient), plus whatever
`settlePlainWrites` adds (§5.2). Commit at the identity (no move, no flip) pushes nothing and returns false.

After a commit the selection stays lifted: lifted and rest are kept, the box shows the new place, the next drag renders
from the same lifted and rest with the new cumulative `H`. **That is what stops repeated moves from blurring**: every
landing is one resample of the original, never a resample of a resample.

### 3.4 What ends the floating copy (and what does not)

The lifted and rest textures are a convenience, never truth. They are dropped (freed; the selection and its box stay) when:

- anything else writes this layer: a brush stroke, fill, delete or duplicate layer, merge, board change, the wet sheet
  printing (all of these already go through `beforeLayerWrite` / `settleLift` paths);
- Undo or Redo (any step, not only the selection's own);
- the drawing, layer or frame changes, or the graphics context is lost.

The next drag lifts afresh from the layer as it now is. A **pure read** (autosave, snapshot, thumbnail, export, a
save on the way out) puts the real tiles back for the read, exactly as `settleLift` does, and keeps the floating copy;
if a drag is under way when a read arrives, the read sees the last committed pixels and the drag carries on. (This is the
one behaviour the line lift does not have; §R asks the engine session whether to add a "read-only settle" or to keep
`settleLift` and accept that a save mid-drag drops the floating copy.)

### 3.5 The actions (each exactly one undo step)

| Action | Engine operation | Result |
|---|---|---|
| Move, scale, turn, corner-pin, flip | §3.3 with `H` | Paint at the new place; selection there |
| Delete | commit with lifted dropped: after = rest | Hole where the paint was, soft edge kept; selection ends (D16) |
| Fill | after = source-over of `argb x coverage` on the current pixels (`MaskPaint.units`, as the fill pen) | Selection stays |
| Duplicate | `liftPixels(keepSource = true)`: rest = source; the floating copy starts at +16 dp (D16) | One step adds the copy; the copy is the selection |
| Copy | CPU readback of lifted (premultiplied RGBA, coverage applied) into the pixel clipboard | No step, no change |
| Cut | Copy, then Delete | One step (the Delete) |
| Paste | Clipboard tiles become a floating lift with rest = the destination's pixels, placed per A1's rule | One step when placed; the pasted paint is the selection |
| Invert selection | mask only (`SelectionMask.invert`) | No step |
| Adjust colour | P10: shader on lifted, live, one step on release (H10) | Later slice |

**The pixel clipboard** (`android/select/PixelSelectionClipboard.kt`, new, beside `LineSelectionClipboard`): session
lifetime, survives switching drawings, holds tile bytes plus the loop and its document position, capped at **32 MiB of
heap** (the app heap is 512 MB and a save holds every store in it: `LayerNames.kt:117-121`). Over the cap: the words in
§6.3. The system clipboard PNG for other apps (D16) is a later, separate row.

**Cut** is not in the owner's K9 row; it is added under More because this job's brief asks for it. It is one tap he can
remove.

### 3.6 Contract changes (all inside Select's own files except the engine section)

`LiftedPixels` (`SelectionHost.kt:131`) keeps its five calls and gains:

```kotlin
/** The selected paint as clipboard tiles (premultiplied, coverage applied); null when it would not fit (the host says why). */
fun copy(): PixelClip?
/** Why the last call could not run, in the owner's words (§6.3), with the way forward the row offers; null = it ran. */
val lastRefusal: PixelRefusal?
```

and `SelectionHost` gains `fun pastePixels(clip: PixelClip, at: Homography): LiftedPixels?`. `PixelClip`, `PixelRefusal`
and the pure planning (`PixelLiftPlan`: which keys, how many bytes, which way forward) are new pure classes in
`core/select/`, JVM-tested.

## 4. Pixels, exactly

### 4.1 Exact cases are exact

A whole-pixel move, a flip and a turn by a multiple of 90 degrees copy texels (nearest, no filter): the paint lands bit for
bit. Anything else is bilinear in premultiplied space. Shrinking below half size reads a prefiltered copy of the lifted
tiles (one box-filter level per halving, made on demand, freed with the lift), so a small copy does not sparkle.
Enlarging stays bilinear (soft, never blocky: the owner's rule for the wet sheet, rule 16, applies in spirit).

### 4.2 Why the preview lives in the layer's tiles

The line lift draws its lines over the finished picture. Paint on layer 2 of 5 dragged that way would show above layers 3 to
5 and ignore its own blend mode and mask, and would land looking different from the drag: a preview that lies. Swapping
transient tiles into the plane makes the compositor show the truth. Cost: each drag frame renders one quad per destination
tile (two texture reads each); §6 bounds the count. If one frame takes over 24 ms the drag shows every second frame, and
release always renders the exact final frame before commit.

### 4.3 Soft edges (carried from the October note)

`lifted + rest = source` holds **by addition**, not by source-over: a half-covered opaque pixel splits into two halves
whose source-over gives about 75 % alpha. So: commit at the identity is a no-op by rule (§3.3), never a recomposite; a moved
lift lands by source-over on the rest, the convention painters expect; a 1 px nudge of a soft-edged loop leaves the edge
very slightly lighter where the halves no longer meet, as in other paint apps. Test: identity lift and release leaves every
byte unchanged, with soft coverage and with existing partial alpha.

## 5. Where it touches the engine, and what it never touches

### 5.1 Files

| File | Owner | Change |
|---|---|---|
| `androidkit/.../gl/select/PixelLift.kt` (**new**) | paint-engine session reviews; built by whoever it says | The lift state (borrowed sources, lifted, rest, coverage, transient tiles, cumulative H), render, commit, drop. Small, single-purpose, like `Lift` |
| `androidkit/.../gl/GlPaintEngine.kt` | paint-engine session | A short section: `beginPixelLift / setPixelLiftTransform / commitPixelLift / dropPixelLift / copyPixelLift`, each delegating to `PixelLift`; `settleLift()` (`:3614`) also settles a pixel lift; the commit's admission rules from `replaceTiles` (`:2118-2121`, `:2134`). No change to media, atlas, sheet, save, projection or compositor code |
| `androidkit/.../JbCanvasView.kt` | paint-engine session | Four queue wrappers like `beginLineLift` (`:1005-1023`). Not the media-stroke, snapshot or load code |
| `joybrush/shaders/jb_pixel_lift.frag` (**new**) | paint-engine session reviews | rest ∘ warp(lifted ⋅ coverage): one program, nearest or bilinear by a uniform |
| `core/select/PixelLiftPlan.kt`, `PixelClip.kt`, `PixelRefusal.kt` (**new**), `SelectionMaskAdmission.kt` | Select lane | Pure planning, budget maths, words; JVM tests |
| `androidkit/select/SelectTool.kt`, `SelectionHost.kt`; `android/select/CanvasSelectionHost.kt`, `SelectController.kt`, `SelectRowView.kt`, `PixelSelectionClipboard.kt` (new) | Select lane | Lazy lift, Cut/Paste rows, the words of §6.3; the host backs `liftPixels` |
| `bench/SelectBench.kt` (+ a new `bench/SelectPixelsBench.kt`) | Select lane | Device checks (§9) |

### 5.2 The live wet sheet

The commit, Delete, Fill, Duplicate and Paste call `beforeLayerWrite(layerId)` first (`GlPaintEngine.kt:2121`'s rule):
the sheet prints the wash where it lies, in its own step, undone together with the step under it (wet sheet rule 15).
Nothing in `WetSheet`, `MediaCanvas`, `MediaLayerEngine` or the overlay changes. Selecting and Copy do not call it (a copy
of a layer with a wet wash copies the printed tiles under it: the wash is not in the copy; P4's device check confirms the
words for this). The lift is begun only with no stroke, media stroke or fade in progress (`:3620`'s check).

### 5.3 The save path

Untouched: `JbArchive`, `BoardSnapshot`, `readContents`, `JoyBrushActivity.beginSave`, `snapshot(keepWet)`,
`mediaPlaneOfCel`. A commit leaves ordinary tiles (and, from P5, ordinary media stores) in the plane, which every save
already writes. No new archive entry or schema version. The floating copy is never in a file: every reader settles first
(§3.4). After a reopen the selection is gone (as for lines).

Both media-save states hold: while ordinary layers' stores are not saved, a reopened drawing's moved realistic paint is a
picture, the same as all its other realistic paint; frames, whose stores are saved, keep exactly what the commit left (set
in P4, fresh from P5). When the save fix lands, P5's moved payload is saved like any other payload with no change here.

## 6. Memory on the Note 9

### 6.1 Cost of a lift (RGBA8 256 px tile = 256 KB)

| Part | New memory per tile key | Notes |
|---|---|---|
| Source | 0 | Borrowed; it becomes the step's "before" |
| Lifted + rest | 512 KB GPU | Only keys with paint under the mask |
| Coverage | 64 KB GPU (edge tiles only) | Interior tiles use a constant |
| Transient destination | 256 KB GPU per destination key | Becomes the step's "after" on commit |
| Clipboard | ≤ 256 KB heap per copied key | Session cap 32 MiB |

A full Note 9 screen at 100 % (1440 x 2960 document px) is 72 to 91 tiles: about **45 MB** lifted + rest, **23 MB**
destination, under **6 MB** coverage, so about 75 MB. The same screen at 31 % zoom shows ten times the area: if it is all
painted, about 750 MB, which the phone does not have.

### 6.2 The allowance

Admission runs in `PixelLiftPlan` **before any readback, mask upload or allocation** (Codex's October rule: a size check
after allocating is too late), from the mask's tile keys, the plane's non-empty keys and the box's destination bounds.
Starting allowance: **96 MB of new graphics memory for one lift**, inside the engine's existing room accounting (the paint
room, `LineEditBudget`'s 192 MiB), never on top of it unseen. It is a starting number: P2's device check measures the real
headroom and timings and the number is set from that (§R asks the engine session for its view). Undo trimming is never
counted on to make room.

### 6.3 At the limit: words and a way on, never an error

Each limit has one sentence in the bar (above the selection, paper-coloured, K3) and one or two buttons. No technical words,
no number of megabytes.

| Limit | What the artist sees | Way forward |
|---|---|---|
| Loop bigger than the live allowance (before P8) | "That's more paint than this phone can carry in one go." | **Select less** (keeps the loop on screen to draw a smaller one). Zooming would not help: the cost is the paint's size in the drawing, not on screen |
| Same, after P8 | Nothing: the drag shows a lighter preview at screen sharpness, and on release "Placing the paint…" for the moment the full-size landing takes | — |
| Landing needs more room than is free | "Moving this much paint needs the room your oldest undo steps use." | **Make room** (trims the oldest steps, then lands; Undo still reaches this move) · **Cancel** (paint back where it was) |
| Clipboard over 32 MiB | "That's too much paint to copy at once." | **Select less** |
| The layer has editable lines (before P7) | "This layer mixes editable lines with paint." | **Move the lines** (switches the bar to Lines, K13) · **Turn lines into pixels** (Rasterize Down, then move it all as paint) |
| Tiled board, layer mask (before P6) | "Paint can't be moved while tiling is on." / "...on a layer mask yet." | **Turn tiling off** (as the existing tiling notice) / **Select on the layer** |
| Graphics memory fails mid-way | "The phone ran out of room for that move, so the paint stayed where it was." | **Try again** · **Select less** |

On every failure, the paint, the undo history and the selection are exactly as before (§8).

## 7. Layers that hold editable lines (P7, design only)

For a tile some line crosses, the engine keeps `tile = base ∘ items in seq order` (`NATIVE_LINES_V1`, `replaceTiles`
`:2134` refuses other writes there). A pixel move must therefore become ordered slab changes: the affected paint is
removed from its base/slabs and a new pixel slab at the next seq carries the landed paint, all in the same step, with every
untouched line kept exactly (the October note's mixed-item transaction, which needs a NativeLines transaction API). Until
then the row's two buttons in §6.3 are the way on. Captured-physical lines stay refused (`CanvasSelectionHost.kt:370`).

## 8. Undo, failure and context loss

- **One press, one step.** A drag, a flip, Delete, Fill, Duplicate, Cut, Paste: one `Step` each. Selecting, re-reading
  chips, Copy and Invert are not steps.
- **Undo moves the box back.** The tool keeps, per committed step, the loop before it (runtime only, keyed by the step).
  Undo or Redo of that step restores the loop, refits the box and drops the floating copy (§3.4). An Undo of an unrelated
  step keeps the selection as it is.
- **All or nothing.** The commit prepares every destination tile first; only then does it swap them in and push the step.
  A failure at any point frees only what this attempt made; the plane, the history and the selection are untouched (the
  `beginLift` catch, `:3680-3688`, is the pattern).
- **Context loss.** Every lift texture is transient: the lift is dropped, the selection (a loop on the CPU) stays, the next
  drag lifts again. A commit already pushed is ordinary history.
- **D23 notices** do not come from these operations.

## 9. Slices (each lands and is checked alone)

Device checks run on the **Note 9** (sandbox) through the Select bench's synthetic pen; real-pen and look checks are the
owner's. Every engine slice also runs the 23 realistic brushes and the existing Select bench (21 steps) unchanged.

| Slice | What the artist can do after it | Engine? | Device check |
|---|---|---|---|
| **P1** | A full-screen loop at any zoom is accepted (marching ants, no "too large"); the toast stays honest | No: `Lasso.rasterise` finalises each 256-row band as it finishes it (full tiles become the shared `FULL_TILE` at once), so the peak is one band plus the edge tiles; `SelectionMaskAdmission` then counts edge tiles (a perimeter bound) instead of the box; `PixelLiftPlan` maths. JVM tests: same coverage bytes as today for every existing lasso test; a 16k-square loop peaks near 20 MB (one band plus its edge tiles; today's path would need 256 MB) | Loop the whole screen at 100 % and 31 %: ants, no refusal; heap before/after logged |
| **P2 (first slice)** | **Move, scale, turn, corner-pin and flip paint on a plain pixel layer**, live in its layer, one undo step per drag, repeated moves without blur | Yes: `PixelLift`, the engine section, the shader, the host. Shared plane only; layers with lines, media payload, frames, tiling or masks show their §6.3 words | Bench `selpix`: paint 3 blobs on layer 2 of 3, loop one, drag/scale/rotate/flip, each step's tile hash vs a JVM `Resample` reference (exact for whole-pixel, flip, 90 degrees; within 2/255 for bilinear); Undo x 4 restores every byte; identity release changes no byte; 10 drags then compare with one drag to the same place (no blur build-up); full-screen lift at 100 %: peak graphics memory and frame times logged (sets §6.2's number); a save during the selection reopens with the committed pixels only; temperature logged |
| **P3** | Delete, Fill, Duplicate, Copy, Cut, Paste on plain layers | Small: commit variants + readback | Bench: each action one step, Undo restores; copy in drawing A, paste in drawing B; clipboard cap words shown |
| **P4** | All of the above on **realistic-paint layers** (pencil, oil, dried watercolour); a wet wash dries first | No new engine path: the P2 commit with `settlePlainWrites` and `beforeLayerWrite` | All 23 media brushes: paint, loop, move, Undo; the moved paint looks identical (look-tile hash), a new oil stroke over it does not smear it (the known "set" limit); wet wash then move: dries, one Undo takes back the move, a second the print + stroke (rule 15) |
| **P5** | Moved realistic paint **stays fresh** (wet brushes still push it); paint around it in the same patch stays fresh too | Yes, paint-engine session's design: payload stores warped with the look, rest payload weighted by (1 - coverage) instead of dropped; whole-pixel moves first | Oil: move a fresh blob, a knife drag over it pushes it; pencil: tooth still darkens; save and reopen on a **frame** (the only plane whose stores are saved today) |
| **P6** | Select on animation frames, tiled boards and layer masks | Yes: plane regions, wrapped writes, mask stores | Frame: paint moved off its frame's rectangle is trimmed (box shows it); tiling: wraps or the words; mask: grey moves |
| **P7** | Pixels on layers that also hold editable lines | Yes, NativeLines transaction (§7) | Lines over and under the moved paint keep their place and order; one Undo |
| **P8** | Very large selections (zoomed far out) move with a light preview and a short "Placing the paint…" | Yes: screen-sharp proxy preview, batched landing, "Make room" | Loop a full painted screen at 31 %: no refusal, landing time logged, Undo |
| **P9** | Painting inside a selection stays inside (H4) | Yes: coverage applied to each brush family's final contribution; families not proven say so | Per family: a stroke across the edge stops softly; the toast tells the truth |
| **P10** | Adjust colour on paint (H10) | Yes: shader on lifted, live | Hue drag live, one step |

Separate rows, not slices of this job: SEL-C3 (fade the rest) and SEL-D23 (first-use notices).

**First slice: P1 then P2.** P1 needs no engine file and can land at once; P2 is the first engine slice and is built only
after the paint-engine session's yes (§R).

## 10. Done means (whole job)

Every row action in K9 works on plain and realistic layers on the Note 9 with one undo step each; the bench's tile hashes
match their references; a save and cold reopen shows exactly the committed paint; no refusal anywhere without the §6.3
way forward; STATE row written; the owner has tried it with his pen. None of that is claimed by this document.

## §R. Review by the paint-engine session

Questions sent 11-14:

1. Transient tiles swapped into the plane during a drag (§3.3, §4.2): acceptable in `planeTiles`, and is there a cheaper
   or safer place than the swap (e.g. `invalidateProjection` cost on layers with a region plan)?
2. Reads during a live drag (§3.4): add a read-only settle that keeps the floating copy, or keep `settleLift` as is?
3. Media layers (P4): is `settlePlainWrites` dropping the payload of every touched tile the right first behaviour, and is
   P5 (warp payload, weight the rest by 1 - coverage) the right second one, or does a moved `paper` store need other rules?
4. Wet sheet (§5.2): is calling `beforeLayerWrite` at commit, and never at select, right? Anything in `withOverlayStamped`
   or rule 17 that a lift must respect?
5. Memory (§6.2): is 96 MB of new graphics memory per lift sane next to the paint room and the 192 MB undo budget on the
   Note 9, and which existing accounting should the lift report into?
6. Files (§5.1): who builds the engine section and `PixelLift.kt`, and when, given your current work (streaming saves)?

_Answers: pending (11-14). The session is landing its own batch; its answers are folded in here when they come. Owner
decisions owed: none to start; the Cut button (§3.5) and the "set" behaviour of moved realistic paint until P5 (§1) are
his to keep or change when he tries them._

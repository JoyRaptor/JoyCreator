# JB-5.20 — Lines in the one layer: editable strokes and pixels share a layer (R51 Phase 3)

| | |
|---|---|
| **Tier** | T1 (design by the Lead; slices below say who builds each) |
| **Status** | 📝 Design, Lead 2026-10-07. Slices a–c may be dispatched once JB-2.40 has landed; d–f are the Lead's |
| **Needs** | R51; JB-2.40 (landed first: it also changes the doc model); JB-5.12 (InkTiles), JB-5.13 (FillTrace), JB-5.14 (numbers); the built ink core (JB-5.01, 5.02, 5.03, 5.03a, 5.10, 5.11) |
| **Reads** | `design/ONE_LAYER_MODEL_BRIEF_20261007.md` §4–§8; `research/R5_concepts_infinite_painter.md` §1.6 (Concepts selection), §2 |

## Goal

The owner, verbatim (brief §3 Q11–Q13):

> *"I would either have to have a … separate section for vector work or just fold it in. That way I'm not having to
> rebuild everything twice and the an- animation tools uh, naturally flow in it."*

> *"concepts is basically my poster child for the vector side of everything. And Infinite Painter is my poster child
> for everything on the raster side."*

> *"I can select a line, I can nudge any part of it … it doesn't smudge the pixels, it actually moves the underlying
> vector line while keeping all the pressure, velocity, and tilt … at any time I can change it, let's say from a pen
> stroke to a pencil stroke … to a filled shape."*

After this row: on any layer, a pen line stays a line (select it, nudge it, drag its tip, slice it, recolour it, swap
its brush) while paint brushes beside and over it make pixels, in the order they were drawn. There is no vector layer,
no vector mode and no vector section. A layer that holds only pixels behaves, saves and costs exactly what it does today.

## What is already built (do not rebuild)

`StrokeRecord` and `StrokeCodec` (raw samples, seed, colour, width; smoothing re-applied on replay), `InkReplay` (the
live dabber, re-run exactly), `InkRaster`, `StrokeEdit` (reshape, re-weight, re-brush, recolour, keeping id and seed),
`InkEditSession` (records in order, tap/band select, drag with soft falloff, one step per gesture), `StrokePicker`
(tap again to cycle), `VectorEraser` (partial, whole, back to the crossing; R28's speck rule) and `InkErase` (JB-5.11:
ink erases lines, paint erases pixels; an unreplayable line is left alone and reported). Codex is adding `InkTiles`
(5.12) and `FillTrace` (5.13). None of it reaches the screen yet: the view does not capture records (JB-5.01b) and the
app refuses to open an INK layer.

## Decisions

**D1 — A cel holds pixels and lines, ordered by a sequence number.** Every line record and every pixel slab (D2) has a
`seq`, increasing within the cel. Time order decides what covers what; there is no "above/below" setting. Editing a
line keeps its `seq` (Concepts keeps z-order on edit too).

**D2 — Slabs are per tile, and a new one starts only where it must.** A slab is one RGBA8 tile of pixels with a `seq`.
A pixel write lands in the tile's top slab, unless a line with a higher `seq` than that slab crosses the tile: then the
write starts a new slab on that tile only. So a pure-pixel cel has one slab per tile, which is today's tiles exactly
(same bytes, same archive entries); a pure-line cel has no slabs; slabs multiply only on tiles where paint and lines
really interleave.

**D3 — The look is a cache.** A tile's look is its slabs and the lines that cross it, composited in `seq` order. A tile
with no lines needs no cache: its single slab IS the look, so pure-pixel layers pay nothing. Looks of tiles with lines
are saved with the file (opening never replays), and rebuilt only for tiles an edit dirtied.

**D4 — Lines are re-rendered on the GPU with the live stroke's own dabs.** JB-5.01 proved `InkReplay.dabs` is the live
dab list element for element, so replaying them through the same GL stamping gives the same pixels. A re-rendered tile
then cannot show a seam against a neighbour that was not re-rendered. `InkTiles` (CPU) is for export and tests, where
every tile is drawn the same way.

**D5 — The drawing keeps its own copy of every brush its lines use.** A record names a brush by id; the document stores
a frozen copy of that preset. Editing a brush in the drawer never changes lines already drawn, and re-brushing (R20) is
the only way a line's brush changes. Without this, a later re-render of one tile would draw half a line with the old
brush and half with the new one. It also makes a drawing open the same on another phone.

**D6 — Which brushes make lines.** A brush says so with one flag, `lines`, defaulted by its shelf and switchable in the
brush's own settings (hold a brush → its settings; the switch reads "Editable lines"):
- **Lines by default:** Inks & pens, Markers, the plain (stamp) pencils, the fill pen, shapes, flood fill (D10), and a
  stroke watercolour (a stamp brush with a multiply-type blend; brief Q14/Q19: overlap darkens because each line
  composites over the ones below it with its own blend, so it stays editable).
- **Pixels always:** media (realistic pencil, watercolour, oil: they keep state, R51.7), smudge and push, the wet
  engine, blur, clone, paste.
- **Paint and Airbrush shelves: pixels by default** (the Infinite Painter side; thousands of soft strokes as records
  would make files bigger and edits slower: brief red team 7 and 12). Owner check Q2.
- Pixel brushes wear the small "bakes to pixels" badge in the drawer (R51.7). The first time someone paints with one,
  a single toast: "Brushes that blend with what's underneath become pixels. Everything else stays editable." Shown once.

**D7 — One eraser, both kinds.** Where it passes, it cuts lines (JB-5.11, the chosen VectorEraser mode) and erases
pixels in every slab of the touched tiles, as ONE undo step. Nothing it touches can reappear when a line moves later.

**D8 — Pixel tools that read declare one of two behaviours (R51.5).**
- **Ignores lines:** the tool runs on each slab of a tile separately, so it never reads a line and never copies one into
  pixels. "Smudge (paint only)", blur and the media brushes behave this way. A media stroke is an ordinary pixel write
  (new slab above lines per D2), so wet paint glazes over a line and never lifts it.
- **Consumes lines:** every line the tool's footprint touches is first baked WHOLE into a slab at that line's own `seq`
  and removed from the line list; then the tool runs. "Smudge" (the default one) behaves this way. The first time, a
  toast: "That line became pixels." Never part of a line: a partial bake leaves a ghost when the rest is moved.
- So there are two smudge brushes in the drawer (owner, R51.5): **Smudge** (consumes lines) and **Smudge paint only**
  (ignores lines).

**D8a — A baked line keeps its operator (Lead, 2026-10-07 23:40, answering Codex's stop on 5.20b).** Codex was right:
a Multiply, erase or behind line stored as a plain NORMAL slab changes how it looks. The rule:
- **A line composites as one unit.** Its dabs build the line's own stroke buffer, then that buffer is applied to the
  tile ONCE with the line's operator (normal, erase, behind, or a blend mode). This is what `InkTiles` already does
  (`InkRaster.stamps` then one `Blend.apply` per pixel). For normal, erase and behind it is identical to dab-by-dab
  painting, because those are Porter–Duff "over" operators and associate; for blend modes it is the definition (there
  are no dab-level blend modes on lines).
- **Every slab carries an operator**, `op` (default normal). Baking a line makes a slab whose bytes are the line's
  stroke buffer and whose `op` is the line's operator, at the line's `seq`. The composer applies a slab exactly as it
  would have applied the line, so baked and unbaked tiles are bit-identical when both are composited in one float pass
  per tile and quantised once at the end (a 5.20b test).
- **D2 amended:** a pixel write enters the tile's top slab only if that slab is a normal slab with no line above it;
  otherwise it opens a new normal slab. Non-normal slabs never merge with anything. Two adjacent normal slabs with no
  line between them may merge.
- Brush blend words stay `normal | erase | behind` for now. The stroke watercolour's multiply (D6) is a later
  brush-format addition with exactly these stroke-level semantics.

**D9 — Undo is one history.** `UndoLog.Step` gains `lines: List<LineChange>` (`celId`, `id`, `before`, `after`, each a
record or null). `InkEditSession` loses its own 50-step stack and emits these steps. Undoing a line step restores the
records and re-renders the tiles they cross (look tiles are not kept in undo, unless JB-5.14 shows re-rendering is too
slow, in which case the step also carries the before-looks). One press, one step, always.

**D10 — Flood fill makes a shape.** Tap-fill traces the filled region (`FillTrace`, JB-5.13) into a filled line at a new
`seq`, tucked 0.75 doc px under the line work. Its colour stays editable. The fixed-geometry limit stands for now:
moving the lines that bounded it does not move the fill (brief §4.6).

**D11 — Selecting.** One Select tool, Concepts-style and live (popup above the box, direct manipulation, no confirm,
R5 §1.6):
- Tap on a line selects it; tap again in the same place cycles to the next line under the finger (`StrokePicker`).
- A lasso selects the lines it encloses. If it encloses no line, it is a pixel selection (today's raster selection),
  so there is no mode switch.
- With lines selected: move, scale, rotate; Nudge (soft falloff, size slider); drag a tip; recolour (pick a colour);
  swap brush (pick a line brush, R20); re-weight; delete; Rasterize Down.
- Slice is the eraser at width 0 (it splits a line without removing anything), as in Concepts.

**D12 — The two boundary buttons (owner, R51.8)**, in the layer menu:
- **Rasterize Down:** the selected lines (or, with none selected, every line on this layer's current cel) become
  pixels, each at its own `seq`. The picture does not change; those lines stop being editable. One undo step.
- **Flatten Lower:** this layer and every layer below it become one pixel layer, all lines baked. One undo step.
  (Owner check Q1: this is the brief's reading of "collapse it and below to raster".)

**D13 — INK layers retire.** A file's INK layer opens as an ordinary layer whose cels hold only lines. `LayerKind.INK`
stays in the enum for reading (R3); nothing writes it again. The app stops refusing to open such layers.

**D14 — The file.** The next DOC_VERSION (assigned at landing). A cel gains a slab list (tile key plus `seq`, beside
`tiles`, which keeps meaning "the bottom slab", so a pure-pixel cel writes exactly what it writes today), its line
records (StrokeCodec v3: v2 plus `seq`), and look entries for tiles with lines. The document gains `brushes/` (D5).
Record ids are unique within a cel (validation; the picker does not rely on it, JB-5.02 fix).

**D15 — Boards.** Lines live in frame cels like pixels do. A duplicated frame keeps every line's id, which is what makes
the owner's tween idea cheap later (brief §9 #2): frame 2's line `a` is frame 1's line `a`, moved. Tweens are their own
row after this one.

## Build slices

| Slice | What | Who |
|---|---|---|
| 5.20a | Core doc: cel slabs, records with `seq`, StrokeCodec v3, document brush copies, validation, INK → ordinary layer on read | T2 dispatch (Codex) after JB-2.40 lands |
| 5.20b | Core composer: per-tile item order, the D2 slab rule, D8 ignore/consume operations, Rasterize Down; CPU reference with hand-worked pixels. ✅ `core/vector/CelComposer.kt` (Lead, 2026-10-08; 9 tests, the bake proved exact by mutation). Flatten Lower moved to 5.20f: it is a layer composite, which is `RegionRenderer`'s job | Lead (taken while Codex was idle) |
| 5.20c | Core undo: `LineChange` in `UndoLog`; `InkEditSession` emits steps. ✅ half one (Lead, 2026-10-08): `UndoLog.LineChange` with seqs, folded by `mergeNewest`, counted in `heldBytes`, kept by `extendNewest`/`replaceInNewest`; `CelComposer.applyLines`; 6 tests. Half two (`InkEditSession` emits these instead of its own stack) lands with 5.20d, where the engine owns the history | Lead |
| 5.20d | Engine: slabs and look cache in `GlPaintEngine`, GPU replay (D4), record capture at pen-up (JB-5.01b), the eraser on both (D7) | Lead (R30 lock order: these files are the Lead's) |
| 5.20e | Screen: the Select tool, Nudge, tip drag, Slice, the layer menu buttons, the badge and toasts, the two smudges, flood fill wiring | Lead |
| 5.20f | Export: `RegionRenderer` draws slabs and lines per tile through `CelComposer.look`; Flatten Lower as a pure document + tile function on `RegionRenderer` | T2 dispatch after 5.20a |

## Notes for 5.20d (Lead, from reading the live path, 2026-10-07)

- **Where a record is captured:** in `JbCanvasView.feed`, after the guide snapper (`snap?.map(s)`) and before
  `feedOne`, so a line drawn against a ruler replays against the ruler. Not after the brush's response curves: those
  belong to the brush, and `InkReplay` now applies them itself (fixed 2026-10-07; the replay had skipped them, so a brush
  with a pressure curve would have redrawn at another weight). Never predicted samples.
- **What goes in:** `seed` = the seed `startStroke` gives `BrushDabber` (today `SystemClock.uptimeMillis()`); `smoothing`
  = the stroke's `amount`; `screenPerDoc` = `view.zoom` at pen-down; colour = `colorArgb ?: brush.argb`; `widthScale` 1.
- **What is not a line yet:** the hard-coded `Brush` path (no preset; retire it or keep it as pixels), the tuft engine
  (not replayable by `InkReplay`; pixels until it is), anything on a mask. The fill pen already keeps its raw samples
  (`fillSamples`), so it becomes a filled line directly.
- **The engine keeps media stores per layer, not per cel** (`GlPaintEngine.Layer.floats`), which JB-2.40 slice 3 has to
  change for frames; slabs (D2) must be per cel from the start.

## Gates

- **Regression:** a layer with only pixels gives bit-identical pixels, files and undo to today (the GPU checks stay at 0
  mismatches).
- **One press, one step** for every gesture in this spec.
- **Speed on the Note 9 (from JB-5.14, then the device):** editing one line under 1,000 others re-renders its tiles in
  under 100 ms; opening a drawing is no slower than today (looks are saved).

## Owner checks on the Note 9

1. Ink a face with a pen; paint colour over and under it with a paint brush on the SAME layer. Select the nose line and
   nudge it: it moves, and the paint does not.
2. Drag the tip of a line; slice a line in two; swap a line from pen to plain pencil to fill pen and back.
3. Smudge across a line with Smudge paint only (the line stays a line) and with Smudge (it becomes pixels, with the toast).
4. Tap-fill inside line work: no hairline gap; recolour the fill afterwards.
5. Rasterize Down, then Undo. Flatten Lower, then Undo.
6. Watercolour over a line: the line shows through, glazed; it does not bleed.
7. Save, close, reopen: everything still editable, and it opens as fast as before.

## Questions for the owner

- **Q1:** Flatten Lower = "this layer and everything below it become one pixel layer". Right, or did you mean only this
  layer and the one directly below?
- **Q2:** Should the Paint and Airbrush shelves make pixels by default (my pick: painting stays light and fast), with the
  "Editable lines" switch in each brush's settings for when you want one as lines?

# JB-2.40 — Media on any layer: the MEDIA kind becomes a per-tile payload (R51 Phase 1)

| | |
|---|---|
| **Tier** | T1 |
| **Status** | 📝 Spec, Lead 2026-10-07 — builder: the media session ("Realistic brush engines"); Lead reviews |
| **Needs** | Media port steps 1–4d part one (landed, through 4fdb608d); R51 |
| **Owner area** | `core/doc` (DocModel, DocOps, DocJson), `core/brush/BrushRules.kt`, `core/layers/LayerNames.kt` (LayerBudget), `core/media/*`, `androidkit/gl/GlPaintEngine.kt` (media paths only), `androidkit/gl/media/*`, `androidkit/JbCanvasView.kt` (media paths only), `androidkit/io/JbArchive.kt`, `androidkit/io/BoardSnapshot.kt`, `joybrush-android/.../JoyBrushActivity.kt` (the slots line only) |
| **Do not touch** | root's fill hooks in `JbCanvasView` (startStroke/feed/finishStroke fill branch), `tools/FillPenRaster.kt`, `FillPenPreview.kt`, `BoardRuntimeController.kt`, `ExportChoiceSheet.kt`; anything under `core/vector` or `core/stroke` (Codex's queue, LEAD_DESK 2026-10-07) |

## Goal

The owner, verbatim (brief §3 Q1, Q2, Q4, Q5):

> *"my last working mental model is just having one type of layer … I only had on one layer when I was testing the brushes."*

> *"why does the layer need a mode at all? We can just have whenever a tile gets touched with a fancy brush, it just goes
> into fancy mode for that tile … If a drip drizzles and touches and uh, a tile below it, that tile becomes a fancy tile."*

> *"I'm wondering how many people are actually going to want to come back to a wet canvas after closing their app.
> They're probably just going to expect that it's dry."*

After this row, on the Note 9: open a new drawing, and on **Layer 1 alone** draw with an ink pen, a realistic pencil,
watercolour and oil, in any order. No layer appears by itself, nothing is refused, the layer counter is a plain count,
and one press of Undo takes back one stroke.

## What stays exactly as it is

The float stores and their names (`<id>#p0 #p1 #paper #w0 #w1`), half floats at rest, laziness per medium, the window
and its reservation, the spread cap, dried-water drop, running-water undo (`extendNewest` / `replaceInNewest`), the
shaders, the look under the layer id, the 256 MB payload ceiling (`mediaBudgetBytes`, until the memory governor row),
`windowFits`. The compositor still sees ordinary pixels: **no media branch in drawComposited** (contract point 1).

## Requirements

1. **No kind.** A media brush paints on the active PAINT layer. Remove `addMediaLayerStep`, the auto-make branch in
   `JbCanvasView` (~:1096) and the `mediaMadeLayer` / `mediaLayerFailed` bookkeeping. A media brush on an INK layer is
   refused in words, as today (INK merges into the one kind in Phase 3, JB-5.20).
2. **The payload is per tile, per cel.** `Cel.floatTiles` is legal on a PAINT cel (DocOps rule 8b changes accordingly).
   A tile gains a payload the first time a media stroke, its water or its drip writes there, and only then.
   **It must work in board frame cels:** lift the `JbCanvasView.refusalFor` line (~:1698) that refuses media on frames,
   and `BoardSnapshot`'s media special case becomes "a cel with float tiles".
3. **Existing pixels under a new payload are kept exactly.** The first media touch on a tile that already holds plain
   pixels must not change one untouched pixel of it, not even by a rounding step. (Rendering a fresh, empty payload over
   the tile would erase it; converting it through Kubelka–Munk and back would shift its colours.)
4. **Plain writes over media act per pixel (R51.4).** Every non-media pixel write on a tile with a payload (stamp
   brushes, the plain eraser, smudge/push, fill and the fill pen, paste, transform, clear) leaves a correct look and a
   payload that can never render over it later. **§Q1** is how; the outcome must hold for all of them.
5. **A safety net for missed paths (brief red team 1).** Any tile write that does not go through the §Q1 step must make
   the payload discard itself for that tile at the next media render, keeping the current look as plain pixels (a
   version number per tile, or an equivalent you prefer). Lossy, never wrong.
6. **Saves are dry; the session is not.** A save writes p0, p1 and paper for payload tiles as if the water had settled
   (floating pigment deposited, no colour lighter on reopen than it was on screen) and writes no w0/w1. The live wash
   keeps flowing after a save. The look saved is the look shown.
7. **No layer cost.** Remove `LayerBudget.MEDIA_SLOTS`, `slotsFor`, `slotsUsed`, `roomFor` and the "counts as 7"
   message; the column's counter is a plain layer count (`JoyBrushActivity` ~:1121). The media ceiling still stops a
   stroke politely when full (`mediaFullMessage`).
8. **Brush rules.** `BrushRules.refusalFor` loses its MEDIA branch. What stays: on INK, only stamp and fill (R20) until
   JB-5.20.
9. **Files.** The next DOC_VERSION (9 today; read the number at landing, ROADMAP R30.3). A v8 file with MEDIA layers opens with them as PAINT, keeping their float tiles, so the
   owner's test drawings still open. A v9 file never writes MEDIA. `EnumFreezeTest` keeps MEDIA (R3).
10. **Undo.** Every requirement above that changes tiles does it inside the stroke's one step; a plain stroke over media
    that touches the look, the payload and anything else is still one step.

## §Q1 — the media session decides, and writes the answer here before building

How does a plain write sit on media paint? The two candidates:

- **(a) Deposit.** The plain stroke is written into the paint model as dry paint, so the look is always rendered from
  the state. Watch requirement 3: inverse Kubelka–Munk of arbitrary RGBA (and of blend-mode brushes) must not shift
  untouched pixels.
- **(b) Base plane and fix.** A payload tile keeps a `base` RGBA8 store (lazy) holding the plain pixels under the media;
  look = media over base. A plain write on the tile bakes the current look into base **within its footprint** and clears
  the paint state there (paper crush may stay); outside the footprint nothing changes. Water may later carry pigment
  back over that area, which is physically fair (wet paint over dry gouache). Requirement 3 is then free: the first
  touch copies the tile's pixels into base.

The Lead leans (b) for exactness and simplicity; (a) only if it can meet requirement 3 exactly.

**DECIDED 2026-10-07: (b), as the media session specified it ("#g ground").** Deposit was rejected on the media
session's reading of `jb_media_render.frag`: Normal blend is linear and Kubelka–Munk over a ground is subtractive, so a
plain stroke would change colour where it crosses a tile edge or a faded wash edge; the look is shade-multiplied (lamp,
impasto shadow, gloss, sheen, glints) and cannot be inverted; every pixel writer would need its own inversion; and every
plain stroke would need the GPU window. The rule:
- **`<id>#g` ground**, RGBA8 premultiplied, 4 B/px, only on tiles with media state; saved (re-wetting after reopen needs
  it). The look is render(state over ground); Kubelka–Munk's Rg = ground over the paper colour.
- **Media on plain:** the first media touch on a tile copies its current look into `#g`; the other stores start empty.
  Watercolour over ink line work therefore glazes it (requirement 3 holds exactly).
- **Plain on media:** wherever the plain write changed a pixel (coverage > 1/255), `#g` takes the new composite from the
  look and p0, p1, the flakes (paper g/b) and w0/w1 are zeroed for that pixel, in the same undo step. Crush (paper r)
  stays: it is the paper's dent and shades nothing without flakes or body. Identity to test: in the app `u_relief = 0`, so
  render(empty state, ground) returns the ground exactly.
- **Media eraser:** keeps its scaling path and scales `#g` too, so a soft erase leaves media live.
- **Whole-tile operations** (transform, filter, merge down, opacity bake): bake the whole tile (`#g` = look, stores
  dropped). Transforming the stores themselves is a later row.
- Known limit, unchanged: Rg is the ground over the flat paper colour, not over the layers below, which is the same
  approximation the look's alpha already makes.

## Tests (core and androidkit, counted from the XML reports)

- Validation: PAINT with float tiles is valid; INK with float tiles is not; a v8 MEDIA layer decodes as PAINT with its
  float tiles; a v9 encode never writes MEDIA.
- BrushRules: a media brush on PAINT is allowed; on INK refused; smudge, fill and stamp on PAINT allowed.
- LayerBudget: the counter is a count; no slot API remains.
- Requirement 3, 4 and 5 by hand-worked pixels on the CPU side where they can be (the base/fix arithmetic in (b) is
  plain RGBA and testable without a GPU).
- Requirement 6: a save of a wet tile reopens with no w0/w1 entries and with the floating pigment deposited.
- The GPU blend and mask checks (`tools/blend_gpu_check.js`) still give 0 mismatches.

## Owner checks on the Note 9 (the Lead runs them with him)

1. New drawing, Layer 1 only: ink pen, pencil, watercolour, oil, in that order and then reversed. No new layer appears.
2. A pen line across a wet wash: the line is on top and stays crisp; the wash keeps moving round it.
3. Watercolour over existing ink: the ink is untouched outside the wash, and glazed (not erased) inside it.
4. Save while a wash is running; close; reopen: dry, and the colours match what was on screen.
5. After reopening, lift a highlight out of the dried wash with a wet brush.
6. Fill inside pencil line art on the same layer.
7. An animation board: watercolour on frame 2 only; frame 1 untouched; save and reopen.
8. One press of Undo per stroke throughout.

## Out of scope

Editable lines in the same layer (JB-5.20), the "bakes to pixels" badge (JB-5.20), the single memory governor (its own
row), paging payloads to the CPU (M5.3d), tile dedupe across frames.

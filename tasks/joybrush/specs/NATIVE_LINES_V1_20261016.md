# Editable lines v1: native lines (lead design, 2026-10-16)

The owner wants editable strokes now, to judge vector quality and the editing experience:
- draw with the line brushes (ink, the stamp-engine brushes, his watercolour line brush, and the eraser);
- then select, move, rotate, scale, recolour, re-brush, re-weight, reshape and delete;
- with one undo per press, and keep the lines after save and reopen.

## Why not Codex's joint root
Codex's captured / recorded-scene path (RecordedScene, joint publication, promotion) moves the WHOLE engine into a second
storage mode. In that mode native painting, media, boards, paper and layer operations all refuse until each has an
adapter (LIVE_CAPTURE_NEXT_20261016.md lists them). Its captured lines also allow only integer translation, so no
rotate, scale or re-brush. That design is right for a later exact-provenance phase, but it is weeks from usable.

## The v1 model: the engine's own tiles stay the truth for pixels
- **Pixels, history and boards unchanged.** A plane is a layer's cel (shared, or one animation frame). Its ordinary
  RGBA8 tiles in `GlPaintEngine` stay what the screen, compositor, boards, thumbnails, eyedropper and export read.
- **A plane may also hold LINES**, in a new engine-side `NativeLines` store:
  - its `StrokeRecord`s in seq order (seq ≥ 1);
  - a **base**: a copy of the plane's tiles from before its first line (seq 0; Codex's bottom slab);
  - each line's **footprint** (tile keys), cached.

  Invariant: for every tile some line crosses, **tile == base ∘ lines in seq order**, composed by
  `composeRecordedTile`.
- **Drawing a line.** The stroke paints live exactly as today: same dabber, same latency. At pen-down,
  `StrokeCapture` records the raw samples (post-guide, pre-smoothing), as the diagnostic capture already does. It
  freezes the effective brush against the document's own brush registry. `endStroke` pushes ONE step: its usual tile
  changes plus a `LineChange(add)`. The first line on a plane snapshots the plane's current tiles as its base.
- **Editing lines** (replace / add / delete; the Select tool calls these):
  - affected tiles = old footprints ∪ new footprints;
  - re-render each affected tile as base ∘ (every line crossing it, in seq), using `prepareRecordedLine` units and
    `composeRecordedTile`, clipped to the plane's region slices;
  - ONE step: tile changes (copy-on-write) plus line changes.
- **Undo / redo of a line step**: put the before/after tiles back, as any stroke does, and put the before/after records
  back in `NativeLines`. Nothing is re-rendered, so undo is instant. (`recordedHistory` stays only for a bound
  RecordedScene.)
- **Which strokes record.** A brush whose replay `prepareRecordedLine` admits, when "Editable lines" is on:
  - stamp with a procedural tip, or watercolour line;
  - no whole-stroke `line` shaping;
  - blend normal / erase / behind / multiply / screen;
  - not a mask target, not an armed Tile board.

  An eraser stroke is a line too (blend erase), so it stays in order and edits correctly. Other strokes on a plane that
  holds lines (smudge, media, image tips) are refused in words for v1; the owner gets a sentence, never a silent
  flatten.
- **Save.** Codex's doc v11 format, unchanged:
  - `Cel.tiles` = the base for tiles lines cross, else the plain tile;
  - `Cel.looks` = the composed tile for tiles lines cross;
  - `strokes.jbs` = the records;
  - `JbDocument.brushes` = the frozen brushes.

  Open: the tile store ← looks (else tiles), base ← tiles under looks, records ← strokes. No replay at open.
  Ordinary records only; captured stays refused. Recovery and autosave go through the same path.
- **Regions.** Lines on a regional layer (animation frames) are clipped at re-render to the plane's current region
  slices. Boards lock after placing (R50). An unlocked, moved board re-renders its lines against the new geometry, an
  accepted v1 limit; Codex's captured domains remain the exact answer later.

## Select host
`SelectionHost` is backed by a canvas-side host:
- an immutable UI mirror of the active plane's lines (records plus `InkReplay` geometry);
- edits are submitted to the GL thread and resolve on publication. The UI keeps the old selection until the result
  comes back, then refreshes.

v1 limits:
- `liftLines` returns null: the box drags, and pixels update on commit.
- `bakeLines` and `liftPixels` say "not yet".
- `adjustColour` stays off.

v1.1: live lift preview. Re-compose without the selected lines once at lift; draw their own texture transformed during
the drag.

## Proof
- JVM: NativeLines bookkeeping (seq assignment, footprints, line-change apply and revert, save/open mapping).
- GPU: on the Note 9 bench, draw N lines, then for each edit compare the edited tiles with a full re-render from records
  (exact), and check undo restores bytes exactly.
- Owner: the test list in the delivery note.

# Editable lines v1.2: hardening work order (lead, 2026-10-17)

Source: the adversarial review of v1 at 1d1d4e19f (19 findings). Base: lead/claude-lines2. That branch carries the
v1.1 live drag preview (beginLift / setLiftTransform / previewLift / endLift; settleLift guards).

Read NATIVE_LINES_V1_20261016.md first. The invariant stays: for every tile some ITEM crosses,
**tile == base ∘ items in seq order**. Items are now lines AND upper pixel slabs.

## Items, in this order. One commit each, with JVM tests where testable.

**H1. Upper pixel slabs in NativeLines.**
- A plane holds, besides records and bases (seq 0), slabs at seq ≥ 1: `seq → (op, Map<tileKey, texture>)`. Seqs are
  shared with records (never two items at one seq).
- Ops: the existing set (normal, erase, behind, multiply, screen), which `composeRecordedTile` already applies. Every
  composition (editLines, beginLift, previewLift helpers) folds slabs in by seq, with the plane's region clips like lines.
- Undo/redo: `SlabChange` at any seq (`applyNativeLineStep` today only handles seq 0). Ownership follows the existing
  tile/slab copy-on-write rules in UndoLog.Step.
- Crossing: a slab crosses its own keys (it needs bases there, like a line).

**H2. Non-line strokes on a plane that holds items become slabs** (no more refusals for these).
- In `endStroke`, when the stroke has no pending line and the plane holds items:
  - for each written key, render the stroke's own contribution onto a CLEARED tile with NORMAL commit (as
    prepareRecordedLine does);
  - make it a slab at the plane's next seq, op = the stroke's blend name (smudge and tuft → normal);
  - record the slab in the same step (plus bases for newly crossed keys, as commitPendingLine does).
- Fill pen (`replaceTiles` from the fill branch): the slab is mask coverage × colour × opacity, premultiplied; op = FILL
  normal, BEHIND behind, ERASE erase. Plumb the mask through instead of the composited tiles when the plane holds items.
- Media brushes on a plane with items stay refused in words.
- Update `JbCanvasView.lineRefusal` and the layer-has-lines refusal: on a plane with items, a non-recordable stroke
  becomes a slab instead of being refused (media is the exception).
- Also add an ENGINE guard: a non-line write on a crossed tile that bypasses this path throws before mutating (it can
  no longer silently break the invariant).

**H3. Rasterize Down** (`SelectionHost.bakeLines`): each selected line becomes a slab at its own seq (its rendered unit
tiles, op = unit.op), and the record is removed. Tiles are unchanged. ONE step. Host: implement `bakeLines` via a new
`LineEdit.Bake(ids)`.

**H4. Structural and board operations never leave stale lines.**
- Delete layer, delete frame and remove board: the same undo step carries the removal of that layer's/cel's records,
  bases and slabs, so undo restores them. Then `lineSave` and `withLines` can never emit a dead cel (review #1 critical,
  #2 zombie lines).
- Every board op that rewrites pixels of a plane holding items:
  - create-animation-board transfer, hold/unhold, move/resize/duplicate/remove board, frame copy;
  - layer move between boards; anything else found in applyBoardChange.
  - Before the op, FLATTEN the affected planes' items: remove records, bases and slabs; tiles unchanged. Merge it into
    the op's step (`undo.mergeNewest`, as clearLayer does). Say one sentence:
    "Editable lines on <layer> became pixels for this board change."
- Duplicate layer / duplicate frame: copy the records, bases and slabs to the new plane (texture COPIES, new plane
  key) in the same step. If that proves too invasive, flatten the copy with a sentence instead; say which you chose.
- Plane matching everywhere is on (layerId, celId), never layer id alone.

**H5. commitPendingLine is two-phase:** compute copies and records first, install tiles, then mutate NativeLines. On a
throw nothing half-applies, and fresh copies are recycled (review #5).

**H6. Footprints by identity plus a tile index (review #6, #13, #19).**
- `NativeLines` keeps per plane `recordId → footprint` and `tileKey → item seqs`, updated on put/remove/setSlab.
- `crossed`/`crossing` become index lookups.
- The CPU footprint is computed from SAMPLES, not dab replay: each sample's box ± R, where
  R = 2 · size.base · widthScale (+ scatter reach) + 16 px. It is a guaranteed superset and cheap.
- Widen with the GPU's tiles when rendered. No value-keyed map.

**H7. Edit memory admission (review #7):** before rendering, estimate units × tiles × 256 KB. Above 192 MiB, refuse
in words ("That's too many lines to change at once on this phone; choose fewer."). Never fail mid-edit.

**H8. Frozen brush cache at pen-down (review #8):** memoise `FrozenBrushRegistry.freeze` per (registry revision,
encoded effective preset) in JbCanvasView, so a stroke start never re-encodes every frozen brush. Do NOT change the
brush/record format here; note the size/opacity-per-brush growth in the report.

**H9. Lock and hidden (review #9):** editLines / submitLineEdit refuse edits on a locked or hidden layer in words, and
the host filters locked layers out of `visibleLines` / `activeLines` selection (they may still show).

**H10. Every submitted edit is answered (review #10):** if the GL work is dropped (contentLost, graphics epoch) or the
drawing was reloaded (a line-edit epoch bumped in `load`), `done(false, "The drawing changed; try again.")`. The host
clears its pending edits on load.

**H11. A stroke split across planes shares a groupId (review #11):** the per-plane copies get the record's groupId or a
fresh one.

**H12. Replay admission at commit (review #4):** when a line is committed, run the CPU replay
(`InkReplay.replay(record, brush)`) and check the GPU preconditions (paper warnings, grain assets). If it would not
replay, keep the stroke as a SLAB (pixels at its seq, H2 path) and say once: "This stroke was kept as pixels: its brush
can't be redrawn later."

**H13. Save/open of slabs (Codex's v11 layout):** upper slabs to `Cel.slabs` (SlabRef key, seq, op) +
`JbContents.slabs` bytes; open loads them into NativeLines. `CanvasSnapshot.requireSupportedPayload` and
`JbCanvasView.refusalFor` admit slabs, with exact declaration/body consistency checks like looks. Upper slabs on a
cel with no lines are still legal.

## Proof
- JVM: NativeLines slabs/index/footprint tests; LineChange/SlabChange undo round trips; a structural-op test per H4
  case; save→open round trip with lines + slabs + bases.
- Full `:core:jvmTest`, `:androidkit:test`, `:joybrush-android:testDebugUnitTest`.
- Note everything only the phone can prove (GPU composition parity of slabs, smudge/fill as slabs).

# Select lines implementation — 2026-10-24

Branch: `codex/select-lines`. Base: `e5a0f02c0`. Integration belongs to the Engine Lead.

## Source implemented, acceptance pending

| Brief | What the owner can do after integration | Current evidence |
|---|---|---|
| A1 | Copy lines across drawings in the same app session; Paste imports their kept brushes, assigns fresh line/group identities and selects the copies. Copies remain in place when their centre is visible, otherwise they centre in the current view. | Source and regression coverage added; final tests pending. Translation uses `LineTransform.apply`, so the vector lane's wash-frame extension is honoured. |
| A2 | Lock/unlock, group/ungroup and invert line selections; choose whether locked lines can be picked. | One replacement transaction per metadata action. Group expansion also respects the locked-line filter. Save codec round-trip coverage added. |
| A3 | Shift each selected line's hue, saturation or brightness while retaining its own colour relationship. | Preview uses the lifted lines, writes no history, and release commits one edit. Each slider returns to neutral after applying its relative change. |
| A4 | Undo/refit a moved selection; recover the selection after undoing Delete; retain surviving lines when only part of a selection disappears. | Late snapshots with unchanged selected records preserve an unfinished drag. A drawing epoch prevents selection recovery across Open. |
| A5 | Open Select settings for Select all lines, Paste, Pick locked lines too and Dim the rest, alongside the existing settings. | Preferences and `Picks.fadeRest` wired. The outside veil remains UI specialist C3; this does not claim it works. Phone screenshots pending. |
| A6 | Select all lines or Rasterize Down from a layer's menu; Flatten Lower as one existing engine flatten transaction. | Rasterize scopes to this layer's selected lines, otherwise all its lines. Flatten Lower preserves layers above and refuses locked/frame layers or a clipped layer immediately above the result. Hidden lower layers are removed in the same undo step without contributing visible paint. Phone proof pending. |
| A7 | Drag off the row's colour dot or strip swatch to recolour the selected lines with the sampled colour. | Only the final eyedropper callback commits. Returning to the dot or cancelling does not take a colour. No selection keeps the ordinary eyedropper path. |
| A8 | See the original line shape at 20% during a nudge. | Original picker geometry is held for the drag and drawn through one 20% overlay layer; release/cancel clears it. Phone screenshot pending. |

## Verification

- Initial draft: `:joybrush-android:compileDebugKotlin` **PASS**, 8m07s, under `lead-build.sh` with a 768 MB Gradle heap. This predates the final review fixes and layer actions; it is not the final compile claim.
- Final app selection tests: pending.
- Core and androidkit suites: pending.
- Phone: no install or acceptance claim yet.

## Dependencies to the Lead and vector/UI specialists

**A9 / Slice — engine hook required.** No Slice preset is shipped in `joybrush/brushes/index.txt` or its brush files.
`BrushValidate` rejects `size.base <= 0`, and `ToolMemory` clamps ordinary sizes above zero. The canvas's current eraser
path does not call `InkErase`/`VectorEraser`. `LineEdit` exposes Add, Replace, Delete and Bake separately: splitting one
record into two needs an atomic remove-and-add batch with one history step. Adding a zero-width JSON preset now would
either fail loading or silently behave as a raster eraser. No invalid or misleading preset was added. The Lead needs to
provide the zero-width cut admission and atomic split transaction before the shelf preset can be made usable.

**A10 / G11 — waits for L3.** The vector lane's `WATERCOLOUR_LIVE_20261023.md` still lists the per-brush/global editable
default as pending. The badge, per-brush setting and once-only toasts must follow that choice. No default was chosen by
this lane.

**Ring Bend / G12 — waits for L4.** `LineTransform.kt` and the watercolour wash-frame implementation remain vector-owned.

**Phase B — gated.** Paint lift/clip work starts only after Phase A is merged and the Lead approves the pixel-selection
design note required by the brief. No pixel engine work is included here.

## Integration

Supporting Select/core implementation must be applied before the separate `LEAD PATCH:` commits, which name its new
helpers. The branch as a whole retains all those dependencies. No merge to `joy-creator` is performed by this lane.

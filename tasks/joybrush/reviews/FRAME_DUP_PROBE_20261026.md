# Frame duplicate GPU oracle — October 26, 2026

Assigned by JoyBrush Lead Coordinator. Isolated branch
`codex/frame-duplicate-probe-20261026`, base `717f6c326`, checkout
`C:/Temp/jb-frame-duplicate-probe-20261026`.

Owned paths: new FrameDuplicateProbe.kt, FrameDuplicatePixels.kt and its test,
this report and affected roadmap row/patch. No engine, View or BenchRunner edits.

- [x] Author bounded debug-gated disposable-engine ordinary Ink oracle (uncompiled/unrun).
- [x] Author checks for negative/odd board edges against independent clipped RGBA8 references,
  nonempty pigment, recipe/brush closure and independent target deletion.
- [x] Author one-step duplicate/delete undo and redo checks, including source immutability.
- [x] Review source/API use and exact same-coordinate oracle; diff whitespace check passed.
- [x] Commit source checkpoint `7b23ce906`; publish dedicated branch with scoped roadmap patch.

Deferred gates: low-budget refusal/allocation-cleanup execution, injected publication
failure, archive/save/reopen, captured/joint ownership, watercolor and owner appearance.
This standalone source is not a wired dispatch, an executed GPU result or a feature activation.

## Invocation and oracle

Call `FrameDuplicateProbe.run(context, disposableEngine, layerId)` on the GL thread only
after initializing a NEW engine with one empty normal unlocked layer, no document/history
or joint root. The entry refuses non-debuggable ApplicationInfo before engine mutation.
Caller must dispose the engine in its own finally block, including probe failure. The helper
does not create/destroy the context or touch any app save root. Root owns later debug dispatch.

The fixed board is `(-19,-13,293,271)`. Setup creates the board through real RegionDocumentOps
and applyBoardChange, seeds a whole negative edge tile through writeCelTile, then adds a
procedural ordinary stamp/Ink stroke through editLines. The intentionally unowned base pixels
are an imported-like fixture, not a claim of live stylus behavior. The source must contain
paint inside and sentinel pixels outside the rectangle; the line must change actual pixels.

The expected duplicate is an independent CPU per-pixel rectangle clip of actual source RGBA8;
it does not call RegionTransferTiles or replay the copied line to manufacture its reference.
Comparisons include actual tile keys, estimated/GPU footprints and a one-tile halo, with a
64-key cap. Missing tiles mean zero RGBA, including hidden RGB, not just zero alpha. Actual
canonical DUPLICATE must retain scoped record ID/seq/seed/raw samples and frozen brush, produce
separate base handles and exactly one undo step. Deleting only the target line must reveal the
clipped seeded base, change nonzero pixels and leave source pixels/recipes intact. Undo delete,
undo duplicate, redo duplicate and redo delete each check exact pixels, document and depth.

Four new FrameDuplicatePixelsTest methods exercise the CPU oracle's negative/odd exclusive
edges, hidden RGB detection, halo and changed-pixel/nonvacuity counting. They are UNRUN.
No compilation, test job, GPU or phone action was performed. Earlier coordinator26/0/0/0
receipt belongs to exact717f6c326, not these additions. Raw receipt:
`C:/Temp/jb-lead-ops/frame-live-717f6c326-root-count.json` (lead-reported, not recounted here).

No new major user-facing feature is activated; root retains STATE ownership and the existing
frame-tweens feature remains unfinished. The isolated FRAME-DUP roadmap row records source-only
probe availability. Small row patch must be reconciled with the current canonical board.

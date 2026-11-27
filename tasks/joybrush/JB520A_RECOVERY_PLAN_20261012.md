# JB-5.20a recovery, 2026-10-12

Base: origin/joy-creator f58bf202. Branch: lead/jb-5.20a-recovery.
Original C:/Temp/jb-520a preserved unchanged; recovered only its six tracked WIP files and LineRules.kt.

Contract: HANDOFF_20261012 job 3 and JB-5.20 D1/D2/D3/D5/D8a/D13/D14/D15, slice a.
DOC_VERSION 9 -> 10; StrokeCodec 1/2 readable, writer 3; seq, locked and groupId; per-tile slab operators,
saved looks and frozen brushes. INK reads as ordinary PAINT. Pure pixel/media payloads remain unchanged.
Existing composer and undo changes are retained. Engine capture/replay and screen tools remain slices d/e.

- [x] Read handoffs/spec and compare partial patch against current head.
- [x] Claim owned paths below and recover actual WIP onto clean current-head worktree.
- [ ] Finish validation, genuine legacy migration/resolver semantics, and sequence-boundary archive parsing.
- [ ] Add lifecycle refusal before unsupported line/slab/look content can enter the raster renderer.
- [ ] Add meaningful binary/archive/document/layout regression tests and update obsolete current-writer fixtures.
- [ ] Inspect scoped diff and stage owned edits; report exact build tasks to root.
- [ ] Root-controlled serialized tests/build pass; repair within scope as needed.
- [ ] Root review and scoped branch commit after permitted checks; no shared-main push.

Owned source: core/doc/DocModel.kt, DocJson.kt, DocOps.kt; core/stroke/StrokeRecord.kt, StrokeCodec.kt;
core/vector/LineRules.kt; androidkit/io/JbArchive.kt, CanvasSnapshot.kt, StagedDrawing.kt, DrawingRecovery.kt
only where lifecycle safety requires; corresponding tests. No JbCanvasView, engine, shaders, brush format or UI edits.
No builds or phones by this worker. Root owns jb-gradle.lock and admission. Ask root for product/architectural choices.

Review evidence: pending; no compilation or tests claimed.

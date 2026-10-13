# JB-5.20a recovery, 2026-10-12

Worker recovery base: origin/joy-creator f58bf202, lead/jb-5.20a-recovery.
Lead integration: C:/Temp/jb-520a-integration, lead/jb-5.20a-integration on c7b630e3 (includes atlas A1).
Original C:/Temp/jb-520a and worker recovery are preserved unchanged. Only reviewed source/tests and this plan were
copied; the stale lane appendix was excluded. Tests will run once on this combined source before publication.

Contract: HANDOFF_20261012 job 3 and JB-5.20 D1/D2/D3/D5/D8a/D13/D14/D15, slice a.
DOC_VERSION 9 -> 10; StrokeCodec 1/2 readable, writer 3; seq, locked and groupId; per-tile slab operators,
saved looks and frozen brushes. INK reads as ordinary PAINT. Pure pixel/media payloads remain unchanged.
Existing composer and undo changes are retained. Engine capture/replay and screen tools remain slices d/e.

- [x] Read handoffs/spec and compare partial patch against current head.
- [x] Claim owned paths below and recover actual WIP onto clean current-head worktree.
- [x] Finish validation, genuine legacy migration/resolver semantics, and sequence-boundary archive parsing.
- [x] Add lifecycle refusal before unsupported line/slab/look content can enter the raster renderer.
- [x] Add meaningful binary/archive/document/layout regression tests and update obsolete current-writer fixtures.
- [x] Inspect scoped diff and stage owned edits; report exact build tasks to root.
- [x] Root-controlled serialized tests/build pass; repair within scope as needed.
- [x] Root review and scoped branch commit after permitted checks; worker does not publish shared main.

Owned source: core/doc/DocModel.kt, DocJson.kt, DocOps.kt; core/stroke/StrokeRecord.kt, StrokeCodec.kt;
core/vector/LineRules.kt; androidkit/io/JbArchive.kt, CanvasSnapshot.kt, StagedDrawing.kt, DrawingRecovery.kt
only where lifecycle safety requires; corresponding tests. No JbCanvasView, engine, shaders, brush format or UI edits.
No builds or phones by this worker. Root owns jb-gradle.lock and admission. Ask root for product/architectural choices.

Review evidence: production core/backend compiled in the first integrated run. The first test compilation exposed two
missing PAINT fixture kinds; repaired. The next run reached core 2136 tests (17 failures, four optional skips) and backend
402 tests (one failure). Obsolete v9 INK fixtures were migrated through the actual decode boundary or updated to ordinary
PAINT records. No geometry, ownership or held-frame assertions were removed. A real BoardSnapshot loss path was fixed.

Root-reviewed capability guards now refuse the whole document's ordinary-layer records, slabs and looks before region
allocation, export output, paper/stroke callbacks, animation sinks or cache creation. Hidden and noncurrent cels are
included. Canvas/board snapshots also refuse frozen brush metadata they cannot retain. Plain pixel/media rendering and
legacy INK callback rendering remain supported. New tests prove refusal leaves files and callbacks untouched.

Legacy archive migration requires an explicit brush resolver and freezes its validated result. Production screen and
recovery paths deliberately do not wire that resolver yet: they refuse line payloads until slice d can show and retain
them safely. This is an archive/document foundation, not a claim that old INK drawings can now open in the live screen.
Slab composition requires every declared slab's bytes; partial composition is refused.

October13 continuation: repair2 backend 408/0/0/0 passed. One BoardPreviewHistory fixture parenthesis was corrected;
focused core 83/0/0/0 then full core 2140/0/0 with four optional skips passed (7m25s). XML independently counted.
Native full unit tests 115/0/0/0 passed. Initial app packaging failed under the scratch768MiB heap; app-only retry using
the repository2048MiB heap and CPU2 passed45s. Source checkpoint32062efc and source-constraints docs464cc023 are backed up.
Root publishes the checked checkpoint from clean jb-merge; no device acceptance or live-line support is claimed.

# Cooldown backlog: independent mid-tier contracts

Owner goal: use several frontier-free days to finish objective engineering work, leaving architecture/artistic decisions for frontier recovery. Read CURRENT_HANDOFF.md and claim the canonical queue. These jobs may run independently in isolated worktrees, subject to coordinator resource admission and serialized builds. No sub-agent may broaden ownership or silently make a product decision. Each job must inspect existing coverage first and add missing coverage/value rather than rename an existing test. If already complete, submit exact evidence and stop; do not recreate it.

All jobs below: no production Kotlin edits, app installation, protected media WIP edits, schema changes, threshold weakening, new libraries, network or owner-data deletion. Tests exercise real public production APIs. Passing tests are Review evidence, not permission to publish unreviewed changes. A discovered defect gets a minimal reproducible failing case and a concrete Blocked report; do not change expected behavior to accommodate it. Coordinator may retain a failing regression on the isolated branch. Never claim that these tests complete GPU/device integration.

## JB-NOW-09 — Undo/Redo transaction sequence regression

Owned file only: new `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/paint/UndoTransactionSequenceTest.kt`.

Read actual UndoLog API/contracts and RegionUndoTest plus existing UndoLog tests. Test deterministic sequences of push/undo/redo, new-push-after-undo, clear, capacity trimming and newest-step merge/extension using the real UndoLog and identifiable fake resource handles. Use an independent simple chronological list/cursor oracle for the supported operations, not a copy of UndoLog's implementation. Track releases and verify no double release or release of a still-required resource against the documented ownership rules. Include a step combining look and all currently defined media-store IDs, plus document-before/after metadata, to verify they travel in one step without using GL. Include the existing recordWater behavior only where its actual public contract supports the setup; distinguish pure history bookkeeping from pixel restoration. At least three fixed seeds, each 200 bounded operations, plus hand-written branch-after-undo and merged-step fixtures. Fail assertions on history order, retained changes and metadata mismatches. Do not invent GPU restoration guarantees.

Acceptance: actual single-class core JVM test passes, all sequences reproducible with printed seed on failure, and release oracle differs structurally from production. Explain existing coverage versus additions. Guarded command: standalone core jvmTest filtered to `*UndoTransactionSequenceTest`.

## JB-NOW-10 — recorded-stroke codec adversarial corpus

Owned file only: new `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/stroke/StrokeCodecAdversarialCorpusTest.kt`.

Read StrokeRecord, PenSample, StrokeCodec and existing codec tests. Exercise actual encode/decode with deterministic records varying sample count (bounded at 512), IDs/Unicode, signed seed extrema, tools and optional sensors. Compare documented preserved fields using raw Float bits; include signed zero and distinct NaN payloads only where the codec contract promises preservation (optional all-absent channels may intentionally omit payloads). Include changing sensor availability within a record, explicit color and width scale, and exact legacy-v1 defaults using independently constructed version1 bytes from the documented layout. Test truncation at every byte offset for a small valid record, bad magic/version/flags/counts and trailing-byte policy exactly as documented; stop with a repro when the contract is ambiguous. No arbitrary allocation bombs or fabricated future-format requirements. Include a mutated positive control that actually corrupts a required field.

Acceptance: real single-class JVM test, no codec duplication and no weakened assertions. Report what is genuinely bit-exact versus intentionally normalized/omitted. This protects future vector/replay work but does not open JB-5.20 runtime gates.

## JB-NOW-11 — plain-bake sparse/partition equivalence regression

Owned file only: new `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/media/MediaPlainBakePartitionTest.kt`.

Read MediaPlainBake and its nine existing tests. Add deterministic multi-pixel fixtures containing all combinations of absent/present stores, exact coverage threshold, nearest representable Float below/above threshold, zero/full coverage, arbitrary ground/look byte patterns and untouched nonfinite/raw-bit store values. Compare one actual whole-array bake with independent per-pixel expected results AND concatenated actual partition bakes at varied split points. Empty partitions must follow the real API contract. Verify untouched raw bits, paper.r crush bits, covered ground bytes, clearing of covered state, input immutability and result nonaliasing. Apply the same bake twice to demonstrate result idempotence with the same post-write look/coverage. Bound total fixture pixels to 256 and deterministic random iterations to 50. Check invalid coverage/shape/store IDs without modifying production.

Acceptance: actual targeted JVM test and independently specified expected byte/bit values; demonstrate at least one intentionally broken expected result is detectable. CPU-only reference validation, not real plain-writing integration or GPU proof.

## JB-NOW-12 — offline project archive inspection tool

Own only new `joybrush/tools/diagnostics/jb_archive_report.py` and `joybrush/tools/diagnostics/test_jb_archive_report.py`.

Python standard library, one saved archive path in, JSON stdout, read-only. Inspect ZIP directory and bounded metadata; list entries, sizes, compressed sizes, duplicate names, suspicious absolute/traversal/backslash paths, compression methods, total declared bytes and counts by suffix. Recognize current mimetype/document/tile paths from actual JbArchive constants and DocJson (read source, do not hard-code a guessed filename or version). Parse document JSON only with an explicit 8MiB read cap; show declared version and board/layer/cel counts only where current schema unambiguously defines them. Unknown schemas/versions remain unknown, not invalid. Recognize current RGBA8 and f16 tile byte-length expectations from actual TILE_BYTES/tile size; flag disagreements as inspection warnings, not claims the archive loader refuses them. Never decompress every tile, extract paths, allocate from declared ZIP size, repair files, resave documents or migrate artwork. Summarize unknown extensions rather than reject future payloads. Corrupt ZIP and unreadable path produce actionable errors/nonzero exit; bounded suspicious metadata yields a report with warnings. Label results inspection, not a replacement for real app load or exporter acceptance.

Acceptance: unittest builds small temporary synthetic ZIPs covering current recognizable structure, unknown version, duplicates, suspicious paths, malformed JSON, metadata over cap, missing metadata, declared tile-size mismatch and corrupt ZIP. Tests verify no extraction/rewriting and unchanged input bytes. Valid JSON CLI smoke test. Exact loader behavior remains a frontier/app review gate.

## JB-NOW-13 — multi-board clipping and preview boundary regression

Owned file only: new `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/render/MultiBoardRegionBoundaryTest.kt`.

Read JB-3.00a boards owner specification, RegionRenderer/RegionTileSource and their current tests, RegionPaintPlan plus the active-board preview contract. Use actual public region rendering/sourcing APIs with deterministic fake tiles; never implement a second renderer. Add missing cases for two overlapping boards, negative/non-tile-aligned board origins, content crossing left/right/top/bottom edges and wholly outside the selected board. Compare exact pixels against hand-computed sparse patterns/intersections, prove identical underlying tiles can be rendered through different board bounds, and untouched source bytes stay unchanged. Use supported source/cel selectors to demonstrate unrelated cel pixels are not leaked where the public API actually carries that identity. Distinguish source crop semantics from layer-preview UI wiring: do not claim a core test proves the Android selector. If region core cannot express a requested case, file that precise API limitation rather than invent future parameters. Existing SpriteSwapExportTest/region fixtures should be reused by reading their setup, not edited or duplicated.

Acceptance: actual single-class core JVM test, exact dimensions/transparent outside pixels and independent expected patterns. No GPU/phone appearance claim. Covers board-boundary semantics needed for dependable previews/exports.

## Reporting and frontier pickup

Every submission records base commit, scoped commit, owned paths, exact check command/result, missing evidence and any discovered blocker through the claim tool. One consolidated batch to lead, not continuous messages. Tools must be run by the coordinator under existing runtime/build guards; don't rebuild the app for Python work. Jobs09/10/11/13 use existing core test configuration without editing Gradle. Root/Claude later review and integrate independently; defects touching production get separate scoped fixes after that review. This backlog is deliberately safe preparation, not a claim that all remaining product behavior can be finished without frontier judgment.

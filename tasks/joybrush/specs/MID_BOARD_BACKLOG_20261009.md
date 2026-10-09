# Board, frame and persistence cooldown contracts

Read CURRENT_HANDOFF.md; claim the canonical queue with its helper before working. Owner wants parallel useful engineering during frontier cooldown. These are narrowly owned missing-coverage and tooling tasks, not authority to change architecture. Read the locked JB-3.00a owner model before all board jobs. Current source plus locked specifications define behavior; disagreement is a reproducible blocker, never permission to silently choose a new design.

## Common acceptance — applies to every row

Each job owns ONLY its named NEW file(s), in an isolated worktree. No production edits, Gradle/dependency changes, shader changes, phone use, installs, paper/brush design, shared-branch pushes or protected-WIP edits. Read existing coverage FIRST. Add actual missing edge cases, sequence tests or independent oracles, not renamed copies; when all requested coverage already exists, submit exact existing test evidence and stop without filler. The goal is catching board/frame/save failures before expensive integration.

Tests must call real public production APIs, with hand-computed or structurally independent expected results. Do not assert the output against itself, transcribe an implementation, weaken tolerances, accept accidental exceptions or treat CPU models as GL proof. Use deterministic seeds with repro IDs, bounded workloads (normally <=128x128 raster fixtures, <=256 operations), no timing pass thresholds. A defect gets the smallest failing test and Blocked evidence on the isolated branch; do not alter production or expected results to make it green. Existing tests need not all be rerun. New tests live alongside existing tests using the project's current conventions. Only coordinator-controlled, locked, RAM-admitted single-class standalone core jvmTest (androidkit:test for job28) is authorized, serialized with workers/browser. Python tooling uses stdlib/unittest and no app build.

Submission: base and scoped commit hashes, exact command/result, new cases versus existing coverage, known limits and blockers. Mark Review through the shared helper; no integration or owner acceptance claim. One batch report. Result status may be coverage already satisfied, but never declare an untested case covered. Leaf code fixes can be separately specified by a frontier lead after reviewing defects; these contracts do not permit guessing fixes.

## JB-NOW-14 — board selection/arming lifecycle

Own new `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/doc/BoardSessionLifecycleCorpusTest.kt`.
Read BoardSession and BoardDocumentOps. Exercise actual select/arm/reconcile with Image, Tile-as-CANVAS, Sprite and Animation, null selections, missing IDs, deletion and kind changes. Verify lock is independent of ephemeral arming and selected board can differ from armed board. Deterministic short transition sequences must preserve document values and remove stale IDs only according to actual reconciliation rules. Include invalid documents and exact expected refusal type; do not assume locks prevent feature arming. Core semantics only, not UI preview proof.

## JB-NOW-15 — exact Sprite-grid arithmetic boundaries

Own new `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/sprite/SpriteGridBoundaryCorpusTest.kt`.
Read SpriteGridMath, existing tests and owner's unlocked resize/cell-size clarification in LEAD_DESK. Exercise real grid/cell rectangle/index helpers at negative board origins, non-square cells, first/last cells, exact seams and supported min/max dimensions. Use independently computed row/column products and rectangles; test integer-overflow-adjacent invalid inputs without allocating giant arrays. Cover board-size versus cell-size helper semantics actually exposed today; absent drag UI is a limitation, not a fake test. Verify no fractional cells silently reach content operations. Report intentional UI-safe clamping separately from content-operation refusal.

## JB-NOW-16 — Sprite swap address-plan involution

Own new `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/doc/SpriteSwapPlanCorpusTest.kt`.
Read SpriteCellOps, RegionChange and existing swap/export coverage. Add missing linked-frame/held-region/hidden-and-locked-layer cases, PAINT and INK address plans, same-cell no-op, invalid indices, malformed grid and unlocked-board refusal. Use real swap plans and compare exact transfer source/destination rectangles and cel IDs against independently listed expectations. Swap twice must restore an independently interpreted bounded byte-model fixture; clearly label byte model, not GPU. Masks, document metadata and unrelated frame/shared regions stay outside transfer writes. No unsupported-content executor implementation.

## JB-NOW-17 — Animation-board operation plan safety

Own new `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/doc/AnimationBoardOperationCorpusTest.kt`.
Read AnimationBoardOps and locked board model. Cover supported resize/moveAll/duplicate/remove plans on one-frame and multi-frame boards, overlap refusal, negative positions and linked/held cels. Verify all layers/frames intended by the real contract are represented, independent masks preserved, and rejected inputs leave original documents unchanged. For explicit moveAll use its actual supported semantics rather than applying the ordinary locked-frame drag restriction incorrectly. IDs from deterministic suppliers must be unique/remapped as specified. Plan proof only, no atomic GL execution claim.

## JB-NOW-18 — region-frame edit sequence invariants

Own new `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/doc/RegionFrameSequenceCorpusTest.kt`.
Read RegionDocumentOps, AnimOps and RegionFrameEditingTest. Use actual add/duplicate/delete/reorder/hold operations that exist, deterministic sequences across two nonoverlapping Animation boards and multiple layers. Track frame identities/current frame, shared content and held/link ownership with independent lists. Board A changes must not rewrite board B or global outside-region ownership. Include deleting selected/last allowable frames and invalid IDs according to actual spec. No random giant documents. A missing public operation is reported, not implemented here.

## JB-NOW-19 — layer clone alias/link preservation

Own new `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/doc/LayerCloneIdentityCorpusTest.kt`.
Exercise LayerContentClone.plan using shared cels, repeated linked cels, multiple region animations and deterministic ID allocation. Verify old-to-new physical cel mapping, linked references remain linked within the clone, clone does not alias original IDs, and copy plans deduplicate shared source content as documented. Preserve applicable visibility/lock/blend/opacity fields and do not invent media ground or payload copying absent from the actual plan. Separate document clone proof from byte/payload transfer and list any unsupported payload semantics as limitations.

## JB-NOW-20 — document JSON refusal/roundtrip corpus

Own new `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/doc/DocJsonBoardCorpusTest.kt`.
Read DocJson/DocModel, current DOC_VERSION and existing migration/schema tests. Roundtrip small valid mixed-board documents containing sparse negative origins, Sprite grid, tiling/lock metadata, region frame links and supported layer fields. Mutate actual encoded JSON with missing/unknown fields, wrong types, duplicate identities and invalid geometry; assert actual documented refusal rather than inventing future migration requirements. Independently expected ownership survives roundtrip. Test only versions for which actual read support is present; never bump version. A valid serialized structure failing validation becomes a repro, not a rewritten codec.

## JB-NOW-21 — playback clock/step sequence oracle

Own new `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/anim/PlaybackSequenceCorpusTest.kt`.
Read PlaybackClock, FrameStepper and tests/specs. Use small independently enumerated frame schedules with varied hold durations, ranges, LOOP/PING_PONG/ONCE and exact boundary times. Verify frameIndexAt, nextWakeMs and returned Step sequences using real APIs, including skipped polling times and audio events only as documented. Distinguish an audio command from sound playback. Bound elapsed times; invalid/nonfinite inputs follow documented behavior or become ambiguity reports. No clock/runtime changes or device smoothness claim.

## JB-NOW-22 — filmstrip weighted-scrub consistency

Own new `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/anim/FilmStripScrubCorpusTest.kt`.
Read FilmStrip and the actual shared weighted-scrub helper location from source, plus existing tests. Add missing cases relating displayed frame widths/hit positions to variable hold durations, selected endpoints, empty/single-frame strips and supported scaling. Compute expected cumulative boundaries independently. Check available actual APIs, not a fake UI implementation. Exact-boundary tie behavior must come from existing spec; report ambiguity rather than choose a new gesture. No Android UI edits and no claim this checks touch latency or accessibility.

## JB-NOW-23 — fill contour topology regression

Own new `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/fill/FillTraceTopologyCorpusTest.kt`.
Read FillTrace and its tests. Trace bounded masks containing holes, multiple disconnected islands, border-touching areas, narrow corridors and supported diagonal adjacency. Use independently counted selected pixels/boundaries and simple hand-drawn polygons to verify coverage, holes and deterministic output according to existing tolerance/adjacency rules. Assert no invented filled region or mutation of input. Do not demand vertex ordering/canonicalization not in the contract. This checks the future editable fill foundation, not brush appearance or runtime fill wiring.

## JB-NOW-24 — actual flood-fill barrier/growth regression

Own new `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/fill/FloodFillBarrierCorpusTest.kt`.
Read FloodFill options/specs and tests. Use tiny independently expected masks for zero-gap/grow0 flood connectivity, closed/open walls, seed outside image, alpha/tolerance boundaries and documented grow/gap-close behavior. Show narrow intentional gaps versus supported closure diameters, but do not substitute another morphology algorithm when semantics differ. Compare default/specified options where contract guarantees equivalence; preserve input arrays. Include supported invalid limits without large allocations. No fill pen UX/engine tuning or changed closure radius.

## JB-NOW-25 — editable stroke attribute invariants

Own new `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/stroke/StrokeEditAttributeCorpusTest.kt`.
Read StrokeEdit/StrokeRecord and built edit tests. Exercise actual reshape/reweight/rebrush/recolour APIs with independently specified selected changes. Every documented untouched field (ID, seed, raw sensor values, sample time/order and other metadata) remains preserved; compare raw Float bits where appropriate. Include no-op edits and boundary sample selections. Do not demand physical invariants the operation intentionally changes. This protects future editable vector behavior but does not capture strokes on-screen or open JB-5.20 gates.

## JB-NOW-26 — existing cel-composer interleaving proof

Own new `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/vector/CelComposerInterleaveCorpusTest.kt`.
Read CelComposer and JB-5.20 D1-D3 plus existing tests. Using actual draw/look/paint/bake/compact APIs, add missing cases of solid deterministic slabs interleaved with supported real replayable lines, lines crossing only one of two tiles, writableTop selection and editing lines while preserving seq. Compare hand-computed flat-color normal-blend pixels for simple cases and source look before/after bake where contract guarantees identity. Missing brush lookups follow actual refusal contract, never fabricated empty lines. Do not modify composer or wire it to document/GL; record unsupported cases distinctly.

## JB-NOW-27 — real ink-tile footprint/translation regression

Own new `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/vector/InkTileTranslationCorpusTest.kt`.
Read InkTiles/InkRaster/InkReplay and existing tests. With real supported preset lookup and deterministic lines crossing positive/negative tile boundaries, verify actual tile keys, bounded coverage and integer-tile translation equivalence. Preserve input records/seed; compare translated corresponding tile bytes where replay semantics promise equivalence, report any coordinate-dependent texture exemption from real spec rather than loosening tolerances. No all-brush optical equivalence or GPU claim. Include empty/missing-lookup behavior and a narrow stroke touching a tile edge.

## JB-NOW-28 — actual archive roundtrip/corruption matrix

Own new `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/ArchiveBoardCorpusTest.kt`.
Read JbArchive, BoardSnapshot and existing archive/media/completeness tests. Call the real writer/reader with a small supported mixed-board document and distinguish multiple layer/cel tiles using exact byte patterns. Verify current supported tile/state payloads survive and do not leak across frames. Independently mutate ZIP entries to duplicate/truncate/misname tiles and document metadata, using existing documented acceptance/refusal rules. No source edit or invented future JB-2.40 requirements. If Android runtime fixtures cannot support it under current JVM tests, provide the exact blocker and stop rather than mocking the reader/writer. Actual androidkit single-class test required.

## JB-NOW-29 — reproducible evidence-bundle manifest tool

Own new `joybrush/tools/diagnostics/evidence_manifest.py` and `joybrush/tools/diagnostics/test_evidence_manifest.py`.
Python stdlib CLI takes explicitly listed local artifact paths and user-supplied metadata (job, base/commit, device serial optional, command). Emit JSON stdout with exact SHA256/byte sizes, input path and UTC observation time; never discover directories recursively or open credentials. Stream hashes in bounded chunks, reject directories/missing inputs and report changed-during-read files, do not silently accept symlink substitutions. Preserve repeated-basename files by full paths; no sensitive path redaction guesses. No zipping/upload/install/network/source-control mutation. Timestamp is collection time, not proof a check ran. Tests cover empty file, binary content, duplicate basenames, missing/directory paths and detected file-change using deterministic injected filesystem seams. CLI output must not claim results verified merely because hashes exist. Purpose: preserve decisive raw logs/APK/test artifacts for frontier pickup.

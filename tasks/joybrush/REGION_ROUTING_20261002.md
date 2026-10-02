# Region routing foundation — 2026-10-02

Owner priority: dependable painting, then region-board animation (JB-3.00a/R50).
Worktree C:/Temp/jb-region-routing, branch codex/region-routing, base origin/joy-creator 666ca072.
Claim recorded in main tasks/LANES.md; protected Activity/engine and document schema untouched.

## Plan

- [x] Define an immutable per-layer routing snapshot for a stroke and render/export read.
- [x] Partition boundary tiles into pixel-exact current-frame and shared-canvas rectangles.
- [x] Add independent edge, negative-coordinate, multi-board, coverage and byte-preservation tests.
- [x] Run core tests under the shared build lock; record counts and mutation proof.
- [x] Commit/push the core foundation and record the adapter contract for engine integration.

## Contract

RegionPaintPlan owns no pixels, GL resources, persisted metadata or undo history. The future
document adapter supplies a shared cel and each participating board's current frame/cel. Held
layers omit that board from their plan. Animation boards cannot overlap; board/cel ambiguity is
refused before painting. Whole-layer legacy cels need explicit migration; the plan does not
reinterpret their bytes. Capture a plan for the whole stroke, and commit every touched plane
as one undo transaction. Do not route by the dab centre: clip its complete pixel footprint.

Tile-local rectangles use top-left origin and half-open integer boundaries. GL integration must
convert this origin correctly and use the same bounds for brush commit, smudge/sample, masks,
preview and export. Whole tiles never switch ownership just because they touch a board.
The shared slices plus frame slices cover each tile exactly once, even with two boards in a tile.
Coordinates widen before multiplying or adding, avoiding Int overflow at distant/negative tiles.
copyRgbaSlice transfers exact premultiplied bytes into caller-owned tiles without extra allocation.

Next: persisted region model/current-frame IDs, bounded legacy migration, DocOps/AnimOps validation,
GPU commit/preview integration, RegionRenderer parity, all-plane save/load and thumbnail framing.
This helper alone does not make region animation operational. No phone build/install in this lane.

## Verification

Full core suite: 1,457 tests, zero failures/errors/skips, including 13 RegionPaintPlan tests.
Command: `.\gradlew.bat --no-daemon --no-watch-fs --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx768m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=C:/Temp -Djdk.net.unixdomain.tmpdir=C:/Temp' '-Pkotlin.compiler.execution.strategy=in-process' -I C:/Temp/jb-region-test-heap.gradle -p joybrush :core:jvmTest --console=plain`.
Init script sets Test.maxHeapSize to 1536m and maxParallelForks to 1, matching the established paper
suite setting. Initial 256m test heap failed two existing ProcreateImport budget fixtures with OOM;
rerun with 1536m passed in 53s. This concerns the JVM test fixtures, not the phone heap or tile limit.
Shared lock held and the primary watcher paused for the checks; no other lane's builder stopped.
Mutation changed the right-edge comparison from `<` to `<=`: 13 tests ran, two failed (exact edges
and negative-coordinate edges), zero errors. Original bytes restored; all 13 tests then passed,
zero failures/errors/skips, in 1m22s. Lock released and primary watcher restarted once. No
app/phone proof claimed. The redundant worktree todo entry was retained outside Git and removed
from this source-only landing; this document holds the plan and review so shared todo edits do
not create another integration conflict.

Latest paper commits through d6c43dab merged cleanly in this isolated worktree after read-only
merge-tree preflight. Combined core rerun passed in 1m53s: 1,458 tests, zero failures/errors/skips
(paper added one catalogue test). No production paper/core source changed in that refresh.
Foundation commit 15af73f5, integration commit 529a8381; backup branch codex/region-routing.
This clean isolated integration does not resolve the primary tree's divergent board spec or
land its legacy frame foundation. Both remain preserved separately for the later adapter work.

## Owner policy update and region model — 2026-10-02

Owner confirms all current artwork is disposable scratches and there is no user base. No old
test-file backups/restoration or conversion will be built. This overrides earlier migration plans.
Version 6 adds Board.currentFrameId, Layer.sharedCelId and per-board RegionFrames; includes the
specialist's v5 Board.tiled flag. RegionDocumentOps creates boards across paint layers, adds
blank/copy/link frames, selects saved frames and produces per-layer paint plans. Operations return
bounded pixel-copy instructions for the future engine transaction; no pixel work occurs in core.
The old scalar celFor refuses region layers so unwired exports cannot silently paint the wrong cel.
Codec canonicalizes per-region maps, and validation checks real frame/cel addresses, unique
region ownership and non-overlap. Final combined verification: 1,470 core tests and 225 Android
file/codec tests, zero failures/errors/skips; build successful in 4m58s. Twelve new model tests
exercise the saved addresses and operations. No phone install or working region UI claimed.

## Integration safety decisions (updated for disposable test data)

- Do not convert old whole-layer test animations; start a fresh region drawing.
- A new region document has a distinct shared pixel store; a frame must never alias it. Frame
  links alias only within that board's frame sequence. Store namespaces still include layer ID.
- Capture all participating regions once per stroke. Frame/board changes wait for stroke completion.
  A fragment crossing a boundary is committed to every relevant store in one undo transaction.
- Calculate tileSlices once per touched tile, not by iterating every pixel through planeAt.
  planeAt serves picking/reference reads; clips serve batched rendering and commit passes.
- Treat masks as their own stores under R48; do not duplicate a shared layer mask on every frame.
  Clipping base reads must use the base layer's region plan at the same document pixel.
- The runtime paint-plan helper remains non-Serializable; only the document fields serialize.
  GPU undo/paint, CPU region rendering and save/load integration remain the next slice. Until then
  these schema operations are not exposed as working phone controls.

## Region export projection — 2026-10-02

RegionRenderer now projects region layers through RegionTileSource: shared canvas outside boards,
each board's saved cursor inside. An explicit export frame overrides only its owning board; unknown
or ambiguous frame ownership is refused. Blank frame tiles cannot reveal old shared paint. Held
regions still read shared paint. Masks remain separate stores; a clipping base is projected using
its own layer addresses at the same document pixel. Sources are never modified, each physical cel
is fetched once per projected tile, and full shared tiles take the direct path. No image-wide cache.
Six new tests cover exact boundaries, independent boards, held/blank frames, mask/clipping, negative
tiles, source isolation and malformed requests. Focused checks: 75 tests, zero failures/errors/skips.
Full combined verification: core 1,481 tests and Android export/file suite 225 tests, zero failures,
errors or skips; build successful in 2m5s. Painting GPU/undo and phone controls remain
unfinished; this slice connects the CPU renderer used by exports, not the live GL compositor.

Paper refresh after the model landing passed 34 combined region/paper checks, zero failures/errors/
skips. Board specialist e0b1ea9f handoff received: bounded shadows, coalesced scene updates, selected
board thumbnail scope and reusable export presentation remain isolated pending Lead source review.

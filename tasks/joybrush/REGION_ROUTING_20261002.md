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

## Integration safety decisions

- Do not silently convert v4 whole-layer animation at Open. Outside pixels may differ across its
  old frames; choosing one frame as the shared outside would hide other artwork. Keep legacy
  reading isolated and require a reversible, explicit conversion with the original retained.
- A new region document has a distinct shared pixel store; a frame must never alias it. Frame
  links alias only within that board's frame sequence. Store namespaces still include layer ID.
- Capture all participating regions once per stroke. Frame/board changes wait for stroke completion.
  A fragment crossing a boundary is committed to every relevant store in one undo transaction.
- Calculate tileSlices once per touched tile, not by iterating every pixel through planeAt.
  planeAt serves picking/reference reads; clips serve batched rendering and commit passes.
- Treat masks as their own stores under R48; do not duplicate a shared layer mask on every frame.
  Clipping base reads must use the base layer's region plan at the same document pixel.
- No schema bump is made here. Coordinate tiled metadata, saved currentFrameId and region tracks
  in one reviewed schema change; new runtime helper types are deliberately not Serializable.

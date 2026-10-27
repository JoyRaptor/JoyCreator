# Live scratch source lease: scout (2026-10-27)

Read-only scout on `lead/select-reconcile-20261024` (HEAD). No build, test, device, or git write was run. Tags: **[verified in code]** = read or grepped in this checkout or in the named commit; **[doc only]** = taken from a document, not checked against code.

Line numbers below are for this checkout unless marked otherwise. The plan doc was written against `C:/Temp/jb-live-current`, and many of its anchors have drifted (see section 6).

## 0. Headline finding

**An unadopted lease implementation already exists, on a different branch.** [verified in code]
- Commits `786c88faf` ("stage actual live source leases and prepared scene inputs"), `62fb2a943`, and `fe2838486` sit on `lead/jb-live-current`. `git merge-base --is-ancestor fe2838486 HEAD` returns **no**, so none of them is on HEAD.
- `786c88faf` touches `GlPaintEngine.kt` (+244), `lines/RecordedScene.kt` (+173), adds `androidkit/src/test/.../gl/GlLiveRecordedSourceLeaseTest.kt` (300 lines, **13** `@Test`s), and `.../gl/lines/RecordedScenePreparedCreateTest.kt` (367 lines, **19** `@Test`s).
- The lease test is JVM-only and calls `initWith {}` with an empty body. Its own header says: "Real begin/ticket/lifecycle and seeded metadata only. No GL copy or appearance proof." So the chain has no GPU fidelity proof.
- The handoff (`tasks/joybrush/HANDOFF_20261016.md:57-60`) and the lead plan (`tasks/joybrush/LEAD_PLAN_20261016.md:20-21`) both say to keep these commits and adopt them only with the editable-strokes work. [doc only for the adoption rule; the commits are verified in code]

The orphan should be recovered and reviewed, not rebuilt from scratch and not archived (owner rule in memory: "save the orphans").

## 1. What the plan says the lease is

Source: `C:/Temp/jb-lead-ops/LIVE_CAPTURE_SLICE_20261016.md` (plan), read in full. [doc only]

- **Name and job** (plan line 25): "an **engine-only, non-publishing live scratch source lease**. It takes a fully validated immutable contact/header/domain ticket after the live stroke has reached its existing scratch state; requires the same current physical/graphics epoch and unchanged owner/paper/plan; settles WC pool once; copies the actual `strokeTiles` through the exact live copy shader into fresh RGBA8 source units; reports exact allocated keys/operator/byte size; and returns a closeable owner lease."
- **Owner**: the closeable lease is returned to the caller. The plan does not name a later owner; line 27 says the eventual single UP-time finalizer takes it. Lifetime is from after begin and before the first allocated scratch tile, through `finish`/`close`. The commit's doc comment adds: "The caller must explicitly cancel the live stroke after finishing/closing." [verified in code: `786c88faf` admit doc comment]
- **May**: copy scratch into fresh RGBA8 outputs; settle WC pooling exactly once; report allocated keys, operator and bytes; return a closeable lease; on failure release only this attempt's fresh outputs in the same physical epoch (line 25).
- **May not** (plan lines 25, 35): call `endStroke`; modify layer stores, history, document, or root; connect to `JbCanvasView.startStroke`; install a published root; insert into a published Scene; act as ordinary centreline geometry.
- **Must refuse** (plan lines 17, 25, 27, 35):
  - "It must reject on any mismatch before painting or publishing; it must never infer the domain from current UI board/frame at UP." (line 17)
  - Epoch change, or changed owner/paper/plan/cursor, "before copy" (line 25).
  - Unsupported brushes, owners, or boards "before they start painting under capture" (line 27).
  - Any published-root state: "the first source lease must not install a published root" (line 35). Engine guard: `requireLocalPaint` (`GlPaintEngine.kt:469-472`) fails when `publishedPaint != null`.
  - Pooling mismatch or a second finish of the same ticket (plan line 25 implies single settle; commit ticket consumes itself on `finish`).

## 2. Existing engine APIs that implement pieces

### 2a. Source capture (live, native)
- `StrokeCapture` (core): `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/stroke/StrokeCapture.kt:12-45`, `add(placedRaw)`, `finish(): StrokeRecord?`, `cancel()`. Records post-guide raw samples; never predicted. [verified in code]
- Live contact creation: `JbCanvasView.kt:2049` `LineContact(StrokeCapture(newLineName("ln"), f.id, strokeSeed, amount, view.zoom, colorArgb ?: b.argb), ...)`. [verified in code]
- Raw tap: `JbCanvasView.kt:2326` `if (raw.isPlaceable && !raw.predicted) lineContact?.let { it.capture.add(raw) ... }`, inside `feedOne` (`:2320`). [verified in code]
- Live UP: `JbCanvasView.kt:2196-2202`: `finishStroke` takes `lineContact.capture.finish()`, then `boardStrokeWork { engine.setPendingLine(line, lineBrushes); engine.endStroke(); reportHistory() }`. [verified in code]
- **Live headers never set `capturedPhysicalSource`.** Assignments exist only in probes (`RecordedCapturedJointProbe.kt:48,50`) and the core transform (`core/.../select/LineTransform.kt:90`). [verified in code: grep]
- `GlPaintEngine.setPendingLine` at `:2292`.

### 2b. Physical source, domains, copy plan (core, pure)
- `CapturedPhysicalSource` / `CaptureDomain` / `CaptureRect` / `SourcePlacement`: `core/.../stroke/CapturedPhysicalSource.kt` (lines 18, 25, 80, 150, 174). [verified in code]
- `CapturedPaintDomains.prepare(plan: RegionPaintPlan)`: `core/.../stroke/CapturedPaintDomains.kt`, companion object, prepare near line 28. Revalidates the plan and builds shared (`celId=null`) plus frame domains. **No engine caller at HEAD**; only `CapturedPaintDomainsTest.kt` calls it. [verified in code: grep]
- `CapturedSourceCopyPlan.prepare(sourceKeys, source, tileSize, limits)`: `core/.../stroke/CapturedSourceCopyPlan.kt`. Pure descriptor, no GL. Callers: `GlPaintEngine.kt:3364` and `RecordedCapturedSceneTest.kt:65`. [verified in code]
- `CapturedStrokeRules.snapshot/problems`: `core/.../stroke/CapturedStrokeRules.kt`. [verified in code]

### 2c. Source unit allocation, release, scratch
- `RecordedLineUnit(op, tiles: Map<Long,Int>, physicalEpoch)`: `GlPaintEngine.kt:3126`. [verified in code]
- Replay-based recipe lease at HEAD (**not** live scratch): `CapturedRecipeLease` `:3294`; `capturedRecipeLease` `:3311` (uses `InkReplay.replay`/`replayAdmitted` at `:3352-3354`); `copyCapturedRecipe` `:3436` (fresh RGBA8 outputs plus `copyTextureRectTranslated` per copy); diagnostic `runCapturedCopyDiagnostic` `:3548`. Plan line 15 says this path must not be used for live source. [verified in code]
- `prepareRecordedLine` (replay, not live): `:3138`. Uses `beginStrokeBuffer` and `InkReplay`. [verified in code]
- `copyTextureRectTranslated` (the GL copy primitive): `:2270`. [verified in code]
- `discardRecordedLine(unit)`: `:3606`. Used as the retire path. [verified in code]
- Scratch: `strokeTiles` `HashMap<Long,Int>` `:553`; `newStrokeTile()` `:6589`; `releaseStrokeTiles()` `:6601` (returns tiles to pools, clears map). [verified in code]
- `protectedRecordedTextureHandles()` `:4078`. [verified in code]
- Ledger: `recordedWorkingTiles` (`RecordedWorkingTileLedger`), `.register/.forget/.snapshot`. [verified in code: used at `:3360-3390`]

### 2d. Native finalisation that the lease must not call
- `endStroke()` `:5197`, `endStrokeInner` `:5198-~5275`. First line `requireLocalPaint()` `:5199`. Pools WC with `prepareFade` (early return, `:5207-5211`) or `finishInto` (`:5213`). Composites `strokeTiles` into layer tiles, `releaseStrokeTiles()`, then `undo.push(...)`. [verified in code]
- `cancelStroke()` `:5286`; JbCanvasView `cancelStroke` `:2208`, `onGl { engine.cancelStroke() }` `:2222`. [verified in code]
- `settleWatercolourFade()` `:5024`; `watercolourFrame(nowMs)` `:5010`. [verified in code]
- `setBoardDocument` `:1851` calls `requireLocalPaint()` as its first line. [verified in code]
- `publishJointPaintStep` `:5583`. Requires `publishedPaint` bound and settled context (`!strokeInProgress && !wcFading && ...`). [verified in code]

### 2e. Admission tickets and joint guards
- `JbCanvasView.RecordedCaptureAdmission` `:3085` (class), state `:3087-3108`, `prepareRecordedCaptureProbe` `:3112` (debug-only GL admission before DOWN, 10 s timeout), `runRecordedCaptureProbe` `:3179` (one-use consume). [verified in code]
- `GlPaintEngine.admitCapturedSource` (RecordedScene renderer hook, CPU preflight only) at `:4658`. [verified in code]
- `hasJointPaintPublication` `:467` (`internal val ... = publishedPaint != null`). [verified in code]
- Archive guard `JbCanvasView.kt:2637`: `if (engine.hasJointPaintPublication) throw JbArchiveException(...)`. [verified in code]
- `createCapturedRecordedScene` `:3627`; `preparePlainRecordingPromotion` `:4176`. [verified in code]
- Epoch filter for queued GL work: `JbCanvasView.kt:3285-3288` (`onGl(allowLost)`). [verified in code]

### 2f. Commit-only pieces (`786c88faf`, not on HEAD)
- `LiveRecordedSourceLease` class (commit file line 1684, `AutoCloseable`, close calls `retire` once). [verified in code]
- `LiveRecordedSourceTicket` (1701; `finish()` consumes the ticket even on refusal; `close()` is CPU-only). [verified in code]
- `admitLiveRecordedSource(layerId, workingTileBudgetBytes)` (1722): requires `requireLocalPaint`, a real live identity set by `beginStrokeBuffer` (commit lines ~3661), one admission per begin (`liveSourceAdmissionTaken`), `strokeTiles.isEmpty()`, paper resources, plan/store identity, owner unchanged, cursor identity, and a `checkCurrent()` that revalidates epoch, identity, document JSON, paper and recorded scene on every step. [verified in code]
- `copyLiveRecordedSource` (1805): `finishInto` pool once and set `wcPrepared=true`; for each `strokeTiles` key allocate `newLayerTile()`, `initializeTile(output,null,false)`, composite via `commitProg` over `clearTex`; register outputs in the ledger; on failure delete only attempt outputs (`discardOutputs`); success returns the lease with `discardRecordedLine(unit)` as retire. [verified in code]
- Grep confirms the lease admit/copy bodies contain **no** call to `endStroke`, `publishJointPaintStep`, `setBoardDocument`, `undo.push`, `setPendingLine`, or `releaseStrokeTiles`. [verified in code]
- `RecordedScene` additions: `createPrepared`, `samePreparedRecord`, a prepared `line()`/`admitCapturedSource` renderer, and `external`/`retire` helpers. [verified in code: grep of commit diff]

## 3. What is missing for the lease

Two sub-tracks. Track A is "land the lease". Track B is "prove it".

**Track A (land or re-author the lease on HEAD)**
1. Decide adopt-vs-rewrite for `786c88faf`/`62fb2a943`/`fe2838486`. Their base is not HEAD's ancestor, so rebase conflicts are expected in `GlPaintEngine.kt` around `beginStrokeBuffer`, `endStrokeInner`, `cancelStroke`. Owner gate applies (section 5). [verified in code]
2. Port `admitLiveRecordedSource` and `LiveRecordedSourceTicket/Lease` (commit 1684-1800) into HEAD `GlPaintEngine.kt`, near `capturedRecipeLease` (`:3311`). Mirror `CapturedRecipeLease` rollback style (`:3436` finally block).
3. Port `copyLiveRecordedSource`. Replace the `commitProg`/`clearTex` composite with the same primitive as `copyCapturedRecipe` (`copyTextureRectTranslated`, `:2270`) **or** prove equivalence (section 6, deviation B).
4. Add the live-identity field and its set/clear sites in `beginStrokeBuffer` (`:4854`), `endStrokeInner` (`:5198`), `cancelStroke` (`:5286`). These sites exist in HEAD; no field yet. [verified in code: grep found none]
5. Wire `CapturedPaintDomains.prepare` into the admission (no engine caller at HEAD). [verified in code]
6. Add a bounded `layerTileDigest(layerId)` helper (SHA-256 over `readTile` bytes, `:1630`, padding included) for the probe. No such helper at HEAD. [verified in code: no existing digest helper found in GlPaintEngine]
7. Add a scratch readback helper (read `strokeTiles` textures via an FBO, like `RecordedCapturedCopyProbe`). None exists for scratch. [doc only: plan line 15 mentions scratch; no helper found]
8. Optional fault injection for "allocation failure": no injection hook exists. Either add `internal var allocationFault` consulted by `newLayerTile()`, or rely on budget refusal (`workingTileBudgetBytes`) for a pre-GL failure only. [verified in code: none found]

**Track B (proof)**
9. Real-GL device probe (section 4). No existing lease-equivalent probe.
10. JVM tests for the admission refusals (budget, stale identity, owner/paper/plan changes), mirroring `CapturedDiagnosticAdmissionTest.kt` and `GlCapturedRendererAdmissionTest.kt`. The commit's `GlLiveRecordedSourceLeaseTest.kt` covers lifecycle, not GL.

## 4. Neighbour tests and probes, and a proposed lease probe

### 4a. Existing coverage (paths)
- JVM, androidkit:
  - `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/gl/CapturedDiagnosticAdmissionTest.kt` (80 lines, 6 tests: budget, dab-count, nonfinite). [verified in code]
  - `.../gl/GlCapturedRendererAdmissionTest.kt` (211 lines, budget/refusal/paper-mutation). [verified in code]
  - `.../gl/RecordedCapturedPixelOracleTest.kt` (104 lines, 5 tests: negative placement, transparent source still creates destination key, window, byte-length, int-range refusal). [verified in code]
  - `.../gl/lines/RecordedCapturedSourceGateTest.kt` (85 lines, 2 tests: eager/saved factories refuse captured records). [verified in code]
  - `.../gl/lines/RecordedCapturedSceneTest.kt`, `.../gl/GlRecordedContextTest.kt`, `.../gl/GlRecordedWorkingLedgerTest.kt`, `.../gl/GlJointPaintHistoryTest.kt`, `.../gl/GlPlainPromotionTest.kt`, `.../gl/GlRecordedHistoryTest.kt`. [verified in code: file presence]
  - `.../io/CapturedArchiveAdmissionTest.kt`, `.../io/CapturedPhysicalBackendAdmissionTest.kt`. [verified in code: file presence]
- JVM, core:
  - `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/stroke/CapturedSourceCopyPlanTest.kt` (incl. Int.MAX/MIN boundary, budgets).
  - `.../stroke/CapturedPaintDomainsTest.kt` (incl. mutated-plan refusal).
  - `.../stroke/CapturedPhysicalSourceTest.kt`, `CapturedStrokeCodecTest.kt`, `StrokeCaptureTest.kt`, and `.../select/CapturedRecordHistoryTest.kt`. [verified in code: file presence]
- Device probes (real GL, dispatched through the bench):
  - `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/RecordedCapturedCopyProbe.kt` (426 lines): read-only GPU compare of a disposable captured copy, exact RGBA8, independent Long arithmetic. [verified in code]
  - `.../gl/RecordedCapturedJointProbe.kt` (594 lines): mixed native edits, public undo/redo, bake copies, byte compares "include padding". [verified in code]
  - `.../gl/RecordedCaptureProbe.kt` (294 lines): parity against the completed legacy engine. [verified in code]
  - Dispatch: `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/bench/BenchRunner.kt:150-196` (case strings `recordedCapturedCopyProbe`, `recordedCapturedJointProbe`, `recordedCaptureProbe`); JVM dispatch test `joybrush-android/src/test/.../bench/WatercolourProbeDispatchTest.kt`. [verified in code]
  - JbCanvasView probe flags and `require` exclusivity: `JbCanvasView.kt:2938-2976`, dispatch `:3008-3010`. [verified in code]

### 4b. Proposed real-GL lease probe (`RecordedLiveSourceLeaseProbe`)
Mirror the joint probe's structure (throwaway engine, flags, JSON report). Run on a fresh disposable engine in the Note 9 sandbox only.

- **P0 preflight.** Fresh engine, `addLayer("lease-probe")`, paper resolved. Record: `hasJointPaintPublication == false`, `recordedScene == null`, `undo.undoDepth == 0`, `undo.canRedo == false`, `D0 = layerTileDigest`, `H0 = document JSON hash`. Exact compare on every field.
- **P1 begin and admit.** `beginStroke(...)` with the real frozen preset, fixed seed and colour. `admitLiveRecordedSource(layerId, tinyBudget)` must **refuse** and allocate nothing (GL texture count and ledger unchanged). Then `admitLiveRecordedSource(layerId, realBudget)` succeeds. Exact: the second admit is the only admit.
- **P2 paint.** Feed a fixed deterministic dab list (`addDabs`). Record scratch key set `K` and scratch digest `S0`.
- **P3 finish.** `ticket.finish()`. Exact compares:
  - `lease.unit.tiles.keys == K` (includes blank or transparent allocated keys).
  - Every output name is a fresh non-zero texture not in the borrowed set.
  - Per-key RGBA8 bytes equal those of a **twin** engine that ran the same begin/addDabs/`endStroke` on an empty layer and read back `readTile`. Zero tolerance, padding included; on mismatch, report max channel diff and count as `RecordedCapturedCopyProbe` does.
  - Scratch digest after finish equals `S0` (not consumed).
- **P4 non-publishing, before cancel.** Exact:
  - `layerTileDigest == D0`; `H0` unchanged.
  - `undo.undoDepth == 0`, `undo.peekUndo() == null`.
  - `hasJointPaintPublication == false`; `recordedScene == null`.
  - Native `endStroke` never ran: `strokeLayer` still set and `strokeTiles.keys == K`.
  - Save: call the archive/readback path. It must return the pre-lease bytes or refuse, and the engine state must be unchanged afterwards. (Plan line 35 names "save refused/unchanged".)
- **P5 close.** `lease.close()`: every output name is deleted (`glIsTexture == false`), ledger no longer holds them, a second `close()` is a no-op, a second `finish()` throws, `ticket.close()` after finish is a no-op. Layer digest still `D0`.
- **P6 cancel and revival.** `engine.cancelStroke()`: `strokeTiles` empty, `D0` unchanged. A new begin and admit succeeds; the old ticket's `finish()` throws (no revival).
- **P7 negatives** (each refuses with zero new names retained):
  - (a) Epoch change between admit and finish (use the existing context-loss fixture pattern from `GlRecordedContextTest`). Expect finish throws, retained outputs = 0.
  - (b) Owner or paper change between admit and finish. Expect refusal before copy (`copyLiveRecordedSource` never reaches GL).
  - (c) WC preset: `wcPrepared == true` after finish; second finish refused; pooled pixels equal the twin's `finishInto` result.
  - (d) Empty contact (zero dabs): `K` empty, `unit.tiles` empty, layer digest and history unchanged.
  - (e) Allocation failure: only if the optional fault hook (3.8) is added; otherwise mark **not covered**.

Report JSON: phase, `passed`, counts, digests, `glGetError` after each GL phase. Use exact equality and no tolerance except where stated.

## 5. Risks and conflicts named in the docs

- **Owner gate.** `tasks/joybrush/HANDOFF_20261027_COORDINATOR.md:80` says "Leave Claude explicit live-source/Atlas/save and activation gates." Line 17 says continue live-source "only with actual evidence." [doc only]
- **Lane ownership.** `tasks/joybrush/ROADMAP.md:31` lists LIVE-SOURCE as "ACTIVE root" and says "Groundwork tests do not activate the complete path." Line 41 names LIVE-SOURCE a separate owned lane. [doc only]
- **Adopt only with editable strokes.** `LEAD_PLAN_20261016.md:20-21`. [doc only]
- **No proof of equivalence.** `HANDOFF_20261016.md:59-60`: "No GPU source-copy/opacity/WC equivalence is proved." Still true at HEAD. [doc only; verified by the lease test header]
- **No blanket activation.** Plan line 35: once `publishedPaint` is installed, `requireLocalPaint` rejects native writers globally. Existing normal stamp, media, board and `reportHistory`/`setBoardDocument` paths would break. View history needs an adapter (plan line 31; `reportHistory` at `JbCanvasView.kt:3478-3492` calls `engine.setBoardDocument` at `:3488`). [verified in code + doc]
- **Do not two-step publish.** Plan line 21: do not publish `PlainPromotionTicket` then add the line in a second publication.
- **Dropped queued work.** Plan line 31: a queued block dropped after graphics epoch change must resolve the contact as refused. `onGl` filters on epoch at `JbCanvasView.kt:3285-3288`. [verified in code + doc]
- **Deviation A (copy primitive).** Plan line 25 says "the exact live copy shader". Commit's `copyLiveRecordedSource` uses `commitProg` over `clearTex`. HEAD's captured copy uses `copyTextureRectTranslated` (`:2270`). They are not the same operation. [verified in code]
- **Deviation B (WC).** Native UP can animate the WC pooling fade (`endStrokeInner` `prepareFade` at `:5207-5211`). The lease calls `finishInto` at once and skips the fade. The plan says "make that visible behaviour explicit" (line 15). Decide and document. [verified in code]
- **Brittle tests.** The commit's tests reach private fields by reflection (`strokeTiles`, `recordedWorkingTiles`, `physicalContextEpoch`, `localLayers`). Any rename breaks them silently. [verified in code]
- **Device rules.** Memory: Note 9 is the sandbox (install and test freely; never uninstall or clear data); Note 20 holds real projects. Install kills unsaved drawings, so Home first. Never launch with `adb shell monkey` (`AGENTS.md` phone rule); use `am start`. Probe device temperature during the run. [doc only: memory and AGENTS.md]
- **Orphan handling.** Memory "save the orphans": `786c88faf` chain is half-built, not dead. Recover and review; do not archive. [doc only]

## 6. Plan anchors that have drifted (this checkout vs plan)

| Symbol | Plan anchor | This checkout |
|---|---|---|
| JbCanvasView `startStroke` | 1352 | 1886 |
| `beginStrokeNow` | 1490-1524 | 2086 |
| `feed` | 1534 | 2121 |
| `feedOne` | 1713 | 2320 |
| `finishStroke` | 1556-1602 | 2153-2205 |
| `cancelStroke` (view) | 1605 | 2208 |
| `DiagnosticContact` | 336 | 364 |
| `finishDiagnosticCapture` | 352 | 380 |
| `onGl` epoch drop | 2450 | 3285-3288 |
| `reportHistory` / `setBoardDocument` | 2556 / 2563 | 3478 / 3488 |
| `requireLocalPaint` | 446 | 469 |
| `endStroke` | 3605 | 5197 |
| `beginStrokeBuffer` | 3360 | 4854 |
| `publishJointPaintStep` | 3971 | 5583 |
| `prepareRecordedLine` | 1678-1780 | 3138 |
| `preparePlainRecordingPromotion` | 2662-2750 | 4176 |

[verified in code] The plan doc itself also says it was a read of `C:/Temp/jb-live-current`, not this checkout.

## 7. Recommended next step

1. Review the `786c88faf` chain for the lease and `RecordedScene` pieces against sections 1-2 (owner decision on adopt versus rewrite). [doc: LEAD_PLAN_20261016:20-21]
2. Add the GPU probe (section 4b) before any root integration, and run it only in the Note 9 sandbox after the owner's Home-first step.
3. Do not wire `JbCanvasView` or change `endStroke`, `publishJointPaintStep`, or the view history path in this slice.

No files were changed other than this report. Scratch files written outside the repo during the scout were removed.

# Codex → Claude: three unfinished engine pieces, November 13, 2026

## Read this first

Owner requested this handback. Read-only source audit against fetched
`origin/joy-creator@bb04ad91f9f56ae6eec1579c14bad34af5179991`; this is not a new
build, device run or feature-completion claim. Work in a fresh isolated checkout
from current origin, not in the protected primary or by replacing files from an
October checkout. Later origin commits may supersede these anchors.

**There are two distinct recorded-line paths.** The ordinary NativeLines path is
live and supports existing editable lines, including the sampled watercolor line
brush. The larger JB-5.20d captured-physical-source/joint-scene path remains
unactivated. Calling both simply “live recorded strokes” has obscured what is
already built. The persistent Codex goal remains unfinished.

Last Codex work folder `C:/Temp/jb-live-goal-20261104` is clean at
`eae6bc255db12aefe28aa0a6ee1b00d587ce97ee`, reachable on
`origin/lead/live-goal-20261104`. Its writer follow-up `7502d1160` is NOT an
ancestor of this audited main and was initially UNRUN. Do not merge that whole
branch over newer Activity work. No build or phone job was started for this audit.

## 1. Live recorded-stroke connection / JB-5.20d

### Already built and reusable

- [x] Actual ordinary input capture: `androidkit/JbCanvasView.kt` creates
  `lineContacts` (~2398), retains post-guide raw/nonpredicted samples (~2871–2877),
  finishes records (~2632), then calls `engine.setPendingLines(...); endStroke()`
  (~2676). This publishes through the ordinary NativeLines path.
- [x] Scoped identity, immutable brush/record metadata, sequence/slab groundwork,
  captured physical descriptors and clipping/copy plans; scene staging,
  rollback/history publication and cold source-loader helpers exist.
- [x] Engine-only live scratch leases are ALREADY MERGED. Historical scout
  `LIVE_SOURCE_LEASE_SCOUT_20261027.md` saying they are orphaned is superseded by
  merge `a1da6ae5d` and lease probe `1b9a99140`. Current
  `gl/GlPaintEngine.kt` contains `admitLiveRecordedSource` (~3815), lease/copy
  lifetime management, `preparePlainRecordingPromotion` (~5080), recorded-scene
  adapter and `publishJointPaintStep` (~6562). Do not reapply old whole files.
- [x] Historical Note9 lease proof compares native/captured tile bytes and checks
  cancel cleanup. It explicitly does not publish a joint scene. See
  [October30 receipt](../evidence/one-line-20261030/README.md) and its raw
  `2_liveSourceLeaseProbe_joybrush.ink.log`.

### Still missing in the real app

- [ ] A pre-DOWN admission / UP-time atomic finalizer joining the actual raw/seed
  record to the actual live scratch physical source and persisted owner/clip.
  Ordinary `setPendingLines` does not establish this.
- [ ] Engine-owned physical-epoch lifetime, staged allocation/budget admission,
  cancellation/stale-owner/context rollback and source retirement across actual
  View, display and selection ownership.
- [ ] One production history transaction across recorded source, legacy pixels,
  realistic media, structural/layer/frame/board and paper changes. Existing joint
  helpers/probes are not proof every production producer routes through it.
- [ ] Whole-source cold load, Working/autosave, recovery, thumbnails and all
  relevant exports bound to that same authoritative source. Preserve guards:
  `JbCanvasView.refusalFor` (~3099) rejects captured records;
  `readContents` (~3205) rejects a bound joint publication. Select also refuses
  captured records. Removing these is not the implementation.
- [ ] Exact-source real lifecycle evidence: live draw → display/edit → one
  Undo/Redo → save → cold reopen → first edit; cancellation/failure/context loss
  and ordinary paint/media regression. Probe success alone is insufficient.

**Recommended first slice:** one ordinary shared cel, supported Ink, no mask,
media, wrapping, shaping, symmetry or resource-dependent paper brush. Freeze the
DOWN identity/domain; stage real source and record together; publish once or leave
all prior state intact. Establish a disposable-engine exact-byte negative-control
probe before any production activation. Expand only after source ownership and
save/readback contracts agree. Watercolor fade/settling adds a separate gate.

Starting context: [working checkpoint](JB520D_WORKING_20261013.md),
`HANDOFF_20261015.md`, `HANDOFF_20261016.md`,
`specs/JB-5.20_lines_in_the_one_layer.md`, current `gl/lines/RecordedScene.kt`.
Those dated handoffs describe groundwork and must be reconciled with current code.

## 2. Editable-line engine, especially watercolor lines

### Already built (do not rebuild)

- [x] Ordinary NativeLines recording/replay and line Select host are integrated.
  `JbCanvasView.editableLines` itself starts false; Activity binds it from
  `BrushLinePreferences`, whose unset default enables `ENGINE_WATERCOLOUR`.
  This is the sampled/dab line brush (`joybrush.watercolour`), not wet-sheet media.
- [x] Frozen brush definitions, stroke seeds and samples, sequence-aware line
  edits, replay, line lifting/transform, tint/rebrush/delete and history machinery.
  `CanvasSelectionHost` rejects captured-physical records explicitly (~370),
  so its ordinary-line capability must not be generalized to captured sources.
- [x] Wash frame is implemented and persisted, not merely planned:
  `31ae6f7de`; `core/stroke/StrokeRecord.kt`, `StrokeCodec.kt`,
  `core/select/LineTransform.kt`, then `GlPaintEngine.prepareRecordedLine`
  passes `record.washFrame` into watercolor replay. The Oct23 brief's L3/L4
  “not implemented” statements are stale for these specific source features.
- [x] Disposable ordinary watercolor Add/Replace/move/tint/Undo diagnostic passed
  on Note9; see [October30 scope](../evidence/one-line-20261030/README.md) and
  `2_recordedWatercolourOrdinaryProbe_joybrush.watercolour.log`.

### Remaining acceptance and integration

- [ ] Current production selected-line/cache path: move/rotate/recolor/rebrush,
  delete, Undo/Redo, then save/cold reopen/first edit and graphics context loss.
  Existing ordinary probe excludes selected lift/cache, actual View, save,
  recovery, appearance and physical pooling fade.
- [ ] Prove finish-only recolor does not re-deposit dabs after transforms/reopen;
  inspect current production deposition/finish counters and cache keys rather
  than relying on the old isolated renderer. Existing tests include
  `WatercolourLineGeometryKeyTest`, `NativeLinesTest`,
  `WatercolourOrdinaryFixtureTest`, `LineTransformTest` and codec/archive tests.
- [ ] Cold/warm timings, full pooling fade, real S Pen behavior and owner look
  acceptance. Preserve approved full-resolution pooling; do not quietly trade
  quality for a timing pass.
- [ ] Remaining mixed line/pixel interaction (K26, smudge split/bake and D23
  publication receipts) and complete captured-source support remain distinct.

**Important engine boundary:** November11's default wet-sheet watercolor
(`WetSheet`, `MediaLayerEngine`, `printOverlay`) is an `ENGINE_MEDIA` pixel/wet-state
path. `LineAdmission.slabRefusal` still refuses realistic media on a line-bearing
layer. Making that simulation editable is not a small toggle. Existing sampled
watercolor line engine is the reusable path for the requested line-layer brush.

Next safe job: freeze current source and verify ONE ordinary watercolor line
through selected edits, archive reopen and context-loss/cache counters, then exact
APK/Note9 proof. Use [watercolor brief](../briefs/CODEX_WATERCOLOUR_LINE_LIVE_20261023.md)
as acceptance intent, correcting its stale source statements above.

## 3. Real pixel operations for Select

### Existing UI/ordinary-line work

- [x] Select tool, scoped line identities, line lift/transform/edit, swap list,
  per-brush line preferences and capability gating are integrated. October30
  Select bench 21/21 is bounded evidence, not pixel-operation acceptance.
- [x] Pure D23 first-use-notice presenter and proposed publication receipt
  contract exist. They do not manufacture an engine publication event.

### Direct current-code blockers

In `joybrush-android/.../select/CanvasSelectionHost.kt`:

```kotlin
override fun liftPixels(mask: SelectionMask, keepSource: Boolean): LiftedPixels? = null
override var paintClip: SelectionMask? = null
override fun dimRest(alpha: Float) {}
```

These are at ~465/468/470. The comment correctly says brushes do not paint
through the stored clip. Color adjustment currently operates on active line lift.

- [ ] Engine pixel lift across actual addressed cel/tiles, keepSource copy or cut,
  bounded allocation and preview through box homography.
- [ ] Commit = one history step, then keep the result lifted at identity in its
  new place; release/cancel/failure restore correct prior source and preview.
- [ ] Move/scale/rotate/flip, delete, fill, duplicate/copy/paste and pixel HSB,
  honoring board clipping and preserving untouched lines/media/frame owners.
- [ ] Paint clipping consumed by every admitted brush; unsupported brushes must
  refuse visibly. No UI-only clip with paint still escaping it.
- [ ] C3 outside-selection fade implemented in compositor with source alpha and
  operator correctness; no opaque veil or binary-hole substitute.
- [ ] Joint history and real successful-publication receipts before consuming
  D23 notices; failure/cancel/no-change/whole-layer Freeze do not qualify.
- [ ] Exact GPU preview/commit/Undo/Redo and archive/recovery tests, failure
  rollback and phone proof. Reuse existing selection host/tool contracts.

Primary contract: [Select spec](../specs/JB-5.20e_select_ui.md) H4/H5 and Phase B,
[specialist brief](../briefs/CODEX_SELECT_LINES_20261023.md) B1–B4,
[D23 contract](SELECT_D23_PUBLICATION_CONTRACT_20261026.md).
Historical specialist final report also survives at
`C:/Temp/jb-codex-select/tasks/joybrush/reviews/SELECT_FINAL_HANDOFF_20261027.md`
(not present at that path in current main; recover selectively if useful).

## Saved work, safety and what Claude should do next

The last branch being clean does **not** mean all old helpers are clean.
[Recovery inventory](CODEX_WORKTREE_RECOVERY_20261113.md) records the fresh
181-worktree scan, exact heads, local-only ancestry and dirty source candidates.
Do not remove any folder from those counts. Many helper files are exact uploaded
copies; other bytes differ and need review. A byte difference is not proof of a
missing feature, and a non-ancestor commit may be a patch-equivalent old version.

Separate save prerequisite already landed: E1 outgoing-save/reopen barrier at
`f84dec80f`, historical full Android555/71 and scratch Note9 archive pass; see
[save handoff and limits](LIVE_SAVE_HANDOFF_20261104.md). This is not process-death
or full captured-source save proof. Two Copy writer failure tests/extraction at
`7502d1160` remain isolated/unverified as of this handback; reconcile with newer
November11 Activity/wet-sheet changes before porting. Do not apply the old555
count to that code or current main. Do not assume the phone still has November4's APK.

Suggested allocation: lead owns source/history/display transaction; bounded
worker audits cold loader/archive contracts and recovery candidates; line expert
verifies existing sampled-watercolor/cache path; Select expert writes pixel-lift
design against the same ownership/history contract. Coordinate engine hot files
before edits. One admitted heavy job under `jb-gradle.lock`; phone claim plus fresh
Home/readiness and full saved-data manifests for authorized sandbox checks. No
uninstall, clear-data, owner drawing edits or stale-folder builds.

This audit ran no tests or phone operations and changed no engine behavior.

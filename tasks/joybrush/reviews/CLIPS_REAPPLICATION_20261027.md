# CLIPS nine-file reconciliation handback — October 27, 2026

## Scope and pinned checkpoint

Documentation only, requested as a final focused push before Claude handback. No code edits, builds, phone actions or broad merge. Original C:/Temp/jb-clips remains clean at1553c5b45 and all earlier checkouts/evidence are preserved.

- Audit base: coordinator0604c8a74528182d99b07ca3c40e8b56132001ff, fetched origin before this checkpoint. The new documentation-only branch is docs/clips-manifest-20261027 in C:/Temp/jb-clips-manifest-20261027. Root may have advanced; recheck before any implementation.
- Preserved clips:151b92621f47ebda1d7edf1f5d267ea2b130f1a7, integrated parallel branch. Common base9304277c260660b8e0672cd082713c516e20142e. Foundation59d1f1d63 is an ancestor of coordinator; clips merge6a4c471eb is not.
- Provenance:517a9c61d C1/C2, d232a2b17 saved-end foundation,199d738a4 C3-C8/U1-U8, b96ac5e1c/3f7d5d426 profiling,1553c5b45 chrome diagnostics and later GIF/touch evidence. Merge6a4c471eb keeps DOC_VERSION14/Frame.tween while combining clip v12/v13 and saved-look exports. Never restore the old version13 header.
- Tween companion:35a9d23b1 scenes/clock,0a65e19ca export,9d7adea3f canonical range/exposure validation, docs tip08527af67. Component counterpart is source-only; direct-cel helper/clock/export receipts do not verify it.
- Original full scoped delta:39 production files and30 tests under joybrush and joybrush-android, not all new files. Only the nine NEW production files are the assignment here. Exact paths, source commit/blob IDs and current absence are in [the nine-file CSV](CLIPS_NINE_FILES_20261027.csv).
- Earlier broad manifest remains C:/Temp/jb-clips-proof/CLIPS_REAPPLICATION_MANIFEST_20261026.md with69-path CSV and supplemental tween dependencies. It is pinned to older f6287b921, not a current overwrite manifest. Between f628 and this audit base only Select controller/row/capability code changed; no clips-delta path changed.

## Exact nine-file dependency manifest

Prefixes below are abbreviations only; CSV provides full repository paths. C=joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core, B=joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit, A=joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android.

| New file | Direct prerequisites missing from current coordinator / existing dependencies | Review source and owner | Gate |
|---|---|---|---|
| C/doc/ClipTimeline.kt | Missing Board.end and BoardEnd.of/LOOP/PING_PONG/HOLD/BLANK. Existing Board.frames/fps, Frame.holdFrames and DocException suffice otherwise. No Cel.parts, GPU, renderer or region operation dependency. |151b92621; introduced199d738a4. First pure algorithm review; clips/tween agreement on retiming versus wall-clock exposure. | U-CT1 below; no document-loading activation. |
| C/doc/CelParts.kt | Missing Cel.parts and CelPart(celId,rect), including mask/shared validation. Existing Layer.cels/mask/sharedCelId/name, RectPx, RegionTransfer/RegionClear and DocException. Resolve/clone/reachable/clear/transfer must remain one ownership contract. |151b92621 /199d738a4. Core ownership review with root. | Graph cycles/depth/budget, disjoint bounds/fallback, cloning/pruning, shared/mask refusal; then all consumers before document admission. |
| C/doc/AnimationFlattenPlan.kt | ClipTimeline; AnimationBoardOps.union/overlaps and new clip policy; CelParts.reachable; RegionDocumentOps.generator/BLANK_PREVIEW; valid model/regions, DocOps validation and BoardDocumentOps bounds. |151b92621 /199d738a4; root owns actual flatten transaction. | Metadata group closure, unrelated clips retained, union-induced ownership conflicts, sampling/end modes; actual pixels/history remain separate. |
| C/anim/ClipPlaybackScheduler.kt | Saved end model; PlayMode.of, PlaybackClock.isBlank and compatible nextChangeMs; RegionDocumentOps.BLANK_PREVIEW. Current direct clock lacks these clip APIs. |151b92621 /199d738a4; coordinate tween clock counterpart9d7adea3f. | Independent fps/holds/end clocks, unchanged saved cursors and final Hold/Blank; frame-ID maps alone cannot implement tween exposures. |
| C/export/SceneAnimExportPlan.kt | Saved end model; ClipPlaybackScheduler; AnimOps cumulative duration; AnimationBoardOps.union. Later tween counterpart also requires canonical exposure/range validation and companion sample types. |151b92621 baseline; review9d7adea3f with tween. | Longest/LCM cap, finite+looping alignment, exact tick boundaries; component-aware counterpart remains unverified. |
| B/io/SceneAnimExport.kt (SceneAnimExportRunner) | SceneAnimExportPlan; RegionRenderer.renderWithPreviews; existing JbContents, CanvasPng, GifEncoder/PngWriter, AnimFileSink, TileSource and export memory limits. Requires original records before normalization for tweens. |151b92621 baseline; review9d7adea3f with tween/root export adapter. | Decode GIF delays/loop flags, PNG timing, coalescing/memory/cap warnings; valid GL marshal/lifetime for transient render callbacks. |
| B/bench/PlayAllFixture.kt | Board.end constructor and per-layer overlap policy; existing document/cel/region types and JbContents. No GPU calls inside fixture creation. |151b92621 /199d738a4. Root debug adapter. | Fixture ownership valid:256/512/1024px clips at25/50/12fps; this is input data, not performance proof. |
| B/gl/AnimationClipsProbe.kt | Component-aware model/routing/rendering; real GlPaintEngine projection/transactions/history, layer Flatten/Merge Down, archive/export and GLES context. |151b92621 /199d738a4. Root only. | Eight actual GPU rows plus cleanup, exact source/APK/run/ACK/saved-data receipts; compiled source is not execution. |
| A/bench/PlayAllBench.kt | JbCanvasView preview/load/stats hooks, BoardRuntimeController.playAll/stopPreview, fixture and scheduler; newer FrameStats APIs and BoardChromeView.missLog; Android surface/Choreographer/lifecycle. |151b92621; profiling b96ac5e1c/3f7d5d426/1553c5b45. Root only. | Actual controller run, identical static baseline, frame work AND frame gaps, teardown/context handling. No blanket60fps claim from work p95 alone. |

All nine are absent at the pinned coordinator. None is a standalone drop-in file. Five are pure/core, one a data fixture, one backend export, two runtime/debug consumers. Existing duplicate filename/type names do not imply compatible contracts.

## Smallest independent algorithm review: U-CT1

The lead selected ClipTimeline after a Luna dependency audit and independent source inspection. It has a smaller prerequisite surface than CelParts: no physical ownership or graph consumer changes. This is an **algorithm-only review/test unit**, not an independently safe product activation.

Proposed future production paths (not edited here):

1. NEW core/doc/ClipTimeline.kt from151b92621 (38 nonblank lines).
2. Minimal core/doc/DocModel.kt hunk introducing Board.end and BoardEnd. Preserve existing Frame.tween, DOC_VERSION14 and all current fields. Do not import Cel.parts or replace the whole model.
3. A focused new ClipTimelineContractTest in commonTest; it is a proposed test, not existing or run source. The historical AnimationCombineTest.endRulesAtRetimedTicksAndLinkedDrawings supplies examples but its whole class requires the larger Combine implementation.

Contract:

- At chosen common fps, each drawing receives floor(commonFps/sourceFps*hold+0.5), minimum one tick; this is Combine retiming, not a replacement wall-clock sampler.
- length is the sum of retimed holds; reject empty/over-budget timelines before Int overflow.
- Loop wraps; Ping-pong reflects interior drawings without duplicating end drawings; Hold returns last; Blank returns null after end; unknown end follows Loop. Existing negative-tick clamping stays explicit.
- Preserve IDs and immutability. Do not allocate cels, change records/regions, save cursors, emit history, render, or touch GL.

Proposed focused gate for root admission (NOT requested or launched as a job here):

`-p joybrush :core:jvmTest --tests '*ClipTimelineContractTest*'`

Under the current CreateNew shared lock, fresh focused RAM admission and root queue. Test exact and non-integer retiming/tie/minimum1, all four ends at seams, one/two/three drawing ping-pong, unknown end, negative tick, source immutability and length guard. Add no Android/backend/phone work to this gate. It becomes runnable only after root assigns the isolated source/test slice; no test file was created by this documentation task.

**Admission warning:** adding Board.end to a serializer descriptor means the reader begins recognizing it, even if DocJson itself is untouched. Do not distribute/install that partial build or permit new-format open/resave unless consumers honor end modes or root retains explicit unsupported-feature refusal. Do not disable unknown-key checks to make a test compile. Product integration still needs closure below.

## Product closure and current-source overlap

Root may review the earlier10-path model/ownership package first: DocModel, DocJson, DocOps, CelParts, RegionPaintPlan, RegionDocumentOps, LayerContentClone, RegionRenderer, RegionTileSource and CapturedPaintDomains. Reviewable is not activated: accepted component documents need all physical consumers and runtime operations. Keep strict refuseUnknownKeys before decode; preserve historical migrations; v12 end/v13 parts coexist under v14.

Five existing clips-delta files also changed since common base on the current coordinator: GlPaintEngine, JbCanvasView, JoyBrushActivity, BenchRunner and FrameStats. Root owns their integration. No broad old-file replacement. Preserve current initialized-texture ownership, ordinary frame/WC work, captured-source refusals, Select raw/clipboard lifetime, save/recovery and joint undo.

RegionDocumentOps is unchanged at the pinned canonical base but active root FRAME-DUP work adds operation markers/canonical duplicate validation and ordinary native copy contracts elsewhere. Do not replace those when integrating component cloning. PreparedLinePlaneCopy from the old tween branch is reference only; root's current helper/ownership/budget work wins after review. Shared/still/mask content must never be moved/copied as an animated whole plane merely because RegionCopy exists.

Tween chat01a16aa0 owns isolated clock/export contracts. Later9d7adea3f adds three companion core paths beyond the original69 clips delta: TweenComponentScene, TweenFrameSample and TweenScene. Compare against currently tested direct-cel fixes instead of copying their older versions. Whole records retain geometry; runtime clips each physical ownership piece; correspondence is per layer/cel/piece. Scene playback and export must share exposure/end/range sampling. Synchronous callbacks do not imply GL-thread safety or snapshot texture leases.

## Actual verification and remaining gates

This checkpoint runs only Git/source/manifest integrity checks and the local documentation-link checker. No Kotlin compilation, unit suites, APK, GPU probe or phone acceptance was run. CSV completeness is an inventory assertion, not feature proof.

Historical clips receipts (source151 lineage, not current source): core2548 with4 optional skips, backend1056 and Android330, no failures; eight-row GPU report passes with cleanup and five archive reloads/25 frames. Final five reports show58.31-59.25fps and busy p95 8.08-9.17ms, but frame-gap p95 18.74-19.37ms and startup max84.65-113.55ms. Startup hitch remains; real touch edge drag/lock/Combine/Flatten/export UI remain incomplete. Old APK missing at recorded final2 path; not rehashed. No current combined component/tween/frame/source/save-recovery proof is inferred.

Ready: exact nine-file source/blob inventory, direct dependency boundaries, first algorithm review contract and focused test request. Blocked: source assignment; current root/tween integration; new-format admission safety; actual focused tests; combined save/load/export/undo; exact-APK registered visual/device/performance proof. Owner creative/physical-pen acceptance separate. See the current roadmap, not old resume instructions, for activation and queue ownership.

## Clean handback

Documentation source checkpoint is obtained with git rev-parse HEAD on docs/clips-manifest-20261027; the final response records its pushed remote equality. Owned paths only: this report, CLIPS_NINE_FILES_20261027.csv and the affected CLIPS row in ROADMAP.md. No runtime/editor files, STATE product availability claim or unrelated roadmap rows changed. No owned build jobs, phone jobs or locks. Another coordinator's build lock was observed during preparation; do not reclaim it. Recheck all jobs/locks when admitting any future gate.

Next action for Claude/root: reconcile the small CLIPS roadmap row/report into current coordinator; assign U-CT1 explicitly if desired, or sequence full model/ownership closure with tween. No heavy job or device permission is inherited from this report.
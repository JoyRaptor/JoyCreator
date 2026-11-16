# Joy Creator Studio — Read-only audit, round three

Report date: November 16, 2026.

**15 additional findings: one correctness issue and 14 optimization opportunities. Severity: 14 medium (S2), one low (S3). No critical or high-severity issue is asserted in this wave.**

This wave extends the earlier reports into caption preprocessing, waveform invalidation, thumbnail ownership, fill/eyedropper interaction, styled-text GPU work, and Library loading. **All round-one and round-two findings remain open; none is treated as fixed.** These reports are intended to be handed to developers together.

- [Round one](https://chatgpt.com/library?file_id=libfile_1a1bc95e9cb48191a1e896fc60d2e1d4&account_id=personal)
- [Round two](https://chatgpt.com/library?file_id=libfile_1ddccf124bf88191a12a2aaa5a72ee27&account_id=personal)

## Scope and proof

Reviewed the roadmap, project state, start instructions, active-work board, JoyBrush roadmap, and prior finding inventories before choosing new targets. The frozen GitHub source is [JoyRaptor/JoyCreator @ 65cbae3](https://github.com/JoyRaptor/JoyCreator/tree/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc); the remote `joy-creator` tip matched that commit when checked at the beginning of this wave. It is the same source baseline used for round two.

Local HEAD recorded: `bbd938caed2eb9dac62e790dc4457208cb8934e2`. Findings and code links below refer to the frozen GitHub commit, rather than assuming the local checkout or installed build has identical content.

**This was a read-only source investigation.** No source, roadmap, state, task-board, branch, device, installed pack, or project was changed. No fixes, commits, pushes, or GitHub workflow runs were made. A final tracked-file status check was clean. Only scratch audit analysis and this report were written.

Each reported finding was traced through its implementation and relevant callers. Source-derived Boolean/arithmetic checks were performed for the mask counterexample, bloom pass count, fill-prefix copying, bitmap/texture storage examples, and caption-cache lookup ordering. **These are not application tests.** No Gradle build, device interaction, GPU pixel comparison, heap capture, frame-time profile, or listening test was run.

Performance findings establish concrete repeated work, missing bounds, or unnecessary storage. They do not establish a measured frame-rate gain or a reproduced crash. Logical pixel-storage examples assume the stated format and dimensions and exclude allocator/driver overhead. Before-and-after profiling remains part of the proposed acceptance checks.

## Severity and estimated fix difficulty

| Severity | Meaning |
|---|---|
| S0 — Critical | Broad irreversible loss, severe security exposure, or comparable stop-ship failure. |
| S1 — High | Serious loss/corruption/session failure or a core flow substantially wrong. |
| S2 — Medium | Incorrect feature/combination, significant avoidable work, memory pressure, or responsiveness risk. |
| S3 — Low | Narrow inefficiency, polish, or limited presentation impact. |

| Difficulty | Engineering estimate including focused verification |
|---|---|
| D1 | Under 1 hour |
| D2 | 1–4 hours |
| D3 | Half a day–2 days |
| D4 | 3–5 days |
| D5 | More than one week |

Estimates assume familiarity with this codebase and exclude queues and owner creative acceptance. Shared infrastructure can address several findings, so effort estimates should not simply be added together.

## Ranked findings

Ranking weighs output correctness, likely workload, and implementation evidence. S2 does not mean every performance issue has the same urgency.

| Rank | ID | Room / path | Type | Finding | Severity | Difficulty |
|---:|---|---|---|---|---|---|
| 1 | R3-MASK-01 | Studio · masks and renderer parity | Correctness | Mixed Add/Subtract masks use different Boolean rules in Canvas and GL | S2 | D3 |
| 2 | R3-STU-01 | Studio · caption preview performance | Optimization | Caption cache hits still rebuild grouping and repeat transcript fitting | S2 | D3 |
| 3 | R3-STU-02 | Studio · waveform settings | Optimization | Waveform color and appearance changes reshape all cached audio on the UI thread | S2 | D3 |
| 4 | R3-STU-03 | Studio · timeline memory | Optimization | Timeline filmstrip RAM grows with every visited source and size variant | S2 | D3 |
| 5 | R3-TXT-01 | TextLab · GPU memory shared by styled titles | Optimization | TextLab allocates full-resolution float scratch buffers for looks that never use them | S2 | D3 |
| 6 | R3-TXT-02 | TextLab · render-loop GPU work | Optimization | TextLab runs nine bloom passes even when bloom and lens streak are zero | S2 | D2 |
| 7 | R3-JB-01 | JoyBrush · color sampling | Optimization | Eyedropper reads obsolete move samples before rejecting their answers | S2 | D2 |
| 8 | R3-JB-02 | JoyBrush · fill-pen interaction | Optimization | Fill-pen preview repeatedly copies and rebuilds the entire growing outline | S2 | D3 |
| 9 | R3-LIB-01 | Library · moving thumbnails / room outputs | Optimization | Library animated cards decode the whole sprite sheet at original size | S2 | D3 |
| 10 | R3-LIB-02 | Library · first paint / project catalogue | Optimization | Library's fast first pass rereads complete project JSON files | S2 | D3 |
| 11 | R3-LIB-03 | Library · background I/O across rooms | Optimization | Cancelled Library loads still do their expensive file and folder scans | S2 | D2 |
| 12 | R3-VIS-01 | VisLab · styled-word bitmap lifetime | Optimization | Replacing VisLab words leaves old rendered text variants retained | S2 | D3 |
| 13 | R3-VIS-02 | VisLab · CPU-to-GPU image transfer | Optimization | VisLab reuploads cached word frames when only the render clock changes | S2 | D3 |
| 14 | R3-TXT-03 | TextLab / Studio · look carousel preparation | Optimization | Cold TextLab chip requests can generate the same moving preview repeatedly | S2 | D2 |
| 15 | R3-LIB-04 | Library · refresh and search polish | Optimization | Unchanged Library refreshes rebind and restart every visible thumbnail | S3 | D2 |

## Detailed findings

### 1. R3-MASK-01 — Mixed Add/Subtract masks use different Boolean rules in Canvas and GL

**Studio · masks and renderer parity. Correctness. Severity S2; difficulty D3.**

**Trigger / workload:** Use window mode with hard masks and no Intersect: A is a large Add rectangle; B is a smaller Subtract rectangle; C is an Add rectangle covering B. Compare the GL preview with a masked-PiP Canvas export.

**User impact:** Which pixels are kept can change with the renderer. At a point inside A, B, and C, Canvas excludes the point from the window while GL includes it. This is a different issue from round two's PiP placement and alpha findings.

**Root cause:** MaskPathBuilder selects two-bucket semantics when no shape intersects: union(all Adds) minus union(all Subtracts). MaskFold explicitly reports this as non-sequential. MaskSdf.coverage and the GL shader loops nevertheless always fold the original list sequentially, seeding from the first shape. A later Add restores pixels an earlier Subtract removed. The UI allows each appended shape's mode to change, so this order is reachable.

**Source evidence:** [MaskPathBuilder.java:52](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/model/MaskPathBuilder.java#L52); [MaskPathBuilder.java:71](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/model/MaskPathBuilder.java#L71); [MaskFold.java:52](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/studiokit/src/main/java/com/fadcam/ui/faditor/model/MaskFold.java#L52); [MaskSdf.java:171](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/studiokit/src/main/java/com/fadcam/ui/faditor/model/MaskSdf.java#L171); [FxPreviewTextureView.java:217](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/compositor/FxPreviewTextureView.java#L217); [BlendModeGlEffect.java:282](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/export/BlendModeGlEffect.java#L282); [PipFrameOverlay.java:157](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/export/PipFrameOverlay.java#L157); [PipDrawerTabs.java:889](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/tools/PipDrawerTabs.java#L889).

**Work or storage that can be removed:** Boolean counterexample: Canvas (A ∪ C) − B = 0; GL (A − B) ∪ C = 1 at the chosen point.

**Suggested correction:** Carry the fold policy into every GL consumer, preserving the existing two-bucket rule when Intersect is absent and ordered semantics when it is present. Keep the Java mirror, Canvas geometry, and generated shaders on one policy.

**Acceptance / performance checks:** Check Add/Subtract/Add and leading-Subtract stacks, reordered shapes, window/hole polarity, hard/soft edges, and stacks with Intersect. Compare preview and export pixels safely inside shapes, away from antialias edges.

**Proof / limits:** Source-confirmed mismatch, including the editable mode control and export route. A source-derived Boolean model was checked; no GPU/Canvas pixel test was run.

### 2. R3-STU-01 — Caption cache hits still rebuild grouping and repeat transcript fitting

**Studio · caption preview performance. Optimization. Severity S2; difficulty D3.**

**Trigger / workload:** Play or scrub a GL-composited captioned clip with a long transcript, especially a style using Uniform fit. The rendered phrase may be unchanged and its bitmap already cached.

**User impact:** The bitmap cache avoids rasterization but leaves substantial CPU measurement and allocation work on preview updates. Long transcripts and multiple enabled bindings amplify the work.

**Root cause:** CaptionTextureCache.getOrCreate constructs CaptionPhrases, visible/layout-word lists, a phrase string, and fittedFontPx before checking map.get(key). Uniform fitting constructs another grouping and measures phrases across the transcript. CaptionFit itself instructs callers to cache this result. FxLivePreviewController reaches this method during caption-overlay synchronization.

**Source evidence:** [CaptionTextureCache.java:117](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/compositor/CaptionTextureCache.java#L117); [CaptionTextureCache.java:157](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/compositor/CaptionTextureCache.java#L157); [CaptionTextureCache.java:170](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/compositor/CaptionTextureCache.java#L170); [CaptionPhrases.java:128](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/transcript/CaptionPhrases.java#L128); [CaptionFit.java:102](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/transcript/CaptionFit.java#L102); [FxLivePreviewController.java:1384](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/compositor/FxLivePreviewController.java#L1384).

**Work or storage that can be removed:** Current repeated grouping is proportional to total transcript length per request; Uniform fitting adds whole-transcript measurement rather than only current-phrase work.

**Suggested correction:** Cache grouping and fitted layout using an explicit transcript revision plus all style/box/font inputs. Do not use transcript object identity alone: words, strikes, breaks, and timings are mutable. Resolve the current phrase/word cheaply, then check the bitmap cache before repeating expensive fitting.

**Acceptance / performance checks:** Count grouping builds and text measurements during repeated updates of one unchanged phrase. After warm-up, counts should remain constant until an actual relevant edit. Verify word edits, strike/unstrike, style changes, font replacement, box resize, and preview/export parity.

**Proof / limits:** Source-confirmed mechanism; app/device timing and visual reproduction were not run.

### 3. R3-STU-02 — Waveform color and appearance changes reshape all cached audio on the UI thread

**Studio · waveform settings. Optimization. Severity S2; difficulty D3.**

**Trigger / workload:** Load long audio and several cached source windows, then change a waveform color, glow, lane, or smoothing/contrast setting.

**User impact:** Cosmetic edits can trigger avoidable CPU work and transient arrays, delaying interaction. Even a color-only change enters the full envelope-shaping path.

**Root cause:** The settings sheet labels color as redraw-only but calls commit(false). The host forwards the Boolean to EditorTimelineView.onTapeStyleChanged, whose false branch always calls reshapeAll. That method rebuilds every ready entry synchronously. BandEnvelopeShaper scans samples, performs log/power arithmetic and allocates arrays, then ShapedTape builds the mip representation. Ready entries may alias the same raw source via superset reuse, yet reshapeAll processes every alias independently. New extraction completion also performs shaping inside main.post.

**Source evidence:** [WaveformVisualizerSettingsSheet.java:480](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/waveform/WaveformVisualizerSettingsSheet.java#L480); [FaditorEditorActivity.java:9921](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java#L9921); [EditorTimelineView.java:2476](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/timeline/EditorTimelineView.java#L2476); [BandedTimelineWaveformCache.java:134](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/waveform/BandedTimelineWaveformCache.java#L134); [BandedTimelineWaveformCache.java:177](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/waveform/BandedTimelineWaveformCache.java#L177); [BandedTimelineWaveformCache.java:240](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/waveform/BandedTimelineWaveformCache.java#L240); [BandEnvelopeShaper.java:42](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/waveform/BandEnvelopeShaper.java#L42).

**Work or storage that can be removed:** Work grows with cached sample count × number of cache keys, including aliases; redraw-only changes need none of this sample processing.

**Suggested correction:** Separate re-extract, reshape, and redraw-only invalidation. Deduplicate shared raw data and move substantial shaping/mip work off the UI thread using an immutable style snapshot and generation-checked publication.

**Acceptance / performance checks:** Instrument shaping calls for color/glow/lane changes: they should be zero. For actual smoothing changes, verify one result per unique raw source/style revision. Stress long audio, alias windows, rapid slider changes, cancellation, and stale completion.

**Proof / limits:** Source-confirmed mechanism; app/device timing and visual reproduction were not run.

### 4. R3-STU-03 — Timeline filmstrip RAM grows with every visited source and size variant

**Studio · timeline memory. Optimization. Severity S2; difficulty D3.**

**Trigger / workload:** Work through a many-source project, scrolling across its filmstrips and changing track sizes/collapsed states. Keep those source clips in the project.

**User impact:** Lazy extraction helps initial load, but already visited sources and size variants accumulate. This increases memory pressure and garbage-collection risk during a long editing session.

**Root cause:** thumbnailsCache is an unbounded HashMap. Results from both spine and overlay filmstrips are inserted without a byte limit. setTimeline removes entries only when the source is no longer referenced, intentionally retaining all size variants of a still-live source. Manual export-time release clears the map, but in-flight loads are explicitly allowed to repopulate it; it is not a persistent cache budget.

**Source evidence:** [EditorTimelineView.java:1034](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/timeline/EditorTimelineView.java#L1034); [EditorTimelineView.java:2234](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/timeline/EditorTimelineView.java#L2234); [EditorTimelineView.java:5453](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/timeline/EditorTimelineView.java#L5453); [EditorTimelineView.java:5523](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/timeline/EditorTimelineView.java#L5523); [EditorTimelineView.java:5561](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/timeline/EditorTimelineView.java#L5561); [EditorTimelineView.java:5793](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/timeline/EditorTimelineView.java#L5793).

**Work or storage that can be removed:** Illustration: 60 square ARGB_8888 thumbnails at 168×168 cost 6.46 MiB per variant; 20 such retained variants cost 129.20 MiB, excluding overhead. This is arithmetic, not a heap measurement.

**Suggested correction:** Add a byte-budgeted LRU for decoded filmstrips, protect visible/in-use entries, and reuse the existing disk cache for evicted ones. Avoid publishing obsolete extraction results. If export needs sustained memory relief, add a scoped suspension/epoch rather than only a one-time clear.

**Acceptance / performance checks:** Visit many sources and several heights, then inspect decoded-bitmap bytes. Memory should plateau at the budget while scrolling remains correct. Verify visible-entry ownership, eviction, reload, export suspension, and in-flight completion.

**Proof / limits:** Source-confirmed mechanism; app/device timing and visual reproduction were not run.

### 5. R3-TXT-01 — TextLab allocates full-resolution float scratch buffers for looks that never use them

**TextLab · GPU memory shared by styled titles. Optimization. Severity S2; difficulty D3.**

**Trigger / workload:** Render a look whose shade path does not use the march pass and whose soften path does not use cached wobble. A flat/own-way look with wobble disabled is a target case.

**User impact:** Every renderer still reserves large GPU scratch textures. Several styled titles or larger output dimensions magnify the cost, even when those effects are inactive.

**Root cause:** TextLabRenderer.build unconditionally attempts two W×H floatTarget allocations, march and wobT. TlGl.floatTarget uses RGBA32F. The frame path only uses march when slanted, not own-way, and available; wobT is only used by a narrower multi-piece soften/wobble branch. Unsupported-float fallback exists, but supported devices pay for buffers they may never use.

**Source evidence:** [TextLabRenderer.java:341](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/cc/joycreator/textlab/TextLabRenderer.java#L341); [TextLabRenderer.java:360](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/cc/joycreator/textlab/TextLabRenderer.java#L360); [TextLabRenderer.java:721](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/cc/joycreator/textlab/TextLabRenderer.java#L721); [TextLabRenderer.java:788](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/cc/joycreator/textlab/TextLabRenderer.java#L788); [TlGl.java:119](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/cc/joycreator/textlab/TlGl.java#L119).

**Work or storage that can be removed:** Two RGBA32F buffers reserve 32×W×H bytes of logical color storage: 28.13 MiB at 1280×720 or 63.28 MiB at 1920×1080. Excludes driver padding and other targets.

**Suggested correction:** Allocate these buffers lazily when their consuming branches first need them; retain/reuse only within a bounded renderer budget. Handle knob changes and resize without stale targets or visual changes.

**Acceptance / performance checks:** Count allocations for flat, Tube/Ink/Flag, and multi-piece wobble cases. Check resizing, toggling required features, low-memory fallback, renderer release, and output pixel parity.

**Proof / limits:** Source-confirmed mechanism; app/device timing and visual reproduction were not run.

### 6. R3-TXT-02 — TextLab runs nine bloom passes even when bloom and lens streak are zero

**TextLab · render-loop GPU work. Optimization. Severity S2; difficulty D2.**

**Trigger / workload:** Set both Bloom and Lens streak to zero, then preview or export the look.

**User impact:** Downsample and upsample passes continue on every render even though their result contributes no visible light in that configuration.

**Root cause:** TextLabRenderer.frame always executes five bloom down passes and four up passes. final.frag multiplies the normal bloom contribution by uPost.x, while its separate wide-bloom streak path is guarded by uFinish.z. With both controls zero, those bloom results do not affect the final image, including the floor's bloom term.

**Source evidence:** [TextLabRenderer.java:840](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/cc/joycreator/textlab/TextLabRenderer.java#L840); [final.frag:48](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/assets/textlab/shaders/final.frag#L48); [final.frag:63](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/assets/textlab/shaders/final.frag#L63); [final.frag:78](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/assets/textlab/shaders/final.frag#L78); [parts.json:287](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/assets/textlab/parts.json#L287).

**Work or storage that can be removed:** Nine avoidable fullscreen bloom passes per rendered frame in the zero-consumer case. The percentage of total frame time saved is unknown.

**Suggested correction:** Gate bloom generation on all actual consumers. Bind a valid black texture for unused inputs, and enable/rebuild the chain correctly as soon as Bloom or Lens streak becomes nonzero.

**Acceptance / performance checks:** Count GPU passes with both values zero, with bloom-only, streak-only, and both enabled. Compare output pixels including floor reflection, color fringe, transparent output, and animated settings.

**Proof / limits:** Source-confirmed mechanism; app/device timing and visual reproduction were not run.

### 7. R3-JB-01 — Eyedropper reads obsolete move samples before rejecting their answers

**JoyBrush · color sampling. Optimization. Severity S2; difficulty D2.**

**Trigger / workload:** Drag the eyedropper rapidly, especially while drawing/rendering is already taking longer than one input event.

**User impact:** The GL thread performs redundant readbacks and allocations for positions that can no longer update the eyedropper. The backlog can make the sampling interaction less responsive.

**Root cause:** eyedropMove increments sampleSeq and queues sampleAt for every move. Only its UI callback checks whether the sequence is still current. sampleAt forwards to sampleScreen, whose queue is drained completely after rendering. answerOne allocates a direct byte buffer and performs glReadPixels before the obsolete callback is discarded.

**Source evidence:** [JbCanvasView.kt:342](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt#L342); [JbCanvasView.kt:361](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt#L361); [JbCanvasView.kt:1999](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt#L1999); [JbCanvasView.kt:2039](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt#L2039).

**Work or storage that can be removed:** For M obsolete queued moves, the current path still performs up to M point readbacks and callback posts. Coalescing removes that work before the GPU readback boundary.

**Suggested correction:** Coalesce eyedropper requests by consumer before GL readback, keeping only the latest pending point. Preserve independent icon/chrome sampling consumers and explicit final-pick requests. Reuse the small GL readback buffer.

**Acceptance / performance checks:** Queue many moves between frames and count glReadPixels calls: at most the latest required eyedropper sample should remain. Verify pen/finger offsets, final pick on lift, cancel circle, Tile repeat sampling, and concurrent chrome sampling.

**Proof / limits:** Source-confirmed mechanism; app/device timing and visual reproduction were not run.

### 8. R3-JB-02 — Fill-pen preview repeatedly copies and rebuilds the entire growing outline

**JoyBrush · fill-pen interaction. Optimization. Severity S2; difficulty D3.**

**Trigger / workload:** Draw a long, detailed fill-pen boundary with many released points, within the existing stroke limits.

**User impact:** Work and transient allocation grow as the stroke gets longer, which can slow input and preview before the final fill is committed.

**Root cause:** On each released fill batch, paint appends points then publishes fillPoints.toList(), copying the full prefix. FillPenPreview.onDraw rewinds its Path and transforms/re-adds every point again. The input guard permits up to 32,768 raw samples and a bounded path length, but that bound does not prevent substantial repeated work below the limit.

**Source evidence:** [JbCanvasView.kt:2865](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt#L2865); [JbCanvasView.kt:3045](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt#L3045); [FillPenPreview.kt:18](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/FillPenPreview.kt#L18); [JoyBrushActivity.kt:599](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/JoyBrushActivity.kt#L599).

**Work or storage that can be removed:** If N points arrive in one-point batches, prefix copying alone visits N(N+1)/2 references; N=10,000 means 50,005,000 copied reference slots. Batched delivery reduces the constant, not the underlying repeated-prefix pattern.

**Suggested correction:** Maintain an incrementally appended preview path or delta stream, apply the view transform as a matrix when possible, and publish immutable snapshots only where ownership requires them. Keep final fill geometry and undo independent of preview simplification.

**Acceptance / performance checks:** Measure copied-point count and frame allocations over increasing stroke lengths. Verify closure, sharp corners, smoothing, cancel, navigation/transform changes, and equality of committed fill before/after optimization.

**Proof / limits:** Source-confirmed mechanism; app/device timing and visual reproduction were not run.

### 9. R3-LIB-01 — Library animated cards decode the whole sprite sheet at original size

**Library · moving thumbnails / room outputs. Optimization. Severity S2; difficulty D3.**

**Trigger / workload:** Show large named sprite-sheet files from the shared folder, or large moving-preview sheets linked to looks, in the Library grid/list.

**User impact:** A small card can retain a full-resolution multi-frame image. Several visible or cached sheets can consume much more memory than their display needs.

**Root cause:** SheetLinks marks recognized sheet filenames, FolderReader supplies image URIs, and LibraryThumbs.bindSheet uses SIZE_ORIGINAL with no size override. It deliberately avoids shrinking a whole sheet to one-card dimensions, but does not instead use a grid-aware target. SheetDrawable can already derive each frame's dimensions from the decoded sheet and grid, so original resolution is not always necessary.

**Source evidence:** [SheetLinks.java:32](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/library/SheetLinks.java#L32); [FolderReader.java:108](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/library/FolderReader.java#L108); [LibraryThumbs.java:96](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/library/LibraryThumbs.java#L96); [SheetDrawable.java:44](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/library/SheetDrawable.java#L44); [FileKind.java:27](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/studiokit/src/main/java/com/fadcam/joyfolder/FileKind.java#L27).

**Work or storage that can be removed:** Illustration: a 4096×4096 ARGB_8888 sheet is 64 MiB. For an 8×8 grid displayed at 128 px per cell, a 1024×1024 decoded sheet is 4 MiB. Actual decoder/configuration overhead must be measured.

**Suggested correction:** Decode at a cell-aware resolution: target whole-sheet width/height from displayed cell size × grid columns/rows, preserving frame boundaries and aspect ratio. Add dimension/pixel budgets or a bounded moving-preview proxy for unusually large grids.

**Acceptance / performance checks:** Compare large 8×8 sheets at grid and list sizes. Verify all cells animate in order, borders do not bleed, alpha stays correct, and peak decoded bytes scale with displayed cell size rather than original frame size.

**Proof / limits:** Source-confirmed mechanism; app/device timing and visual reproduction were not run.

### 10. R3-LIB-02 — Library's fast first pass rereads complete project JSON files

**Library · first paint / project catalogue. Optimization. Severity S2; difficulty D3.**

**Trigger / workload:** Enter or return to the Library with many large saved Studio projects, particularly projects containing sprite sheets or avatar rigs.

**User impact:** The first app-only result waits on complete project-file reads. Projects containing parts also require a full JSON tree parse, even when only their parts catalogue is needed.

**Root cause:** LibrarySource calls readRestOfApp/PartsReader before posting its first result. PartsReader.readText loads each entire project file before checking for the two array names; if present, JsonParser.parseString constructs the whole root tree, not only the requested arrays. Parts extraction has no per-save cache. The later ProjectFacts pass may read/parse the same changed project again.

**Source evidence:** [LibrarySource.java:62](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/library/LibrarySource.java#L62); [LibrarySource.java:109](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/library/LibrarySource.java#L109); [PartsReader.java:46](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/library/PartsReader.java#L46); [PartsReader.java:60](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/library/PartsReader.java#L60); [PartsReader.java:142](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/library/PartsReader.java#L142); [ProjectFacts.java:76](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/library/ProjectFacts.java#L76).

**Work or storage that can be removed:** The first pass reads the sum of all project JSON sizes on each load; parts-bearing projects also allocate a whole parsed JSON tree. No startup-time measurement was made.

**Suggested correction:** Maintain a per-save lightweight parts/summary index, with reliable invalidation and recovery for externally changed files. Alternatively stream only required arrays and share parsed metadata. Deliver available project cards before expensive parts enumeration.

**Acceptance / performance checks:** Count bytes read and JSON parses on two unchanged Library entries and after editing one project. Only the changed project's derived index should need rebuilding. Check rename/delete, external restore, sprite changes, and corrupt-file fallback.

**Proof / limits:** Source-confirmed mechanism; app/device timing and visual reproduction were not run.

### 11. R3-LIB-03 — Cancelled Library loads still do their expensive file and folder scans

**Library · background I/O across rooms. Optimization. Severity S2; difficulty D2.**

**Trigger / workload:** Rapidly leave/re-enter the Library or trigger several reloads before previous reads complete, especially with a slow shared-folder provider.

**User impact:** Obsolete work continues consuming I/O and queues ahead of the newest request. Its shared-folder scan can delay other room operations using the same single I/O executor.

**Root cause:** cancel only increments generation. load's queued WORK task has no generation check before project listing and parts reads, and it schedules FolderReader.read without a generation check in that task. Generation is checked for later publication/merge, after the folder walk has already happened. FolderReader scans the folder index; JoyFolderHome owns a single shared executor.

**Source evidence:** [LibrarySource.java:43](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/library/LibrarySource.java#L43); [LibrarySource.java:57](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/library/LibrarySource.java#L57); [LibrarySource.java:62](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/library/LibrarySource.java#L62); [LibrarySource.java:72](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/library/LibrarySource.java#L72); [FolderReader.java:59](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/library/FolderReader.java#L59); [JoyFolderHome.java:22](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/studiokit/src/main/java/com/fadcam/joyfolder/android/JoyFolderHome.java#L22).

**Work or storage that can be removed:** A superseded load can still perform a complete project/parts read plus a complete folder index scan. This is repeated work, not merely a dropped UI callback.

**Suggested correction:** Coalesce pending loads and check cancellation/generation before each expensive stage and between files where practical. Share an in-progress folder snapshot when appropriate. Separate cancellation of delivery from cancellation of work explicitly.

**Acceptance / performance checks:** Queue several reloads then cancel most of them. Instrument project reads and index scans; stale requests should stop before the expensive stage. Verify the newest view still receives a complete result and file-write ordering remains safe.

**Proof / limits:** Source-confirmed mechanism; app/device timing and visual reproduction were not run.

### 12. R3-VIS-01 — Replacing VisLab words leaves old rendered text variants retained

**VisLab · styled-word bitmap lifetime. Optimization. Severity S2; difficulty D3.**

**Trigger / workload:** Within one open VisLab session, repeatedly change the words or choose different named looks for a word layer, allowing each variant to render.

**User impact:** One current layer can leave many previous variants' last bitmaps reachable until the room releases its Pictures source. Memory tracks editing history instead of just live content.

**Root cause:** WordsPicture.slot includes words, look key, and look name. A changed variant therefore gets a new slot. parsed has an eight-entry limit, but shown, lastStep, and lastRef are ordinary maps. frame recycles only the previous bitmap for that same slot. There is no removal of no-longer-used slots until release; the separate StudioTextLab renderer idle sweep does not clear WordsPicture.shown.

**Source evidence:** [WordsPicture.java:44](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/vislab/room/WordsPicture.java#L44); [WordsPicture.java:71](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/vislab/room/WordsPicture.java#L71); [WordsPicture.java:122](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/vislab/room/WordsPicture.java#L122); [WordsPicture.java:139](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/vislab/room/WordsPicture.java#L139); [VisLabActivity.java:595](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/vislab/room/VisLabActivity.java#L595); [StudioTextLab.java:235](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/cc/joycreator/textlab/StudioTextLab.java#L235).

**Work or storage that can be removed:** Retained frame count can grow with the number of distinct rendered text/look slots during the session; the parsed-cache limit does not bound these bitmap maps.

**Suggested correction:** Reconcile cached slots against active scene references, or use a byte-budgeted LRU with safe ownership. Release unreferenced frames and associated timestamps/ref entries while preserving slots shared by multiple live layers.

**Acceptance / performance checks:** Cycle one layer through many text/look variants, then inspect retained bitmap bytes. Memory should plateau after obsolete variants leave the active set. Test undo/redo and multiple layers sharing a slot.

**Proof / limits:** Source-confirmed mechanism; app/device timing and visual reproduction were not run.

### 13. R3-VIS-02 — VisLab reuploads cached word frames when only the render clock changes

**VisLab · CPU-to-GPU image transfer. Optimization. Severity S2; difficulty D3.**

**Trigger / workload:** Render styled words at a display rate above WordsPicture's fixed 30 fps, or hold a moving provider's output frame across multiple render calls.

**User impact:** The provider avoids redrawing the words, but the consumer still uploads unchanged pixels to the GPU. Lower-rate content receives less benefit from its existing frame cache.

**Root cause:** WordsPicture.frame returns the same cached frame within a 1/30-second step. Pictures forwards that frame. VisRenderer.movingTexture skips only an exactly unchanged renderer time; after the clock moves it calls frame and then texSubImage2D/texImage2D whenever a bitmap is available, without a frame-generation test.

**Source evidence:** [WordsPicture.java:40](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/vislab/room/WordsPicture.java#L40); [WordsPicture.java:122](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/vislab/room/WordsPicture.java#L122); [Pictures.java:48](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/vislab/room/Pictures.java#L48); [VisRenderer.java:993](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/vislab/gl/VisRenderer.java#L993).

**Work or storage that can be removed:** In the illustrative 60 fps/30 fps case, roughly every other clock update can upload an unchanged word image. This predicts upload-count reduction, not a measured doubling of render speed.

**Suggested correction:** Add a provider frame/content-generation token and upload only when it changes. Bitmap object identity alone is unsafe because some other providers reuse one Bitmap while changing its pixels.

**Acceptance / performance checks:** At a 60 fps render cadence with 30 fps words, count provider frame changes versus GL uploads. Preserve animated parameters, distinct per-layer speed, video/sprite providers with mutable buffers, paused edits, and reference changes.

**Proof / limits:** Source-confirmed mechanism; app/device timing and visual reproduction were not run.

### 14. R3-TXT-03 — Cold TextLab chip requests can generate the same moving preview repeatedly

**TextLab / Studio · look carousel preparation. Optimization. Severity S2; difficulty D2.**

**Trigger / workload:** Open the Studio title-look shelf with missing preview-cache files and a nonempty folder shelf, or request the same uncached preview from multiple consumers before generation completes.

**User impact:** Duplicate multi-frame preview jobs queue on the GL worker, delaying useful previews and consuming extra GPU rendering/readback/encoding work.

**Root cause:** TextLookRow first shows built-ins, then shows a merged shelf after the folder read and asks for all previews again. TextLabPreviews.chip checks File.exists before posting a job, but has no per-key in-flight join and does not recheck the file inside the queued job. Requests admitted before the first file is published each run LookPreview.make in full. The separate making set is not used by this chip path.

**Source evidence:** [TextLookRow.java:196](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/cc/joycreator/textlab/TextLookRow.java#L196); [TextLookRow.java:214](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/cc/joycreator/textlab/TextLookRow.java#L214); [TextLabPreviews.java:94](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/cc/joycreator/textlab/TextLabPreviews.java#L94); [LookPreview.java:55](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/cc/joycreator/textlab/LookPreview.java#L55).

**Work or storage that can be removed:** Each duplicate generation repeats 20 output-frame readbacks and 114 frame() advances after initialization, plus the initial still and encoding. No timing estimate is implied.

**Suggested correction:** Deduplicate by full preview cache key and fan out completion callbacks. Recheck on the worker before rendering and clear in-flight state on all success/failure paths; retain current engine-stamp invalidation.

**Acceptance / performance checks:** Request one missing key concurrently and through both shelf passes. Verify exactly one generation and all callbacks delivered to still-valid consumers. Test generation failure, cache invalidation, and reopening a shelf while generation runs.

**Proof / limits:** Source-confirmed mechanism; app/device timing and visual reproduction were not run.

### 15. R3-LIB-04 — Unchanged Library refreshes rebind and restart every visible thumbnail

**Library · refresh and search polish. Optimization. Severity S3; difficulty D2.**

**Trigger / workload:** Let the Library's second pass complete without changing visible cards, return to an unchanged Library, or change a search/filter while many surviving cards remain visible.

**User impact:** Surviving cards still rebuild their thumbnail requests and moving drawables. This adds avoidable UI work and can reset animated previews.

**Root cause:** LibraryAdapter.submit always calls notifyDataSetChanged, despite stable IDs. A full card bind calls LibraryThumbs.bind, which clears the current Glide target and drawable before requesting an image again. The adapter already uses a limited payload for selection changes, but not for ordinary catalogue refreshes.

**Source evidence:** [LibraryFragment.java:246](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/library/LibraryFragment.java#L246); [LibraryFragment.java:349](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/library/LibraryFragment.java#L349); [LibraryAdapter.java:45](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/library/LibraryAdapter.java#L45); [LibraryAdapter.java:128](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/library/LibraryAdapter.java#L128); [LibraryCardView.java:157](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/library/LibraryCardView.java#L157); [LibraryThumbs.java:47](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/library/LibraryThumbs.java#L47).

**Work or storage that can be removed:** Current full refresh work scales with the number of bound visible cards even when their content is identical; image-loader caching may reduce decode cost but does not remove clear/rebind work.

**Suggested correction:** Diff immutable row content using stable identity and meaningful visual fields. Use payloads for metadata-only changes and keep an unchanged thumbnail/drawable alive. Treat list/grid presentation changes separately.

**Acceptance / performance checks:** Submit an identical result and count card binds/Glide clears: zero thumbnail reloads should occur. Verify actual image revisions refresh, selection remains correct, search/sort work, and moving sheets keep their state when unchanged.

**Proof / limits:** Source-confirmed mechanism; app/device timing and visual reproduction were not run.

## Source-derived checks completed

| Check | Result | What it proves |
|---|---|---|
| Add/Subtract/Add interior point, no Intersect | Canvas combined mask = 0; GL combined mask = 1 | The two policies disagree away from soft/antialias boundaries. It does not substitute for a rendered pixel comparison. |
| Bloom loops with five down levels | 5 down + 4 up = 9 passes | The amount of pass work bypassable when neither bloom nor lens streak contributes. |
| Fill preview with 10,000 one-point publications | 50,005,000 prefix reference slots copied | The repeated-prefix growth model. Actual batching determines the exact allocation count. |
| Filmstrip example | 6.45996 MiB per 60×168×168 RGBA variant; 129.19922 MiB for 20 | Logical decoded pixel capacity, not an observed heap profile. |
| Two RGBA32F scratch textures | 28.125 MiB at 1280×720; 63.28125 MiB at 1920×1080 | Logical color storage for the pair, excluding other targets. |
| 8×8 sheet preview example | 4096² RGBA = 64 MiB; 1024² RGBA = 4 MiB | The benefit available from cell-aware sampling for a 128 px display cell. |
| Caption source ordering | fittedFontPx is evaluated before map.get(key) | Even a successful bitmap hit currently follows fitting work. |

## Where to start for speed

The most direct bounded changes are the disabled-bloom bypass (R3-TXT-02), eyedropper request coalescing (R3-JB-01), and duplicate preview-generation join (R3-TXT-03). Each has an explicit countable operation to eliminate.

For larger projects, prioritize caption grouping/fitting reuse (R3-STU-01), waveform invalidation and background shaping (R3-STU-02), and decoded-filmstrip budgeting (R3-STU-03). These attack work or memory that scales with project size instead of just the currently visible phrase/frame.

For room entry and shared assets, combine the Library parts index, obsolete-load cancellation, cell-aware sheet decoding, and row diffing. They address distinct stages: catalogue I/O, queued work, pixel memory, and UI rebinding.

Treat R3-MASK-01 as a correctness gate before refactoring mask rendering for speed. The optimized result must preserve one agreed Boolean policy.

## Verification plan for developers

1. Record representative baselines on the Note 9: long captioned video, long audio with several trimmed aliases, a many-source timeline, a long fill boundary, large sheet cards, multiple styled titles, and a VisLab word-edit session.
2. Count the operations named above: transcript fits, waveform shapes, decoded bitmap bytes, GL allocations/passes/uploads/readbacks, JSON reads/parses, and duplicate preview jobs.
3. Apply changes independently where possible. Compare output, timing, undo, and ownership at the same source/project state.
4. Use p50/p95 frame/input latency, peak/retained memory, and first-use/return-to-room latency to establish actual gains. Do not advertise a percentage based only on the source arithmetic in this report.
5. Exercise invalidation and failure cases: edits during pending work, stale callbacks, cancellation, memory refusal, reopen, and multiple simultaneous consumers.

No benchmark thresholds are claimed to have passed. The report supplies specific acceptance targets for the implementation lane.

## Coverage boundaries and unconfirmed leads

This is a deeper set of new paths, not a repeated whole-product census. It does not re-audit or close earlier save/history, PiP decoding/geometry, audio gain/mute, pack consistency, sprite-transfer, or animation-export findings.

The drawn-mask Boolean path was inspected; an exhaustive gesture-cancel/undo sweep was not completed. A possible chip-executor detach/reattach lifetime concern and TextLab partial-build allocation cleanup deserve a dedicated failure/lifecycle check, but are **not counted as confirmed findings** here because complete runtime reachability/recovery was not established.

Other worthwhile next areas: linked-group/ripple operations, actual mask gesture cancellation, additional frame/brush/board device combinations, multi-project asset ownership, and benchmark evidence for the mechanisms identified in this wave.

## Handoff

Keep rounds one, two, and three together. This report adds findings and proposed verification; it contains no fixes or feature-completion claims. Every finding has a stable ID, severity, effort estimate, source chain, optimization/correction mechanism, and acceptance checks.

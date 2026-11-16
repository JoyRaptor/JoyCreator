# Joy Creator Studio — Read-only audit, round two

Report date: November 15, 2026 (UTC session continued into November 16).

**15 additional findings: 3 high, 11 medium, 1 low. Two further leads require verification and are excluded from that count.**

This is an additive research wave. **The owner states that none of round one's findings have been fixed. All round-one findings remain open; this report does not supersede, close, or reclassify them.** The intent is to hand the waves to developers together.

Prior report: [JoyCreator_Audit_Round1_20261115.md](https://chatgpt.com/library?file_id=libfile_1a1bc95e9cb48191a1e896fc60d2e1d4&account_id=personal).

## Source and method

- Frozen GitHub source: [JoyRaptor/JoyCreator @ 65cbae3](https://github.com/JoyRaptor/JoyCreator/tree/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc). Every code link below targets that exact commit.
- Local HEAD recorded at the start of this wave: `bbd938caed2eb9dac62e790dc4457208cb8934e2`. The local tracked tree was clean at that observation. These are different source snapshots; findings below are tied to the frozen GitHub commit, not a claim about which build is installed.
- Reviewed the roadmap/start/state context and round-one inventory before selecting deeper, different paths. Traced callers through scheduling, processor/render implementation, persistence, and publication where relevant.
- Source review was read-only. No source/task-board edits, fixes, commits, pushes, GitHub workflow runs, installed-pack changes, or device actions were performed.
- Evidence is source inspection and deterministic arithmetic/counterexamples. No app build, device reproduction, listening test, GPU pixel comparison, benchmark, or executed regression suite is claimed. Each issue states its practical verification limits.

“Source-confirmed” means the implementation and reachable caller chain support the stated defect. It does not mean the symptom was observed in a running application. Conditional decoder/GPU behavior is identified explicitly.

## Severity and effort scales

| Rank | Meaning |
|---|---|
| S0 — Critical | Broad irreversible loss, severe security exposure, or a comparable stop-ship failure. None identified in this wave. |
| S1 — High | Core workflow substantially wrong, serious state divergence, or potential editing-session failure. |
| S2 — Medium | Broken feature/combination, incorrect output, resilience failure, or significant avoidable work. |
| S3 — Low | Narrow boundary or presentation error with limited impact. |

| Difficulty | Estimated engineering effort, including focused verification |
|---|---|
| D1 — Very easy | Under 1 hour |
| D2 — Small | 1–4 hours |
| D3 — Moderate | Half a day–2 days |
| D4 — Hard | 3–5 days |
| D5 — Large | More than a week |

Effort assumes a developer familiar with this codebase. It excludes queue time and owner acceptance. Shared fixes may address several rows, so estimates should not simply be added.

## Ranked findings

Priority follows likely user impact, then confidence and scope. The numbered order is a triage recommendation, not a dependency graph.

| Priority | ID | Area | Additional finding | Severity | Difficulty |
|---:|---|---|---|---|---|
| 1 | R2-FX-01 | Studio · PiP export | Unmasked PiP export shrinks and distorts the source | S1 | D3 |
| 2 | R2-FX-02 | Studio · PiP playback/export efficiency | PiP decoder consumes a fresh source frame for every output frame | S1 | D3 |
| 3 | R2-JB-01 | JoyBrush · layer duplication / graphics-memory failure | Failed layer duplication can leave a phantom layer and escape the render loop | S1 | D3 |
| 4 | R2-AUD-02 | Studio · audio preview / export parity | Muted audio clips and lanes can still play in preview | S2 | D2 |
| 5 | R2-AUD-01 | Studio · audio DSP | Stereo audio ignores fades and volume automation | S2 | D2 |
| 6 | R2-AUD-03 | Studio · imported audio / export robustness | Multichannel PCM can overrun the two-channel gain array before downmix | S2 | D2 |
| 7 | R2-FX-04 | Studio · transitions / chunked export / FX automation | PiP and image effect keyframes use chunk-local time | S2 | D2 |
| 8 | R2-FX-03 | Studio · PiP blending / masks | GL PiP opacity violates the downstream premultiplied-alpha contract | S2 | D2 |
| 9 | R2-JB-02 | JoyBrush · boards × wet media | Board metadata edits prematurely dry an unrelated wet wash | S2 | D2 |
| 10 | R2-JB-03 | JoyBrush · frame-by-frame / Sprite boards × physical brushes | Frame duplication and Sprite pixel moves retain appearance but lose physical paint payload | S2 | D4 |
| 11 | R2-TIME-01 | Studio · text/object timeline moves | Lock-only time shuttle can step past one obstacle into another | S2 | D2 |
| 12 | R2-PACK-01 | TextLab / JoyFolder · pack installation and cross-room supplies | Failed supply-pack updates can leave a mixed installed version | S2 | D3 |
| 13 | R2-PACK-02 | JoyFolder → Studio · fonts, grades, caption supplies | Distinct pack IDs collide in Studio's local supply-cache paths | S2 | D2 |
| 14 | R2-PACK-03 | JoyFolder → Studio · offline/cache resilience | A transient pack read failure removes a usable cached supply from the shelf | S2 | D2 |
| 15 | R2-TIME-02 | Studio · timeline shuttle boundary | Time snapping can move an object past the host's maximum start | S3 | D1 |

## Detailed findings

### 1. R2-FX-01 — Unmasked PiP export shrinks and distorts the source

**Severity: S1. Fix difficulty: D3.** High · source-confirmed route and geometry mismatch.

**Trigger / reproduction target:** Export an unmasked picture-in-picture video, especially a portrait source over a landscape project. Compare its size and aspect ratio with preview; repeat with a mask.

**User impact:** The ordinary GL export path uses a different rectangle from preview/the Canvas path. The exported PiP becomes substantially smaller and can stretch its aspect ratio. Adding a mask changes which renderer runs, so a mask can unexpectedly change placement.

**Root cause:** BlendModeGlEffect selects the GL PiP overlay for unmasked, non-degraded content. GlPipFrameOverlay uses frameWidth × 0.3 × scale and frameHeight × 0.3 × scale, rather than fitting the source dimensions. At scale 0.35, a matching-aspect source occupies approximately 10.5% of the frame width instead of 35%. Source dimensions are absent from this size calculation. The Canvas overlay instead fits the actual source.

**Source evidence:** [BlendModeGlEffect.java:285](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/export/BlendModeGlEffect.java#L285); [GlPipFrameOverlay.java:223](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/export/GlPipFrameOverlay.java#L223); [PipFrameOverlay.java:140](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/export/PipFrameOverlay.java#L140).

**Suggested correction:** Give GL and Canvas export one source-aspect-aware placement calculation, shared with preview. Remove the placeholder 0.3 multiplier.

**Acceptance checks:** Compare portrait, landscape, and square PiP with masks on/off, several scales, rotation, and both export paths. Assert output bounds and aspect ratio against preview.

**Proof / limits:** Static source verification and arithmetic only; no rendered device comparison was run.

### 2. R2-FX-02 — PiP decoder consumes a fresh source frame for every output frame

**Severity: S1. Fix difficulty: D3.** High · source-confirmed missing current-frame reuse; visible rate depends on decoder cadence.

**Trigger / reproduction target:** Export a lower-frame-rate PiP into a higher-frame-rate project, or slow a PiP source so several output frames should reuse one decoded frame.

**User impact:** The PiP can advance too quickly and reach end-of-stream early, then hold its last frame. It also performs avoidable decoder work. A 24 fps source sampled at 30 fps is a useful verification case; exact timing depends on timestamp and decoder behavior.

**Root cause:** SurfaceFrameReader.updateTo() reuses only the end-of-stream result. Otherwise it calls decodeForwardTo() even when the currently latched source frame already covers the requested target. decodeForwardTo() dequeues another frame and accepts the first presentation timestamp at or beyond the target. Repeated targets inside an already-covered source interval therefore consume another source frame.

**Source evidence:** [SurfaceFrameReader.java:104](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/export/SurfaceFrameReader.java#L104); [SurfaceFrameReader.java:235](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/export/SurfaceFrameReader.java#L235); [SurfaceFrameReader.java:284](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/export/SurfaceFrameReader.java#L284).

**Suggested correction:** Track the latched frame timestamp/validity and reuse its texture until the requested source time requires advancing. Define explicit behavior for repeated and backward time requests.

**Acceptance checks:** Export 24→30 and 24→60 fps, slow motion, repeated timestamps, clip offsets, and end-of-stream. Check numbered source frames and decoder dequeue counts.

**Proof / limits:** The source establishes unnecessary advancement. No on-device export was run to measure the exact speedup or freeze point.

### 3. R2-JB-01 — Failed layer duplication can leave a phantom layer and escape the render loop

**Severity: S1. Fix difficulty: D3.** High · source-confirmed optimistic publication and uncaught command failure.

**Trigger / reproduction target:** Duplicate a populated layer when a texture/framebuffer allocation or copy fails. Include masks, animation cels, editable lines, and media payloads.

**User impact:** The UI can adopt the duplicate before the engine succeeds. The engine correctly rolls back its own stack and rethrows, but the queued caller does not restore the UI or handle the failure. The render command can escape onDrawFrame, potentially stopping rendering or crashing depending on Android recovery behavior.

**Root cause:** JbCanvasView adopts the after-stack before scheduling duplicateLayerStep(). GlPaintEngine performs copies, rolls back partial publication on failure, and rethrows. initializeTile() can throw GraphicsMemoryException. The frame-work queue is drained outside the later media error handling, and this duplication command has no matching catch/resynchronization.

**Source evidence:** [JbCanvasView.kt:1060](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt#L1060); [GlPaintEngine.kt:1221](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt#L1221); [GlPaintEngine.kt:1263](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt#L1263); [GlPaintEngine.kt:2774](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt#L2774); [JbCanvasView.kt:1576](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt#L1576); [JbCanvasView.kt:4292](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt#L4292).

**Suggested correction:** Publish the UI stack only after engine success, or restore it from authoritative engine state on failure. Contain expected graphics-memory failures at the command boundary and report a refused duplicate.

**Acceptance checks:** Inject failure during raster, mask, line, and media copying. Verify that original art, selection, layer list, undo state, and subsequent drawing all remain usable.

**Proof / limits:** Failure propagation is source-confirmed. An actual Android crash was not reproduced; severity reflects loss of a core editing session under a realistic memory failure.

### 4. R2-AUD-02 — Muted audio clips and lanes can still play in preview

**Severity: S2. Fix difficulty: D2.** High · source-confirmed gain arguments discarded.

**Trigger / reproduction target:** Mute an audio clip or its lane, then play or seek into its active interval. Compare preview with export.

**User impact:** Preview can play audio the user explicitly muted, while export skips the muted clip. This undermines mix decisions and preview/export parity.

**Root cause:** AudioLayerSync and editor playback calculate effective preview volume, including mute, and call setVolume(left,right). AudioClipPreviewPlayer.setVolume() discards both arguments and refreshes processors from the raw clip. The processor refresh does not apply mute. Initial muted-chain construction returns an empty processor chain, which provides no silence gate. Scheduling still considers muted clips in range.

**Source evidence:** [AudioClipPreviewPlayer.java:282](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/compositor/AudioClipPreviewPlayer.java#L282); [AudioLayerSync.java:274](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/audio/AudioLayerSync.java#L274); [AudioLayerSync.java:489](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/audio/AudioLayerSync.java#L489); [AudioFxChainFactory.java:134](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/audio/fx/AudioFxChainFactory.java#L134); [AudioFxChainFactory.java:197](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/audio/fx/AudioFxChainFactory.java#L197); [FaditorEditorActivity.java:10359](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java#L10359); [FaditorEditorActivity.java:10390](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java#L10390); [ExportManager.java:6459](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/export/ExportManager.java#L6459).

**Suggested correction:** Preserve a dedicated preview mute/gain gate for effective clip-and-lane state. Keep that gate separate from the DSP chain so processor refreshes cannot restore audible output.

**Acceptance checks:** Test clip mute, lane mute, mute toggled while playing, seek into muted content, unmute, and FX-enabled clips. Compare with export.

**Proof / limits:** Source-confirmed across the player and callers; no listening test was run.

### 5. R2-AUD-01 — Stereo audio ignores fades and volume automation

**Severity: S2. Fix difficulty: D2.** High · source-confirmed channel-specific arithmetic.

**Trigger / reproduction target:** Apply a fade or volume keyframe envelope to a stereo song/clip and preview or export it. Compare the same material rendered as mono.

**User impact:** The stereo signal keeps the static volume/pan gains instead of following the requested fade or automation. Mono takes the envelope path, producing inconsistent behavior between channel layouts.

**Root cause:** VolumeAudioProcessor calculates frameGain from base volume and the envelope. The mono branch applies frameGain; the multi-channel branch applies only the static channelGains array. Advancing frameGain later in the loop does not change what the stereo branch multiplies.

**Source evidence:** [VolumeAudioProcessor.java:91](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/export/VolumeAudioProcessor.java#L91); [VolumeAudioProcessor.java:106](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/export/VolumeAudioProcessor.java#L106); [VolumeAudioProcessor.java:140](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/export/VolumeAudioProcessor.java#L140); [AudioFxChainFactory.java:145](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/audio/fx/AudioFxChainFactory.java#L145); [ExportManager.java:6541](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/export/ExportManager.java#L6541).

**Suggested correction:** Apply the time-varying envelope to every output channel while preserving pan and applying base volume once.

**Acceptance checks:** Use a constant stereo tone with known fade/keyframe points; measure both channels at the start, middle, and end. Repeat mono and panned stereo.

**Proof / limits:** Static arithmetic verification, not an executed PCM regression test.

### 6. R2-AUD-03 — Multichannel PCM can overrun the two-channel gain array before downmix

**Severity: S2. Fix difficulty: D2.** High for unsafe indexing · conditional reachability on actual decoder output.

**Trigger / reproduction target:** Import audio decoded as more than two PCM channels, such as 5.1, into a Studio path that runs VolumeAudioProcessor before stereo downmix.

**User impact:** The processor can throw when channel index 2 accesses a two-element gain array, aborting playback/export. This is a distinct Studio chain-order defect from round one's VisLab multichannel interpretation issue.

**Root cause:** onConfigure() accepts PCM16 with arbitrary channel count. channelGains() returns one gain for mono or two for every other layout. queueInput() cycles channelIdx through the actual channel count and indexes gains[channelIdx]. ExportManager.withStereoOutput() appends its ChannelMixingAudioProcessor after existing processors, so that downmixer does not protect this earlier indexing.

**Source evidence:** [VolumeAudioProcessor.java:106](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/export/VolumeAudioProcessor.java#L106); [VolumeAudioProcessor.java:118](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/export/VolumeAudioProcessor.java#L118); [VolumeAudioProcessor.java:154](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/export/VolumeAudioProcessor.java#L154); [ExportManager.java:6191](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/export/ExportManager.java#L6191); [ExportManager.java:6220](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/export/ExportManager.java#L6220).

**Suggested correction:** Downmix before stereo-only gain/pan processing, or support/reject channel layouts explicitly before indexing.

**Acceptance checks:** Pass 3-, 6-, and 8-channel PCM through the real processor order and a decoded multichannel sample. Check output channel count and absence of array errors.

**Proof / limits:** The out-of-bounds condition is explicit in source. Whether a particular device decoder negotiates multichannel PCM into this chain remains untested.

### 7. R2-FX-04 — PiP and image effect keyframes use chunk-local time

**Severity: S2. Fix difficulty: D2.** High · source-confirmed clock mismatch.

**Trigger / reproduction target:** Animate an image/PiP effect on an absolute editor timeline, then export a later chunk or content after a transition where the exporter supplies a nonzero editor-time offset.

**User impact:** Placement/mesh animation can use the correct editor time while effect values resolve against an earlier local time. The exported object can be in the right place with the wrong blur/color/other keyed FX state.

**Root cause:** ExportManager passes editorTimeOffsetForChunk/overlayOffsetMs into these overlays. Geometry uses the offset, but BlendModeGlEffect and ImageBlendGlEffect resolve their FX stack with pts / 1000 alone. FxStack's contract defines its keyframe time as the absolute editor timeline.

**Source evidence:** [BlendModeGlEffect.java:344](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/export/BlendModeGlEffect.java#L344); [ImageBlendGlEffect.java:736](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/export/ImageBlendGlEffect.java#L736); [FxStack.java:155](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/studiokit/src/main/java/com/fadcam/ui/faditor/fx/FxStack.java#L155); [GlPipFrameOverlay.java:70](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/export/GlPipFrameOverlay.java#L70); [ImageBlendGlEffect.java:474](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/export/ImageBlendGlEffect.java#L474); [ExportManager.java:7403](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/export/ExportManager.java#L7403); [ExportManager.java:7609](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/export/ExportManager.java#L7609).

**Suggested correction:** Resolve each FX stack with the same editor-time offset used by its geometry, using a single documented time conversion.

**Acceptance checks:** Place distinctive FX keyframes around transition boundaries and later export chunks. Compare preview, single-chunk export, and chunked export at the same editor timestamps.

**Proof / limits:** Source clock comparison only; rendered parity has not been tested.

### 8. R2-FX-03 — GL PiP opacity violates the downstream premultiplied-alpha contract

**Severity: S2. Fix difficulty: D2.** Medium · shader mismatch confirmed; visible output depends on GL staging state.

**Trigger / reproduction target:** Export a partially transparent PiP using the ordinary GL path. Compare opacity 1.0, 0.5, and near zero over black and a colored background; repeat blend and matte modes.

**User impact:** The shader chain can preserve too much source brightness or produce incorrect blend/matte results instead of a proportional fade. This requires a GPU render check before treating a particular visual symptom as reproduced.

**Root cause:** The PiP staging shader emits vec4(c.rgb, c.a * uAlpha), reducing alpha without reducing RGB. BlendModeGlEffect later divides RGB by alpha as if the staged sample were premultiplied, then composites by alpha. Under direct replacement staging, gray 0.2 at alpha 0.5 over black yields approximately 0.2 instead of 0.1. The staging path does not establish an explicit blend-state contract that resolves this representation mismatch.

**Source evidence:** [GlPipFrameOverlay.java:164](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/export/GlPipFrameOverlay.java#L164); [BlendModeGlEffect.java:192](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/export/BlendModeGlEffect.java#L192); [BlendModeGlEffect.java:200](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/export/BlendModeGlEffect.java#L200).

**Suggested correction:** Choose one alpha representation for the staging texture and all downstream consumers. Set GL blending explicitly; premultiply RGB when producing a premultiplied texture.

**Acceptance checks:** Render known-color fixtures and read pixels at several opacities across normal, blend, and matte modes. Include reused GL state and multiple overlays.

**Proof / limits:** This is a source-confirmed contract inconsistency with conditional visual impact. No GPU pixel-readback test was run.

### 9. R2-JB-02 — Board metadata edits prematurely dry an unrelated wet wash

**Severity: S2. Fix difficulty: D2.** High · source-confirmed metadata-to-dry path.

**Trigger / reproduction target:** Keep a wet-sheet wash active, then rename/lock a board or change animation FPS, end frame, or hold metadata. Continue painting into the wash.

**User impact:** A metadata-only operation can dry and print the current wet sheet even though it does not write its pixels. The next stroke loses the wet interaction the user expected. Drying also adds unnecessary media work.

**Root cause:** BoardRuntimeController.metadata() wraps metadata in a RegionChange and routes it through the normal board-edit host. GlPaintEngine.applyBoardChange() invokes beforeLayerWrite(null) unconditionally, before validating the change or checking whether any pixels move. MediaCanvas maps that callback to settleSheet(); WetSheet.settle() calls dryNow() and print(). Null targets whichever layer currently holds the sheet.

**Source evidence:** [BoardRuntimeController.kt:555](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/BoardRuntimeController.kt#L555); [BoardRuntimeController.kt:578](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/BoardRuntimeController.kt#L578); [GlPaintEngine.kt:2339](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt#L2339); [MediaCanvas.kt:70](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaCanvas.kt#L70); [MediaCanvas.kt:390](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaCanvas.kt#L390); [WetSheet.kt:492](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/WetSheet.kt#L492).

**Suggested correction:** Validate first and fence only operations that actually write affected paint planes. Metadata changes, no-ops, and refused board operations should not settle unrelated wet paint.

**Acceptance checks:** For each listed metadata operation, compare subsequent wet-on-wet painting with a no-edit control. Also verify that real pixel moves still settle safely when required.

**Proof / limits:** Source-confirmed side effect. Exact resulting paint appearance was not exercised on a device.

### 10. R2-JB-03 — Frame duplication and Sprite pixel moves retain appearance but lose physical paint payload

**Severity: S2. Fix difficulty: D4.** High · explicit implementation limit, also visible in the copy path.

**Trigger / reproduction target:** Create a cel with physical media payload, such as oil/pencil/legacy-water state, then duplicate the frame or move/swap Sprite board cells through a board pixel operation. Continue physical painting in the result.

**User impact:** The copied/moved pixels preserve their visible appearance, but the associated physical state is absent or settled. The result cannot continue the same wet/media interaction. This is an unfinished capability boundary, not a claim that the displayed pixels disappear.

**Root cause:** Board transactions prepare/publish pixel copies, then settlePlainWrites() removes physical payload for rewritten tiles. The source explicitly says board-moved paint loses payload until slice 3b carries it. In contrast, ordinary layer duplication has an explicit per-plane payload-copy loop, showing that retaining appearance and retaining physical state are separate operations.

**Source evidence:** [GlPaintEngine.kt:2414](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt#L2414); [GlPaintEngine.kt:2541](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt#L2541); [GlPaintEngine.kt:2586](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt#L2586); [GlPaintEngine.kt:6947](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt#L6947); [GlPaintEngine.kt:1236](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt#L1236).

**Suggested correction:** Carry physical stores with the same per-cel ownership, copy/drop mapping, budget checks, and undo transaction as the pixel move. Preserve absent-payload semantics when replacing destination content.

**Acceptance checks:** Compare source and duplicated/moved cel behavior for each supported physical brush family, then undo/redo and save/reopen. Include overlapping cell moves and allocation failure.

**Proof / limits:** Explicitly recorded as an implementation limitation. Active wet-sheet settling is a separate mechanism covered by R2-JB-02; this finding concerns per-plane physical payload. It does not repeat round one's save-budget flattening issue.

### 11. R2-TIME-01 — Lock-only time shuttle can step past one obstacle into another

**Severity: S2. Fix difficulty: D2.** High · deterministic source counterexample and caller verification.

**Trigger / reproduction target:** Use the time shuttle in lock-only/no-push mode with several tightly spaced neighboring objects. Jump far enough to cross both neighbors.

**User impact:** The anti-overshoot correction can commit an overlapping placement even though the initially resolved destination was legal. The preview/commit host accepts that corrected start without another occupancy check.

**Root cause:** ObjectTimeScrubSession first resolves the desired start against occupancy. Its lock-only forward anti-overshoot helper can then replace that valid start with the first crossed object's end without checking whether the moved object's whole duration fits before the next object. For a 1,000 ms object starting at 0, neighbors [2,000,3,000) and [3,500,5,000), and a jump to 6,000, resolution permits 6,000 but the helper returns 3,000. The resulting [3,000,4,000) overlaps the second neighbor by 500 ms.

**Source evidence:** [ObjectTimeScrubSession.java:106](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/move/ObjectTimeScrubSession.java#L106); [ObjectTimeScrubSession.java:135](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/move/ObjectTimeScrubSession.java#L135); [FaditorEditorActivity.java:30111](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java#L30111); [FaditorEditorActivity.java:30129](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java#L30129); [FaditorEditorActivity.java:30174](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java#L30174).

**Suggested correction:** Run the complete proposed span back through collision resolution after anti-overshoot correction, or have that helper search only legal gaps. Apply the same rule in both directions.

**Acceptance checks:** Exercise tight gaps, touching neighbors, jumps across multiple objects, both directions, and undo/redo through the actual shuttle host.

**Proof / limits:** The counterexample was traced through source. No interactive move was performed.

### 12. R2-PACK-01 — Failed supply-pack updates can leave a mixed installed version

**Severity: S2. Fix difficulty: D3.** High · source-confirmed per-file publication without pack transaction.

**Trigger / reproduction target:** Install a new version of an existing pack, then encounter an I/O failure or interruption after some files have been replaced but before all files finish.

**User impact:** The previous installed pack is partly overwritten despite the UI reporting installation failure. Manifest and assets can belong to different versions, affecting rooms that use the shared pack.

**Root cause:** PackInstaller reuses the existing same-ID pack folder and writes files individually with writeAtomic(). Atomicity is per file, not per pack. There is no staged complete replacement or rollback for already replaced files. PackOpenActivity catches the failure and displays a failed-install result without restoring the prior directory.

**Source evidence:** [PackInstaller.java:95](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/cc/joycreator/textlab/PackInstaller.java#L95); [PackInstaller.java:108](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/cc/joycreator/textlab/PackInstaller.java#L108); [PackOpenActivity.java:111](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/cc/joycreator/textlab/PackOpenActivity.java#L111).

**Suggested correction:** Stage and validate a complete version, then publish the directory/version pointer as one operation suitable for the storage provider. Retain the old version until publication succeeds.

**Acceptance checks:** Fail the Nth write and interrupt installation at each phase on filesystem and SAF providers. The old pack must remain coherent or the new pack must be fully published.

**Proof / limits:** Source-confirmed update-consistency gap. No installed pack was modified.

### 13. R2-PACK-02 — Distinct pack IDs collide in Studio's local supply-cache paths

**Severity: S2. Fix difficulty: D2.** High · deterministic ID-to-path collision.

**Trigger / reproduction target:** Install two valid packs with different IDs that sanitize to the same string, and give them an entry with the same filename. Examples: 'Artist Pack' and 'Artist_Pack', or 'a/b' and 'a?b'.

**User impact:** The shelf retains distinct supply identities, but both can reference the same cached local file. One pack's font/grade/style can substitute for the other, including in existing projects. The original installed assets remain intact.

**Root cause:** PackSuppliesSync forms a logical ID from the raw pack ID, but forms its cache folder from safe(pack.id). safe() replaces runs outside [A-Za-z0-9._-] with '_', a many-to-one transformation. JoyPack parsing requires a nonempty ID but does not restrict IDs to that safe alphabet. No collision suffix/hash separates the resulting folders.

**Source evidence:** [PackSuppliesSync.java:77](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/joyfolder/studio/PackSuppliesSync.java#L77); [PackSuppliesSync.java:118](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/joyfolder/studio/PackSuppliesSync.java#L118); [JoyPack.java:84](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/studiokit/src/main/java/com/fadcam/joyfolder/JoyPack.java#L84); [PackSupplies.java:1](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/joyfolder/studio/PackSupplies.java#L1).

**Suggested correction:** Use a collision-free encoding or a stable hash of the complete canonical pack ID for local cache ownership. Migrate existing shelf references safely.

**Acceptance checks:** Sync colliding ID pairs with different file bytes; verify distinct paths and correct rendered/loaded assets before and after restart/resync.

**Proof / limits:** Deterministic source mapping; no user packs were installed.

### 14. R2-PACK-03 — A transient pack read failure removes a usable cached supply from the shelf

**Severity: S2. Fix difficulty: D2.** High · source-confirmed retention ordering.

**Trigger / reproduction target:** Start with a successfully cached font/grade/style. During the next supply sync, make just that source entry unreadable or cause its copy to fail while the existing local cache remains usable.

**User impact:** The newly written shelf can lose the old entry even though its cached file still exists. Projects can fall back or lose access to the supply until a successful resync.

**Root cause:** PackSuppliesSync marks the source ID as seen before it validates/reads/copies the source. If those steps fail, no new item is added. The old-item retention loop then discards the old entry because that ID is already in the seen set. The final shelf write publishes the omission.

**Source evidence:** [PackSuppliesSync.java:70](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/joyfolder/studio/PackSuppliesSync.java#L70); [PackSuppliesSync.java:81](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/joyfolder/studio/PackSuppliesSync.java#L81); [PackSuppliesSync.java:93](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/joyfolder/studio/PackSuppliesSync.java#L93).

**Suggested correction:** Retain the last known usable item on transient source/copy failure. Distinguish a successfully refreshed ID from an encountered but unavailable source ID.

**Acceptance checks:** Inject stat/read/copy failure for one cached entry while other entries sync. Verify the old mapping survives and later refreshes normally.

**Proof / limits:** Source-confirmed shelf logic. This is independent of the cache-name collision and the installer transaction findings.

### 15. R2-TIME-02 — Time snapping can move an object past the host's maximum start

**Severity: S3. Fix difficulty: D1.** High · deterministic clamp/snap ordering.

**Trigger / reproduction target:** Use a snap increment that does not divide the allowed maximum start. Move to the end boundary with no intervening obstacle.

**User impact:** The final start can exceed the host's advertised maximum by up to approximately half a snap increment. This is a narrow boundary-placement error.

**Root cause:** ObjectTimeScrubSession clamps desired time to maxStart, then rounds that already-clamped result to the nearest snap without clamping again. For maxStart=950 ms and snap=100 ms, desired=950 becomes 1,000 ms.

**Source evidence:** [ObjectTimeScrubSession.java:102](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/move/ObjectTimeScrubSession.java#L102); [ObjectTimeScrubSession.java:107](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/move/ObjectTimeScrubSession.java#L107); [FaditorEditorActivity.java:30128](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java#L30128).

**Suggested correction:** Enforce the boundary after snapping and make collision resolution respect the same final bound.

**Acceptance checks:** Check non-aligned end caps, half-step ties, start zero, and a collision adjacent to the end cap.

**Proof / limits:** Arithmetic/source verification only; no interactive timeline test was run.

## Additional leads — not included in the 15 findings

These are useful next investigations, not confirmed defects or extra severity-count entries.

### W1 — Mono envelope timing after seek

AudioClipPreviewPlayer seeks/parks the underlying player without visibly updating VolumeAudioProcessor's envelope start. The processor resets frame position on flush. Export explicitly supplies envelope offsets for pieces. This suggests a preview fade/keyframe phase reset after seeking, particularly for mono, where the envelope is actually applied.

Evidence: [AudioClipPreviewPlayer.java:195](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/compositor/AudioClipPreviewPlayer.java#L195), [VolumeAudioProcessor.java:181](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/export/VolumeAudioProcessor.java#L181), [ExportManager.java:6544](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/export/ExportManager.java#L6544).

**Unresolved:** Verify Media3's actual flush/seek lifecycle and resulting envelope clock before assigning a defect rank. Suggested test: mono constant tone with a distinctive envelope, seeking repeatedly to the same timeline point.

### W2 — Audio sequence timing across folded video transitions

Export maps audio-piece starts through the transition time map, but appears to keep source durations when appending a sequential audio lane. If a preceding clip still occupies its original duration, the next mapped start can fall behind the actual append cursor.

Evidence: [ExportManager.java:6473](https://github.com/JoyRaptor/JoyCreator/blob/65cbae3e31d6e810567f1a3d437823f2f7ac0ddc/app/src/main/java/com/fadcam/ui/faditor/export/ExportManager.java#L6473).

**Unresolved:** Establish intended continuous-music versus clip-aligned audio policy, then compare two adjacent audio clips around a 600 ms video transition using audible/visual markers. Do not classify ordinary intentional music continuity as a bug.

## Coverage and remaining gaps

| Area | Deeper paths inspected in this wave | What this wave does not establish |
|---|---|---|
| Studio audio | Mute propagation, mono/stereo gain arithmetic, processor channel layout/order, seek-clock and transition-timing leads | Actual decoder negotiation, audible device parity, performance measurements |
| Studio PiP/images | GL versus Canvas geometry, decoder advancement, opacity representation, chunk-to-editor FX time conversion | GPU-state-specific pixels, every mask/blend/source-format combination |
| Studio timeline | Lock-only object shuttle, occupancy correction, snapping and end limits | General ripple/group movement, every clip type or multi-selection operation |
| JoyBrush | Layer duplicate failure publication, metadata edits while wet, animation/Sprite pixel copies versus physical payload | Every board/brush combination, device memory stress, complete undo/save/reopen matrix |
| TextLab / JoyFolder / Studio supplies | Pack replacement consistency, ID-to-cache ownership, transient-source cache retention | Exhaustive TextLab editing/export behavior or every SAF provider |
| VisLab and room handoffs | Existing round-one findings remain open; supply handoff paths were extended here | A second exhaustive VisLab sweep or closure of prior cross-room findings |

The efficiency findings here are specific: redundant PiP decoding and unnecessary wet-sheet drying/printing. Their cost is not benchmarked. Cache resilience findings also identify unnecessary supply loss/resynchronization, rather than a measured speed improvement.

Useful next areas include drawn-mask gesture cancellation, linked-group/ripple operations, caption word editing, additional project/asset-transfer cases, and a systematic device matrix for physical brushes across Animation and Sprite boards. Those are coverage gaps, not assertions of defects.

## Developer handoff

Keep this report alongside round one. Start with the three S1 findings, then audio mute/envelope correctness and export clock parity. Verify the conditional multichannel/GPU cases with focused fixtures before broad rewrites.

No fixes or project-state updates accompany this report. The suggested corrections and acceptance checks are research output for the owner to batch with other waves.

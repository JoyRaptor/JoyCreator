# Animation clips implementation — 2026-10-18

Checkout: `C:/Temp/jb-clips`, branch `lead/anim-clips`, continuing C1/C2 `517a9c61d` and partial C3 `d232a2b17`.

## Changes

C1/C2 retain per-layer overlap ownership and active-layer-only clip creation. C3/C4 add saved end modes, true blank projection, independent simultaneous playback clocks and final Hold/Blank previews. C5 uses transactional region changes and component cel references to retain drawing identity when two same-layer timelines touch. C6 samples original frames before publishing combined/flattened output and isolates only the selected linked frame for Merge Down. C7 adds scene export with shared playback clocks, bounded Full loop, GIF loop flags and cumulative delay quantization. C8 adds transient artwork movement while dragging and a single committed move, leaving still artwork and masks stationary.

`DOC_VERSION=13`: v12 introduced saved clip end behaviour; v13 adds acyclic `Cel.parts` references with disjoint world-space rectangles and physical fallback outside each reference. Cloning remaps the whole graph; deletion follows transitive references; painting/rendering resolve physical fragments. Repeated components remain editable as the same drawing across differing composite pairs. Existing default-loop documents omit the new default fields. Confirm schema-version allocation with concurrent engine lanes before integrating.

## Verification

Final source checks completed October 19 and were reread October 22: core 2548 tests (0 failures/errors, 4 optional skips); androidkit 1056 (0 failures/errors/skips); Android 330 (0 failures/errors/skips). Full core/backend log `C:/Temp/jb-clips-all-check.log` reports BUILD SUCCESSFUL in 8m57s; native log `C:/Temp/jb-clips-native-check.log` reports BUILD SUCCESSFUL in 3m47s. Earlier exploratory failures are superseded by these runs.

Added focused coverage for independent clocks, saved Hold/Blank, unknown end fallback, retiming/end-mode links, component ownership and graph lifecycle, scene sampling/duration caps, GIF timing/loop metadata, archive pixels/links, one-step engine history and controller drag/playback cancellation. The engine history tests use empty physical stores and prove publication/Undo, not GPU pixel correctness.

Final fixes included the low-level active-layer creation default, rejection of malformed shared-cel components, actual GIF encoder warning propagation, and startup pen-fade initialization that could hide transport/end controls. `git diff --check` passed again October 22. Native chrome-light.png/chrome-dark.png under `joybrush-android/build/reports/animation-clips/` are generated UI proof, not device screenshots.

## Outstanding acceptance

- Note9 is connected, but real GPU Combine/Flatten/Merge Down pixels, Undo/Redo, drag preview, archive reload and GIF decode still need the phone pass. The disposable eight-row GPU probe is compiled but unexecuted.
- `playAll` benchmark implemented with 256/512/1024 px clips at 25/50/12 fps, on three layers, with a 1152 px canvas. No measured 60 fps result yet. Launch the existing debug bench activity with `--es jb_bench playAll`; read the `playAll` JSON under the existing BenchRunner log tag.
- U1–U8 are implemented, including cyan cut-out end chips, labeled Play all/Stop and overlapping selection cycling. UI handoff: `tasks/joybrush/handoffs/ANIMATION_CLIPS_UI_HANDOFF_20261018.md`. Real device screenshots are still outstanding.

No feature APK was built or installed and no owner drawing was opened or changed. Changes remain uncommitted in the isolated feature branch. The owner requested a resume handoff October 22: `tasks/joybrush/handoffs/ANIMATION_CLIPS_RESUME_20261022.md` contains current resource coordination, verified evidence, source recovery snapshot and exact next steps. Neither shared lock exists at that inspection; the current primary install watcher must be coordinated before any new build/device action.

## Note 9 acceptance — 2026-10-22

Feature APK: `C:/Temp/jb-clips-proof/animation-clips-debug.apk` (arm64, SHA256 `A73A816B24E8FAA434444AF6D3E074ED0BF3210C827F9E58216D205DBD3DB7DA`, BUILD SUCCESSFUL 7m14s, log `C:/Temp/jb-clips-apk.log`). Installed with `adb install -r` on Note 9 (SM-N960U, `$SANDBOX_PHONE`), device copy hash re-verified identical. Never uninstalled; previous app backed up as `C:/Temp/jb-clips-proof/pre-install-20261022.apk`. Owner drawings and the Note 20 untouched; bench mode only.

Coordination note: the owner's shell loop kept restarting `gradlew -t installDefaultDebug` and reinstalling the main build over the feature APK within seconds (first two bench attempts therefore ran the main build and logged `no case 'animationClips'`). With the owner's permission the loop was stopped for the test window and restored afterwards.

### Eight-row GPU proof (`--es jb_bench animationClips`): PASSED
`passed=true`, `cleanupComplete=true`, `ownerFilesTouched=false`; all eight rows true: fixtureGpuReadback, combinePixelsAndSingleUndo (6 frames / 12 layer comparisons, 1 undo), directMovePreviewCancelAndCommitUndo, flattenFramewiseCompositeAndSingleUndo, singleGifSavedEndFlagsAndSceneGif (loop+ping-pong carry the loop extension; hold/blank do not; scene 6 frames), componentLinksEditAndMoveAcrossTiles, componentCurrentFrameMergeAndUndo (3 other frames unchanged, redo verified), diskArchivesReloadIntoGpu (5 archives, 25 GPU frames). Raw report: `animation-clips-evidence/gpu-proof-logcat.txt`; generated GIFs pulled beside it. GIF header/NETSCAPE check done by byte scan; a full frame-duration decode is still owed.

### Play all performance (`--es jb_bench playAll`), real BoardRuntimeController: MET for frame work; rare hitches open
First measurement (frame "busy" = whole frame incl. the first command on the window surface): fps 58.6-59.4, busy p95 17.2-17.7 ms vs 16.67 budget. Profiling (10-22, Note 9, 26.6 C):
- A no-playback baseline (`--es jb_bench playAllStatic`, same canvas, same frames) measured the same: busy p50 14.2, p95 17.3. The clips are not the cost.
- The cost sat in `draw:paper` (7.5-9 ms p50) although the paper is cached and the frame only blits it: that first command on the window surface blocks until the compositor returns a buffer (vsync idle time). A bench-only probe now times that wait before the frame starts (`view:windowBufferWait`, 8-10 ms) and keeps it out of the frame's busy figure; `draw:paper` falls to 0.07 ms.
- Real frame work during Play all on the final build (three runs): busy p50 5.3-5.6 ms, p95 8.75-11.0 ms (budget 16.67), fps 57.8-58.7, errors none. Playback adds about 1.5 ms over the static baseline (setFramePreviews ~0.7 ms, tile projection up to ~1.5 ms). No render logic was changed.
- OPEN: rare long frames. Dropped frames (gap > 34 ms): playback 1-5 per 5 s, static 0; worst single frame 198 ms (outside every timed phase) and gaps of 88-108 ms with no busy spike. Not yet attributed (startup, GC, or UI-thread stall); a per-hitch timestamped recorder is the next step.
- Evidence: `animation-clips-evidence/playall-*.txt|json`, `gpu-proof-final-build.txt` (8-row proof re-run on the final build: passed, cleanupComplete).
- Final APK: `C:/Temp/jb-clips-proof/animation-clips-debug-final.apk`, SHA256 `8AF9E6BBB0FE0C1EC6D3F51CDFDC1833054AC873B9F5A2AF064690D83E793D30`; androidkit backend 1056 tests, 0 failures after the change.
### Real phone screenshots (`animation-clips-evidence/`)
`note9-playall-fixture-1.png` (three overlapping clips playing, saved end chips with Ping-pong solid cyan, labelled Stop all, runner), `note9-chip-hold-selected.png` (Hold chip solid cyan with cut-out icon after tap, Play all), `note9-play-all-running-hold-chip.png` (Stop all while playing).

### Still not verified on device
Real-finger checks of drag/lock, Combine, Flatten, repeated overlap taps and export through the UI (covered by the GPU probe and Robolectric, not by gestures); GIF frame-duration decode.

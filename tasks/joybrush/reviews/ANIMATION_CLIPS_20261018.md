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
- Rare long frames, attributed (10-22, hitch recorder + UI trace + 5 ms UI-thread stack sampler; bench cases `playAll`, `playAllStatic`, `playAllTrace`): 
  1. One ~85-110 ms UI-thread stall as playback starts (every run; static baseline has none). The first Choreographer frame spends ~70-90 ms in the UI draw pass (layout only ~8 ms, bench callback 0.1 ms). The sampler puts it in `BoardChromeView.drawShadowPatch` creating/drawing software shadow-patch bitmaps (`Bitmap.createBitmap`, `FilmStrip.drawOutline`, saveLayer): pressing Play all changes the chrome state, so the elements' raster cache misses once. It behaves as ~0.1 s of press latency, then playback is smooth. Not fixed: pre-warming or splitting that rasterization is a `BoardChromeView` redesign, left for the owner to decide.
  2. First run after each install only: two ~190 ms stalls inside queued GL work (no timed phase, no GC). Absent from every later run; consistent with cold shader/JIT compilation. Not proven.
  3. Isolated single missed vsyncs (gap ~33 ms), about 0-3 per 5 s; GC ruled out (5 concurrent, non-blocking collections per run, counters unchanged across hitches). Steady-state UI thread: ~29% of samples handing frames to the renderer (`nSyncAndDrawFrame`), ~20% chrome draw/layout, ~2% patch rasterization.
  Final build, five runs: busy p50 5.3-5.7 ms, p95 8.1-9.2 ms; dropped frames (gap > 34 ms) 1-2 per run, all explained by item 1 plus an occasional single vsync; fps 58.3-59.2; errors none. GPU 8-row proof re-run: passed. Android unit tests 330/0 failures, androidkit 1056/0.
  Evidence: `animation-clips-evidence/playall-ui-stack-samples.txt`, `playall-ui-trace-runs.jsonl`, `playall-hitch-runs.jsonl`, `playall-final-build-runs.jsonl`, `gpu-proof-final-build-2.txt`. Final APK `C:/Temp/jb-clips-proof/animation-clips-debug-final2.apk` SHA256 `210AA284F569E74B13E9387E5DED6730F4F544A4327715B7E3DC829671416799`.
- Evidence: `animation-clips-evidence/playall-*.txt|json`, `gpu-proof-final-build.txt` (8-row proof re-run on the final build: passed, cleanupComplete).
- Final APK: `C:/Temp/jb-clips-proof/animation-clips-debug-final.apk`, SHA256 `8AF9E6BBB0FE0C1EC6D3F51CDFDC1833054AC873B9F5A2AF064690D83E793D30`; androidkit backend 1056 tests, 0 failures after the change.
### Real phone screenshots (`animation-clips-evidence/`)
`note9-playall-fixture-1.png` (three overlapping clips playing, saved end chips with Ping-pong solid cyan, labelled Stop all, runner), `note9-chip-hold-selected.png` (Hold chip solid cyan with cut-out icon after tap, Play all), `note9-play-all-running-hold-chip.png` (Stop all while playing).

### Still not verified on device
Real-finger checks of drag/lock, Combine, Flatten, repeated overlap taps and export through the UI (covered by the GPU probe and Robolectric, not by gestures); GIF frame-duration decode.

### Startup stall: chrome raster breakdown and device gestures (10-22/23)
- `animation-clips-evidence/playall-chrome-raster-misses.txt` (bench case `playAllTrace`): the board chrome rasterizes small cached patches by element. Before Play all is called it already misses 478 times (film-strip sprocket holes 348, cell frames 35, numbers 35, rest-frame 39); in the first 400 ms after Play all it misses 160 more (sprockets 116, rest-frame 18, cells 12, numbers 12), ~60-80 ms of UI-thread work, which is the ~90 ms start-of-playback stall. Each tiny sprocket costs ~0.44 ms (bitmap + software draw + cache put). Left unfixed deliberately: drawing the sprockets directly or merging them changes the strip's halo/appearance (owner's visual call).
- GIFs decoded from the phone (animation-clips-evidence/*.gif): loop 3x80 ms + loop flag; ping-pong 4x80 ms (1,2,3,2) + loop flag; hold and blank 3x80 ms, no loop flag; scene 4 frames 40/40/90/80 ms (cumulative rounding), no loop flag.
- Real touch on the Note 9 bench fixture: repeated finger taps on the clip overlap cycle the selected clip (Bouncer <-> Walker: strip, end chip Loop/Ping-pong and layer ring all follow); Hold/Ping-pong chips select in solid cyan; Play all <-> Stop all toggles; a stray stroke undoes in one step (gesture-4/5). NOT cleanly shown on device: dragging a clip by its edge (the cycling taps left it unclear which clip was selected when I swiped); lock, Combine, Flatten and export through the UI. The engine side of drag/Combine/Flatten/export is covered by the 8-row GPU proof; the controller side by Robolectric tests.
- Note: `adb shell input tap` did not cycle; `input touchscreen tap` did (the app treats them as different sources).
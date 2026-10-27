# Animation clips — resume handoff, 2026-10-22

## Start here

The owner requested the entire `tasks/joybrush/specs/ANIMATION_CLIPS_20261018.md`, including **C1–C8 and U1–U8**, and repeatedly asked to continue until fully finished. This handoff was explicitly requested on October 22. Implementation is present and all final JVM/Android unit suites pass. **The feature is not finished: the APK and Note9 acceptance pass have not been completed, and the changes have not been committed or integrated.**

Continue in **`C:/Temp/jb-clips`**, branch **`lead/anim-clips`**, HEAD **`d232a2b17d9ea6fed829ade40757e002d447f16c`**. Do not reset or clean it: both modified and untracked files are essential. HEAD includes earlier C1/C2 `517a9c61d` and partial C3 `d232a2b17`; subsequent implementation remains uncommitted.

The primary shared checkout is `C:/+Projects/Screenrecorder/FadCam`, branch `joy-creator`. It contains unrelated lanes. Do not copy the dirty feature tree over it, merge blindly, or discard its edits. Read its current `tasks/LANES.md` and repository instructions before coordination. The isolated checkout has its own `AGENTS.md` and `tasks/todo.md`.

Recovery source snapshot: `C:/Temp/jb-clips-proof/animation-clips-source-handoff-20261022.zip`. It contains modified/untracked files, not an entire repository; use the existing checkout first. Verification evidence bundle: `C:/Temp/jb-clips-proof/animation-clips-tests-handoff-20261022.zip`. Build helper copies are in `C:/Temp/jb-clips-proof/build-scripts/`.

## Verified state

The queued final run completed on **October 19**, after the final source fixes. XML totals were independently reread on October 22:

| Suite | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| core JVM | 2548 | 0 | 0 | 4 optional |
| androidkit backend | 1056 | 0 | 0 | 0 |
| joybrush-android Robolectric | 330 | 0 | 0 | 0 |

- `C:/Temp/jb-clips-all-check.log`: **BUILD SUCCESSFUL in 8m57s** (core/backend).
- `C:/Temp/jb-clips-native-check.log`: **BUILD SUCCESSFUL in 3m47s** (Android).
- XML directories: `joybrush/core/build/test-results/jvmTest`, `joybrush/androidkit/build/test-results/test`, `joybrush-android/build/test-results/testDebugUnitTest`.
- `git diff --check` passed again October 22; only LF/CRLF advisory messages for task documents.
- Native rendered UI proof: `joybrush-android/build/reports/animation-clips/chrome-light.png` and `chrome-dark.png`, generated in the final successful Android run. Both were visually inspected earlier; cyan selected chip/cut-out labels and overlapping outlines were legible. These are **Robolectric images, not phone screenshots**.

Earlier failed logs/test reports were intermediate, not the final result. The last two Android failures exposed a real startup fade-clock issue, now fixed and covered. No need to rerun all green suites without a source change or another concrete reason.

## What is implemented

- **C1/C2:** per-layer animated overlap validation; named refusals; new clips animate only the active layer. Both `BoardDocumentOps.createAnimation` and the default low-level `RegionDocumentOps.create` follow this policy. Explicit `animate=null` remains available for intentional all-layer callers; legacy fixtures now request it explicitly.
- **C3/C4:** saved Loop/Ping-pong/Hold/Blank, unknown values fall back to Loop; true transient blank projection; one `ClipPlaybackScheduler` with independent clocks and one changed preview map; Hold/Blank final preview persists until Stop. Single play/hover and Play all cancel each other without saving frame cursors; onion state restores.
- **C5:** transactional Combine; union/larger name/faster fps/per-drawing half-up retiming/end modes; linked component drawings preserve edits across different composite pairs; old clips removed and new selected; one Undo.
- **C6:** Flatten combines relevant overlapping participating clips, samples the original scene, then publishes all output frames in one Undo. Still content is copied into frames. Merge Down isolates only the selected current linked/component frame; other frames survive unchanged. Different overlapping clips offer Combine first.
- **C7:** single GIF loop flags and ping-pong order; scene Longest/Full loop export; rational cadence/period alignment; explicit 600-frame cap; GIF at up to 50 fps with cumulative centisecond timing; PNG timing metadata. Actual encoder warnings, including caps introduced only by GIF resampling, are collected by `BoardExportCoordinator.write` and appended to the existing UI-thread export completion toast by the Activity.
- **C8:** unlocked edge movement previews all animated drawings without saving metadata/pixels/history; cancel restores; release makes one Undo. Shared still pixels and masks remain stationary. Locked clips cannot move by touch. Invalid overlaps report names once per gesture and can recover during the drag.
- **U1–U8:** actual saved end chips with solid cyan selected state/cut-out text+icon/hover labels; labeled Play all/Stop all; both outlines visible with selected top; repeated finger overlap taps cycle clips while stylus paints; named Combine and retiming sentence; active-layer creation hint; named runner hover labels refreshed after rename; direct drag/lock behavior.

Detailed UI/API handoff: `tasks/joybrush/handoffs/ANIMATION_CLIPS_UI_HANDOFF_20261018.md` (updated to implemented UI). Review: `tasks/joybrush/reviews/ANIMATION_CLIPS_20261018.md`.

## Architecture and fixes to preserve

- **DOC_VERSION=13**: v12 saved `Board.end`; v13 adds default-empty `Cel.parts: List<CelPart(celId, rect)>`. Defaults are omitted in JSON. Recheck version allocation against other lanes before integration; do not blindly bump or downgrade.
- `core/doc/CelParts.kt`: bounded acyclic component graph, disjoint world-space pieces, physical fallback outside parts. Clone remaps graph; prune traverses reachability; moves translate part rectangles once; paint/render/capture resolve physical fragments. Shared canvas and mask cels cannot themselves carry component parts. Shared-component malformed files now fail validation before painting.
- `RegionPaintPlan` separates logical `roots` from physical fragment `frames`; validates exact disjoint root coverage, blank agreement and ownership. Do not replace logical roots with fragment count for current-frame operations.
- `CapturedPaintDomains` groups disconnected fragments belonging to one physical cel into one bounded capture domain with exclusions; logical identity remains validated.
- `AnimOps` calculates boundaries from cumulative integer exposure ticks times 1000/fps. Summing individual floating durations skipped exact boundaries; regression covers fps 1–60.
- `GlPaintEngine` transient move cache is keyed by **physical cel ID plus tile key**, preventing one component from overwriting another. Clear on document/frame/paint changes.
- `mergeComponentFrame` isolates current graphs and preserves the other drawings; one Undo, rollback/memory guard, lower native items retained where applicable. Upper lines intentionally become paint with a notice.
- Flatten distinguishes metadata regrouping from actually recomposed hidden animated layers. **Held-only hidden layers retain shared pixels/native items/masks.** Do not revert this distinction.
- GPU cleanup uses `graphicsTouched` so metadata-only/empty-store unit history paths do not call GLES without a context. Real graphics paths still clean up.
- `BoardChromePenFade` initial resting state anchors to supplied pen-lift age, not application uptime. Otherwise startup can fold controls with no timer to restore them. Two regressions cover resting startup and a genuine recent lift.

Main changed areas: `joybrush/core/.../{doc,anim,render,stroke,export,chrome}`, `joybrush/androidkit/.../{gl,io,layers,JbCanvasView.kt}`, `joybrush-android/.../{board,chrome,bench,JoyBrushActivity.kt}` and their tests. Use `git status --short` for the complete file list; many new implementation files are untracked.

## Build and phone coordination — recheck before acting

**October 22 observations:** `%TEMP%/jb-gradle.lock` and `%TEMP%/jb-phone.lock` are both absent. Note9 is connected as serial **`JoyRaptor`**, model **SM_N960U**. These observations are not reservations.

A primary Gradle watcher is currently running **`GradleWrapperMain -t installDefaultDebug`** (PID 28268 at inspection; PID is not stable). The old animation helper scripts only recognize the previous primary `-t :app:assembleDefaultDebug` watcher. **Do not run them blindly or run concurrent Gradle builds.** Coordinate the current install watcher first; it may also deploy to the phone. Inspect the current canonical LANES/build rules (newer lanes mention `tasks/joybrush/lead-scripts/lead-build.sh`, which is absent in this older feature checkout). Preserve the primary watcher and restore it hidden after any authorized pause.

Old build helpers, all outside Git:

- `C:/Temp/jb-clips-all-check.ps1`: CreateNew shared lock; pause known primary watcher; refuse other wrappers; stop idle daemons; memory admission; serial full core/backend then Android tests; finally restore environment, own lock and watcher.
- `C:/Temp/jb-clips-native-only.ps1`: Android suite only with same protocol.
- `C:/Temp/jb-clips-apk-check.ps1` and `jb-clips-watch-build.ps1`: **not executed yet**. Intended watcher-only APK assembly, success detection, stable copy to `C:/Temp/jb-clips-proof/animation-clips-debug.apk`, SHA256, then stop only own watcher and restore primary. Admission requires at least 3500 MiB free. Update coordination for the current watcher before use.
- SDK: `C:/Users/JoyRaptor/AppData/Local/Android/Sdk`; Gradle JDK17. Low-memory tests use one worker, in-process Kotlin, 1536 MiB Gradle heap and `C:/Temp/jb-tile-fixture-heap.gradle` test heap init script. APK helper uses 2048 MiB heap. Avoid concurrent Gradle wrappers and avoid unbounded heap growth.

Use CreateNew for both lock files, store a unique owner token, and remove a lock only if its full contents still match that token. No lease survives merely because an old handoff says it was held. Previous phone token `ANIMATION_CLIPS_20261019-1792435784649` is historical and **not currently present**.

Never uninstall, never touch Note20, never open/change owner drawings. HOME before install. The owner already authorized the full spec including Note9 acceptance; use disposable bench drawings. Current installed APK may have changed since October 19, so back it up afresh before installing. Historical backup `C:/Temp/jb-clips-proof/pre-animation-clips.apk`, 171067814 bytes, SHA256 `12725868CAA0D5DD3DF4D430AFB4BBE1024949EDC90481E546723F719ED46B19`, is from October 19 and must not be described as the current installation.

## Exact next steps

1. Read current coordination rules; reserve build/device resources without racing the primary install watcher. Do not rerun the completed final test pass solely because this is a new agent.
2. Build this checkout's debug APK using the coordinated watcher process. Save stable APK, build log and SHA256. **No feature APK has been built or installed yet by this task.**
3. Under a fresh phone lease, HOME, back up current installed APK, install with `adb -s JoyRaptor install -r <verified feature APK>`. Never uninstall if version/signature fails.
4. Run the disposable **8-row GPU proof**:

   ```powershell
   adb -s JoyRaptor shell am start -n com.fadcam.beta/com.fadcam.joybrush.JoyBrushBenchActivity --es jb_bench animationClips
   adb -s JoyRaptor logcat -d -v raw -s JoyBrushPerf:I
   ```

   `AnimationClipsProbe.kt` creates its own fresh cache directory. It checks GPU readback; Combine pixels/one Undo; move preview/cancel/commit/Undo; Flatten framewise pixels/one Undo; four single GIF end flags and scene GIF; same-layer component link edits/moves across tile boundaries; current component frame Merge Down/other-frame preservation/Undo/Redo; and reload of **all five saved archives into a reset GPU engine** with frame comparisons. The report must say `passed=true`, all eight rows true, and `cleanupComplete=true`. Probe has compiled but **has never run on the phone**; treat any failure as real work to fix.

   Large report lines use metadata chunks (`recordedProbeChunk=true`, case `animationClips`, run0, zero-based `part`, `parts`, `dataBase64`). Sort parts and concatenate decoded bytes to recover JSON. The report includes the private cache `directory`. Pull only its generated artifacts using `run-as`; do not read owner drawings. Keep raw logs without clearing the shared global logcat buffer.

5. Run the real-controller performance case:

   ```powershell
   adb -s JoyRaptor shell am start -n com.fadcam.beta/com.fadcam.joybrush.JoyBrushBenchActivity --es jb_bench playAll --el jb_hold_ms 600000
   ```

   `PlayAllBench` loads a disposable 1152 px canvas with three overlapping 256/512/1024 px clips at 25/50/12 fps; selects largest `clip-2`; warms 1500ms; drives **BoardRuntimeController**, not just a helper clock; measures five seconds. Log fields: driver, selectedClip, warmupMs, uiFps, renderFrames, busyP95Ms, budgetMs, errors. **No 60 fps measurement exists yet.** Verify approximately 60 UI fps, frame work within 16.67ms and no errors; investigate actual failures, do not lower the requirement. `jb_hold_ms` keeps the fixture open after measurement for UI checks (not `jb_bench_hold_ms`).
6. On that disposable fixture verify end chips/Play all/Stop, independent selection and repeated overlap taps, lock/direct drag/Undo, Combine/Undo, Flatten/Undo, export/open. GPU proof archives cover disk persistence and GPU reload; opening a generated archive through the Activity can add full application loader acceptance. Reopen generated GIFs with a decoder and record flags/frame durations. Capture real phone screenshots, inspect them, and save evidence under the review folder. Bench mode never restores or saves the owner's Working drawing.
7. Update review/todo/LANES with actual build/device evidence and remaining limitations. Commit scoped source and evidence coherently on `lead/anim-clips`, then integrate only after checking current schema/hot-file lanes. No push or PR was requested. Do not mark the spec complete until the device acceptance is actually satisfied.
8. Release only owned locks, restore coordinated watcher, and leave the phone safely at HOME. Report concrete results and paths.

## Agents and communication

The existing children `/root/combine_core`, `/root/exports`, `/root/playback_core` finished their assignments and are frozen; there are no pending edits. They may not persist into another chat. Their relevant findings are incorporated above. They did not perform builds/device actions/Git commits. Repository AGENTS permits subagent delegation. Do not message other user-owned chats without the owner's authorization.

The most recent user request is to produce this handoff. No further build, install, or phone input was started while preparing it.

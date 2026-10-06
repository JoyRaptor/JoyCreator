# Note 9 board acceptance — 2026-10-06

Source: isolated codex/region-routing checkpoint 1bbd3a8b. Main checkout watcher is running separately; its APK is not the integration APK.

## Plan

- [x] Owner Home/readiness gate; install integration arm64 APK in place and verify deployed APK identity.
- [ ] Open Joy Brush; inspect controls and GL/crash logs.
- [ ] Place Image, Animation and Sprite boards through actual UI, select and clear selection.
- [ ] Verify painting across animation boundary, blank/duplicate frames, saved navigation, playback and Undo/Redo.
- [ ] Verify layer preview scope, selected-board PNG, save and reopen.
- [ ] Fix confirmed phone blockers, rerun affected tests/build and device checks.
- [ ] Record exact observed results and remaining specification gaps for Claude.

## Limitations known before phone testing

Seamless tiling, Sprite cell swaps/sequences, onion skins, all-frame animation geometry and shared export choices are unfinished. A successful phone test of the connected controls does not complete those features. Automated pointer events cannot judge S Pen pressure/tilt feel.

## Preflight corrections and evidence

Read-only helper audit found two controller defects before deployment. Sprite controls use `sprite-cell-` IDs, while the controller's pen filter checked Animation's `cell-` prefix; the real Sprite grid therefore consumed stylus painting. The filter now uses the generated Sprite IDs. Missing/invalid thumbnail replies also immediately reissued themselves during refresh; failed frame IDs now remain pending for the current artwork epoch, and a content revision permits another request.

Native UI suite: 43 tests, zero failures/errors/skips, freshly written XML at 2026-10-06 08:29 Eastern. The Gradle daemon records BUILD SUCCESSFUL in 1m. The temporary PowerShell wrapper nevertheless returned an error because it treated Java's informational `JAVA_TOOL_OPTIONS` stderr as fatal; its native-command invocation now preserves the actual exit code and restores strict error handling afterward. No source or test failure was suppressed. Regression checks use actual generated Sprite cells dispatched through a parent with an underlying painting View, for both stylus and eraser; empty/partial/invalid and stale thumbnail replies are covered.

Main checkout's Git index is already locked by another process. Root did not remove that lock or alter its Git state; shared lane announcement was appended, and staging that shared file must wait. Integration work has its own clean Git index.

Preflight APK watcher assembly: BUILD SUCCESSFUL in 29s, 276 tasks. Arm64 APK SHA-256: `189B2CB5FF89D57D0204363B1CA27A65D9D10C7F380A2BCDF1A5784397991E52`. Primary watcher restored after owned build lock released. At that checkpoint installation was still pending owner Home/readiness; the later device session follows below.

## Device session after owner readiness

Owner confirmed Home. First installed APK's SHA-256 matched the packaged `base.apk` on the Note 9; package update time changed to October 6 08:38 Eastern. Opening Joy Brush through the lobby restored the disposable scratch document. Menu → Boards displayed Image, Animation and Sprite choices.

Phone found a placement teardown crash: removing the capture child while dispatching UP synchronously sent CANCEL, which removed the same child again and raised `IndexOutOfBoundsException` in `ViewGroup.removeFromArray`. The controller now finishes once, releases capture ownership, defers removal until dispatch ends and rejects stale completion after stop/new placement. Four parent-dispatched regression tests cover stylus/finger UP, CANCEL, duplicate terminal events and lifecycle/second-placement invalidation. Native suite then passed 47/0; watcher APK build passed in 23s. Fixed APK fingerprint `BE40B0F37ED01EF99EB8710A14297C1A0F4F708CBBA0481D9F88F099F1D76DB2` matched installed bytes.

That build successfully placed an Image at screen rectangle (240,1480,540,300) and an Animation at (240,800,540,300), using drag → New board → Apply. Selected rings, dimensions, side pill and Animation strip/peg were visible. A real SAF page PNG export saved `Board 1.png`; decoded dimensions 1080×2220, transparent corner alpha 0, 1,714,537 bytes. Board-scoped PNG remains to be checked separately.

Adding an Animation frame exposed a second device crash on October 6 09:14 Eastern: recursive `BoardChromeView.setSceneIdentity → stopInteractions → RollDragController.end → host.stripGap → refreshTransform → setSceneIdentity`. This is a synchronous callback ordering defect, not a frame-pixel proof. Do not mark animation frame controls as device-passed until the fix is rebuilt and rechecked.

An isolated Samsung framework NPE also occurred immediately after the first package replacement in `ActivityThread.updateLocaleListFromAppContext`, before app Activity code. Subsequent cold launches succeeded; no source cause or recurring startup fault has been established.

The frame identity fix publishes identity/input before synchronous cancellation callbacks and guards cancellation against reentry. Its production-controller regression then exposed an additional timing fault: a nested refresh sampled a newer timestamp before the outer pass reached the pen fade clock. Controller refreshes now finish one complete pass and coalesce nested requests into one later animation pass; stop clears queued refreshes. The monotonic pen fade contract was retained. Final native suite: 48 tests, zero failures/errors/skips; watcher app assembly passed in 23s, 276 tasks.

The persisted archive read after the Add crash contained the original page, the correctly sized new Image and Animation boards, and one Animation region/two physical cels on each of the three layers. This proves placement/save metadata, but not the failed Add transaction's subsequent save.

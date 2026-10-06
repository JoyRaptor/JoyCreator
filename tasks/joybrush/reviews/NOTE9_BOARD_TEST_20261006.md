# Note 9 board acceptance — 2026-10-06

Source: isolated codex/region-routing checkpoint 1bbd3a8b. Main checkout watcher is running separately; its APK is not the integration APK.

## Plan

- [ ] Owner Home/readiness gate; install integration arm64 APK in place and verify deployed APK identity.
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

Fixed APK watcher assembly: BUILD SUCCESSFUL in 29s, 276 tasks. Arm64 APK SHA-256: `189B2CB5FF89D57D0204363B1CA27A65D9D10C7F380A2BCDF1A5784397991E52`. Primary watcher restored after owned build lock released. Installation has not happened: owner authorized testing, but the phone remains on Joy Brush and the owner Home/readiness step is pending. Do not describe the new APK as phone-tested.

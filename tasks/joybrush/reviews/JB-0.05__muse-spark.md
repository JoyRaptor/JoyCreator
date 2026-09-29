# Adversarial review — JB-0.05 Android module + first screen

- Reviewer: muse-spark (cross-reviewer; builder was an unrelated subagent — different family).
- Task status: 🟧 Built. Commit reviewed: `047efa88`.
- Spec reviewed: `tasks/joybrush/specs/JB-0.05_android_module_and_input.md` (exact build edits 1–5, activity file list, verification 1–2, Do-not, Questions 1–6).
- §5b checks: diff touches only the 5 listed build files (exact hunks, verified below) + NEW `joybrush-android/` (build file + `JoyBrushActivity.kt`) + the ROADMAP row + spec Questions — inside the owner area; no other app file touched (notably not `FaditorEditorActivity`); nothing under `joybrush/`. Evidence available to me: spec step 1 (watcher `BUILD SUCCESSFUL`, pasted in spec + commit message). Step 2 (sandbox device + screenshot) is outstanding — no adb on this machine either — and T1 review is owed (app build files). Both are board-noted process items, not code findings. Reviewed against the COMMITTED tree (HEAD): the working tree additionally holds uncommitted JB-0.08b work in these files, which is out of scope here.
- Severity scale per §5b: BLOCKER / MAJOR / MINOR.

## Findings: none on the built code. Verified instead

1. **The 5 build edits are verbatim the spec's list** (diff-checked hunk by hunk): toml `androidLibrary` + `kotlinAndroid` 2.2.0; root aliases `apply false`; `include(":joybrush-android")` + `includeBuild("joybrush")`; app dependency; manifest activity with the spec's theme/configChanges and `exported="true"` carrying the JB-0.09-revert comment. The manifest comment names the revert explicitly, so the temporary export cannot be mistaken for intended.
2. **The activity implements the spec's file list and nothing else** (committed 257-line version): full-screen + keep-screen-on; `JbCanvasView` filling a FrameLayout; × button (40dp, 10%/12% whites per the spec's colours, `finish()`); smoothing SeekBar 0..100 default 35 → `canvas.smoothing`; Undo/Redo/Clear wired to `onHistoryChanged` enablement starting disabled; eraser toggle via `brush.copy(erase =)`; every button with `contentDescription` + tooltip; `onPause`/`onResume` forwarded. No input adapter, no renderer, no zoom/layers/saving — per "Do not".
3. **Spec Questions 1–6 read as answered in-code:** composite substitution proven by the watcher log (Q1, with the do-not-fix warning); android.jar-at-configuration coupling documented (Q2, later-spec lever); `kotlinOptions`-fallback form with the needed import (Q3); no library manifest needed (Q4); R8/keep-rules carried to JB-0.08 explicitly (Q5 — still open there); unconditional include cost noted (Q6).

## Explicitly not filed (scope, already owned elsewhere)
- Close (×) finishes with no dirty check: spec-compliant (saving did not exist in this task's scope), covered by JB-0.08b autosave when it lands.
- `OVERLAY_FILL`/`OVERLAY_RING`/`Color.WHITE` literals vs D.01's later "no hex literal" rule: the 0.05 spec *mandated* those values; the conflict is inter-spec and filed under D.01, not here.
- Device feel/screenshot + T1 review: owed per the board; no adb or reviewer signature available in this session.

## Recommendation
No send-back. No BLOCKER, MAJOR, or MINOR open. The remaining items (device check, T1 review) are sign-off, not code.

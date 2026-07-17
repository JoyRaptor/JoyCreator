# NEXT SESSION PROMPT (written 2026-07-17 afternoon by Fable 5)

You are continuing UI work on FadCam/Joy Creator at `C:\+Projects\Screenrecorder\FadCam`
(branch `joy-creator`). The ENTIRE 2026-07-17 UI queue is BUILT and INSTALLED on the sandbox
Note 9 (SANDBOX_SERIAL, re-authorized): Slice 2 gap-insertion, D2 `<♦>` widget + C7 honesty,
C4 row keyframe parity, D2a curves + ease picker, C8 stragglers. HEAD = `2fbc874`.

READ FIRST, in order:
1. `tasks/handoff.md` — the ♦ 2026-07-17 afternoon block (top).
2. `tasks/FEEDBACK_20260717_ui_dialogs_and_keyframes.md` — EXECUTION ORDER + STATUS block: items
   1–4 all ✅ with per-item DEVICE-VERIFY lists. STILL-OPEN SPEC DEBT list at the bottom is the
   menu for new work.
3. `git log --oneline -10` + `git status` — commit or triage any uncommitted work before new
   lanes (icons/PSD stay uncommitted, not ours).

WHAT TO DO:
- If JoyRaptor has hand-test feedback on the just-installed build, that feedback is the queue.
- Otherwise pick from STILL-OPEN SPEC DEBT (my suggested order):
  1. ObjectMenuSheet §2 Prop adapters for audio / PiP / visualizer (drawer exists, types
     unwired — audio volume/pan, PiP transform/opacity, viz props; reuse overlayMenuProp's
     pattern incl. armed/easeGet/easeSet, and the C6 retarget will start working for them).
  2. Dual-stream Phase 4 (`linkedClipId` mirrored edits — editor lane; Phases 0–3 landed).
  3. Export-side GL transition A/B proof (use the A/B frame-diff method in memory:
     absolute-geometry diffs, not symmetric).
  4. Visualizer-studio Phase 4 (live-recording viz, perf-gated).

HOUSE RULES (hard-won):
- PowerShell builds: `$env:TEMP='C:\Users\JoyRaptor\gtmp'; $env:TMP=$env:TEMP` BEFORE gradlew.
  Compile check = `.\gradlew.bat compileDefaultDebugJavaWithJavac`.
- **NEVER `--rerun-tasks`** — corrupts media3-patched inter-module jars (recover with a normal
  incremental build, or clean lib-extractor/lib-exoplayer and rebuild).
- adb: `C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe`, sandbox serial
  SANDBOX_SERIAL (authorized as of 07-17 13:12; full APK installed then).
- Commit per feature, `faditor(scope): …` style, `git commit -F <file>` for multi-line messages.
- Glob tool broken on this path — Grep/ls.
- Gesture FEEL can't be adb-verified — compile-green + queue hand-tests in the spec status block.
- Don't touch: icon PNGs/.psd, `tools/jvm-harness/out2/`, whisper.cpp, `media3-patched/`
  (load-bearing source substitution).
- Orchestration pattern that worked well today: scope the lane yourself with targeted greps,
  write a precise brief with file:line anchors + house rules, delegate to an Opus subagent
  (general-purpose), review the full diff line-by-line, compile, commit yourself. Sequential
  lanes when they share FaditorEditorActivity.
- Update the spec's STATUS block as you land things; prepend a handoff.md block + rewrite this
  file at session end.

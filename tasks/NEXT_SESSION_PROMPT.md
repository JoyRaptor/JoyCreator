# NEXT SESSION PROMPT (written 2026-07-17 evening by Opus 4.8)

You are continuing UI/editor work on FadCam/Joy Creator at `C:\+Projects\Screenrecorder\FadCam`
(branch `joy-creator`). HEAD = `6bf5066`. Two lanes landed this session, both compile-green:
`3fed299` (ObjectMenuSheet §2 audio/PiP/viz adapters — installed on SM-N986U) and `6bf5066`
(dual-stream Phase 4 FOUNDATION — linkedClipId schema + Timeline link helpers, dormant/safe).

READ FIRST, in order:
1. `tasks/handoff.md` — the ♦ 2026-07-17 evening block (top).
2. `tasks/FEEDBACK_20260717_ui_dialogs_and_keyframes.md` — STILL-OPEN SPEC DEBT (bottom); the
   ObjectMenuSheet audio/PiP/viz item is now ✅ with its DEVICE-VERIFY list.
3. `tasks/feature-dual-stream-recording-spec.md` — the updated Status block spells out exactly
   what Phase 4 foundation landed and the two REMAINING parts (link-creation UI + mirrored ops).
4. `tasks/LANE3_gl_transition_ab_hypothesis.md` — the export GL-transition A/B hypothesis + recipe.
5. `git log --oneline -6` + `git status` (icons/PSD/out2 stay uncommitted, not ours).

DEVICE SITUATION (check first): the sandbox Note 9 `SANDBOX_SERIAL` was DETACHED this session;
only the main phone `REAL_SERIAL` (SM-N986U) was online, and it's a work-profile device (user
150) where `run-as`/`pm list` are BLOCKED — so NO project.json injection / export-A/B there.
Confirm `adb devices`; the export-proof and hand-tests want the Note 9 back.

WHAT TO DO:
- If JoyRaptor has hand-test feedback on the installed build (the audio/PiP/viz drawer), that's the queue.
- Otherwise pick (my suggested order):
  1. **Dual-stream Phase 4 reachable ops** (build on `6bf5066`). Decide the link-creation entry
     point WITH JoyRaptor (asset-browser pair-detection vs a manual "Link/Unlink" action — see spec
     Status (a)). Then mirrored delete/trim/split + unlink (spec Status (b): anchors
     `deleteSelectedSegment`~21477, `onTrimChanged`~1373, `Timeline.splitAt`:308; ONE undo per
     pair; RE-LINK split children). These touch destructive paths — device-verify on a REAL
     recorded pair before shipping.
  2. **Export-side GL transition A/B** (Lane 3) — needs the Note 9. Follow
     `tasks/LANE3_gl_transition_ab_hypothesis.md`: inject a project with TWO mismatched-aspect
     clips + one GL transition, export, measure the incoming-clip geometry mid-transition vs its
     single-clip presentation. Fix `GlTransitionFrameOverlay.fitRect` (fit to CANVAS aspect via
     the `canvasDims` it already receives) only if the A/B confirms the mismatch.
  3. **Visualizer-studio Phase 4** (live-recording viz, perf-gated).

HOUSE RULES (hard-won):
- PowerShell builds: `$env:TEMP='C:\Users\JoyRaptor\gtmp'; $env:TMP=$env:TEMP` BEFORE gradlew.
  Compile check = `.\gradlew.bat compileDefaultDebugJavaWithJavac` (a build watcher also compiles
  on save; `build.log` is UTF-16 — read via PowerShell `Select-String`).
- **NEVER `--rerun-tasks`** — corrupts media3-patched inter-module jars.
- adb: `C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe`. If a device is
  `offline`, `adb reconnect offline` recovered it this session.
- Commit per feature, `faditor(scope): …`, `git commit -F <file>` for multi-line messages.
- Glob tool broken on this path — Grep/ls.
- JVM harness (`tools/jvm-harness`) can only test PURE-model classes — `Clip`/`Timeline` pull
  `android.net.Uri`, off the harness classpath, so link semantics were verified by review+compile,
  not a harness test. Gesture/compositing FEEL can't be adb-verified — compile-green + queue
  hand-tests in the spec status block.
- Don't touch: icon PNGs/.psd, `tools/jvm-harness/out2/`, whisper.cpp, `media3-patched/`.
- Orchestration: scoping the lane yourself then delegating the mechanical build to an Opus
  subagent worked, BUT subagents stalled twice this session on mid-stream API errors — keep the
  brief self-contained and be ready to finish by hand (as happened for the audio/PiP/viz lane).
- Update the spec STATUS block as you land things; prepend a handoff.md block + rewrite this file
  at session end.

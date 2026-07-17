# Paste this to a fresh Fable 5 agent (working dir C:\+Projects\Screenrecorder)

You are continuing an in-flight UI build session on the FadCam/Joy Creator Android video editor.
The repo is `C:\+Projects\Screenrecorder\FadCam` (branch `joy-creator`). Work was mid-stream when
the previous session ran out of usage.

READ FIRST, in this order:
1. `FadCam/tasks/handoff.md` — TOP block only (the 🎛️ 2026-07-17 morning block + whatever block
   sits above it, if the previous agent added one). It says exactly what landed and what's next.
2. `FadCam/tasks/FEEDBACK_20260717_ui_dialogs_and_keyframes.md` — the binding spec for this UI
   push: decisions D1–D4, curve set D2a, spec corrections C1–C9, and the EXECUTION ORDER + STATUS
   block that tracks what's done. Update its status block as you land things.
3. `FadCam/tasks/FEEDBACK_20260703_dragux_v3.md` — the SLICE 2 BLUEPRINT (gap-insertion, BINDING)
   if Slice 2 isn't already marked done in the spec above.
4. `git log --oneline -15` and `git status` — if there is UNCOMMITTED work, review it against the
   spec, finish or commit it coherently before starting anything new.

THE QUEUE (skip anything the status block already marks ✅):
1. dragux_v3 SLICE 2: kill the pinned purple "+ Drop here for new layer" zone AND the cross-band
   arm; replace with gap-insertion lines (every gap between floating rows + above top + below
   bottom = new-layer target; one accent line; release creates the track AT that index, higher
   row = higher z). Include the C5 riders (sticky hover hysteresis, disarm bookend excursion on
   gap entry, insertion index → z renumber in ONE undo step) and C8 stragglers (A1 edge auto-pan,
   outline color audit) if time allows.
2. Keyframe visibility batch: program-wide `<♦️>` widget per D2 (chevrons = prev/next key, hollow/
   solid diamond, ×-in-diamond removes the key under the playhead, long-press = ease picker slot),
   C7 arming honesty (inline "Static — tap ♦ to animate" hint, NO auto-keying), C4 row display
   parity (consolidated keyframe diamonds on overlay/sprite rows, horizontal diamond drag = move
   key in time, opacity-only rubber-band envelope with a scrim so it reads over §2 thumbnails).
3. D2a ease curves: extend `keyframe/Easing.java` with EASE_IN_EXPO, EASE_OUT_EXPO, ANTICIPATE,
   OVERSHOOT, SPRING_SOFT/SPRING/SPRING_BOUNCY (damped sine, ζ≈0.75/0.5/0.3), BOUNCE (Penner),
   STAIRS_4 — every apply() must hit exactly 0 at t=0 and 1 at t=1 (springs: residual-ramp
   normalization). Then the picker: popover anchored at the diamond, grid of rounded tiles,
   thumbnails RENDERED FROM apply() itself, first tile ⊘ = linear/none, NO custom-graph tile,
   selected tile = GREEN ring+tint 0xFF4CAF50 (KineMaster's layout, green not red — JoyRaptor).

HOUSE RULES (hard-won, do not rediscover):
- Builds: PowerShell, `$env:TEMP='C:\Users\JoyRaptor\gtmp'; $env:TMP=$env:TEMP` BEFORE any
  `.\gradlew.bat` call (AppData temp breaks gradle loopback). Compile check =
  `.\gradlew.bat compileDefaultDebugJavaWithJavac`, grep for BUILD SUCCESSFUL.
- adb is NOT on PATH: `C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe`.
  Sandbox Note 9 = serial SANDBOX_SERIAL; it went `unauthorized` — JoyRaptor must accept the USB
  prompt before any install; the build watcher auto-installs on its next green build if running
  (check build.log mtime freshness before trusting its tail — stale SUCCESSFUL reads like green).
- Commit per feature with the repo's style (`faditor(ux): …`), multi-line messages via
  `git commit -F <file>` (quotes break -m on this shell). Update the spec's status block +
  prepend a handoff.md block at session end.
- Device gesture FEEL cannot be verified via adb (no real double-tap injection) — build
  compile-green, queue the hand-test list for JoyRaptor in the spec status block.
- Glob tool is broken on this path — use Grep/ls/find instead.
- Don't touch: the icon PNGs / .psd (not ours), `tools/jvm-harness/out2/`, whisper.cpp, media3
  source substitution (`media3-patched/` is load-bearing).

Work autonomously, commit as you go, and when YOUR usage runs out, update
`FadCam/tasks/NEXT_SESSION_PROMPT.md` + handoff.md so the next agent can do exactly what you
just did.

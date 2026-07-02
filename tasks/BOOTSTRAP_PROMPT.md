# Autonomous Agent — Bootstrap Prompt

Copy and paste EVERYTHING below into a fresh Opencode session.

---

```
You are continuing autonomous development on FadCam/Faditor (Android video editor,
C:\+Projects\Screenrecorder\FadCam, package com.fadcam.beta, minSdk 24, targetSdk 35).

Always-green: never end a chunk with the tree non-compiling. Revert rather than break.
Never commit. Update handoff docs after every verified chunk.

READ FIRST (in order):
1. C:\+Projects\Screenrecorder\FadCam\tasks\road_map.md — the full phased roadmap with touch-zones and priorities
2. C:\+Projects\Screenrecorder\FadCam\tasks\HANDOFF.md — current state of what's done/undone
3. C:\+Projects\Screenrecorder\FadCam\tasks\lessons.md — rules learned the hard way
4. C:\+Projects\Screenrecorder\FadCam\AGENTS.md — workflow conventions

HOW THIS LOOP WORKS:
You are the ORCHESTRATOR. You manage subagents to do the actual work. Each cycle:
1. Read road_map.md — find the highest-priority item with status ❌ NOT STARTED
2. Check its Track — ensure no OTHER subagent is currently working in the same Track (file touch-zone)
3. Launch a subagent (use the Task tool with subagent_type="general") with:
   - Exactly which files to touch (from road_map.md touch-zones)
   - Clear build/verify instructions: `cd C:\+Projects\Screenrecorder\FadCam; .\gradlew.bat assembleDefaultDebug --no-daemon`
   - The instruction: "Report back BUILD SUCCESSFUL or the exact compile error"
   - NEVER let two subagents edit overlapping files simultaneously
4. While waiting, you may launch subagents in DIFFERENT Tracks (parallel safe)
5. When a subagent finishes successfully:
   a. Append results to tasks/HANDOFF.md — update "What's DONE" and note any new bugs
   b. Update tasks/road_map.md — mark that item as ✅ DONE with date
6. If a subagent reports BUILD FAILED: fix it yourself or have the subagent fix it. Never move on broken.
7. LOOP — pick the next item. Never wait for user input. Keep going.

DEVICE VERIFICATION:
- Build: .\gradlew.bat assembleDefaultDebug --no-daemon (add GRADLE_OPTS='-Xmx4096m -Dfile.encoding=UTF-8' for dexing)
- Install: & "C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s REAL_SERIAL install -r "C:\+Projects\Screenrecorder\FadCam\app\build\outputs\apk\default\debug\app-default-arm64-v8a-debug.apk"
- Screenshot: adb exec-out screencap -p > "C:\Users\JoyRaptor\AppData\Local\Temp\s.png" then read that path
- adb is NOT on PATH — prefix each call with the full path above

ENVIRONMENT GOTCHAS:
- New dialogs MUST use bare `new MaterialAlertDialogBuilder(this)` (no theme arg) or they crash
- tree is ALWAYS-GREEN — if something won't compile, revert that file and re-plan
- Gradle daemon may OOM on dexing — retry with GRADLE_OPTS='-Xmx4096m -Dfile.encoding=UTF-8'
- The watcher in PowerShell rebuilds on save; check build with `tr -d '\000' < build.log | tail -n 20` (UTF-16)

START: Pick the first ❌ NOT STARTED item from road_map.md Phase 0. Go.
```

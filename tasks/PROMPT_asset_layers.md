# Kickoff prompt — paste this to the implementing AI

You're working on **FadCam/Faditor**, an Android video editor at
`C:\+Projects\Screenrecorder\FadCam`. Your job is to finish the **Asset Browser** and then build
**Layers (multi-track)**, following a detailed execution plan.

## Read these first, in order
1. `tasks/PLAN_asset_browser_and_layers_EXECUTION.md` — YOUR PLAN. Work through milestones **M0 → M11
   in order**. Each has explicit, device-verifiable acceptance criteria. Do not start a milestone until
   the previous one is verified on the device.
2. `tasks/handoff.md` — project overview + most-recent state (export now works end-to-end).
3. `tasks/DEVICE_CONTROL_RUNBOOK.md` — how to build/install/screenshot/tap/log the phone. Note §7b
   (read live `project.json` via `run-as`), §7d (capture export failures), §7f (you CANNOT run Gradle —
   the user runs a watcher).
4. `tasks/PLAN_asset_browser_v2_layers.md` — the design + user intent behind everything (the "why").
5. `tasks/DIAG_20260626.md` — recent export fixes + the `ffmpeg`/frame verification techniques you'll reuse.

## Hard rules
- **Always-green:** never leave the tree non-compiling. If you can't make it build, REVERT and report.
- **You can't run Gradle** (sandbox blocks it). The user runs a continuous-build watcher
  (`watch-build.ps1`) that rebuilds + reinstalls on save and logs to `build.log` (UTF-16:
  `tr -d '\000' < build.log | tail -40`). Edit source, then wait for the FINAL `BUILD SUCCESSFUL` before
  testing. If `build.log` is stale, ask me to start the watcher.
- **Verify on the device, don't assume.** Device serial `REAL_SERIAL`, package `com.fadcam.beta`. adb at
  `C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe` (use the **PowerShell** tool with
  `-s REAL_SERIAL`; the Bash tool's adb is flaky). Screenshot via `screencap` + `adb pull` (PowerShell
  `>` corrupts PNGs). For exports, pull the MP4 and check with `ffmpeg` (audio RMS) + extracted frames —
  hold layers to "export matches preview."
- **Ship in small chunks** and update `tasks/road_map.md` + `tasks/handoff.md` status after each verified
  milestone. **No git commits.**
- **Data safety:** anything that copies/moves/deletes user media is opt-in, reviewable, COPY-not-move by
  default. Never delete originals without an explicit confirmed checkbox.
- **When a step is ambiguous or needs a product decision, STOP and ask me** — do not invent architecture
  or policy.

## Start now
Begin with **M0 (diagnose, no code changes):** read the docs, confirm the watcher + device, pull the
active `project.json`, exercise the asset browser on-device, and write `tasks/DIAG_assetbrowser_<date>.md`
listing the concrete current bugs. Then report your M0 findings and your plan for M1 before writing any
code.

# BOOTSTRAP — Layers/Timeline UX overhaul BUILD session (2026-07-06)

Paste the block below to start the build session.

---

Continue the autonomous FadCam/Joy Creator mission (Android video editor at
C:\+Projects\Screenrecorder\FadCam, branch joy-creator, package com.fadcam.beta).
I (JoyRaptor) can't code — you're my hands. Work autonomously; I may step away.

WHAT THIS SESSION DOES: BUILD the Layers/Timeline UX overhaul. The DESIGN is DONE and locked — this
session implements it, always-green, one green milestone per commit.

READ FIRST (authoritative, in order):
  1. tasks/PLAN_GESTURE_CONTRACT_FINAL_20260706.md — THE authoritative design contract (co-designed with
     JoyRaptor). Gestures, object menu + peek/sandwich rendering, layers-as-substrate, nameless on-demand
     lanes + ripple + AI consolidation, attach/detach + piggyback/stratified overlays with source
     semantics, resizable/fullscreen timeline, multi-select marquee, object linking, sprite lane
     thumbnails. Build slices G1–G9 are at the bottom.
  2. tasks/PLAN_LAYERS_UX_EXECUTION.md — the ALWAYS-GREEN slice sequence A–G with confirmed code facts
     (the two-renderer root cause + exact file/line anchors). Slice G now defers to the contract above.
  3. tasks/FEEDBACK_20260706_layers_ux.md — JoyRaptor's original gripes, triaged (the WHY behind the slices).
  4. tasks/LANES.md (file-lock board + DEVICE token) + tasks/DEVICE_CONTROL_RUNBOOK.md (build/adb/ffmpeg).
     tasks/PLAN_LAYER_GESTURE_CONTRACT.md is prior gesture work (badges principle, swipe=scrub, bookend
     snap) that STILL HOLDS and G1 must reconcile with, not discard.

OPERATING RULES (non-negotiable):
  - NEVER run gradle/gradlew. The user's watcher is the ONE builder — save files, then poll build.log
    (UTF-16: `tr -d '\000' < build.log | tail -40`) for a fresh BUILD SUCCESSFUL. Confirm watcher is
    live + green before starting.
  - Always-green: never leave the tree non-compiling — revert rather than break. Each slice compiles +
    device-verifies before the next.
  - Local commit per GREEN milestone, one task each. NEVER push/rebase/reset/stash.
    End commit messages with: Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  - Device is single-owner (LANES DEVICE token). Phone: SM-N960U (<note9-serial>), plugged in.
    adb: C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe (not on PATH).
  - Drag/gesture tests: ONE scripted attempt, then hand me a numbered hand-test checklist. Tap/state/
    JSON verification stays agent-side. At most ONE sonnet subagent (low/med), sequential. Gesture state
    machines are STRONG-MODEL (Opus-tier) work — G1 especially.
  - Claim your lane + the DEVICE token before device work; release both when done. Update tracking docs
    after each verified milestone.

VERIFIED STATE (don't redo): Sprite Build-1 live + device-verified. T8 (sprite-per-lane) DONE = b55b1cd.
The layers overhaul is the current priority track (before sprite fast-follows T3/T4/T6/T7, which add more
layer items).

BUILD ORDER:
  1. Slice A — make captions & visualizers Track-model citizens (TrackKind.CAPTION/VISUALIZER, payload
     sockets, banding getters). No visual change. START HERE — it's the prerequisite the rest hangs on.
  2. Slices B → C — render caption/viz rows in LayerRowRenderer, then DELETE the old drawLayers row
     system (kills the double-render). This is the renderer consolidation — the root fix.
  3. Slice D — header hit zones (now minimal: solo + twirl only, per contract §4.5) + caption-chooser
     auto-hide.
  4. Slice E — vertical re-layout (overlays+CC on top, MASTER centered/larger, AUDIO below).
  5. Slice F — no-overlap all types + move-between-layers + lane consolidation resolver.
  6. Slices G1–G9 (per contract): gesture state machine, peek/sandwich menu, keyframe diamonds+ribbon,
     preview handles, attach/detach+piggyback/stratified overlays, resizable+PiP timeline, coach-marks,
     multi-select marquee, object linking. G6 (resizable timeline) is independent and can land early if
     JoyRaptor wants the space win sooner.

DESIGN NOTES CARRIED IN (from the contract — don't re-litigate, but honor):
  - Layers are nameless substrate; controls (eye/lock) are PER-OBJECT; only solo is per-layer.
  - Two [JOYRAPTOR-CAN-FLIP] defaults: stratified overlays stay time-tethered; keyframes auto-record when
    armed. Small build-time confirms noted in the contract (batch-rename numbering; "freeze" is retired
    into lock; detach behavior is a per-object prompt + permanent hold-menu setting).
  - Sprite lane thumbnails: decode-once/cache/cull/LOD so cost tracks visible segments, not sprite size.

Start by reading the docs, confirming the watcher is live + green and the tree is clean, then begin
Slice A. Coordinate relink relocation (contract §5.6 / G9) with existing T3 when you get there.

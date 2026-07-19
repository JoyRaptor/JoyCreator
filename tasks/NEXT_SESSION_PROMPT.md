# NEXT SESSION PROMPT (rewritten 2026-07-19 02:00 by Fable 5, pre-limit)

You are resuming autonomous spec-finishing on FadCam/Joy Creator at
`C:\+Projects\Screenrecorder\FadCam` (branch `joy-creator`, HEAD `14a6c47`). JoyRaptor's standing
directive: work autonomously through the unfinished specs, document as you go, and when the
session limit hits, schedule a one-shot CronCreate wakeup ~5h out that points back at this file.

READ FIRST: tasks/handoff.md top block (⏰ 2026-07-19 02:00) — it has the full state.

IMMEDIATE ERRANDS, IN ORDER:
1. UNCOMMITTED TREE: GL-transitions export-parity work is sitting uncommitted (GlTransition* +
   FaditorEditorActivity + ProjectStorage + UndoManager + LayerRowRenderer + FaditorPlayerManager;
   context in tasks/GL_TRANSITIONS_HANDOFF_20260718.md). Compile, review the diff, commit if sound.
2. Relaunch visualizer-studio Phase 4 (live-recording viz, tasks/feature-visualizer-studio-spec.md
   §Phase 4 + RECORDING_HANDOFF.md; Tier-1 only, tap the encoder PCM buffer, reuse the
   webcam-bubble compositing pass, perf validation is the done-when).
3. Relaunch H.264 baseline patch (tasks/H264_BASELINE_PROFILE_FINDING_20260714.md; NOTE from the
   dead agent: validation resets profile→NO_VALUE when level absent — thread the ORIGINAL request).
4. LANE_BADGES §4.5 eye/lock migration; audio-band marquee; then device-verify queue.
5. SKIP dual-stream P4 (blocked on JoyRaptor's link-UI decision).

HOUSE RULES (unchanged): TEMP/TMP→C:\Users\JoyRaptor\gtmp before gradlew; NEVER --rerun-tasks;
adb at C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe; Glob broken — Grep/ls;
git commit -F <file>; commit per feature `faditor(scope): ...`; don't touch icons/PSD, out2/,
whisper.cpp/, media3-patched/ (EXCEPT the H.264 lane which patches media3-patched deliberately —
that lane alone). Opus subagents for mechanical builds; review line-by-line before committing;
briefs must be self-contained (agents die on API errors — be ready to finish by hand).

DEVICE: Note 9 SANDBOX_SERIAL unlocked and authorized as of last check; Layers-UI build
installed. JoyRaptor hand-test owed: viz Layers drawer (chips/props/reorder), new P3/P4 presets in a
real project, export A/B of a customized stack.

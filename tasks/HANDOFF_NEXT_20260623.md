# FadCam/Faditor — Handoff prompt for the next session (2026-06-23)

Paste everything below into a fresh session to continue. Read `tasks/HANDOFF_NEXT_20260622.md` first (it has
the full DONE list + per-feature plans), then `tasks/HANDOFF.md` top summary, then `tasks/FEEDBACK_20260621.md`.

---

You are continuing autonomous development on **FadCam/Faditor** (Android video editor,
`C:\+Projects\Screenrecorder\FadCam`, package `com.fadcam.beta`). Always-green (never leave the tree
non-compiling), **never commit**, no code comments unless idiomatic, update docs after each chunk, then start
the next. A PowerShell watcher rebuilds+installs on save; check the build with
`tr -d '\000' < build.log | tail -n 20` (the log is UTF-16). The watcher rebuilds MID-EDIT, so intermediate
`BUILD FAILED` lines are normal — only trust the FINAL tail being `BUILD SUCCESSFUL` before you device-verify.

## Environment gotchas (important — cost me time)
- **adb is not on PATH.** It lives at `C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe`.
  In the Bash tool, prefix each call: `export PATH="$PATH:/c/Users/JoyRaptor/AppData/Local/Android/Sdk/platform-tools"; adb ...`
- **Screenshots:** `adb shell screencap -p /sdcard/x.png` was erroring (printed usage). Use
  `adb exec-out screencap -p > "/c/Users/JoyRaptor/AppData/Local/Temp/s.png"` and Read that Windows path
  (the Read tool needs a Windows path, not `/tmp`).
- `uiautomator dump` returns null root → navigate by screenshot coordinates (PNG is full device res 1440×3088).

## Device
- Work phone **<note20-serial>** (SM-N986U, 1440×3088) — the user's real projects (tiger "primed_she_believed";
  "In the day ye eat thereof" Eden project; etc.). Nav: tap **Faditor** bottom-nav icon ~(840,2915) → project
  row ~(720,1150) → play/pause ~(720,2050). The bottom **tool row** (~y2950) scrolls horizontally; swipe LEFT
  there (NOT at y2560 — that scrubs the timeline) to reach Split/Delete/Duplicate/Add/**Text**/Visualizer and
  further for Transcript/Transitions/Volume.
- Backup phone **<note9-serial>** (SM-N960U, 1440×2960) — dedicated test project (3 captioned scenes,
  visualizer, 2 audio clips, volume keyframes). Nav: Faditor ~(835,2755); project ~(720,1170); Volume ~(375,2895).
- Only one is usually connected; the watcher installs to whichever is. Gotchas: editor "Welcome Back" Continue
  is broken (tap **New Project** to clear); USB offline/unauthorized → `adb kill-server && adb start-server`;
  landscape → `adb shell settings put system user_rotation 0`; asleep → `input keyevent KEYCODE_WAKEUP` then
  swipe up. Can't hear audio over adb → verify audio via `dumpsys audio | grep "/<pid>"` player states
  (started/paused). **New editor dialogs MUST use bare `new MaterialAlertDialogBuilder(this)` (no theme arg)**
  or they crash with InflateException.

## DONE recently (compile-green; device-verified unless noted) — don't redo
- Music-runs-on-after-video-end BUG fixed (`syncAudioPlayerWithPlayhead` gates on real playback + 400ms debounce).
- Transcript word-edit timestamp box + ±1-frame carets (`showWordEditDialog`).
- LAYERS visible slices in `EditorTimelineView` (multi-lane audio rows, VISUALIZER + CAPTION rows, tappable →
  open/select, long-press → delete, white selection-highlight ring).
- **OVERLAY opacity/transparency (static + keyframed fades) — BUILT + installed, NOT yet device-verified.**
  `TextOverlayItem.opacity` field + `animatedOpacity` fallback + `addKeyframeAt` captures it; `ProjectStorage`
  round-trips it; an OPACITY slider in the overlay editor (`buildOverlayAnimationControls`) sets static opacity
  when unarmed, drops an OPACITY keyframe at the playhead when armed. Preview (`TextOverlayLayer:146`) + export
  (`ExportManager:1665`) already consume `animatedOpacity`. See `tasks/HANDOFF_NEXT_20260622.md` DONE block.

## TODO (priority order — build each in compile-green chunks, device-verify, update docs)

1. **DEVICE-VERIFY overlay opacity (finish what's in flight).** Open a text/image overlay's editor, drag the
   new OPACITY slider, confirm the overlay visibly fades in the preview. Then ARM (ADD KEYFRAME), move the
   playhead, change opacity → confirm a fade animates during playback. Export a short clip and confirm the fade
   is baked. If anything's off, fix in `buildOverlayAnimationControls` / `TextOverlayLayer` / `ExportManager`.
   OPTIONAL follow-on the user asked about ("assets"): **video-CLIP** opacity keyframes are still NOT built —
   add a `{timeMs,opacity}` list on `Clip` (mirror `AudioClip.VolumeKeyframe`/`gainAtClipMs`), storage
   round-trip, a GlEffect alpha pass in `ExportManager`, a preview alpha path, plus an envelope + arm/slider UI.

2. **Transcript word "scrub-to-retime" strip with ACCELERATION + real-time timeline.** User: drag left/right on
   a strip to move the selected word's start; a SMALL drag nudges a little, a LARGE drag (finger far from
   center) moves it FASTER (ballpark fast, then dial in fine), watching the word move in the timeline transcript
   row IN REAL TIME. KEY: make the word-edit UI **non-modal** (a top drawer like `volume_drawer`, gravity top)
   so the bottom timeline transcript row stays visible while scrubbing — the current `showWordEditDialog`
   AlertDialog covers the center. Custom horizontal `WordScrubView`: velocity = `sign(dx)*(|dx|/unit)^~1.6`
   (super-linear → acceleration); each frame add `velocity*dtFrameMs` to the word's start via the existing
   live-apply (`TranscriptPanelView.setWordStart` + `syncTimelineTranscript` + invalidate + caption rebind);
   snap back to center on release. Reuse the `applyAbs`/absolute-time logic already in `showWordEditDialog`, and
   keep the tappable exact-time box + ◄ ► ±1-frame carets in the same panel.

3. **AI `retime_words` anchor-and-interpolate tool** (so "set 'in' at 6s, space the/day evenly to 'ye'" works).
   WHY it failed before: the only transcript-timing AI tool is `correct_transcript` (find/replace text,
   redistributes time PROPORTIONALLY) — there is NO tool to set/anchor specific word TIMES, so the model asked
   for exact timestamps the user couldn't give. Add `retime_words` in `AIToolExecutor` (register in the tool
   list + dispatch). Args `{clipId, anchors:[{word|index, timeMs}], distribute:"even"|"by-length"}`: set the
   anchor words' starts, interpolate the in-between words between consecutive anchors (and a trailing anchor
   like an existing well-timed word, e.g. "ye"). Mirror `correct_transcript`'s clip/transcript lookup + save +
   `AIChatState.signalModified`, and document it in the system-prompt tool docs so the model reaches for it.

4. **Frame-level AUDIO scrubbing.** Currently scrubbing seeks the video frame; audio isn't positioned to frame
   level. On scrub (drag in `onPlayheadSeeked`) and at minimum on scrub-finish, seek the audio `MediaPlayer`(s)
   to the playhead so play-from-here is sample-accurate (`MediaPlayer.seekTo` with `SEEK_CLOSEST` on API 26+;
   minSdk here is 24 so guard the SEEK_CLOSEST overload).

Bigger backlog after that: GL transition REAL previews; Track-model LAYERS foundation (tiny safe steps).

## Open notes / gotchas
- **Audio DUCKING is HOLLOW**: `Clip.duckAmount` is stored + settable (volume sheet + AI `set_clip_duck`) but
  NEVER applied in export/playback — do NOT expose duck UI until a real voice-activity duck processor exists.
- `TranscriptWord` is immutable (final text/start/end) — "moving" a word = replace it in `transcript.words`
  (that's what `TranscriptPanelView.setWordStart` does).
- The audio-tail feature (music continuing past video) does NOT reliably engage on video-end for these projects
  (the end path takes the "fully stop" branch); the music-bug fix makes it stop cleanly, which is correct. If
  the user wants outro-music-over-final-frame, revisit `getTimelineEndMs()` vs `totalEffectiveMs()`.

## Two user questions already answered (for the record)
- "Why didn't the AI just retime the words?" → no anchor-based retime tool existed (only proportional
  find/replace). TODO #3 (`retime_words`) fixes it.
- "Can we scrub audio to frame level too?" → not currently; TODO #4 — feasible via `MediaPlayer.seekTo`
  (SEEK_CLOSEST for frame accuracy).

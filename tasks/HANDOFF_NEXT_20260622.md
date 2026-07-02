# FadCam/Faditor — Handoff prompt for the next session (2026-06-22)

Paste everything below into a fresh session to continue. Read `tasks/HANDOFF.md` (top blocks) and
`tasks/FEEDBACK_20260621.md` first for full status; this file is the focused to-do + context.

---

You are continuing autonomous development on **FadCam/Faditor** (Android video editor,
`C:\+Projects\Screenrecorder\FadCam`, package `com.fadcam.beta`). Always-green, never commit, update docs
after each chunk, then start the next. A PowerShell watcher rebuilds+installs on save; check
`tr -d '\000' < build.log | tail -n 40` (UTF-16). The watcher rebuilds MID-EDIT, so intermediate
`BUILD FAILED` lines are normal — confirm the FINAL tail is `BUILD SUCCESSFUL` before verifying.

## Devices
- Work phone **REAL_SERIAL** (SM-N986U, 1440×3088) — has the user's real projects ("In the day ye eat
  thereof" Eden project; tiger "primed_she_believed"; etc.). Nav: Faditor nav icon ~(840,2915); project row
  ~(720,1160); play/pause ~(720,2050); toolbar scroll horizontally to reach Transcript/Transitions/Visualizer.
- Backup phone **SANDBOX_SERIAL** (SM-N960U, 1440×2960) — dedicated test project (3 captioned scenes, a
  visualizer, 2 audio clips, volume keyframes). Nav: Faditor ~(835,2755); project ~(720,1170); Volume ~(375,2895).
- Only one is usually connected; the watcher installs to whichever is. `uiautomator dump` returns null root on
  both → navigate by screenshot coords (PNG is full device res). Gotchas: editor "Welcome Back" Continue is
  broken (tap **New Project** to clear); USB offline/unauthorized → `adb kill-server && adb start-server`;
  landscape → `adb shell settings put system user_rotation 0`; asleep/charging → `input keyevent KEYCODE_WAKEUP`
  then swipe up. Capture transient overlays mid-gesture with `input motionevent DOWN/MOVE...; screencap; input
  motionevent UP`. Can't hear audio over adb → verify audio via `dumpsys audio | grep "/<pid>"` player states
  (started/paused). **New editor dialogs MUST use bare `new MaterialAlertDialogBuilder(this)` (no theme arg).**

## DONE this session (compile-green + device-verified) — don't redo
- **Music-runs-on-after-video-end BUG fixed.** `syncAudioPlayerWithPlayhead` now gates on actual playback
  (`playerManager.isPlaying() || imagePlaybackActive || audioTailActive`) with a 400ms debounce
  (`audioStoppedSinceMs`), not `getPlayWhenReady()` (which stays true at STATE_ENDED / stuck-in-gap). Added
  `FaditorPlayerManager.isEnded()`, `pauseAudioPlayer()` at the video-end stop branch + STATE_ENDED, and an
  audio-tail-first guard in the play/pause onClick.
- **Transcript word-edit timestamp box + ±1-frame carets** (`showWordEditDialog`): tappable absolute-time box
  (type exact seconds) + ◄ ► nudge ±33ms, applied live via `TranscriptPanelView.setWordStart`/`getWord` +
  `syncTimelineTranscript` + caption rebind.
- Earlier: volume control overhaul; visualizer 3-column Rolodex + polish; LAYERS visible slices (multi-lane
  audio, VISUALIZER + CAPTION timeline rows, tappable rows → open/select, long-press rows → delete).
- **OPACITY / transparency for overlays — BUILT + compile-green + installed; device-verify still PENDING.**
  - Model `TextOverlayItem`: new static `float opacity` field (default 1f) + `getOpacity()`/`setOpacity()`
    (clamped 0..1); `animatedOpacity()` now falls back to the static `opacity` instead of hardcoded 1f;
    `addKeyframeAt()` captures the current static opacity (was hardcoded 1f). `addOpacityKeyframeAt(t,op)`
    already existed and is now reachable from the UI.
  - Storage `ProjectStorage`: round-trips `opacity` (serialized only when != 1f; has()-guarded read, back-compat).
    OPACITY keyframes already round-trip via the generic keyframe-track serializer — no change needed there.
  - UI `buildOverlayAnimationControls()` (the text/image overlay editor dialog): added an **OPACITY** label +
    SeekBar (0–100%) + live % readout, mirroring the volume-keyframe model — when the overlay **is armed**
    (has keyframes) the slider drops/updates an OPACITY keyframe at the playhead (a fade); when **not armed**
    it sets the static opacity for the whole overlay. Preview updates live (`overlayLayer.setPlayheadMs` +
    `rebuild`), `syncTimelineOverlays()` each change, `scheduleAutoSave()` on release.
  - Preview already read `animatedOpacity` (`TextOverlayLayer.java:146`); export already bakes it
    (`ExportManager.java:1665` `setAlphaScale(o.animatedOpacity(t))`). So preview + export are covered.
  - **UNFINISHED / next step:** device-verify the slider visibly changes overlay alpha in preview and that a
    fade (armed → two opacity keyframes at different playheads) animates during playback and on export. I was
    mid-verification (had just added a text overlay via the Text tool) when asked to wrap up. ALSO: this is
    overlays only — **video CLIP opacity keyframes are still NOT implemented** (would need a `{timeMs,opacity}`
    list on `Clip` like `AudioClip.VolumeKeyframe`, a GlEffect alpha pass in export, and a preview alpha path).
    If the user wants clip-level opacity, that's the remaining chunk of this feature.

## TODO — explicitly requested by the user (build these, device-verify each)

### 1. Transcript word "scrub-to-retime" strip with ACCELERATION + real-time timeline
User: drag left/right on a strip to move the selected word's start; a SMALL drag nudges a little, a LARGE
drag (finger far from center) moves it FASTER (ballpark fast, then dial in fine). Watch the word move in the
timeline transcript row IN REAL TIME. Build on the existing word-edit (timestamp box + carets already done).
- KEY: make the word-edit UI **non-modal** (a top drawer like `volume_drawer`, gravity top) so the BOTTOM
  timeline transcript row stays visible while scrubbing. The current AlertDialog covers the center.
- Custom horizontal `WordScrubView`: track finger dx from a center anchor; velocity = sign(dx) *
  (|dx|/unit)^~1.6 (super-linear → acceleration); each frame add `velocity * dtFrameMs` to the word's start
  via the existing live-apply (`setWordStart` + `syncTimelineTranscript` + invalidate + caption rebind). Snap
  back to center on release. Reuse `wordAbsoluteStart`/`applyAbs` logic from `showWordEditDialog`.
- Keep the tappable exact-time box + ◄ ► carets in the same panel.

### 2. OVERLAY opacity — DEVICE-VERIFY (built this session, see DONE block) + optional video-CLIP opacity
Overlay opacity (static + keyframed fades) is BUILT and installed but NOT device-verified yet. FIRST verify:
open a text/image overlay editor → drag the new OPACITY slider → confirm the overlay visibly fades in preview;
then arm (ADD KEYFRAME), move playhead, change opacity → confirm a fade animates on playback and survives an
export of a short clip. THEN, if the user wants it, add **video-CLIP** opacity keyframes (still missing): a
`List<{timeMs,opacity}>` on `Clip` (mirror `AudioClip.VolumeKeyframe`/`gainAtClipMs`), storage round-trip, a
GlEffect alpha pass in `ExportManager`, and a preview alpha path (player/image view), plus an envelope on the
clip's timeline row + the same arm/slider UI.

### 3. AI relative-retiming tool (so "set 'in' at 6s, space the/day evenly to 'ye'" works)
WHY it failed: the only transcript-timing AI tool is `correct_transcript` (find/replace text, redistributes
time PROPORTIONALLY across a run) — there is NO tool to set/anchor specific word TIMES, so the model asked for
exact timestamps it couldn't get. FIX: add an AI tool `retime_words` in `AIToolExecutor` (register in the tool
list + dispatch). Args: `{clipId, anchors:[{word|index, timeMs}], distribute:"even"|"by-length"}`. It sets the
anchor words' start times and interpolates the in-between words' times between consecutive anchors (and a
trailing anchor like an existing well-timed word, e.g. "ye"). This directly fulfills "make 'in' start at 6s
and space 'the','day' evenly until 'ye'". Mirror `correct_transcript`'s clip/transcript lookup + save +
`AIChatState.signalModified`. Also update the system-prompt tool docs so the model reaches for it.

### 4. Frame-level AUDIO scrubbing
Currently scrubbing seeks the video frame; audio isn't positioned to frame level while scrubbing. Add: when
the user scrubs (onPlayheadSeeked drag), seek the audio `MediaPlayer`(s) to the scrub position and optionally
play a tiny blip (or just position silently) so audio aligns to the frame. At minimum, on scrub-finish, seek
audio players to the playhead so play-from-here is sample-accurate. (MediaPlayer.seekTo with
SEEK_CLOSEST on API 26+ for frame-accurate.)

## Open notes / gotchas discovered
- **Audio DUCKING is HOLLOW**: `Clip.duckAmount` is stored + set (volume sheet + AI `set_clip_duck`) but
  NEVER applied in export/playback — do not expose duck UI until a real voice-activity duck processor exists.
- The audio-tail feature (music continuing past video) does NOT reliably engage on video-end for these
  projects (the end path takes the "fully stop" branch); the bug fix makes it stop cleanly, which is correct.
  If the user wants outro-music-over-final-frame, revisit `getTimelineEndMs()` vs `totalEffectiveMs()`.
- `TranscriptWord` is immutable (final text/start/end) — "moving" a word = replace it in `transcript.words`.

## Two user questions answered (for the record)
- "Why didn't the AI just retime the words?" → no anchor-based retime tool existed (only proportional
  find/replace). See TODO #3 — adding `retime_words` fixes it.
- "Can we scrub audio to frame level too?" → not currently; see TODO #4 — feasible via MediaPlayer.seekTo
  on scrub (SEEK_CLOSEST for frame accuracy).

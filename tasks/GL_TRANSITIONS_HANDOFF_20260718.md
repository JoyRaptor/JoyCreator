# GL Transitions — Handoff (2026-07-18, after F13)

Continuation brief for the next agent. Read `tasks/PERF_SPEC_LONGFILE_20260718.md`
(F10–F13) and memories `gl-transition-preview-design`, `gapless-structural-edit-resync`,
`device-input-injection-limits`, `gradle-loopback-temp-fix` first.

## Where things stand (all committed)

- `f05f43b` F10–F12: gapless structural-edit resync; transition add/remove undoable +
  player resync both directions; PREVIEW transition frames crop to clip bounds.
- `924df30` F13: GL transition PREVIEW overhaul — reliable and good-looking:
  ticker survives STATE_ENDED at file-end seams; endpoint frames (A@out, B@in) decoded
  off-main + prefetched ~1.2s ahead; ValueAnimator drives the shader at frame rate;
  native GLUtils texture upload only-on-change; frames composed at the GL VIEW's rect
  (not container aspect); final blend frame HELD until the incoming player's
  onRenderedFirstFrame (1.5s timeout). Device-verified via screenrecord frame pulls.

Current preview blend is FREEZE-FRAME: two static endpoint bitmaps, A's motion pauses
≤600ms. JoyRaptor wants the next tier.

## Task 1 — Live A+B motion in the preview blend

Goal: both legs MOVE during the blend.

Recommended architecture (true live): render the blend on a GLSurfaceView/EGL context
with TWO SurfaceTexture-backed OES textures:
- Leg A: the main player is ALREADY playing A's tail during the window (F13 leaves it
  live under the GL view until frames are ready) — retarget its video output to a
  SurfaceTexture owned by the transition renderer for the window's duration, or
  PixelCopy the playerView at ~20fps as a cheaper intermediate.
- Leg B: a second, muted ExoPlayer prepared on B (prefetch at seam-approach, same spot
  as `prefetchGlTransitionEndpoints` in FaditorEditorActivity), playing into the second
  SurfaceTexture. On blend end this player can BECOME the main player for B (double win:
  removes the handoff prepare gap entirely — the 1.5s hold becomes unnecessary).
- Shader: `GlTransitionShaderProgram` / `GlTransitionShaderLoader.loadWrappedPreviewShader`
  currently sample TEXTURE_2D; OES sampling needs `#extension GL_OES_EGL_image_external`
  + samplerExternalOES variants of the wrapper (see how PlayerHolder/export pipelines
  handle OES if reusing).

Intermediate tier (much smaller, if the full thing is too big): decode N≈4 frames per
leg across the window (OPTION_CLOSEST — CLOSEST_SYNC lands all samples on one keyframe
in long-GOP sources) and step them by progress in the animator's update. The
upload-on-change texture cache in `GlTransitionPreviewView.bindTexture` already handles
changing bitmaps cheaply. Watch decode time (~300ms/frame OPTION_CLOSEST): start the
prefetch earlier (2.5s) and keep the graceful degradation (blend starts when ready;
A's live tail covers the wait; hard-cut fallback at progress≥1 stays).

Key invariants (breaking these re-introduces fixed bugs — see F13 in the spec):
- Ticker must keep running while `transitionPlaybackActive` (STATE_ENDED kills isPlaying).
- Completion paths: animator owns it when running; poll hard-cut at progress≥1 when not;
  ENDED+isAtTrimEnd fallback only when no animator. Never two advancers for one seam.
- Retriever calls go through `transitionDecodeLock` (shared with the scrub path).
- Animator bitmaps must be defensive copies (frame cache recycles on hide).
- Frames composed at GL-view rect (`glTransitionFrameDims()`), never container aspect.
- Stale handoff-holds cleared on new seam activation (`glTransitionHold = false`).

## Task 2 — EXPORT parity (transitions must match preview)

> **UPDATE 2026-07-18 ~15:50 (commit 62bc140): Task 2A is DONE and device-proven** —
> see "F12 CLOSED" in the spec. Remaining here: 2B verification sweep, and the known
> limit that incoming-leg crop covers the "custom" preset only (named preset crops on
> the incoming clip still blend uncropped).

Two known export gaps, both in the transition segment:

A. ~~CROPS IGNORED~~ FIXED in 62bc140 (spec F12 CLOSED). Original description: `ExportManager.assembleClipVideoEffects` skips the Crop
   effect when `isTransitionItem == true` (~line 2181), and `GlTransitionExportEffect`
   samples the INCOMING clip's raw source (built in `ExportManager` ~line 1361 with
   `nextClip.getSourceUri()`). Exported result: framing pops to uncropped raw + black
   bars for the transition's duration, then back — exactly the preview bug JoyRaptor caught,
   still present in export. Fix BOTH legs:
   - Outgoing: add the clip's Crop effect ORDERED BEFORE the GlTransitionExportEffect in
     the videoEffects chain; verify the effect's canvas compose still letterboxes
     correctly after crop changes the frame dimensions.
   - Incoming: apply nextClip's crop to the sampled frames INSIDE GlTransitionExportEffect
     (crop rect on the sampled texture/bitmap, or pre-crop uniforms in its shader).

B. GEOMETRY/ASPECT: after fixing crops, verify the same container-vs-canvas aspect trap
   fixed in preview (F13 item 4) doesn't exist in export: each leg must be composed on
   the export CANVAS (`canvasDims`) fit-centered, so mid-transition framing == the
   adjacent non-transition frames.

PROOF (required, do not skip): A/B export frame-diff with ABSOLUTE geometry per memory
`ab-export-frame-diff-proof`. Recipe: sandbox test project 302da9ac ("81033" in recents,
landscape rug 3.2s → tangentMotionBlur → vertical 5.5s; add custom crops to both clips
first to exercise F12A) → export → pull the mp4 (exports land in
`/storage/emulated/0/Android/data/com.fadcam.beta/files/FadCam/Faditor/`, find with
`-mmin -10`; logcat REDACTS paths) → ffmpeg-extract frames at the seam ±1s → assert the
cropped framing holds through the blend and matches preview screenrecords.

## Environment / verify recipes

- Build: `cd FadCam && TEMP=C:\Users\JoyRaptor\gtmp TMP=C:\Users\JoyRaptor\gtmp ./gradlew assembleDebug`
  (AppData TEMP is AF_UNIX-broken → "Unable to establish loopback connection"). If
  "Could not get file mode … java_res": `rm -rf app/build/intermediates/java_res`.
  APK: `app/build/outputs/apk/default/debug/app-default-arm64-v8a-debug.apk`.
- Sandbox device SM-N960U id `SANDBOX_SERIAL` (1080x2220): launch
  `monkey -p com.fadcam.beta -c android.intent.category.LAUNCHER 1`, Faditor nav tap
  (627,2100), top recent project (540,706), play (541,1746), seek-to-0 = tap minimap far
  left (45,1815). Play at timeline END does NOT restart — seek first. adb from Git Bash:
  `export MSYS_NO_PATHCONV=1`; screencap redirect only in Git Bash (PowerShell mangles).
- Verify pattern: `screenrecord --time-limit 9` + tap play + pull +
  `ffmpeg -vf fps=10,scale=300:-1` frame pull; read the frames.
- JoyRaptor's personal phone is `REAL_SERIAL` — NEVER inject taps while it's in-hand (screen
  rotation between screenshots = in-hand; stop immediately). Model truth via
  `run-as com.fadcam.beta cat files/faditor/projects/<id>/project.json`.
- Do not commit without asking. The human is reviewer-of-record.

## Also open (smaller, unrelated to transitions)

- Speed-change gapless sibling: `setPlaybackSpeed` never rebuilds the engine's playlist
  (per-window speed baked at prepare) — needs rebuild-on-release in the speed sheet.
- F9 cosmetic: same-session "analyzing audio…" sticks after band extraction completes
  (restart clears; likely missed invalidate in BandedTimelineWaveformCache onReady).

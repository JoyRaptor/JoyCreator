# FadCam Recording Pipeline — Handoff Document

**Last updated:** 2026-06-20
**Purpose:** Document FadCam's *capture/recording* pipeline (as opposed to the Faditor *editing*
pipeline covered by `HANDOFF.md`). This unblocks two pieces of queued work:
1. The **webcam-doesn't-rotate-in-landscape** bug (§9).
2. The **dual-stream-recording** spec (`tasks/feature-dual-stream-recording-spec.md`).

It is pure exploration — no build was required to produce it. Line references are accurate as of
2026-06-20; treat them as approximate if the files have since changed.

---

## 1. THE TWO RECORDING SUBSYSTEMS

FadCam records in two fundamentally different ways. **Do not conflate them.**

| | **Camera recording** (FadCam classic) | **Screen recording** (FadRec) |
|---|---|---|
| Entry service | `services/RecordingService.java` (7016 lines) | `fadrec/services/ScreenRecordingService.java` |
| Source | Camera2 `CameraDevice` capture session | `MediaProjection` → `VirtualDisplay` |
| Compositor | `opengl/GLRecordingPipeline` + `GLWatermarkRenderer` | `fadrec/encoding/ScreenRecordingPipeline` + `GLWatermarkRenderer` |
| Webcam overlay | **PiP composited into the frame** (dual-camera) | **System overlay window** captured by the screen grab |
| Dual variant | `dualcam/service/DualCameraRecordingService.java` | (webcam is the floating overlay) |

Both video paths funnel through the **same** `GLWatermarkRenderer` (OpenGL ES compositor) and the
**same** `FragmentedMp4MuxerWrapper` muxer. The audio capture loop is duplicated (one in
`GLRecordingPipeline`, one in `ScreenRecordingPipeline`) but structurally identical.

---

## 2. CAMERA RECORDING PIPELINE (RecordingService)

`services/RecordingService.java` is a foreground `Service` and the single biggest file in the app.
It owns the Camera2 session and drives `GLRecordingPipeline`.

**Flow (happy path):**
1. `onStartCommand` (line ~997) handles `ACTION_START_RECORDING` and friends (pause/resume/stop,
   torch, camera-switch, tap-to-focus, zoom, photo capture — all intent-driven).
2. `setupSurfaceTexture()` (~4429) and `openCamera()` (~3375) open the selected `CameraDevice`.
3. `createCameraPreviewSession()` (~3959) builds a `CameraCaptureSession`. The **target surfaces**
   are assembled in `createStandardSession()` (~5724) / `createHighSpeedSession()` (~5650):
   - the **GL pipeline's camera-input surface** (`cameraInputSurface`, backed by a `SurfaceTexture`
     that the renderer samples as an OES external texture) — this is the recorded stream;
   - the **preview surface** (optional, the editor/recorder UI);
   - an optional **motion-analysis `ImageReader` surface** (`maybeAttachMotionAnalysisSurface`,
     ~2532) for the motion-detection / forensics features.
4. The capture request is configured by `applySavedCameraPrefsToBuilder()` (~5776):
   frame rate (`applyFrameRateSettings` ~5936), zoom (`applyZoomSettings` ~5995), AE/AF, EV, mirror.
5. The camera writes frames into the GL pipeline's `SurfaceTexture`; the renderer composites
   (camera + watermark + optional PiP + optional forensics overlay) and draws into the **encoder
   input surface**; MediaCodec produces H.264 NAL units that the muxer writes to the MP4.

**Key methods worth knowing:**
- `stopRecording()` ~1771, `pauseRecording()` ~2060, `resumeRecording()` ~2099.
- `switchCameraLive()` ~2160 — hot-swaps front/back *during* recording: drains the encoder
  (`drainEncoderBeforeCameraSwitch` ~2345), closes camera resources (`closeCameraResourcesForSwitch`
  ~2373), reopens, and reuses the pause/resume timestamp machinery so the timeline stays contiguous.
- `handleSegmentRolloverInternal()` ~5337 — max-file-size segment splitting (see §5).
- `capturePhotoFromRecording()` ~1512 — still capture from the live stream.
- Watermark text is built by `buildBaseWatermarkText()` ~3061 and fed through
  `WatermarkInfoProvider` (`ensureWatermarkInfoProvider` ~3095).

---

## 3. THE GL COMPOSITOR (GLRecordingPipeline + GLWatermarkRenderer)

`opengl/GLRecordingPipeline.java` (3210 lines) is the orchestrator; `GLWatermarkRenderer.java`
(EGL/GLES20) is the actual renderer. The pipeline owns:
- `MediaCodec videoEncoder` (`video/avc`, I-frame interval 1s, line ~40), `encoderInputSurface`.
- `MediaCodec audioEncoder` + `AudioRecord audioRecord` (the audio loop, §4).
- `FragmentedMp4MuxerWrapper mediaMuxer` (§5).
- A dedicated `HandlerThread renderThread` + `Handler`; renders are posted as runnables, guarded by
  `renderRunnableQueued` (AtomicBoolean) so only one render is queued at a time. Target ~30fps
  (`PREVIEW_RENDER_INTERVAL_MS = 33`).
- Timestamp synchronization (`timestampLock`): `getSynchronizedVideoTimestamp(cameraTsNanos)`
  (~118) and `getSynchronizedAudioTimestamp()` (~99) produce a **single monotonic timeline** shared
  by video and audio, with pause periods subtracted.

**GLWatermarkRenderer render paths:**
- `renderToEncoder()` / `renderToEncoderInternal(allowStaleFrame)` (~403/420) — draws to the encoder
  EGL surface and swaps buffers (one encoded frame).
- `renderPreviewOnlyFrame()` (~363) — draws to the preview EGL surface only (no encoding).
- Both: sample camera OES texture → apply orientation tex-matrix (§9) → `drawOESTexture` →
  optional `drawPipOverlay` (dual camera) → watermark/forensics overlay → `eglSwapBuffers`.

The renderer keeps **two EGL window surfaces** off one shared `EGLContext`: `eglSurface` (encoder)
and `previewEglSurface` (UI). It defensively recreates surfaces on `EGL_BAD_SURFACE` (~475).

---

## 4. AUDIO CAPTURE LOOP

In `GLRecordingPipeline` (fields ~164–183): `AudioRecord` (mic) → byte buffers → `MediaCodec`
audio encoder (AAC) → muxer audio track.
- `audioThread` runs the capture loop; `audioThreadRunning` / `audioLock` gate it.
- Audio focus is requested (`audioFocusListener`, `originalAudioMode`, `originalSpeakerphoneOn` are
  saved and restored on stop).
- Silence/feedback guard: `consecutive512Count` tracks consecutive 512-byte AAC frames (a tell for
  silence) for diagnostics.
- PTS bookkeeping: `audioSamplesWritten`, `lastAudioPts`, `lastVideoPts` keep A/V in lockstep on the
  synchronized timeline. During pause the audio thread stops recording samples (so no samples ⇒ no
  time advances), and `currentPauseOffsetUs` is subtracted on resume.
- Wired-mic detection: `isWiredMicConnected()` (RecordingService ~5438) influences audio routing.

`ScreenRecordingPipeline` has its own equivalent: `startAudioRecordingLoop()` (~946),
`queueAudioData()` (~986), `startAudioEncodingLoop()` (~1001), `drainAudioEncoder()` (~1021).
`zeroAudioBuffer()` (~977) writes silence when the mic is muted/unavailable so the track stays
continuous.

---

## 5. ENCODER / MUXER / SEGMENT ROLLOVER

- Muxer: `media/FragmentedMp4MuxerWrapper.java` — writes **fragmented** MP4. (NB: this is exactly
  why Faditor export must remux these files to seekable MP4 before clipping — see `HANDOFF.md`
  §3.15 "ClippingMediaSource: not seekable".)
- `videoTrackIndex` / `audioTrackIndex` assigned when each encoder emits `INFO_OUTPUT_FORMAT_CHANGED`;
  the muxer starts (`muxerStarted` / `tryStartMuxer`) only once **both** formats are known.
- **Segment rollover** (max file size): when `segmentBytesWritten` approaches `maxFileSizeBytes`,
  `ROLLOVER_PREEMPT_THRESHOLD_RATIO = 0.95` triggers an **early keyframe request**
  (`pendingRollover` → `awaitingKeyframeForRollover`). On the next sync frame the current MP4 is
  finalized and a new segment file (`segmentNumber++`) is opened via `SegmentCallback.onSegmentRollover`.
  `rolloverInProgress` / `rolloverRequestedByDrain` guard against concurrent rollovers. This keeps
  each segment independently playable.
- `ScreenRecordingPipeline` uses `setNextOutputPath(path, fd)` (~1078) for the same rollover flow.

---

## 6. PAUSE / RESUME

Implemented in `GLWatermarkRenderer` (fields ~236–244, methods `pauseRecording()` ~2237,
`resumeRecording()` ~2300) and mirrored by `RecordingService.pauseRecording/resumeRecording`.

Mechanism (timeline stays contiguous, no gap):
- On pause: `isPaused = true`; `pauseStartTimeNanos = System.nanoTime()`; save
  `lastVideoPtsBeforePauseUs` / `lastAudioPtsBeforePauseUs`. `getSynchronizedVideoTimestamp()`
  returns **-1 while paused** to signal "skip this frame" (no encoded frames during pause). The audio
  thread stops pulling mic samples.
- On resume: `totalPauseDurationNanos += (now - pauseStartTimeNanos)`. All subsequent sample PTS have
  the accumulated pause duration subtracted (`currentPauseOffsetUs`), so the encoded timeline has no
  pause gap.
- **Camera switch reuses this**: `prepareForCameraSwitch()` (~2285) sets `isCameraSwitchPause` so the
  same offset machinery covers the reopen latency.

---

## 7. WEBCAM-BUBBLE COMPOSITING — TWO DISTINCT PATHS

### 7a. Floating webcam overlay (for SCREEN recording) — `fadrec/ui/FloatingWebcamService.java`
A separate foreground `Service` (`FOREGROUND_SERVICE_TYPE_CAMERA`) that adds a
`TYPE_APPLICATION_OVERLAY` window (`WindowManager`) containing a **`TextureView` camera preview**.
It is **not composited by GL** — it simply sits on top of the screen and is therefore captured by the
`MediaProjection` screen grab. Features: drag-to-move, resize handle (aspect-locked near-diagonal,
free otherwise), minimize-to-bubble, front/back switch, multi-back-lens cycling, corner-roundness
slider (persisted in `floating_webcam_prefs`). Frame fit: `configureTransform()` (~370) center-crops
using `sensorOrientation` only. **`isRunning` static flag** lets the FadRec floating menu reflect state.

### 7b. PiP composite (for dual CAMERA recording) — inside `GLWatermarkRenderer`
The actual second-camera-into-the-frame compositing. Fields ~110–144 (`pipOesTextureId`,
`pipSurfaceTexture`, `pipCameraInputSurface`, `pipVertexBuffer`, `pipMvpMatrix`). The secondary camera
writes into `pipSurfaceTexture`; `drawPipOverlay()` (~2302) draws it as a rounded-corner quad over the
primary frame. Geometry computed by `computePipGeometry()` (~2218) from `DualCameraConfig`
(position TOP_LEFT/…/BOTTOM_RIGHT, size ratio, margin dp, corner radius). `camerasSwapped` toggles
which camera is fullscreen vs PiP. `updatePipConfig()` (~2134) hot-updates position/size without
restarting. PiP draws with an **identity MVP** so the global aspect/rotation MVP doesn't distort the
PiP rectangle (geometry is pre-computed in post-rotation NDC).

---

## 8. DUAL-CAMERA RECORDING SERVICE

`dualcam/service/DualCameraRecordingService.java` (1696 lines) opens **both** cameras
(`openBothCameras()` ~801, staggered open to ease shared-hardware pipelines) and delegates to a
single `GLRecordingPipeline` with PiP enabled — i.e. dual-camera is single-camera mode + the PiP
overlay. Intent handlers: start/stop/pause/resume, `handleSwapCameras()` (~483, reassigns cameras to
the primary vs PiP input surfaces so the renderer needs no per-frame swap), `handleUpdatePipConfig()`
(~540), torch, EV/AE/AF/zoom/tap-focus/mirror, photo capture. Capability/config models live in
`dualcam/DualCameraCapability.java`, `DualCameraConfig.java`, `DualCameraState.java`.

**This is the closest existing analog to the dual-stream-recording spec** — but note the spec wants
*two synchronized output streams* (screen + raw webcam), whereas this service produces *one composited
stream*. The reusable parts: the synchronized-timestamp machinery (§3), the two-camera open/swap
logic, and the segment/muxer plumbing.

---

## 9. ORIENTATION / ROTATION HANDLING + THE LANDSCAPE-WEBCAM BUG

### How rotation is computed (camera path)
`GLWatermarkRenderer.getRequiredRotation()` (~2698):
```java
int rotation = (sensorOrientation - getDisplayRotation() + 360) % 360;
if (isFullscreenCameraFront()) rotation = (360 - rotation) % 360;  // front compensation
```
This is correct and *does* consult the live display rotation. `initializeEncoder()` swaps encoder
width/height for 90/270° so `encoderWidth/Height` are already post-rotation, and `computePipGeometry`
explicitly warns not to swap again (~2221).

### Where it breaks for landscape (the bug)
The **texture matrix** for landscape is **hardcoded**, not derived from the actual rotation. In both
`renderToEncoder` (~636–648) and `drawPipOverlay` (~2318–2332) the code does:
```java
boolean isLandscape = "landscape".equalsIgnoreCase(userOrientationSetting);
if (isLandscape) { /* fixed vertical-flip matrix: scale(1,-1) + translate(0,-1) */ }
else            { /* use raw SurfaceTexture transform */ }
```
Problems:
1. It branches on the **user's orientation *setting* string** (`"landscape"`/`"portrait"`), not on the
   real `deviceOrientation` (`Surface.ROTATION_0/90/180/270`). So **ROTATION_90 vs ROTATION_270
   (landscape vs reverse-landscape) are treated identically** — one of them comes out upside-down.
2. The fix is a blunt vertical flip rather than the rotation that `getRequiredRotation()` already
   knows. The PiP (webcam) inherits the same blunt flip, so **the webcam/PiP content does not rotate
   with the device in landscape** — it stays in its portrait-derived orientation.
3. `setDeviceOrientation()` (~2442) exists and calls `updateMatrices()`, but the landscape tex-matrix
   path ignores `deviceOrientation` entirely.

### The floating-webcam variant of the bug (screen path)
`FloatingWebcamService.configureTransform()` (~370) computes its crop from `sensorOrientation` only and
never consults the display rotation, so when the device (and the overlay window) rotate to landscape
the preview transform is wrong (rotated/stretched). Because the screen grab just captures whatever the
overlay shows, the recorded webcam is rotated too.

### Fix — Camera/PiP path IMPLEMENTED (iteration 1, 2026-06-20, compile-green + installed; NEEDS LANDSCAPE VERIFY)
Done in `GLWatermarkRenderer`. The three duplicated `userOrientationSetting`-string landscape branches
(encoder `renderToEncoder` ~637, preview ~866, `drawPipOverlay` ~2324) are removed. Now:
- **Fullscreen (encoder + preview): use the raw `texMatrix` in EVERY orientation.** The fullscreen quad
  is already rotated by `recordingMvpMatrix`/`previewMvpMatrix` (= `getRequiredRotation()`, distinct for
  ROTATION_0/90/180/270), so the old hardcoded vertical-flip was a band-aid that, keyed off the setting
  STRING, applied identically to 90 and 270 (one came out upside-down). **Portrait path is byte-for-byte
  unchanged** (portrait already used raw), so portrait cannot regress; only landscape changes.
- **PiP: counter-rotate the PiP texture by `getDisplayRotation()`** via the new
  `applyPipDisplayRotation(rawTexMatrix)` (rotates texcoords about (0.5,0.5), composed after the raw ST
  transform like `applyHorizontalTexFlip`). The PiP quad draws with an IDENTITY MVP (so the global
  rotation doesn't distort the PiP rectangle) — which is exactly why the PiP content never rotated with
  the device. The counter-rotation is **0° in portrait** (`getDisplayRotation()==0` → returns the source
  matrix untouched), so portrait PiP is unchanged; ROTATION_90 vs _270 now rotate distinctly.

**WHY this is portrait-safe:** every changed branch reduces to the exact prior behavior when the device
is in portrait. The only behavioral delta is in the two landscape orientations.

**OPEN UNKNOWNS to confirm on device (record in landscape BOTH ways):**
1. Fullscreen single-front-cam in landscape: should now be upright (was vertically flipped / one
   orientation upside-down).
2. Dual-cam PiP in landscape: rotates upright with the device. **Direction corrected per device testing
   (2026-06-20):** `applyPipDisplayRotation` now uses `(360 - getDisplayRotation()) % 360` (the negative
   of the display rotation) so left-side-down (ROTATION_90) turns CCW and right-side-down (ROTATION_270)
   turns CW. If a future device disagrees, the angle is isolated to that one helper.
3. The renderer holds a SINGLE `sensorOrientation` for both cameras; if front/back differ on this device
   the PiP rotation may need the PiP camera's own sensor orientation (not currently plumbed in).

### Fix — Floating-webcam path (screen recording): MANUAL controls shipped (2026-06-20, compile-green + installed)
Rather than risky auto-rotation in `FloatingWebcamService`, the overlay's tap-controls now expose **manual
rotate / mirror-H / mirror-V** icons (the user explicitly asked for these, and manual is robust for the
overlay case). `configureTransform()` folds `userRotation` (0/90/180/270, +90 per tap, over-scales to fill
on 90/270) and `userMirrorH/V` (postScale) into the preview matrix; all three persist in `floating_webcam_prefs`.
Because the screen grab captures whatever the overlay shows, these correct a sideways/flipped floating webcam
directly. Mirror toggles tint green when active. **VISUAL VERIFY** during a screen recording with the webcam
overlay. (Auto-rotation via `getDefaultDisplay().getRotation()` + `onConfigurationChanged` is still a possible
future enhancement, but the manual controls make it non-urgent.)

Also polished in the same pass: the controls' hard-edged `#66000000` box → a soft top-down gradient
(`drawable/webcam_controls_gradient.xml`, `#CC000000`→transparent, rounded top) so the options panel fades
out instead of showing a sharp dark rectangle.

---

## 10. KEY FILES

| File | Role |
|---|---|
| `services/RecordingService.java` | Camera recording foreground service (Camera2 session, prefs, switch, photo, segments). |
| `opengl/GLRecordingPipeline.java` | Camera GL compositor orchestrator: encoder, audio loop, muxer, timestamps, rollover. |
| `opengl/GLWatermarkRenderer.java` | EGL/GLES20 renderer: camera OES draw, watermark, **PiP**, orientation tex-matrix, pause. |
| `opengl/WatermarkInfoProvider.java` | Supplies live watermark text. |
| `media/FragmentedMp4MuxerWrapper.java` | Fragmented MP4 muxer (⇒ needs remux before Faditor clipping). |
| `dualcam/service/DualCameraRecordingService.java` | Dual-camera (PiP) recording; opens both cameras. |
| `dualcam/DualCameraConfig.java` / `DualCameraCapability.java` / `DualCameraState.java` | PiP config + capability models. |
| `fadrec/services/ScreenRecordingService.java` | Screen recording service (MediaProjection lifecycle). |
| `fadrec/encoding/ScreenRecordingPipeline.java` | Screen GL compositor: VirtualDisplay → GLWatermarkRenderer → encoder/muxer/audio. |
| `fadrec/ui/FloatingWebcamService.java` | Floating webcam overlay window (captured by screen grab). |

---

## 11. OPEN ITEMS THIS UNBLOCKS

- **Webcam-landscape-rotation bug** — root cause + fix direction in §9 (both the camera-PiP path and
  the floating-overlay path). VISUAL VERIFY required after any fix (record in landscape, check webcam
  orientation in both ROTATION_90 and ROTATION_270).
- **Dual-stream recording spec** — §8 explains the existing dual-camera service produces ONE composited
  stream; the spec wants TWO synchronized streams. Reusable: synchronized-timestamp machinery (§3/§6),
  two-camera open/swap (§8), segment/muxer plumbing (§5). New work: a second encoder+muxer fed the raw
  webcam OES texture in parallel with the composited stream, sharing the synchronized timeline.

---

## 11a. DUAL-STREAM RAW-WEBCAM RECORDING (Phases 1–3, 2026-07-17)

Implements `tasks/feature-dual-stream-recording-spec.md` Phases 1–3 — a second, independent
encoder writing the RAW webcam feed to `<screenfile>_webcam.mp4`, frame-synced to the screen
recording. Compile-green on the file-watcher; device verification is queued (see the spec's
Status block for the exact adb/ffprobe steps).

- **`fadrec/encoding/RecordingClock.java` (new)** — single pause/rebase source of truth
  (spec Decision 3). Extracted from `ScreenRecordingPipeline`'s old private timestamp fields
  (§6 above). SHARED pause state (`paused`, `totalPausedTimeNanos`) + per-encoder baselines via
  `RecordingClock.Stream` (`newStream()`), so both files start at PTS 0 yet subtract the SAME
  pause duration → identical effective duration & segment boundaries. The `primary` stream is a
  byte-for-byte reproduction of the old `getSynchronizedVideo/AudioTimestamp()`, so plain screen
  recording (dual OFF) is unchanged.
- **`ScreenRecordingPipeline`** now drives/reads the clock instead of owning pause fields; added
  `getRecordingClock()` and `setAudioTap(AudioTap)` (the PCM tee point, in `queueAudioData` ~1007).
- **`fadrec/encoding/WebcamEncoderPipeline.java` (new)** — video/avc MediaCodec fed by a Surface
  + AAC fed by teed PCM + `FragmentedMp4MuxerWrapper`; mirrors ScreenRecordingPipeline's shape.
  All PTS via a `RecordingClock.Stream`. **No segment rollover in v1** (writes one file).
- **`fadrec/ui/FloatingWebcamService`** — adds the encoder surface as a SECOND camera target
  (`startPreview()` builds a 2-surface session; `restartCaptureSession()` rebuilds to add/remove
  it). Static bridge: `attachRecordingSurface`/`detachRecordingSurface`/`isPlainWebcamActive`/
  `getActivePreviewSize`/`getActiveSensorOrientation`/`setOverlayLifecycleListener`. **Avatar mode
  excluded** (no Camera2 preview → no raw pixels). A capture-session rebuild causes a brief
  preview blip at record-start — accepted, not a blocker.
- **`fadrec/services/ScreenRecordingService`** — `startDualStreamWebcamIfEnabled()` gates on pref
  `fadrec_dual_stream_webcam` + `DualEncoderCapabilityChecker.supportsDualHardwareEncode()` +
  `FloatingWebcamService.isPlainWebcamActive()`. Shares the screen pipeline's clock, sizes the
  webcam encoder to the camera preview size (must match a supported camera output size — no
  16-rounding), tees PCM via `setAudioTap`. Pause/resume need NO extra wiring (screen drives the
  shared clock, webcam observes). Finalized on stop / overlay-close / cleanup; any failure
  downgrades to screen-only.

**Phase 4 (editor import + `linkedClipId`) is NOT done** — owned by the editor lane. The two files
land on disk as ordinary recordings; the `_webcam.mp4` suffix is the pairing hint.

## 12. NOTES / GOTCHAS

- Fragmented MP4 output is the reason Faditor export remuxes FadRec recordings (cross-ref `HANDOFF.md`
  §3.15). Any new recording output that Faditor will edit should be seekable or expect a remux pass.
- `GLWatermarkRenderer` owns EGL on a dedicated render thread — never touch GL objects off that thread.
- Two independent audio loops exist (camera vs screen). A future refactor could unify them, but they
  are intentionally separate today; fix bugs in both if touching audio.
- Pause inserts **no** timeline gap by design (PTS offset subtraction) — preserve this when editing the
  pause/resume or camera-switch code.

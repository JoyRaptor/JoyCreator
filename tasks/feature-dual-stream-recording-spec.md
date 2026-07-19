# Feature Spec: Synchronized Dual-Stream Recording — Screen + Raw Webcam (for Claude Code)

**Status (2026-07-17):** Phase 0 DONE (`2970737`). **Phases 1–3 DONE** (compile-green on the
file-watcher; NOT yet device-verified — see the verification checklist at the end of this block).
**Phase 4 FOUNDATION landed** (`6bf5066`, 2026-07-17, compile-green): `Clip.linkedClipId`
schema + getter/setter/isLinked + ProjectStorage write/tolerant-read (omit-when-null, so old
projects stay byte-identical) + copy semantics (fresh-id `Clip(other)` does NOT inherit the link;
`relinked()` keeps it since it preserves the id) + `Timeline.findClipById/findLinkedClip/
linkClips(static, symmetric)/unlinkClip(both-sides, idempotent)`. This is the dormant, safe base.
**Phase 4 REACHABLE OPS LANDED (2026-07-19, compile-green, NOT device-verified):** both
JoyRaptor-mandated entry points + delete/split/trim mirroring. Files: `Timeline.java`
(findClipById now spans BOTH lanes so a master↔overlay link resolves; new `indexOfClip`),
`assetbrowser/AssetBrowserPanel.java` (`findDualStreamPartner` + `isWebcamSibling` +
`WEBCAM_SUFFIX="_webcam"`), `FaditorEditorActivity.java` (all UI + edit-op wiring).
  (a) **Link creation — DONE, both entry points.**
      - AUTO-DETECT: `insertSelectedAssetAtPlayhead` (the green playhead-insert button) checks
        `assetBrowserPanel.findDualStreamPartner(selectedAsset)`; when a `<name>.mp4 ↔
        <name>_webcam.mp4` sibling exists it shows `offerDualStreamPairInsert` → "Add as
        linked pair" runs `addDualStreamLinkedPair(screen, webcam)`: IO off the main thread
        (importInsertedAsset + getVideoDuration on `assetImportExecutor`), then screen →
        master clip (appended), webcam → overlay/PiP clip (mirrors `onOverlayVideoPicked`'s
        starter transform) pinned to the master's start, `Timeline.linkClips` both ways, ONE
        undo step. Neutral button adds only the tapped file.
      - MANUAL: `showMarqueeBatchMenu` now appends "Link clips (screen + webcam)" when
        `eligibleDualStreamLinkPair` finds EXACTLY one master + one overlay clip, neither
        already linked (`linkDualStreamPair`), and "Unlink clips" when the selection touches
        any linked clip (`unlinkDualStreamPair`). Toasts on both; one undo step each. These
        are the master↔overlay `linkedClipId` mechanism — SEPARATE from the G9 peer
        link-timing groups, which stay untouched.
  (b) **Mirrored ops — DONE (delete + split required; trim same-speed).**
      - DELETE: `deleteSelectedSegment` (master lane) and `deleteOverlayClipWithConfirmation`
        (overlay lane) both detect a linked partner and route to `confirmDeleteLinkedPair` —
        a confirm dialog that NAMES the partner and deletes BOTH; ONE undo step restores the
        pair (both clips re-inserted at original positions + re-linked).
      - SPLIT: `splitAtPlayhead` → `splitLinkedPartnerAndRecord`. The master splits via
        `Timeline.splitAt` (fresh-id, unlinked halves); the overlay partner is split at the
        SAME source-relative point (offset from each clip's in-point, clamped to partner
        bounds), the right half's `overlayStartMs` advances by the left half's effective
        duration, and the halves are RE-LINKED pairwise (A↔left, B↔right). ONE undo step.
      - TRIM: `onTrimFinished` → `recordTrimMaybeMirrored`. Same source-time in/out deltas
        mirror onto the partner (setters clamp to partner bounds); ONE undo step for the pair.
        **SCOPED TO SAME-SPEED pairs** (see Deferred) — a speed mismatch skips the mirror,
        trims the master only, and logs why.
Read the architecture reality map below BEFORE touching Phase 1 — Section 2's Camera2 guess does
NOT match the codebase.

### What was built (Phases 1–3)

- **`fadrec/encoding/RecordingClock.java` (new, commit `c3b77fa`)** — the single pause/rebase
  source of truth (Decision 3). Extracted the pause/timestamp math that lived privately in
  `ScreenRecordingPipeline` (`recordingStartTimeNanos` / `firstVideoTimestampNanos` /
  `totalPausedTimeNanos` / `pauseStartTimeNanos` / `isPaused`). Shared **pause state**
  (`paused` + `totalPausedTimeNanos`) lives on the clock; each encoder gets its OWN
  first-frame baseline via `RecordingClock.Stream` (`newStream()`), so both files start at
  PTS 0 but subtract the SAME pause duration. The `primary` stream reproduces the old
  `getSynchronizedVideo/AudioTimestamp()` byte-for-byte, so a plain screen recording (dual
  OFF) is a no-op refactor. `ScreenRecordingPipeline` was refactored to drive/read the clock;
  it also gained `getRecordingClock()` and a `setAudioTap(AudioTap)` PCM hook.

- **`fadrec/encoding/WebcamEncoderPipeline.java` (new, commit `23b194b`)** — the raw-webcam
  encoder: `video/avc` MediaCodec fed by a Surface + AAC MediaCodec fed by the teed PCM +
  `FragmentedMp4MuxerWrapper`, mirroring `ScreenRecordingPipeline`'s encoder/muxer shape. All
  PTS route through a `RecordingClock.Stream`. Output `<screenfile>_webcam.mp4`.
  **v1 skips segment rollover for the webcam file** (writes one continuous file even if the
  screen file auto-splits) — noted in the class javadoc.

- **`fadrec/ui/FloatingWebcamService.java` (commit `23b194b`)** — static bridge
  (`attachRecordingSurface` / `detachRecordingSurface` / `isPlainWebcamActive` /
  `getActivePreviewSize` / `getActiveSensorOrientation` / `setOverlayLifecycleListener`).
  Adds the encoder input surface as a **second target** on the existing camera session
  (`TEMPLATE_RECORD` when a recording surface is attached; session is rebuilt — a brief
  preview blip at record-start is accepted, NOT a blocker). **Avatar mode is excluded** (the
  puppet path never opens a Camera2 preview, so there are no raw camera pixels to encode —
  dual-stream falls back to screen-only there). Overlay close fires the lifecycle listener so
  the recording service finalizes the webcam file (a shorter-but-valid pair beats a corrupt one).

- **`fadrec/services/ScreenRecordingService.java` (commit `23b194b`)** — owns the orchestration.
  On start, `startDualStreamWebcamIfEnabled()` gates on: pref `fadrec_dual_stream_webcam` ON
  **AND** `DualEncoderCapabilityChecker.supportsDualHardwareEncode()` **AND**
  `FloatingWebcamService.isPlainWebcamActive()`. It builds `WebcamEncoderPipeline` sharing
  `recordingPipeline.getRecordingClock()`, sizes the encoder to the camera's chosen preview
  size (must match a supported camera output size — no 16-rounding), attaches the surface,
  starts the pipeline, and tees mic PCM via `setAudioTap` (Decision 4). Pause/resume need NO
  extra wiring — the screen pipeline drives the shared clock, the webcam pipeline observes it.
  Sibling output: internal `<base>_webcam.mp4`, or a SAF sibling DocumentFile. Any failure
  downgrades cleanly to screen-only. Finalized on recording stop, overlay close, or cleanup.

### Deferred / limitations
- Segment rollover NOT implemented for the webcam file (v1).
- Dual-stream unavailable in **avatar/puppet mode** (no raw camera surface).
- User-facing strings are `// TODO(strings)` — failures are logged, not surfaced in UI.
- Phase 4 reachable ops LANDED (see the Status block above). Remaining Phase-4 deferrals:
  - **Trim mirror is same-speed only.** Across a speed mismatch, equal source-time deltas map
    to unequal on-timeline deltas, so `recordTrimMaybeMirrored` trims the master only and logs
    the skip. Full cross-speed trim mirroring (scaling the partner delta by the speed ratio and
    re-clamping) is deferred — delete + split mirroring are complete and unconditional.
  - **No linked badge on master/overlay clip blocks.** The G9 chain badge is for peer
    link-timing groups, not the dual-stream `linkedClipId` pair; a visual affordance showing a
    clip is dual-stream-linked is deferred (the link is still discoverable via the batch menu's
    "Unlink clips" and the delete confirm dialog).
  - **Split mirror assumes clean recorded pairs.** The overlay-half `overlayStartMs` advance
    uses `getEffectiveDurationMs()`; interactions with per-half `removedSpans` (non-destructive
    heal gaps) on a linked pair aren't exercised — v1 recorded pairs have none.
  - **Auto-detect offer is on the playhead-insert button only.** Drag-drop insert
    (`insertAssetAtIndex`) and the start/end insert buttons add the single tapped file without
    the pair offer; the manual batch-menu "Link clips" covers those cases after the fact.

### DEVICE VERIFICATION (queued for a human-attended session — NOT run here)
Prereqs: a device where `DualEncoderCapabilityChecker.supportsDualHardwareEncode()` is true;
enable the "Record webcam as separate file" toggle; open the floating webcam overlay showing a
LIVE camera (not an avatar); then screen-record.
1. **Two files, matching length (Phase 1):** continuous ~60s recording. Pull both:
   `adb pull /sdcard/Android/data/com.fadcam/files/FadCam/Screen/<name>.mp4` and `..._webcam.mp4`.
   Compare duration + frame count:
   `ffprobe -v error -select_streams v:0 -show_entries stream=nb_read_frames,duration -count_frames -of csv <file>`
   Durations should match within a frame; frame counts within a couple of frames.
2. **Pause stress (Phase 2):** record with ~5 quick pause/resume cycles. Both files' total
   durations must match, AND the effective segment boundaries must line up (scrub both — the
   content at each pause seam should be at the same timestamp). Compare programmatically via
   the ffprobe duration above; they must agree.
3. **Audio (Phase 3):** confirm both files carry synced audio independently:
   `ffprobe -v error -show_streams <file>` shows an aac stream in each; play each alone and
   confirm audio matches the video and is in sync.
4. **Overlay-close mid-recording:** close the webcam overlay while recording — the `_webcam.mp4`
   must be a valid, playable (shorter) file; the screen recording keeps going.
5. **Fallback:** with the overlay in avatar mode, or on a device that fails the capability
   check, recording must proceed screen-only with no `_webcam.mp4` and no crash.

### DEVICE VERIFICATION — Phase 4 reachable ops (owed; needs a real recorded pair)
Prereqs: a dual-stream recording so `<name>.mp4` + `<name>_webcam.mp4` sit in the project's
pinned asset folder. Open Faditor, open the asset browser on that folder.
1. **Auto-detect pair add:** select `<name>.mp4`, tap the green playhead-insert button → the
   "Linked recording detected" dialog appears → "Add as linked pair" places the screen file as
   a master clip AND the webcam file as a PiP overlay pinned to the master's start. Repeat by
   selecting the `_webcam.mp4` half — same offer. "Add … only" adds just the tapped file.
2. **Manual link/unlink:** marquee-select exactly one master clip + one overlay clip → batch
   menu shows "Link clips (screen + webcam)" → toast, clips linked. Re-marquee either → batch
   menu shows "Unlink clips" → toast, unlinked. (Selection with a non-clip payload, or 2 masters,
   must NOT offer "Link clips".)
3. **Mirrored delete:** select the master, hit trash → confirm dialog NAMES the webcam partner →
   "Delete both" removes both; ONE undo restores both. Repeat deleting the overlay half → same.
4. **Mirrored split:** playhead mid-clip on the master, split → BOTH master and overlay split at
   the same source-relative point; the resulting left pair and right pair are each still linked
   (verify: delete a left half → its partner left half is named/removed). ONE undo restores the
   pre-split pair.
5. **Mirrored trim (same speed):** drag the master's trim handle → the overlay partner's window
   mirrors the same source-time trim; ONE undo reverts both. Then set the master's speed ≠ the
   partner's and trim again → only the master trims, logcat shows "trim mirror skipped: speed
   mismatch".
6. **Round-trip:** save + reload the project → all links survive (foundation round-trip), and
   the mirrored ops above still behave.

## Architecture reality map (2026-07-17, verified against RECORDING_HANDOFF.md + source)

The spec assumed the webcam rides the main Camera2 capture session. In reality, for SCREEN
recording (the target mode) the webcam is `fadrec/ui/FloatingWebcamService.java` — a separate
overlay Service with its OWN Camera2 session feeding ONE surface (a `TextureView` preview that
MediaProjection happens to capture). Consequences:

- **The "third stream" is actually a SECOND surface on the FloatingWebcamService session** —
  add a `MediaCodec` encoder input surface alongside the TextureView surface in its
  `createCaptureSession` call. 2 streams total on that session: guaranteed headroom, no
  restructuring needed (spec §0 item 3: answered).
- **The screen side needs no capture changes** — `fadrec/encoding/ScreenRecordingPipeline`
  already encodes the projection. What it needs is to EXPOSE its pause/resume + timestamp
  state through the shared `RecordingClock` instead of owning it privately. Its audio loop
  (`startAudioRecordingLoop`/`queueAudioData` ~946/986) is where the PCM tee for Decision 4
  lives.
- **The new webcam pipeline** (`fadrec/encoding/WebcamEncoderPipeline`, new) mirrors
  ScreenRecordingPipeline's encoder+muxer shape: `MediaCodec` (video/avc, from the surface) +
  AAC audio track fed by the teed PCM + `FragmentedMp4MuxerWrapper` (same muxer wrapper —
  keeps the output consistent with all other FadCam recordings, including the fMP4 remux
  behavior Faditor already handles). Output file: `<screenfile>_webcam.mp4` next to the
  screen file (spec §3 naming convention).
- **RecordingClock** (new, `fadrec/encoding/`): extract the pause/rebase math documented in
  RECORDING_HANDOFF §6 (pauseStartTimeNanos / totalPauseDurationNanos / currentPauseOffsetUs,
  returns -1-while-paused semantics) into one object owned by ScreenRecordingService; both
  pipelines query it for every video/audio PTS. Do NOT let WebcamEncoderPipeline track pause
  itself (Decision 3).
- **Lifecycle coupling:** ScreenRecordingService starts/stops the webcam encoder only when
  the pref is on AND FloatingWebcamService.isRunning. If the user closes the webcam overlay
  mid-recording, finalize the webcam file cleanly at that point (a shorter-but-valid pair
  beats a corrupt file); if the overlay was never up, record screen-only as today.
- **Phase 4 linkage:** recordings import into Faditor via the picker paths
  (VideoSourceBottomSheet / onVideoAssetPicked in FaditorEditorActivity). `linkedClipId`
  assignment + mirrored trim/split/delete live in the editor lane — build AFTER 1–3 and
  coordinate with whoever owns the editor god-files at the time.
**Depends on:** `tasks/HANDOFF.md`. **This spec touches FadCam's recording pipeline, which `HANDOFF.md` doesn't currently document** (it's editor-focused). Read Section 0 below before anything else.

---

## 0. Read First — and Document What You Find

There is no existing handoff doc for FadCam's camera/recording internals. Before writing any code:
1. Locate and read the current camera capture session setup (how many output surfaces it already configures), the audio capture loop, the video encoder/muxer setup, the existing pause/resume implementation, and however the webcam bubble is currently composited onto the screen recording.
2. Write down what you find as `tasks/RECORDING_HANDOFF.md`, matching the documentation style of `tasks/HANDOFF.md`, before starting Phase 0. Everything in Section 2 below assumes you'll verify against that real implementation, not against this spec's guesses.
3. **Specifically determine how many of the 3 guaranteed-simultaneous Camera2 output streams the current bubble-compositing feature already uses.** This spec needs one more. If the existing implementation already uses 2 (e.g., a preview surface plus the bubble-compositing surface), there's no headroom left for a third without restructuring — flag this back before proceeding rather than discovering it mid-implementation.

---

## 1. Goal

Record the screen as today, but also save the **raw, full-resolution webcam feed** as a second, independent file — frame-accurately the same effective length as the screen recording, including identical pause/resume behavior, so the two can be loaded into Faditor as a synced pair and composed freely (cut to either, eventually position the webcam feed as its own layer) instead of being stuck with whatever was baked into the small bubble.

---

## 2. Architecture Decisions (Locked)

1. **One Camera2 session, multiple output targets — not a second camera session.** Every Camera2-capable device guarantees up to 3 simultaneous output streams from one session (configuration and performance overhead permitting). Opening a second independent session risks camera-busy conflicts with the first; instead, add a third target Surface (feeding a dedicated `MediaCodec` encoder for the raw file) to the existing capture session's request, alongside whatever surfaces already exist for preview/bubble-compositing.

2. **Encoder concurrency must be checked at runtime, not assumed.** Running two simultaneous hardware video encoders (screen + webcam) is gated per-device, set by the OEM in `media_codecs.xml`. Query `MediaCodecInfo.CodecCapabilities.getMaxSupportedInstances()` for the relevant codec/resolution before exposing this feature. If the device can't sustain two concurrent hardware encodes: either fall back to software-encoding the second (raw webcam) stream, or disable the feature for that device with a clear in-app message. Never assume support — this varies meaningfully across SoCs.

3. **Sync correctness is one shared clock, not two independent pause trackers.** This is the part most likely to go subtly wrong: if the screen-recording pipeline and the new webcam pipeline each manage their own pause/resume state independently, tiny timing differences in exactly when each one stops/resumes will compound across a session with several pause/resume cycles into a real, visible desync by the end. Build one `RecordingClock` object that is the single source of truth for "are we paused, and what's the current rebased presentation timestamp." Both encoder pipelines query it; neither decides pause state for itself.

4. **Duplicate the mic audio onto both files.** Same `AudioRecord`/PCM buffer, encoded into both the screen recording (as today) and the new raw webcam file. Cheap, and means the webcam clip is independently scrubbable with sound in the editor without needing to reference the other file.

5. **Ship as an opt-in toggle, off by default.** Doubling encode load has a real battery/thermal/storage cost. Gate the toggle's availability behind the Decision 2 capability check — if the device can't do it, don't show the option at all rather than showing it and having it fail.

6. **Editor models the pair as a linked clip, not two unrelated clips.** Default behavior: trimming/splitting/deleting one mirrors onto its linked partner at the same relative position — the same convention pro NLEs use for multicam pairs. An explicit **unlink** action breaks the connection for independent editing, matching what you actually asked for ("compose them in the editor however I want").

7. **Freeform repositioning of the webcam clip as a floating layer is out of scope here.** That needs the multi-layer video track system already flagged as near-term, not-yet-built roadmap work (same dependency the b-roll spec notes). In this pass, the linked webcam clip is a normal full-screen clip you can cut to/from — not yet a resizable picture-in-picture element. Revisit once multi-layer tracks land.

---

## 3. Schema Additions

`docs/project-schema.md` — `Clip` object, new optional field:

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `linkedClipId` | string\|null | no | null | If present, this clip was recorded as a synced pair with another clip. Split/trim/delete operations mirror onto the linked clip at the same relative position by default, unless explicitly unlinked. |

No other schema changes — both clips are otherwise ordinary `Clip` objects (the webcam one just happens to have a tall/full-frame `sourceUri` instead of whatever the bubble crop was).

**Recording output convention:** a dual-stream recording produces two files (e.g. `recording_001.mp4` + `recording_001_webcam.mp4`) plus enough metadata for the importer to set `linkedClipId` on both resulting `Clip` objects automatically when the recording is brought into a Faditor project — don't make the user manually link them.

---

## 4. New / Touched Code

| File | Responsibility |
|---|---|
| `RecordingClock` (new) | Single source of truth for pause/resume state and rebased presentation timestamps. Both encoder pipelines subscribe to/query this — see Decision 3. |
| `DualEncoderCapabilityChecker` (new) | Wraps `getMaxSupportedInstances()` (and/or a safe try-configure-and-release probe) to decide whether the opt-in toggle should even be shown. |
| Camera capture session setup (locate per Section 0) | Add the third output Surface/target to the existing `CaptureRequest`, feeding a new dedicated encoder pipeline. |
| New raw-webcam encode pipeline | A second `MediaCodec`+`MediaMuxer` (or software-encoder fallback per Decision 2) writing the full-resolution webcam stream, driven by `RecordingClock`. |
| Audio capture loop (locate per Section 0) | Route the same PCM buffer to both encoders (Decision 4). |
| Recording settings UI | Opt-in toggle, hidden/disabled when `DualEncoderCapabilityChecker` says no. |
| Importer (wherever recordings become Faditor project clips) | Set `linkedClipId` on both resulting `Clip` objects per the Recording output convention above. |
| `Timeline.java` / `EditScriptApplier.java` | Extend split/trim/delete handling: if a clip has `linkedClipId`, mirror the same operation onto the linked clip at the same relative position by default. Add an explicit unlink action that clears `linkedClipId` on both. |

---

## 5. Implementation Phases

### Phase 0 — Capability check only
- Build `DualEncoderCapabilityChecker`, wire the opt-in toggle to show/hide based on it.
- **Done when:** the toggle correctly reflects actual device support — verify on whatever physical device(s) are available; this genuinely varies by hardware, not just configuration, so an emulator check isn't sufficient.

### Phase 1 — Second stream, no pause/resume yet
- Add the third Camera2 output target and the new encode pipeline, for a simple continuous start-to-stop recording only (no pause handling yet).
- **Done when:** a continuous recording produces two files of matching duration and frame count.

### Phase 2 — Shared pause/resume clock
- Build `RecordingClock`, wire both pipelines to it, remove any independent pause tracking from the new pipeline.
- Stress-test: a recording session with several pause/resume cycles in quick succession.
- **Done when:** both output files have identical total duration and identical segment boundaries after the stress test — check this programmatically (compare durations/frame counts), don't rely on eyeballing playback.

### Phase 3 — Audio duplication
- Route the shared PCM buffer to both encoders.
- **Done when:** both files have synced, correct audio independently.

### Phase 4 — Editor import + linked-clip behavior
- Wire the importer's `linkedClipId` assignment and the mirrored split/trim/delete + unlink behavior.
- **Done when:** importing a dual-stream recording produces two correctly linked clips in the project, trimming one mirrors onto the other, and unlinking lets them diverge.

---

## 6. Constraints Carried Over

Same as the other specs: no unsolicited comments/commits, compile check after each phase, update `tasks/HANDOFF.md`, `tasks/RECORDING_HANDOFF.md`, and `docs/project-schema.md` after each phase.

## 7. Out of Scope Here

- Floating/resizable picture-in-picture placement of the linked webcam clip — depends on the not-yet-built multi-layer video track system.
- Any visualizer-related work — separate spec (`feature-visualizer-studio-spec.md`), unrelated dependency chain.

# Feature Spec: Synchronized Dual-Stream Recording — Screen + Raw Webcam (for Claude Code)

**Status:** Ready to implement
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

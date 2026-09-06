# TODO — SPEC_C: Export a single frame as JPG or PNG

## The one decision that matters
**Reuse the video-export compositor by running a TRUNCATED copy of the same media3
export, then pull the frame out of the encoded file.** No screenshot of the preview
(the trap), no hand-rolled GL replay (a second compositor = the drift this project
keeps getting bitten by). The frame comes out of the exact pipeline a video export
runs: same `buildComposition`, same `assembleClipVideoEffects`, same
`CompositeExportOverlay`, same Transformer settings.

### Why truncated export instead of a full one
- Full export to reach T decodes+encodes the whole project (JoyRaptor's 46s project
  ≈ 15 min) — a frame export nobody would use.
- Truncated = [black filler of exactly `cursor` ms] + [the covering item, its
  MediaItem END clamped to target+lookahead]. Effects see the SAME
  presentationTimeUs values they see in a full export, so every effect, overlay,
  caption, waveform and PiP lands identically. Seconds, not minutes.

## Plan
- [x] ExportManager: record per-item editor spans while `buildComposition` runs
      (same variables as the item sites — no re-derived arithmetic), find the
      covering item for a target editor ms, map editor→composition time.
- [x] ExportManager: `exportSingleFrame(project, timeMs, PNG|JPG, listener)` —
      second build pass that skips items before the covering item, clamps its end,
      runs Transformer to a cache mp4, extracts the frame with
      MediaMetadataRetriever (OPTION_CLOSEST), compresses, writes where video
      exports go (internal Faditor dir or SAF copy), deletes the temp mp4.
- [x] Extend `copyTempToSaf` mime switch for image extensions (png/jpg).
- [x] ExportService: accept single-frame extras (time + format), indeterminate
      notification, same completion/error broadcasts.
- [x] Export dialog: "single frame" option — format spinner (PNG default, JPG),
      timecode field pre-filled with the playhead, out-of-range timecode REJECTED
      with a message (never clamped), quality/loudness/clean-audio greyed out,
      resolution stays live.
- [x] Caption-cache check (spec asks for a report): read CaptionExportRenderer +
      CaptionTextureCache, confirm no monotonic-clock state breaks a single frame.
- [x] Verify: `./gradlew assembleDefaultDebug --console=plain` → BUILD SUCCESSFUL.
      Say compile-verified vs device-verified honestly.

## Review (SPEC_C delivery notes)

**Which composing path.** The video export's own media3 Transformer pipeline —
`buildComposition` → `assembleClipVideoEffects` (grade, crop, spine FX/transform, opacity,
PiP blends, adjustment layers, text-FX) + `CompositeExportOverlay` (text/captions/
waveforms/sprites). The full-frame composer is that pipeline, not one method; the spec's
"`getBitmap(...)` composes the clip picture + grade + PiPs" is not literally true —
`getBitmap` draws overlays on a transparent bitmap, the picture/grade/PiPs are GL effects
around it. So the frame is produced by running that whole pipeline for one frame:
pass 1 records which EditedMediaItem covers the requested editor time, pass 2 emits only
that item (preceded by a black pad of exactly its composition start, end-clamped just past
the frame), the tiny mp4 is encoded, and the frame is pulled with MediaMetadataRetriever.
**One composer, called twice.** A covering TRANSITION item is deliberately NOT clamped:
GlTransitionExportEffect takes the item duration at build time and derives the blend
progress from it — truncating would re-time the mix.

**Caption caches (spec question).** `CaptionExportRenderer` holds only fit caches keyed by
(transcript, box, style) plus a stored `frameSourceMs`; emphasis/animation are pure
functions of the render call — no monotonic-clock state, nothing to reset.
`CaptionTextureCache` is preview-side only (not in the export path). The clamp pass renders
the covering item from its own start anyway — the same monotonic run a video export makes.

**Output location.** `generateOutputPath(project, "png"/"jpg")` — same custom-filename +
internal-Faditor-dir or SAF-copy flow as video exports; `copyTempToSaf` learned image
mimes. The intermediate mp4 always lives in cache and is always deleted.

**Pixel identity.** Identical effect chain and identical presentationTimeUs at the
requested time; the pixels differ from a video export's same frame only by H.264
encode-generation rounding (one encode/decode round trip, different GOP position) —
typically a few LSBs, invisible. PNG saves the decoded frame as-is; JPG at quality 90.
Frames are opaque (H.264 has no alpha) so JPG loses nothing.

**Timecode grammar.** `TimeFormatter.parseTimecodeMs` — `[h:]mm:ss[.fff]`, plain seconds,
optional `s` suffix; unparsable/negative → -1 → inline dialog error; out-of-range → inline
error naming the project length. Never clamped. Harness-pinned:
`bash tools/jvm-harness/run-frame-time.sh` → all cases pass.

**Verdict.** `./gradlew assembleDefaultDebug --console=plain` → BUILD SUCCESSFUL (fresh
compile; artefact timestamps newer than sources; new symbols confirmed via javap).
**Compile-verified + parser-harness-verified. NOT device-verified** — no phone attached.
Device checks still owed: covering-item mapping on a project WITH transitions (the §2d
editor↔composition model), a playhead inside a transition, and the extracted-frame
timestamp at 30fps. `run-caption.sh` is red from another lane's staged FontLibrary change
(FLog stub signatures) — pre-existing, not touched. run-fx.sh: ALL GREEN.

## Adversarial review (2026-09-05, second pass over my own build)

**BUG FOUND AND FIXED — transition mapping used a ½ ratio (would have shipped).** I had
recorded a transition item's editor span as `[S(B)-eff, S(B)+eff)` (length 2·eff) against
its composition length `eff` and mapped linearly — so a playhead mid-crossfade resolved to
HALF the blend's progress: a visibly under-faded frame. Verified against
`FaditorEditorActivity.updateScrubTransitionPreview` (the blend plays 1:1 across the
outgoing clip's tail, progress = (T-start)/duration) and against the §2d clock
(`editorTimeOffsetFor`, measured on device 2026-08-03). The mapping is now PIECEWISE
(`FrameExportDirective.noteItem(…, transitionLegMs)`):
- A-side `[S(B)-eff, S(B))` → comp = T (1:1) → blend progress (T-e0)/eff — exact vs both
  the preview scrub and the export's own clock (algebra checked against the §2d identity
  comp = T - Σtransitions for both regions).
- Incoming-head `[S(B), S(B)+eff)` → comp = T - eff → the blend's second half — the B leg
  stays time-correct; see the irreconcilable case below.

**BUG FOUND AND FIXED — RGB_565 frames.** `getFrameAtTime` can return RGB_565 on some
devices; compressing that to PNG bakes banding into a "lossless" file. Now normalized to
ARGB_8888 before compress.

**Verified safe during review** (was assumed while building): `getVisualDurationMs() =
trimmed + loopBefore + loopAfter` (tiling assumption holds); `buildWaveformSlots` skips on
null waveform data (pass 1's empty cache cannot crash); `buildClipItem` DOES set
`durationUs` explicitly (media3's default is TIME_UNSET for video items — the recorded
cursor arithmetic is real); build-time duration consumers are limited to
GlTransitionExportEffect (hence transitions stay full-length).

**Inherited export behaviours the frame faithfully reproduces (criterion #1 = export
parity), flagged for the record — NOT changed in this lane:**
1. Overlays on a loop-before clip's MAIN item evaluate `loopBefore` ms early in the file
   (the overlay offset carries the transition corrections but no loopBefore term).
2. A playhead inside the incoming clip's head region after a transition has no plain frame
   in the file (that content is the blend's second half); the frame returns the blend with
   a time-correct B leg and an A ghost.
3. Transition items carry no overlay pass in the export, so a frame inside a crossfade
   shows no text/captions — matching the video file, not necessarily the preview.
4. The exported FILE is shorter than the timeline by the total transition durations —
   file position ≠ playhead position; the frame addresses the PLAYHEAD clock (§2d), which
   is what the typed timecode and prefill mean.

## SPEC A lane (2026-09-05) — pointer

Adversarial review of SPEC A (rotation beyond 360) is complete; the full delivery record
and findings live in `tasks/specs/SPEC_A_rotation_beyond_360.md` → "Delivery record"
(this file's earlier SPEC A section was overwritten by this lane's rewrite, so the spec
file is the durable record). Summary: build SUCCESSFUL, rotation grammar harness ALL
GREEN on the current tree, storage/preview/export verified raw end to end. One decision
requested from JoyRaptor (F1 in the spec file): rotation sliders stay bounded [-180, 180]
while storage now holds raw winding — a slider touch after typing 720 silently collapses
it. Status: compile-verified + harness-verified, NOT device-verified; the device run was
blocked by a pre-existing app bug (the "Opening project…" overlay never dismisses on
projects with missing media — FaditorEditorActivity.java:484).



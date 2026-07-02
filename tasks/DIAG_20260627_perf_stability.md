# Diagnosis 2026-06-27 — systemic performance/stability + failed export

> **MILESTONE 2026-06-27: first video shipped.** Export works end-to-end with native-portrait output,
> consistent captions/crop, and fixed transitions. Now in autonomous hardening (see "Round 5" below).

## Round 8 — export crash-hardening (2026-06-28, ADDITIVE guards only)
Defensive pass over the export pipeline to prevent crashes on edge inputs / native-resource leaks. **All
ADDITIVE — success/failure semantics, timing, and output for valid inputs are unchanged.** Build green.

Guards added:
- `CompositeExportOverlay.getBitmap` (caption block): null-styleId guard — `styleId != null && !"hidden".equals(styleId)`
  before `styleId.equals(...)`. `Clip.getCaptionStyleId()` / `captionStyleAtClipMs()` are not `@NonNull`; a null
  would have NPE'd per-frame and aborted the encoder thread. (`CaptionStyle.byId(null)` was already null-safe.)
- `CompositeExportOverlay.getBitmap` (text / caption / waveform draw sections): each non-essential overlay draw
  section wrapped in `try { … } catch (Throwable)` with a once-only `FLog.w`. On failure the canvas is restored
  via `restoreToCount(savedCount)` (captured cheaply up front) so a throw mid-draw can't imbalance the matrix
  stack, and a single bad overlay/caption/waveform no longer crashes the whole export. No per-frame
  allocation/IO added — only one `int` save-count + the try/catch. Core media pipeline is NOT wrapped.
- `ExportManager.extractStillFrameForLoop`: early `clip.getSourceUri() == null` guard → returns null (skip loop
  extension) instead of feeding null into the retriever.
- `ExportManager.getSourceWidth` / `getSourceHeight`: early `clip.getSourceUri() == null` → return 0 (matches the
  existing failure return), avoiding a null into `setRetrieverDataSource` (`@NonNull` param).
- `ExportManager.getOrCreateSilenceFile`: converted the raw `FileOutputStream` to try-with-resources so a write
  failure can't leak the native file descriptor (previously only closed on the happy path).

Flagged but NOT touched (ambiguous or already safe — would risk behavior change):
- `MediaMetadataRetriever` handling is already correct: `ExportManager` releases its thread-local retriever in a
  `finally` around `buildComposition`; `GlTransitionFrameOverlay` releases its per-instance retriever in
  `release()`. No leak.
- All `/ duration`, `/ width`, `/ height`, `/ speed` divides in `ExportManager`, `GlTransitionShaderProgram`,
  `GlTransitionFrameOverlay` are already guarded by `Math.max(1L,…)` / `Math.max(0.1f,…)` etc. — left as-is.
- `CaptionExportRenderer` bounds (`phrases.get(wordPhrase[active])`) are internally consistent (active is range-
  checked against `wordPhrase.length`, phrases populated whenever words exist) — no guard added to avoid
  changing layout for valid transcripts.
- The audio-clip-caption sub-loop shares the wrapped caption try/catch; its individual renders were already
  null/recycled-checked.

## Round 7 — fMP4 export fix DEVICE-VERIFIED (2026-06-28, Note 9)
The additive trimmed-fMP4 fix (ExportManager `resolveSeekableSourceUri` + ExportService off-thread remux warm,
see Round 5) is now CONFIRMED on device. Reproduced the exact failing state (sandbox project, 9:16 canvas,
non-zero in-points = 4670 on raw FadCam fMP4) → before the fix this threw `IllegalClippingException: not
seekable to start`; AFTER the fix the export SUCCEEDS: `Faditor_20260628_091222.mp4`, **1080×1920 native
portrait, rotation 0**, 26.9s, h264 + aac stereo, captions rendered correctly. No new error log written.
Normal/imported projects are unaffected (collectSourcesNeedingRemux returns empty → export fires immediately,
unchanged). ✅ Export robustness items (orientation + trimmed-fMP4) are both done + device-verified.

## Round 6 — on-device verification (2026-06-28, backup phone SM-N960U / Note 9)
Installed the latest build on the backup phone and tested the sandbox project (bdd51919).
- ✅ App launches; editor opens a 9:16 project with no crash (validates the canvas-rect caption sizing,
  crop-size cache, frame-accurate-scrub changes load cleanly).
- ✅ **Orientation fix CONFIRMED.** Exported the project (canvas forced to 9:16); `ffprobe` of the output =
  **1080×1920, NO rotation side-data, NO rotate tag** → natively portrait (was 1546×870 + rotate(-90) before
  `setPortraitEncodingEnabled(true)`). Verified on an OLDER device, so portrait encoding is broadly compatible.
- ✅ Export completes end-to-end on the Note 9 with all changes (no regression). Frame check: upright portrait,
  fills frame, caption rendered at correct canvas size.
- ✅ **Durable export error log CONFIRMED** — it captured a real failure (below) with full project + stack.
- ✅ **FIXED: exporting a TRIMMED raw fragmented-MP4 clip** (`ClippingMediaSource$IllegalClippingException:
  not seekable to start`). Root cause unchanged: `buildClipItem` uses Media3
  `ClippingConfiguration(startPositionMs=inPoint,...)`, which needs a seekable source; raw FadCam fMP4 isn't
  seekable to a non-zero start. Fix is **ADDITIVE — the normal/imported export path is byte-for-byte unchanged**:
  (A) `ExportManager` gained `resolveSeekableSourceUri(Clip)` + a lazy `exportRemuxer` field. It is a PURE LOOKUP
  (never blocks/remuxes): if a cached remuxed (faststart) copy already exists for a `file://` source that
  `needsRemux`, it returns that seekable file's URI; otherwise it returns the original URI. It is wired in ONLY
  at the MediaItem source `setUri(...)` sites of the VIDEO branches of `buildClipItem`, `buildTransitionItem`
  (outgoing clip), and `buildLoopExtensionItem`. Image branches, `buildStillLoopExtensionItem`, the GL-transition
  `nextClip` source, and audio clips are untouched. (B) `ExportService.startExportInternal()` warms the remux
  cache OFF the main thread BEFORE export: `collectSourcesNeedingRemux()` gathers unique `file://` video sources
  (skips images, dedupes) where `needsRemux && !hasRemuxedVersion`. If the list is EMPTY (the common
  normal/imported case) it calls `exportManager.export(project)` immediately as before — no thread, no behavior
  change. Only when raw-fMP4 sources are present does it `remuxSync` each on a single-thread executor (foreground
  notification stays up), then posts `export(project)` back to the main thread. Executor is shut down in
  `onDestroy`. Net: one-time, cached background remux for raw-fMP4 projects only; builders then point at the
  seekable copy. Compile-green (BUILD SUCCESSFUL).

## Round 5 — autonomous hardening (post-ship)
Working through the remaining backlog with the phone off (compile-verified via watcher; install skipped).
- [x] **Durable export error log.** `ExportManager.writeExportErrorLog()` now writes a timestamped report
  (project summary + clip list + full stack trace) to `<externalFiles>/faditor_export_errors/` on any export
  failure (both the Transformer onError path and the start-up catch). Previously failures left no trace.
- [x] **Activity-leak review (static audit) — no permanent leak found.** No static Activity/Context/View
  refs; `FaditorEditorActivity` registers no BroadcastReceivers; `SharedPreferencesManager.getInstance()` uses
  `getApplicationContext()`; `ExportService.pendingProject` is cleared on consume (line 156). Conclusion: the
  "5 live activities" in meminfo was GC starvation from the undo-snapshot memory bomb (up to ~480 MB of JSON),
  which kept destroyed activities from being collected. That root cause is fixed (undo cap/throttle + async
  save), so destroyed activities now collect normally. No code change — nothing to "fix." Revisit with a real
  heap dump (LeakCanary) only if meminfo still shows >2 activities after heavy use on the new build.
- [x] **Undo coverage — caption style-keyframes + overlay move/range/keyframe drags now done too.**
  - [x] **Filter / color (EffectStack)** — `EditActions.EffectStackAction`; `FilterBottomSheet` got an
    `onClosed()` hook so the whole grading session = one undo entry (recorded on dismiss if changed). Added
    `EffectStack.equals()/hashCode()` so "changed" is detected by value.
  - [x] **Opacity keyframes** — `EditActions.OpacityKeyframesAction`; captured on opacity-drawer open,
    recorded on close if the keyframe list changed (same proven open/close pattern as filter).
  - [x] **Caption style change** (`applyCaptionStyle`, video + audio clips) — via new generic
    `EditActions.LambdaAction` (before/after closures; works across Clip & AudioClip). Records one entry per
    style/enable change.
  - [x] **Visualizer add / remove** (`addWaveformVisualizer` + both delete sites) — `LambdaAction`
    add↔remove on the timeline's waveform overlay list.
  - Also: `refreshEditorAfterUndoRedo()` now calls `syncTimelineOverlays()` + re-binds captions so caption/
    overlay/visualizer undo/redo is reflected in the UI (it only restored data + timeline before).
  - [x] **Caption position** (`applyCaptionPosition` + both overlay `onMoved()` callbacks, video + audio)
    — `LambdaAction` before(getCaptionCenterX/Y captured pre-write)→after via `setCaptionCenter`; no-op guarded.
  - [x] **Caption size** (`applyCaptionSize`, video + audio) — `LambdaAction` before/after on
    `getCaptionSizeFraction`/`setCaptionSizeFraction`; no-op guarded.
  - [x] **Caption hide (enable→disable)** via long-press (both `onLongPressed()` callbacks, video + audio)
    — `LambdaAction` toggling `setCaptionsEnabled` back. (Caption ENABLE stays covered by `applyCaptionStyle`.)
  - [x] **Text/image overlay ADD** — image add recorded immediately in `onOverlayImagePicked`; text add
    recorded on commit (OK with non-empty text) in `showTextOverlayEditor`, guarded by a new
    `textOverlayAddRecorded` identity-set so a never-committed placeholder (cancel/empty) records nothing and
    re-editing doesn't double-record. `LambdaAction` add↔remove on the timeline's text-overlay list.
  - [x] **Text/image overlay DELETE** — recorded at the three real user-delete sites: timeline long-press
    (`onOverlayLayerLongPressed`), image-editor Delete button, and text-editor Delete button (only when the
    overlay had been committed). Placeholder cleanup paths (empty OK / cancel) intentionally record nothing.
  - [x] **Caption style-keyframes** (Clip only — AudioClip has no style-keyframe track) — new
    `EditActions.CaptionStyleKeyframesAction` (deep-copies the `CaptionStyleKeyframe` list before↔after, mirrors
    `OpacityKeyframesAction`) + `Clip.setCaptionStyleKeyframes()`. Wired at all three edit sites: style-chip add,
    hide-chip add ("hidden"), and `deleteCurrentCaptionStyleKeyframe`. No-op guarded (list equality). There is no
    user "move keyframe" gesture for caption-style KFs (timeline only renders them; no drag handler).
  - [x] **Text/image overlay MOVE / time-range / keyframe drags** — new `EditActions.OverlayTransformAction`
    over a `TextOverlayItem.TransformSnapshot` (deep copy of center/size/rotation/opacity/startMs/endMs + the
    KeyframeSet; added `KeyframeSet.copyFrom`). Canvas drag/pinch: snapshot at `ACTION_DOWN`, recorded at
    `ACTION_UP` via new `TextOverlayLayer.Callback.onOverlayManipulated` (default method). Timeline range-edge &
    keyframe drags: snapshot at a new `EditorTimelineView.Listener.onOverlayDragStart` (fired before any mutation
    in `onDown`), recorded in the existing `onOverlayRangeFinished` / `onOverlayKeyframeMoveFinished`. ONE undo
    step per gesture (never per-frame); all no-op guarded via `TransformSnapshot.matches`. Also:
    `refreshEditorAfterUndoRedo()` now rebuilds the on-canvas `overlayLayer` from the model so overlay
    add/delete/move/keyframe undo is reflected in the preview (was timeline-only before).
  - NOTE: caption + overlay undo are compile verified (BUILD SUCCESSFUL); interactive undo/redo
    spot-check still pending (trivial: move a caption or add a text overlay, then tap undo).
- [ ] Crop-during-transition geometry (GL effect crops both clips) — needs device/export to verify; deferred.
- [ ] Transcript dedup — DATA-TOUCHING, needs user confirm before auto-running on their project; deferred.
- [ ] Out-of-process export — would isolate export OOM, but breaks per-process LocalBroadcastManager progress
  delivery and needs device testing; deferred.

Active project on device: `27221664-21e9-4e9d-8fd7-e8884bb0eb55` (21 video clips, 2 audio, 2 transitions).
Symptoms reported: ANR/"app not responding" on trim, import, untrim, and applying transitions;
a failed export that "got decently far but quit before the new edits"; general slowness/instability.

## Evidence gathered (device)
- **~20 ANRs today 15:47–16:35 alone** (`/data/anr` root-only, dropbox bodies "contents lost"). Chronic for days.
- `dumpsys meminfo com.fadcam.beta`: **Dalvik (Java) heap = 172 MB**, Native = 126 MB, Graphics = 56 MB,
  **TOTAL PSS = 546 MB**, **Views = 1862**, **Activities = 5** (back stack only holds 2 → ~3 leaked/pending-GC).
- logcat (app pid): repeated `Skipped 85–1377 frames`, `Davey! duration=803ms / 1286ms / 2322ms` → multi-second
  main-thread frames.
- `project.json` = **2.4 MB**; **21 clips, 23 transcripts, 7,110 words** (clip[2] alone = 3,818 words; clips 2–5
  each carry 3 largely-duplicate transcripts). Transcripts dominate the file size.
- Export error file `/sdcard/Android/data/com.fadcam.beta/files/faditor_export_error.txt` is **stale (Jun 22)** —
  no current writer exists in the code, and `ExportService.onExportError` only notifies/broadcasts (no file).
- All 11 `project://assets/<uuid>.mp4` referenced by clips EXIST in the project assets dir → export failure is
  NOT missing assets. Both transitions (clipIndex 10, 15, GL_SHADER) are AFTER the failure point.

## Root causes (systemic / architectural)
1. **Snapshot-based undo = central flaw.** `UndoManager.recordAction` called
   `snapshotRestorer.captureSnapshot()` = `ProjectStorage.toJson(project)` (the FULL 2.4 MB project) on the
   **UI thread for every edit**, and retained up to **`maxHistory=200`** such strings → up to ~480 MB of JSON
   in heap (explains 172 MB Dalvik + GC thrashing). `undo()` also captured a snapshot every time.
   In-session undo doesn't even need these snapshots (it uses precise `EditAction.undo()`); they exist only for
   cross-session undo.
2. **Synchronous project save on the UI thread per edit.** `saveProjectNow()` → `ProjectStorage.save()`
   serialized+wrote 2.4 MB (plus undo-history of N snapshots) synchronously after nearly every trim/transition/
   move/import. Comment claimed "fast for small JSON" — false for this project.
3. **Activity/memory leak.** 5 live Activity objects (should be 1–2). Leaked editor activities each retain a
   player/decoder/timeline-bitmaps/undo-stack → compounds memory the more you use the app. (Root holder not yet
   pinned — needs an Activity-instance heap dump; partly amplified by GC starvation from #1.)
4. **Transcript bloat + duplicates** drive the 2.4 MB size, which makes #1/#2 catastrophic. Likely also the bulk
   of the 1862 Views (per-word transcript UI) once multiplied across leaked activities.
5. **Export runs in-process** (`ExportService`, no `android:process`) under this memory pressure → Media3
   Transformer's decoder/encoder/GL buffers push PSS over the limit → **process killed mid-export (OOM)**.
   That's why it "got decently far then quit" with no ExportException persisted (an OOM kill skips onExportError).

## Fixes applied 2026-06-27 (BUILT GREEN, pending on-device verify)
- `ProjectStorage`: added a serial `ioExecutor`; split serialize (caller thread, no CME) from the atomic disk
  write. New `saveAsync` / `saveUndoHistoryAsync` (UI thread only serializes; write is backgrounded) +
  `flushPendingWrites()`.
- `FaditorEditorActivity.saveProjectNow(false)` and the debounced autosave now use the **async** save; forced
  saves (onPause/onDestroy, `saveProjectNow(true)`) stay synchronous and call `flushPendingWrites()` so nothing
  is lost when leaving the editor.
- `UndoManager`: `DEFAULT_MAX_HISTORY 200 → 50`; **throttled snapshot capture** to ≥1500 ms apart (in-session
  undo still precise via actions; only cross-session granularity coarsens); `undo()` no longer serializes a
  snapshot for in-session entries (only for disk-loaded snapshot-only entries).
- Net effect: per-edit UI-thread work drops from ~2× full-2.4 MB serialize + disk I/O (+ unbounded snapshot
  retention) to at most one serialize, written off-thread, with bounded/throttled snapshots.

## Round 2 fixes (2026-06-27 evening) — editor UX bugs (BUILT GREEN, pending verify)
- **Crop can no longer nudge overlays** (#3): `enterCropMode` now hides the visualizer
  (`waveformOverlayView`) and both caption overlays alongside text overlays, restoring their pre-crop
  visibility on exit. (Previously only `overlayLayer`/text was hidden.)
- **Crop preview consistency** (#4, partial): the crop-zoom preview and overlay geometry are derived from
  `player.getVideoSize()`, which is 0/stale right after a seek and differs across clips of different
  resolutions → crop "looked different" and captions resized on scrub. `onVideoSizeChanged` now re-runs
  `updatePreviewTransforms()` so the preview converges to the correct (export-matching) result once the real
  frame size is known. REMAINING: caption font = `sizeFraction * captionView.height()`; if the view's
  effective rect still tracks per-clip video size, sizing should key off the CANVAS/stable source dims for
  true WYSIWYG.
- **Trim edge preview moved to the main video** (#2): dragging a trim handle now seeks the main player to the
  exact in/out frame (EXACT seek, throttled ~45 ms) instead of the floating thumbnail bubble the finger
  covered. The bubble (`drawTrimEdgePreview`) is retained in code but no longer activated.
- **Reorder minimap scrub-to-navigate** (#1): in reorder mode you can now drag along the overview minimap
  WITHOUT grabbing a clip to pan the (overflowing) block row into view first, then grab. Builds on the
  existing reorder scroll/minimap-jump.

## Undo robustness (#5) — DIAGNOSED, fix pending
The undo stack is NOT cleared anywhere (`UndoManager.clear()` is never called). There are 30 `recordAction`
sites, but they cover only structural ops (trim, crop, speed, rotate, flip, add/delete/split/duplicate,
reorder, audio). The HIGH-FREQUENCY edits record NOTHING: filters/color (`EffectStack`), captions
(toggle/style/position/size/style-keyframes), opacity keyframes, text overlays (add/move/edit/delete),
visualizer (add/style/remove/move), transcript edits. So undo reverts an older *recorded* action while the
user's latest (unrecorded) mistake stays — matching "I undo and something far away changes," and the stack
showing only ~4–5 items after long sessions. FIX: add per-op `EditAction`s + `recordAction` for each
uncovered mutation (consistent with the existing pattern; memory-cheap vs. snapshots). Sizeable, careful pass.

## Round 3 — caption drop + export orientation (2026-06-27 late, empirical via pulled export)
Pulled the latest export (Faditor_20260627_175622.mp4, in Trash) and extracted frames.
- **Audio captions stop mid-song — ROOT CAUSE FOUND + FIXED.** Empirically: caption "STRIVING" shows @918s
  (clip[7]) but is GONE @922.6/923.5s (clip[8]+). `ExportManager.assembleClipVideoEffects` only creates the
  `CompositeExportOverlay` (which draws audio captions) when `hasOverlays` is true — and that flag checked only
  the VIDEO clip's own text/captions/visualizer, NOT whether a captioned AUDIO clip overlaps this clip. So any
  clip with no overlays of its own (captions off, no visualizer) dropped the audio caption. FIX: add an
  audio-caption-overlap test to `hasOverlays`. [DONE, built green]
- **`Transcript.indexAtOrBeforeTime` early-break** — bailed at the first non-monotonic word; Whisper word
  timestamps are sometimes out of order, which would freeze the active word. FIX: scan all words, no break.
  [DONE] (This transcript happened to be monotonic, so it wasn't the cause here, but it's a real latent bug.)
- **Export orientation is portrait-BY-ROTATION-FLAG.** ffprobe: coded 1546x870 (landscape) + `rotation=-90`
  → displays 870x1546 (9:16). Players that honor the flag look correct (verified: AI clips fill the portrait
  frame, e.g. 978s). But players/platforms that IGNORE the flag show it sideways / letterboxed — the likely
  "not sized to fill the screen / sizing bleeds into export" report. NOT yet fixed: needs the Transformer
  encoder to emit natively-portrait dimensions (or verify the canvas Presentation orientation). [TODO]
- Export crop/fill itself looked CORRECT in this render (sampled clips 945–1003s fill the portrait frame, no
  letterbox/pillarbox detected). The crop INCONSISTENCY the user sees is preview-side (round-2 onVideoSizeChanged
  recompute helps; caption font still keys off the per-clip video rect — see round 2 REMAINING).

## Preview frame accuracy (beat alignment) — TODO
Timeline scrubbing seeks with CLOSEST_SYNC (keyframe) for speed, so you can't land on an exact frame to line a
cut/transition to a music beat. Options: EXACT seek when paused/slow-scrubbing, or frame-step (±1 frame) buttons.

## Round 4 — transition glitch (audio bleed + aspect break) FIXED + verified
User reported a transition where audio briefly returned AND the aspect ratio broke. Confirmed empirically on
the kept export (Faditor_20260627_175622.mp4) at transition[0] (clipIndex=10, ≈952s):
- **Audio bleed**: mean volume `-19 dB` before → **`-8.9 dB` mid-transition (952.3s)** → `-18 dB` after — a
  ~10 dB spike. Root cause: `ExportManager.buildTransitionItem` (video branch) never applied the outgoing
  clip's mute/volume, so a muted clip's original audio played over the ~600ms overlap. FIX: mirror
  buildClipItem — `setRemoveAudio` when image/muted, else apply Sonic(speed)+Volume. [DONE, green]
- **Aspect break**: mid-transition frame is a small letterboxed LANDSCAPE band centered in the portrait frame
  (vs full-frame portrait elsewhere). Root cause: `GlTransitionShaderProgram.configure()` returns the INPUT
  (source) size, and the canvas `Presentation` was guarded out for transition items (`!isTransitionItem`), so
  the segment wasn't scaled to the canvas. FIX: apply `Presentation` to transition items too. [DONE, green]
- REMAINING (documented, not done): the transition segment still doesn't apply CROP to either clip (the GL
  effect renders the incoming clip uncropped), so a cropped-from-landscape clip will letterbox during the
  600ms overlap instead of crop-filling. Full fix = pass both clips' crop rects into GlTransitionExportEffect
  and apply them in the shader. The Presentation fix removes the size/aspect "mess up"; this remains a content
  nicety for kept transitions.

## Still TODO (recommended, not yet done)
- **Restart the app** to load these fixes AND clear the 5 leaked activities + ~480 MB of stale undo snapshots,
  then retry export — likely succeeds with the reduced baseline.
- **Transcript dedup** (data-touching → confirm first): clips 2–5 hold triplicate transcripts; collapsing to the
  active one would shrink project.json dramatically and cut every serialize cost.
- **Activity-leak root cause**: capture an Activity-instance heap dump (LeakCanary or `hprof`) to find the GC
  root holding old `FaditorEditorActivity` instances.
- **Export hardening for "flawless"**: (a) write a durable, detailed export-error log on every failure;
  (b) consider running `ExportService` in a separate `android:process` so an export OOM can't be amplified by
  the editor's heap (and vice-versa); (c) verify `project://` / SAF `content://` sources all resolve at export
  time and fail with a clear per-clip message instead of a generic Source error.
- Deeper: move undo off full-project JSON snapshots toward action-only or diff-based persistence.

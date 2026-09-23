# TODO — 48-minute export truncation + export UX (2026-09-21)

## Diagnosis (proved from on-device `faditor_export_errors/*.txt`, NOT theory)

- Project `a32d24e2` = 24 clips, 48.45 min. Clips 2–21 are windows into ONE 2.2 GB
  fragmented-MP4 screen recording (`The_woman_and_the_5.mp4`, 1348 `moof` boxes).
- Failure 1+3 (04:09, 13:09, both ≈30:35 output): Media3 watchdog —
  `Abort: no output sample written in the last 120000 ms`. Muxer starves exactly at the
  clip18→clip19 seam (composition 1834599 ms = source 1872992 ms). Decoder cannot
  produce samples past ~31:13 of the source within 120 s (already raised from 10 s
  default on 2026-08-26 — still not enough). Watchdog timeout is a ceiling, not a fix.
- Failure 2 (11:32, ≈28 min output): `FileNotFoundException:
  cache/remuxed/The_woman_and_the_5-remuxed-1434.mp4 ENOENT` — the 2.2 GB remuxed copy
  in `getCacheDir()` was evicted by the system under storage pressure MID-EXPORT.
  Cache dir is the wrong home for a multi-GB file an hour-long export depends on.
- Partial files stay in `FadCam/Faditor/` (written in place, deleted on error ONLY for
  SAF-temp mode) → user mistakes short failures for finished exports.
- Progress = raw Media3 curve (non-linear) + notification/error channel is
  IMPORTANCE_LOW → missed failure notices; finalize phase (loudness/SAF, minutes on
  GB files) has no UI state at all.

## Fix list (revised after review — review was right on the headline gap)

- [ ] 0. DIAGNOSTIC FIRST: log resolved URI (remuxed vs original) per item in
  `buildClipItem`, + per-seam open/seek/first-frame timing, + decoder type at each
  seam (HW vs software fallback — ~2 HW slots on this phone). The probe (item 3) runs
  cool, so it doubles as the COLD BASELINE for every window incl. clip 19's; the
  export's seam logs are the HOT measurement. Cold-fast/hot-slow = thermal/contention
  → watchdog sized from data + visible "Positioning clip 19 of 24…" state. Cold-slow
  = intrinsic cost → per-clip pre-trim cache gets pulled INTO THIS PASS (cut each
  window to its own faststart file in PREPARING; keyed like the remux). No public
  Media3 hook exists to pre-warm the next item — do not hunt for one; pre-trim is the
  structural option. One-pass completion is the goal; a nicer error is not a fix.
  (`ExportManager.java`)
- [ ] 1. Remux cache → `files/faditor/remuxed/` (persistent) + migrate existing cache
  entries (avoid a one-time 2.2 GB re-remux) + prune rule: original-gone OR unused N
  days, never a live `.part`. (`FragmentedMp4Remuxer.java`)
- [ ] 2. Warm phase verifies remux exists right before dispatch; re-remux once or fail
  fast. Carries the PREPARING label (re-remux takes minutes on this file). NOTE: this
  cannot prevent MID-export deletion — fix 1 is the real fix, this is belt-and-suspenders.
  (`ExportService.java`)
- [ ] 3. Pre-flight probe on the RESOLVED export URI: open + seek + DECODE first frame
  of every clip window (MediaCodec), per-window timing + timeout. Honest budget:
  minutes, not seconds — still 10–70x cheaper than a failed 74-min export. A window
  that can't produce a frame → fail fast with clip + source time + options.
  (Extractor-only probing CANNOT catch this failure — demonstrated: "no decoded
  output", container may be fine.) (`ExportManager.java`)
- [ ] 4. Atomic output: export to `<name>.exporting` (same-dir rename — no SAF rename
  issue; SAF path still streams from cache temp), rename on success, delete staging on
  error/cancel (even with no transformer callback). NEVER delete a successfully-muxed
  file when the SAF copy fails (today's keep is correct). (`ExportManager.java`)
- [ ] 5. Split causes: remux-lost ENOENT = retryable "helper was cleared, recreating,
  safe to retry" (today's SOURCE_MISSING copy misleads here); source-missing stays;
  watchdog stall = OWN NON-RETRYABLE cause with position reached + options (cool
  phone, split project, lower resolution) — NOT a Retry loop (each retry heats the
  phone and fails 74 min later at the same seam). (`ExportFailureCause.java`)
- [ ] 6. Honest progress: phases (Preparing / Compositing clip ≈i/N / Finalizing),
  monotonic rate-capped % = max(smoothed Media3 curve, pace-based measure),
  MB written + elapsed + pace ETA, SAF copy progress in Finalizing; notification +
  stripe alive through finalize thread. (`ExportManager`, `ExportService`,
  `FaditorEditorActivity` progress sites only)
- [ ] 7. Error notification IMPORTANCE_DEFAULT + editor shows missed failure on resume
  (result recorded in `faditor_export` prefs by service).
- [ ] 8. STRUCK — already in tree (verified 2026-09-21): `prepareMemoryForExport()`
  (`FaditorEditorActivity.java:12897`, called :13143) → `playerManager.releaseForExport()`
  (`FaditorPlayerManager.java:669`) releases the master decoder; overlays at :9638.
  Todo's premise was wrong (read `exportUiOnStarted` and stopped). No work.
- [ ] 9. Watchdog ceiling: decided AFTER the probe — if seam cost is finite-but-slow,
  raise 120 s → 300 s; if infinite (no frame ever), raising only wastes time. Measure,
  then set.
- [ ] 10. Verify: watcher BUILD SUCCESSFUL (never gradle); device check read-only
  (never uninstall, never touch his projects). Record in LEDGER.

## Definition of done

Done = project `a32d24e2` exports to a complete ≈48:27 file in a SINGLE pass on the
Note 20, with the seam logs showing what happened at clip 19. Partial/split export is
explicitly NOT the mitigation for this bug (ranged export lives in INBOX as its own
future feature, not as the answer to this stall). Preview plays this content fine on
this phone — export must too.

## Review (2026-09-21, ~23:30 — all compile-verified, BUILD SUCCESSFUL)

Built, staged, uncommitted. Files: `export/ExportManager.java` (item 0, fixes 3/4/6),
`export/ExportService.java` (fixes 1-prune-call/2/6/7), `export/ExportFailureCause.java`
(fix 5), `playback/FragmentedMp4Remuxer.java` (fix 1), `FaditorEditorActivity.java`
(progress UI sites + resume ledger only), `res/values/strings.xml` (3 phase strings,
BOM verified intact).

- Item 0: EXPORT_ITEM now carries `remux=yes/no`; EXPORT_SEAM logs every item crossing
  with wall time. Next real run names the stall's item.
- Fix 1: remux home `files/faditor/remuxed/` + one-time migration + prune(30d, 8GB).
- Fix 2: warm phase verifies + one retry + fail-fast with plain message; "Preparing"
  notification during remux.
- Fix 3: `probeClipWindows()` — decode-first-frame per window on the RESOLVED uri,
  PROBE log per window (cold baseline), fail-fast naming clip + source time. Budget
  ~1–3s/window on HW.
- Fix 4: `.exporting` staging + atomic rename commit; error/cancel delete staging;
  SAF-copy-failure keeps the muxed file; gallery extension-filter never shows staging.
- Fix 5: HELPER_LOST / PREFLIGHT_UNREADABLE (retryable) / SEAM_STALL (not); dialog
  shows service-authored messages verbatim.
- Fix 6: phase notification line (% + ≈clip + MB + pace ETA), monotonic rate-capped
  in-app bar, Finalizing state across the loudness/SAF thread.
- Fix 7: DEFAULT-importance alerts channel for done/failed (names the file, BigText
  errors) + terminal-result ledger with once-only resume announcement.
- Fix 8: STRUCK (already in tree).
- Fix 9: OPEN — watchdog stays 120 s until the probe + seam logs say whether clip 19's
  cost is finite (raise to 300 s) or unbounded (pre-trim cache).

NOT device-verified: no export run yet. Acceptance = project `a32d24e2` one-pass
≈48:27 on the Note 20. Two transient BUILD FAILEDs during the session were the known
save-race, each followed by a clean SUCCESSFUL with all edits in.

## Review (2026-09-22, 20:51 - chunked export driver, BUILD SUCCESSFUL, INSTALLED to Note 20)

Structural fix for the item-19 stall: long timelines (>=12 min) now export in bounded
~5-min chunk sessions (fresh Transformer/decoder/muxer each), not one 48-min session.

- ExportManager.export(): dispatches to exportChunked() when total >= CHUNKED_THRESHOLD_MS (720s).
- exportChunked() step machine: video chunks 0..N-1 (sequential) -> one audio pass -> ffmpeg
  concat+mux join -> shared inalizeExportAsync (loudness + SAF). Short timelines: legacy
  path unchanged (byte-identical).
- computeChunkRanges(): cuts at clip seams near 5 min target, never straddling transitions;
  trailing runt (<60s) merges into predecessor.
- Chunk state: chunkBaseMs (exact, from built items), chunkVideoOnly, chunkClipStart/End.
  uildComposition emits only the chunk's clip range; uildClipItem drops audio when
  chunkVideoOnly; editorTimeOffsetForChunk keeps overlay clocks absolute.
- uiltRangeDurationMs(): measures exact composition duration per range (no encode) so
  overlay clocks stay exact across chunks.
- Manifest resume: iles/faditor/chunks/<project>/manifest.json + chunk*.mp4 +
  udio_full.m4a. Completed chunks survive failures/restarts (resume, not redo).
- Progress: ChunkProgressAdapter maps per-chunk Media3 progress to overall (video 85%,
  audio 7%); onChunkPhase broadcasts "Part i of n" / "Sound" / "Joining" to UI + notification.
- chunkFail(): part-numbered error message; chunks stay for resume.
- exportAudioOnly(project, path, terminalOverride): intermediate audio pass hands result
  to the driver instead of finalizing (single loudness pass runs once, on the final mux).
- PreTrimCache.probeVideoDurationMs(): public wrapper for chunk file validation.
- Compile fixes: Composition.sequences / EditedMediaItemSequence.editedMediaItems (public
  fields in Media3 1.8.0, not getters); 3 aditor_font_* strings added to strings.xml.

NOT yet device-verified: full 48:27 proof run pending. Acceptance = project 32d24e2
exports to a complete ~48:27 file in a SINGLE user action on the Note 20.

## STUDIO_POLISH — JoyRaptor's 2026-09-23 round (drawers, top bar, defaults)
Done in the tree, NOT YET BUILT (the watcher stopped rebuilding at 04:42):
- [x] Frost/Solid removed: drawers and the transcript panel are always see-through (Kit.DRAWER_FILL, s_drawer_scrim). Frost returns only as SAME fill + blur on API 31+.
- [x] Drawer tabs back on the header line (● name · tabs · toggles · ✕); tab 0 pills say what they do (Transform / Level / Effects); image "Move" tab renamed Lanes.
- [x] Pass-through icons = JoyRaptor's PNGs; chain link/unlink = aligned Material link/link_off.
- [x] Rotate + Opacity share one line when the screen is ≥420dp wide (image drawer and video-overlay drawer).
- [x] Top bar: back arrow; export = bare icon in the Studio gradient; Joybot in the corner.
- [x] New text/images: 5 s from the playhead on every add path (one helper). New images and video overlays come in at Fit.
- [x] The drawer keeps the open tab when the selection moves to another object that has it.
To do:
- [ ] Verify all of the above on the Note 9 once the watcher builds, then commit.
- [ ] Universal drawer (needs JoyRaptor's OK on the gesture plan): text gets Transform/Blend/Effects/Lanes beside its text tab; the video overlay gets the image tabs plus its own; sprite gets a top drawer (Sprite tab raises the bottom palette); hold→release = object menu everywhere, and the obsolete opacity-keyframe hold widget goes.
- [ ] Frost blur (API 31+): needs testing on a phone that can render it (the Note 20), so it waits for JoyRaptor.

## STUDIO_POLISH — 2026-09-23 later round (all verified on the Note 9 unless noted)
- [x] One drawer language: text/video overlay/sprite in ObjectDrawer; double-tap OR hold opens it (64a49a71)
- [x] Text drawer shell retired, Kit kept (05cc2f80); split halves keep their lane (33538f36)
- [x] Film-graffiti backdrop, AMOLED top bar + divider, sprite drawer opens as a tabs-only strip (8c9d5370)
- [ ] Video overlay drawer: compile-verified only (no project on the Note 9 has a PiP)
- [ ] Image masks "Move with the object" (object vs screen space): video overlays already have it per mask.
      Images need a pose track on the timeline clock passed at FOUR render sites (ImageOverlayDraw,
      ImageBlendGlEffect x2, FxPreviewTextureView pip build); the GL image paths also pack the RAW spec
      (mask keyframes may not animate there). MaskPathBuilder/MaskSdf/FxPreviewTextureView hold another
      lane's STAGED, uncommitted work: coordinate before touching them.
- [ ] Visualizer hold still opens the old ObjectMenuSheet (no top drawer yet)

## Review (2026-09-23, Claude/Opus 5.5 - took over from three failed attempts)

**Re-read the evidence first; most of the handoff's numbers were a misread gauge.** Media3
progress = item index / item count, averaged across every sequence. Decoded properly, the
12:17 single pass ran ~1x from the start, stepped to ~0.25x, and stopped at comp ~30:35.

Disproved on device before touching code: overlay density (no correlation), caption shadow
blur (0.4 ms/frame, app_process bench on the Note 20), fMP4 deep seeks (remux is a normal MP4,
keyframe every ~1 s), VFR frame rate (steady 30-45 fps).

**Root cause of the slowdown: no wake lock + 5-minute screen timeout.** Busy process with the
screen off was asleep 30-45% of wall time, half speed; 100% the instant the screen woke.

Landed (all compile-verified by the watcher, all on the Note 20):
- [x] ExportService PARTIAL_WAKE_LOCK for the export's life (released in onDestroy, 8 h cap).
- [x] Export progress overlay keeps the screen on (off at done/error/hide). Same clips:
      screen on ~1.2x, screen off + wake lock ~0.6x, old build ~0.25x.
- [x] Chunk tail filler measured from the ABSOLUTE cursor (chunk6 was 44 min of black).
- [x] Honest length-mismatch message, both numbers.
- [x] Chunk resume keyed on project CONTENT (lastModified stripped, UUIDs by first-appearance
      order - audio clip ids are re-minted on every load; spun off as its own task).
- [x] Join: explicit -map 0:v:0 / 1:a:0 (unmapped, ffmpeg took the parts' forced SILENT track).
- [x] Join + loudness apply: -f mp4 (outputs end .exporting / .loudnorm.tmp; ffmpeg refused them.
      The loudness pass has been silently failing for every export that asked for it).
- [x] STALL_STACKS: 90 s without progress -> every thread's stack into the trace.
      Known false positive: an image-only part reads 100% while still drawing (harmless).
- [x] Join reopens the trace and records ffmpeg's own error tail.

Device proof so far (run 04:44, screen on): 7/7 parts at 0.9-1.4x (last night 0.11-0.25x),
byte-identical sizes to last night's parts; chunk6 48 MB/4:21 (was 114 MB/48 min); the
single-session SOUND pass crossed 30:35 fine (48.2 MB, 9 min). Join then failed on the
.exporting extension -> fixed. Re-run 09:22 is from scratch because overlay #97 gained
invertMasks=true (a real content change - resume correctly refused).

Still open: why single-pass (picture+sound together) always died at the clip 18->19 seam.
Neither half alone reproduces it; the chunked driver sidesteps it for long timelines.
Speed ceiling ~1x: CPU Canvas image overlays (STALL_STACKS caught the GL thread in
ImageOverlayDraw.draw) - the GPU-compositor port in the handoff is the next speed step.
- [ ] NEW (JoyRaptor 2026-09-23): dragging sprite FRAMES on the tape does not work (plumbing should exist) — investigate
- [ ] NEW (JoyRaptor 2026-09-23): image Bend warp handles are hard to grab/move — investigate hit targets
- [x] Video overlay drawer now device-verified (3e6dfa60); visualizer hold path still not reachable on the Note 9
- Test media left on the Note 9: /sdcard/Download/joy_test_overlay.mp4 (copy of ws20.mp4), pushed for the PiP test

## NEXT: export speed, 1x -> 6-9x (measured ceilings, 2026-09-23)

Hardware ceilings on the Note 20 (app_process MediaCodec bench): H.264 encode 1080x1920
416 fps (13.9x), 720x1280 723 fps (24x); decode of the screen recording ~300 fps (~10x).
Encode+decode share the VPU, so a GPU-only 1080p pipeline tops out ~6-9x. 20x at 1080p is
not physically available on this phone; 720p gets close. Media3 already sets best-effort
priority + high operating rate on both codecs (checked) - the codecs are not the limit.

Where the ~30 ms/frame goes (single GL thread, serial): per clip the chain is
Crop, Presentation, OverlayEffect(below-blend CompositeExportOverlay: every plain image under
a GL-routed image, CPU Canvas, full-frame clear + draw + 8.3 MB upload), 4x ImageBlendGlEffect
(visible: CPU draw + Bitmap.createBitmap full copy 6.5 ms measured + upload), OverlayEffect
(captions/text: full-frame caption bitmap + full-frame blit + 8.3 MB upload), Presentation.
STALL_STACKS caught the GL thread inside ImageOverlayDraw.draw.

- [ ] 1. MEASURE (needs Note 20 Wi-Fi ADB): per-pass nanos on the GL thread (below-blend
      overlay, each ImageBlend, caption overlay, uploads) -> one EXPORT_COST line per chunk.
      `am profile start --sampling 500 com.fadcam.beta:export /data/local/tmp/x.trace` works
      without root (simpleperf needs security.perf_harden - do not change it); parser at
      scratch dmtrace.py pattern (ART .trace v3). Profile project 3968cd84 (copy of the
      lecture, no chunk cache) for 60 s, then cancel.
- [ ] 2. QUICK WINS (~1 day, expect 2-3x): drop ImageOverlayFrameOverlay's per-frame full copy
      (patched BitmapOverlay already keys on generationId); captions render into their
      bounding box only and re-raster only when the active word/emphasis changes; skip the
      upload when a pass's pixels did not change.
- [ ] 3. THE REAL FIX (several days, expect 6-9x): image overlays become GL quads - each image
      uploaded ONCE as a texture, placed per frame by the same keyframe authority
      (animatedCenterX/Y/Size/Rotation/Opacity) and masked by the SAME MaskSdf shader the
      preview uses. Removes the CPU raster and the per-frame uploads, and makes export masks
      preview-identical by construction (JoyRaptor's mask parity bug is the same fork).
      Coordinate with the mask lane: its staged "hole by default" (fxMaskCover) is the shader
      this would share.
- [ ] 4. Offer 720p as a fast export preset (encoder ceiling 24x).
- [ ] Sound pass comes out MONO 44.1 kHz (final file 2026-09-23): the audio-only composition
      takes its format from the first item (the silence WAV?). Should be the project rate
      (48 kHz) and stereo.

# PERF SPEC — Long-project editor unusable (45-min Joy Creator) — 2026-07-18

**Status: DIAGNOSED (evidence below). Fixes F1–F6 specced with exact sites + acceptance.
Execution log at the bottom — update it as fixes land so any session/account can resume.**

This closes the open root-cause from `LONGFILE_FEEDBACK_20260716.md` #1 (the ANR was NOT
memory-pressure GC thrash — see Evidence). Read that doc for prior context; this doc
supersedes its #1.

## Repro / environment

- Device: SM-N960U (`<note20-serial>`), app `com.fadcam.beta`, project "Joy Creator"
  (`a32d24e2-6b8d-4bd8-8432-ef5a6169dcfc`): 4 clips over ONE 46-min 2.3GB fMP4 source
  `/storage/emulated/0/Android/data/com.fadcam.beta/files/FadCam/Screen/The_woman_and_the_5.mp4`,
  clip0 in=0 out=501515, sourceDuration=2757301ms. No separate audio clips.
- Build: `cd FadCam && TEMP=C:\Users\JoyRaptor\gtmp TMP=C:\Users\JoyRaptor\gtmp ./gradlew assembleDebug`
  (AppData temp is AF_UNIX-broken → "Unable to establish loopback connection"; see memory
  `gradle-loopback-temp-fix`). Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
  (beta appId comes from the debug suffix). adb from Git Bash: `export MSYS_NO_PATHCONV=1`.
- Media3 is a SOURCE SUBSTITUTION to `media3-patched/` (settings.gradle.kts) — do not
  swap to Maven artifacts; the gapless engine depends on the patched DefaultAudioSink.

## Evidence (captured live 2026-07-18, logs in scratchpad / re-capturable any open)

1. Editor `onCreate` 09:55:31 → ffmpeg remux of the FULL 2.3GB source starts 09:55:32 →
   **ANR 09:55:42** (`am_anr: Input dispatching timed out`) → remux ends 09:56:12.660
   (13s stream copy + 27s faststart second pass ≈ 9GB disk I/O) → "Timeline initialized"
   09:56:12.785. Main thread logged NOTHING 09:55:32→09:56:12: it sat inside
   `remuxSync`. That's the black screen.
2. Playback: `Davey!` frames with **DrawStart→SyncQueued = 0.8–1.6s** (the onDraw
   recording pass itself), `Choreographer: Skipped 43–66 frames` once per draw. 2-3fps.
3. `cache/waveform_bands/` EMPTY after a full session +
   `BandWaveformExtractor: Band waveform extraction incomplete — displayed but NOT cached`
   → tape analysis re-runs from zero every session, forever.
4. Heap 117→219MB in ~2min, GC freeing ~1M objects/cycle (boxed Floats + per-frame
   Strings/RectF + hot-path logging).

## Root causes → fixes

### F1 — Synchronous 2.3GB ffmpeg remux on the main thread at open  ★ ANR/black screen

- Site: `FaditorEditorActivity.resolvePlaybackUri()` (~line 3026-3048) calls
  `remuxer.remuxSync(sourceFile)` when no cached remuxed copy exists. Reached on main
  via `continueLoadFromSavedProject → loadClipForPlayback → getPlaybackClip` AND via the
  gapless build (`setGaplessTimeline → prepareTimeline → resolver.resolveSeekable`,
  activity ~3411-3426). The 120s-deferred background remux in onCreate (~1134-1146) is
  dead code in practice — the sync one always wins.
- Failure cycle: ANR dialog → user closes → app killed mid-remux → `.part` discarded →
  next open repeats. Cache lives in `getCacheDir()/remuxed/` so the OS also evicts the
  2.3GB copy routinely → "slow every open".
- **Fix**: `resolvePlaybackUri` must NEVER remux. Semantics:
  - remuxed copy exists (`hasRemuxedVersion`) → return it (unchanged);
  - else → return the RAW `file://` URI and schedule ONE deferred background
    `remuxAsync` (keep the existing kill-safe temp+rename; keep a deferral ≥60s so it
    doesn't fight initial load I/O; dedupe so repeated resolves don't double-schedule).
  - Delete the now-redundant onCreate deferred-remux block (~1134-1146) or fold it in.
- Raw-fMP4 session correctness: the gapless engine builds ClippingConfiguration
  playlists, which need a seekable SeekMap; a raw FadCam fMP4 (no sidx) is not reliably
  seekable that way. When ANY video clip resolves to a still-fragmented source
  (`needsRemux(file)==true` && no remuxed copy), SKIP `setGaplessTimeline` for the
  session (activity ~3411) → the legacy single-clip path takes over (it exists
  precisely for fMP4 — see FaditorPlayerManager class doc). Next open (remux landed in
  background) is gapless again. Cache the per-file needsRemux answer (64KB header read)
  in the existing `playbackUriCache` mechanism — never re-read per frame/tick.
- Acceptance: open Joy Creator with `cache/remuxed/` DELETED
  (`adb shell run-as com.fadcam.beta rm -rf cache/remuxed`): timeline interactive in
  <5s, zero ANR, logcat shows ffmpeg starting AFTER the editor is interactive.
  Playback/scrub works (keyframe-sticky seeks acceptable this one session).

### F2 — onDraw work scales with total timeline length, not viewport  ★ 2-3fps

All in `EditorTimelineView.java`. The canvas is translated by `-scrollOffsetPx`;
visible content window = `[scrollOffsetPx, scrollOffsetPx + getWidth()]`. The transcript
word loop (~3396-3411) already culls this way — copy that pattern.

- **F2a `drawRuler` (~2888-2955)**: all three tick loops run `t=0 → totalEffectiveMs`
  (45min ⇒ 5k-18k drawLine/measureText/drawText + a formatted String per label, per
  frame). Fix: start each loop at `floorToInterval(xToTime(visLeft))`, end at
  `xToTime(visRight)`. Note `timeToX` is per-segment linear; ticks are time-uniform, so
  compute the start ONCE per loop from the visible edge (no per-tick inverse).
- **F2b `drawSegmentWaveform` (~3511-3548)**: `barCount = rect.width()/step` bars for
  the WHOLE segment (~43,000 drawRect/frame at editing zoom on the 8.4-min clip). Fix:
  `jStart = max(0, (visLeft - rect.left)/step)`, `jEnd = min(barCount,
  (visRight - rect.left)/step + 1)`.
- **F2c `drawThumbnailsForSegment` (~3596-3616)**: tiles the whole segment
  (`while (x < rect.right)`) with a `new RectF` per tile. Fix: start
  `x = rect.left + floor((visLeft - rect.left)/tileWidth)*tileWidth` (clamped ≥
  rect.left), stop at `min(rect.right, visRight)`; hoist one reusable RectF field.
- **F2d `TapeTileCache.draw` (TapeTileCache.java ~105)**: `wF > 8192f` silently falls
  back to FULL vector tape rendering EVERY frame — at editing zoom 8192px ≈ 10-45s of
  timeline, so every real clip takes the fallback (the tile cache exists for exactly
  this case). Fix: remove the `> 8192` arm; add visible-window culling: the view passes
  `visLeft/visRight` (content px) into `draw(...)` (plumb from
  `drawClipAudioDrawers` ~2622), and the tile loop only bakes/blits tiles intersecting
  it. Raise `MAX_TILES` to ~24 (screen width / 512 + margin both sides).
  KEEP the `wF < 2f` degenerate guard and `directVectorMode`.
- Acceptance: `adb shell dumpsys gfxinfo com.fadcam.beta framestats` during playback at
  editing zoom: p90 frame < 16ms, no Davey >700ms. Playback visually smooth ≥24fps.
  Scrub responds instantly. Zooming fully out and fully in both stay smooth.

### F3 — Audio analysis: three pipelines, no effective caching  ★ "audio loads forever"

- **F3a `util/AudioExtractor.doGenerateWaveform` (~215)** decodes the ENTIRE audio
  track (20ms bins), NO disk cache — re-runs every open (this is the visible "loading
  the audio" wait). Fix: disk-cache the `int[]` under
  `getCacheDir()/waveform_legacy/<hash(uriString)>_<fileLength>.bin` (version byte +
  length + data); read before decode, write after. Key on the ORIGINAL source URI
  string + file length (stable across sessions).
- **F3b unstable cache keys**: `refreshWaveformOverlays`
  (FaditorEditorActivity ~9741) feeds `WaveformExtractor` the RESOLVED playback URI
  (`resolvePlaybackUri(...)`) which flips raw↔remuxed between sessions → same audio,
  different disk-cache key → silent re-extraction. Fix: pass
  `Uri.fromFile(resolveToFile(clip.getSourceUri()))` (stable raw path) — the audio
  stream is identical in both files. Audit ALL WaveformExtractor/BandWaveformExtractor
  call sites for the same mistake. (`EditorTimelineView` band/tape calls use
  `sd.sourceUri` = original — already stable.)
- **F3c band tape never completes** (`BandedTimelineWaveformCache` ~164-177): a partial
  extraction is shown, deliberately not cached, evicted after 60s, re-kicked by the next
  draw → infinite multi-minute decode loop competing with playback ("plays okay then
  goes downhill"). After F1+F2 the starvation cause (remux I/O + 1s draws + GC) is
  mostly gone, so completion is expected. Two hardenings: (1) don't KICK new extractions
  while the player is playing (add a suspend flag: activity sets
  `editorTimeline.setAnalysisSuspended(isPlaying)` from play/pause; in-flight jobs keep
  running); (2) keep the 60s retry only when NOT suspended.
  Follow-up (NOT this pass): resumable span checkpoints.
- Acceptance: `run-as com.fadcam.beta ls cache/waveform_bands cache/waveform_legacy`
  non-empty after one idle-open session. Second open: cyan bars + tape appear <2s, NO
  "Waveform extract START"/band-decode logs for already-analyzed spans.

### F4 — Hot-path logging & allocation churn

- `FLog` (com/fadcam/FLog.java) runs FOUR redaction regexes per message; hot paths log
  constantly: `EditorTimelineView.setPlayheadFraction` (2 lines) + `centerPlayhead`
  (1 line) per 50ms tick; `onTouchEvent`/`onDown` per motion event (~2Hz-120Hz);
  `FaditorEditor computeVideoContentRect` per tick; ExoPlayer `EventLogger` (debug
  builds). Fix: delete (or gate behind `private static final boolean VERBOSE = false`)
  the per-tick/per-motion log lines. Do NOT touch warn/error or one-shot logs.
- Extractor boxing (`ArrayList<Float>` in WaveformExtractor/BandWaveformExtractor):
  optional P2 — growable `float[]`. Only if time permits; caching (F3) makes it rare.
- Acceptance: `adb logcat --pid=$(adb shell pidof com.fadcam.beta)` during 30s playback
  produces < 200 lines (was ~thousands).

### F5 — Remux durability (secondary, after F1)

With F1, an evicted remux costs only a background rebuild — acceptable. Optional
hardening: `StorageManager.allocateBytes`/`setCacheBehaviorGroup` hints, or promote a
completed remux of the CURRENT project's source to `getFilesDir()/remuxed/` with an LRU
of 1-2 entries (2.3GB each — must stay bounded). Decide with JoyRaptor; do not block on this.

### F6 — Strategic (separate session): fMP4-index playback in the editor

`SeekableFragmentedMp4MediaSourceFactory` (playback/) already gives VLC-like precise
seeking on raw fMP4 (used by PlayerHolder/VideoPlayerActivity) with a seconds-long index
scan instead of a 40s/9GB remux. Wiring it into the editor legacy path
(`FaditorPlayerManager.preparePlayer` ~922: `setMediaSource(factory.createMediaSource(item))`
when raw fMP4 file://) and the gapless engine (`MasterPlaybackEngine.prepareTimeline`
~379-426: build `MediaSource`s — wrap in `ClippingMediaSource` for windows — instead of
MediaItems) would make remuxing unnecessary entirely. HIGH RISK on the gapless side
(seam math, patched audio sink) — needs its own device-verified pass. Until then F1's
legacy-fallback covers the un-remuxed session.

## Order of execution & verification

1. F2 (+F4 log strips in the same files) — pure view/render, biggest playback win.
2. F1 — open path. 3. F3 — caching. 4. Device pass: delete `cache/remuxed` +
   `cache/waveform*`, cold-open Joy Creator, capture logcat + gfxinfo, compare against
   the Evidence numbers. 5. Second-open pass (caches warm): audio <2s, no re-decode.
- After each: `./gradlew assembleDebug` (TEMP fix!), install, spot-check. Full
  acceptance list per fix above. Do NOT commit without a device pass on the real
  45-min project (playback, scrub, zoom in/out, drawer open, transcript visible,
  segment select, trim drag).
- Guard rails: `ExportManager`, `ProjectStorage`, compositor internals, avatar math are
  on the do-not-touch list (see tasks/Opencode-work.md conventions). F2 must not change
  any rendered OUTPUT inside the viewport — culling only skips off-screen work.

## Execution log (append-only)

- 2026-07-18 Fable session: spec written. Beginning F2/F4.
- 2026-07-18 Fable session — F1-F4 IMPLEMENTED (device-verify pending):
  - F2a ruler cull (EditorTimelineView.drawRuler: all 3 tick loops → visible window ±1
    interval); F2b segment-waveform bar cull (jStart/jEnd); F2c thumbnail tile cull
    (grid-snapped start + reusable thumbTileDst RectF); F2d TapeTileCache: >8192px vector
    fallback REMOVED, viewport-culled tile bake/blit (new visLeft/visRight params, both
    call sites plumbed: EditorTimelineView drawer + LayerRowRenderer audio rows),
    MAX_TILES 16→24, PLUS width-stability gate (vector during live pinch, tiles once the
    width repeats — bake-per-frame during zoom would beat the old cost).
  - F1: resolvePlaybackUri never remuxes (remuxed-if-exists else raw + ONE deduped
    scheduleBackgroundRemux at 120s); onCreate deferred-remux block folded into it;
    gapless SKIPPED for the session when timelineHasUnremuxedFmp4() (legacy path carries
    raw fMP4; next open upgrades). ExportService.remuxSync untouched (off-main, legit).
  - F3a: AudioExtractor.generateWaveform now disk-cached
    (cache/waveform_legacy/<uriHash>_<len>_<mtime>.bin, version 1, byte-per-bin).
  - F3b: refreshWaveformOverlays extracts from the RAW source file (stable cache key),
    falls back to resolvePlaybackUri only when the source doesn't resolve to a file.
  - F3c: setSuspended on BandedTimelineWaveformCache + TimelineWaveformCache; forwarded
    via EditorTimelineView.setAnalysisSuspended, driven from updatePlayPauseButton.
  - F4: VLOG gate (constant false) on all per-tick/per-motion FLog.d in
    EditorTimelineView; computeVideoContentRect per-tick log removed.
  - Build note: first assembleDebug hit "Could not get file mode ...
    intermediates\javac\...\fragments" in processDefaultDebugJavaRes — stale-intermediates
    Windows quirk, NOT a code error; fix = rm -rf app/build/intermediates/java_res, rerun.
  - DEVICE-VERIFIED F1/F2/F4 (build 11:04, project "first lecture on phone" / id
    a32d24e2, 4 clips over the 46-min source, cache/remuxed WIPED first):
    * Open: "Timeline initialized" 1.1s after tap (was ~40s + ANR). ZERO ffmpeg lines at
      open. Log: "Gapless skipped this session: raw fragmented source… next open upgrades".
      No new ANR. Choreographer skips: 2 (both in first 1.5s = one-time view inflation,
      incl. a WebView for a GeneratedSlide clip), then clean. Playback window: 0 skips,
      0 Davey. → F1 (no ANR) + F2/F4 (frame cost) CONFIRMED.
    * REGRESSION found: with gapless skipped, the LEGACY path can't play the RAW fMP4 —
      plain setMediaItem gives no seek map, so trim-start seeks fail and each clip reads
      instantly ENDED. Symptom (user): play flips to pause for a frame, auto-advances to
      start of clip 2→3→4, sticks at clip 4; scrubbing the minimap then pressing play
      repeats. Log confirms: "Auto-advancing to segment 1/2/3" then "Playback stopped at
      last segment end" in a burst. This is the F6 gap — F1 removed remux but the raw
      file was never actually playable without it.

### F6 — IN PROGRESS (applied, build+verify pending). Wire the fMP4 index source into
the editor's LEGACY player ONLY.

KEY INSIGHT that shrinks F6: the gapless engine NEVER needs to handle raw fMP4. By the
two-tier design, gapless is only selected when a remuxed (seekable) copy exists; the
un-remuxed session already falls to the legacy single-clip path (F1's
timelineHasUnremuxedFmp4 gate). So the ONLY change needed is: legacy preparePlayer plays
raw fMP4 via SeekableFragmentedMp4MediaSourceFactory (moof index, ~ms-seconds — the same
path PlayerHolder/VideoPlayerActivity already trust). The scary "gapless +
ClippingMediaSource per window" half of the original F6 is NOT needed and should NOT be
attempted — leave MasterPlaybackEngine alone.

APPLIED (this session, UNVERIFIED — build was kicked, confirm it compiled + device-test):
  - FaditorPlayerManager.java: new field `fmp4SourceFactory` (lazily-created
    com.fadcam.playback.SeekableFragmentedMp4MediaSourceFactory); in `preparePlayer`
    (~line 934), if `fmp4SourceFactory.isFragmentedMp4(resolvedUri)` →
    `player.setMediaSource(fmp4SourceFactory.createMediaSource(mediaItem))` instead of
    `setMediaItem`; falls back to plain item for non-fMP4 or any index-build exception.
    Mirrors PlayerHolder.setMediaIfNeeded (~line 282) exactly.

F6 acceptance (THE gate for this whole spec — do this next):
  1. Build (rm -rf app/build/intermediates/java_res first if the file-mode error hits),
     install arm64 APK, WIPE cache/remuxed, open "first lecture on phone".
  2. Press play → video must actually PLAY (not auto-advance/stick). Play through a clip
     boundary (small re-prepare hiccup at each cut is EXPECTED on the legacy path — 3 cuts
     in this project — and is fine; it's not the freeze). Scrub the minimap, press play,
     confirm it plays from there. Check log: NO "Auto-advancing…/Playback stopped at last
     segment end" burst on a plain play.
  3. Let the 120s background remux land (or wait), reopen → log must show gapless ACTIVE
     (no "Gapless skipped") and playback is seam-smooth.
  If (2) still auto-advances: the index build may have failed for this file — check log
  for "fMP4 index source failed" or SeekableFmp4Factory "seekable=false"; if isSeekable
  is false the moof index didn't parse, fall back to investigating FragmentedMp4IndexBuilder
  on THIS source, OR (pragmatic) re-enable a ONE-TIME foreground remux with a visible
  progress dialog + cancel, off the main thread (NOT remuxSync on the UI thread).

DEVICE-VERIFIED F6 (build 11:14, project reopened, cache/remuxed WIPED, gapless skipped):
  playback PLAYS — playhead advanced 00:14 → 00:23 in ~9s wall-clock (real-time), pause
  icon HELD (not flipping back to play), live captions rendering, 0 "Auto-advancing /
  Playback stopped at last segment" events during play, 0 Choreographer skips, 0 Davey.
  The auto-advance-and-stick regression is FIXED by the legacy-path fMP4 index source.
  (The FaditorPlayerManager "Prepared clip via seekable fMP4 index source" line lands in
  the open-phase buffer; behavioral proof is conclusive — raw fMP4 on plain setMediaItem
  would auto-advance, now 0.) → F1+F2+F4+F6 all CONFIRMED on the real 45-min project.

REMAINING after F6 verify:
  - F3 device-verify (2nd open: cyan bars + tape <2s, no re-decode logs; caches non-empty).
  - Optionally delete the now-redundant scheduleBackgroundRemux entirely if the index
    path proves reliable (removes the 2.3GB cache writes). Decide with JoyRaptor.
  - F5 remux durability — only if the remux is kept.

F7 — DOUBLE-TAP AUDIO-DRAWER ANR (found by JoyRaptor 2026-07-18 ~11:29, post-F6 build):
  SYMPTOM: double-tap on the main layer (expand waveform tape drawer) does nothing,
  freezes all input, repeated attempts → "Input dispatching timed out" ANR with the app
  at ~30% user CPU (busy, not deadlocked).
  ROOT CAUSE: TapeTileCache F2d removed the wF>8192 guard but kept two O(item-width)
  paths on the main thread: (a) the width-settle frame did a FULL-rect vector draw, and
  (b) bakeTile used the "offset rect trick" — positioning the FULL item render per tile.
  TapeWaveformRenderer.columns() builds a per-PIXEL float array + Path point per pixel,
  so at editing zoom the 45-min layer (~100k+ px wide) cost seconds PER pass; expand =
  1 settle draw + 3-4 bakes = 10s+ blocked = ANR. (Pre-F2d it was one full vector pass
  per frame — also seconds on this project, so the drawer was never usable on long files.)
  FIX (TapeTileCache.java): time↔x mapping is linear (x/W*clipDurMs), so rendering a
  time SUB-SPAN at the same ms-per-px is pixel-equivalent to cropping a full render.
  bakeTile now renders only its tile's sub-span (+8px overscan so the 3-tap smoothing
  and ±6px spark detection see real neighbors — no visible seams), and the settle-frame
  / direct-vector fallbacks go through drawVectorWindowed() which clips to the visible
  window and renders that sub-span only. All paths are now O(viewport), never O(item).

F7 DEVICE-VERIFIED (build 11:44, JoyRaptor hands-on): drawer opens on double-tap, no freeze,
  seek "super quick", plays over clip seams fine. One residual 1455ms frame / 85 skipped
  logged at first drawer render (11:47) — likely first-tile bakes + band-cache read on a
  cold drawer; monitor, not a blocker.

F8 — DURABLE WAVEFORM CACHES (JoyRaptor 2026-07-18: "every time we update the app it has to
  rebuild the waveform"): all three waveform caches lived under getCacheDir() —
  waveform_legacy (AudioExtractor), waveform_bands (BandWaveformExtractor), waveform
  (WaveformExtractor). The device sits at ~3% free storage, so the OS trims cache dirs
  constantly — every trim/update forced the minutes-long re-decode. NEW: DurableCache.java
  (util) → getFilesDir()/<name> with lazy one-shot migration (renameTo) from the old
  cache-dir location. Safe: entries are KB-to-low-MB, content-keyed (uri hash + params;
  legacy adds len+mtime), and format-version-gated in the readers — a stale entry is
  rejected, never rendered. Deliberately NOT moved: cache/remuxed (2.3GB class),
  cache/filmstrip (bitmap-heavy), faditor_export — those SHOULD yield to storage pressure.
  NOTE: first-ever extraction of a source still takes as long as it takes; durability
  means it happens ONCE per source, surviving updates and cache trims.

F9 — PARALLEL/PIPELINED BAND ANALYSIS (JoyRaptor 2026-07-18: "cut the audio-analysis wait ~70%,
  keep all functionality"). BandWaveformExtractor rewritten:
  - CHUNKED mode: when a probe seek VERIFIES the container honors seekTo (raw FadCam fMP4
    does not — the platform extractor has no index and lands at 0), the span splits into
    up to 4 HOP-aligned ranges, each with its own MediaExtractor+MediaCodec on a shared
    4-thread daemon pool. 300ms pre-roll warms the IIR filter state at each boundary
    (110Hz corner settles in ~10ms); interior seams drop one partial hop (<2.9ms each,
    ≤3 seams) — visually identical stitch. Any worker failure → pipelined fallback.
  - PIPELINED mode (unseekable sources): single sequential decode, but the 4 band chains
    run on the worker pool in double-buffered 64k-sample batches — DSP overlaps decode
    instead of serializing after it on one thread.
  - REMUXED SUBSTITUTION (decode-side ONLY, cache keys stay on the original uri): if the
    validated remuxed copy exists (atomic-rename guarantees validity), decode THAT — it
    seeks, so raw recordings get chunked mode once the background remux lands. Also wired
    into WaveformExtractor (HD tiers), whose mid-file span extractions on a raw fMP4
    otherwise decode from byte 0.
  - Inner loop (both modes): bulk ShortBuffer.get(short[]) replaces ~260M per-sample
    gets; primitive FloatList replaces boxed ArrayList<Float> (~4M Float allocs/run).
  - New log line for A/B: "Band extraction <mode> span=Xms took Yms complete=Z".
  Cache format/version UNCHANGED (existing entries stay valid). Expected: seekable/remuxed
  sources ~4x (≥70% cut); first-ever raw fMP4 ~2x (decode-bound), and it upgrades to
  chunked automatically once the 120s background remux produces the copy.

F9 DEVICE-VERIFIED (build 12:09, fresh empty bands cache): logcat
  "Band extraction chunked×4 span=2757301ms took 63777ms complete=true" — the full
  45:57 source in 63.8s; 7.8MB entry written to files/waveform_bands (durable). The raw
  content:// fMP4 PASSED the seek probe on this device, so chunked ran without needing
  the remuxed copy. JoyRaptor confirmed the drawer opens and the tape renders from cache
  after an app restart. Minor open niggle: in the SAME session that ran the extraction,
  one drawer kept showing "analyzing audio…" after completion (data was ready+cached;
  restart cleared it) — likely a missed invalidate/alias handoff in
  BandedTimelineWaveformCache onReady → superset alias path; cosmetic, worth a look.
  FIXED `031ed07` (2026-07-25): it was the superset-REUSE scan in `get()`, not onReady — it
  early-returned null on the first IN-FLIGHT covering span, short-circuiting before it could find
  an already-READY covering entry later in HashMap iteration order (two overlapping extractions =
  full-source prime + per-clip window). Now scans all candidates for a ready covering span first;
  returns null only if none ready (no-duplicate-extraction guarantee preserved). Compile-green.

F10 — STRUCTURAL EDITS DESYNC THE GAPLESS ENGINE (JoyRaptor 2026-07-18 pm: cut a clip during
  gapless playback → video played straight through the cut; on next play the audio jumped
  to the start). ROOT CAUSE: MasterPlaybackEngine plays a ClippingConfiguration playlist
  SNAPSHOTTED at prepareTimeline(); only trim/loop/undo paths rebuilt it. Every edit that
  changes clip COUNT/ORDER/IDENTITY (split, delete, gap-delete, both reorder paths,
  silence-cuts, both asset inserts, slide code edit) or ELIGIBILITY (transition add)
  mutated the Timeline model only — engine kept playing the pre-edit cut. Split is worst:
  new Clip(original) mints FRESH ids, so the engine's tracked clip id dies and any stale
  resume homes to window 0 ("audio from the start").
  FIX (uncommitted, on top of 0d7a2ce):
  - FaditorPlayerManager: rebuildGaplessTimeline() refactored onto shared
    rebuildGaplessInternal(); new rebuildGaplessResumingAt(homeClipId, clipLocalMs,
    playAfter) homes to a CALLER-chosen post-edit clip; stale-id guard via
    windowForClipId() >= 0 before seekInClip.
  - FaditorEditorActivity: one funnel resyncGaplessAfterStructuralEdit(...) — bumps
    rebuildGeneration (RANK-1c stale-bake discard), rebuilds, and if the edit flipped
    eligibility OFF (transition add) falls back to loadClipForPlayback(selected) so the
    preview isn't dead after engine teardown. Wired into: splitAtPlayhead (homes to clip
    B at the seam), deleteSelectedSegment (homes to shifted-in clip), gap-delete (homes
    to spacer), moveSelectedClipTo + drag-reorder (home to moved clip), applySilenceCuts
    (homes to first keep), insertAssetAtPlayhead/AtIndex (home to new clip),
    applySlideCodeEdit (same id, stale source URI), both insertTransition* paths.
  - Speed-change staleness (setPlaybackSpeed with no rebuild; engine bakes per-window
    speed at prepare) is a KNOWN SIBLING, deliberately deferred: needs rebuild-on-release
    plumbing in the speed sheet, separate change.
F10 DEVICE-VERIFIED (build 13:1x, installed 13:22): split at absoluteSplit=66161 on the
  45-min project logged "gapless playlist prepared: 7 clipped items" immediately after
  splitAtPlayhead and "Selected segment 2/7 ... in=66161" — engine homed exactly to clip
  B's start; preview rendered live. NOTE: the split was an ACCIDENT of adb UI driving
  (tap landed on Split), and unwinding it while JoyRaptor was simultaneously handling the
  phone caused stray Delete-clip actions; final state verified via project.json = the
  original 6 clips (a82993aa restored continuous 12166..501515). Lesson recorded in
  memory: never inject taps while the device is in-hand; verify screen state immediately
  before EVERY tap, not per-batch.

F11 — TRANSITION ADD/REMOVE: NO UNDO RECORD + PLAYER STRANDED (JoyRaptor 2026-07-18 pm: GL
  transition at the eb36b1df|ba454ae7 seam looked janky reframing the video on canvas;
  undo did not remove the jank). TWO defects, both pre-existing, exposed by the F10
  eligibility fallback:
  (1) insertTransitionAtSeam / insertTransitionAtPlayhead / deleteTransition recorded NO
      undo action — "undo" after adding a transition silently unwound the user's PREVIOUS
      edit while the transition stayed.
  (2) Nothing resynced the player on transition REMOVAL, so after F10's add-side teardown
      the session stayed stranded on the legacy single-clip player with a stale crop-zoom
      transform (scale/translate/clipBounds computed for the engine's geometry) — the
      persistent "reframed on canvas" jank. project.json diff (scratchpad project2 vs
      project3) proved the MODEL was clean: crops/trims byte-identical, transition gone —
      pure runtime state.
  FIX (uncommitted): addTransitionUndoable() records a LambdaAction (restores a replaced
  seam transition on undo; refreshes transition markers, which refreshEditorAfterUndoRedo
  does NOT re-feed); deleteTransition records the mirror action;
  resyncPlayerForTransitionChange() resyncs BOTH directions — add ⇒ gapless teardown +
  legacy fallback + updatePreviewTransforms() (transform refresh added to the F10 funnel
  fallback too), remove/undo ⇒ rebuildGaplessTimeline() re-promote when eligible again.
  Build OK, installed 14:0x. DEVICE-VERIFY OWED (phone was in personal use): add a GL
  transition on the lecture project → preview stays framed; undo → transition gone AND
  session back on gapless with correct framing. If a FRESH legacy session still misframes
  cropped clips, that is a separate legacy-path crop bug — chase with the device.

F12 — TRANSITION FRAMES IGNORE CLIP CROP (JoyRaptor 2026-07-18: both clips cropped to 9:16;
  at the transition the outgoing side "pops out where there are black bars around";
  incoming leg reads as respected because the handoff ends on the correctly-cropped
  player). ROOT CAUSE: decodeTransitionFrame() feeds RAW MediaMetadataRetriever frames
  to the GL/overlay transition renderers — the preview's crop-zoom lives in the
  PlayerView TRANSFORM, which transition rendering bypasses. Both legs were actually
  uncropped during the transition.
  FIX (uncommitted): cropToClipBounds() crops the decoded frame to the clip's custom
  crop BEFORE letterbox/scale, in both the video and image paths; crop is part of the
  frame-cache key (cropKey()) — REQUIRED because clips split from the same source share
  a URI but can carry different crops. Installed ~14:2x; verify = re-run JoyRaptor's GL
  transition at the eb36b1df|ba454ae7 seam: no pop-out at transition start.
  KNOWN GAP (deliberately not fixed): EXPORT transitions have the same hole —
  assembleClipVideoEffects skips the Crop effect for isTransitionItem=true (outgoing
  leg, ExportManager:2181) and GlTransitionExportEffect samples the incoming clip's RAW
  source. Fixing needs GL-side crop of both legs + canvas-compose review — do NOT change
  blind; needs an A/B export frame-diff proof (see memory ab-export-frame-diff-proof).

F13 — GL SHADER TRANSITIONS: PREVIEW RELIABILITY + QUALITY OVERHAUL (JoyRaptor 2026-07-18 pm,
  sandbox SM-N960U, test project 302da9ac: 3.2s landscape rug -> tangentMotionBlur ->
  5.5s vertical clip, canvas 'original'). Four defects found by screenrecord frame pulls:
  (1) FREEZE at a file-end seam: transition branch lived inside if(isPlaying); ExoPlayer
      STATE_ENDED before progress>=1 -> isPlaying false -> tick loop died -> frozen
      mid-blend, clip B never started. FIX: ticker keeps ticking while
      transitionPlaybackActive; a !isPlaying+isAtTrimEnd tick completes + advances.
  (2) BLACK FLASH instead of a blend: renderTransitionPreview decoded BOTH legs via
      MediaMetadataRetriever ON MAIN per 50ms tick (100-300ms each) — nothing rendered
      within a 600ms window. FIX: animator-driven blend — endpoint frames (A@out, B@in)
      decoded once OFF-main (single-thread executor; retriever calls serialized with the
      scrub path via transitionDecodeLock), prefetched ~1.2s before the seam, then a
      LinearInterpolator ValueAnimator drives the shader at frame rate. A's LIVE tail
      stays on screen until frames are ready (slow decode = shorter blend, never black).
      Poll keeps a progress>=1 hard-cut fallback; ENDED fallback only when no animator.
  (3) TEXTURE UPLOAD COST: GlTransitionPreviewView re-uploaded both textures EVERY draw
      through a per-pixel Java loop (~100ms+/frame). FIX: GLUtils.texImage2D native
      upload, only when the bitmap reference changes (once per transition with static
      endpoints); texture ids + upload cache reset on surfaceCreated (fresh EGL context).
  (4) GEOMETRY: endpoint frames were letterboxed to the CONTAINER aspect but the GL quad
      fills the CANVAS rect -> incoming clip squashed during the blend, snapping to the
      correct pillarbox at handoff. FIX: glTransitionFrameDims() = GL view rect (fallback
      computeVideoContentRect) so blend framing == playback framing.
  Plus HANDOFF HOLD: advanceToSegment->loadClipForPlayback->hideTransitionPreview cleared
  the GL view before B had a frame (black canvas + spinner ~300ms). The GL view now HOLDS
  the blend's final frame (== B's first frame); released on onRenderedFirstFrame of the
  incoming player or a 1.5s timeout; stale holds cleared on new seam activation.
  DEVICE-VERIFIED (screenrecord tr7, 10fps frame pull): live A tail -> full-rate
  tangentMotionBlur blend at correct aspect -> hold -> B plays pillarboxed, zero black
  frames, zero freezes. Animator bitmaps are defensive COPIES (the frame cache recycles
  on hide). Uncommitted. Defensive-copy + endpoint-frame model means the blend uses
  STATIC endpoint frames (standard freeze-frame blend) — A's motion pauses for ≤600ms;
  live-texture (SurfaceTexture two-player) rendering would be the next tier if wanted.

F12 CLOSED (2026-07-18 ~15:45): EXPORT transition crops implemented + device-proven.
  - Outgoing leg: assembleClipVideoEffects no longer skips Crop for isTransitionItem —
    the Crop effect is ordered BEFORE the GlTransitionExportEffect (preOverlayExtra), and
    GlTransitionShaderProgram.configure() outputs at input size, so the segment's
    geometry class is unchanged (crop dims now vs source dims before).
  - Incoming leg: GlTransitionFrameOverlay.drawFrame samples only cropSrcRect(frame)
    (mirror of preview cropToClipBounds; "custom" preset, same 0.99 no-op epsilon)
    before fit-centering; applies to the image-clip path too.
  - PROOF (absolute-geometry frame inspection per ab-export-frame-diff-proof): crops
    [.15,.15,.85,.85] / [.05,.10,.75,.80] injected into sandbox project 302da9ac via
    run-as json surgery -> exported Faditor_20260718_154016.mp4 (896x504) -> ffmpeg
    frame pulls: A's cropped framing fills the frame INTO and THROUGH blend start (no
    raw+black-bars pop), incoming B appears mid-blend already at its cropped framing,
    and post-cut playback framing is identical (no snap). Test project's json restored
    to its crop-free original afterward.
  - LIMIT: incoming-leg crop handles the "custom" preset only (parity with preview);
    named PRESET crops on the incoming clip still export uncropped through the blend —
    small follow-up if preset crops matter. Uncommitted.

F12 LIMIT RE-SCOPED (2026-07-25, Opus 5 — the one-line note above understates it).
  The limit reads as an EXPORT-side gap ("named PRESET crops on the incoming clip still
  export uncropped through the blend — small follow-up"), which implies a small fix in
  GlTransitionFrameOverlay.cropSrcRect. It is not that, and a fix scoped that way would
  make things worse.
  - The PREVIEW has the SAME gap: FaditorEditorActivity.liveLegGeometry (the live A+B tier)
    and cropToClipBounds both branch on `"custom".equals(clip.getCropPreset())` and apply NO
    crop for a named preset. So preview and export currently AGREE — both blend a
    preset-cropped clip uncropped, then snap to the cropped framing at the cut.
  - Therefore fixing only the export leg would CREATE a preview/export divergence, which is
    the one invariant this compositor work is not allowed to break. The fix has to be
    symmetric: preset → source-rect in cropSrcRect (export) AND in liveLegGeometry /
    cropToClipBounds (preview), landing together.
  - Doing it safely by construction: express BOTH paths through one NDC→rect conversion, fed
    either by the custom fractions or by the preset table (ExportManager.getCropRect, which
    is what the Crop effect itself consumes). Then the device-proven custom path and the new
    preset path cannot disagree — the preset case inherits F12's proof instead of needing a
    fresh derivation.
  - ⚠️ Note while there: the preset table's NDC constants are written for a 16:9 source
    (`"1:1"` is `[-1,1,-1,1]`, i.e. no crop at all). Mirroring them gives blend/post-cut
    PARITY, which is the goal, but it inherits whatever the Crop effect actually does — do
    not "correct" the constants in the same change, or the blend will stop matching the cut.
  - NOT ATTEMPTED THIS SESSION: the acceptance gate is an absolute-geometry frame-pull A/B
    (memory: ab-export-frame-diff-proof), same as F12's, and the device was in human use.
    This is device work, not a desk fix.

  ### 2026-07-26: still not attempted, but the acceptance gate is now BUILT and the recipe
  ### is spelled out, so this is no longer blocked on inventing a proof.

  Deliberately NOT started in that session: it is a symmetric three-site change in the GL
  transition compositor (the area the `gl-transition-preview-design` and
  `gl-transition-live-tier` memories describe as having four separate traps), and there was
  not enough remaining context to do it AND device-verify it without risking an unfinished
  tree. Starting it half-way would be worse than not starting.

  **Everything needed to execute it now exists.** `tasks/export_ab_diff.py` is the acceptance
  gate, already proven on two other items this session (neutral-substrate item 8, cross-type Z
  acceptance 1/2/4). Concretely:

  1. Build ONE fixture pair off a throwaway clone (recipe in
     `tasks/PICKUP_PROMPT_20260726.md`): two clips with a GL transition between them, the
     incoming clip carrying a NAMED preset crop (not `custom`). Second fixture identical but
     with the equivalent crop expressed as `custom` fractions.
  2. **BASELINE FIRST, before touching any code.** Export both and diff. Today's expected
     result is that they DIFFER during the blend and AGREE after the cut — that difference IS
     the bug, and capturing it first is what proves the later fix did something. Skipping this
     baseline is how you end up unable to tell a fix from a no-op.
  3. Make the change symmetrically — `cropSrcRect` (export) AND `liveLegGeometry` /
     `cropToClipBounds` (preview) — routed through one NDC→rect conversion fed by
     `ExportManager.getCropRect`, exactly as the block above prescribes.
  4. Re-export both and diff again. Acceptance: preset and custom now agree THROUGHOUT,
     including mid-blend. Use `--check-asym` and put the crop OFF-CENTRE — a centred crop is
     symmetric and would hide a flip, which is the specific way this class of proof has failed
     before.
  5. Screenshot the PREVIEW mid-blend for both fixtures too. The invariant this item exists to
     protect is preview/export parity, and an export-only diff cannot see a preview regression.

  Do NOT "correct" the preset table's 16:9 NDC constants in the same change — see the warning
  two bullets up. Parity with the Crop effect is the goal, not correctness of the table.

# PERF SPEC — Long-project editor unusable (45-min Joy Creator) — 2026-07-18

**Status: DIAGNOSED (evidence below). Fixes F1–F6 specced with exact sites + acceptance.
Execution log at the bottom — update it as fixes land so any session/account can resume.**

This closes the open root-cause from `LONGFILE_FEEDBACK_20260716.md` #1 (the ANR was NOT
memory-pressure GC thrash — see Evidence). Read that doc for prior context; this doc
supersedes its #1.

## Repro / environment

- Device: SM-N960U (`REAL_SERIAL`), app `com.fadcam.beta`, project "Joy Creator"
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

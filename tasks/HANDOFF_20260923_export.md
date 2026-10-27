# HANDOFF — 48-minute export truncation + export being 200× slower than preview

**Rewritten 2026-09-23 03:20 after the pace traces were actually read.** Supersedes the
earlier version of this file. Sections §3, §5, §6 and §8 changed materially — the earlier
"delete the black clips" product question was **wrong** and is retracted in §2.1.

You are taking over a bug that has survived many attempts. Read this whole file before
touching code. **Come at it from a different direction than §5** — those angles are spent.

---

## 0. The one-paragraph version

Preview plays this project perfectly, with no dropped frames, and masks look *better* in
preview than in export. That is because **preview and export are two different renderers**:
preview composites on the GPU with SDF masks in the shader (`FxPreviewTextureView` +
`MaskSdf.GLSL_MASK_FN`); export composites on the **CPU with `Canvas`** (`CompositeExportOverlay.getBitmap`),
bakes masks with `MaskPathBuilder`, and then uploads the **whole 1080×1920 bitmap to the GPU
every frame** (8.3 MB/frame), twice per frame (two `OverlayEffect`s in the chain), plus 4×
`ImageBlendGlEffect` overlay texture fetches. The phone is not limited. Export is throwing
its frame budget at CPU raster + CPU→GPU traffic that preview never pays. Measured decay:
**23× → 3.4× → 0.8× → 0.27× → 0.13×**, then a hard stall that trips the watchdog. The
structural fix is to make export use the renderer preview already has.

---

## 1. The job

Project `a32d24e2-6b8d-4bd8-8432-ef5a6169dcfc` must export to a complete ~48:27 video in
**one user action** on JoyRaptor's Samsung Galaxy Note 20, and it must be **fast** (target:
better than 20× realtime, since preview already achieves that) without losing quality.

Owner: **JoyRaptor** (not an engineer — plain language in the UI, no jargon in dialogs).
Repo `C:\+Projects\Screenrecorder\FadCam`, branch `joy-creator`, app id `com.fadcam.beta`,
the Faditor editor inside Joy Creator.

### Hard rules (violating these has already destroyed user data)

1. **NEVER `adb uninstall`.** Projects live in app-private storage. A prior lane uninstalled
   and destroyed 23 of 24 projects irrecoverably. Update-only: `adb install -r`.
2. **Back up projects first** (`adb exec-out run-as com.fadcam.beta tar cf - files/faditor/projects > backup.tar`).
3. **Never run Gradle directly.** Build with `.\tools\phone.ps1 build` and require
   `BUILD SUCCESSFUL`. A watcher (`watch-build.ps1`) may be running; `build.log` is ~210 MB
   UTF-16 and **accumulates stale errors across runs** — copy it (`cmd /c "copy build.log %TEMP%\blog.txt /Y"`)
   and read the TAIL with a seek, or trust only the script's final line.
4. **Never resolve a git merge conflict.** Stop and report at the top of your output.
5. **Never report work you have not compiled.**
6. Claim a lane in `tasks/LANES.md`. Record anything you got wrong in `tasks/lessons.md`.
7. JoyRaptor wants **one-pass completion**. Ranged/partial export is NOT an acceptable
   mitigation (it lives in `tasks/INBOX.md` as a separate future feature).
8. He wants it **much faster without losing quality**, and progress UI that never lies.

### Device

- Owner's phone: Samsung **SM-N986U** (Note 20), wireless ADB, serial `192.168.1.14:<port>`
  (port drifts — `adb connect 192.168.1.14:41763`, check `adb devices`).
- Sandbox: SM-N960U (Note 9), `JoyRaptor`, USB. **Do not test on it** — no owner
  data, different hardware.
- **`tools/phone.ps1` picks the FIRST attached device.** With both attached it silently
  installs to the sandbox. Always set `$env:PHONE = "<note20-serial>"` first.
- PC VPN breaks wireless ADB (`10013` / `connection reset`). Reconnect with `adb connect`.
- ADB binary: `& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s <serial> ...`
  (`phone.ps1` resolves it). Shell: Windows PowerShell 5.1. `adb` is not on PATH.

---

## 2. The project (exact facts, measured)

- 24 clips on the master ("spine") track. Sum of trimmed durations = **2,907,125 ms = 48:27.2**.
- **0 transitions.** No loops. All speeds 1.0. `audioClips=3`. Canvas preset `9_16`.
- Clips **2–21** are *windows into ONE file*: `The_woman_and_the_5.mp4`, a **2.2 GB
  fragmented MP4** with **1348 `moof` boxes** (a screen recording). Windows are scattered
  across the source; deep seeks into this container are expensive (see §3.6).
- Clip 0 = camera video (`Faditor_20260621_030854.mp4`, 5,226 ms).
- Clip 22 = 5,000 ms image asset. **Clip 23 = `files/images/faditor_gap_black.png`,
  `imageClip=true`, "Blank (auto)", 260,821 ms.**
- Remuxed helper (persistent, correct home): `files/faditor/remuxed/
  The_woman_and_the_5-remuxed-1434.mp4` = 2,368,303,641 bytes.
- Clip table (trim ms):
  ```
  0:5226  1:13892  2:1567   3:7428   4:10757  5:66548  6:5870   7:26957
  8:131595 9:54749 10:171016 11:6024 12:98197 13:267174 14:440521 15:168906
  16:226914 17:27593 18:103665 19:201570 20:244911 21:360224 22:5000 23:260821
  ```

### 2.1 RETRACTED: "the black clips are wasted space"

**They are not.** JoyRaptor: *"the black clips have audio playing and animated image
overlays… I am simply not using the spine because I don't have video content, but I am
generating visual content. Black spine should never be seen as wasted space when there are
other images and or sound playing — it's auto extending the project as necessary. It was
designed that way."*

The blank spine clip is a **canvas** carrying layered content (animated image overlays,
text, waveforms, audio). It auto-extends to fit. Any fix that skips, shortens, or "optimizes
away" blank spine clips **destroys user content**. Note this also means the export's
per-frame composite load is heaviest exactly where the spine is blank — the content lives in
the overlay layers, which is the slow path (§3).

---

## 3. What the evidence actually says (stop guessing — this is measured)

### 3.1 THE SPEED BUG: measured pace profile (from `trace1217.txt`, single-pass)

Project is 2,907 s of content. Instantaneous rate (content s per wall s):

| wall window | rate |
|---|---|
| 0–26 s | **23×** |
| 26–104 s | 3.4× |
| 104–351 s | 0.82× |
| 351–1216 s | 0.27× |
| 1216–2581 s | **0.13×** |
| 2581–5553 s | 0.15–0.23× |
| 5553 s → | **zero frames for ~7 min** → watchdog |

Yes, this is the speed bug, and it is not subtle: **~200× decay**, then a true deadlock
(the watchdog fires on *zero* output for 120 s / 300 s, not on slowness — at 0.23× it was
still emitting ~700 frames per 120 s). JoyRaptor's read is right: *"it flies through the
rendering and gets slower the longer it goes… it wasn't overworking but getting starved of
something."*

### 3.2 Why: export and preview are DIFFERENT RENDERERS (this is the answer)

| | preview | export |
|---|---|---|
| compositor | `FxPreviewTextureView` — one GL pass | `CompositeExportOverlay.getBitmap()` — **CPU `Canvas`** |
| masks | `MaskSdf.GLSL_MASK_FN` — SDF **in the shader** | `MaskPathBuilder` — **Canvas path clipping** |
| per-frame cost | 1 shader draw | CPU raster of text/captions/waveforms/masked images **+ full-frame bitmap upload** |
| mask fidelity | correct | visibly worse (JoyRaptor confirms) |

The export effect chain is **8–9 effects per item**:

```
Presentation, OverlayEffect(CompositeExportOverlay), ImageBlendGlEffect ×4,
OverlayEffect(CompositeExportOverlay), Presentation
```

Per output frame that is approximately:

- **2× full-frame CPU `Canvas` raster** (two `OverlayEffect`s, each a `BitmapOverlay`)
- **2× 8.3 MB synchronous CPU→GPU texture upload** (1080×1920 ARGB_8888)
- **4× `ImageOverlayFrameOverlay.getTextureId()`** (each its own raster/upload)
- masks rasterized on the CPU instead of evaluated in the shader

~50 MB of CPU raster + ~50 MB of texture upload **per frame**. Preview does essentially none
of this. That is "what gives" — the phone is not limited; export is spending its frame
budget on CPU→GPU traffic and Canvas work.

**The smoking-gun detail** — `CompositeExportOverlay.getBitmap()` already has a ping-pong
optimization (two scratch bitmaps + a shared `emptyBitmap`), and its comment says outright:
*"what stops is the CPU→GPU traffic."* It eliminated the per-frame **allocation**. But Media3's
`BitmapOverlay.getTextureId()` is:

```java
if (bitmap != lastBitmap || generationId != lastBitmapGenerationId) {
    GlUtil.setTexture(lastTextureId, bitmap);   // full-frame upload
}
```

Ping-pong makes `bitmap != lastBitmap` **true every frame by design**, so the upload still
happens every frame. Only the **empty** frames skip it (return the same `emptyBitmap`
twice → Media3 skips the upload). The moment one overlay is actually visible you pay 8.3 MB.

**This is also why it "flies then crawls" rather than being uniformly slow**: early timeline
is sparse → many empty frames → cheap. Later timeline is where the animated overlay content
lives (§2.1) → every frame draws → every frame uploads. Per-frame cost tracks **how many
overlay layers are active at that point in the timeline**, not merely session age.

### 3.3 Accumulation evidence (the prior "no leak" conclusion was drawn from the wrong run)

A previous analysis looked at the **chunked** run's `MEM` log (`native=85–86MB` flat) and
concluded there was no leak. That run resets every ~5 minutes per chunk, so it cannot show a
session leak. In the single-pass runs that actually decayed:

| run | `native=` | `threads=` | outcome |
|---|---|---|---|
| 15:55 | 111 MB → 198 MB → **plateau 191 MB** | 26 → 31 (flat) | **completed** (produced a file) |
| 17:05 | 16 MB → 183 MB in 76 s | 25 → 33 | (short window observed) |
| 17:07 | 112 MB → **393 MB, never plateaus** | 26 → **46** | died at 70% |

`dalvik` oscillates 13–99 MB throughout = GC churning. So there **is** accumulation, it is
native/graphics rather than Java, and the run that found a plateau is the run that finished.
Threads grow by 20 over a session. Candidates: GL textures/FBOs not deleted at item teardown
(`ImageBlendGlEffect.Program` calls `overlay.release()` at :753 but `MeshStampGl` may not),
and bitmaps held per active overlay.

### 3.4 We have been timing the wrong 1%

`EXPORT_COST` only ever measured `CompositeExportOverlay`:
`frames=79389 overlayDrawMs=103281 emptyFrames=79389 avgMsPerFrame=1` — 1 ms/frame and
**all frames empty**. That is the cheap path. The four `ImageBlendGlEffect`s and the
CPU→GPU uploads are **completely unmeasured**. Do not trust any conclusion that does not
first instrument them (§8.1).

Also unmeasured: `ImageBlendGlEffect.Program` has `texturePoolCapacity=1` (a single texture
slot → serialize-and-wait), calls `overlay.getTextureId(presentationTimeUs)` per frame in
**two** code paths (:449 flat, :630 mesh fallback), and renders a `MeshStampGl` FBO **every
frame** for bent/meshed images. `transform-diag.log` shows `bend commit hasMesh=true` and
`baked id=…` entries — this project **does** use bend/mesh.

Shader compile count per session: 24 items × 4 effects × 2 programs (flat + mesh) = **192
`GlProgram` compiles**. Not obviously the dominant cost, but it is unbudgeted.

### 3.5 The historical single-pass failures (all land at the same place)

| run | symptom | cause |
|---|---|---|
| 04:09, 13:09, 01:50, 13:56 | watchdog `Abort: no output sample written in the last 120000 ms` | see below |
| 11:32 | `FileNotFoundException cache/remuxed/… ENOENT` at 28 min | 2.2 GB remux lived in `getCacheDir()` and was evicted mid-export |
| 15:55 | completed, but video **black after 1:42** (130.8 MB) | v1 pre-trims cut with `-ss` before `-i` + stream-copy ⇒ zero-based PTS while composition clips in absolute source time ⇒ windows seek wrong |
| 17:07 (failed 18:51) | `lastSeam=item 19`, watchdog `Muxer error` at 70% | see below |

**The stall is at the item 19→20 boundary (comp ~2,036,169 ms = 70.05%), not "at item 19".**
Traces show it *entering* item 19 at 64% and continuing to 70% (the end of item 19) before
going silent for 7 minutes. `lastSeam=item 19` only means the item-20 seam log was never
reached.

Crucially: the **chunked run covered that exact boundary** (chunk4 = clips 19–20) and
completed it fine in a fresh session (134 MB, done). So the content is not intrinsically
broken — it is where accumulated resource debt (§3.3) tips into a true wait. Do not go
hunting for a corrupt sample at source 2,074,562–2,114,787 ms as the primary theory.

Thermal is ruled out: the SoC was **cooling** (75 °C → 45 °C) during the 12:17 failure, and
JoyRaptor confirms the phone is not hot. Decoder is hardware throughout
(`OMX.qcom.video.decoder.avc`); audio is **software** MP3 (`c2.android.mp3.decoder`,
`DECODER_PICK audio/mpeg → offering SOFTWARE`) — unmeasured, see §8.6.

### 3.6 A SECOND, SEPARATE speed bug: fragmented-MP4 deep seeks

Chunk wall times from the overnight chunked run, against content length:

| chunk | clips | content | wall | rate | source offsets |
|---|---|---|---|---|---|
| 0 | 0–10 | 8.3 min | ~6 min | **1.4×** | low |
| 1 | 10–14 | 16.4 min | 47 min | 0.35× | mid |
| 2 | 14–16 | 10.2 min | 68 min | 0.15× | deep |
| 3 | 16–19 | 6.0 min | 52 min | 0.11× | deep |
| 4 | 19–21 | 7.4 min | 30 min | 0.25× | deep |
| 5 | 21–23 | 6.1 min | 57 min | 0.11× | deepest |
| 6 | 23–24 | 4.3 min | 78 min | — | n/a (image) |

Each chunk is a **fresh** Transformer/codec/muxer, so this is *not* session decay. Rate
tracks **source offset** in the 2.2 GB fragmented file (1348 `moof` boxes) — classic
`moof`-walking on deep seek. This is why even a perfectly fresh session renders the deep
timeline slowly. Separate fix from §3.2 (§8.7).

### 3.7 Chunked driver: it WORKED — 6 of 7 chunks are good (1.78 GB)

```
files/faditor/chunks/a32d24e2-6b8d-4bd8-8432-ef5a6169dcfc/
  chunk0.mp4  244,803,183 B  21:01  done  clips 0..10
  chunk1.mp4  460,732,039 B  21:48  done  clips 10..14
  chunk2.mp4  477,475,260 B  22:56  done  clips 14..16
  chunk3.mp4  297,416,902 B  23:48  done  clips 16..19   ← contains the old "fatal" seam
  chunk4.mp4  134,936,823 B  00:18  done  clips 19..21   ← contains the 19→20 stall point
  chunk5.mp4  165,313,082 B  01:15  done  clips 21..23
  chunk6.mp4  114,772,326 B  02:33  FAILED clips 23..24  ← see §3.8
  manifest.json
```

Chunks 0–5 are valid video covering clips 0–23. The old stall never recurred. **Do not throw
chunking away** — it is a working safety net and resume mechanism.

### 3.8 The chunk6 failure is a REAL BUG (tail-filler math), not a fluke

Failure text: `Part 7 of 7 came out short — will retry` from `chunkFileValid()` at
`ExportManager.java:1696`. It is a false positive *and* chunk6.mp4 is genuinely wrong.

`buildComposition()` appends a black **tail filler** when it thinks it is on the last chunk:

```java
boolean isLastChunk = chunkClipEnd < 0 || chunkClipEnd >= timeline.getClipCount(); // true for chunk 6
tailMs = projectTotalMs - timelineCursorMs;   // timelineCursorMs is CHUNK-RELATIVE!
```

`timelineCursorMs` restarts at 0 per chunk, so for chunk 6 it is only clip 23's 260,821 ms,
while `projectTotalMs` is the whole 2,907,125 ms. Result: a **2,646,304 ms (44 min) black
filler** appended to a 4:20 clip. Confirmed independently in logcat:
`EXPORT_COST clip@260821ms frames=79389` → 79,389 / 30 fps = **2,646 s = 44.1 min**, all
frames empty. So `chunk6.mp4` is a 48:27-long file, not a 4:20 part.

`chunkFileValid()` then compares `probeVideoDurationMs(chunk6)` (~2,907,125 ms) against
`rangeExpectedMs(23,24)` = 260,821 ms with a 26 s tolerance → rejected. The message is
misleading (it came out **long**, not short) and "will retry" never retries.

**Fix shape — do not just widen the tolerance; chunk6.mp4 is wrong and must be re-rendered:**
- Compute the tail against the **absolute** cursor: `tailMs = projectTotalMs - (chunkBaseMs + timelineCursorMs)`, skip when `<= 0`.
  For chunk 6: `2,907,125 − (2,646,304 + 260,821) = 0`. Correct.
- Keep the validator but report both numbers (`expected X ms, got Y ms`) and name the part.
- Delete `chunk6.mp4`, leave `"done": false` for index 6, and resume — **only ~4 minutes of
  work remains**, not 5 hours. (Do this *after* the renderer work if you want the re-render
  to also be fast.)
- Irony: `chunkBaseMs` and `builtRangeDurationMs()` already exist to keep overlay clocks
  absolute and to measure built durations exactly. The tail filler was the one site that kept
  using the chunk-relative cursor. Respect the auto-extend design (§2.1) — the filler is
  legitimate when the project genuinely runs past the master track; it just must use
  absolute time.

### 3.9 Pre-trims are essentially NOT happening (contributing to §3.6)

`files/faditor/pretrim/` holds **only 2 files**, both stamped **17:05** (from the run *before*
the chunked build):
```
The_woman_and_the_5-remuxed-1434-266-1833-94867-v2.mp4      2.1 MB   (clip 2 window)
The_woman_and_the_5-remuxed-1434-3704-11132-94867-v2.mp4  11.1 MB   (clip 3 window)
```
The 5.5 h chunked run baked **zero** new pre-trims. Suspect: the chunked driver calls
`runVideoChunk` directly and bypasses `ExportService`'s warm phase (where `PreTrimCache`
bakes). Verify before trusting the cache. Note the directory is `files/faditor/pretrim/`
(singular) — earlier checks of `pretrims/` (plural) looked empty for the wrong reason.

### 3.10 Fixed and landed — do not redo

- Remux helper → `files/faditor/remuxed/` (persistent) + migration + prune(30 d, 8 GB).
  Proven: the 2.2 GB file survives there. Fixes the 11:32 ENOENT.
- `-copyts` + `v2` pre-trim keys + one-time v1 sweep (fixed the black-after-1:42 file).
- Atomic staging `<name>.exporting` + commit-on-success; SAF-copy-failure keeps the muxed file.
- Resampling audio EOS exact-length fix (measured 477 ms A/V drift on a partial).
- Clip 0 was internally inconsistent (video ended 4.750 s, audio 5.226 s); repaired with
  `tpad=stop_mode=clone`, original backed up locally, repaired file pushed to device.
- Watchdog 120 s → 300 s. Failure-cause taxonomy (`ExportFailureCause`).
- Progress/phase UI (`onChunkPhase` → "Part i of n" / "Sound" / "Joining"), notification
  ledger, warm-phase probe (`probeClipWindows`), `PreTrimCache.probeVideoDurationMs()`.
- `CompositeExportOverlay` ping-pong + shared `emptyBitmap` (removed per-frame **allocation**;
  the per-frame **upload** remains — see §3.2).
- `ImageOverlayFrameOverlay` static-bitmap reuse fast path.
- Chunked driver: `exportChunked()` step machine (video chunks 0..N-1 → one audio pass →
  ffmpeg concat+mux → shared finalize), manifest resume, `ChunkProgressAdapter`. Short
  timelines (< 12 min) take the legacy path unchanged (byte-identical).
- Media3 1.8.0 API gotcha (in `tasks/lessons.md`): `Composition.sequences` and
  `EditedMediaItemSequence.editedMediaItems` are **public fields**, not getters; `EditedMediaItem.durationUs`
  likewise. Patched source at `C:/+Projects/Screenrecorder/media3-patched/` (see
  `local.properties` → `media3.patched.path`); the classes live under `libraries/transformer/…`.

### 3.11 Code map

- `app/src/main/java/com/fadcam/ui/faditor/export/CompositeExportOverlay.java` — **the CPU
  compositor**. `getBitmap()` at :597, ping-pong note at :1137, empty-frame branch at :1152.
  This is the slow path.
- `export/ImageBlendGlEffect.java` — blend/mask/FX for image overlays. `Program` at :332
  (`texturePoolCapacity=1`), `drawFrame` at :437, `drawFlatFallback` at :622, `meshStamp` /
  `MeshStampGl` (per-frame FBO render for bent images), `overlay.release()` at :753.
- `export/ImageOverlayFrameOverlay.java`, `export/ImageOverlayDraw.java` (Canvas draw +
  `MaskPathBuilder`), `export/GlPipFrameOverlay.java` + `export/SurfaceFrameReader.java`
  (per-frame `updateTo()` → `MediaExtractor.seekTo` for video PiPs — **unmeasured**, see §8.6).
- `ui/faditor/compositor/FxPreviewTextureView.java` — **the good renderer**. `MaskSdf.GLSL_MASK_FN`
  spliced at :194, mask fold at :215, `MaskSdf.packShapes`/`packOps` at :917–923. Port
  target for §8.1.
- `ui/faditor/compositor/FxLivePreviewController.java`, `OverlayVideoPreviewView.java`
  (Canvas preview path using `MaskPathBuilder` at :1103–1137 — note preview has *both* paths).
- `model/MaskSdf.java`, `model/MaskPathBuilder.java` — the two mask implementations.
- `export/ExportManager.java` — `export()`, `exportChunked()`, `runChunkStep()`,
  `runVideoChunk()`, `runChunkAudio()`, `runChunkJoin()`, `builtRangeDurationMs()`,
  `chunkFileValid()`, `computeChunkRanges()`, `buildComposition()` (tail filler ~:3166),
  `buildClipItem()`, `buildAudioOnlyComposition()`, `editorTimeOffsetForChunk()`, staging,
  `trace`, `LoudnessTarget`.
- `export/ExportService.java` — foreground service, warm phase (remux verify, probe, pre-trim
  dispatch), notifications, `onChunkPhase`, terminal-result ledger.
- `export/PreTrimCache.java`, `playback/FragmentedMp4Remuxer.java`,
  `export/ResamplingAudioProcessor.java`, `export/ExportFailureCause.java`,
  `export/BlendModeGlEffect.java`, `export/TextFxGlEffect.java`, `export/SpriteBlendGlEffect.java`.
- `ui/faditor/FaditorEditorActivity.java` — `exportUiOnProgressDetailed()`,
  `lastExportPhase`, resume ledger catch-up. 45k lines — do not reformat.
- `res/values/strings.xml` — watch the UTF-8 BOM (a past lane wrote UTF-16 and broke 2779
  strings silently).

---

## 4. Where the artifacts are

On device (`adb -s <note20> shell "run-as com.fadcam.beta …"`):
- `files/faditor/chunks/a32d24e2-*/` — manifest + 7 chunk files (1.89 GB) — **keep these**
- `files/faditor/pretrim/` — 2 stale v2 pre-trims
- `files/faditor/remuxed/The_woman_and_the_5-remuxed-1434.mp4` — 2.2 GB helper
- `files/faditor/projects/a32d24e2-*/project.json` — the project
- `files/faditor/transform-diag.log` — overlay/mesh bake diagnostics
- Export error/trace files are written by `writeExportErrorLog`/`openTrace` (logcat said
  `Wrote export error log: …`) but `files/faditor_export_errors/` was **empty** when checked —
  verify where they actually land and pull them.

Locally (`C:\Users\JoyRaptor\AppData\Local\Temp\opencode\`):
`trace1217.txt` (**read this one — it has the pace curve**), `trace1555.txt`, `trace1705.txt`,
`trace1707.txt` (**has the MEM curve**), `err0150/0409/1132/1309/1356/1851.txt`,
`logcat.txt`, `proj_a32d.json`, `smoke2051.png`.

Backups: `projects_backup_20260921_2330.tar`, `projects_backup_20260922_1130.tar`.

---

## 5. EVERYTHING ALREADY TRIED (complete record)

Four buckets. Read all four: **5.2** is what is disproved, **5.3** is what landed but did
*not* fix the bug (do not redo, and do not credit), **5.4** is what only half-worked and is
probably where the remaining cost lives.

### 5.1 Run log — every export attempt and its outcome

| # | when | build/fix in place | outcome |
|---|---|---|---|
| 1 | 09-21 04:09 | baseline | watchdog `Abort: no output sample written in the last 120000 ms`, output stuck at **30:35** |
| 2 | 09-21 11:32 | baseline | `FileNotFoundException cache/remuxed/… ENOENT` at 28 min — 2.2 GB remux evicted from `getCacheDir()` mid-export |
| 3 | 09-21 13:09 | baseline | watchdog again at 30:35 |
| 4 | 09-22 01:50 | instrumentation + remux home + staging + probe + causes + progress UI | watchdog at item 19 |
| 5 | 09-22 12:17 | + thermal/MEM telemetry, seam timing | watchdog at 70%; **trace captured the 23×→0.13× decay** (`trace1217.txt`) |
| 6 | 09-22 13:56 | same | watchdog again at item 19 |
| 7 | 09-22 15:55 | + v1 pre-trim cache | **"completed"** but output is **black after 1:42**, 130.8 MB — v1 pre-trims were zero-base PTS |
| 8 | 09-22 17:05 | + `-copyts` / v2 pre-trims | short trace window only (`trace1705.txt`) |
| 9 | 09-22 17:07 → 18:51 | + `-copyts`/v2, resampler EOS fix, clip-0 repair, watchdog 300 s | died at **70%**, `lastSeam=item 19`, `Muxer error` (`err1851.txt`, `trace1707.txt`) |
| 10 | 09-22 20:55 → 09-23 02:33 | + **chunked driver** | **6 of 7 chunks good (1.78 GB)**; chunk6 failed on tail-filler false positive (§3.8). Took 5.5 h |

Ten attempts. The only one that produced a complete-length file was #7 and its video was
corrupt. #10 is the closest to success: valid video for clips 0–23 already exists on device.

### 5.2 Theories DISPROVED — stop re-proposing these

1. **Thermal throttling.** SoC was *cooling* (75 °C → 45 °C) during the 12:17 failure; the
   pace was decaying while temperature dropped. JoyRaptor confirms the phone is not hot.
2. **Software decoder fallback.** `DECODER_PICK video/avc` → `OMX.qcom.video.decoder.avc`,
   hardware, every item, every run. (Audio *is* software — different thing, §8.6.)
3. **A corrupt sample / `moof` pathology at the 19→20 boundary** as the primary cause.
   Chunk4 (clips 19–20) crossed that exact boundary fine in a fresh session (§3.5). Not
   saying the file is pristine — saying this is not why it dies.
4. **Java heap leak / thread explosion as the root cause.** `dalvik` oscillates 13–99 MB (GC
   churning, bounded). The growth is `native=` and `threads=`, and only in single-pass
   sessions (§3.3) — so "no leak at all" is also wrong. Both naive versions are dead.
5. **Blank spine clips are wasted space to optimize away.** **Retracted** — see §2.1. Any
   fix that skips/shortens them destroys user content.
6. **"The export just needs a bigger watchdog."** 120 s → 300 s changed nothing about the
   decay or the root cause. The watchdog is a *symptom* (§3.1: it fires on genuine zero
   output, not on slowness).
7. **Chunking is the speed fix.** Chunking fixed *completion* (and it works — keep it). It
   does not fix speed: fresh chunks still render the deep timeline at 0.11–0.25× (§3.6).
8. **"No accumulation — `MEM` is flat."** This conclusion was drawn from the **chunked**
   run, which resets every ~5 min and therefore cannot exhibit a session leak. In the
   single-pass runs that actually decayed: `native=` 112→393 MB, `threads=` 26→46 (§3.3).

### 5.3 Mitigations that LANDED and are fine to keep — but did NOT fix the core bug

These are real improvements. Do not redo them. But do not mistake them for progress on the
truncation/speed bug — **the bug survived every one of them**.

| landed | what it actually bought | why it did not fix the bug |
|---|---|---|
| Item 0 instrumentation (`remux=yes/no` per item, `EXPORT_SEAM` timing) | names the stall's item; produced the pace curve | telemetry only |
| Thermal + `MEM` telemetry at seams | disproved thermal; exposed `native=` growth | telemetry only |
| Durable per-export `trace` file | offline evidence without logcat | telemetry only |
| Persistent remux home `files/faditor/remuxed/` + migration + prune | fixed run #2's ENOENT | not the stall |
| Warm-phase remux verify + fail-fast | belt-and-suspenders vs mid-export eviction | not the stall |
| Pre-flight decode probe (`probeClipWindows`) | fail-fast on unreadable windows | not the stall |
| Atomic staging `<name>.exporting` + commit-on-success | no more half-files mistaken for exports | not the stall |
| Failure-cause taxonomy (`HELPER_LOST`, `PREFLIGHT_UNREADABLE`, `SEAM_STALL`) | honest error messages | not the stall |
| Progress/phase UI + MB + pace ETA + `Finalizing…` | honest progress | not the stall |
| DEFAULT-importance notification channel + terminal-result ledger | notices are seen; resume announces | not the stall |
| Warm-phase early-return fix (video exports now always probe) | probe/prep actually runs | pre-trims still don't bake (§3.9) |
| Probe padStart warning correction | no more false "video-ends-early" | cosmetic |
| Clip 0 repair (`tpad=stop_mode=clone`, original backed up) | clip 0 is internally consistent | not the stall |
| Watchdog 120 s → 300 s | a few more minutes of grace | see 5.2 #6 |
| Media3 1.8.0 field-vs-getter fixes + `faditor_font_*` strings | compiles | n/a |
| `PreTrimCache.probeVideoDurationMs()` wrapper | chunk file validation works | validation is wrong for the last chunk (§3.8) |

Earlier lessons already recorded in `tasks/lessons.md` (also landed, also not the fix):
`MediaMetadataRetriever` thread-local + `release()` discipline; `BlurMaskFilter`/`Shader`
allocation moved out of per-frame render paths.

### 5.4 Optimizations that only HALF-worked — the remaining cost is probably here

1. **`CompositeExportOverlay` ping-pong + shared `emptyBitmap`.** Removed the per-frame
   **allocation** (`Bitmap.createBitmap(scratch)` every frame, ~3.7 MB at 720p). Did **not**
   remove the per-frame **upload**: ping-pong makes `bitmap != lastBitmap` true every frame,
   so `GlUtil.setTexture()` still pushes the whole 1080×1920 bitmap (8.3 MB) per frame. Only
   *empty* frames skip it. See §3.2 — this is likely the dominant remaining term.
2. **`ImageOverlayFrameOverlay` static-bitmap reuse.** Helps static images. Animated/keyframed
   overlays (which is what this project is full of) still raster + upload per frame, and this
   is one of **four** `ImageBlendGlEffect`s per item. Never measured (§3.4).
3. **Pre-trim cache** (`-ss` windows → faststart files) to kill deep seeks into the 2.2 GB
   fragmented source. Mechanism is right; in practice it **baked 2 files and stopped** — and
   the 5.5 h chunked run baked **zero** new ones (§3.9). Treated as "done" when it was not.
4. **`-copyts` + v2 pre-trims.** Fixed the zero-base PTS corruption (run #7's black video).
   Did nothing for the stall or the speed — run #9 had it and still died at 70%.
5. **Audio resampler EOS exact-length accounting.** Fixed a real 477 ms A/V drift. Not the
   stall. (But note the bug proves the audio path was emitting stray samples — see §8.6.)
6. **Chunked driver** (bounded sessions + manifest resume). Fixed *completion* and *resume*.
   Not speed, and its tail-filler/validation has the bug in §3.8.

**Net:** every theory aimed at the stall has failed, and the one architectural observation
that survives all the evidence is §3.2 — export renders on the CPU and uploads a full frame
per `OverlayEffect` per frame, while preview does it all in one shader. That is the bet to
make. Measure it first (§8.1) so the next person is not guessing either.

---

## 6. What the evidence does NOT yet say (be honest about uncertainty)

- We have **no timing at all** for `ImageBlendGlEffect.drawFrame`, `MeshStampGl.render`,
  `ImageOverlayFrameOverlay.getTextureId`, `GlUtil.setTexture` traffic, or the audio graph.
  The two-renderer theory (§3.2) is strongly supported by the code structure, the ping-pong
  comment ("what stops is the CPU→GPU traffic"), the mask-quality difference JoyRaptor
  reports, and the empty-vs-drawn frame asymmetry — but it is **not yet measured**. Measure
  before doing a large port (§8.1), or at least in parallel with it.
- It is not established whether the decay is mainly (a) per-frame upload/raster cost scaling
  with active overlay count, (b) native/GL resource accumulation (§3.3), or (c) both. The
  15:55 run plateaued and finished; the 17:07 run did not. What differs between them is
  unexplained.
- Whether preview's `FxPreviewTextureView` path can run headless (no `SurfaceView`, offscreen
  FBO → encoder input surface) at full rate is an assumption. It is a good assumption (it is
  just a shader) but verify early (§8.1).
- Audio is software-decoded MP3 through Sonic/SpeedChanging/Resampling + the FX chain. It may
  be a second starvation source. Untested.

---

## 7. Open product question (ask before changing timeline semantics)

Not "should we delete the black clips" — that is settled (§2.1). The remaining question is
narrower: **when the blank spine auto-extends, is the intended output 48:27 of picture
including the blank-canvas segments (with their overlays + audio), or is the project total
inflated by the auto-extend beyond what should be exported?** The tail-filler fix (§3.8) must
agree with whatever `getTotalDurationMs()` is supposed to mean. Note `Timeline.java` already
has comments saying `getTotalDurationMs()` is inconsistent (it counts audio) — a clean
"master track end" vs "project end" distinction at the model layer would remove this whole
class of bug.

---

## 8. What I would try — ordered, each with a cheap decisive test

### 8.1 Measure the unmeasured 90% (30 min) — DO THIS FIRST
Same `EXPORT_COST` pattern (frames, nanos, empty-count) on:
- `ImageBlendGlEffect.Program.drawFrame` (:437) and `drawMeshFrame` / `drawFlatFallback`
- `MeshStampGl.render`
- `ImageOverlayFrameOverlay.getTextureId`
- count + bytes of `GlUtil.setTexture` calls (the CPU→GPU traffic)
- `CompositeExportOverlay.getBitmap` split into *drawn* vs *empty* frames (it already counts these)
- `SurfaceFrameReader.updateTo` wall time (video PiPs only)

Run **one 5-minute chunk** (reuse `chunk4`'s range: clips 19–21) via the chunked driver.
One run, one log, and we stop guessing.

### 8.2 Port export onto the preview renderer — the real fix
Make the export chain a `GlEffect` that runs the **same shader** `FxPreviewTextureView`
already uses (`MaskSdf.GLSL_MASK_FN` + the FX splice), instead of `CompositeExportOverlay`
returning a Bitmap. Kills the CPU raster, kills the 8.3 MB/frame uploads, and makes masks
identical to preview (i.e. correct). Verify the headless assumption from §6 first. This also
closes the "masks look worse in export" complaint, which is a correctness win, not just speed.

### 8.3 Cheap wins while §8.2 is built
- **Merge the two `OverlayEffect`s into one composite pass** — halves the upload traffic today.
- **Dirty-rect upload**: `glTexSubImage2D` on the changed region instead of `GlUtil.setTexture`
  of the whole frame. When one small image animates we currently push 8.3 MB to change ~200 KB.
- **Cache `GlProgram` compiles** by shader-source hash (192 compiles/session today).
- **Release `MeshStampGl` + overlay GL resources at item teardown**; assert texture count and
  `glGetError` in a teardown log line to close out §3.3.

### 8.4 A/B the effect chain (30 min, decisive, independent of §8.2)
Same one-chunk harness as §8.1, three variants: 4 blends → 1; mesh/bend off; `ImageBlendGlEffect`
removed entirely. Whichever restores 20× identifies the dominant term empirically.

### 8.5 Fix the tail filler (§3.8) and keep the chunked driver as the safety net
Plus: make chunking **adaptive** — end the session when pace drops below 1× and start a fresh
one. Self-heals against residual decay and gives free resume. Fix the error message to say
"length mismatch (expected X ms, got Y ms)" and actually retry once.

### 8.6 Measure the audio path
Run `exportAudioOnly` standalone and time it. If it also decays, it is a second starvation
source (software MP3 + Sonic + Resampling + FX). Also check `SurfaceFrameReader.updateTo` —
it does `MediaExtractor.seekTo` per frame for video PiPs.

### 8.7 Kill the fragmented-MP4 deep-seek cost (§3.6)
Defragment the source once: `ffmpeg -i in.mp4 -c copy -movflags +faststart out.mp4` →
`files/faditor/`. Turns `moof`-walking seeks into `stss`/`stco` binary search. One-time cost,
permanent win, and it may let the pre-trim cache be simplified or dropped entirely. Check free
space first (18 GB free as of 09-22; the source is 2.2 GB).

### 8.8 Fix `PreTrimCache` population (§3.9) — lower priority if §8.7 lands
Find why the chunked driver bakes nothing (likely: it bypasses `ExportService`'s warm phase).
Instrument `bakeSync`/`hasPreTrim` before changing behaviour.

---

## 9. Definition of done

Project `a32d24e2` exports to a complete **48:27** file in **one user action** on the
Note 20, at **≥ 20× realtime** (preview proves the hardware can), with picture + sound
throughout (spot-check ~0:00, 1:42, 30:35, 47:00) and **masks matching preview**. Progress
UI honest the whole way. Then: `BUILD SUCCESSFUL` from the watcher path, work staged and
committed to `joy-creator` with a clear message, `tasks/todo.md` review appended,
`tasks/lessons.md` updated with anything you got wrong first.

## 10. Suggested first hour

1. Read `trace1217.txt` (pace) and the `MEM` lines in `trace1707.txt` yourself — confirm §3.1
   and §3.3 before believing me.
2. Do §8.1 (instrument) + §8.4 (bisect) on one chunk. Two short runs, and the dominant cost
   stops being a theory.
3. Land §8.3's cheap wins for an immediate relief build while §8.2 is designed.
4. Ask the §7 product question before touching `getTotalDurationMs()` semantics.

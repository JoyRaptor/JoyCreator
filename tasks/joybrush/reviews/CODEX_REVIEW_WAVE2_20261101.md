# Joy Creator: second-wave engine, VisLab and Studio review

November 1, 2026. Reviewed `C:/+Projects/JoyCreator`, branch `joy-creator`, HEAD `ef47451afd89d7971d9ec03458ea2a50cbbe3792`. HEAD and tracked source remained unchanged during this review. No project files were modified. Klaw is handling the six first-wave findings; they are excluded here.

This is a source review with two small JVM probes, not a whole-product acceptance test. P1 means a work-preservation or crash issue to address first; P2 means a concrete functional/performance defect. Optimization entries identify avoidable work; their device speedup is not measured. Priority is largest to smallest within each requested wing. Source links point to the checked checkout and must be reconciled if Klaw changes those files.

## Engine: recommended order

### E1 — P1: reopening a drawing can race its outgoing save

[Restore starts immediately](C:/+Projects/JoyCreator/joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/JoyBrushActivity.kt:2391); [outgoing write is queued only after GL snapshot](C:/+Projects/JoyCreator/joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/JoyBrushActivity.kt:2279).

Home calls `finish()` immediately (line 724). `onPause` requests an asynchronous snapshot. Before that callback queues its disk write, a new Activity can queue `readWorking` for the same drawing. The shared disk executor serializes tasks already queued; it cannot order a read behind a write that has not been queued yet. Thus an immediate reopen can load the previous revision. The old Activity can subsequently save the newest revision, then an edit/save in the stale reopened Activity overwrites it.

**Change:** use a shared per-drawing save-completion/revision barrier, registered before starting the outgoing GL snapshot; reads of that drawing await it. Avoid blocking the UI thread. Preserve timeout/failure handling and let the user know if reopening must wait.

**Check:** delay the old GL snapshot, leave, immediately reopen the same file, release the old snapshot, edit and save. Both the outgoing marks and new marks must survive. Include activity recreation and failed outgoing save.

### E2 — P1: ordinary PNG/ORA export can crash on valid large drawings

[Writer catches only Exception](C:/+Projects/JoyCreator/joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/JoyBrushActivity.kt:2313); [renderer allocates full-region float scratch](C:/+Projects/JoyCreator/joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/render/RegionRenderer.kt:381).

The export retains the full drawing snapshot while allocating a `width * height * 4` float array. An allowed 3840x2160 region requires 126.56 MiB of float scratch, plus 31.64 MiB of RGBA output and encoding overhead. A 1711-nonblank-tile snapshot alone is 427.75 MiB. Snapshot plus scratch already exceeds a 512 MiB heap, before the running app. `OutOfMemoryError` escapes this asynchronous worker boundary; neighboring load/board-export paths already handle it explicitly.

**Change:** render/encode in bounded strips or tiles and take a target-specific snapshot (E4). Add a last-resort OOM refusal at the export boundary that completes the request and preserves drawing/save state. Merely raising a size cap is not a fix.

**Check:** export a small and 4K board from a large multilayer drawing under constrained heap. Compare pixels, paper, masks, blends and transparent output; allocation failure must report a refusal without terminating the app or damaging previous files.

### E3 — P2: atlas admission still uses the old fixed-window memory estimates

[Fixed held-byte accounting](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaCanvas.kt:227); [water expansion guard](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaCanvas.kt:625); [actual water allocations](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaLayerEngine.kt:359).

The checks reserve the old 1024-square window: 132 MiB dry and 240 MiB wet, so adding water asks for 108 MiB. Actual atlases grow to 2048-square. From the current allocation formats, adding full-resolution water there adds about 448 MiB. The optional water park adds up to another 138 MiB at its default 24 tiles; that is absent from `heldBytes` too.

The JVM probe confirms the guard permits adding water with 600 MiB available and a 226 MiB low-memory threshold. Its stated policy leaves twice that threshold, but the real allocation is several times its reservation. This does not establish the cause of the owner's previous lost tiles; it establishes incorrect admission accounting.

**Change:** one ledger for actual allocated bytes and planned peak additions: atlas dimensions, lazy water targets, park growth, media stores, history and transient export/readback. Admit before allocation; release failed partial allocations. Preserve the full-resolution appearance.

**Check:** compare ledger against `memoryReport` for minimum/maximum atlases, dry-to-wet, park growth and release. Inject allocation failures and verify existing artwork/history remain intact.

### E4 — major optimization: make save/export readback bounded and destination-specific

[Per-store scratch allocation](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt:7138); [full snapshot on every destination](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt:3112).

Every float-store tile allocates a fresh 1 MiB direct buffer, 1 MiB float array, and 512 KiB half-float result. Reading 512 such stores creates 512 MiB of direct-buffer churn and 512 MiB of float-array churn, beyond retained output. This happens on the GL thread. Reuse one readback buffer and convert directly to the destination half bytes; consider chunked snapshot/archive writing with immutable revision ownership.

Rendered exports also read all physics stores before `ExportPicture.asShown` discards them. Per oil tile, p0+p1+ground adds 1.25 MiB and three readbacks; wet paint can add 2.25 MiB and five. PNG and image-board exports need the final appearance, not simulation restart data. A picture-only, target/frame-specific snapshot can avoid those reads while full saves retain every required store. Keep ORA's layer/mask needs explicit rather than flattening it.

**Expected benefit:** less save/export pause, allocation churn and peak memory. The bytes are source arithmetic; no device milliseconds or speed multiplier are claimed.

**Check:** byte/pixel parity, save/reopen simulation state, negative tiles, all frame cels, editable lines/slabs, cancellation, concurrent new strokes and graphics loss during capture.

### E5 — major optimization: cache and cull live stroke previews before rendering them

[Whole-stroke preview preparation](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt:7344); [every touched tile rerendered](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt:7407); [pool capped at 64](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt:7597).

A visible mask, clipping layer or non-normal blend routes the drawing through the compositor. Each frame it recreates previews for every tile the live stamp stroke has touched, before screen culling. Completed/offscreen stroke portions are redrawn too. At 500 touched tiles that is 125 MiB of temporary RGBA8 outputs each frame. Because all outputs coexist until drawing finishes and the recycle pool retains only 64, subsequent frames can allocate/delete 436 textures repeatedly.

**Change:** keep revisioned preview tiles, refresh only dirty stroke tiles, and request only tiles needed for visible layers/masks/clip dependencies. Do not remove mask/clip reads on the assumption they are offscreen without proving the dependency.

**Check:** identical pixels with masks/clipping/blends; long stroke while panning; changes to base/layer opacity; pen-up, cancel and context-loss cleanup. Profile allocation count and GPU work versus touched-tile count.

### E6 — P2: stationary oil/water input is swallowed by the spline

[Stationary reports are replaced without being delivered](C:/+Projects/JoyCreator/joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/media/MediaSpline.kt:21).

The spline sends the first point downstream, then drops same-position reports and finishes nothing for a one-point path. `PasteStroke` and `WetStroke` require a subsequent report; their explicit stationary deposit/dwell branches never receive these updates. A stationary tap/hold can paint nothing, and pressure/tilt changes while staying in place are lost. The oil JVM probe produced 60 steps when fed directly and zero through the production spline for the same one-second hold.

**Change:** preserve time/contact-channel updates independently of positional resampling, without introducing extra motion dabs. Define touchdown/tap deposition deliberately for each brush, especially blades that should deposit only while moving.

**Check:** exact-position hold with varying pressure/tilt, tap, tiny jitter, water dwell, oil round, knife and scraper; compare direct builders with the full host input pipeline.

### E7 — P2: distant small marks can exceed the atlas table despite ample slots

[Dense bounding-box table limit](C:/+Projects/JoyCreator/joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/media/MediaAtlasPlacement.kt:120).

The table covers the rectangular extent of all resident tiles and refuses an area over 4096 entries. Two resident tiles at (0,0) and (80,80), only 20,480 document pixels apart on each axis, require 6561 table cells and fail. A small stroke after panning to a distant area can hit this while old unpinned tiles remain cached; this is not actual paint-slot exhaustion. The JVM probe reproduces the two-page refusal.

**Change:** evict/write back unrelated unpinned regions before a table-extent refusal, or use sparse/windowed addressing with explicit support for simultaneously pinned distant symmetry regions. Reuse table buffers and avoid rebuilding/reallocating the table texture for unchanged residency.

**Check:** tiny distant strokes, multiple boards, negative coordinates and distant symmetry copies. Budget by actual working pages; never discard dirty paint to shrink the table.

### E8 — P2: editable-line drawings leave blank/stale Library thumbnails

[Thumbnail renderer uses the unadapted document](C:/+Projects/JoyCreator/joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/library/DrawingLibrary.kt:518).

The thumbnail path passes ordinary-layer line/slab metadata into `RegionRenderer`, whose support guard rejects it. `onSaved` catches/logs the error, leaving the old or absent picture. Restore/fill-missing use the same helper. Actual exports already apply `ExportPicture.asShown`; thumbnails omitted that adapter.

**Change/check:** adapt to the saved appearance before filtering/rendering; test a new Unibrush line, edit, save, Library refresh, cold restore and a slab-only cel. Do not rebuild simulation just for a thumbnail.

## VisLab: recommended order

### V1 — P2: a slow render silently exports the wrong visualizer

[Two-second timeout returns the unfinished result](C:/+Projects/JoyCreator/app/src/main/java/com/fadcam/vislab/studio/StudioVis.java:159); [export substitutes/skips on null](C:/+Projects/JoyCreator/app/src/main/java/com/fadcam/ui/faditor/export/CompositeExportOverlay.java:995).

The queued GL task keeps running after timeout. Export sees null and draws a classic waveform or omits the look, yet can report success. Shader compilation, video decoding and queued work share this worker. A late task can also create a bitmap after its consumer has left.

**Change/check:** distinguish unavailable look from pending/failed render. Export must await with cancellation or explicitly fail/retry, and recycle abandoned late results. Delay a render beyond two seconds and verify no successful wrong frame, leaked bitmap or unbounded queue.

### V2 — major optimization: keep Studio's VisLab frames on the GPU

[Fresh bitmap each frame](C:/+Projects/JoyCreator/app/src/main/java/com/fadcam/vislab/studio/StudioVis.java:332); [readback and Java row swaps](C:/+Projects/JoyCreator/app/src/main/java/com/fadcam/vislab/studio/OffscreenGl.java:121).

The bridge renders on GL, synchronously reads all pixels to CPU, swaps rows in Java, copies into a fresh bitmap, then Studio composites/uploads it again. One 1080p look allocates 8,294,400 bitmap bytes per frame: 248.8 MB/s at 30 fps, before the other copies. Full-frame 4K multiplies that by four.

**Change:** shared-context texture handoff or render directly into Studio's GL pass with synchronization/lifetime ownership. A reusable bitmap/buffer path is a smaller interim improvement. Measure readback/copy/upload separately; no measured fps/heat gain is claimed.

**Check:** blend/alpha/orientation parity, multiple looks, preview alongside export, context loss, effects order and cancellation.

### V3 — P2: two layers of the same video at different speeds repeatedly rebuild a decoder

[Decoder keyed only by URI](C:/+Projects/JoyCreator/app/src/main/java/com/fadcam/vislab/room/Videos.java:84); [backward request destroys it](C:/+Projects/JoyCreator/app/src/main/java/com/fadcam/vislab/room/Videos.java:98); [each layer has its own speed](C:/+Projects/JoyCreator/app/src/main/java/com/fadcam/vislab/gl/VisRenderer.java:988).

For two layers at 1x and 2x, calls alternate t and 2t. Next frame's t+dt goes backward relative to 2t, rebuilding the decoder. The faster layer then decodes forward across the gap again. Cost grows with playback position and affects the room, preview and export.

**Change/check:** decoder identity must include independent playback instance; share only matching-time results. Test the same source in two layers at different speeds/offsets and loops. Decoder construction should not scale with rendered frames.

### V4 — P2: sprite animation disagrees between scrubbing and sequential export

[Timeline delta clamped to 0.1 seconds](C:/+Projects/JoyCreator/app/src/main/java/com/fadcam/vislab/studio/StudioVis.java:309); [sprite accumulates that delta](C:/+Projects/JoyCreator/app/src/main/java/com/fadcam/vislab/gl/SpriteClock.java:51).

Studio does not reset/replay the sprite's state on seeks. Jumping from 0 to 5 seconds advances it by at most 0.1 seconds; backward seeks leave it advanced. Recreating the renderer after idle starts over. A probe compiled from the checked production `SpriteClock.java` gives frame 4 for sequential playback at five seconds and frame 1 after Studio's seek-delta sequence (eight frames, 12 fps).

**Change/check:** own-speed animation should use absolute scene time; beat/kick modes need a deterministic event index or bounded reset/replay. Test random seeks, reverse scrubbing, idle recreation and preview/export frame parity.

### V5 — P2: nine music-linked effects thrash the eight-clock cache

[Owner-keyed LRU capped at eight](C:/+Projects/JoyCreator/app/src/main/java/com/fadcam/vislab/studio/StudioEars.java:72); [new clocks replay 2.2 seconds](C:/+Projects/JoyCreator/app/src/main/java/com/fadcam/vislab/studio/SoundClock.java:52); [per-layer export owner](C:/+Projects/JoyCreator/app/src/main/java/com/fadcam/ui/faditor/export/ExportManager.java:7505).

Nine active owners accessing in frame order produce zero hits and nine misses every frame, confirmed by a matching LRU probe. Each new clock at later song times replays about 133 analysis steps at FFT size 2048. That can exceed 1000 FFT analyses per output frame, under the global synchronized method.

**Change/check:** share analysis per source/time stream within a preview/export session, or retain required clocks for their lifecycle. Do not simply remove the cap without resource ownership. Test 1, 8 and 9 owners and simultaneous preview/export.

## New Studio work: recommended order

## Studio findings S1–S5: checked against the source, 2026-11-11

Read against `joy-creator` at a8071d3e (Claude Opus 5.5, branch `claude/studio-s1-s5-20261111`). No Android build or phone check was possible from this session: the fixes are tested off the phone only.

| # | Finding | Verdict | What was done | Proof |
|---|---|---|---|---|
| S1 | Failed import check deletes a saved board picture | Confirmed | Fixed: the picture is the project's as soon as `storage.save` succeeds (`JoyBrushBoardImportActivity.receive`) | `run-boardimport-lint.sh` passes; fails on a8071d3e |
| S2 | Folder copy loses marks, regions, loop | Confirmed, already fixed 11-10 by SAFE-1 | Nothing more: `ProjectSidecars` rides in `joyFolder.sidecars`, `restoreMissing` writes it back before `project.json` | `run-sidecars.sh` 16 pass (SAFE-1; not re-run here, no gson jar) |
| S3 | Undo in a loop-extended clip picks the next clip | Confirmed | Fixed: `refreshPreviewSurfaceAfterUndoRedo` uses on-timeline spans (`AnchorMath.clipAtTime`) and `Clip.mapToSourceMs` for the repeat | `run-anchormath.sh` 52 pass; the old sum picks the wrong clip (control) |
| S4 | Audio region export renders video | Confirmed | Deferred: the audio-only path still plays loop extensions as silence and has no transition crossfade, so switching would change the sound; needs a sample-by-sample comparison on the phone | none |
| S5 | Region batch copies the project N times | Confirmed | Deferred: costs storage and memory per region, loses nothing; the shared snapshot must survive cancel and service restarts, which needs a build and phone run | none |

### S1 — P1: failed import verification deletes an already-committed PNG

[Successful save followed by fallible reload](C:/+Projects/JoyCreator/app/src/main/java/com/fadcam/ui/faditor/sprite/JoyBrushBoardImportActivity.java:160); [cleanup deletes the image](C:/+Projects/JoyCreator/app/src/main/java/com/fadcam/ui/faditor/sprite/JoyBrushBoardImportActivity.java:177).

Send to Studio into an existing project saves the new sheet URI, then verifies by reloading. If reload fails or cannot locate the sheet, `unsavedImage` is still set and cleanup deletes it. The saved project now references a missing PNG.

**Change/check:** transfer ownership immediately at durable commit, or roll back model and asset together. Inject failure after save but before verification, then reopen the existing project and verify all preexisting assets plus the new sheet are present or consistently rolled back.

### S2 — P1: named marks/regions are missing from portable project backups

[Metadata lives only in a sidecar](C:/+Projects/JoyCreator/app/src/main/java/com/fadcam/ui/faditor/markers/MarkerWorkspaceStore.java:26); [folder mirror reads only project.json](C:/+Projects/JoyCreator/app/src/main/java/com/fadcam/joyfolder/studio/StudioMirror.java:230); [restore writes only project.json](C:/+Projects/JoyCreator/app/src/main/java/com/fadcam/joyfolder/studio/StudioMirror.java:678).

Restoring a `.joyproject` onto another phone recovers the composition but loses named bookmarks, regions, export selections and loop settings from `timeline-marks.json`. The new feature is locally persistent but absent from the portable representation.

**Change/check:** version/embed the workspace in the mirror and restore it transactionally, preserving unknown/newer fields. Round-trip a project with marks/regions/loop through the real mirror into a clean temporary project directory; compare semantic state. No uninstall is needed or authorized for this check.

### S3 — P2: undo refresh can choose the next clip inside a loop-extended clip

[Helper sums trimmed durations](C:/+Projects/JoyCreator/app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java:15230); [timeline uses visual durations](C:/+Projects/JoyCreator/app/src/main/java/com/fadcam/ui/faditor/timeline/EditorTimelineView.java:1734).

For an image with a 2-second base extended to 10 seconds, followed by video, undo at 8 seconds makes the helper choose the video and switch preview surfaces even though the playhead remains inside the image. It also clamps local time to the base duration.

**Change/check:** use the same clip lookup and timeline-to-source mapping as seeking, including speed/loops/reverse. Test image-to-video, video-to-image, slides, loop extension and undo/redo without moving the playhead. A shared authority prevents repeating this class of bug.

### S4 — major optimization: audio region export should avoid video rendering

[Audio ranges with video clips use the chunked video pipeline](C:/+Projects/JoyCreator/app/src/main/java/com/fadcam/ui/faditor/export/ExportManager.java:1865).

The path renders/composes video chunks, joins/trims them, then extracts audio. Requesting an M4A can therefore pay video decode, effects, video encoding and temporary MP4 storage costs. There is already an audio-first mixing/range path nearby.

**Change/check:** extend the composed audio path to mixed video/audio timelines, preserving clip speed, trim, transitions, overlays, envelopes and audio effects. Compare decoded audio samples/timing against the current path before replacing it.

### S5 — optimization: share one immutable project snapshot across a region batch

[Full snapshot file written once per region](C:/+Projects/JoyCreator/app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java:14327); [service deserializes each before queuing](C:/+Projects/JoyCreator/app/src/main/java/com/fadcam/ui/faditor/export/ExportService.java:349).

Every region gets the same large JSON copied to a distinct file; the service loads and retains a full project model for each queued output. With N regions, metadata storage and queued model memory grow roughly N times even though the composition is identical.

**Change/check:** one immutable batch snapshot with small range/name descriptors and reference-counted lifetime. Test many regions, edits after enqueue, cancellation, service recreation and partial failures. Keep the edit-immune guarantee.

## Verification and limits

- Repository source was read-only. No Gradle/Android build, broad test suite, phone action, installation or device profiling was performed.
- `EngineProbe.java` ran against the existing core JAR (SHA256 `A292A8E7367A1CE5BFF9019E24BC69CFBD1DC124916FB4234A17BB03C5C05D92`, built October 31 at 08:50 local host time). The probed spline/table/guard source paths predate that artifact. This is not a claim that the JAR represents every later HEAD change.
- `VisClockProbe.java` compiled the checked production `SpriteClock.java` with plain javac outside the repository; its LRU portion is a faithful small model of the cache policy.
- Lifecycle ordering, memory allocations and error paths were established from source, not reproduced in the Android app. Allocation byte counts exclude object/driver overhead. Proposed performance gains need Note 9 measurements and picture parity checks.
- The first-wave six issues remain Klaw's separate work. Reconcile their fixes before editing overlapping paths. No new BOARD/build/phone ownership was taken.

Probe results:

```text
stationary oil: direct steps=60, production spline steps=0
2 atlas pages at (0,0),(80,80): resident tiles spread over too large a table
existing dry atlas -> water at available=600MiB threshold=226MiB: guard permits=true
legacy extra water reservation MiB=108
same 5-second scene, sprite frame: sequential=4, Studio seek-delta=1
9-owner / 8-clock LRU frame 0: hits=0, misses=9
9-owner / 8-clock LRU frame 1: hits=0, misses=9
9-owner / 8-clock LRU frame 2: hits=0, misses=9
```

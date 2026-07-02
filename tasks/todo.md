## In Progress: Loop/Ping-Pong Extension Export Fix

### User report (2026-06-25)
- "It did not render loop or ping pong extension styles"

### Root causes (already analyzed by orchestrator — fix, do not re-analyze)
1. **Wrong source sub-range.** Each rep used `[inPoint, outPoint]` (full clip range) regardless of how much timeline the rep was supposed to fill. When the rep's played duration was smaller than the source range, the timeline cursor advanced by the rep duration but Media3 played the entire 5s source — a timing mismatch and the wrong content.
2. **STILL mode was a no-op.** No `STILL` branch existed; STILL re-used the same full-range clip and just replayed it, so the user saw the same motion instead of a frozen frame.
3. **No comment explaining the ping-pong mirror trade-off.** The `setScale(-1f, 1f)` hack silently mirrored text overlays too.

### Plan
- [x] Re-read `ExportManager.buildLoopExtensionItem` and `buildComposition`'s loop block.
- [x] Read `Clip` (loop-related methods) and `Transition` for context.
- [x] Compute `playedMs = min(trimmedPlayMs, extensionMs - repIndex*trimmedPlayMs)`.
- [x] Derive source sub-range from `playedMs` and `reverse`:
   - Forward leg: `[inPoint, min(outPoint, inPoint + playedMs*speed)]`.
   - Reverse leg (PING_PONG): `[max(inPoint, outPoint - playedMs*speed), outPoint]`.
- [x] Branch on `LOOP_MODE_STILL`: extract first/last frame as JPEG via `MediaMetadataRetriever`, build a single image `MediaItem` with `setImageDurationMs(extensionMs)`. Only rep 0 produces an item.
- [x] Add `buildStillLoopExtensionItem` and `extractStillFrameForLoop` helpers. Cache the JPEG in `cacheDir/faditor_export/loop_still_<id>_<first|last>.jpg`.
- [x] Add a TODO above the mirror hack flagging the text-overlay side effect.
- [x] Import `android.graphics.Bitmap`.
- [x] Run `.\gradlew.bat compileDefaultDebugJavaWithJavac --no-daemon`. **BUILD SUCCESSFUL** (1 task executed, 56 up-to-date).
- [x] Verified all three methods (`buildLoopExtensionItem`, `buildStillLoopExtensionItem`, `extractStillFrameForLoop`) are present in the compiled `ExportManager.class` via `javap -p`.

### Files modified
- `FadCam/app/src/main/java/com/fadcam/ui/faditor/export/ExportManager.java`
   - Added `import android.graphics.Bitmap;`
   - Rewrote `buildLoopExtensionItem` so each rep's source range is derived from `playedMs` and `reverse`. PING_PONG reverse legs play the tail of the clip and add `setScale(-1f, 1f)`. STILL mode delegates to `buildStillLoopExtensionItem` on rep 0 and returns null for later reps.
   - Added `buildStillLoopExtensionItem(Clip, extensionMs, isBefore, timelineCursorMs, ...)`: builds an image `MediaItem` with `setImageDurationMs(extensionMs)`, wires overlay + opacity + canvas Presentation, and removes audio.
   - Added `extractStillFrameForLoop(Clip, isBefore)`: uses `MediaMetadataRetriever.getFrameAtTime(OPTION_CLOSEST_SYNC)` to grab the first (or last) frame and writes a 90% JPEG into `cacheDir/faditor_export/`. Caches by file existence to avoid re-decoding across exports.
   - Updated the `setScale(-1f, 1f)` block with a TODO about the text-overlay mirror side effect.

### Math sanity check
- `inPoint=1000ms, outPoint=4000ms, speed=1.0, trimmedPlayMs=3000ms, loopAfterMs=5000ms`:
   - **NORMAL** rep 0: playedMs=3000, source=[1000,4000], dur=3000. rep 1: playedMs=2000, source=[1000,3000], dur=2000. Sum=5000. ✓
   - **PING_PONG** rep 0: playedMs=3000, forward, source=[1000,4000], dur=3000. rep 1: playedMs=2000, reverse, source=[2000,4000] + scaleX=-1, dur=2000. Sum=5000. ✓
   - **STILL**: one item, `setImageDurationMs(5000)`, total=5000. ✓

### Open issues / hand-off
- Overlay timeline math inside `CompositeExportOverlay` is unchanged. The `clipEnd` is still derived from `clip.getTrimmedDurationMs()` rather than the actual loop rep duration, so text overlays may be clipped early during a long extension. Out of scope for this fix; flagged for the overlay pass.
- The `setScale(-1f, 1f)` mirror is still a visual stand-in; a true reverse would require pre-rendering the source. The TODO comment captures the trade-off.

---

## In Progress: Export Pipeline Hardening (2026-06-25 autonomous session)

### Original user report
- No transitions in exported video
- No fading / opacity black layer
- No audio visualizers
- Loop/ping-pong extensions not rendered
- No captions
- No extension styles

### Root causes (from code review + subagent analysis)
1. **GL transition `ratio` uniform stripped on Adreno 650** — `uniform float ratio` declared in template but not referenced in `main()`. Driver strips it, `glGetUniformLocation` returns -1, `setFloatUniform` NPE (caught), UV math like `uv.x * ratio` collapses to 0 → black/garbage transitions.
2. **GridFlip had undeclared `uniform ivec2 size`** — never injected by `uniformsFor()`, shader fails to compile.
3. **`bgcolor` setFloatsUniform was NPE-unsafe** — bypassed the `setFloatUniform` NPE-catching wrapper.
4. **BitmapOverlay texture cache frozen at frame 1** — `CompositeExportOverlay.getBitmap()` mutated the same scratch `Bitmap` in place; `getGenerationId()` only bumps on reallocation; cache hit, texture never re-uploaded. Result: text/captions/waveforms appear for frame 1 then disappear (or never appear at all if frame 1 was transparent).
5. **Opacity effect placed BEFORE OverlayEffect** — order was `opacity → overlay → presentation`, so opacity dimmed the video but the overlay re-composited at full opacity on top. The user saw a dimmed video with bright overlays, not a fade-to-black.
6. **Opacity shader kept `c.a = 1.0`** — `vec4(c.rgb * uOpacity, c.a)` is a color multiply, not a real alpha blend. The output is always opaque (alpha 1.0 for video), so even with reordering the fade can't make the frame transparent.
7. **`presentationTimeUs` in per-frame shaders is item-local, not timeline-absolute** — opacity keyframes evaluated at the wrong time for every non-first clip in a multi-clip project.
8. **Loop extension used full source range per rep** — each rep played the full trimmed range, but the rep's timeline duration was smaller, causing timing mismatches and wrong content.
9. **STILL mode was a no-op** — replayed the full source range instead of freezing a single frame.
10. **GlTransitionFrameOverlay used `OPTION_CLOSEST_SYNC`** — returns keyframe-stepped frames, not the requested time. Retriever is also not thread-safe.

### Subagent results

| Agent | Scope | Status | Headline change |
|-------|-------|--------|-----------------|
| 1 | GL transitions (ratio / GridFlip / frame overlay / sanitize) | PASS | Confirmed `const float ratio = <value>;` export template; stripped `ivec2 size` from GridFlip + replaced with `ivec2(4)`; `bgcolor` NPE-guarded; `OPTION_CLOSEST` + 33ms cache; `sanitize()` drops `uniform float progress`/`ratio` always |
| 2 | Composite overlay (text/captions/waveforms) | PASS | `getBitmap()` now returns `Bitmap.createBitmap(scratch)` so the `BitmapOverlay` generationId cache fires per frame; confirmed + documented caption source-time coord system; release-time summary log added |
| 3 | Opacity + architecture audit | PASS | Shader now `vec4(c.rgb * uOpacity, c.a * uOpacity)`; `clipTimelineOffsetMs` plumbed through `OpacityExportEffect` → `OpacityExportShaderProgram` (clipMs = pts/1000 + offset); effect ordering fixed (overlay → opacity → presentation) at all 3 call sites in `ExportManager` |
| 4 | Loop/ping-pong/STILL | PASS | `buildLoopExtensionItem` rewritten with per-rep source sub-range; new `buildStillLoopExtensionItem` + `extractStillFrameForLoop` for STILL mode (one image item, duration = extension); TODO on mirror hack |

### Final build verification
- `.\gradlew.bat compileDefaultDebugJavaWithJavac --no-daemon` → **BUILD SUCCESSFUL in 12s**, 57/57 tasks up-to-date. All 4 subagent fixes integrate cleanly with no conflicts.

### Remaining / follow-up issues
1. **Other `assets/gl_transitions/*.glsl` may have undeclared uniforms** beyond GridFlip. `colorphase.glsl` declares `vec4 fromStep`/`vec4 toStep` which the catalog doesn't inject. **Sweep pass needed.**
2. **Single-clip + `original` canvas = NO overlay at all** (`ExportManager.buildClipItem` line ~557 only adds overlay if `outW > 0 && outH > 0`). The `isSimpleTrim` path also bypasses the overlay via `experimentalSetTrimOptimizationEnabled(true)`. Likely root cause of the user's most common "no overlays" report for simple projects. **NEEDS FOLLOW-UP** — fall back to the full re-encode path when overlays are present.
3. **PING_PONG reverse is still a horizontal mirror** (TODO in code). Media3 doesn't support true reverse. Proper fix would pre-render the reversed source as a separate file. Out of scope for this pass.
4. **WaveformStyleRenderer allocates a `BlurMaskFilter` per frame** when glow is enabled — perf smell, not correctness.
5. **Per-frame `Bitmap.createBitmap(scratch)` is ~8 MB/frame memcpy** for 1080p. Bounded, acceptable for now; future optimization is a custom `TextureOverlay` with a GL texture handle.
6. **Keyframe storage convention (item-local vs timeline-absolute) is undocumented** — `clipTimelineOffsetMs` fix assumes timeline-absolute. User should validate in-app that the opacity envelope rides with the clip position. If keyframes are actually item-local, the `+ clipTimelineOffsetMs` should be removed and the `Clip.opacityAtClipMs` docstring should say "item-local".
7. **Overlay's `clipEnd` math is loop-unaware** — text overlays scheduled past `trimmedPlayMs` from the loop start get clipped to `trimmedPlayMs` even when the loop rep runs longer.

### Architecture correlation (from subagent 3)
- **Common failure pattern:** each export feature was added independently; `videoEffects` ordering is duplicated across 3+ call sites (`buildClipItem`, `buildLoopExtensionItem`, `buildStillLoopExtensionItem`, `buildTransitionItem`) with no shared helper. The "what order do these go in?" invariant is enforced only by tribal knowledge.
- **Missing abstraction (minimum viable refactor):** extract one helper — `List<Effect> assembleClipVideoEffects(Clip, project, timelineCursorMs, canvasDims, waveformSlots)` — that returns the effects in the canonical order. ~30 lines, prevents the entire class of "I added a new effect in the wrong slot" bug from recurring. Recommended as the next code-quality pass.
- **Missing abstraction:** shared `clipMsFor(ptsUs)` helper so every per-frame effect uses the same timeline-time math (avoids the `clipTimelineOffsetMs` re-plumbing we just did three times).
- **Missing abstraction:** `MediaMetadataRetriever` pool/factory with a "one per worker, never share" contract.

### Lessons captured
See `tasks/lessons.md` for the 5 new patterns added this session:
- BitmapOverlay texture cache requires a fresh Bitmap per frame
- GLSL uniform stripping on Adreno — declare in `main()` OR make it `const`
- Effect ordering in Media3 export pipelines must be a single shared helper
- Bitmap mutation in place defeats Android's generationId-based caches
- Per-frame presentation time is item-local, not timeline-absolute

---

## In Progress: Export Pipeline Timestamp Architecture Fix (2026-06-25)

### Correction to previous analysis
Subagent investigation of `media3-patched` confirms `presentationTimeUs` passed to `GlShaderProgram.drawFrame()` and `BitmapOverlay.getBitmap()` is **timeline-absolute across the Composition**, not item-local. The previous todo entry "Per-frame presentation time is item-local" was wrong.

This invalidates several prior "fixes":
- `CompositeExportOverlay` adding `clipTimelineStartMs` to `presentationTimeUs/1000` double-counts the offset.
- `GlTransitionShaderProgram` treating raw `presentationTimeUs` as item-local makes later transitions evaluate progress far outside `0..1`.

`OpacityExportShaderProgram` (subtracts `clipTimelineOffsetMs`) was already correct for absolute timestamps.

### Root causes of current user report
1. **Timeline cursor never advances for video clips.** `ExportManager` used `item.durationUs / 1000`, but `durationUs` is `C.TIME_UNSET` for video items. So `clipTimelineStartMs` stayed ~0 for every clip.
2. **Overlay timing double-counts offset.** `CompositeExportOverlay` adds `clipTimelineStartMs` to an already-absolute timestamp, so later clips evaluate text/caption/waveform lookups far outside the clip window.
3. **Waveform source mapping never configured for export.** `WaveformOverlayInstance.setSourceMapping()` / `setLoopExtension()` are not called, so visualizers read from source time 0 at speed 1.0 regardless of trim/speed/loop.
4. **Waveform loop wrapping math is wrong.** It applies speed before the modulo, wrapping the source offset instead of the output cycle.
5. **Caption source time uses absolute `clipMs`.** Should use clip-local time (`timelineMs - clipTimelineStartMs`) times speed.
6. **Transition progress uses absolute `presentationTimeUs`.** Needs item-start offset subtracted.
7. **Loop before-extensions are appended after the main clip.** They should appear before it on the exported timeline.
8. **`isSimpleTrim` does not guard loop extensions.** Single-clip projects with loops can take the fast-trim path and skip all effects.
9. **Waveform fallback leaves a dangling `audioSourceRef`.** After falling back to the first non-image clip, the slot is filtered out because the ref no longer matches any clip ID.
10. **Audio clips are not searched as waveform sources.** `resolveWaveformUri()` only looks at video clips.

### Plan
- [x] Fix `ExportManager.buildComposition` to advance `timelineCursorMs` using explicit timeline durations (`setDurationUs` on each item), not `item.durationUs`.
- [x] Fix `CompositeExportOverlay` to treat `presentationTimeUs` as absolute timeline time.
- [x] Fix caption source-time mapping to use clip-local ms.
- [x] Fix `ExportManager.buildWaveformSlots` to call `setSourceMapping`/`setLoopExtension` per slot and to search audio clips.
- [x] Fix `WaveformOverlayInstance.mapToSourceMs` loop wrapping math.
- [x] Fix `ExportManager` waveform fallback to update `audioSourceRef` or skip cleanly.
- [x] Fix `GlTransitionShaderProgram` / `GlTransitionExportEffect` progress to subtract item timeline offset.
- [x] Fix `GlTransitionFrameOverlay` progress to subtract item timeline offset.
- [x] Keep const `ratio` fix; ensure ratio is inferred from source dims when canvas preset is "original".
- [x] Reorder loop extensions: build before-extensions before the main clip.
- [x] Add `!clip.hasLoopExtension()` to `isSimpleTrim` guard.
- [x] Run `compileDefaultDebugJavaWithJavac` and verify.

### Files modified
- `FadCam/app/src/main/java/com/fadcam/ui/faditor/export/ExportManager.java`
   - `isSimpleTrim` now excludes clips with loop extensions.
   - Added `inferSourceDims()` helper; `resolveCanvasDims()` reuses it.
   - `buildComposition` infers non-zero dims for "original" canvas so overlays/transitions have a size.
   - `buildComposition` advances `timelineCursorMs` via `item.durationUs / 1000` after every item now sets `DurationUs`.
   - Loop before-extensions are built BEFORE the main clip; after-extensions remain after.
   - `buildClipItem` sets `setDurationUs()` from source/speed and fixes image-clip duration to the trimmed item range.
   - `buildTransitionItem` sets `setDurationUs()`, computes actual timeline duration, and passes it + `timelineCursorMs` to `GlTransitionExportEffect`.
   - `buildLoopExtensionItem` / `buildStillLoopExtensionItem` set `setDurationUs()`.
   - Added `findClipById()` / `findAudioClipById()` helpers.
   - `resolveWaveformUri()` now searches audio clips.
   - `buildWaveformSlots()` configures `setSourceMapping()`/`setLoopExtension()` and updates fallback `audioSourceRef`.
- `FadCam/app/src/main/java/com/fadcam/ui/faditor/export/CompositeExportOverlay.java`
   - `getBitmap()` treats `presentationTimeUs` as absolute timeline ms.
   - Uses `clipLocalMs` for caption source mapping.
   - Text-overlay filter uses `getVisualDurationMs()` as a safe upper bound.
- `FadCam/app/src/main/java/com/fadcam/ui/faditor/model/WaveformOverlayInstance.java`
   - `mapToSourceMs()` wraps in output time then applies speed.
- `FadCam/app/src/main/java/com/fadcam/ui/faditor/gltransitions/GlTransitionShaderProgram.java`
   - Constructor accepts `timelineStartMs`; `drawFrame()` subtracts it for local progress.
- `FadCam/app/src/main/java/com/fadcam/ui/faditor/gltransitions/GlTransitionExportEffect.java`
   - Constructor accepts and stores `timelineStartMs`; passes it to `GlTransitionShaderProgram` and `GlTransitionFrameOverlay`.
- `FadCam/app/src/main/java/com/fadcam/ui/faditor/gltransitions/GlTransitionFrameOverlay.java`
   - Constructor accepts `timelineStartMs`; `getBitmap()` subtracts it for local progress.
- `FadCam/tasks/lessons.md`
   - Corrected the "Per-frame presentation time" lesson from item-local to timeline-absolute.

### Verification
- `cd FadCam && .\gradlew.bat compileDefaultDebugJavaWithJavac --no-daemon` → **BUILD SUCCESSFUL in 13s**, 57/57 tasks up-to-date.
- `cd FadCam && .\gradlew.bat assembleDefaultDebug --no-daemon` → **BUILD SUCCESSFUL in 18s**.
- Reinstalled with `adb install -r app\build\outputs\apk\default\debug\app-default-arm64-v8a-debug.apk` → **Success**.
- Verified package present: `adb shell pm path com.fadcam.beta --user 0` → `package:/data/app/.../com.fadcam.beta-.../base.apk`.
- Data preserved: `-r` reinstall only replaces the APK; app storage/projects are untouched.

### Known remaining limitations
1. **PING_PONG reverse is still a horizontal mirror**, not true reverse playback. Media3 Transformer has no native reverse. A proper fix requires pre-rendering reversed segments (Option A) or an FFmpeg fallback (Option B).
2. **Caption/word highlighting does not reverse during ping-pong reverse cycles** because audio is not reversed and the visualizer/caption mapping is forward-only. Consistent with the current mirror approximation.
3. **Loop-extension opacity keyframes** use the extension item's own start offset, not the clip's base start, so an opacity envelope defined across the whole clip will not align perfectly across before/after extensions. Acceptable for now; base clip opacity works.

---

## In Progress: Forced Caption Line Breaks (2026-06-25)

### User request
- "It would be nice if there was something that I could insert that would force a break in" caption rendering, instead of adding spacer words.

### Design
- Add a `forceLineBreakAfter` flag to each `TranscriptWord`.
- Double-tap a word in the transcript panel to toggle the flag.
- Caption renderers (`CaptionOverlayView` preview + `CaptionExportRenderer` export) break phrases after flagged words, so the following word starts a new line.
- Persist the flag in project JSON (`b` key) alongside existing `x` (struck) key.

>### Plan
- [x] Add `forceLineBreakAfter` field + constructors to `TranscriptWord`.
- [x] Preserve flag in `Transcript.copy()` and add `Transcript.setForceLineBreakAfter()`.
- [x] Honor flag in `CaptionOverlayView.buildPhrases()`.
- [x] Honor flag in `CaptionExportRenderer.buildPhrases()`.
- [x] Add double-tap gesture + cyan break indicator to `TranscriptPanelView`.
- [x] Add a compact header button (Material `wrap_text` icon) to toggle the break on the currently highlighted word.
- [x] Add `onActiveWordChanged()` and `onLineBreaksChanged()` listener callbacks; wire button state + auto-save in `FaditorEditorActivity`.
- [x] Serialize/deserialize `b` in `ProjectStorage` for both video and audio clip transcripts.
- [x] Run compile/assemble and reinstall.

### Files modified
- `FadCam/app/src/main/java/com/fadcam/ui/faditor/transcript/TranscriptWord.java`
- `FadCam/app/src/main/java/com/fadcam/ui/faditor/transcript/Transcript.java`
- `FadCam/app/src/main/java/com/fadcam/ui/faditor/transcript/CaptionOverlayView.java`
- `FadCam/app/src/main/java/com/fadcam/ui/faditor/export/CaptionExportRenderer.java`
- `FadCam/app/src/main/java/com/fadcam/ui/faditor/transcript/TranscriptPanelView.java`
- `FadCam/app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java`
- `FadCam/app/src/main/java/com/fadcam/ui/faditor/project/ProjectStorage.java`
- `FadCam/app/src/main/res/layout/activity_faditor_editor.xml`
- `FadCam/app/src/main/res/values/strings.xml`

### Verification
- `cd FadCam && .\gradlew.bat compileDefaultDebugJavaWithJavac --no-daemon` → **BUILD SUCCESSFUL in 46s**, 57/57 tasks up-to-date.
- `cd FadCam && .\gradlew.bat assembleDefaultDebug --no-daemon` → **BUILD SUCCESSFUL in 35s**.
- Reinstalled with `adb install -r` (via `C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe`) → **Success**.

### Status: COMPLETE

---

## In Progress: Bug Squash & Architecture Cleanup (2026-06-25)

### Goal
- Fix every remaining correctness bug we already know about.
- Refactor duplicated / fragile export pipeline code into shared helpers.
- Improve runtime efficiency (allocation hot spots, retriever reuse, etc.).

### Known remaining issues (from prior passes)
1. **GL transition uniform/schema mismatch** — `assets/gl_transitions/colorphase.glsl` declares `vec4 fromStep`/`vec4 toStep` that the catalog doesn't inject. Likely fails to compile or renders black.
2. **Single-clip + "original" canvas = no overlays** — `ExportManager.isSimpleTrim` can take the fast trim path when overlays/loops are present, skipping all effects.
3. **Overlay `clipEnd` is loop-unaware** — text overlays scheduled past `trimmedPlayMs` inside a loop extension get clipped early.
4. **Duplicated effect ordering** — `ExportManager` builds `videoEffects` lists in 4+ call sites with no shared helper; easy to put effects in the wrong slot.
5. **No shared per-frame timestamp helper** — every per-frame effect re-derives `clipMs` differently.
6. **`MediaMetadataRetriever` not pooled** — created per frame / per loop still-frame extraction; no "one per worker" contract.
7. **`WaveformStyleRenderer` allocates `BlurMaskFilter` per frame** when glow is enabled.
8. **PING_PONG reverse is a horizontal mirror** — Media3 limitation; true reverse needs pre-rendered segment or FFmpeg fallback. Out of scope for a quick fix but should be documented.

### Plan
- [x] Audit all `assets/gl_transitions/*.glsl` for undeclared uniforms / mismatched injection and fix or remove broken transitions.
- [x] Harden `ExportManager.isSimpleTrim` to never bypass effects when overlays, captions, waveforms, opacity keyframes, or loop extensions are present.
- [x] Extract shared `assembleClipVideoEffects(...)` helper in `ExportManager` with canonical effect order.
- [x] Extract shared `clipMsFor(...)` helper for timeline-time conversion used by all per-frame effects.
- [x] Fix loop-aware overlay `clipEnd` math in `CompositeExportOverlay`.
- [x] Pool / reuse `MediaMetadataRetriever` instances and add thread-safety contract.
- [x] Remove per-frame `BlurMaskFilter` allocation in `WaveformStyleRenderer`.
- [x] Build and smoke-test (reinstall blocked by no connected device).

### Files touched
- `FadCam/app/src/main/assets/gl_transitions/powerKaleido.glsl` — moved uniform-dependent global initializer into `mainImage()`.
- `FadCam/app/src/main/java/com/fadcam/ui/faditor/export/ExportManager.java` — added `assembleClipVideoEffects`, `clipMsFor`, thread-local retriever pool, and hardened simple-trim guard already covers overlays/captions/waveforms/opacity/loops.
- `FadCam/app/src/main/java/com/fadcam/ui/faditor/export/CompositeExportOverlay.java` — loop-aware `clipVisualEndMs`, uses `ExportManager.clipMsFor`.
- `FadCam/app/src/main/java/com/fadcam/ui/faditor/export/OpacityExportShaderProgram.java` — uses `ExportManager.clipMsFor`.
- `FadCam/app/src/main/java/com/fadcam/ui/faditor/waveform/WaveformStyleRenderer.java` — cached `BlurMaskFilter`.
- `FadCam/app/src/main/java/com/fadcam/ui/faditor/gltransitions/GlTransitionFrameOverlay.java` — synchronized per-instance retriever access.

### Verification
- `cd FadCam && .\gradlew.bat clean compileDefaultDebugJavaWithJavac --no-daemon` → **BUILD SUCCESSFUL in 1m 5s**.
- `cd FadCam && .\gradlew.bat assembleDefaultDebug --no-daemon` → **BUILD SUCCESSFUL in 1m 38s**.
- Reinstall attempted but device was not connected (`adb.exe: no devices/emulators found`). APK is ready at `app\build\outputs\apk\default\debug\app-default-arm64-v8a-debug.apk`.

---

## Completed: Performance / correctness hot spots (2026-06-25)

### Scope
Address the three known issues flagged in the export hardening plan:
1. Per-frame `BlurMaskFilter` allocation in `WaveformStyleRenderer`.
2. `MediaMetadataRetriever` pooling / thread-safety in export/transition/loop code.
3. Loop-aware overlay `clipEnd` in `CompositeExportOverlay`.

### Changes made

#### `FadCam/app/src/main/java/com/fadcam/ui/faditor/waveform/WaveformStyleRenderer.java`
- Cached `BlurMaskFilter` in `glowFilter`, recreating it only when `glowRadiusDp` or `density` changes.
- Added `ensureGlowFilter(float, float)` helper; `drawFrame()` now calls it instead of `new BlurMaskFilter(...)` every frame.
- Added `androidx.annotation.Nullable` import.
- **Preview paths unchanged** — the same filter object is used for both preview and export; only allocation frequency changes.

#### `FadCam/app/src/main/java/com/fadcam/ui/faditor/export/ExportManager.java`
- Added thread-local `MediaMetadataRetriever` pool (`retrieverPool`, `retrieverCurrentUri`) with documented "one retriever per thread, never share" contract.
- Added `acquireRetriever()`, `setRetrieverDataSource(Uri)`, and `releasePerThreadRetriever()` helpers.
- Refactored `getSourceWidth()`, `getSourceHeight()`, and `extractStillFrameForLoop()` to reuse the thread-local retriever instead of creating a new instance per call.
- Wrapped `buildComposition(project)` in `try/finally` so the retriever is released after composition building even if an exception is thrown.

#### `FadCam/app/src/main/java/com/fadcam/ui/faditor/gltransitions/GlTransitionFrameOverlay.java`
- Added `retrieverLock` and synchronized all access to the per-instance `MediaMetadataRetriever` in `decodeFrame()` and `release()`.
- Documented that the retriever is per-instance and must not be shared across threads.

#### `FadCam/app/src/main/java/com/fadcam/ui/faditor/export/CompositeExportOverlay.java`
- Added explicit `clipVisualEndMs` field computed as `clipTimelineStartMs + clip.getVisualDurationMs()` (trimmed range + loop/ping-pong extensions).
- `filterTextOverlays()` now uses `clipVisualEndMs` so text overlays scheduled inside loop extension regions are not prematurely filtered out.
- Added class-level/field documentation explaining the loop-aware upper-bound behavior.
- Waveform slots already receive `setLoopExtension(srcClip.getTrimmedDurationMs())` in `ExportManager.buildWaveformSlots()`, so waveform source-time wrapping continues to work through extensions.
- Caption source mapping already loops through the trim window with each rep because `clipLocalMs` resets per `EditedMediaItem`.

### Verification
- `cd FadCam && .\gradlew.bat compileDefaultDebugJavaWithJavac --no-daemon`
- **BUILD SUCCESSFUL in 58s**, 57 actionable tasks: 1 executed, 56 up-to-date.

### Files changed
- `FadCam/app/src/main/java/com/fadcam/ui/faditor/waveform/WaveformStyleRenderer.java`
- `FadCam/app/src/main/java/com/fadcam/ui/faditor/export/ExportManager.java`
- `FadCam/app/src/main/java/com/fadcam/ui/faditor/gltransitions/GlTransitionFrameOverlay.java`
- `FadCam/app/src/main/java/com/fadcam/ui/faditor/export/CompositeExportOverlay.java`

---

## Completed: Struck words no longer render in captions (2026-06-25)

### Problem
Struck (removed) transcript words were still being drawn by `CaptionOverlayView` and `CaptionExportRenderer`, even though the audio timeline skips them.

### Fix
Both renderers now filter the phrase to visible (non-struck) word indices before line-wrapping and drawing. If every word in the active phrase is struck, nothing is drawn for that phrase.

### Files changed
- `FadCam/app/src/main/java/com/fadcam/ui/faditor/transcript/CaptionOverlayView.java`
- `FadCam/app/src/main/java/com/fadcam/ui/faditor/export/CaptionExportRenderer.java`

### Verification
- `cd FadCam && .\gradlew.bat compileDefaultDebugJavaWithJavac --no-daemon` → **BUILD SUCCESSFUL in 19s**.

---

## In Progress: Transcript panel refresh + failed export / timeline jump investigation (2026-06-25)

### Problems reported
1. **Transcript panel stays blank / stale** until the user interacts; audio-clip transcripts don't appear reliably.
2. **Failed exports** on projects with looped video + audio-clip transcripts.
3. **Timeline jumps around** when a looped video is involved.

### Fix for #1
Refactored `FaditorEditorActivity.openTranscriptPanel()`:
- Extracted `resolveTranscriptTarget()` and `loadTranscriptPanelContent()`.
- The panel now refreshes its content when the selected video segment changes (`selectSegment`).
- It also switches to the selected audio clip's transcript when an audio clip is selected (`onAudioClipSelected`).

### Files changed
- `FadCam/app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java`

### Verification
- `cd FadCam && .\gradlew.bat compileDefaultDebugJavaWithJavac --no-daemon` → **BUILD SUCCESSFUL in 15s**.
- `cd FadCam && .\gradlew.bat assembleDefaultDebug --no-daemon` → **BUILD SUCCESSFUL in 21s**.
- Installed on device (`REAL_SERIAL`) → **Success**.

### Next step
Reproduce failed export and timeline jump while capturing logcat.
- `cd FadCam && .\gradlew.bat assembleDefaultDebug --no-daemon` → **BUILD SUCCESSFUL in 16s**.
- Reinstall attempted but no device connected; APK ready at `app\build\outputs\apk\default\debug\app-default-arm64-v8a-debug.apk`.


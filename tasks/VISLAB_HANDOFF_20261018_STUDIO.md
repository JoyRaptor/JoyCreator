# VisLab handoff, 2026-10-18: finish the Studio side (and the rest)

For: the next agent (Codex), working alone from this file. Owner: JoyRaptor, a non-technical
artist. Report to him in plain words with a short status table, phone-tested results only.

Read first, in order: `tasks/VISLAB_SPEC.md` (the truth for VisLab), `tasks/VISLAB_TODO.md`
(the checklist; tick items there with the commit hash, only after a phone test), then this file.

## 0. Where things stand

Done and phone-tested (Note 9), all pushed to `joy-creator`:
- The VisLab room: 39 looks, the Lab (start from nothing, a parts shelf with every layer of every
  look, my parts saved as `Supplies/Looks/<Name>.vispart.json`, colours / reactivity / softness /
  place per layer), words as art (path x behaviour x letters, your own typed words), the heat
  guard, smooth music-linked speeds, the lobby hero, the web look book.
- The floating visualiser: box / full screen, every look by name, hears a recording, the phone's
  own sound (Android's screen-capture yes) or the microphone.

Open:
1. **C4: VisLab looks inside Studio** (this file, sections 2-6). State at the end of 10-18:
   - **S1 built, untested:** `WaveformOverlayInstance.visLook` (+ `getVisLook`, `setVisLook`,
     `isVisLab`) and its save / load in `ProjectStorage` (key `visLook`). No UI sets it yet.
   - **S3 core built, compiles, NOT wired, NOT run:** `vislab/studio/OffscreenGl` (own ES 3
     thread + pbuffer, FBO per size, read-back with row flip), `vislab/studio/SoundClock`
     (features from PcmSidecar at source time, 60 Hz steps, 2.2 s replay after a jump),
     `vislab/studio/StudioVis` (`renderBlocking` for export, `previewFrame` for preview,
     `releaseAll`). Read their header comments. Left for S3: the preview and export wiring
     below, then the phone test. First test of the bridge before S2's UI exists: on the Note 9,
     open a SANDBOX project with a visualiser, close Studio, add `"visLook": "halo-drop"` to that
     item in `files/faditor/projects/<id>/project.json` (`adb shell run-as com.fadcam.beta`),
     reopen: the item should draw Halo Drop. Expect first-run bugs in the GL bridge (it has never
     run): check `adb logcat -s VisStudio VisStudioGl`.
   - S2, S4, S5: not started.
2. **D2: the hour-long heat test** (owner deferred it): floating visualiser over a game while a
   screen recording runs, an hour, on the Note 9. Log `adb logcat -s VisTextureView:I` (it prints
   `fps N scale X% heat N ...` once a second) and `dumpsys thermalservice` every minute. Report:
   peak temperatures, whether the heat guard kicked in (`heat 3` -> scale 50%), whether the
   recording dropped frames. Needs the owner to start the game and the recording.

## 1. Working rules (break none)

- Phone: Note 9 (`SM-N960U`) is the sandbox: install and test freely. **Never install on the
  Note 20** (real projects). One driver at a time: `$env:PHONE_OWNER='vislab'; .\tools\phone.ps1
  claim vislab "<why>"`, then `install`, `launch`, `tap`, `swipe`; `release vislab` when done.
  A claim lapses after 30 min; another lane may then install an OLDER build: check
  `dumpsys package com.fadcam.beta | grep lastUpdateTime` before trusting what you see.
- **Older builds without VisLab's latest strip parts of looks they do not know** (10-18: a build
  without the Words layer deleted the words from ten looks). Fixed in `LookValidator` (it keeps
  what it does not know), but merge `origin/joy-creator` before installing any other branch.
- Never run gradle: a watcher builds on every save. Check `build.log` ("BUILD SUCCESSFUL" after
  your edit time) and the APK time `app/build/outputs/apk/default/debug/app-default-arm64-v8a-debug.apk`.
  Early "BUILD FAILED" lines while you are mid-edit are normal; wait for the build after your last save.
- Never `adb uninstall`, never clear app data, never `--no-verify`.
- Commit only your own paths: `git commit -- <paths>` (other lanes keep things staged, e.g.
  `tasks/LANES.md`). Do not touch `app/src/main/res/values/strings_studio_fmt.xml` or any other
  file another lane has uncommitted (check `git status` first). Push at the end of each item.
- **Line endings:** `FaditorEditorActivity.java` and `LobbyFragment.java` are CRLF. Edit scripts
  must keep CRLF (normalise to LF, edit, convert back), or use an editor that keeps it.
- Owner's design rules: drawers ~55% see-through; flat fully rounded pills; ON = solid cyan with
  the icon cut out; every button gets a hover label (`Ui.label` / tooltip); one press = one undo;
  phone-first; keep the old behaviour selectable; dp values as drawn.
- Tests: `bash tools/jvm-harness/run-vislab.sh` (looks, knobs, speeds, layer controls) and
  `bash tools/joyfolder-tests/run.sh` (file kinds) must stay green.

## 2. C4, the owner's rulings (10-18)

- **Integrate, do not override.** Studio's visualiser item (`WaveformOverlayInstance`) carries an
  optional VisLab look. Same item, same timeline row, same transform, same Rolodex.
- **VisLab looks are a section of the Rolodex** (the visualiser's Style tab). Classic waveform
  styles stay selectable; choosing one turns the VisLab look off.
- **A small editor in Studio** for values worth keyframing, using Studio's shared keyframe helper
  (the two-arrows-and-diamond button): at least **Reactivity** (scales every music link of the
  look) and **Fade** (opacity), plus a few of the look's own key knobs. Everything else: an
  "Edit in VisLab" button that opens the room on that look.
- Movement and attachment are NOT VisLab's job: the item already has transform keyframes and
  proxy links (it implements `LinkFollower`), so a visualiser held in a hand or stuck to a ball
  is done by Studio's helpers. Just keep drawing at the item's animated pose.
- Studio is meant to replace After Effects: every VisLab value exposed in Studio should be ready
  to keyframe.

## 3. Scouted places (Haiku scouts, 10-18; line numbers drift, search the names)

All under `app/src/main/java/com/fadcam/`:
- **Item:** `ui/faditor/model/WaveformOverlayInstance.java`: `styleId`, `customStyleJson` (inline
  style; wins over styleId), `audioSourceRef`, time range, `centerX/Y`, `width/heightFraction`,
  `rotationDeg`, fades, `keyframes` (`KeyframeSet`; `addPropertyKeyframeAt(property, ms, value)`,
  `keyframes.valueAt(property, localTime(t), default)`), `mapToSourceMs(timelineMs)`.
- **Save/load:** `ui/faditor/project/ProjectStorage.java`, hand-written JSON under
  `timeline.waveformOverlays`: writer near `wj.addProperty("customStyleJson", ...)`, reader near
  `wo.setCustomStyleJson(...)`. Convention: optional keys, absent = default.
- **Add:** `FaditorEditorActivity.addWaveformVisualizer()` (creates `new WaveformOverlayInstance("spectrum_mirror")`).
- **Style tab:** `FaditorEditorActivity.buildVisualizerStyleTab(overlay)` returns
  `buildVisualizerRolodex(overlay, styles)`; the style carousel writes `setStyleId` and clears
  `customStyleJson`; the layers section `buildVisualizerLayersSection`. Transform tab:
  `buildVisualizerTransformTab` (keyframe-aware sliders: copy its keyframe button wiring for S4).
  Drawer: `showVisualizerObjectDrawer`.
- **Preview:** `ui/faditor/waveform/WaveformOverlayView.onDraw`: per item, a Bitmap from
  `renderer.renderReusable(...)` at `o.mapToSourceMs(playheadMs)`, drawn at the animated pose
  with rotation and fade. The view gets `setData(Map<sourceRef, WaveformData>)` from
  `FaditorEditorActivity.refreshWaveformOverlays()`, which knows each source's `Uri` (it calls
  `WaveformExtractor.extractAsync(uri, ...)`). Playhead: `playheadUpdater` every 50 ms ->
  `setPlayheadMs`.
- **Export:** `ui/faditor/export/CompositeExportOverlay.getBitmap(presentationTimeUs)`, the
  waveform loop: `waveRenderer.render(...)` returns a NEW Bitmap per frame (recycled after the
  draw), placed at the animated pose, fades applied. Slots come from
  `ExportManager.buildWaveformSlots` (has `wfUri` per item and the `context`). `getBitmap` runs on
  **Media3's GL thread with Media3's EGL context current**: never make another context current
  on that thread.
- **Sound:** `ui/faditor/waveform/PcmSidecar`: whole-source PCM, 22050 Hz mono floats;
  `isBaked(ctx, uri)`, `bakeAsync(ctx, uri, cb)`, `open(ctx, uri)` -> `Handle` (`frameForMs`,
  `frameAt(int)`, one reader per handle: open your own).
- **Words:** `Clip.getActiveNamedTranscript()` / `AudioClip.getTranscript()`; `TranscriptWord`
  (`text`, `startMs`, `endMs`, `struck`) in **source** ms.
- **VisLab side:** `vislab/gl/VisRenderer` (`render(features, timeSec, dtSec, fbo, w, h)`,
  `setScene`, `setTransparent(true)`, `setForceFrame(1)` = soft contained edges, `setWords`,
  `setWordsTime`, `setBrandPalette`), `vislab/VisLab.get(ctx).newRenderer()`,
  `vislab/audio/SpectrumAnalyzer(2048)` + `FeatureExtractor(rate, 2048).update(db, l, r, dt)`
  (exactly what `LiveAnalyzer.step` does), `vislab/room/VisLabSession.get(ctx).lookJson(id)`
  (runs on the main thread; call it on main or at slot-build time), `vislab/words/WordFeed`.

## 4. Build order for C4 (tick each in VISLAB_TODO)

**S1. The item can carry a VisLab look.** `WaveformOverlayInstance`: `@Nullable String visLook`
(a look id; null = classic). `ProjectStorage`: write `visLook` when non-null, read it when present.
Undoable change through the editor's existing `LambdaAction` pattern.

**S2. Rolodex section.** In `buildVisualizerStyleTab`: above the Rolodex, a "VisLab looks" strip
(look names from `VisLabSession.get(this).lookIds()/lookNames()`, the chosen one solid cyan) and a
"Classic" chip. Choosing a look: `overlay.setVisLook(id)` (one undo step), refresh preview. While a
VisLab look is on, show the VisLab section (S4 editor + "Edit in VisLab") INSTEAD of the classic
carousels and mode toggles ("swap the knobs"); "Classic" brings them back. Hover labels on all.

**S3. One renderer for preview and export (the hard part).** Package `vislab/studio/`:
- `OffscreenGl`: a `HandlerThread` with its own EGL 1.4 display, an **ES 3** context
  (`EGL_OPENGL_ES3_BIT_KHR`, version 3; copy the setup in `vislab/view/VisTextureView`) and a 1x1
  pbuffer surface. Per request size: one FBO with a `GL_RGBA8` renderbuffer (re-made on size
  change). After `VisRenderer.render(f, t, dt, fbo, w, h)`: `glReadPixels(0, 0, w, h, GL_RGBA,
  GL_UNSIGNED_BYTE, buf)`, **flip rows** (GL is bottom-up), `bitmap.copyPixelsFromBuffer(buf)`
  into an `ARGB_8888` Bitmap. The output is premultiplied (the renderer blends ONE,
  ONE_MINUS_SRC_ALPHA), which matches Android Bitmaps. One `VisRenderer` per item (they keep
  living-layer state), all on this one thread/context.
- `SoundClock` (one per item key): features at a SOURCE time `t` from `PcmSidecar`. It keeps a
  `FeatureExtractor(22050, 2048)` + `SpectrumAnalyzer(2048)` and the last analysed time. Steps are
  1/60 s: for each step take the 2048 samples ending at that time (`Handle.frameAt`), analyse,
  `update(db, l, null, 1/60.0)`. If `t` is ahead by less than 0.5 s, step forward to it; otherwise
  (a seek) start a fresh extractor at `t - 2.2 s` and step up to `t` (2.2 s fills the 64-row
  history and settles beat tracking). Not baked yet: `bakeAsync` once, silence (`new Features()`)
  until then. Note: 22050 Hz loses the top of VisLab's "air" band (6-16 kHz); fine for now, an
  upgrade is decoding at the native rate (copy `PcmSidecar.bake`'s decode loop).
- `StudioVis` (facade, singleton): `Bitmap renderBlocking(key, lookJson, uri, sourceMs,
  timelineMs, w, h)` for export (posts to the GL thread, waits with a latch, returns a NEW
  bitmap); `Bitmap previewFrame(key, lookJson, uri, sourceMs, timelineMs, w, h, Runnable
  onReady)` for preview: never blocks; returns the last finished bitmap (or null), queues ONE
  pending render per key (coalesce: keep only the newest request), calls `onReady` (post an
  invalidate) when done. Renderer setup per key: `setScene(VisScene.parse(lookJson))`,
  `setTransparent(true)`, `setForceFrame(1)`, `setWords(null)` (S5 changes that). Time for the
  renderer: timeline seconds; dt = difference from that key's last render, clamped to 0..0.1.
  `releaseAll()` from the editor's `onDestroy`.
- Wire preview: `WaveformOverlayView` gets `setSourceUris(Map<String, Uri>)` (fed beside
  `setData` in `refreshWaveformOverlays`); in `onDraw`, an item with `visLook` takes
  `StudioVis.previewFrame(...)` instead of `renderer.renderReusable(...)` (skip drawing while it
  returns null); everything after (pose, rotation, fade, selection box) stays.
- Wire export: `WaveformSlot` gains `uri` and `visLookJson` (filled in `buildWaveformSlots`; get
  the JSON through the main thread there, e.g. a latch-posted `VisLabSession.lookJson`); in the
  loop, an item with `visLookJson` takes `StudioVis.renderBlocking(...)` instead of
  `waveRenderer.render(...)`.
- Acceptance (phone): a VisLab look on a visualiser item moves with the clip's music in preview;
  export the clip; the exported video shows the same look at the same place, moving with the
  same music (compare a frame of each). Scrub: no crash, picture settles within a moment.

**S4. Small editor (keyframable).** In the VisLab section: Reactivity (0-2) and Fade (0-1) as
item properties stored in the item's `KeyframeSet` under names like `vis.react` / `vis.fade`
(read with `keyframes.valueAt(name, localTime(t), default)`), each slider with Studio's keyframe
helper button (copy the Transform tab's wiring). Apply them per frame: Reactivity -> for now set
every layer's `react` before rendering (`CompiledScene.Layer` reads the layer JSON's `react`; a
cleaner hook is a renderer-level multiplier in `KnobSet.eval(signals, react)`), Fade -> the
bitmap's paint alpha (multiply with the item's existing fade). Then 3-4 of the look's own knobs
chosen per look family (brightness, size, colour shift, speed) the same way. "Edit in VisLab":
start `VisLabActivity` with the look id (the room picks the last look from prefs
`vislab/last_look`; set it before starting).

**S5. Transcript words.** For an item whose look has a Words layer: build a `WordFeed` from the
item's audio source transcript (words in source ms -> seconds, skip `struck`), `setWords(feed)`
and `setWordsTime(sourceMs / 1000.0)` per frame. Preview and export use the same feed.

## 5. Pitfalls already met

- `VisLabSession.lookJson` hops to the main thread; never call it from Media3's GL thread while
  the main thread waits for export.
- Living layers (particles, slime, reaction-diffusion, echo) step on a 60 Hz tick grid; a big
  time jump steps once (no reset). Fine for export (monotonic); for preview after a seek the
  picture needs a moment to settle.
- Music-linked speeds keep a running distance (`rate` knobs): after a seek the distance restarts
  as base speed x time, so a seek can shift a spin slightly versus continuous play. Export plays
  from the item's start, so export is deterministic.
- The VisLab picker overlay closes after 6 s untouched (15 s while the list is open).

## 6. When done

Tick S1-S5 and C4 in `tasks/VISLAB_TODO.md` with commit hashes; update the status table in
`tasks/VISLAB_SPEC.md` (section 9) and section 7 ("Built" paragraph); push; report to the owner
with a before/after screenshot of a Studio frame and the exported frame side by side.

# VisLab: what is left (durable handoff checklist)

Lane `VISLAB_20261016`. Started 2026-10-18 for the owner's last VisLab window of the week. If you
pick this up cold: read `tasks/VISLAB_SPEC.md` first (the truth for VisLab), then this list top
to bottom. Tick `[x]` as each item is done **and phone-tested**; add the commit hash.

Working rules (from the spec and the owner): phone-first (Note 9 = sandbox, never the Note 20);
`$env:PHONE_OWNER='vislab'; .\tools\phone.ps1 claim vislab "<why>"` before driving the phone and
release after; never run gradle (the watcher builds; check `build.log` and the APK time); never
uninstall or clear data; commit only your own paths (`git commit -- <paths>`, other lanes stage
things); push at the end of each item. Off-phone checks: `bash tools/jvm-harness/run-vislab.sh`.
Web pilot: `preview_start vislab-web`, page `tools/vislab-web/index.html#<look-id>`,
`window.__vislab.reload()` after shader / look edits. Measure phone speed with
`scratchpad lyricfps.sh`-style logcat reading: `adb logcat -s VisTextureView:I` prints
`fps N scale X% heat N ... gpu ... = ms` once a second.

## A. Lab: build your own (owner, 10-18: "the lab part is making everything composable")

- [x] **A1. Colours per layer.** Each layer can keep the look's colours or take its own palette
  - DONE 10-18, Note 9: the aurora in Ember inside My look; brand lock still wins (pilot too).
  (the aurora in Ember colours inside an Ice look).
  - Data already exists: a layer's `"palette": [5 hex]` (`CompiledScene.Layer.palette`,
    `VisRenderer.useLayer` prefers it). Brand lock must still win over it (check `useLayer`).
  - App UI: `vislab/room/KnobPanel.java` `buildLayers()` — after the layer's own controls add a
    "Colours" row: "The look's" pill + the `PALETTES` chips (same `paletteChip()` as
    `buildColours()`), `host.beginEdit("Layer colours")` / `L.add("palette", ...)` / `host.edited()`.
  - Pilot: `tools/vislab-web/vislab-app.js` layer section (`knobRows(lp, common ...)`) — same row.
- [x] **A2. Start from nothing.** A "+ New" card first in the look strip makes an empty look
  - DONE 10-18, Note 9: the + New card opens an empty look with the shelf open (pilot too).
  (no layers, black, Joy palette, named "My look", "My look 2"...) and opens the editor on
  Layers with "+ Add a layer" open.
  - Session: `vislab/room/VisLabSession.java` — add `newLook(label, group)` beside `duplicate()`
    (line ~335): same remember/undo, file written by the normal sync.
  - Strip: `vislab/room/LookStrip.java` `setLooks()` — a leading "+ New" card with its own
    callback; `VisLabActivity` wires it (it already wires `onPick`).
  - Check an empty look renders (background only) in `VisRenderer.render` / `CompiledScene`.
- [x] **A3. Parts shelf in "+ Add a layer".** Besides the base pieces, every layer of every look
  - DONE 10-18, Note 9: Parts from every look in + Add a layer; a part keeps its home colours (pilot too).
  is a part you can drop in: "Aurora · Northern Lights", "Constellation · Written in Stars".
  - `KnobPanel.buildLayers()` `addBox` (line ~217): a "From looks" section listing each
    look's layers (Host needs `List<VisScene> allLooks()` from the session).
  - Copy = deep copy of the layer JSON; if it has no own palette, give it its source look's
    palette so it looks the same in the new look (then A1 lets you change it).
  - Pilot: `vislab-app.js` add grid (`for (const fam of T.families)` ~line 401).
- [x] **A4. My parts.** "Save as a part" on the chosen layer writes `Supplies/Looks/<Name>.vispart.json`
  - DONE 10-18, Note 9: Save as a part wrote Supplies/Looks/Fire Aurora.vispart.json, shown under My parts (app only; the pilot has no folder).
  (`{"format":"joy.vis.part/1","name","from","layer":{...}}`); the shelf shows "My parts" first.
  - `vislab/room/LookFiles.java`: `readAll` only reads `.vis.json`, so parts sit beside looks
    without being taken for looks; add `readParts` / `writePart`.
  - Shared file (announce): `studiokit/.../joyfolder/FileKind.java` LOOK endings + ".vispart.json";
    `JoyFolderTests` gets `eq(FileKind.LOOK, FileKind.of("Aurora.vispart.json"))`.
- [x] **A5. Split the big pieces into mixable parts** the way Words became path x behaviour x
  - DONE 10-18, Note 9 + pilot: particles were already independent choices (9 emitters x forces / 4 attractors x 9 looks); what hid them was 48 knobs in one list. Now four parts in the editor: Born from / How they move / What each one looks like / Camera (3D); Attractor size only with an attractor. Separate GPU programs per part (as Words) only if a phone test shows particles slow.
  letters. First: particles = where they come from x how they move x what each one looks like
  (`types.json` "particles", `shaders/particles_sim.frag`, `particles.vert/.frag`). Plan it in
  the spec before building.

## B. Words

- [x] **B1. Typed words.** Type your own words; they show in the Words layer one per beat.
  - DONE 10-18, Note 9: a Words layer's Your words (kept in the look as "text", one word per 0.6 s, round and round); Firework Words showed JOY CREATOR LIVE TONIGHT (pilot too). Later: a pace knob, fireworks read better slower.
  Pilot has `WordFeed.setTyped()` (`tools/vislab-web/vislab-words.js:60`); Java
  `vislab/words/WordFeed.java` needs the twin. Room: a "Words" pill beside the sound chips in
  `VisLabActivity` (`sources` SlantRow ~line 231) opening a text sheet (`LookSheets.askName` style).
- [x] **B2. Ribbon + Burst is faint.** `shaders/words.frag` `weave()` (~line 417) with behaviour 4.
  - DONE 10-18, pilot (no shipped look uses Ribbon + Burst; same shader on the phone): on a path, coming words wait whole and dim and burst as they are sung; before, only the word being sung showed.
- [x] **B3. Busy moments dip to the mid-30s** (Note 9, 10-18, 20 s each: Smoke Words 44-55 fps,
  - DONE 10-18, owner's ruling: keep the curling smoke as it is; the mid-30s dips at busy moments are fine. Do not swap in a cheaper swirl.
  Cigar Smoke 37-53, Written in Stars 34-55, all at 40-45% resolution). Next lever: the smoke
  behaviour's swirl (`curl2`, 4 noise samples) per word in `behave()` bh 3. A 2D flow would be
  about twice as fast but looks less like real curling smoke: the owner's call (taste over speed).

## C. The other places

- [x] **C1. Lobby Viz Lab hero** shows "No visualisers yet": show the last look moving.
  - DONE 10-18, Note 9: leaving VisLab saves a still + name (room/LobbyHero); the lobby shows LAST VISUALISER, the look, Carry on.
  `ui/lobby/LobbyFragment.java:500` (`R.string.lobby_empty_viz`); a small `VisTextureView` with
  the session's current look, paused when off screen.
- [x] **C2. Floating visualiser: a look picker** (only ‹ › now) — `vislab/overlay/OverlayControls.java`.
  - DONE 10-18, Note 9: tap the look's name in the floating controls for every look by name; picked Glass Peaks from the list (it also floats over the home screen). The chosen row is solid cyan and the list stays 15 s (built after the test, compiles green).
- [x] **C3. Floating visualiser hears phone audio without recording** (Android's playback
  - DONE 10-18, Note 9 with the owner: speaker button -> Android's question -> Start now -> hearing the phone; Glass Peaks jumped with the demo song playing on the phone; tapping again went back to the microphone and Android released the permission. Apps that forbid capture stay silent.
  capture needs the screen-capture consent) — `vislab/overlay/OverlaySound.java` (NONE/RECORDING/MIC).
- [ ] **C4. Studio: VisLab looks inside Studio's visualiser** (full brief for the next agent:
  `tasks/VISLAB_HANDOFF_20261018_STUDIO.md`) (owner 10-18: build it here, not the
  Studio lane; integrate, not override; "Studio is meant to replace After Effects").
  Owner rulings 10-18: VisLab looks are a **section of the Rolodex** (same visualiser item, classic
  styles stay selectable); a **small editor in Studio** for values worth keyframing (reactivity,
  fades...), using Studio's shared keyframe helper (two arrows, diamond); movement / attachment comes
  from Studio's own transform keyframes and proxy links (the item already implements LinkFollower).
  Scouted 10-18 (Haiku), paths under `app/src/main/java/com/fadcam/`:
  - Item: `ui/faditor/model/WaveformOverlayInstance.java` (time range :41, centre/size/rotation
    :109-113, styleId :34, customStyleJson :106, keyframes :223, mapToSourceMs :473). Saved by hand in
    `ui/faditor/project/ProjectStorage.java` writer :2569-2646, reader :3534-3630 ("absent = default").
  - Add: `FaditorEditorActivity.addWaveformVisualizer` (~:20423). Style tab: `buildVisualizerRolodex`
    (~:20595), carousel writes styleId (~:21099). Transform tab: `buildVisualizerTransformTab` (~:32143).
  - Preview: `ui/faditor/waveform/WaveformOverlayView.onDraw` (:133-197) renders a Bitmap per overlay
    (`renderer.renderReusable` :160). Playhead from `playheadUpdater` every 50 ms.
  - Export: `ui/faditor/export/CompositeExportOverlay.getBitmap(presentationTimeUs)` (:859), waveform
    loop :1097-1169 (`render` :1120, time `instance.mapToSourceMs(timelineMs)` :1105). Runs on Media3's
    GL thread, so VisLab must render on its OWN EGL thread and hand back a Bitmap.
  - Sound: `ui/faditor/waveform/PcmSidecar` (whole-source PCM, 22050 Hz mono, `open(ctx, uri)`,
    `frameAt`), used by scrubbing. Transcripts: `Clip.getActiveNamedTranscript`, words in source ms.
  Build order (tick each):
  - [x] S1 the item can carry a VisLab look (`visLook` id), saved and loaded, undoable.
        PHONE-TESTED 10-22 (Note 9), ad63b8db1: picked in the Style tab, survived a reinstall + reopen, one undo.
  - [x] S2 Rolodex section "VisLab looks"; choosing one swaps the classic knobs out (Classic brings them back).
        10-22: `vislab/studio/VisLabStyleTab`, hooked in `FaditorEditorActivity.buildVisualizerStyleTab`
        (the old Rolodex is now `buildClassicVisualizerStyle`). Look row on top: Studio's `‹ ◆ ›` + chips
        Classic / every look, the one at the playhead solid cyan, scrolled into view on open.
        PHONE-TESTED 10-22, ad63b8db1: pick -> body swaps to the VisLab values; one undo per pick.
  - [x] S3 one renderer for preview and export: offscreen ES 3 on its own thread -> Bitmap; sound from an
        offline 60 Hz feature track of the source (PcmSidecar -> FeatureExtractor), cached.
        10-18: core BUILT and compiling (`vislab/studio/OffscreenGl`, `SoundClock`, `StudioVis`; a
        replay-after-seek clock instead of a stored track); NOT wired into preview/export, never run.
        10-22: WIRED. Preview: `WaveformOverlayView` (`setSourceUris`, `StudioVis.previewFrame`, drawn
        scaled from at most 720 px, repeat requests for the same moment coalesced so a paused preview
        never loops). Export: `WaveformSlot.visLooks`/`visUri` from `ExportManager.buildWaveformSlots`,
        `CompositeExportOverlay` renders with `renderBlocking` (classic style if it returns null).
        `ExportService` warm-up waits for the person's looks and bakes each VisLab item's sound first
        (`vislab/studio/StudioVisExport`). Preview and export keep separate renderers
        (`previewKey`/`exportKey`); idle renderers are let go after 20 s; the editor's onDestroy calls
        `releasePreviewIfRunning`.
        PHONE-TESTED 10-22: Halo Drop plays in the preview with the clip's song (the sound copy is made
        on first pick), the Joy emblem picture shows, a paused preview does not loop; single-frame
        export (PNG) and an 8 s range export draw the same look at the same place, moving with the
        music (ripples on kicks). Export runs in its own process: the warm-up covers it. Cost on the
        Note 9: ~49-55 ms per exported frame for a 1080-wide item.
  - [x] S4 small editor: Reactivity, Fade (opacity) and the look's key knobs, keyframable.
        (Look keys, Reactivity, Fade DONE and phone-tested 10-22.)
        10-22 later: the key knobs BUILT as four look-wide controls, keyframable like the rest:
        Bright (x every layer's Brightness), Colour (+ every layer's Colour shift; palettes wrap),
        Size (x every layer's size in its own space, the common "size") and Speed (x every rate knob,
        on the travelled path so it never jumps; once a speed was hosted it stays on that path).
        Owner's own list (10-18: brightness, size, colour shift, speed). Look-wide rather than
        hand-picked per look: works for every look and every future element (KnobSet finds them by
        knob id: "brightness", "palShift", "size", rate knobs). Code: `KnobSet.host`,
        `VisRenderer.setLookControls`, `vislab/studio/VisControls`, `model/VisLabKeys` (VALUES:
        vis_react, vis_fade, vis_bright, vis_hue, vis_size, vis_speed; json react/fade/bright/hue/
        size/speed). PHONE-TESTED 10-22 (Note 9), 345b1f6b3: Size 250% made the transcript word "pipsqueak"
        large and readable in a strip-shaped item (preview and export); Colour 50% shifted the
        word's colour; Bright 0% hid it; Speed 0% froze Halo Drop's drifting wash, 300% reshaped it
        frame to frame; each drag one undo. Paused frames cannot show Speed (no time passes).
        Owner 10-22: the LOOK itself gets a keyframe too (which VisLab look shows from when),
        at the top of the editor. A held (step) key: the look changes at the key, no blend.
        10-22: BUILT. `model/VisLabKeys` on the item (look keys incl. Classic, Reactivity 0-2, Fade 0-1,
        their own KeyframeSet so they never arm the pose), saved as `"vis"` in project.json.
        Reactivity = `VisRenderer.setReactivity`: it scales what the look HEARS (levels, hits, spectrum,
        waveform, history; running totals by scaled steps, so a change never jumps), not just the music
        links: a phone test showed link-only scaling left most of a look (bars, spectrum reads) as loud
        as before. The row is labelled "React" (the drawer's label column fits ~7 letters). Fade = the
        picture's alpha in preview and export. Rows use `PipDrawerTabs.addPropRow` (diamond, ease on
        hold, one undo per drag). Still owed: the look's own key knobs (3-4 per look family).
        PHONE-TESTED 10-22: look keys (key at 0 s, Lyric Flight keyed at 4 s, held between, undo
        removes it), React 0 / 100 / 200 % visibly calm / as set / wild on a paused frame, Fade 50 %
        half see-through, each drag one undo. Lyric Flight draws nothing in Studio until S5 (no words).
  - [x] S5 the transcript's words feed a look's Words layer.
        10-22 BUILT: `vislab/studio/StudioWords` (unstruck words in source seconds + a change key);
        preview asks the editor live (`WaveformOverlayView.setTranscriptSource` ->
        `FaditorEditorActivity.visualizerTranscript`), export snapshots them into
        `WaveformSlot.visWords`; `StudioVis` keeps one WordFeed per item, rebuilt only when the key
        changes, timed by `setWordsTime(sourceMs / 1000)`. A look's own typed words still win. With no
        transcript, the VisLab section says to make one (Transcript, in the toolbar).
        PHONE-TESTED 10-22, 345b1f6b3: Lyric Flight on BundlingFontTest's cat clip shows "this", "she",
        "is name", "pipsqueak" as they are spoken; a single-frame export at 7 s shows "pipsqueak"
        too; the hint stays hidden when the clip has a transcript.
        Seen while testing (Studio, not VisLab): leaving the editor through the export-complete
        screen loses the last in-session undo steps on reopen (the project itself keeps them), so
        an undo after reopening reaches further back than expected.
## E. Audit 10-18 (tasks/audits/2026-10-18/; details in the handoff, section 7)

- [ ] **Audit R1-R3** floating visualiser: fixed 10-18 (`VisOverlayService`, `OverlaySound`, `AnnotationService`).
      R3 PHONE-TESTED 10-22 (close by its X -> the recorder menu reads Off). R1 owed: needs a screen
      recording running (the owner taps Android's "Start now"). R2 owed: needs an Android 12+ phone
      (the Note 9 is Android 10).
- [x] **Audit V1-V9** VisLab: fixed 10-22 (ad63b8db1). PHONE-TESTED on the Note 9: V3 (paused = pixel-identical
      frames, no renders; an edit while paused redraws once), V5 (the mic closes on pause, reopens on
      play: `dumpsys audio`), V2 (two Words layers with different typed words each draw their own,
      55 fps), V6 (the Joy emblem shows in the floating visualiser and in Studio), V9 (the person's own
      look comes back after a force-stop). V1 (failed write retried), V4, V7, V8 are code-checked
      only (no visible symptom to provoke on the phone); the normal save path was seen working
      (a look file edited then undone came back byte-identical).

## D. Speed, heat, sharing

- [x] **D1. Crystal and Gyroid Dive** are the slowest looks (`shaders/voronoi.frag`, `raymarch.frag`).
  - DONE 10-18, Note 9 (cool): Crystal 44-53 fps, Gyroid Dive 47-54 (60-64% resolution). Fine after the earlier fixes; no change needed.
- [ ] **D2. The real heat case:** floating visualiser over a game while recording, an hour.
  Heat guard is in (`view/HeatGuard.java`, seen working 10-18).
- [x] **D3. Republish the web look book** with today's looks: `node tools/vislab-web/build.mjs`, then
  - DONE 10-18: rebuilt (39 looks, 44 shaders, with the Lab parts, typed words and the spiral fix) and republished as version 3 of https://claude.ai/artifact/Kc5e3auEwB8KQorH92hUXU (private until shared from its Share menu).
  the Artifact update (the pilot page).

## F. Later (owner questions, 10-22)

- [ ] **Element packs (third-party / plugins).** Today: a look (`.vis.json`) and a part
  (`.vispart.json`) are the person's own files; the 26 elements live in the app. 21 of them are
  "fullscreen" kind = one `types.json` entry + one `.frag` (no Java; the editor builds itself;
  `VisRenderer` line ~594 `program("fullscreen.vert", t.frag)`). The other 5 (particles, slime,
  rd, words, image) are engines with Java. Proposed: a pack = a folder in `Supplies/` with its own
  `types.json`-format list + `.frag` files, merged into the registry at load; fullscreen/instanced
  kinds only; OFF until the person turns it on; a test compile + one timed draw on load (GpuTimer);
  HeatGuard covers it; a look naming a missing element keeps it (LookValidator keep-unknown) and
  says which pack it needs. Shaders cannot touch files or the network, but can hang the GPU.

## G. Owner review, 10-22 evening (his words condensed; recommendation after each)

Quick wins (do first):
- [x] **G1 Sliders: see where it was, get back to it.** 10-23 BUILT (Sonnet helper: `room/SnapSlider`,
  `AngleMarks`, `GhostSeekBar`, `KnobPanel`; pilot `vislab-app.js`). PHONE-TESTED: a rough drag on
  Turn landed on exactly 45.00; mid-drag the ring sat at the start value while the thumb snapped to
  90.00; one undo per drag. While dragging, a ghost dot marks the value
  at touch-down and the thumb softly snaps back to it, and to 0, the default and 45/90/180 on angles.
  ("Really hard to get it back to 0 or 90 or 45.") Room `KnobPanel` + web pilot; propose app-wide.
- [x] **G2 Sliders that seem to do nothing.** 10-23: 66 music-only knobs flagged `"music": true` in
  `types.json` (Haiku helper, each checked against its shader line; doubtful ones left unflagged:
  lightning's knobs, floor.chase, lasers.strobe, points.spinBass, plasma.react). Rows show a note
  badge + hover "Acts on the music: play something to see it" and dim while paused or silent.
  PHONE-TESTED: Bass swell row normal while playing, dimmed when paused. Mode-only knob audit not done. Many only act on the music (Sensitivity, Kick pump,
  Kick flash, Bass swell, Pulse...) or only in some modes. Flag music-only knobs in `types.json`
  (e.g. `"music": true`), show them with a small note badge and dimmed while it is silent/paused,
  hover label "Acts on the music". Audit mode-only knobs and hide them where they do nothing
  (`showFor` exists).
- [x] **G3 Turning with the sound.** 10-23: Place **Spin** (turns per second, a speed: travelled
  turns in `CompiledScene.Layer` / pilot `_spun`, carried across edits). PHONE-TESTED: Spin 0.25 on
  the Spiral layer turns the whole layer steadily. The note on Turn already swings the angle with a sound (answer:
  kick = punch, bass = slow swell, beat = steady). Add a Place **Spin** (a speed, `"rate"`) so a
  layer (e.g. Sound City's woven floor) keeps turning and speeds up on the beat, smoothly.
- [x] **G4 Words fill the spiral.** 10-23: 48 word slots (one-channel 2048x6144 sheet, 12 MB, under
  the old 16 MB), Look ahead up to 12 s, new **Look behind**, **Shift along** (ribbon/spiral/ring/
  tunnel/street) and **Fade in / out** (`words.frag` `edgeFade`, `uWordWin`). Pilot: Spiral Banner
  at 59 fps with up to 23 words on screen (was capped at 16). PHONE: the knobs show in the room;
  their effect not yet judged by the owner. (a) a bigger word buffer: `WordFeed.SLOTS` 16 -> ~48 (atlas
  re-layout: 2 columns or shorter rows; `words.frag` SLOTS too; pilot `vislab-words.js`);
  (b) **Look behind** knob (now fixed `back = 4.0` in `VisRenderer`); (c) **Shift** along the path
  (put the words where you want them on the spiral / ribbon); (d) **Fade in / out** for every
  behaviour (words popping in on the ribbon). Look ahead (`lead`) already exists.
- [ ] **G5b Studio visualiser onto the shared transform box** (split from G5, 10-23; plan by a Sonnet
  scout, verified against the code by name only). Today's box (inside `WaveformOverlayView`) has no
  rotate, writes statics only (a keyframed visualiser does not move when dragged), drags have no
  undo, handles hit-test unrotated, and it keeps its own selection (timeline selection shows no box;
  `hitsInPreview` is false for visualisers). Plan: new `ui/faditor/transform/VisualizerTransformHost`
  implementing `TransformOverlayView.Host` directly (free aspect: `AffineTransformHost` is uniform),
  `writeQuad` -> width/height/rotation/centre, affine only, no bend/flip; in `FaditorEditorActivity`
  `transformVizId` + `enterVisualizerTransformMode` (model: `enterSpriteTransformMode`), a
  `vizHandlesTarget` whose writes copy `vizMenuProp`'s setter (linked / armed / static), undo via
  `snapshotViz` + `recordVizMenuUndo`, branches in `updatePreviewHandlesForSelection`,
  `hitsInPreview`, `collectSnapFrames`, `selectAt`, `handoffGesture`; `WaveformOverlayView` stops
  taking touches. Risks: re-tap -> double-tap, hold-to-drawer needs a body-hold hook in the shared
  box, linked followers (world <-> own pose), model clamps (centre 0..1, size 0.05..1) vs the box's
  -2..3. Seen on the way: `handoffGesture` omits `transformSpriteId` (first drag on an unselected
  sprite may do nothing); export draws a visualiser for its whole bound clip without a start/end
  check (verify); hidden/locked are saved only for attached visualisers (ProjectStorage ~2636);
  JoyBot's blend action (`LooksActions`) does not cover visualisers.
- [ ] **G5 Studio visualiser: the shared transform box + blend mode.**
  10-23: a VisLab look on a clip with no sound data (a silent screen recording) was dropped from
  export (ExportManager skipped slots without waveform data): now a silent stand-in keeps the
  slot. Found while phone-testing the blend: the exported frame had no visualiser.
  Blend PHONE-TESTED so far: the Blend mode tab with the full list (Normal, Darken, Multiply,
  Screen, Add, Overlay, Difference...), one undo per pick, the "applied when you export" note.
  Export blend PHONE-TESTED 10-23 (2cda719a2): Halo Drop set to Difference on a silent screen
  recording ("no audio track") exported as a single frame through `VisualizerBlendGlEffect`: the
  emblem's colours flip against the icon beneath (orange -> blue, glow -> green), and the silent-clip
  fix kept the visualiser in the export. Test edits undone (3 steps, restored from disk).
  10-23: Blend mode BUILT (Sonnet helper): `overlayBlendMode` on the item (same key as pictures),
  a Blend mode tab in the drawer (`PipDrawerTabs.blendTab`, one undo per pick), export blends for
  real through `export/VisualizerBlendGlEffect` (same equations as pictures); preview shows Normal
  (as pictures and PiPs do; a true preview needs the GL compositor). Normal visualisers unchanged. Move the visualiser from its
  own older box onto Studio's shared transform box (`studiokit/.../transform/TransformOverlayView`,
  same as pictures/text: move, corners, rotate handle below). Add **Blend mode** to its drawer,
  default Normal, the image drawer's list (`layers.BlendMode`), preview and export.

Bigger:
- [ ] **G6 Split scenes into composable elements.** Aurora is curtains + mountains + stars in one
  shader (snow is already a separate layer): he wants each on its own (soften the curtains, keep
  stars crisp with their own twinkle behaviours, mountains he drives past instead of warping).
  New elements: Aurora curtains, Mountains (ridge silhouette, drive-by scroll), Stars (crisp,
  twinkle behaviours), plus an audit of other lumped scenes (Nebula has stars; Outrun = sun +
  mountains + grid + sky; Woven floor's horizon glow). Stacking order + Light/paint gives the
  occlusion. Keep the combined elements selectable; existing looks unchanged.
- [ ] **G7 Studio proxies drive look knobs.** In Studio, a VisLab layer's Place (across, up, size,
  turn) and a picture's own position / size / spin can follow a Studio proxy (dummy) object, so a
  dummy animates them. Later: any knob driven by any Studio object's property.
- [ ] **G8 Media inside looks** (he asked for options): (a) Sprite element: a SpriteLab sheet
  playing inside the look (frame rate, a frame per beat, ping-pong), every effect applies;
  (b) Video element: a clip decoded into the look; (c) Studio layer reference (precompose): a
  look's media layer shows another Studio item rendered each frame; (d) VisLab's finishes (echo,
  bloom, glitch, RGB split, warp) as a Studio effect on any clip or layer; its natural home is
  Studio's adjustment layer (owner 10-23: an empty layer whose effects change everything under
  it, like After Effects, so the effect can be masked, faded in and out, or switched on and off
  by animating the layer; `export/AdjustmentLayerGlEffect.java` exists). Recommended order:
  (a), then (c), with (d) as the long-term way to put the effects on anything; (b) if still needed.
- Answered, no change: the picture's Across/Up/Size place the picture inside its layer; Place
  moves/turns/scales the whole layer (every element has Place). Two levels allow layered motion
  (frame the logo with its own knobs, give Place a music-linked bounce or a proxy link).

## Done this window (newest first)

- [x] Every layer: Reactivity, Softness, Place made in its own space; smooth music speeds
  (`rate`); Spiral Banner filled log spiral; lyrics looks 30-57 fps; heat guard; a build keeps
  what it does not know in a look (ten lyrics looks restored on the Note 9) — `caf5d7445`, `c016532d1`.

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
  - [ ] S1 the item can carry a VisLab look (`visLook` id), saved and loaded, undoable.
        10-18: field + save/load BUILT (not phone-tested; no UI sets it yet; undo comes with S2).
  - [ ] S2 Rolodex section "VisLab looks"; choosing one swaps the classic knobs out (Classic brings them back).
  - [ ] S3 one renderer for preview and export: offscreen ES 3 on its own thread -> Bitmap; sound from an
        offline 60 Hz feature track of the source (PcmSidecar -> FeatureExtractor), cached.
        10-18: core BUILT and compiling (`vislab/studio/OffscreenGl`, `SoundClock`, `StudioVis`; a
        replay-after-seek clock instead of a stored track); NOT wired into preview/export, never run.
  - [ ] S4 small editor: Reactivity, Fade (opacity) and the look's key knobs, keyframable.
  - [ ] S5 the transcript's words feed a look's Words layer.
## E. Audit 10-18 (tasks/audits/2026-10-18/; details in the handoff, section 7)

- [ ] **Audit R1-R3** floating visualiser: fixed 10-18 (`VisOverlayService`, `OverlaySound`, `AnnotationService`), NOT phone-checked yet.
- [ ] **Audit V1-V9** VisLab: open, for the next agent.

## D. Speed, heat, sharing

- [x] **D1. Crystal and Gyroid Dive** are the slowest looks (`shaders/voronoi.frag`, `raymarch.frag`).
  - DONE 10-18, Note 9 (cool): Crystal 44-53 fps, Gyroid Dive 47-54 (60-64% resolution). Fine after the earlier fixes; no change needed.
- [ ] **D2. The real heat case:** floating visualiser over a game while recording, an hour.
  Heat guard is in (`view/HeatGuard.java`, seen working 10-18).
- [x] **D3. Republish the web look book** with today's looks: `node tools/vislab-web/build.mjs`, then
  - DONE 10-18: rebuilt (39 looks, 44 shaders, with the Lab parts, typed words and the spiral fix) and republished as version 3 of https://claude.ai/artifact/Kc5e3auEwB8KQorH92hUXU (private until shared from its Share menu).
  the Artifact update (the pilot page).

## Done this window (newest first)

- [x] Every layer: Reactivity, Softness, Place made in its own space; smooth music speeds
  (`rate`); Spiral Banner filled log spiral; lyrics looks 30-57 fps; heat guard; a build keeps
  what it does not know in a look (ten lyrics looks restored on the Note 9) — `caf5d7445`, `c016532d1`.

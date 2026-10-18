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
- [ ] **A5. Split the big pieces into mixable parts** the way Words became path x behaviour x
  letters. First: particles = where they come from x how they move x what each one looks like
  (`types.json` "particles", `shaders/particles_sim.frag`, `particles.vert/.frag`). Plan it in
  the spec before building.

## B. Words

- [x] **B1. Typed words.** Type your own words; they show in the Words layer one per beat.
  - DONE 10-18, Note 9: a Words layer's Your words (kept in the look as "text", one word per 0.6 s, round and round); Firework Words showed JOY CREATOR LIVE TONIGHT (pilot too). Later: a pace knob, fireworks read better slower.
  Pilot has `WordFeed.setTyped()` (`tools/vislab-web/vislab-words.js:60`); Java
  `vislab/words/WordFeed.java` needs the twin. Room: a "Words" pill beside the sound chips in
  `VisLabActivity` (`sources` SlantRow ~line 231) opening a text sheet (`LookSheets.askName` style).
- [ ] **B2. Ribbon + Burst is faint.** `shaders/words.frag` `weave()` (~line 417) with behaviour 4.
- [ ] **B3. Busy moments still dip to ~30 fps** (Smoke Words, Cigar Smoke, Written in Stars).
  Next lever: the smoke behaviour's `curl2` + `fbm2` per word (`behave()`, bh 3).

## C. The other places

- [x] **C1. Lobby Viz Lab hero** shows "No visualisers yet": show the last look moving.
  - DONE 10-18, Note 9: leaving VisLab saves a still + name (room/LobbyHero); the lobby shows LAST VISUALISER, the look, Carry on.
  `ui/lobby/LobbyFragment.java:500` (`R.string.lobby_empty_viz`); a small `VisTextureView` with
  the session's current look, paused when off screen.
- [ ] **C2. Floating visualiser: a look picker** (only ‹ › now) — `vislab/overlay/OverlayControls.java`.
- [ ] **C3. Floating visualiser hears phone audio without recording** (Android's playback
  capture needs the screen-capture consent) — `vislab/overlay/OverlaySound.java` (NONE/RECORDING/MIC).
- [ ] **C4. Studio** (looks as overlays, scrub, export, transcript words) — handed to the Studio
  lane, `tasks/INBOX.md` 10-17 + spec §7. VisLab side is ready; only do it if the owner moves it here.

## D. Speed, heat, sharing

- [ ] **D1. Crystal and Gyroid Dive** are the slowest looks (`shaders/voronoi.frag`, `raymarch.frag`).
- [ ] **D2. The real heat case:** floating visualiser over a game while recording, an hour.
  Heat guard is in (`view/HeatGuard.java`, seen working 10-18).
- [ ] **D3. Republish the web look book** with today's looks: `node tools/vislab-web/build.mjs`, then
  the Artifact update (the pilot page).

## Done this window (newest first)

- [x] Every layer: Reactivity, Softness, Place made in its own space; smooth music speeds
  (`rate`); Spiral Banner filled log spiral; lyrics looks 30-57 fps; heat guard; a build keeps
  what it does not know in a look (ten lyrics looks restored on the Note 9) — `caf5d7445`, `c016532d1`.

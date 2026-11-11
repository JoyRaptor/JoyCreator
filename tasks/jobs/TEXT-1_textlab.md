# TEXT-1: TextLab (name pending): title text that looks like a film poster

**Milestone:** M1  ·  **State:** 🔨 look engine and look book built; nothing in the app yet  ·  **Owner:** Claude Fable 5.2 "TextLab" (branch `claude/textlab-20261111`, `C:/Temp/textlab-20261111`)  ·  **Checked:** 2026-11-11 against `joy-creator` at `5fe9c0c36`

## Why it matters (for the user)
Studio's text box is fine for slides. TextLab is the step beyond it, the way VisLab went beyond the
first visualiser: words made of chrome, gold, steel, neon and fire, lit like a film title, moving
without the user thinking about it. A look made here appears as a one-tap style on any text in
Studio, VisLab and JoyBrush ("each room is a fountain that waters the others"). Stills for
illustration and moving titles for video.

**The owner's bar (2026-11-11, binding):** a handful of looks that would sit on a Hollywood
poster, not a shelf of presets nobody uses. Glow never covers weak work. See
`tasks/lessons.md` (2026-11-11 TextLab).

## Done when
- [ ] The owner says the hero looks are poster-grade (he judges on his own screen, not from my captures).
- [ ] The same looks draw in the app on the Note 9 from the same shader files (preview = export).
- [ ] Studio: select a text, pick a look from a carousel; the words stay typed and editable; "Open in TextLab".
- [ ] TextLab room on the phone: every knob in `parts.json`, save a look to Supplies, phone tilt moves the light.
- [ ] VisLab and JoyBrush read the same look shelf; the emitter (particles off any shape) is offered to VisLab.
- [ ] Hand-drawn ink (a JoyBrush drawing) can be the "letter".
- [ ] STATE.md rows move from pilot to built to LIVE; Joybot help topics.

## Where it stands (2026-11-11)
Built on the computer, as VisLab was, with the shader files written ONCE in the app's assets:

- **Shared files (what the phone will run):** `app/src/main/assets/textlab/`
  - `shaders/` (GLSL ES 3.00, assembled by the VisLab contract: version + precision + defines + `common.glsl` + body)
  - `parts.json`: every knob (name, uniform, range, plain label, slot). Panels are built from it.
  - `looks/*.text.json`: a look = font + knobs + a material sphere recipe + particles + how letters arrive.
    `looks/index.json` lists **looks** (finished) and **drafts** (kept, unfinished, never shown to a user).
- **Look book (the pilot):** `tools/textlab-web/` (`node tools/textlab-web/serve.mjs`, then
  `http://127.0.0.1:8791/tools/textlab-web/`). Address-bar switches are listed at the top of `tl-app.js`
  (`?sheet=2` shows every look; `&drafts=1` adds the drafts; `&bench=1` times every look).
- **How a look is drawn** (one fixed row of passes, `tl-engine.js` top comment):
  letters become distance pictures (once each) → composed into the FIELD (nearest letter wins, so
  letters can move on their own) → optional melt/wobble → optional fire ticks → the lit letters
  (`shade.frag`: bevel, raised frame, sides by a ray walked through the field, material sphere,
  cast shadow, wear) → flame → particles → bloom → film curve, wet floor, lens streak, grain → edge smoothing.
- **Five hero looks:** Dark Steel, Chrome '84, Gold Crown, Neon Sign, Blaze. Nine drafts kept
  (liquid chrome, steel plate, spirit flame, magma, water, ice, retro pop, candy, toon fire).
- **Proof:** seen by me in the look book at true pixels. All five compile and run on the **Note 9's
  own GPU** (Adreno 630) through the phone's Chrome, nothing installed: 11 to 35 ms a frame at
  1280 px wide including the look book's painted stage (`tools/textlab-web/note9-bench-20261111.json`);
  battery 25.1 °C before and after. **Not proven:** anything in the app; a long run's heat; the
  owner has not yet judged the rebuilt looks.
- **Owner, 11-11 evening:** Dark Steel "is looking good". Blaze "on the right track but still not quite there, keep
  iterating". Neon must be a real object, not a glow (see lessons): rebuilt that evening as a thick round glass rod with
  uneven drifting gas, a window-light, tie wires and a patchy strike (`shade.frag` TUBE; knobs `gas*`, `tie*`), and the
  fire now carries each streamline's own brightness up the flame (fibres; `fire_step.frag` channels b and a).
  Note 9 after that: neon 12 ms, blaze 32 ms a frame. Still owed on neon: electrode ends and blacked-out joins, real
  single-tube letter shapes (a monoline neon face); on fire: taller, wispier tongues.
- **Honest limits today:** the fire is good stylised fire, not yet the photograph the owner showed;
  water and the other drafts are below the bar; only the app's own fonts are used (Archivo, Plex,
  Ubuntu, Doto), so there is no script or true display face yet.

## What is left
- [ ] **Owner's verdict** on the five heroes (and which drafts he wants raised next).
- [ ] Fonts: a short list of open-licence display faces to bundle (needs his yes to download).
- [ ] Fire toward the photograph (finer filaments, a real fluid step if the Note 9 allows); water with splashes.
- [ ] **Phone engine**: a small Kotlin module (new package `cc.joycreator.textlab`, no FadCam code) that runs
      the same passes with GLES 3: reuse the VisLab patterns (`vislab/gl/ShaderLib`, `GlProgram`, `GlTarget`,
      `GpuTimer`, `StudioVis`' own GL thread, `OffscreenGl`). Twin of `tl-engine.js` + `tl-glyphs.js`.
- [ ] Output for a compositor: `final.frag` `uOut = 1` (linear, premultiplied) is written, untested.
- [ ] Studio: a look on `TextOverlayItem` (new optional field; old projects must still open), carousel in the
      text drawer (one press = one undo step), preview and export through the same renderer.
- [ ] TextLab room; Supplies shelf (`Supplies/Text looks/<Name>.text.json`, the user's copy wins).
- [ ] Emitter for VisLab; hand-drawn ink as a piece; weld mode (a whole word as one piece, for script faces).
- [ ] Each letter's own motion carried to its particles; vines, fur and other growing elements (owner's wish list).
- [ ] Materials from a photo of a shiny ball (the sphere is just a picture).
- [x] One-file build of the look book for sharing: `node tools/textlab-web/build.mjs` (`--drafts` adds the drafts).
      Published for the owner 11-11 as the artifact "TextLab Look Book", https://claude.ai/artifact/LwJZr7bdN3erpCreQib64S
      (private to him; republish the same page from a new session by passing that address).

## Specs, handoffs and evidence
- Design conversation and owner rulings: this card, `tasks/lessons.md` 2026-11-11.
- Phone timing: `tools/textlab-web/note9-bench-20261111.json`.

## Files and traps
- **Change the assembly contract in both places** (`tl-gl.js` `TL.assemble` and the future Kotlin twin).
- A shader's surface bumps must use computed noise (`vnoise`); anything that only drifts uses the noise
  texture (`tnoise`, 4 to 7 times faster on the Note 9). Mixing them up shows as steps in a mirror.
- The fire travels several pixels a tick: its feed is read along the path (`fire_step.frag`) and its heat
  picture has a fixed fineness (640 across), or flames comb into stripes and cost grows with the picture.
- A film curve run per colour channel bleaches colour: `final.frag` scales by the brightest channel.
- Half-float render targets are required (present on the Note 9).
- Look files are plain JSON; the web page reads them from the app's assets, so a look seen on the
  computer is the file the phone ships.

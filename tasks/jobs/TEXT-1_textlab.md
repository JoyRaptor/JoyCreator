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
- **Owner, 11-11 later:** the first real-neon pass was too thick. Rebuilt from his BAR photo: `neon-bar` (one thin tube
  writes each letter, a window at night), then the same tube in `neon-sign` (brick, two gases) and `neon-outline` (a
  big sign: the tube runs round heavy letters). New: `tubeHand` (blown glass wavers in width), stage 8 (night window).
  Seven looks in the list now. Note 9: the three neons 11 to 13 ms a frame.
- **Owner's neon rule, 11-11 night (with sketches of B and R):** one tube never joins another; a letter is as few
  tubes as still read; a tube stops short, doubles back (a loop, sometimes painted black) or crosses; every tube has two
  ends that go back to the case. Built: `app/src/main/assets/textlab/tubefont.json` (A-Z, 0-9, a few marks, as centre
  lines), `tools/textlab-web/tl-tubes.js` (path to a round rod, painted ends), `glyph.frag` (the later tube passes in
  front at a crossing), font family `"tube"` in a look. Neon Bar and Neon Sign use it. Owed: his verdict letter by letter
  (the shapes are my first drawing), painted double-backs, supply wires, a pen stroke as a tube, the Kotlin twin.
- **Owner, 11-11 late (with a marked-up Blaze):** (1) fire must also pool UNDER an edge and wrap round it before it
  climbs: built as `fireUnder` / `firePool` (`fire_step.frag`: the shape blocks the rising flame, a pool hangs below
  edges that face down, heat creeps sideways to the corner); off by default so the old fire stays selectable, on for
  Blaze. (2) A neon end is ROUND from the front (the tube turns away before the black tape): ends are now lit round
  elbows with a small dark spot (`tl-tubes.js`). Neither re-timed on the Note 9 yet.
- **Owner, 11-11 last:** Blaze "much closer, keep going"; the bottom must be softer (fire is a plasma): the pool now
  thins away downward with no edge of its own, trapped flame spreads, and carving and rims only come in as flame
  climbs. Neon ends show NO dark spot straight on (the tape is behind the turn): ends are plain round lit elbows.
- **Owner, "softer still":** new knob `fireSoft` (young flame is drawn as an edgeless glow that fades with its heat;
  off by default, on for Blaze), deeper and fainter pool, more spreading of trapped flame, cooler letter rim.
- **Owner, 11-12:** Blaze: "let's go somewhere in the middle of these two ... and then let's ship": set midway
  (`fireSoft` 0.5, `firePool` 0.072, spread 0.57) and locked. Neon Outline had no breaks: every loop of an outline tube
  now has one, with two round ends (`tl-glyphs.js` `withBreaks`, `glyph.frag` uTubePiece 2, `shade.frag`).
  **Second wave of seven (his order):** Liquid Chrome, Crystal Ice, Carved Stone, Diamond, Retro Pop, Hologram (new
  `HOLO` variant), Phantom (letters of mist: the fire with `fireSteady` and the letters hidden). Fourteen looks in the
  list. Note 9, all fourteen, 6 to 40 ms a frame at 1280 wide (Diamond 40, Crystal Ice 35 are the slow ones), 22.8 C.
  His verdict on the seven is owed; I judged them from one or two frames each, less closely than the first wave.
- **Owner, 11-12 later:** of the second wave, Carved Stone, Crystal Ice and Phantom were "completely unconvincing"
  (in that order). Rebuilt from the real things: stone = broken edges (static wobble), rock relief (`detailKind` 9) with
  darker hollows (`detailShade`) and patchy colour (`mottle`); ice = milky middle, fractures, frost, snow on top
  (`ice*` knobs, REFRACT); Phantom = a faint ghost letter (HOLO, no scan lines) with slow sideways mist. Note 9: stone
  29 ms, ice 45 ms (too slow, to trim), Phantom 9 ms. Owed: his verdict; then "take the others to the next level"
  (Liquid Chrome, Diamond, Retro Pop, Hologram), not started.
- **Owner, 11-12 night:** ice "actually looks a little bit worse" (with four reference photos): it must be crystalline
  and jagged, clear in places with deep fissures, frosty in others, chips in the outline that lead to cracks, bubbly low
  and inside, clearer outward and upward. Rebuilt: `chip`/`chipSize` (angular cells bitten out of the outline in
  `soften.frag`), each crystal bends light on its own slant, fissures along the same cell walls and only where the
  block has split, milk and bubbles low and deep, frosted patches. Still short of his photos (too even, too grey).
  Phantom "coming along, push further" (two smoke-letter photos): parts fade in and out of existence (`presence`,
  in `common.glsl`, used by the ghost letter and the mist's feed), the letters waver (animated wobble), denser curling
  mist. Neither re-timed on the Note 9.
- **Owner, 11-13:** ice "much, much better, especially with the parameters that are available"; he tuned it on his
  phone (chip 0.011, crystals 6.285, bevel 0.014 wide, 0.128 high, scooped -0.65) and those ARE the look now. His ask:
  one more push of new parameters. Added: `grow` (shards stand proud), `iceVeil`, `iceBacklight`, `icePrism` (sparks
  that move with tilt), `iceWet`, and cold vapour sinking off the letters (the fire with a negative rise).
- **Owner, 11-13 later:** ice accepted ("Yes, lift the others"). Lifted: Diamond = brilliant-cut stones (`detailKind` 10:
  a table and eight facets per stone); Hologram = colour split, ghost copy, projector beam from the floor line, static,
  and it uses `presence`; Liquid Chrome = slow flow and beads of metal off the edges; Retro Pop = uneven ink, rough
  edges, harder shadow. His verdict on these four is owed.
- **Owner, 11-13 evening:** Diamond "is ready to ship. It's great" (still 46 ms a frame on the Note 9: trim before the
  app). Hologram: a very soft lens colour fringe toward the screen's edges (`lensFringe`). Retro Pop: the printer's
  version (plates out of register, `printFringe`), and the white line in his HOT reference is its own element hovering
  over the letter, not an inner stroke (`hoverX` / `hoverY`). He wants to explore flat print styling further. He asked
  what I would do to the STONE of Carved Stone (the shadow already works): answered in chat, his pick owed.
- **Owner, 11-14:** Retro Pop "is perfect". Carved Stone: build all five of my ideas: done as the `STONE` variant
  (`stone*` knobs: chisel marks, fresh pale chips, veins and strata, mineral grain, dust on ledges, moss in hollows).
  He asked whether people can bring their own fonts: the engine takes any shape, so yes in principle; the look book now
  has a "Your font" file button (pilot only). In the app this should reuse Studio's font keys (`file:` fonts), not built.
- **Fonts (owner's yes, 11-14):** five open-licence faces bundled in `app/src/main/assets/textlab/fonts/` with their
  OFL texts and a README (Pacifico, Bebas Neue, Cinzel, Racing Sans One, Orbitron; about 700 KB). Look book: a font
  picker and a "Your font" file button. Set as the look's own face on Liquid Chrome (Pacifico), Carved Stone (Cinzel)
  and Hologram (Orbitron); the looks he has already approved keep Archivo. The app must load these from assets and
  mirror the keys in `TL.FONTS`.
- **Third wave (real date 2026-11-11; the 11-12 to 11-14 labels above are steps of one long session, not days).**
  The owner asked for seven more TYPES of look, each bringing a new PIECE people can build with (his example: fur),
  an opinion on which fourteen would add the most, and slider groups that fold away. Mid-way he asked whether an
  upright phone would do better with tabs for the families of controls. Built:
  | Piece (new) | Where | Look that shows it | Note 9, 1280 wide |
  |---|---|---|---|
  | Drips: threads with a bead that lengthen, let go and fall; a true shape, so they take the whole finish | `soften.frag` `dripOf`, `uDrip` (`drip*` knobs, slot Shape); engine does shape first, then rounding | Slime | 28 ms |
  | Fur: every strand drawn (18 slices tip to root), tufts combed their own way, ragged overhanging edge | `shade.frag` `FUR`, `uFur`, `furTip` | Shag | 22 ms |
  | Lamp board: the word is rows of glass bulbs in sockets on a framed sign; round or square; a chase | `shade.frag` `CELLS`, `uCells`, `uBoard` | Marquee | 5 ms |
  | Vessel: glass letters part full; level leans with tilt, waves, rising bubbles, a head of froth | `shade.frag` `REFRACT` + `uTank` (`tank*`, slot Glass) | Fizz | 38 ms (slow) |
  | Lightning: bolts that leap off the outline, jag, fork and re-strike (particle shape 5, so ANY shape can throw them: VisLab's emitter gains it), plus a quiet crawl on the metal | `particle.vert/frag` kind 5; `shade.frag` `ARCS`, `uArc` | Voltage (Dark Steel's material) | 35 ms |
  | Cut paper: stacked sheets, each cut by hand, lit and shaded cut edges, a shadow on the sheet beneath | `shade.frag` `PAPER`, `uPaper` | Paper Cut | 11 ms |
  | Burning away: char, a hair of live ember, nothing behind it (no ghost shadow); `burnGone` is an exit animation | `shade.frag` `BURN`, `burnAt`, `uBurn` | Ember Fade | 15 ms |
  Slider families: a row of tabs over the sliders (All + each family; a dot marks the families the look uses). A family
  shows only its own sliders; All keeps the scroll-through view with every group folding away, remembered per device.
  An upright phone starts on a family (the look's own special one). The phone room should copy this.
  Checked: each of the seven looked at up close on the computer, most at one or two moments only (the motion of drips,
  burn, chase and bolts was not watched as a moving picture); all 21 looks still draw; no shader errors on the Note 9's
  GPU; battery stayed 23 C in short runs. Not checked: a long heat run; the owner's verdict on any of the seven.
  Proposed next seven (my pick, his yes owed): vines and leaves that grow along the letter; real water with splashes;
  stitched thread / embroidery; a foil balloon; sand or powder that blows away; a letter made of many small things
  (sequins, coins, sprinkles); ink that bleeds into wet paper.
- **Fourth wave (2026-11-11, the owner's "Yes, next seven").** Seven more pieces, the ones proposed above:
  | Piece (new) | Where | Look that shows it | Note 9, 1280 wide |
  |---|---|---|---|
  | The FRONT: a ragged line that crosses the words; letters and particles share it, so a particle can be born exactly as it passes (`front` particle knob; its drift becomes the wind) | `common.glsl` `uFront` `frontAt`; `particle.vert` `uPFront`; slot Front | Ember Fade moved onto it (sparks now rise from the burning edge only) | 16.4 ms |
  | Vines: stems along the outline, turns wound round each stroke, tendrils; leaves and blossoms are rooted particles (kinds 6 and 7) that open after the front passes, each with its own shadow | `shade.frag` `VINE`; `particle.vert/frag` | Overgrown (Carved Stone's material; grows from the ground up, once) | 35.9 ms |
  | Splashes: drops thrown off the tops, a follower behind each, falling back and merging (true shapes, like the drips) | `soften.frag` `splashOf`, `uSplash` | Water (the draft rebuilt; out of drafts) | 39 ms |
  | Satin stitch: threads straight across each stroke, mitred at corners, twisted, padded, ragged ends; a cloth stage (9) | `shade.frag` `STITCH`; `backdrop.frag` | Stitched | 14.9 ms |
  | Foil balloon: flat weld seam, sharp crinkles that run in from it (along rays from the seam), slow dents | `shade.frag` `BALLOON` | Foil Balloon | 25.4 ms |
  | Sand: every grain its own speck and tilt, glassy grains that flash with tilt, a crumbling edge, loose grains round the foot; blown off grain by grain at the front | `shade.frag` `SAND` | Dust | 19.4 ms |
  | Made of small things: sequins, beads, sprinkles or coins (one knob), overlapping like roof tiles, each with its own tilt, colour and shade on the next | `shade.frag` `BITS` | Sequins | 32.7 ms |
  | Ink on wet paper: bleeds outward along rays from the brush mark, tide line, lighter dye beyond, backruns; takes light from whatever is behind it (multiplies a video) | `shade.frag` `INK` | Ink Bleed (letters arrive one by one) | 7.7 ms |
  Checked: every one looked at up close on the computer AND at several moments (vines growing, sand leaving, ink
  spreading, splashes mid-leap); all 28 looks draw with no shader errors on the computer and none on the Note 9's GPU.
  Battery went 28.2 to 28.8 C over about twenty-five short timing runs (it began warmer than last time). Not checked: the look book's tabs with the seven new families on a real phone screen; a long heat run; the
  owner's verdict on any of the fourteen. Known weak spots: leaves are drawn (illustration, not photograph); sequins make
  a coarse letter at small sizes; Water's top half reads a little like glass because it mirrors a pale sky.
  Speed work this wave: Overgrown first timed 69 ms (its stone base, not the vine: the vine and its leaves cost about
  5 ms); it now uses the stone without the rock relief, veins, dust or moss (all hidden under leaves) and each stone
  feature is skipped when its slider is zero. Drips and splashes find their edge by a fixed 7-look walk (Slime 28 to
  25 ms, Water 53 to 39). Still over the 33 ms line: Water 39, Overgrown 36 (with Diamond 46, Fizz 38, Voltage 35,
  Carved Stone 36 from before). The look book on an upright phone now shows the looks as one row to swipe.
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
- When a pattern must run ALONG an outline (crinkles from a seam, ink feathers, threads) there is no such
  coordinate in a distance picture. Use the FOOT: `q - normal * distance` is the nearest point of the edge, and it
  is the same for every point on one ray out of the edge; noise read there gives one value per ray. Where two
  edges' rays meet (gradient shorter than 1) fade it out, or it shows as slits.
- A shape change (drip, splash) must be its own pass BEFORE the rounding blur, or the blur dilutes it away.
- **What the Note 9's driver skips (measured 11-11):** a branch whose test is a slider (uniform) alone IS skipped;
  a per-pixel test round a block of texture looks is NOT (all the looks still run). So: guard optional work by
  its own slider (`if (uStone.z > 0.0)` took Overgrown from 54 to 36 ms), and replace search loops that `break`
  with a fixed, short walk (`edgeAlong` in `soften.frag` walks the field's own distance: 7 looks, was up to 19).
- The timing page can leave things out to find a cost: `&bench=1&only=a,b`, `&k=knob:value`, `&nop=1` (no
  particles), `&minus=VINE` (without that piece). Inside `adb shell "... -d '...'"` write `&` bare.
- A film curve run per colour channel bleaches colour: `final.frag` scales by the brightest channel.
- Half-float render targets are required (present on the Note 9).
- Look files are plain JSON; the web page reads them from the app's assets, so a look seen on the
  computer is the file the phone ships.

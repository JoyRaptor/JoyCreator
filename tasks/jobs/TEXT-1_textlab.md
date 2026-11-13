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
- **Owner, 2026-11-12 (after wave four).** Water "looks excellent, especially with these settings" (his sliders:
  thickness 0.012, melt 0.019, wobble 0.047, wobble size 9.287, wobble speed 2.085, chips 0.009: now the look), but
  "the drip particle emitters don't look as good as the rest of the text". Dust: he likes the idea, "pretty
  unconvincing": the dust "blows away in round clusters" and the puffs do not seem to come from the grains
  dissolving off the word. The painted backdrops (sea, bricks) do not convince him, and matter little: there will be
  video, a visualiser or a picture behind; he wants transparent as an option. He asked how a look's shimmer and
  its dissolves should be set for export, how this wires to VisLab, and what a next seven would be. Done:
  - **Water**: the bubble sprites are gone; every drop is a true shape of the same water (two more followers behind
    each leaping drop, `splashOf`).
  - **Dust** rebuilt: no soft round sprites; the front can be combed into wandering tongues by the wind
    (`frontComb`); the letter thins over a wider band that starts just ahead of the line; and THE STREAM
    (`uSandB`, `sandWind` / `sandStream` / `sandLift` / `sandEddies`): the grains that have left are still drawn,
    carried downwind. A point ahead of the line is reached only by grains from one place upwind, so it costs one
    look per pixel and is the letter's own grains, moved. Front-born particles now start from rest, gather speed,
    and leave a little before or after the line as the letter's grains do.
  - **Look book helpers (pilots of what Studio and JoyBrush would carry)**: the LIGHT helper under the picture
    (Live = follows the phone; Pinned = stays where the pad puts it, what an export uses; Sway = moves by itself
    round the pinned place); a PROGRESS bar for looks with a front (drag to hold a moment, or Plays); what is
    behind now stays as chosen from look to look, with Clear (grey squares) and Your picture (stage 10 and 11).
  - Timing: Note 9 at 1280 wide after this pass: Dust 26 ms (was 19; the stream), Water 50 ms at his settings (was 39; his crystal-chips slider adds a cell search to the shape pass: to trim), Ember Fade 16, Overgrown 36. Battery 23.7 to 23.8 C. Checked on the computer at several moments and at phone size; the light helper, progress bar, Clear and own-picture were exercised by script, not by hand on a phone.
  **My recommendations to him (his yes owed):**
  - Export never reads the sensor. Every text carries a light position; the helper has Live (try it), Pinned (the
    stored value) and Sway (a stored gentle motion, the no-thinking choice for video). Studio keyframes the pinned
    position and the progress on its timeline; JoyBrush stores them with the layer (a still uses Pinned; an animated
    export may use Sway). Progress defaults to "plays in at the clip's start / plays out at its end".
  - VisLab: a look is a skin any VisLab word part can wear; the music's bands drive a look's sliders (glow, wobble,
    light position, progress, particle rate) through the same follow-the-music mapping VisLab has; VisLab's own
    shapes can use TextLab's pieces (bolts, leaves, sand, the front). One renderer draws to a clear layer that
    VisLab, Studio and JoyBrush composite (`final.frag` `uOut = 1`, still untested).
  - Next seven proposed: fog on glass (wipes clear, blurs the video behind), shatter (rigid shards, in or out),
    cloth (the whole word waves like a flag: a warp for any look), written by hand (the stroke draws itself: the
    pen's path as a piece), stained glass (leaded panes, coloured light on the floor), peeling paint and rust (coats
    that wear through), jelly (light through the body, springy arrival).
- **Owner, 2026-11-12 (who builds what).** He is short of credit for the lead's model and has Opus 5.5 as well. His
  question: which of us should build the phone app? Lead's answer, which he acted on by asking for the looks: the
  phone engine is a careful PORT with a picture to match (same shader files, a reference engine to mirror file for
  file), so a second session builds it from `tasks/jobs/TEXT-1_PHONE_ENGINE_BRIEF.md`; the lead keeps to looks.
- **Wave five (2026-11-12): the third seven.**
  | Piece (new) | Where | Look that shows it | Note 9, 1280 wide |
  |---|---|---|---|
  | Shatter: a letter drawn as up to eight rigid shards, each a solid chunk with the look's whole finish; thrown, falling, turning, fading, then back together. Compose level, so ANY look can wear it | `glyph.frag` `uShard` / `uSeeds`; `tl-engine.js` `brokenNow` / `shards`; sliders `shatter*` | Shatter (Dark Steel's material) | 35.1 ms whole, 36.7 ms mid-break |
  | The pen's path: a tube letter's picture says how far along its writing each point is (`1 + arc`, so neon still reads "bare"); the field carries it per letter (`uArc`) | `tl-tubes.js`, `glyph.frag`, `PEN` in `shade.frag` | Handwritten (ink + pen), Fogged Glass (the finger writes it) | 8 ms |
  | Fog: a steamed pane over the whole picture, soft behind the mist (the backdrop's blurred copies), sharp where wiped, tiny drops, a bead along the wiped edge; runs are the look's own drips | `FOG` in `shade.frag`; the engine blurs the backdrop only for these looks | Fogged Glass | 15 ms |
  | Flag: the word printed on a hemmed cloth flying from its left edge (a warp with fold light) | `FLAG` in `shade.frag` | Banner | 5.2 ms |
  | Stained glass: leaded panes, each its own shade, lit from behind, shifting with tilt; their colours on the floor | `STAINED` in `shade.frag` | Stained Glass | 34.5 ms |
  | Rust: a coat of paint failing in patches (edges first) over rusting steel, with runs | `RUST` in `shade.frag` | Rusted (Dark Steel's shape) | 36.8 ms |
  | Jelly: light goes into the body (thin rims glow, deep middle, light out the far side, bends what is behind); `spring` arrival | `JELLY` in `shade.frag`; `EASE.spring` | Jelly | 18.4 ms |
  The look book's progress bar also drives a break ("Broken"). Checked: all 35 looks draw with no shader errors on
  the computer; each new one looked at on the computer at one to three moments (Shatter whole, mid-break and gone;
  the pen mid-word; Jelly mid-bounce); the seven ran with no errors on the Note 9's GPU. Battery stayed at 21.4 C over two short runs. Not checked: the
  motion of Banner, Fogged Glass and Stained Glass over time beyond two stills; anything by hand on a phone; the
  owner's verdict. Known weak spots: Stained Glass's light on the floor still reads as a mosaic more than as light;
  Banner has no pole and no shadow; the handwriting is the neon letters' single-stroke capitals, not a script hand.
- **Owner, 2026-11-12 (wave five):** "They all look good, except Rust needs a lot of improvement", with four
  photographs of rusted steel letters. Rebuilt from them (`RUST` in `shade.frag`, new sliders `rustPlates`,
  `rustUnder`, `rustRelief`, `rustSteel`, `rustUnderCol`; particle shape 8 = rivet): the letter is a tray (a rusted
  raised frame round a lower face) built of plates each painted its own colour, with seams; the paint is a coat
  with a thickness that lets go in sharp flakes over an older coat, blisters, is crazed, chalked and dirty; the
  steel beneath is rough, mostly deep red-brown with orange and ochre spots, black pits and dark iron scale; rust
  runs down the paint; rivets hold the plates. Checked up close against the photographs on the computer; Note 9
  36 ms (as before). General wear (grain, mottle, grime, scratches) is now skipped when its slider is zero, which
  took Dark Steel from 30 to 24 ms. His verdict on the new Rusted is owed.
- **Owner, 2026-11-12, on the rebuilt Rusted:** three surfaces need three kinds of light: "bare metal ... high
  specular and anisotropic", rust "very, very matte, porous", paint "medium specular". And rivets need "logical
  rules ... orderly in a line ... symmetric", either on the raised outline or in the recess. Done:
  - `RUST` now has a band of bright bare steel between the old paint and the rust (`rustMetal`), brushed one way
    per plate: fine grooves, a soft bright streak ACROSS the grooves that slides with the tilt, a slightly blurred
    reflection. Paint: a satin sheen (`rustPaintShine`). Rust: no shine, its pores hold shadow. (`rustMetalShine`)
  - Rivets by rule, `TL.rivetsOf` in `tl-glyphs.js`: the line a fixed distance in from the outline (`rivetInset`,
    by default the middle of the raised frame) is traced from the letter's distance picture; a rivet at every
    corner, then evenly spaced between corners, never further apart than `rivetGap`; a loop without corners (an O)
    starts at its top. They are an ORDERED list in the emitter's rows 192 to 255 and drawn by particle `from` 4.
    Changing the frame, weight, stroke or rivet sliders re-places them.
  - Checked on the computer, up close. Note 9: 42 to 43 ms (it was 36 before the shiny steel), but the phone was
    at 30.8 C against about 23 C in earlier runs, so heat slowdown may be part of it; not re-measured cool.
  - A slip found on the way: a slider group that fills only three of a four-number setting is silently refused
    (`Program.set` warns in the console and sets nothing), so the bare steel, paint sheen and steel shine all read
    as zero until the setting was declared with three numbers.
- **Honest limits today:** the fire is good stylised fire, not yet the photograph the owner showed;
  water and the other drafts are below the bar; only the app's own fonts are used (Archivo, Plex,
  Ubuntu, Doto), so there is no script or true display face yet.

## What is left

**Audit, 11-12 evening (phone-engine session), checked against the code on `joy-creator`.** Everything below is
real work, not done; the itemised history follows.
- **The TextLab room on the phone** is the biggest gap: today's TextLab screen is a plain first screen (picture, chips,
  words, Save). Owed: every knob in `parts.json` by its tab (37 tabs; icons proposed 11-12 for the owner's approval),
  live tilt light with Still / Sway, the progress helper, a background picker, your own font, rename / delete / revert
  a saved look, and a door from the lobby (today only the Library opens it).
- **Studio's Look row:** no way to change a look's knobs for one title (the data is there: `TitleLook.knobs`); no
  "Open in TextLab" to edit a title's look; the title's font, colour, bold etc. are ignored while it wears a look
  (the look decides); Joybot cannot set a look yet; no help topics; the hand-tilt direction is unconfirmed; the
  progress row and corner pin are not phone-tried; VisLab showing a looked title is not phone-tried.
- **Previews:** PNG sheets are ~0.9 MB each (WebP would be ~1/5); Sprite Lab's own sheets do not use the moving name.
- **Packs (PACK-1):** only title looks are read from packs; opening a pack from the Library or a share, removing,
  signing and the shop are not built.
- **Wider:** VisLab and JoyBrush using looks; the emitter for VisLab; hand-drawn ink as letters; abilities as
  installable pieces (with the lead); an FX'd title never bends in export (plain titles too).
- [ ] **Owner's verdict** on the five heroes (and which drafts he wants raised next).
- [ ] Fonts: a short list of open-licence display faces to bundle (needs his yes to download).
- [ ] Fire toward the photograph (finer filaments, a real fluid step if the Note 9 allows); water with splashes.
- [~] **Phone engine** (brief for the builder: `tasks/jobs/TEXT-1_PHONE_ENGINE_BRIEF.md`). **Built 11-12 by the
      phone-engine session (Claude Opus 5.5, branch `claude/textlab-phone-20261112`)**, brief steps 1 to 3 in one go:
      package `cc.joycreator.textlab` in `app/src/main/java` (Java, like VisLab, so the app module needs no Kotlin
      plugin): `TextLabRenderer` (every pass of `frame()`, `still()`, fire, particles, shatter, photo stage),
      `TlGlyphs` (letters, distance transform, emitter points), `TlTubes`, `TlGl` / `TlProgram` (typed uniforms by
      name, on VisLab's `GlProgram` / `GlTarget` / `ShaderLib`, made public for it), `TlFonts`; debug screen
      `TextLabDebugActivity` (adb: `--es look <id> --ef t 3 --ei w 1280 --ei h 720`; saves the still to the app's
      files folder `textlab/<look>_t3.png`, then plays live with the frame time on screen; tap holds / plays).
      **Proof, Note 9, against the look book in Chrome on the same Note 9** (`&t=3&w=1280&h=720`, same words; pictures
      in `tasks/screens/textlab-phone/`, compare with `tools/textlab-web/compare.py`, phone side `phone-stills.sh`):
      neon-bar identical (worst 0 of 255); slime, dust, ink-bleed mean 0.01 (worst 44, 126, 13 on a few pixels);
      dark-steel mean 0.62 and blaze 0.17 (worst 133, 217 on single pixels; the same by eye, `side-by-side.png`).
      Cause, measured: those two use Archivo at weight 800, and Chrome on Android snaps a big letter's outline to
      the 64 px grid it builds it at (a T's top at 286.0 px where the font says 285.8; slight hinting), so a long
      straight edge sits a fifth of a pixel apart and the brushed bevel detail along it speckles. No Android call
      gives that snapped outline (drawing the letter as a path was tried: same pixels); the phone keeps the true
      outline. Not the tilt sensor: a re-capture with the light pinned (`&tx=0&ty=0`) gave byte-identical stills
      (Chrome sends no tilt to a plain http page). Every picture is the same size as the look book's.
      Live frame time, dark-steel at 1280 x 686 (wall clock with glFinish, debug build): about 46 to 55 ms.
      **Two traps found:** (1) the browser's letter ink box is Skia's box at 64 px rounded out and scaled
      (multiples of 6.5 at 416 px); the phone copies that rule or every cell is cut a pixel or two off;
      Android also rounds advances to whole pixels unless measured large. (2) The look book on the COMPUTER is not a
      reference for font looks: Windows draws letters with DirectWrite, so edge points (where particles and glints
      are born) differ; compare with the look book on the phone. **Name clash for the lead:** `uArc` is both the
      lightning knob (vec4, `shade.frag`) and a tube's share of its letter (vec2, `glyph.frag`); `setAll` tries the
      knob on the glyph program every letter and is refused (both engines; harmless, the right value follows).
      Left: brief step 4 (`uOut = 1`), GPU time per pass, then Studio
      (ask the lead first).
- [x] **Look previews (owner, 11-12):** pick a look by its picture, not its name, and browse saved looks in the
      Library moving, like pictures. **Built and agent-checked on the Note 9, 11-12** (branch
      `claude/textlab-phone-20261112`; pictures in `tasks/screens/textlab-phone/ui/`): the TextLab screen has the
      chips (cyan ring on the chosen one, which plays), your words and Save; Save wrote `HOT.text.json` +
      `HOT.5x4.sprite.png` (880 KB) to `Supplies/Looks/` in 3 s; the Library lists HOT once, and its card plays (four
      screenshots 0.25 s apart, a fifth of the card changes between each); tapping a saved look opens it in the
      TextLab screen with its words; a look saved without its picture got one from the Library by itself. A chip
      picture costs 1.5 to 4 s to make, once (35 looks: about 2 minutes, in the background, the first time; remade
      only when the shader, knob or tube files change). Code: `LookPreview`, `TextLabPreviews`, `LookChipRow`,
      `com.fadcam.ui.library.SheetDrawable`, `FolderReader.linkSheets`, `LibraryThumbs.bindSheet`,
      `joyfolder.SheetName` (+ `.text.json` in `FileKind.LOOK`; tests in `tools/joyfolder-tests`). Not done: the
      owner has not tried it; the sheet is a PNG of about 0.9 MB (a WebP would be about a fifth); a hand-edited
      look is remade only when the Library is next opened. Plan as built:
      - A saved look is two files side by side in `Supplies/Looks/` (the existing Looks kind; `.text.json` added
        to its endings): `<Name>.text.json` and its moving preview `<Name>.5x4.sprite.png`: a sprite sheet, 5
        across and 4 down, 20 frames at 10 a second (2 s), each 256 x 144, drawn by the same renderer from 3 s in.
        The grid is in the name, so anything reading the file knows how to cut it.
      - Name: the words written (cleaned), the user can retype it; a clash gets "2", "3" (the folder's free-name rule).
      - Library: any `*.<C>x<R>.sprite.png` plays as a loop (only cards on screen play); a look's card shows its
        sheet, and a sheet beside its own look is not listed twice. A preview older than its look is remade.
      - Chips: the TextLab screen gets a row of look chips (each look's own sheet, made once in the app's cache):
        still until chosen, the chosen one plays. Studio's carousel will reuse the same chip row.
      - Not here yet: packs, installable abilities (a separate job, with the lead).
- [x] **Studio: built 11-12 (phone-engine session), agent-checked on the Note 9, not owner-tried.** The text drawer
      has a LOOK row (picture chips, "Plain" first; built-in packs + installed packs + your own looks), then Light
      (Still with a small pad, Sway, Record tilt) and, for burn / blow-away / grow / shatter looks, Plays (By itself /
      Pinned + a slider at the playhead). A title keeps its look in its own block (`TitleLook`, saved as
      `"textLook"`: a whole copy of the look, knobs by name, light, its own keys), never in the title's keyframes;
      a project with a look is stamped v14 (`FaditorProject.SCHEMA_VERSION`), so an older build refuses it instead
      of dropping the look. Preview: `TextBoxView.drawLook` asks `StudioTextLab` (its own GL thread, one renderer per
      title, newest request wins) and draws the see-through picture at the title's letter size; the box resizes to
      the look. Export: `CompositeExportOverlay.drawLookItem` (both the GPU and the canvas routes go through
      `drawTextItem`; a look makes the title clocked). Light takes: `LightTake` (rotation sensor only during a take;
      the tilt at the start is the zero; thinned and curve-fitted with the puppet's `simplifyRange` and
      `MeshEasingFit`; one undo step). Arming puts a see-through sheet over the picture (under the drawer): hold
      anywhere on the picture, not only the letters (easier one-handed; does not fight the title's own drag).
      Split and trim shift the look's keys. **Proof on the Note 9 (test project "Untitled audio 4"):** HELLO in Dark
      Steel drawn live in the preview; exported `textlab-hello-check.mp4` (5 s, 17 s to export) carries the look in
      every frame checked; a 3 s hold recorded a take (one undo step, saved as v14 with lightX / lightY keys, thinned
      to 2 each because the phone lay still); closing the drawer disarms. Pictures:
      `tasks/screens/textlab-phone/ui/studio-*.png`, `export-hello-frames.png`.
      **Not checked yet:** a real tilt by hand (the sensor's left / right and up / down signs are a guess:
      `LightTake.onSensorChanged`; the owner's first try settles it), the progress row on the phone; Joybot setting a look.
      **Done 11-12 (later the same day):** a title with FX exports its look with the effect on it (`TextFxGlEffect`;
      Note 9: Invert on Dark Steel, `export-look-fx-invert.png`; the FX route has never applied a bend, plain titles
      included, so an FX'd title exports unbent); a bend and a corner pin apply to the look in the preview
      (`CornerPinTextView` via `TextBoxView.drawLookInto`) and the export (`CompositeExportOverlay.drawLookItem`)
      (Note 9: an arched Dark Steel in the preview and in `textlab-bend-check.mp4`, `export-look-bent.png`; corner pin
      shares the code but was not tried on the phone); VisLab shows a looked title moving (`StudioPictures.lookTitle`,
      its own renderer) (built, not tried on the phone: the test taps could not open a visualizer's drawer to point a
      picture layer at the title).
      Original plan below.
- [x] **Studio: a title wears a look, live (owner, 11-12).** A titled look in Studio is NOT a picture pasted in:
      the words, the look and its knobs stay editable, and the engine draws each frame at its own moment (preview
      at a lighter size, export at full size) as the see-through picture (`output = CLEAR`) Studio lays over the
      video. **The light in Studio is recorded, never live:** the owner's "record live". **The owner's pick (11-12):
      arm, then hold the title** (over arm-then-Play). It is the same pattern as the puppet's existing "Record on touch"
      (`PuppetDrawerTabs`: arm it, then touching records; a take is a dense run of keys drawn as one bar on the tape,
      `PuppetTapeMarks`; "Detail of recorded moves" thins it; `MeshEasingFit` turns the thinned keys into curves,
      overshoots included). So: arm "Record tilt"; hold a thumb on the title; the video plays from the playhead and
      the phone's tilt at that instant is the zero; the tilt is recorded as the light's keys until the thumb lifts;
      one take = one undo step; recording over a stretch again replaces its keys; the keys then edit, thin and
      cut like any. Export reads only the keys, never the sensor.
      **The lead's answers (11-12), binding for the Studio step:** start it (after the rivets / `uPenArc` port, done
      11-12, `1a15b7caa`). A look on a title is opaque data plus knob overrides keyed by name; unknown keys are
      skipped, never an error (the lead keeps adding knobs, slots and pieces; existing names change only with a
      note). Studio offers index.json `looks` and the person's own looks in Supplies, never drafts. Beside "Record
      tilt", two no-thinking light choices, both stored as keys: **Still** (one key, set on a small pad: the look
      book's Pinned) and **Sway** (generated keys round that point: x + 0.4 sin(1.1 t), y + 0.28 sin(0.8 t + 1) in
      tilt units; the look book's default for video). Looks with a FRONT (Ember Fade, Dust, Overgrown) or SHATTER
      play in from the clip's start by default (frontAuto / shatterAuto as the look sets them); a keyframe can pin
      the progress (frontGone with frontAuto 0, or shatter with shatterAuto 0, as the look book's progress bar
      does), so export is exact. Look values (not shapes) will still change with the owner's verdicts.
- [x] Output for a compositor (phone-engine session, 11-12). `uOut = 1` (linear light, premultiplied) tested as
      written; and **`uOut = 2` added to `final.frag`** (the lead's file; the look book's own picture, `uOut 0`, is
      unchanged): the words alone, see-through, with the look's own film curve, colour and contrast, sRGB,
      premultiplied, 8 bits: what Studio lays over video as a picture. Glow is light, not paint: its cover is its
      own brightness. No dark corners or grain (they belong to a whole frame). Proof (`ClearCheck`, and the same
      sums in the look book on the computer): laid over black, `uOut 2`, and `uOut 1` finished by
      `final.frag`'s sums, equal the screen picture over a black ground to within 1 of 255 (rounding) on dark-steel,
      neon-bar, blaze, slime, dust, ink-bleed; no pixel has more colour than cover. The renderer:
      `TextLabRenderer.output` = `FOR_SCREEN` / `CLEAR` / `LINEAR_CLEAR`; `readBitmap()` gives a see-through
      Bitmap for `CLEAR`, `readLinear()` floats for `LINEAR_CLEAR`; both skip FXAA (it would fill in the
      see-through). **On the Note 9 too** (`tools/textlab-web/phone-clearcheck.sh`, the app's `ClearCheck`): the same six looks give the same result, within 1 of 255, no colour above cover. Limits, by nature: over a
      bright video the words' film curve and their light spilling onto the background cannot be the same as the
      look book's (that picture grades its own stage); paper looks (ink-bleed) bring their paper.
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
- A slider group must fill every number of the shader setting it drives (a `vec4` needs four sliders, or
  declare it `vec3`). One short is refused whole, silently except for a console warning.

- Look files are plain JSON; the web page reads them from the app's assets, so a look seen on the
  computer is the file the phone ships.

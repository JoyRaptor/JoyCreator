# OIL-KNIFE: a palette knife that handles like a real one, and oil that pulls where strokes cross

**Milestone:** M1 (JoyBrush base brushes)  ·  **State:** 🔨 soft passage landed; Knife 3 first build on the branch  ·  **Owner:** Claude Fable 5.2 "atlas solid" session  ·  **Checked:** 2026-11-11 against `5ab0d27fe`

## Why it matters (for the user)
The owner (2026-11-11, on a Note 9 film of four oil brushes crossing, with Bob Ross reference photos): where a stroke
crosses wet paint it leaves hard straight lines "like sticks, no glop with thickness"; real paint would be softer, or if
thick it would be pulled along. The knife is "unwieldy" and "too razor sharp flat": he could not make Bob Ross evergreen
touches (the knife's edge, short broken wavy horizontal marks) or build thick paint and carve into it. Some oil strokes
lack size variance and look "uniform and rake-like". "Our oils look decent."

## Done when
- [ ] A stroke crossing thick wet paint drags that paint along its travel and squeezes it up at its sides; no straight
      hard-edged strip. Owner's eye on the Note 9.
- [ ] A new **Palette knife 3** beside knives 1 and 2 (kept to compare): one tool that lays, breaks, cuts and carves by how
      it is held, per the table below. The owner makes a Bob Ross tree and a snow break with it.
- [ ] Hair brushes: no regular rake; visible size variance with pressure and tilt.
- [ ] Lab and app agree (golden, uniform parity, footprint bounds); all 22 media brushes re-checked against a same-hour
      baseline; STATE.md row.

## Where it stands
Found in code 11-11 (`shaders/media/jb_paste.glsl`, `jb_paste_dab.frag`, `core/media/Paste.kt`): Knife 2 and the Scraper are
a flat rigid plate. Everything above the plate comes off onto it at once (`rPick`), its own paint fills up to it, and the
plate's sides are a one-pixel ramp (`jb_bladeCover`). Nothing moves sideways and nothing is dragged, so a crossing is a
planed strip: the "stick". The plate's outline is fixed (`jb_bladeHalf`: a rounded point widening to one width), tilt only
changes how much edge is in play, pressure only its height. Broken coverage over the canvas tooth already exists
(`jb_bladeReach`, LOAD mode) but the paint on the blade is even along the edge, so marks break evenly, not in clumps.

Research 11-11 (read by a helper agent; mechanisms, not marketing):
- IMPaSTo (Baxter, Wendt, Lin 2004), https://math.vu.nl/~eliens/demo/local/documents/impasto.htm : paint under the tool
  moves at half the tool's speed plus a push away from where the tool presses deepest; that push squeezes ridges out at
  the sides. Transfer is gated by how far the tool sinks into the paint, so a light touch rides on top.
- Stuyck et al. 2017 (iPad, GLES 3.0), https://tuurstuyck.github.io/assets/oilpaint_low_res.pdf : thick paint holds its
  shape (movement under a threshold is stopped); layers shear, they do not mix at once.
- RealBrush (Lu et al. 2013), https://gfx.cs.princeton.edu/pubs/Lu_2013_RPW/Lu_2013_RPW.pdf : at a crossing the old colour
  trails downstream into the new stroke in streaks.
- Corel Painter Thick Paint (help pages): knife "corner tilt" blends the contact from corner to flat; plough strength and
  radius; "clumpiness" = random bristle radius, length and load. ArtRage: a knife carries a set load and, thin, "sticks to
  only the high points of the canvas". An Apple Pencil oil app (Soyatec): travel direction against the blade decides lay
  or scrape; pressure thins the layer, it does not change the knife's size.
- Not used: a pickup map at canvas resolution (Chu et al. 2010 is patented, US 9,030,464; the guard at the top of
  `jb_paste.glsl` already forbids it). Rebelle, Fresco and Verve publish controls, not mechanisms.

## The proposed feel of Palette knife 3 (waits for the owner's yes)
| What the hand does | What the knife does |
|---|---|
| **Tilt** (how flat the pen lies) | Which part touches: upright = the tip's curved corner (a small touch); half way = the straight edge (a line); laid flat = the whole face (a wide slab). This is the size variance. |
| **Lean direction** | Where the edge points, as on Knife 2. |
| **Pressure** | How hard it bears down: light = paint catches only the high spots and breaks (snow, tree touches); firm = fills the weave; hard = digs into wet paint and ploughs it. Pressure does not change the knife's size. |
| **Travel with the edge trailing** (the face pulled over the paint) | Lays and spreads its roll of paint. |
| **Travel with the edge leading** (the edge pushed into the paint) | Scrapes and carves; paint piles up ahead and curls off at the sides. |
| **Travel along the edge** | A thin cut, or a thin line of paint. |
| **The roll of paint** | Lumpy along the edge and running out as it is used, so touches are broken and uneven by themselves. Reloads when the pen lifts. |

## Patents: what the two claims cover (an engineering reading 11-11, NOT legal advice; a patent lawyer before launch)
- **US 9,030,464 (Microsoft, "Simulating painting", to 2031):** every independent claim needs a 2D paint pickup map on the tool
  whose resolution matches the canvas paint map (and a 3D tool model). We have no pickup map. Its "streak map" is not claimed.
- **US 8,462,173 (Adobe, pickup and reservoir model, to 2031):** needs TWO paint stores on the brush (pickup and reservoir)
  and a rule that lays from the pickup store alone once it reaches the flow rate. Our brush cell holds ONE mixed paint and
  lays a continuous blend: keep it so. Never add a "recently picked up" store beside the load, or a switch between stores.
  Its dependent claims describe a bristle-by-position buffer layout like our lanes by depth, which is why the rule matters.
- **Free as published before both:** moving the CANVAS paint under the tool (IMPaSTo 2004), which is what the soft passage does.
- **Not read:** the Adobe siblings US 8,599,213 (wetness, drying, mixing settings) and US 8,654,143 (non-uniform brush loading).
  Read them before touching Covers / Carries or the loading tray.
- Claim PDFs: https://patentimages.storage.googleapis.com/25/15/3e/96a096e8bd187e/US9030464.pdf ,
  https://patentimages.storage.googleapis.com/b2/b9/74/ad2c1ad1741f59/US8462173.pdf

## 2026-11-11: built so far
- **Soft passage** (`PasteBrush.soft`, `u_bladeSoft`): rounded underside, sides fading inward, thick paint ridden down over a
  stretch, wet paint dragged at half the blade's speed. The owner chose strength 1 from a Note 9 film of 0 / 0.6 / 1. It is
  the default on Palette knife 2 and the Scraper; a brush's settings have **Passage: Soft / Hard**. On `joy-creator`
  (6bcc5a9e5), unit tests pass, lab table and golden updated. The lab's own picture of it has not been compared.
- **Palette knife 3** (`PasteBrush.hand`, `rollLump`; `u_bladeLay`, `u_bladeLump`): first build on the branch, filmed on the
  Note 9 with new bench strokes (`knifeSpread`, `knifeSnow`, `knifeCarve`, `tree0..9`). Not owner-tried, not landed.

## What is left
- [ ] 1. Crossings (blades first, then hair brushes): the plate gets a rounded underside across its width; the paint it
      displaces goes to its flanks and behind it, conserved; its side ramp widens with the wet thickness under it. New
      terms default to today's behaviour so the lab golden stays exact until the lab is ported.
- [ ] 2. Palette knife 3: outline with a straight edge, a facet and a curved tip; contact from tilt; pressure as bearing;
      lay or scrape by travel against the blade's normal; a lumpy, depleting roll. Bench strokes with tilt and lean.
- [ ] 3. Hair brushes: per-run variance in length, load and width that drifts along the stroke (clumps), instead of a
      fixed streak profile; width from how many hairs touch.
- [ ] 4. Lab port (`lab/media/js/paste.js`, `oils.js`), golden, `MediaUniformsParityTest`, `PasteFootprint` bounds and tests.
- [ ] 5. Note 9 films of each against the reference photos; the owner's try; STATE row; help text.

## Specs, handoffs and evidence
- The owner's words: this card. His reference photos are his media and are not in git.
- Note 9 film he judged: runs `oil100`, `oil31` (session scratch; the judged frame was sent to him).
- Earlier knife rulings: [MEDIA-SPEED](MEDIA-SPEED_large_brushes.md) (turns, blade easing); the Knife v3 notes in `Paste.kt`.

## Files and traps
- `joybrush/shaders/media/jb_paste.glsl`, `jb_paste_dab.frag`, `jb_paste_brush.frag`; `core/media/Paste.kt`, `PasteFootprint.kt`
  (its bounds quote shader lines: a wider footprint must widen them or strokes clip in squares); `androidkit/.../MediaUniforms.kt`.
- The lab mirrors all of it and tests compare number for number: port in the same change, or gate new terms at 0.
- Patent guards at the top of `jb_paste.glsl` (one paint per brush cell, a coarse brush grid, no pickup-first rule): keep them.
- The JoyBrush Lead and Codex hold other oil items (Covers / Carries): say so on the BOARD before changing shared shaders.

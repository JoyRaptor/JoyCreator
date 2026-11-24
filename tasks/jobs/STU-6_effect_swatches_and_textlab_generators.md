# STU-6: Effect swatches, and TextLab's pieces as Studio effects

**Milestone:** M1  ·  **State:** 🔨 step 1 built 2026-11-23 (cloud green; not yet seen on the Note 9)  ·  **Owner:** the TextLab phone-engine / Studio session (Opus)  ·  **Checked:** 2026-11-12 against `402899218`+

## Why it matters (for the user)
The owner, 2026-11-12: Studio's effect picker is "all just a text list"; every effect, Clouds included, should
show what it looks like, and each card in the stack should carry a small picture that "live updates" as its own
sliders move. And the pieces TextLab's looks are built from (tiles, cells, halftone, brushed metal, chipped
paint, burn-away...) should become Studio effects, so he can build textures the way he did in 3ds Max: patterns
plus displacement plus masks. "You already did a lot of the hard work to make it for one place."

## Done when
- [ ] The effect picker is a grid of pictures (every existing effect, not only the new ones), each with its name.
- [ ] Every card in an effect stack shows a small live swatch of that card alone, updating as its sliders move.
- [ ] The new Generate cards and the new over-video effects below exist, keyable, previewed live and identical in
      export, each phone-proven on the Note 9 with a before/after screenshot in `tasks/screens/` and its cost.
- [ ] STATE.md rows, roadmap row and this card updated; pushed, local equal to remote.

## Where it stands (read before starting)
- Studio's effects: `studiokit/src/main/java/com/fadcam/ui/faditor/fx/`. `FxRegistry` defines them;
  `FxEffectDef` (Family BLUR / COLOR / GENERATE / DISTORT; Capability POINTWISE / GENERATOR / SAMPLER / UV_REMAP /
  FEEDBACK), `FxParam`, `FxCompiler` (bodies to GLSL ES 1.00 for export, the same source previewed live through
  `FxPreviewTextureView`), `FxBlurbs` (each effect's one-line description). The panel: `app/.../faditor/tools/FxPanel.java`.
- Generate today has only Solid Color, Gradient and Noise / Clouds. No tiles, no cells.
- **The model to copy:** `FxVisLabDefs.java` / `FxVisLabDefs2.java` brought VisLab's finish pass into the stack.
  Read its header first: coordinates (uv 0..1, y UP, height -1..+1 with `FX_ASPECT`), time (`FX_TIME` = timeline
  seconds, so preview equals export), sin-free hashes for anything fed by time, helpers built as text snippets
  because a body is a statement block and cannot declare a function, capability choices, "ids are wire values:
  add, never rename". Do the same in a new `FxTextLabDefs.java` (split into two files if it grows past ~400 lines).
- **The look previews you already built** (`cc.joycreator.textlab.LookPreview`, `TextLabPreviews`, the chip rows)
  are the pattern for the picker grid: rendered once by the real renderer, cached, invalidated by a version.

## What is left (in this order; stop and show the owner after 1, after 2, after 3)

### 1. Swatches for every effect (first: it helps all of Studio's existing effects)
- **Picker:** replace the text list with a grid of tiles: a still of the effect at its default settings, its
  name under it. Generators are shown on their own over a neutral grey; colour, blur and distort effects over
  one fixed sample frame (a small bundled photo with faces, sky and edges, so blur and colour read). Rendered
  once with the real compiler (`FxGlSource`) on an offscreen GL context, cached as PNGs keyed by effect id + a
  hash of its body + its defaults, so an edited effect re-renders by itself. Keep any search/filter that exists.
- **Card:** a small swatch in each card's header (visible when the card is collapsed too) showing THAT card alone
  (a one-card stack) at its current values and the playhead's time, over the current frame (or the sample frame
  if there is none). Re-render when one of its sliders moves, throttled (about 10 a second), and idle otherwise.
  Animated effects may show their frame at the playhead; no looping animation needed in the card.
- Owner rules that apply: dp values as drawn on the Note 9 (never inflated), greys fade and colour guides (old
  black, zinc greys, flat pills), a chosen item is solid cyan, every button gets a hover label, one press is one
  undo step, small new classes (FaditorEditorActivity is 45,000+ lines: add nothing to it you can put elsewhere).

### 2. New Generate cards (pattern makers, for masks and blend modes)
Each is a GENERATOR (fuses, costs no pass) unless noted. Sources are in `app/src/main/assets/textlab/shaders/`;
read the TextLab look's description in `looks/*.text.json` for what it is meant to look like.
| Card | TextLab source | Settings to offer |
|---|---|---|
| **Tiles** | `shade.frag`, `#ifdef RUST`: the plates (`pg = q * uRustB.x`, each course offset by a hash of its row, `seam` = distance to the plate edge) | tiles across, aspect (bricks vs squares), row offset, gap width, gap softness, each tile's own shade (random brightness / colour), output: tiles / gaps only / tile id as grey |
| **Cells** | `common.glsl` `voronoi()` (3 x 3 search: nearest, second nearest, cell id); used by ice fissures (`wallD = cr.y - cr.x`), stained-glass panes, ember cracks | cells across, randomness, output: edges (cracks) / filled cells (random grey) / soft blobs (nearest distance), edge width, seed |
| **Halftone / Stripes / Checks** | `shade.frag` `pattern()` (dots that shrink toward the top; stripes; checks) | kind, cells across, angle, dot size from the picture's brightness (POINTWISE when it reads `src`), colours |
| **Brushed metal** | `shade.frag` RUST lighting block: `groove` (fine lines along the brush) and the light streak ACROSS the grooves | grooves direction, fineness, streak position (keyable: this replaces the phone tilt, which Studio never reads), streak width, brightness |
| **Chipped wear** | `shade.frag` RUST albedo block: `worn` = fbm + a cell offset (`chipV`), thresholds `thrTop` / `thrUnder`, the lifted edge (`rustLip`) | how much has worn, chip size, edge lift, output: mask (for wearing through another layer) / layered colours (coat, under-coat, bare, rust) |
| **Caustics** | `shade.frag` REFRACT: `voronoi(q * uCaustic.y + time drift + ...)`, `pow(sat(1 - (v.y - v.x) * 1.6), 4)` | scale, speed, sharpness, colour |
| **Glitter** | `shade.frag` SAND: a few cells flash (`step(0.97, sandB)` times a sine of their own phase) | density, size, flash speed, colour |

### 3. Whole effects that work over video
| Effect | TextLab source | Capability | Notes |
|---|---|---|---|
| **Burn away** | `common.glsl` `frontBase` / `frontAt` (the ragged front, its direction and comb) + `shade.frag` `#ifdef BURN` (char ahead of the line, a hair of live ember, nothing behind) | POINTWISE (alpha) | progress 0..1 keyable; direction; raggedness; glow. Could later also be offered as a transition. |
| **Stained glass** | `shade.frag` `#ifdef STAINED` (panes per em, lead width, light through glass) | SAMPLER | each pane takes the video's colour at its cell centre: the cell helper must also return the nearest seed's position |
| **LED board** | `shade.frag` `#ifdef CELLS` (bulb shape, socket, glow, round/square) | SAMPLER | each bulb samples the video at its centre |
| **Flag wave** | `shade.frag` `#ifdef FLAG` (two-pass warp, fold light from the slope) | SAMPLER | the warp alone could be UV_REMAP, but the fold shading needs the slope, so one SAMPLER |
| **Steamed glass** | `shade.frag` `#ifdef FOG` (soft world behind mist, tiny drops as little windows, a bead along a wiped edge) | SAMPLER | the blur needs neighbour reads; where it is wiped clear comes from the card's own masks |

Not portable yet (they grow from a letter's shape): fur, drips, rivets, bevels. Studio masks are shapes of the
same kind (`MaskSdf`), so "drips off a mask edge" is a later job; note it, do not build it now.

## Specs, handoffs and evidence
- The owner's words: this card, "Why it matters". The TextLab looks these come from: the TextLab look book
  (artifact "TextLab Look Book"), `tasks/jobs/TEXT-1_textlab.md`.
- Phone timing lessons: memory note "Note 9 driver" in the TEXT-1 card's Files and traps: a branch on a slider
  alone is skipped, a per-pixel branch round reads is not; loops need constant bounds anyway in GLSL ES 1.00.

## Files and traps
- Bodies are GLSL ES 1.00 statement blocks: no function declarations (helpers as text snippets, as in
  `FxVisLabDefs`, or a shared helper in `FxCompiler`'s prelude next to `fxNoise`), constant loop bounds, read the
  picture only through `FX_SAMPLE`, parameters through `FX_P(name)`.
- `fxHash` in the prelude uses `sin`: fine for positions, NOT for anything fed by `FX_TIME` (use VisLab's
  sin-free hash, as `FxVisLabDefs` does).
- TextLab works in em with y up and reads its own noise texture (`tnoise`); Studio has no noise texture. Use
  value noise (`fxNoise`, or the sin-free `vnoise` snippet) and keep octave counts low: time each card on the Note 9.
- The phone tilt is never read in Studio (owner ruling): anything TextLab drives with tilt becomes a keyable slider.
- Ids are wire values. Add, never rename. Every new effect needs an `FxBlurbs` line and a JVM harness compile test
  (`tools/jvm-harness/FxCompilerTest.java`).
- The phone: `tools/phone.ps1 claim/release`; never `adb shell monkey`; never uninstall or clear data; Note 9 is
  the sandbox; log battery temperature while timing.

## Progress 2026-11-23 (Sonnet lane)
Step 1 built, cloud build green (run 45300940592), phone look owed (the Avatar slice-1 lane held the Note 9 lock):
- `tools/FxSwatchRenderer.java`: offscreen EGL pbuffer, the real `FxGlSource.fragment` + `FxUniforms`, one card alone at full opacity, no mask/key; returns null (plain tile) on Echo Trails or any GL failure. In-memory cache keyed by effect + values. Generators over flat grey, others over a drawn sample frame (sky, sun, hills, figure, edges; not a bundled photo).
- `tools/FxSwatchViews.java`: picker tile and the card-header swatch (28dp, refreshed at most ~10/s on any value change via a wrapping Host), one worker thread.
- `FxPanel`: picker chips became 3-across picture tiles (unavailable ones faded, still tappable); each card header carries its swatch, also when collapsed.
Not done: disk PNG cache (memory only), bundled sample photo, Note 9 timings, steps 2 and 3.

## Progress 2026-11-23, later (steps 2 and 3 built)
- Step 2: `fx/FxTextLabDefs.java`: Tiles, Cells, Halftone/Stripes/Checks, Brushed Metal (streak position is a keyable slider, replacing tilt), Chipped Wear (mask or layered colours), Caustics, Glitter, all GENERATOR cards.
- Step 3: `fx/FxTextLabDefs2.java`: Burn Away (POINTWISE, keyable progress), Stained Glass, LED Board, Flag Wave, Steamed Glass (SAMPLER; wipe the fog with the card's own masks).
- They are TextLab's ideas re-expressed in Studio's conventions, not line-for-line copies of `shade.frag`, so they will not look identical to the TextLab looks yet. `FxBlurbs` lines added, registry now 46 effects, the fx JVM harness is green (registry self-check, every effect emits GLSL and AGSL, every effect has a picker sentence).
- NOT proven: the GLSL has not been compiled by a phone GPU (no validator on the PC), no timing, no before/after screenshots, no export-versus-preview check. The Note 9 pass (swatches and all twelve new cards, timed) is the open gate. Fur, drips, rivets, bevels stay out (they grow from a letter's shape).

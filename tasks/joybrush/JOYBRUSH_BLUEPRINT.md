# JOY BRUSH — the blueprint (v1.1, 2026-09-28)

**Status:** design direction from the Joy Paint design lead, built on seven research reports
(`tasks/joybrush/research/R1…R8`) and the owner's rulings (`tasks/joybrush/OWNER_CONSTRAINTS.md`).
This is the one current-truth document for the wing. Specs for each phase are written *when that
phase is scheduled*, not before (repo rule), and they must agree with this file or change it.

Working name: **Joy Brush** (owner, 2026-09-28). Earlier notes say "Joy Paint" — same thing.

---

## 0. The goal in one paragraph

Top-tier raster + vector illustration on a phone, with first-rate frame-by-frame animation, that
hands its output straight to the rest of Joy Creator: sprite sheets to SpriteLab and the Studio,
animations to the Studio timeline, rigged characters to Avatar Studio and the character library.
A few excellent brushes, not hundreds. Stylus first (pressure + tilt on every Note; barrel rotation
where hardware has it). The Note 9 is the performance floor; the Tab S8 is the large-screen check.
The door to iOS stays open.

---

## 1. The owner's ideas, rated honestly

Scale: ★★★ adopt as is · ★★ adopt with a change · ★ rethink.

| # | Idea | Rating | Verdict and the change, if any |
|---|---|---|---|
| 1 | **One-dialogue parametric tip** (square↔circle corners, taper to trapezoid/triangle, initial rotation, height/width −100…+100 to razor) | ★★ | Covers every Photoshop *computed* tip and adds shapes Photoshop cannot make (R4). **Changes:** (a) the corner slider must be a *superellipse* (one number from ellipse to square); plain corner-rounding of a stretched square gives a pill, not an oval, so it cannot match Photoshop's "roundness"; (b) the razor extreme keeps a ~1 px minimum with alpha fade or it flickers out; (c) add a tip-source switch so an image can *be* the tip (needed for imports and leaf/stamp brushes). |
| 2 | **Height-field texture:** procedural cloud × (tilt/pressure gradient) → threshold, for clumpy dry marks | ★★★ | This is exactly how Photoshop's "texture depth" and Krita's *Height* mode work (confirmed in Krita source, R3/R4) — and the **tilt-aimed gradient is something neither of them has**. **Two refinements:** (a) replace the hard threshold with one "edge width" number: 0 = crisp clumps, ⅓ = softer, 1 = smooth (one formula covers Krita's Hard Mix, Hard Mix Softer and Height); (b) there are **two textures, not one** — a *tip texture* that turns with the brush (bristle clumps, your cloud) and a *paper grain* locked to the canvas (pencil and charcoal only read as "on paper" if the grain stays put). |
| 3 | **Rotation derived from stroke direction, with damping from bristle length/stiffness** | ★★★ | Expresii does the same thing (R2); the research adds that the damping should be *by distance travelled*, not by time, and freeze at very low speed. Since the Note pens report tilt (owner, confirmed), **when tilted the brush follows the pen's lean; when upright it follows the stroke.** Round tips ignore rotation entirely (Expresii's developer removed twist for round brushes because it made them worse). |
| 4 | Scatter (small = jitter, large = leaves), rotation variance, hue/value jitter | ★★ | Standard and right. **Add:** count per dab and scatter on both axes (leaves need them), size jitter with a minimum, saturation jitter. |
| 5 | Spacing (you rarely go past 4%) | ★★ | Right for our own brushes. Two cautions: imported Photoshop brushes are authored at 10–25%, so they keep their own spacing; and the ink brush won't use dabs at all where a continuous stroke is cleaner. |
| 6 | **Missing from the model: flow vs opacity** | — | The single most important addition. Every stroke paints into its own buffer: *flow* is how fast paint builds, *opacity* is the ceiling it never passes. This is why ink and markers don't go darker where a stroke crosses itself (Krita "wash" mode, R3). Wet edges and proper erasing also depend on it. |
| 7 | **Few brushes:** inking, washing, easy fills, smudge/nudge, variable texture | ★★★ | Correct, and Expresii proves one great brush beats a library. Built as **two engines + one tool**: the *stamp engine* (ink, pencil/dry texture, marker, soft airbrush, smudge, nudge, eraser — all presets of the same engine), the *wet engine* (washes), and *fill* (a tool, not a brush). |
| 8 | **Infinite canvas; worry about when a far-away stroke is "a separate thing"** | ★★ | Good instinct, and the worry disappears: a layer only stores the patches you've painted (sparse tiles), so distance costs nothing and nothing needs deciding. What *does* need a boundary is export — that is what **Boards** are (§2). |
| 9 | **Sprite grid overlay** (drag onto art, sizes in px, cell count, tap cells in order, play preview, export sheet or sequence) | ★★ | Excellent, and it lands in formats the app already reads (`.sprite.json` + PNG, and the "sequence" sheet kind). **Change:** make it a *Board* anchored in the document, not a floating overlay, so it stays put, can sit beside other boards, and remembers its settings. The tap-cells-then-play mechanic is SpriteLab's chip system — reuse it, don't rebuild it. |
| 10 | **Animation paper** (peg bar that doubles as button row, pixel rulers, film frame with sprockets, ± buttons to add/duplicate/extend/delete, finger-scrub, onion skin, export video/GIF/sheet/sequence) | ★★ | The best idea in the brief — physical, grabbable, on-brand ("sprockets should do what sprockets do"). **Changes:** (a) hover menus don't exist for fingers, so the ± buttons open on tap/long-press, with hover as a pen bonus; (b) **a frame's cell width shows how long it is held, and you drag its edge to hold it longer** — FlipaClip, the #1 animation app (82M installs), has refused its users this for 12 years (R6); (c) onion skin is extracted from SpriteLab into one shared component, same settings, never a copy. |
| 11 | **Puppet board** (pins over the art, "test" on a flattened preview, export a rig) | ★★ | Good. **Change:** the test must run through Avatar Studio's own pose solver — one authority, per the repo rule that a second copy is how preview and export start disagreeing. Export = the existing `.avatar` bundle. |
| 12 | **Wiring boards into a character** (expression board + mouth board → head, look-at proxy, parallax) | ★★ | A strong vision and already half-built: an `AvatarRig` *is* parts that reference sprite sheets plus a mouth-shape map. **Change:** Joy Paint makes the parts and draws the wires; Avatar Studio stays the one place rig behaviour is edited. Build it last. |
| 13 | Brush-pack import (Photoshop etc.) | ★★ | Realistic (R4): `.abr` first (open-source readers exist, every major competitor imports it), then Procreate, MyPaint, Krita. Infinite Painter and Concepts formats can't be read. Later phase. |
| 14 | Test brush maths in a PC web app, then load as modules | ★★ | Kept, with the phone as the judge (§3). The PC lab shows how a mark *looks*; how it *feels* and how fast it runs are only judged on the Note 9. |
| 15 | Separate files so agents don't collide; don't grow the editor file | ★★★ | Essential. Joy Paint is separate modules with contracts between them (§3). |
| 16 | Raster-first with animation/sprite as modes, OR infinite canvas | ★★ | You don't have to pick — see §2. |

**The one real risk in the brief is size, not quality.** This wing is at least as large as the video
editor. The roadmap (§4) protects it by making the first release a genuinely excellent
paint-and-animate app, then adding one ecosystem link at a time.

---

## 2. Vector vs raster, infinite vs fixed — the recommendation

### Both, with one brush engine
- Every stroke is **recorded** as what the pen did (positions, pressure, tilt, time, brush, random
  seed) *and* painted as pixels.
- **Paint layer** — the pixels are the truth. Everything works here, including smudge and wet paint.
- **Ink layer** — the recording is the truth. The line is re-drawn crisp at any zoom, and can be
  moved, reshaped, or given a different width or brush after the fact. Tiny files; ideal for
  animation line art. Brushes that *read* the canvas (smudge, wet) are unavailable on ink layers —
  the same rule Clip Studio uses.
- **Fill** can use any layer as its boundary, so the classic "ink on top, colour underneath" works.
- Same brushes, same maths on both layer kinds. No second engine.

### Infinite workspace, with Boards
- The document is an unbounded, sparse tile space at one pixel density. Memory grows with what you
  paint, not with how far apart things are.
- A **Board** is a named rectangle in that space that knows its size in pixels and its export:
  - **Canvas board** — a plain illustration (PNG/PSD/OpenRaster export).
  - **Animation board** — the animation paper + film strip.
  - **Sprite board** — the grid.
  - **Puppet board** — pins and rig test.
  - (later) **Character board** — wires between the others.
- "New drawing" = one canvas board at the size you choose. It looks and behaves like a normal fixed
  canvas; turn on **Clip to board** and it *is* one. The space around it is there when you want it.
- A layer-count budget is computed at runtime from the device's memory and shown on screen (what
  Procreate and Infinite Painter do), so the Note 9 never gets pushed into a crash.

### What the canvas feels like (owner's reference: Concepts, 2026-09-28)
- **Open and draw.** A new drawing opens straight onto a white or gently textured page. No setup
  questions. Zoom and pan freely.
- **Paper is a setting, not a layer.** The background is transparent underneath; the paper colour
  and texture are chosen visually and shown behind everything. Export has an **Include paper**
  checkbox.
- **Export three ways:** what's on screen · the selected objects · the whole board.
- **Helpers are overlays**, never part of the art: grids, perspective guides, and shape tracers
  (ruler, ellipse, French curve) that the pen can run along. They never export.
- **Hold to perfect a shape.** Finish a rough line, arc, circle, ellipse, triangle or rectangle and
  hold: it becomes precise but keeps the stroke's pressure and tilt, so it still looks hand-made
  (spec JB-2.10).
- **One smoothing slider**, context-aware inside: corners stay sharp at any setting, slow shaky
  stretches get more smoothing than fast confident ones, and it is measured on screen so zooming in
  for detail automatically smooths less (built: JB-0.01, `StrokeSmoother`).
- **One eraser, context-aware.** On an ink layer it erases lines; on a paint layer it erases pixels
  with its raster brush settings. Ink erasing has three modes: *partial* (cut at eraser width),
  *whole line*, and **to intersection** (trim an overhang back to where it crosses another line,
  leaving a clean corner) (spec JB-5.10).
- **Selecting in dense line work** picks the stroke you meant: nearest centreline to the pen, most
  recent on ties, with a quick cycle-through on repeated taps (UI spec to come).
- **Nudge scales with zoom:** one nudge moves the same distance on screen; zoom in for fine moves.

---

## 3. The structure — so parts load as modules and agents don't collide

### 3.1 Modules (none of them is `FaditorEditorActivity`)

| Module | What lives there | Knows about Android? |
|---|---|---|
| `joypaint-core` | Document, boards, layers, stroke recordings, the input pipeline (filtering, smoothing, derived rotation and speed), brush-file parsing and the dynamics evaluator, undo bookkeeping, file format | **No.** Unit-tested on any computer, including the cloud |
| `joypaint-gpu` | Tile pool, dab renderer, compositor, low-latency front buffer, wet-paint scheduler | Yes (OpenGL ES 3) |
| `joypaint-app` | The Joy Paint screen: panels, gestures, boards UI | Yes |
| `shaders/` (shared folder) | Every GPU stage as GLSL ES 3.00 files | Used by phone *and* PC lab |
| `brushes/` (shared folder) | The brush presets (§3.2) | Data only |
| `tools/brushlab/` | The PC Brush Lab, one HTML file like SpriteLab | — |

- `:app` depends on these modules; they never depend on `:app`.
- **Hand-offs to the rest of Joy Creator are file contracts** the app already reads: `.sprite.json`
  + PNG to SpriteLab/Studio, PNG sequences and MP4 to the Studio, the `.avatar` bundle to the
  character library. Then one intent opens the receiving screen. No shared classes, so no collisions.
- **Language:** the core is **Kotlin Multiplatform**. In your terms: one engine that runs on the
  phone, inside the PC Brush Lab, and later on an iPhone — so the brush you test on the PC is the
  *same code* as on the phone, not a copy that can drift. Kotlin works alongside the app's existing
  Java without friction. (The alternative, C++, is faster to run but much harder to debug when
  something crashes on the phone.)
- **Performance rule (owner asked, 2026-09-28):** the heavy maths — every dab, blend, texture and
  the wet simulation — runs in GPU shaders, which are identical whichever language drives them. The
  language only runs the light work (reading the pen, curves, bookkeeping). Hot loops in the core
  must not allocate (no garbage-collector pauses mid-stroke). Phase 0 benchmarks the few heavy CPU
  jobs (flood fill, tile compression, PSD writing) on the Note 9; any that miss their budget move
  into a small C++ library that Kotlin calls on every platform. C++ is used where a measurement says
  so, not by default.

### 3.2 What a "brush module" is
Two levels, so power users get depth and the phone stays safe:

1. **Brush preset** — a small folder: `brush.json` (the tip shape, textures, flow/opacity, and every
   dynamic as *input → curve → setting*, MyPaint's proven model) plus optional `tip.png` /
   `grain.png`. Pure data: shareable, importable, safe. Imported Photoshop/Procreate brushes become
   these. The painter sees 3–6 big sliders per brush; the full editor lives in the Brush Lab.
2. **Engine stage** — new *maths*: a GLSL shader plus the list of parameters it exposes (e.g. "height
   threshold grain", "tilt gradient", "wet-lite flow"). These are what you prototype, tune and then
   "load in". The phone and the PC lab load the same stage files.

### 3.3 Two Brush Labs, phone is the judge
- **PC Brush Lab:** sliders, instant redraw, your Samsung laptop pen via the browser (pressure and
  tilt), a fake-rotation control for barrel-rotation brushes, and a "phone budget" meter that warns
  when a stage would be too slow for a Note 9. In the cloud it also runs automated
  "does this brush still look the same" checks.
- **Phone Brush Lab:** a hidden developer screen. Save `brush.json` on the PC and the phone reloads
  it within a second over Wi-Fi. This is where feel is tuned.

### 3.4 Engine decisions (from R1–R3, R7)
- **Performance floor = Note 9** (Android 10, OpenGL ES 3.2). Modern features are used when present
  (prediction ML on Android 14+, faster screens) and never required.
- **Low latency:** Google's front-buffer renderer (works from Android 10, so both Notes) + stroke
  prediction, with a switch to turn either off.
- **Input:** every pen sample is used, not just the latest. A one-euro filter smooths jitter, then
  an optional stabiliser. Palm rejection: once a pen is seen, fingers navigate and the pen draws.
- **Calibration:** a data table with sensible defaults per device, plus a hidden live overlay showing
  raw pressure/tilt/direction. You calibrate *after* the app works — nothing waits on it.
- **Tiles:** 256×256 on the GPU, compressed on the CPU/disk. Undo stores only the tiles a stroke
  changed. Every stroke paints into a 16-bit scratch buffer (no banding in soft glazes); layers are
  8-bit by default with an optional deep-colour mode.
- **Files:** a native zip in the open OpenRaster shape (layers + JSON for boards, frames, stroke
  recordings). Export PNG, OpenRaster, PSD (own writer), GIF/WebP/MP4, PNG sequence, sprite sheet.

### 3.5 UI rules (R5, R6 + owner)
- **Screen-size agnostic:** every panel is one piece of content that shows as a drawer on a phone
  and a small popover on a tablet. Nothing is designed twice.
- **Standard gestures, not inventions:** 2-finger tap undo, 3-finger tap redo, 2 fingers
  pan/zoom/rotate (quick pinch fits the board), long-press eyedropper, draw-and-hold snaps to a clean
  shape, 4-finger tap hides the UI. No destructive gestures. A gesture cheat-sheet exists because
  users never find gestures by accident. **Unresolved, decided at the UI spec:** three-finger drag is
  wanted twice — for size/opacity (Infinite Painter, much praised) and for flipping animation frames
  (Callipeg). It can only mean one thing.
- **Values change by dragging on the control**, never by opening a panel. Drag off the active colour
  swatch = instant colour picker; drag on the brush swatch = size. Any control that has a useful
  drag gets one.
- **Pen and finger (owner, 2026-09-28):** with no pen seen, fingers draw. The moment a pen is
  detected, fingers stop drawing and become a **tool finger** the user cycles between three modes —
  *select objects · lasso · colour pick* (Concepts' model) — plus nudging. Two- and three-finger
  gestures are assignable in settings.
- **Transforms commit when you tap outside** (owner, 2026-09-28). Undo reverts.
- **Selection done right (the thing Infinite Painter gets wrong):** start it in one gesture (hold the
  S Pen button and loop); the transform box appears immediately; two fingers *inside* the box
  transform it, outside they move the canvas; painting stays inside the selection; the selection
  survives operations and can be reselected. One row of actions, nothing hidden in a sideways
  scroll. A tap outside commits.
- **S Pen button:** hold while drawing = erase with the current brush; tap = eyedropper. Never bind
  the click while hovering (Samsung's Air Command owns it).
- **Autosave that never loses work** — the #1 complaint across 34,000 reviews of competitors was
  crashes and lost work, ahead of any UI issue.

---

## 4. The roadmap

Every phase ends with an **owner check on the Note 9**. "Built" and "proven" stay different words.
The Tab S8 check follows once a phase is good on the phone.

| Phase | What | Owner check that ends it |
|---|---|---|
| **0 · Foundation** | The modules and their contracts (document, stroke, brush JSON schemas). Input pipeline with the hidden pen-values overlay. Tile engine, layers, undo, save/reopen, PNG export. One round brush with pressure + tilt. Low-latency drawing. A Joy Paint entry from the lobby. | Draw, save, reopen on the Note 9. Lag feels like Infinite Painter's or better. |
| **1 · The brush engine** | Your parametric tip (superellipse corners, taper, aspect, rotation). Tip texture + paper grain with the height threshold and tilt gradient. Flow/opacity stroke buffer. Dynamics curves. Scatter, count, jitters, colour jitter. Smoothing. Presets: Ink, Pencil/Dry, Marker, Soft airbrush, Smudge, Nudge, Eraser (any brush). PC Brush Lab + phone hot-reload lab. | You judge each brush on the Note 9 and sign it off (or send it back). |
| **2 · A real painting app** | The minimal UI (§3.5), colour picker, layers panel with blend modes, selection + transform done right, fill with gap closing and reference layer, shape snap, symmetry, reference images, PSD/OpenRaster export. | You finish a real illustration without hitting frustration. Then the Tab S8 check. |
| **3 · Animation board** | Animation paper with peg bar, film strip, ± frame actions, drag-to-hold, finger scrub, shared onion skin, playback, audio track. Export MP4/GIF/WebP/PNG sequence/sprite sheet. **Send to Studio.** | You animate a short loop and drop it on a Studio timeline. |
| **4 · Sprite board** | Grid board, cell play preview (SpriteLab's chip mechanic), sub-grids, export sheet or sequence — **Export** and **Export and open in SpriteLab**. | A sheet drawn in Joy Paint opens in SpriteLab and plays in the Studio. |
| **5 · Ink layers** | Recorded strokes as the truth: crisp at any zoom, editable after the fact, fill-by-reference to ink. | You re-shape and re-weight a finished line. |
| **6 · Wash** | Wet engine in stages: *Level 0* fake watercolour on commit (edge darkening, granulation, blooms — ~80% of the look); *Level 1* "wet-lite" flow only in the wet area, paused while the pen is down (Rebelle's trick); the one great Expresii-style brush (tip for detail, belly for washes). Level 2 full fluid only on newer tablets, later. | Washes you'd actually use, at full speed on the Note 9. |
| **7 · Puppet board + characters** | Pins over the art, test through Avatar Studio's own solver, export `.avatar`. Then the Character board: wire expression and mouth boards into a head, parallax, look-at. Into the character library. | A character drawn in Joy Paint talks in the Studio. |
| **8 · Brush import & packs** | `.abr` → Procreate → MyPaint → Krita. Bundle the free-to-ship packs (MyPaint CC0, David Revoy CC-BY, public-domain sets). | Your favourite Photoshop brush works. |
| **Later** | Wet Level 2; pigment-true layers (lossless re-wetting); iOS shell (Metal via shader translation); desktop-grade extras. | — |

**Parallel lanes once Phase 0's contracts exist:** engine/GPU · brush stages + labs · UI shell ·
file formats and hand-offs · boards (animation/sprite). Each lane owns its module or folder and is
claimed in `tasks/LANES.md`.

### Relation to the launch
`ROADMAP.md` keeps BEFORE LAUNCH closed. Joy Paint touches no launch file except one lobby entry,
so it can run as its own lane without disturbing launch work. **Started 2026-09-28 on the owner's
word.** It competes for agent hours, not for files.

---

## 5. Legal notes to carry forward (not legal advice)
- **Do not ship Mixbox** (non-commercial licence). Colour mixing uses MyPaint's spectral mixing (ISC)
  or spectral.js (MIT).
- **Do not commit or copy the MoXi research code or PDFs** (research-only licence). Clean-room from
  the published equations.
- Krita code (GPL-2+/3+) and libmypaint (ISC) are compatible with Joy Creator's GPL-3.0; keep their
  copyright notices on anything ported.
- **Patents — read from the actual claims (R8, 2026-09-28; re-check in USPTO Patent Center before a
  US launch):** US 8,296,668 (paper absorption channel) **lapsed 2024**. Every other effect is built
  without the claimed recipe:
  - Smudge / colour pickup: ONE carried colour + amount per brush that mixes toward the canvas and
    toward the chosen colour, one dab — never separate reservoir and pickup stores, never "deposit
    only picked-up paint when there is enough" (8,462,173; 8,599,213). Wet/Load/Mix/Dry sliders are fine.
  - Loading a brush: from a swatch or gradient tray, or a one-colour eyedropper — never a mode that
    sweeps over the painting sampling it without changing it (8,654,143).
  - Brush tip is a 2-D shape, never a 3-D brush model; pickup state stays coarse (9,030,464).
  - Wet paint: grid shallow-water / diffusion / thin-film solvers, never lattice Boltzmann
    (8,335,675); never growing water/pigment polygons (8,917,282/283 — may lapse Dec 2026).
  - Edge darkening on the raster wet mask, never on vector fill outlines (7,777,745, to 2028).
  - Vector export is one outline per stylus stroke, never per-bristle paths (8,605,095).
- Importing brush files the user owns is fine; redistributing converted third-party packs is not.
  Keep author/licence/source in every imported brush.

---

## 5b. The look
Joy Brush uses Joy Creator's own visual language — tokens, drawers, pills, frosted see-through
panels, fonts and motion — reusing the existing components wherever they are public
(`tasks/joybrush/design/JOYBRUSH_VISUAL_LANGUAGE.md`). **Boards wear the colour of what they feed:**
Sprite board in SpriteLab's pink→violet, Puppet and Character boards in Avatar's violet→purple,
Animation board in the Studio's aqua→lime, Canvas board in Joy Brush's own. State colours stay
global (cyan = selected, pink = live) and are never used as a section colour.

## 5c. How the work is shared out
The build is split into spec sheets that a non-frontier model can execute reliably, tiered by what
each needs (Claude / code model / vision-and-design model / owner on the phone), with Claude keeping
one phase of ready specs ahead of the builders. See `tasks/joybrush/specs/README.md` and `INDEX.md`.

## 6. Open product questions for the owner
Answered 2026-09-28: name = **Joy Brush**; **start now**; **tap outside commits**; fingers draw until
a pen is seen, then become the tool finger.
1. Three-finger drag: brush size/opacity, or flipping animation frames?
2. Joy Brush's own section colour — design lead's recommendation is **indigo → bright blue**
   (`#5C43FD → #4397FD`); runner-up lime → yellow-green.

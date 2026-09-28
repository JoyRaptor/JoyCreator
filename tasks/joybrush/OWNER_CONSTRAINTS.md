# Joy Paint — owner constraints and rulings (running log)

Everything JoyRaptor has said that constrains the design, in his terms. The design lead reads this
before every spec. Newest at the bottom.

## 2026-09-28 — founding brief
- Goal: top-tier raster + vector illustration on mobile, with first-rate animation tools, that
  springboards into the Studio editor, SpriteLab, Avatar Studio and a character library.
- A few excellent brushes, not hundreds: inking, washes, easy fills, smudge/nudge, variable texture.
- Stylus first: pressure and tilt at minimum; rotation from barrel sensors where they exist, otherwise
  derived from stroke direction (lazy-mouse style) with damping; velocity available to every brush.
- Brushes are modular and loadable. Their maths should be testable on a PC, then loaded into the app.
- Must NOT grow `FaditorEditorActivity`; Joy Paint lives in its own files/modules so several agents can
  build parts in parallel without colliding.
- Import of popular brush packs (Photoshop etc.) wanted if at all possible.
- Overlays / modes wanted: sprite grid board, animation paper + film strip + onion skin, puppet board
  with pin test and rig export, and wiring boards together into a character.

## 2026-09-28 — web vs phone
- "Prioritize everything so that it is the best possible end result product on the phone."
- The PC web lab is optional. If building for the web would make the phone product worse, build and
  test on the phone instead. Flexible on method.
- Ruling adopted by the design lead: the phone is the judge; the web lab is a sketchpad for the look of
  a mark, never for feel or performance. Brush modules use the shared shader subset; the engine is free
  to use phone-only GPU features. A phone-side hot-reload brush lab is the primary tuning bench.

## 2026-09-28 — keep the door open to iOS
- If Joy Creator succeeds, an iOS port is likely wanted. JoyRaptor has no iPhone, no Mac, and is not
  in the Apple ecosystem, so nothing can be hand-tested on iOS by him.
- His test hardware for barrel rotation: none (Samsung laptop uses the same pen family as the Note 9).
- Consequence: the Joy Paint core must be platform-neutral (no Android types, portable shaders,
  platform-neutral file format), so a later iOS shell reuses the engine rather than rewriting it.

## 2026-09-28 — tilt on the Note series
- JoyRaptor confirms: the Note 9 and the Note series report tilt (part of the EMR pen technology).
- Consequence: tilt is a FIRST-CLASS input on his devices, not an optional extra. Brushes may lean on
  tilt (tilt-aimed grain gradient, chisel/side-of-pencil shading) and default rotation can use the
  pen's lean direction (AXIS_ORIENTATION) when tilted, falling back to stroke direction when upright.
- Still owed: the Stylus Probe spike measures the actual ranges, direction convention and noise on
  the Note 9 / Note 20 so the calibration is right (forum reports show reversed or diagonal-only tilt
  on some other Samsung models).

## 2026-09-28 — screen size, performance floor, probe timing, sprite export
- **Screen-size agnostic UI.** Infinite Painter and Concepts work equally well on phones and tablets;
  so must Joy Paint. Panels are small and local: drawers on a phone, small drop-downs/popovers on a
  larger screen, with little or no other change between them.
- **The Note 9 is the performance floor.** "If it works good on there … it'll work good on pretty
  much anything modern." Use modern features when available, never depend on them. All his favourite
  art apps run on the Note 9.
- **Tablet check comes after the phone works well:** he has a Galaxy Tab S8.
- **The stylus probe does NOT have to come first.** Build the app, then calibrate. (Ruling adopted:
  the probe is a hidden diagnostics overlay inside the first build, and calibration is a data table
  with sensible defaults, so nothing waits on it.)
- **Sprite export = two buttons:** "Export" (save the sheet + .sprite.json) and "Export and open in
  SpriteLab". A file is always written first.

## 2026-09-28 — name, go-ahead, fingers, canvas behaviour, tools, look, budget
- **Name: Joy Brush** (working title). Folder renamed `tasks/joypaint` → `tasks/joybrush`.
- **"Let's start work now."**
- **Transform/selection: tapping outside commits** (Concepts style).
- **Fingers:** with no pen seen, fingers draw. The moment a pen is detected, fingers stop drawing and
  become a *tool finger* with modes the user cycles between — **select objects / lasso / colour pick**
  (Concepts' model; "that's really what the finger is for") — plus nudging. Two- and three-finger
  gestures are user-assignable in settings.
- **Drag on the controls:** drag off the active colour swatch = instant colour picker; drag on the
  brush swatch = size (or other values). Most on-screen controls should be draggable where it is
  genuinely useful.
- **Paper is a setting, not a layer:** background is treated as transparent; the paper colour/texture
  is chosen visually, and export has a simple "include paper" checkbox (Concepts / Infinite Painter).
- **Concepts' canvas experience is the reference:** open straight onto a white or gently textured
  page and draw; zoom freely; export the screen, the selected objects, or the whole board.
- **Helpers:** overlays for perspective and grids, shape-tracer widgets (ruler/ellipse/etc.).
- **Shape auto-detect:** hold to turn a rough circle/triangle/rectangle/arc into a precise shape
  **while keeping the stroke's pressure and tilt**, so it stays organic, not computer-perfect.
- **Smoothing:** one simple user-facing slider; internally it must be context-aware (velocity
  sensitive; keeps corners and small detail without the user bumping it around).
- **Eraser, context aware:** on vector/ink it erases vectors, on raster it erases pixels with raster
  settings. Vector eraser modes: *partial* (cut a chunk at eraser width), *whole stroke*, and
  **to intersection** — erase an overhang back to where the line crosses another line, leaving a clean
  corner (Clip Studio's "vector eraser: up to intersection").
- **Selection in dense line work** must be as good as Concepts' (easy to pick the right stroke).
- **Nudge scales with zoom:** a nudge moves the same distance *on screen*; zoom in for fine work.
- **Visual language = Joy Creator's.** Same menus, rounded and pill buttons, semi-transparent/frosted
  panels so the art shows underneath, Joy Brush's own colourful two-colour gradient. **Boards wear the
  colours of what they connect to** (Sprite board in SpriteLab's colours; a selected text box in the
  editor's text/font ribbon colours). One visual language across the ecosystem.
- **Budget reality:** JoyRaptor is short of money. When Claude usage runs out he continues in the
  opencode harness with the best free models of the day (currently GLM 5.3, DeepSeek 4.1, MuseSpark
  1.3, Xiaomi models). So: **the whole build is broken into detailed spec sheets a near-frontier
  model can execute reliably**; Claude keeps the parts that must be right (architecture, contracts,
  the hard maths, reviews). Each spec states the capability it needs (e.g. strong vision/design vs
  code-only), because some models design better and some have no vision.

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

# The Digital Pen: one engine, many brushes, better than Photoshop's default (lead spec, 2026-10-16)

Owner (2026-10-16, building on the 2026-10-02 "one parametric pen", LEAD_DESK and AGENT_BOARD item 1):
- **Shape:** square → circle → trapezoid; direction change; a hard-to-soft gradient.
- **Toggles:** whether it follows the stroke direction; whether pressure **tapers** (width) or **fades** (ink).
- **Colour:** hue jitter within a range, saturation jitter too.
- **Range of marks:** "very, very soft, completely diffused huge marks as well as pen-line marks of variable width" from pressure, tilt and jitter.
- **Calligraphy:** set the tip's rotation (from tilt), its width-to-height ratio and its shape.
- **Picture tips:** load a PNG as a sample and set the sample frequency.
- **Presentation:** "several different brushes that are actually the same brush under the hood."

Plus anything that makes it premium. Must work in raster AND as editable lines (NATIVE_LINES_V1).
Builder: Codex or another agent. Lead reviews each slice.

## 0. Owner amendment, 2026-10-18: it is the UNIBRUSH (supersedes the names and A4's shape below where they differ)
Owner, condensed: *the Digital Essentials are really ONE super flexible brush that can become many. In Photoshop the
hardness slider is another dial on MY pen, not two brushes.* The presentation must make people feel these are MODES of one
versatile brush, so they dive in and make it theirs. The presets are only there "to get you thinking."
- **Name:** **Unibrush** (owner's word; the only "UNIBRUSH" US mark found is an abandoned 2017 scrubbing-brush filing).
  Presets are its modes: *Unibrush Soft, Unibrush Hard, Unibrush Calligraphy, Unibrush Taper, Unibrush Fade, Unibrush
  Texture* … On the shelf the header says Unibrush once and each row shows only its mode word.
- **Make it yours:** change anything, press **New brush**, and it is saved as a new mode in that same Unibrush shelf.
- **The shape, by hand** (replaces A4's polygon/star as the main shape tool; star/polygon may stay as presets of it):
  1. **Four points:** drag any of 4 points into any shape 4 points can make; an **arrow handle** sets the facing angle
     (Photoshop has the angle; it has no free shape). **1b. A second shape B**: pressure or tilt (or speed) morphs the tip
     from shape A to shape B (circle → rectangle is just every parameter sliding).
  2. **Corners:** from a hard angle to completely rounded, or anything between (the existing `tip.corner`, now on the quad).
  3. **Sliders** for size and thinness (`tip.aspect`).
  4. **Softness:** a radial gradient (A2 profile) and a linear gradient with its own angle (A3 directional fade).
  5. **Size.** 6. **Jitter** of every sort (size, angle, scatter, colour: A5).
  7. **Texture:** a sampled stamp image with its spacing (A6), and a SECOND texture across the stroke (paper-like, fixed to
     the canvas: the existing `GrainSpec`/`tipTexture`, plus A7's dual tip for broken edges).
  8. **Behaviours:** what the tip turns with: its angle, the stroke direction, the pen's lean, the barrel (A1).
- **Engine (A11):** the tip is a rounded-quad SDF in `jb_tip.glsl` from 4 normalised points + corner radius; shape B is a
  second point set; per dab `t = Param(pressure|tilt|speed)` interpolates points, corner, aspect and profile. Pure dab
  data, so it replays exactly as an editable line. Every existing round/square/taper brush maps to a quad (migration test:
  old brushes byte-identical).
- **Sheet:** the shape editor sits under the live preview stroke (drawn by the real engine); A and B are two tabs of the
  same editor with a "morph by" chip row (pressure · tilt · speed · off).

## 1. What already exists (reuse it, do not rebuild)
The stamp engine (`engine: "stamp"`, `BrushPreset`, `BrushDabber`, `jb_tip.glsl`, `Dynamics.kt`, `Response.kt`) already has most of Photoshop's brush tip and shape dynamics:

| Photoshop | JoyBrush today |
|---|---|
| Size, Hardness, Spacing | `size: Param`, `tip.hardness: Param`, `spacing` |
| Roundness, Angle | `tip.aspect`, `tip.angle: Param`, `tip.aspectDynamics` |
| Square to circle | `tip.corner` (superellipse exponent) |
| Trapezoid / triangle | `tip.taper` |
| Angle follows direction | `tip.followDirection` |
| Size, opacity and flow jitter / control by pressure, tilt, speed, direction | `Param` with inputs and curves (`Dynamics.kt`), `ResponseSpec` curves, `sizeJitter` |
| Angle jitter | `angleJitter` |
| Colour dynamics (H/S/B, per tip) | `ColorJitter(hue, saturation, value, perStroke)` |
| Scattering, count | `ScatterSpec` |
| Texture | `tipTexture` / `paperGrain` (`GrainSpec`: image, scale, depth, tiltGradient, radial) |
| Sampled (PNG) tip | `tip.source = "image"` |
| Build-up vs wash | `accumulate` |
| Smoothing | `smoothing` + `StrokeSmoother` |

So the Digital Pen is **(a) a few real engine additions, (b) a premium settings sheet, (c) a preset family.** Never a second engine.

## 2. Engine additions (stamp engine, brush version bump at landing per R3/R30)
- **A1. Angle source** (replaces the lone `followDirection` boolean, which is migrated):
  `tip.angleSource: fixed | stroke | penAzimuth | penBarrel`, plus `tip.angle` as the offset.
  - `penAzimuth` gives true calligraphy: the nib turns with the pen's lean.
  - `stroke` keeps today's behaviour, smoothed with the existing DirectionTracker.
  - The angle is smoothed over ~1.5 mm of travel, as the oil/watercolour brushes already do, so jitter can't fan a dab.
- **A2. Tip profile** (the "centre gradient"): `tip.profile: hard | linear | smooth | gaussian | custom curve`.
  - It sets the falloff from centre to edge and is applied after the superellipse/taper shape, so every shape gets every gradient.
  - `hardness` stays: it is where the falloff starts.
  - `gaussian` at 100% softness gives Photoshop's fully diffused airbrush.
- **A3. Directional fade** (the "gradient fade angle"): `tip.fade = { amount 0..1, angle°, relativeTo: tip | canvas | penAzimuth }`.
  - A linear gradient across the tip: one side solid, the other side fading.
  - `relativeTo penAzimuth` gives the soft-side shading of a leaning nib or chisel.
  - Dynamics: `amount` is a `Param` (pressure or tilt can push it).
- **A4. Polygon and star tips** (the "variable points"): `tip.points: Int 0..12` (0 = off) and `tip.inner: 0..1`.
  - `inner = 1` gives a regular polygon; `inner < 1` gives a star.
  - It is an SDF in `jb_tip.glsl`, so roundness, angle, profile and fade apply unchanged.
  - Cheap, resolution-free, and replayable.
- **A5. Colour dynamics, premium.** Extend `ColorJitter`:
  - `hueRange°` (± range, e.g. 0–60) and `saturationRange`;
  - `mode: perDab | perStroke | perStrokeDrift` (a slow random walk along the stroke: the "living colour" look);
  - `secondaryMix 0..1` (jitter between the main colour and the second, background colour, as Photoshop's FG/BG jitter);
  - `pressureHue°` (pressure pushes hue a set amount: warm-when-firm inking).

  All of it is seeded from the stroke seed, so it is deterministic and replays exactly.
- **A6. Sampled tips, done well.** `tip.images: List<path>` (1–16 PNGs):
  - Pick modes: `sequence | random | byPressure | byDirection` (byDirection lets a brush carry a nib rendered at 8 angles).
  - `rotateJitter`; `invert`; `useAlpha | useLuminance`.
  - "Sample frequency" stays `spacing`, plus `spacingJitter`.
  - Images are **frozen into the drawing** with the frozen brush (brushes/<id>/…), as frozen brushes already are. That is what lets an image-tip line replay after reopening (today `prepareRecordedLine` refuses image tips: wire them in).
  - The PNG importer reuses the existing image-tip path and the .abr sampled-tip importer.
- **A7. Dual tip** (Photoshop's Dual Brush, the "premium extra"): an optional second shape or image `tip.dual = {shape | image, scale, spacing, scatter, mode: multiply | subtract | min}` masks the main tip. Broken, dry and charcoal edges without a texture layer.
- **A8. Pressure behaviour, said plainly.** A preset field the sheet shows as one chip row: `pressure → width | ink | both | none` (taper vs fade, the owner's toggle). Underneath it only rewires the existing `size` / `flow` Param inputs; nothing new in the dabber.
- **A9. Tilt behaviour**, as one chip row: `tilt → angle | flatten (aspect) | size | side-fade (A3) | none`. Again only Param wiring, plus A1/A3.
- **A10. Stroke ends by length** (start and end taper, independent of pressure; mouse- and finger-friendly). Reuse `LineStyleSpec` taper. The work is making it replayable for editable lines: today a brush with `line` is refused, and the taper must use arc length known at replay.

## 3. The settings sheet (premium, phone-first)
- **Placement and preview.** It opens from "hold a brush" (owner rule). A live preview stroke at the top is drawn by the real engine (fixes "the previews suck"). A tilt/pressure scrub pad sits under it.
- **Simple** (default): Shape (circle ↔ square ↔ trapezoid slider plus a points chip), Softness, Size, Pressure → (width/ink/both), Tilt → (angle/flatten/size/side-fade), Colour life (one slider driving hue range and saturation together).
- **All**: every field above, grouped Tip · Profile · Dynamics · Colour · Sample images · Dual tip · Texture · Stroke ends · Stabilizer.
- **Rules.** Every control gets a hover label (owner rule). Chosen chips are solid cyan with a cut-out icon. Saving makes a new preset ("several brushes, one engine").

## 4. Preset family (all `engine: "stamp"`, all replayable as editable lines)
| Preset | Recipe |
|---|---|
| Basic Round | Circle, smooth profile, hardness 0.85, pressure → both |
| Hard Pen | Circle, hard profile, pressure → width, small, for line work |
| Soft Air (huge) | Gaussian, hardness 0, flow low, pressure → ink, sizes to 2000 px, buildup |
| Calligraphy Flat | Aspect 0.15, corner 6 (square), angleSource penAzimuth, tilt → flatten |
| Chisel Marker | Taper 0.4, side-fade relativeTo penAzimuth, wash accumulate |
| Brush Pen | Smooth, pressure → width with a strong curve, stroke-end taper |
| Living Colour Ink | Hard Pen plus perStrokeDrift hue 18°, saturation 0.1 |
| Dry Texture | Dual tip (image), pressure → ink, spacing 0.12 |
| Star Sparkle | Points 5, inner 0.45, scatter, hue jitter, random rotation |

## 5. Smoothing: brush or a separate stabilizer? (owner deliberating)
**Recommendation: a separate Stabilizer section, with a per-brush suggestion.**
- Lazy-Nezumi-style stabilisation is about the hand and the task (inking, sketching), not the tip. Procreate and Clip Studio both keep it beside the brush, not inside the tip.
- The stabilizer panel (canvas ⋯ menu, also in the brush sheet's last tab):
  - **Mode:** off · smooth (today's StrokeSmoother) · pulled string (lazy rope: radius in screen px) · moving average · catch-up on lift;
  - **Strength;**
  - **"Use each brush's suggestion / Always mine"** toggle.
- **Brushes carry a `stabilizer` suggestion** (Hard Pen and Brush Pen suggest a pulled string; Soft Air suggests off).
- **Later:** ruler, ellipse and perspective guides live with the existing GuideSnapper, not in brushes.
- **Hard rule for editable lines:** records keep RAW samples (post-guide, pre-stabilizer) plus the exact stabilizer mode and parameters actually used. Replay must re-run the identical stabilizer, deterministic and seeded, so an edited line keeps its look. Extend `StrokeRecord.smoothing` into a small `stabilizer` value (record codec version bump).

## 6. Performance (Note 9 is the floor)
- **Huge soft dabs are fill-rate bound.** A 2000 px gaussian dab at 4 % spacing is about 25 full-screen overdraws per inch.
  - Auto-raise spacing for soft profiles (a gaussian dab's coverage barely changes up to ~15 % spacing).
  - Render dabs above about 512 px into a half-resolution stroke buffer and upsample at commit; the eye cannot see it on a fully soft edge. Prove it with a pixel diff.
- **Budget:** pen-line presets at 60 fps on the Note 9 with zero hitches on the bench's spiral and scribble cases. Soft Air at 2000 px at ≥ 45 fps.
- **No per-dab texture binds:** image-tip sets live in one texture array or atlas.

## 7. Slices (builder) and proof
| Slice | Content | Proof |
|---|---|---|
| D1 | A1 angle source + A8/A9 chip wiring + migration of followDirection | JVM dab tests; old brushes byte-identical |
| D2 | A2 profiles + A3 directional fade in jb_tip.glsl | headless shader check vs CPU oracle; broken control |
| D3 | A11 four-point shape + corner + shape-B morph (A4 star/polygon as its presets) | shader check vs CPU oracle; replay == live; old brushes byte-identical |
| D4 | A5 colour dynamics | determinism test (same seed → same colours); replay == live |
| D5 | A6 sampled tip sets + freezing images into drawings + editable-line replay of image tips | archive roundtrip; GPU replay == live on the Note 9 bench |
| D6 | A7 dual tip | shader check |
| D7 | A10 replayable stroke-end taper | replay == live |
| D8 | Stabilizer section + record codec bump | replay of a stabilised stroke == live, bit for bit |
| D9 | Unibrush sheet (shape editor, A/B tabs, New brush saves a mode) + preset modes + real-engine previews | owner check on the phone |
| D10 | Performance: soft-dab spacing and half-res buffer | Note 9 bench numbers in §6 |

**Owner check:** the 9 presets drawn on the Note 9 as raster AND as editable lines (then moved, rotated, recoloured, re-brushed). He judges against Photoshop's default round and Procreate's.

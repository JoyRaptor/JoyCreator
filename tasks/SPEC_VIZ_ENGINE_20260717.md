# SPEC: Joy Viz Engine — modular layered visualizer (2026-07-17, JoyRaptor + Fable)

JoyRaptor: "we have lines, solids, we need particles… progressive vs frequency, linear vs
radial… several hard edge things, not so much soft edged things. make a really
spectacular modular visualizer." Research input: Muviz Navbar 5.1 resource teardown
(feature inventory ONLY — no code/assets copied; their dex/designs untouched).

## 0. What Muviz's engine actually is (teardown findings)

A visualizer **design = an ordered stack of LAYERS** ("Add Layer", "EDIT LAYERS").
Per layer: Shape (**Bars, Lines, Circles, Squares, Particles, Peaks**) ×
geometry (**Size, Height, Spread, Rotation 1°–360°**) × color (**solid, Color
Gradient, From Wallpaper**) × FX (**Glow, Shadow, Transparency**) × audio
(**Audio Response Rate**, **Centre Align Frequencies**) + global **Frame Rate
Low/High**. Community "designs" are serialized layer stacks. The layering (with
additive neon stacking) is where all the "spectacular" comes from — one shape
alone never looks premium.

## 1. What we already have (keep, don't rewrite)

- `WaveformStyle` (bars/line/mirror_bars/filled_wave + spectrum×2), 2-stop
  gradient, glow color+radius, bandCount, sensitivity, per-instance
  `renderMode` LINEAR|RADIAL (+radialRingSize), `dataMode`
  progressive|frequency, `centerMode`, freqLow/HighHz band windowing, justify.
- `WaveformStyleRenderer` — Canvas-based, **stateless at time t** (preview,
  scrub AND `CompositeExportOverlay` all call it at arbitrary t). This property
  is load-bearing; the new engine must preserve it.
- Rolodex + `WaveformStyleIO` (JSON import/export), per-instance overrides.

## 2. Architecture (the modular contract)

A visualizer instance renders a **stack of VizLayers** (legacy single-style
instances = a stack of one, auto-migrated). Each layer runs the same pipeline:

  AudioMapper → GeometryMapper → Emitter → PaintStage

- **AudioMapper** (per layer): source = PROGRESSIVE (time-tape) | FREQUENCY
  (FFT); band window (freqLowHz..freqHighHz); bandCount; gain; **response**
  = attack/release smoothing pair (Muviz "Audio Response Rate"; we smooth by
  sampling energies at t and t−Δ, stateless); centerAlign (bass in the middle,
  mirrored outward — Muviz "Centre Align Frequencies").
- **GeometryMapper**: LINEAR (strip: x = band index) | RADIAL (ring: θ = band
  index, radialRingSize) — emitters draw in mapper space, so EVERY emitter gets
  radial for free (JoyRaptor: "adapt its things to our radial option as well").
  Per-layer: spread (fraction of strip/arc used), phase/rotation offset,
  rotationSpeed (deg/s, deterministic: angle = speed·t), mirror.
- **Emitter** (the shape module — each ~a page of code):
  BARS (rounded caps, width/gap), PEAKS (falling peak-hold caps; cap height =
  max energy over [t−fall, t], stateless via N taps), LINE, FILLED, DOTS
  (circle per band, radius ∝ energy), SQUARES, **PARTICLES** (see §3),
  RING (radial-only accent circle pulsing with RMS).
- **PaintStage** (per layer): color = SOLID | GRADIENT multi-stop (axis: along
  bands or along amplitude); opacity; blend = NORMAL | **ADD** (neon stacking);
  glow (radius, color, BlurMaskFilter OUTER); shadow (dx dy radius color);
  **softness** 0..1 (0 = hard edge today, >0 = NORMAL-blur mask + falloff
  sprites — the missing "soft edged things").

## 3. Particles — the determinism rule (DO NOT VIOLATE)

Export/scrub sample arbitrary t, so particles are a **pure function of time**:
no sim state. Particle i of band b at time t: spawn times are a fixed lattice
(hash(b,i) jitters phase); a particle born at t₀ has age a = t−t₀, position =
mapper-space trajectory(a, energy(t₀)), alpha = life envelope(a) · energy(t₀).
Energy at birth comes from the SAME banded cache used by bars. Preview, scrub,
and export automatically agree; no per-frame state anywhere. Trails likewise =
K extra taps at t−k·Δ with decaying alpha.

## 4. Model + serialization

`VizLayer` (new, model pkg): emitter id + the §2 params, `toJson/fromJson`
tolerant-read like CompositingSpec. `WaveformStyle` gains
`@Nullable List<VizLayer> layers` (null = legacy single-shape → renderer
auto-wraps as one layer at draw time; old JSON untouched byte-for-byte).
Rolodex designs with layers serialize the stack; instance-level overrides
(color/sensitivity) apply to layer 0 for legacy parity.

## 5. Phases

- **P1 — Layer core (build first):** VizLayer model + JSON; renderer refactor to
  the 4-stage pipeline with existing shapes as emitters through GeometryMapper
  (radial for free); legacy auto-wrap; drawer "Layers" row (add/duplicate/
  delete/reorder, per-layer shape+color+opacity+blend). Done when: a 2-layer
  design (ADD-blended bars over filled wave) renders identically in preview and
  an export A/B, and every pre-existing style renders pixel-identical (A/B a
  legacy project before/after).
- **P2 — New hard emitters:** PEAKS, DOTS, SQUARES, RING + shadow + per-layer
  glow. Done when: each renders in linear AND radial.
- **P3 — PARTICLES** per §3 + softness param (blur mask) + trails.
- **P4 — Polish:** multi-stop gradients, response attack/release UI, presets
  (ship 6–8 spectacular stacked designs in the Rolodex), perf pass (layer-count
  budget, cache the per-band energy arrays per frame across layers — compute
  once, all layers read).

Perf note: energies are computed ONCE per frame per instance (all layers share
the banded arrays); Canvas stays the renderer (export parity beats GL here);
particle count budget ≤ ~64/band-window at xxhdpi.

## STATUS
- 2026-07-17: spec written.
- **P1 ✅ (`69756ac`)**: VizLayer model + 4-stage pipeline + GeometryMapper (linear/radial,
  spread/phase/mirror) + DOTS + ADD blend + layers JSON (transient field, IO splice, unknown
  emitters skipped). Legacy auto-wrap parity: JVM arithmetic trace 108/108 + on-device visual
  (all legacy presets render identically in WaveformDebugActivity).
- **P2 ✅ (`b944477`)**: PEAKS (K=8 taps × 66ms, stateless per §3), SQUARES, RING (radial-only),
  shadow params + blurred-translated shadow pass (never setShadowLayer), 4 stacked retrowave
  presets (joy_neon_stack / joy_pulse_ring / joy_retro_grid / joy_soft_glow). DEVICE-VERIFIED
  2026-07-17 on Note 9 via WaveformDebugActivity: peaks caps visible, mirror/phase/ADD all live.
- **P3 ✅ (`23bf053`, DEVICE-VERIFIED 2026-07-17 Note 9):** PARTICLES per §3 — spawn lattice = hash(b,i) phase over a
  1400ms life; birth time quantized to an absolute 175ms grid indexes the shared banded cache (via a
  new per-render-call TapSampler memo that peaks/trails/particles all share), so birth energy is one
  stable tap and the whole trajectory/alpha/size is a closed form of t. Softness 0..1 (NORMAL blur
  mask on the layer's own passes, map-cached per radius). Trails 0..6 (echo emit at t−k·66ms,
  decaying alpha — works on EVERY emitter, not just particles). New preset joy_ember_rise
  (soft bars + ADD particles + trailed line). Legacy parity: softness=0/trails=0/no-particles
  defaults collapse to the P2 arithmetic exactly; legacy rows in WaveformDebugActivity confirmed
  unchanged on-device alongside Ember Rise.
- **P4 ⏳ (pending review / device verify, 2026-07-17):** engine-side polish landed (UI still TODO).
  (1) **Multi-stop gradients** — `VizLayer.GradStop{pos,color}` list + `gradientAxis`
  amplitude|band; tolerant read (drop malformed, sort by pos, ≥2 else null). configureLayerPaint
  now takes (w,h,radial): amplitude = LinearGradient(0,0,0,h) (legacy direction), band-linear =
  (0,0,w,0), band-radial = SweepGradient(cx,cy). stops==null collapses to the exact 2-stop/solid
  path. (2) **Response attack/release** — `attackMs`/`releaseMs` 0..1000 (0/0 = off, same reference
  returned → legacy identical). `applyResponse` builds a PER-LAYER array via shared-TapSampler taps
  at t−k·50ms: attack = trailing moving average (A taps), release = peak-hold-with-envelope over the
  attack-smoothed taps (K taps), both capped at 6. Fed to shadow/glow/solid emits; shared peaks array
  still reads raw (documented — threading per-layer response through the once-per-frame peaks defeats
  the shared optimization). (3) **Perf** — 8-layer draw budget (MAX_DRAWN_LAYERS); drawBars reuses a
  single `barRect` field (no per-bar alloc). No time-keyed/cross-frame caches (determinism preserved).
  (4) **Flagged debt** — instance color/gradient override now also writes layer 0 (color +
  gradientStart/End, gradientStops nulled) so layered rendering shows it; sensitivity already flows
  through shared sampleHeights (comment added); filled-emitter close path now closes at
  originX+usedWidth / originX (spread/phase-aware; identical at spread=1/phase=0). (5) **4 presets** —
  joy_aurora_veil (soft filled band-axis teal→purple→pink + release + particles), joy_laser_grid
  (hard squares + trailed line, band-axis, ADD), joy_galaxy_ring (ring + band-gradient bars + mirror
  particles, for radial mode), joy_velvet_pulse (soft attack+release bars + dots, low-opacity ADD).
  Compile-green (compileDefaultDebugJavaWithJavac). Legacy parity by construction (every new field
  defaults to identity). STILL TODO: **Layers UI** (drawer section — add/dup/delete/reorder + per-layer
  props; FaditorEditorActivity + WaveformVisualizerSettingsSheet lane); response/gradient authoring UI.

# C4 design: one lane-ordered GL item compositor (STU-7, §9 step 3)

**Author:** Claude (Opus 5.5), STU-7 manager, 2026-11-22. **Status:** design approved by its author.
Codex builds it. The code decisions below are made; Codex may change one only by posting the reason in
the channel first. **This document is Claude's "go" for C4** under the card's rule: code that changes
behaviour with motion blur off may land **when the gates in §6 pass**. No further sign-off is needed.

Built on Codex's [C1 route report](C1_ROUTE_REPORT.md), whose `file:line` facts are not repeated here.

---

## 1. The problem in one paragraph

Studio has three drawing systems. They agree only by care.
- **Export:** the clip FX chain (`AdjustmentLayerGlEffect`), the PiP and adjustment walk
  (`ExportManager:7819-7888`), then a trailing text/sprite/caption block. That block is built from
  image/text runs (`GlImageOverlayEffect`, stage 2a) and Canvas-rasterised runs
  (`CompositeExportOverlay`, `SpriteBlendGlEffect`).
- **Preview:** the GL view (`FxPreviewTextureView`) for spine, PiP, adjustments and below-blend
  promotions, and **Android Views** for everything else.

So an adjustment layer never sees text, sprites or captions, whatever the lane order says. Motion
blur on an effect layer, "Layers Below", and any honest stack order all need one compositor that
draws every visual item in lane order, the same way in preview and export. This is also the
unfinished export-speed stage 2 ("one GPU pass per frame").

## 2. The design

### 2.1 Three pieces, all new small classes (`app/.../compositor/lane/`)
| Class | Job |
|---|---|
| `LaneProducer` | One visual item: spine clip, PiP, picture, text, sprite, avatar, visualiser. Gives (a) a **source texture** in its own space (decoder OES copy, cached bitmap upload, cached glyph raster, sprite atlas cell); (b) a **placement sampler** (`ItemMotionSampler`, already built); (c) its **local FX stack** (per-object effects); (d) blend mode, matte, opacity; (e) a **motion breaks** contribution. Producers know nothing about export or preview. |
| `LanePlan` | A pure-Java list, built from the timeline at time t: bottom-to-top producers and adjustment layers, in **one** ordering authority. Wraps `LayerPreviewController.orderedCompositedItems`, extended to every item type. Built in studiokit, JVM-tested. |
| `LaneCompositor` | GL. Draws a `LanePlan` into a working target: each producer through its own shutter (C2's `RigidTextureShutterPass` when blurred, the sharp quad otherwise) and local FX, blended in order. An **adjustment layer consumes the prefix target** (everything below it), runs its FX stack, and writes back. Captions draw last, always above. |

Both hosts call the same compositor:
- **Export:** a single Media3 `GlEffect`, `LaneCompositeGlEffect`, per piece. The piece's decoded
  frame is the spine producer's source.
- **Preview:** `FxPreviewTextureView` hands it the latched decoder textures. Promoted items stop
  being drawn as Views (one owner per item per frame; no double draw).

### 2.2 Rasters are cached; frames are not copied
- **Text:**
  - Rendered once per (text, style, size bucket) into a padded upright texture, as C2 already does
    for plain titles. It is redrawn only when the words or style change.
  - Text effects that animate pixels (TextFx, typewriter) stay as the producer's per-frame raster.
    Their cost is unchanged, but they now sit in lane order.
- **Sprites:** the atlas cell upload, as C2 does.
- **Avatars:** keep `AvatarItemPuppet`'s offscreen 1024 px Canvas render for now, uploaded as the
  producer's source. Moving avatars to a GL mesh is C5's job, not C4's.
- **No full-frame Canvas intermediate** in the compositor's domain. That is where the export-speed
  win comes from: the 11-12 profile says Canvas blits dominate.

### 2.3 Domain and fallback (certified, not guessed)
- **Domain:** `LaneCompositor.supports(item)` is an explicit allow-list. Anything outside it (a
  blend mode not yet ported, a warp, a matte combination) keeps today's route for that **piece**.
  The whole piece falls back, not one item, so the order is never split between two systems.
- **Logging:** every fallback logs its reason once per export (`LANE_FALLBACK <reason>`), so the
  allow-list grows by evidence.
- **Growth:** the allow-list is extended by porting routes one at a time, each with its own
  equivalence proof (§6).

## 3. Behaviour change for existing projects (decided)

**Decision: today's projects look exactly as they did. New projects get true layer order.**
Today a text, sprite or caption lane placed *below* an adjustment layer is not affected by it, in
preview or export. Fixing that changes the look of any old project that has that arrangement.
- **New field:** the project gets `layerOrder: "true"` (absent = `"legacy"`). New projects are
  created with `"true"`.
- **Old projects** stay `"legacy"`: the compositor reproduces the old order, with adjustments seeing
  only video and PiPs and the trailing block on top.
- **Switching:** one project setting row, "True layer order" (tick box, hover label, plain
  sentence: "Effect layers change everything below them, including titles and sprites"). It turns
  it on, with one undo step. Keep old behaviour selectable (owner rule).
- **Motion Blur's "Layers Below"** works fully only in true order. In a legacy project the card's
  inspector says so and offers the switch: an automatic guess with a one-tap way back.

## 4. The `MOTION` capability on an effect layer (C4's second half)

- **`FxEffectDef.Capability.MOTION`.** `FxCompiler.plan` treats a MOTION card as a **stack
  boundary**. Cards below it run as today. The MOTION card hands its scope to the temporal renderer.
  Cards above it run on its output. It never fuses, and it is never emitted for AGSL, like FEEDBACK
  today.
- **Interactive and Standard/High export, Layers' own motion: no reconstruction filter.**
  - The effect layer marks every producer in its scope as blurred with the card's shutter settings.
    Producers with their own Motion Blur keep theirs (Auto skips them, §3 of the spec).
  - Each producer then draws through C2's exact rigid shutter. Coverage is correct per item. Where
    two blurred items cross, they are composited in order per item, which is right unless their
    depth order changes inside the shutter window. That only happens when lane order is animated,
    and lane order is not keyframable today.
  - The velocity-buffer and reconstruction path from the spec (§7.1) is needed only for footage
    (C6) and deformables (C5).
- **Exact export:** `LaneCompositor.drawScopeAt(sampleTime)` N times into an accumulation target
  (linear-light, premultiplied, normalised weights), then the cards above. This reuses the same
  compositor at sample times, so it needs no extra code per item type. It is also the reference
  for the "High keeps crossings" gate (spec §7).
- **Mixed scope (footage inside):** until C6 lands, footage contributes zero motion (sharp video
  under blurred items). The inspector says "Footage motion: coming soon" rather than nothing. No
  dead knob.

## 5. Order of work (each a landing)

1. **C4a: `LanePlan` in studiokit.**
   - One ordering authority over all item types, legacy and true orders both.
   - JVM tests over real saved projects: legacy order reproduces today's draw order exactly, item
     for item (a negative control swaps one item).
2. **C4b: `LaneCompositor` in export**, behind an internal switch (`lane_compositor` in a debug
   preference, off).
   - Supported domain first: spine, PiP without matte, pictures, plain text, rigid sprites,
     captions on top, adjustment layers.
   - Equivalence harness (§6).
3. **C4c: switch export to the compositor by default** once §6 passes on the corpus. The old route
   stays as the certified fallback.
4. **C4d: preview on the compositor.**
   - Promote every supported item from Views into GL. Keep Views only for unsupported items, as
     whole-frame fallback.
   - Fix the C1 paused-PiP gap here (parity case).
5. **C4e: the "True layer order" project setting** and the new-project default.
6. **C4f: the `MOTION` capability**, the Motion Blur card on effect layers (Layers' own motion),
   and Exact per-sample scope export.

C3, the per-item preview blur, can land before C4d on the existing late picture rung, as Codex
planned. C4d then moves it onto the compositor without changing the look (parity test).

## 6. Gates (what "proven" means here)

- **Equivalence corpus.** At least 25 real project files, made by script from fixtures plus copies
  of the owner's project *structures*. Structures only: never his media and never his files. Cover
  every item type, blend modes, mattes, captions, transitions, loops, speed changes and adjustment
  layers, in legacy order.
  - Each frame is rendered by both routes into an FBO **before encode** and read back at three times
    per piece (start, middle, cut-adjacent).
  - **Pass:** max abs difference ≤ 1/255 per channel and mean ≤ 0.1/255, on the Snapdragon Note 9.
  - Every documented mismatch is fixed or listed as an allow-list exclusion.
  - Negative control: the harness must fail when one item's z-order is swapped.
- **Speed.**
  - Export of the 30 s reference project is no slower than today. The target is faster; report the
    `GL_SAMPLE` phases before and after.
  - Preview, blur off: P95 latch interval no worse than C1's baseline (50.38 ms) on the same bench.
- **Memory.** Cached rasters are bounded (an LRU budget, 48 MiB to start). No growth over 50 seeks.
- **Old projects.** Open, preview and export unchanged (the corpus covers this). JSON stays
  byte-identical when nothing was edited.
- **Owner-visible.** C4e's setting is the only new thing a user sees before C4f.

## 7. Traps

- **Media3 single-input effects.** `LaneCompositeGlEffect` receives one input texture (the
  decoded frame). Every other producer's texture is owned by the effect, uploaded on its GL thread
  and released in `release()`. Respect flush and end of stream (C1 report).
- **Matte peers and blend modes** that read the underlay need the prefix target as an input. That
  is natural here, but port each one with its own equivalence fixture.
- **Captions stay on `GlCaptionEffect`'s tight route** as the topmost producer. Do not re-raster
  them.
- **Program size on the Note 9:** one small program per producer kind and blend mode, compiled
  lazily and warmed before timed playback. Never one uber-shader.
- **Preview double-drawing** during migration: one owner per item per frame, asserted in debug
  builds.
- **Other lanes:** `ExportManager`, `CompositeExportOverlay`, `FxPreviewTextureView` and
  `OverlayVideoPreviewView` are shared. Claim them on the BOARD for each sub-step and post in the
  channel.

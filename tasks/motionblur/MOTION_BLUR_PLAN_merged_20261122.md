# Motion blur for Joy Creator Studio: merged plan (2026-11-22)

Merges two independent plans:
- **A** = `MOTION_BLUR_PLAN_opus-5.5_20261122.md` (Claude Opus 5.5)
- **B** = `MOTION_BLUR_PLAN_other-model_20261122.md` (another model's plan, inspected at `331164acf`;
  copied here from the owner's Downloads)

Where a section comes mostly from one plan, it is tagged [A], [B] or [A+B]. Nothing is built. When
the owner approves, this becomes job card **STU-7**, takes over FUTURE_PLANS S14, and gets a roadmap
row (post-launch unless he moves it).

---

## The short version (for the owner)

1. **Things Studio animates get exact blur.** Sprites, pictures, text, picture-in-picture, the main
   clip's moves and puppets all move by data Studio already has. Studio can work out the true smear,
   including **zooms** and swinging limbs. Each item is blurred on its own, so its edges stay clean.
   Nothing that stands still is touched, and a still frame costs nothing. [A]
2. **Your drawings stay yours.** A sprite's held drawings stay held. Studio never morphs one drawing
   into another (two mouth shapes, two hands) unless you ask for a smear and the drawings have
   matching points. You can protect eyes, a mouth or lettering on a puppet. [B]
3. **Filmed video** gets blur from motion measured in the pixels, the way ReelSmart does. It
   appears immediately in the preview at a lighter quality [A]. Studio then quietly analyses the
   clip in the background for the full quality, and export uses that [B]. Studio knows every cut,
   speed change, reverse and freeze, so it never smears across a cut. [A+B]
4. **One Motion Blur effect** goes on an item, a clip or an effect layer. It starts from presets
   (**Natural, Crisp, Heavy, Trailing**) plus a Mix slider [B]. The After Effects and ReelSmart
   controls sit under Advanced. [A+B]
5. **Proof is real, not "it runs":** sharp high-speed camera footage turned into a "true" blur to
   measure against, blind side-by-sides against ReelSmart and After Effects, and the two Note 9 chip
   versions held to pass/fail numbers over ten minutes of play. [B]

---

## 1. Facts from the code that shape the design (checked 2026-11-22)

| Fact | Where | Consequence |
|---|---|---|
| No motion blur effect yet, only the `tangentMotionBlur` transition | `GLTransitionCatalog.java:59`; FUTURE_PLANS S14 | New build |
| FX cards are GLSL ES 1.00 bodies. `FxCompiler.plan` fuses or splits them into full-resolution passes. There is no low-res or reduction pass, no second input, and FEEDBACK keeps one RGBA8 previous frame | `studiokit/.../fx/FxCompiler.java:143-192`, `app/.../fx/FxHistory.java:66-93` | Motion blur needs a new capability that runs its own pass graph. Previous final output **must not** be the blur input, because that produces trails and order dependence [B] |
| The preview GL context is **ES 2**, every Studio shader is `#version 100`, and the editor has no RGBA16F | `FxPreviewTextureView.java:1739`, `FxGlSource.java:45` | Fragment-only core with RGBA8-packed velocity. Float targets and compute are optional, and only where the bench shows a win [A+B] |
| **Preview:** sprites, pictures and text are Android Views above the GL surface | `OverlayVideoPreviewView.java:36`, `FxPreviewTextureView.java:1953-1968` | A blurred item is promoted into GL through the existing below-a-blend path [A] |
| **Export:** adjustment layers see only video and PiPs. Text, sprites and captions are composited after them | `ExportManager.java:7819-7860` | The effect layer's "Layers Below" scope needs a render-order fix first (§7). That fix also repairs every other adjustment-layer effect [A]. B's phase 0 proves the route [B] |
| Keyframes, sprite cells and puppet pins evaluate statelessly; time is whole ms | `KeyframeTrack.valueAt`, `SpriteFrameResolver.resolveCellAt`, `MeshPoseTrack.valueAt` | Items can be evaluated at any shutter instant. Add microsecond / `double` time and stop rounding to ms before evaluation [A+B] |
| **Avatars are stateful**: a forward-only `DiscreteState`, dangle baked at 33 ms, FABRIK at draw time | `AvatarItemPuppet.java:76-107`, `PuppetDangleBake.java:42` | Sample from cloned or replayed state; never advance the live simulation once per tap [A+B] |
| `TimeMap` already maps the timeline to the file and exposes cuts and seams | `model/TimeMap.java:51-193` | Extend `TimeMap` with exact sample-time mapping and a list of discontinuities. **No new timing authority** (A had proposed one; dropped) [B] |
| **OpenCV 4.13 is already a dependency** (motion detection uses it) | `app/build.gradle.kts:227`, `motion/domain/detector/OpenCvMog2MotionDetector.java` | OpenCV's DIS optical flow is available at no APK cost, so the estimator bake-off in §6 is cheap to run [new] |
| Adjustment layers refuse HDR | `AdjustmentLayerGlEffect.java:97-98` | SDR only at first. The UI says so; nothing is silently converted [B] |
| Note 9 driver: program size is the cost; uniform-switched code still costs | memory `note9-driver-skips-slider-branches` | Small programs compiled per option combination [A] |
| No GPU timer on Studio FX passes | (VisLab has `GpuTimer`) | Step 0 builds MotionBench [A+B] |

The Note 9 is a Snapdragon 845 / Adreno 630 (SM-N960U) or an Exynos 9810 / Mali-G72 (SM-N960F).
**Both** are release gates. [B]

---

## 2. One shutter, one clock [A+B]

- **Window.** T is the project frame period (a rational, so 23.976 and 29.97 are exact). The shutter
  opens at `t + Phase/360·T` and stays open for `Angle/360·T`. Presets set the phase: Centered =
  −Angle/2, Trailing = −Angle, Leading = 0. A 180° shutter stays 180° at every frame rate.
- **Samples.** Midpoint strata with normalised weights, so a still shot keeps exactly the same
  exposure. The shutter controls are evaluated at t; motion, transforms and animated inputs at each
  sample time.
- **Breaks in motion.** `TimeMap` lists them, and the sampler never invents a path across one:
  - hard cuts, trims and source gaps (long PTS gaps)
  - each side of a dissolve, evaluated independently: one shot never donates motion to the other
  - sprite cell swaps and viseme swaps
  - held-key jumps and authored teleports (a step keyframe)
  - a loop wrap, unless the producer marks the seam continuous; a continuous ping-pong keeps its
    true reversed path
  - tracking reacquisition

  At a break, the window is split, only the valid part is used and renormalised, and the result
  falls back to the centre frame if nothing valid is left.
- **Speed.** Freeze, slow motion, reverse and speed ramps all go through `TimeMap`. Footage motion
  is measured between real source PTS values and converted to project time.
- **Staleness.** Seek, loop, source change, backgrounding and GL context loss invalidate live
  history. Pending jobs carry a generation ID and stale ones are cancelled. Source-space analysis
  stays valid across a seek. Range export, a single still, full export and reverse-order inspection
  must give identical frames.

---

## 3. Controls [A+B]

The first sheet holds the preset, Shutter Angle, Shutter Timing and Mix, a Targets row (only when
there is a choice) and **hold to compare** (sharp vs blurred). Everything else goes under Advanced,
showing only what applies to the selected source. Angle, Timing/Phase and Mix keyframe through the
existing `"fx"+slot+"."+param` tracks. Analysis settings and quality are **configuration, not
keyframes**, so dragging a slider never starts a new clip scan. [B] Every control gets a hover
label; on/off uses tick boxes (owner rules).

| Control | Behaviour | From |
|---|---|---|
| Preset | **Natural** 180° centred (default), **Crisp** 90°, **Heavy** 270°, **Trailing** 180° ending at the playhead | B |
| Shutter Angle | 0–720°, with stops at 45/90/180/270/360. Above 360° needs both neighbouring frames, so it runs in export, in Inspect Frame, or on analysed footage | A+B |
| Shutter Timing | Centred / Trailing / Leading / Custom phase, shown as a shutter strip | B |
| Mix | 0–100 %, blending in premultiplied working colour without changing the window | B |
| Targets / source | Auto / Transform & rig / Footage motion / Both. **Both couples the motions into one trajectory per sample**, never two blur passes (no double counting). Protect text. On an Auto effect layer, producers that already have their own Motion Blur are skipped, and the inspector says so | B (A had v = J·v_flow + v_transform) |
| Preview quality | Auto / Fast / High. Auto shows a chip when footage is still analysing or quality has dropped. **Inspect Frame** renders the paused frame at export quality | B (A's Off/Draft/Good merged in) |
| Export quality | Standard (8–16 samples) / High (12–24) / Exact (true per-sample compositing, up to 32 and later 64 once validated) | A+B |
| Samples: minimum / adaptive limit | Auto by default; min ≤ max. Tap count follows streak length per tile or item | A+B (AE's controls) |
| Minimum motion | About 0.5 px. Below it the output is **bit-exact** to the input, so handheld jitter and IK wobble cause no softening | A+B |
| Max streak | Total streak length in px at a 1080-high reference (about 96 px in Natural), so it means the same in preview and export. It also guards against teleports | B (A had % of width) |
| Vector detail / Tracking sensitivity | Footage only: analysis scale, and how readily uncertain motion is accepted | B (A's Detail/Smoothness) |
| Tracking | Previous / Next / Both (ReelSmart v7's switch) | A |
| Camera move only | Fits one global motion to the footage and blurs with it, for pans and handheld shots. It cannot "jelly" people. It is also the fallback when local flow is unsure | A (B uses it as a fill) |
| Curved paths | A quadratic through three poses (Guertin 2014), on in High | A |
| Exposure shape | Box / Soft / Fade-out tail, normalised. A follow-up control | A+B |
| Highlight streaks | 0–100 %. Boosts clipped highlights before averaging in linear light, so 8-bit lamps streak instead of greying | A |
| Edges | Transparent (sprites, puppets) / Protect frame edge (footage: fall back to centre when there is no valid donor) / Extend / Mirror | B |
| Sprite behaviour | **Keep holds** (default). **Smear between drawings** is opt-in and **only** when the drawings carry anchors or a matched mesh. A plain filmstrip gets transform blur only. The existing frame blending stays a separate tool | B (replaces A's automatic cell flow) |
| Puppet behaviour | Protect parts or groups (eyes, mouth, lettering, a prop) through the existing part selection. Attachment and layering are preserved | B |
| Show motion | Overlays velocity, confidence and coverage. Used for debugging, teaching and Joybot | A+B |
| Later, pro | Foreground matte / tracking guide; imported vector passes with declared units, sign and time basis; analyse / re-analyse range | B |

---

## 4. Architecture [A, with B's seams]

```
studiokit/.../motion/            pure Java, JVM-tested
  MotionShutter      rational window, strata, weights, preset/timing maths
  MotionBreaks       discontinuity list fed by TimeMap + producers (cells, step keys, loops)
  ItemMotionSampler  item -> transform at microsecond time (wraps animated*At; double-time valueAt)
  SweptBounds        exact screen region each item covers over the window (+ streak halo)
  VelocityPack       12+12-bit velocity + 8-bit order/ID <-> RGBA8
  MotionBlurParams   controls, versioned; unknown settings preserved
app/.../motion/gl/               small GLSL programs, one per option combination, warmed before play
  ItemShutterPass    rigid items: one pass, N inverse-mapped taps into the item's own texture
  MeshVelocityPass   puppet mesh -> per-vertex displacement + part order into a layer velocity target
  TileMaxPass / NeighborMaxPass   1/16-res reductions (fragment)
  ReconstructPass    McGuire 2012 + Guertin 2014 gather, scissored to moving regions, keeps 2
                     motion candidates near overlaps where budget allows
  ScopeSamplePass    Exact export: composite the effect's scope per shutter sample, average premultiplied
app/.../motion/flow/
  FlowEstimator      interface; GpuPyramidFlow (fragment, DIS-style) and OpenCvDisFlow (CPU)
  FlowAnalyzer       background, range-first, cancellable; forward+backward, confidence
  FlowCache          versioned, quota-managed chunks; quantised + compressed
  LiveFlow           1/8-res backward flow from FxHistory's previous frame for instant preview
app/.../motion/
  MotionBench        GPU timer queries + presented-frame counters, temperature log
```

**Hooks into existing code** [B]:
- **`FxEffectDef` / `FxCompiler` / `FxPanel` / `AdjustmentLayer`:** a new `MOTION` capability. The
  planner hands its slot to the temporal renderer instead of fusing it, and registration, undo,
  keyframes and serialisation are reused.
- **`TimeMap`:** sample-time mapping plus breaks.
- **`FxLivePreviewController` / `FxPreviewTextureView`:** resolve active producers, schedule preview
  work and pool the GLES jobs.
- **`ExportManager` / `AdjustmentLayerGlEffect` / `SurfaceFrameReader` / `DecodedFrameCursor`:** a
  bounded look-ahead queue for neighbouring frames, and a scope render stage. A temporal export goes
  around fast paths it cannot use, and effects-off export is unchanged. Audio stays on the existing
  export clock. Media3 texture ownership, flush, end of stream and back-pressure are part of the
  adapter.
- **`SpriteFrameResolver` / `PuppetPoseResolver` / `AvatarParamTrack`:** a sampleable pose
  contract, independent of the web pilot.

Design choices:
- Fragment-only core, RGBA8 packing, `highp` for UV and velocity maths. [A]
- Half-float targets and ES 3.1 compute only where MotionBench proves a win, for example in flow
  analysis. [A+B]
- GPU-resident resources: pooled texture rings and FBOs, reuse only after fences, no render-thread
  readbacks. [B]

---

## 5. Producer 1: things Studio animates

**Rigid items** (sprite, picture, text, PiP, spine clip) [A]:
- One pass over the swept quad: `colour = Σ wᵢ · tex(M(sᵢ)⁻¹ · p)`, with the N inverse matrices
  computed on the CPU. Zoom gives a radial streak (zero at the pivot), and rotation and curved
  keyframe paths are exact.
- Parent links (`SpaceLink`) compose per sample.
- The result has soft alpha over the real background, so it needs no reconstruction filter and
  shows no halos.
- In the preview a moving item is promoted into GL, and drops back to a View when still.
- Captions are excluded by default.

**Perspective, corner pin and mesh warp:** projected vertex trajectories, tessellated where needed.
An extra segment is added where direction changes sharply. Where curvature, overlap or a topology
change breaks the approximation, real subframe renders of that region are used. [B]

**Puppets** [A+B]:
- `MeshVelocityPass` gives motion along the whole bend of a limb. A single pivot vector cannot
  represent an arcing hand. [B]
- Part order is used as depth, so a limb crossing the torso keeps the torso sharp.
- Protected parts are excluded.
- Cached atlas textures are reused; no bitmap is built per sample.

**Avatars** [A+B]:
- Pose hysteresis, spring and drag, IK and seeded secondary motion are made sampleable through
  fixed-step checkpoints or recorded resolved state. Sampling reads cloned or replayed state only.
- **While performing live**, only poses that have arrived are known. The preview uses a causal
  trailing blur from the previous resolved poses per item, and shows a "Trailing (live)" chip.
- **A recorded performance** uses the chosen timing and can do a centred exposure from nearby
  recorded poses.
- **Export** evaluates samples in ascending order from a cloned state. A test locks the case where
  a positive phase overlaps the next frame's window.

**Sprites** [B]:
- Holds are kept.
- A sprite that moves while holding a drawing blurs with its movement.
- A cell swap is a break.
- An opt-in anchored smear comes later (§9 step 7), and only with matched points.

---

## 6. Producer 2: filmed video [A+B]

**Two speeds, one data model.** Motion is measured in **source space** from real PTS, before
transforms and overlays are applied. Every vector has a documented sign, unit (source px) and time
interval.

- **Instant (preview), from A:**
  - `LiveFlow` computes backward flow at 1/8 resolution from the previous decoded frame, so footage
    blurs from the first play with no waiting.
  - Quality is "Fast": edge-aware upsampling, confidence-gated.
- **Analysed (preview High and export), from B:**
  - `FlowAnalyzer` runs in the background, visible range first.
  - Flow is computed forward and backward at 1/4 resolution, with overlapping patches and
    sub-pixel refinement.
  - Confidence combines forward/backward consistency, patch ambiguity, texture and photometric
    residual.
  - Flashes, duplicate frames, long PTS gaps, occlusions and whip pans are flagged.
  - The preview switches to analysed data as each range becomes ready.
- **Estimator bake-off** (B's idea; cheap because OpenCV is already in the app):
  - Candidates: `GpuPyramidFlow` (fragment) against OpenCV `DISOpticalFlow` (CPU, low-res luma).
  - Run them on the same clips on both Note 9s, comparing throughput (including the GPU→CPU copy)
    and quality against the high-speed references (§8). The winner ships, and the other stays as a
    test oracle.
- **Unsure motion:**
  - A reliable global fit fills the background; local motion wins for objects moving on their own.
  - Where motion stays uncertain, the blur is softened locally or the centre pixels are kept.
  - Vectors must not bleed across edges; inconsistent donors are rejected; static pixels pass
    through exactly.
- **Reconstruction:** McGuire/Guertin gather with depth/order and confidence. In High export the
  same flow and the neighbouring decoded frames build intermediate images in both directions where
  reliable. Colour and coverage are warped together, and disoccluded donors are rejected.
- **The cache** (B's numbers check out: about 1.24 MiB per 1080p frame pair raw, which comes to
  about 11 GB for 5 minutes):
  - Quantised, compressed chunks under a 256 MiB starting quota, keeping only a small window in
    RAM.
  - Keyed by source fingerprint, orientation/crop, PTS pair, estimator revision, detail and
    resolution.
  - A colour or shutter change reuses the flow; trim and retime remap it.
  - Nothing goes into project JSON.
  - Eviction is "least recently viewed"; a re-analysis when needed is fine, because LiveFlow
    covers the gap.
- **Honest limits:** blur already in the footage is added to, not removed. Heavy compression,
  reflections, repeating textures and fully hidden surfaces limit any two-frame estimate. Those go
  into the help text.

---

## 7. The effect layer and compositing [A+B]

- **Prerequisite (its own small card):** give adjustment layers the stack below them in export,
  including text, sprites and captions in lane order, and do the same in the preview by promoting
  the covered items into GL. This fixes every adjustment-layer effect, not just this one. Until it
  lands, an effect layer's scope is limited to video and PiPs, and the inspector says so.
- **Interactive:**
  - Isolated producers use their own exact blur, with scissored bounds.
  - Mixed scenes get a velocity field (packed velocity + order) written as the scope composites,
    then a tile-based limited-tap gather.
  - Affected pixels outside the original silhouette are included, and two candidates are kept near
    overlaps.
  - With no usable ordering, or strongly curved motion, a few real subframe passes run over the
    affected bounds when the budget allows.
- **Exact export, from B:** the selected scope is evaluated and composited **per shutter sample**,
  respecting order, clipping, masks and blends at each sample. The premultiplied results are
  averaged, and the average is placed at the effect's position in the stack. Overlays above the
  effect stay outside the temporal passes. Glow, grade and other effects keep their before/after
  order. This is also the reference that the interactive path is judged against.
- **Colour:** linear-light, premultiplied accumulation with normalised weights. Highlight streaks
  are optional. SDR only at first, and transparent art is in the first proof.

---

## 8. Proof [B's bar, A's scenes and fixtures]

**Proof scene** (B): a zooming title plus a jointed puppet over moving video. A translation-only
demo proves nothing.

**References:**
- **Authored art:** brute-force accumulation (128–256 samples, convergence checked) in the JVM
  harness and on the GPU. Compare trajectory, silhouette coverage and alpha at the edges. [A+B]
- **Footage:** capture sharp 120/240 fps clips and integrate them by timestamp into 24/30 fps
  "true" exposures. Compare the added blur with the sharp low-frame-rate input. [B]
- Compare with ReelSmart and After Effects Pixel Motion Blur on the same clips if licences are
  available. Use blind phone and desktop crops for double edges, thickness, halos, lost detail and
  direction. **A/B at least 20 shots: ≥ 80 % judged comparable or better, zero severe cut or alpha
  failures.** Calibrate on the proof set before locking the bar. [B]

**Fixtures**, each shown failing on the wrong behaviour first (break-test rule) [A+B]:
- still frame (bit-exact)
- slide fast, then slow (length, jitter floor)
- logo zoom 1×→3× (radial)
- spoked wheel (curved)
- arm across torso (no halo)
- curled Bézier arm and tracked hand arc
- alternate head/hand drawings (no morph)
- 8 fps flipbook (holds kept)
- fence pan, person crossing, flash, low-light noise, dolly zoom, whip pan
- hard cut and dissolve (no cross-donation)
- 2×, reversed, frozen, speed ramp
- loop and ping-pong
- step-key teleport
- night lamp pan (streaks)
- transparent art over black and over white (no fringe)

**Host tests** [B]: fractional clocks, exposure normalisation, no cross-cut or stale input,
masking, variable speed, PTS gaps, loops, hold keys, plus the existing FX and time-map suites.

**Device tests** [B]: play/scrub parity, preview High vs export at the same resolution and clock,
low storage, cancellation, GL context recreation.

**Note 9 gates** [B], on **both** SM-N960U and SM-N960F, release or profileable builds, with OS and
driver versions recorded:

| Gate | Target |
|---|---|
| Warm playback | 1080p30 source, 720p30 preview, with one analysed clip, an 8-part puppet or sprite group, one still title and one effect layer. **≥ 99 % of frames presented over 10 min**, with no repeated multi-frame stalls. Report P50/P95/P99 against blur-off |
| Added GPU cost | **≤ 5 ms P95 at 720p**, ≤ 48 MiB extra working memory (texture bytes and platform memory tracked apart); analysis tracked separately |
| Still frame | **0 ms** added (A: passes skipped, bounds empty) |
| Seek | First correct image ≤ 150 ms P95 on a cached short-GOP fixture; uncached reported separately; the first visible second analysed within 2 s without blocking |
| Export (30 s reference) | Standard ≤ 2× clip length, High ≤ 4×, flow cached; cold analysis reported apart; output independent of preview quality |
| Reliability | No stale frames after a seek or cut, exact no-op when off or still, no alpha fringes, no memory growth over repeated seeks, cancels or context loss, no audio drift or dropped timestamps |

Measured with presented-frame counters, EGL present timestamps where available, validated GPU timer
queries and temperature logs (memory `probe-phone-temperature`). Android 10 lacks Macrobenchmark's
frame-overrun metric.

**Under load:**
1. Pause background analysis.
2. Reduce taps, then preview scale, with hysteresis.
3. As a last resort, show sharp regions and a status chip.

Pause and Inspect Frame always show the chosen look. Export never downgrades: it tiles with a halo
the size of the streak cap if memory is short, or reports the limit. [B]

---

## 9. Steps (in order; each fits a session)

0. **Route proof and bench** [B+A]: timing traces, MotionBench, and the shutter core (`MotionShutter`,
   double-time `valueAt`, `VelocityPack`, `MotionBreaks` on `TimeMap`). Prove one fixture through
   the live GL path and the real export path: a cut, a seek, a transparent moving layer, a rotated
   input and a fast path refusing a temporal export. Lock the time contract.
1. **Things Studio animates, export first** [A+B]: `ItemShutterPass` for rigid items, the per-item
   tick box, the shutter sheet with presets, Inspect Frame, and the accumulation reference.
   Behind a feature flag.
2. **Same in preview** [A]: promote moving items into GL, bench lines. **Owner look #1**: a
   mix-and-match page (presets, Mix, exposure shapes, streaks) plus a phone session; defaults
   locked as §K.
3. **Effect-layer render order** (prerequisite card), then the `MOTION` capability with Layers' own
   motion only, Auto skipping producers that already blur, and Exact per-sample scope export. [A+B]
4. **Puppets and avatars** [A+B]: mesh velocity, protected parts, sampleable pose contract, the live
   trailing chip, recorded centred exposure.
5. **Footage** [A+B]: high-speed reference shoot; LiveFlow; estimator bake-off on both Note 9s;
   FlowAnalyzer + FlowCache; footage through the clip and the effect layer. **The full premium
   rollout needs this step to pass.**
6. **Finish** [A+B]: curved paths, highlight streaks, Camera move only, Show motion; the 20-shot
   A/B; help topic, Joybot tip and tool, STATE.md row. **Owner look #2** on his own clips (Note 9
   and Note 20).
7. **Later** [B]: anchored sprite smears (only after visual comparison proves they keep the art),
   matte and guide tools after measuring automatic failures, SpriteLab and Avatar Studio preview
   reuse, vector import, HDR.

**Model use:** Opus for steps 0, 1, 3 and 5 (maths, render route, flow). Sonnet at medium for UI,
plumbing, help and bench screens. Fable is not needed; it stays in reserve for a Mali-only flow or
precision stall.

## 10. Risks

| Risk | Mitigation |
|---|---|
| Adjustment layers blind to text and sprites (export) and to Views (preview) | Prerequisite card; items have their own blur first |
| ES 2 preview context | Fragment-only, RGBA8 packing, highp checked |
| Flow quality on Mali, flat or noisy footage | Bake-off, confidence, global fallback, local softening |
| Cache churn (11 GB vs 256 MiB) | LiveFlow covers gaps; range-first analysis; compression measured |
| Avatar state | Cloned/replayed state; live trailing chip; test-locked |
| Art damage on sprites | Holds default; smear only with anchors; alternate-drawing fixture |
| Thermal | Ordered load fallback; sustained-hot runs in the gates |
| Exact export cost | Only for effect-layer scope; Standard/High default; ≤ 4× gate |

# Motion blur for Joy Creator Studio: plan (Claude Opus 5.5, 2026-11-22)

Written for comparison against other models' plans. Nothing is built; no job card or roadmap row
exists yet. When the owner picks a plan, it gets the next STU card (STU-7) and the FUTURE_PLANS S14
row moves to it.

---

## The short version (for the owner)

1. **Most of the motion in Studio is motion Studio already knows about.** Sprites, pictures, text,
   picture-in-picture, the main clip's moves and puppet pins all come from keyframes, and the app can
   ask "where is this thing at any instant?" That means those items can get **exact** motion blur:
   not a guess from the pixels, but the real smear the object makes as it slides, spins and
   **zooms** (your signature move, which ordinary directional blur cannot do). Each moving item is
   blurred on its own layer, so its edges stay clean against whatever is behind it. The usual
   motion-blur faults (halos, the background smearing into the object) do not occur in this case.
2. **Filmed video** gets blur where nothing is keyframed. Studio measures the motion in the pixels
   (optical flow, like ReelSmart Motion Blur) and smears along it. Studio has one advantage RSMB does
   not: it knows exactly where every cut is, how fast every clip plays and whether it is reversed, so
   it never smears across a cut and never gets the speed wrong.
3. **One Motion Blur effect, in two places:** a tick box on any item ("this thing gets motion
   blur") and a **Motion Blur card** you can put on an adjustment layer or a clip, with After
   Effects' controls (shutter angle, phase, samples) plus RSMB's (tracking direction, sensitivity,
   ignore small jitter) and a few extras (highlight streaks, curved paths, "camera move only").
4. **Note 9 floor:** the preview uses a cheap version that only works where something is actually
   moving. Export uses the best version, which may take longer, because nobody watches an export
   frame by frame.
5. **Order:** exact blur for keyframed items first (the biggest win, lowest risk), then puppets
   and sprite flipbooks, then the adjustment-layer version, then filmed video.

---

## 1. What exists today (checked against the code 2026-11-22)

| Fact | Where | Consequence for this plan |
|---|---|---|
| No motion blur effect; only the `tangentMotionBlur` transition | `GLTransitionCatalog.java:59`; FUTURE_PLANS S14 | Built from scratch, but the 08-22 assessment (`archive/HANDOFF_20260821_STATE.md:180`) already proposed velocity blur for preview and accumulation for export |
| FX cards are GLSL ES 1.00 bodies compiled by `FxCompiler`. Capabilities are POINTWISE, GENERATOR, SAMPLER, UV_REMAP and FEEDBACK; each SAMPLER or FEEDBACK card gets its own full-res pass, and kernels are fixed at 17 taps | `studiokit/.../fx/FxEffectDef.java:30-88`, `FxCompiler.java:143-192` | Motion blur needs a new capability: several passes at different resolutions plus an extra input (velocity). The planner has neither today |
| The preview GL context is **ES 2**, and every Studio shader is `#version 100` | `FxPreviewTextureView.java:1739`, `FxGlSource.java:45` | Design for ES 2 first. Do not depend on float render targets or compute shaders in Studio's preview |
| Studio never uses RGBA16F or a linear-light pipeline; Media3 programs run with high-precision colour off | `AdjustmentLayerGlEffect.java:127` | Linear light happens inside the shader's registers (decode each tap, sum, encode), with no float storage |
| Feedback history is **one** previous frame in RGBA8, reset after a 250 ms gap | `app/.../fx/FxHistory.java:26,66-93` | Live optical flow has the previous frame for free (backward tracking). Next-frame tracking only exists in export |
| Keyframes are stateless: `KeyframeTrack.valueAt(long ms)`. Sprites, text, images, PiP and the spine pose all have `animated*At` functions | `KeyframeTrack.java:64-84`, `SpriteOverlayItem.java:392-431`, `Clip.spinePoseAt` | Any item's transform can be evaluated at any sub-frame time, which makes exact per-item blur possible. Time is whole milliseconds, which is too coarse for 32 samples at 60 fps, so a `double` overload is needed |
| Sprite cells (`SpriteFrameResolver.resolveCellAt`) and puppet pins (`MeshPoseTrack.valueAt`) are stateless. **Avatar items are not**: a `DiscreteState` steps forward only, dangle is pre-baked at a 33 ms step, and FABRIK runs at draw time | `AvatarItemPuppet.java:76-107`, `PuppetDangleBake.java:42` | Puppets can be sampled at t−dt. Avatars need care (see §6) |
| In the **preview**, sprites, pictures and text are Android Views above the GL surface; only items promoted "below a blend" enter GL | `OverlayVideoPreviewView.java:36`, `FxPreviewTextureView.java:1953-1968` | A blurred item must be promoted into GL, reusing the existing below-a-blend path |
| In **export**, adjustment layers see only video and PiPs. Text, sprites and captions are composited after them | `ExportManager.java:7819-7860` | An adjustment layer cannot blur sprites in export today. This ordering limit already affects every effect, and it is a prerequisite for the full adjustment-layer version (§7) |
| Note 9 driver: program size is the cost. Code switched off by a uniform still costs; two small passes beat one big one | memory `note9-driver-skips-slider-branches`; `SPEC_ADJUSTMENT_LAYERS_FX.md:593` (budget ≤ 8 ms added per frame) | Compile a separate small program per option combination; never one big program with uniform branches |
| No GPU timer measures Studio's FX passes | `GpuTimer` exists in VisLab only | Building a Studio GPU bench is step 0 |

One correction to the Gemini notes: the Note 9 is a Snapdragon **845** (Adreno 630) or Exynos 9810
(Mali-G72 MP18). There is no "Snapdragon 830". Both variants need testing. Whichever one the owner
holds is the floor that counts, and the other is worth one rented or borrowed check before launch.

---

## 2. The idea that drives the design: three motion sources, one shutter

Every motion blur is the same integral: what the camera would record while its shutter is open. What
differs between the three cases is how much we know about the motion:

| Source | What we know | Best method | Edge quality |
|---|---|---|---|
| **A. Rigid items** (sprite, picture, text, PiP, the spine clip's own moves) | The exact transform at any instant | **Analytic shutter integration** in one pass: for each output pixel, map it back into the item's own texture at N instants and average. Rotation, zoom and curved paths come out exact | Perfect: done on the item's own layer, with alpha |
| **B. Deforming items** (puppet mesh, avatar, sprite flipbook cell changes) | Exact vertex positions at two instants (mesh). For flipbook cells we know which cells are involved but not how pixels move between them | **Per-layer velocity raster + reconstruction**: draw the mesh with each vertex's displacement as its colour, then smear the layer along it. Flipbooks get a **cell-to-cell flow** worked out once per sheet | Very good: inside one layer, ordered by part depth |
| **C. Filmed video** | Only the pixels | **Optical flow + McGuire/Guertin reconstruction** (tile max, neighbour max, depth-aware gather) | Good. Occlusion edges need care |

A **shutter model** shared by all three (`MotionShutter`, pure Java in studiokit) turns the
controls into a time window and a sample schedule:
`window = [t + phase/360 · frame, t + (phase+angle)/360 · frame]`, with the frame length taken from
the **project** frame rate. A per-item override is allowed for a 12 fps sprite in a 30 fps project
(§6.3).

The adjustment-layer effect (§7) adds the three together into one screen-space velocity field. This
part is standard work: within a clip, its transform's velocity is added to the footage flow pushed
through that transform (v = J·v_flow + v_transform).

---

## 3. Parameters (After Effects + RSMB + game engines, with one Joy Creator rule each)

Every control is keyframable (the existing `"fx"+slot+"."+param` tracks) and each gets a hover label.
Defaults look like a film camera: 180° shutter, centred.

**Shutter**
| Control | Range / default | Source | Note |
|---|---|---|---|
| Shutter angle | 0–720°, 180° | AE comp setting | Above 360° only in export, where both neighbouring frames are available |
| Shutter phase | −360…+360°, −90° (centred) | AE | Lets the blur trail behind (phase 0) or straddle the object |
| Shutter shape | Box / Soft / Fade-out tail | Arnold-style shutter curve, Unreal | Soft = a weighted window, which reads less "smeared glass". Fade-out tail = a comet streak; artists love it for sprites |
| Strength | 0–200 %, 100 % | RSMB "Blur amount" | Scales the window. Above 100 % is a stylised exaggeration |
| Max length | 1–25 % of frame width, 8 % | Unreal "Max" | Caps cost and stops a teleport keyframe from becoming a full-screen smear |
| Ignore small motion | 0–4 px, 0.5 px | RSMB "Motion threshold" (sort of) | Stops a wobbly handheld frame or IK jitter from softening the whole picture. Below the threshold the output is **bit-exact** to the input |

**Quality** (preview and export are set separately, AE style)
| Control | Values | Note |
|---|---|---|
| Preview quality | Off / Draft / Good | Draft = half-res gather, 6 taps. Good = full res, 10–12 taps. Off still shows blur when paused on a frame (render that frame at Good) |
| Export quality | Good / Best / Exact | Best = 24 taps plus curved paths. Exact = true sub-frame accumulation for kinds A and B (up to 64 renders of the item; costly, but it is ground truth) |
| Adaptive samples | on | Samples scale with blur length per tile: a 2 px smear takes 2 taps, a 60 px one takes the maximum |
| Noise instead of banding | on | Interleaved gradient noise jitter (Jimenez) on the tap offsets, so low tap counts give fine grain instead of visible copies |

**Motion source** (Motion Blur card only)
| Control | Values | Note |
|---|---|---|
| Use | Layers' own motion / Pixels / Both (default) | Layers = analytic only, no tracking; fast and exact |
| Tracking | Previous / Next / Both | RSMB v7's switch. In the preview Previous is always available; Next is export or paused frames only |
| Detail | Low / Medium / High | Flow resolution (1/8, 1/4, 1/2) |
| Smoothness | 0–100 % | RSMB "Vector smoothness": how much the flow is regularised |
| Camera move only | off | Fits one global motion (homography) to the flow and blurs with that. Robust for pans and handheld footage; it never "jellies" moving people. A distinctive extra, and very cheap |
| Curved paths | on in Best | Guertin 2014: a quadratic through t−1, t, t+1. Matters for wheels, swinging arms and orbiting cameras |

**Look**
| Control | Values | Note |
|---|---|---|
| Linear light | on | Decode sRGB per tap, average, re-encode. Done in registers, with no float textures |
| Highlight streaks | 0–100 %, 30 % | Clipped highlights in an 8-bit video are boosted before averaging, so lamps and sparks streak like film instead of greying out. RSMB v7's advantage here comes from float input; this is the honest 8-bit equivalent |
| Limit to | whole frame / a mask / a layer's shape | Uses Studio's masks (`MaskSdf`) |
| Edges | Clamp / Transparent | Transparent lets a blurred item feather outward on its own layer |

**Seeing it**
| Control | Note |
|---|---|
| Show motion | Overlays the velocity field as colour (direction = hue, speed = brightness). Debug for us, teaching tool for users, and Joybot can point at it |

**Per-item tick box** (in each item's drawer; tick box, not switch, per the owner's rule): "Motion
blur", one Strength slider, and "Use project shutter" (on). A project-level row in Studio settings
holds the shutter defaults and the preview quality.

---

## 4. Architecture (modular, small classes)

```
studiokit/.../motion/              pure Java, JVM-tested
  MotionShutter        angle/phase/shape -> window + weighted sample schedule
  ItemMotionSampler    item -> affine at double time (wraps the animated*At functions)
  SweptBounds          conservative screen rect an item covers over the window
  VelocityPack         12+12-bit velocity + 8-bit depth <-> RGBA8 (no float targets needed)
  FlowSchedule         cut-, speed- and reverse-aware: which source frames feed flow, and the scale
  MotionBlurParams     the controls above, keyframe tracks
app/.../motion/gl/                 small GLSL programs, each compiled per option combination
  ItemShutterPass      kind A: one-pass inverse-transform integration over the swept quad
  MeshVelocityPass     kind B: draw the puppet mesh with per-vertex displacement -> velocity RGBA8
  TileMaxPass          velocity -> 1/16-res max (two separable 4x4 reductions, fragment shader)
  NeighborMaxPass      3x3 max of tiles
  ReconstructPass      McGuire 2012 gather, Guertin 2014 extensions, scissored to moving tiles
  FlowPyramidPass      luminance pyramid
  FlowSolvePass        DIS-style patch inverse search per level, then densify
  FlowRefinePass       forward/backward consistency -> confidence; joint-bilateral upsample
  MotionBench          Note 9 timing harness (step 0)
```

Choices that go against the Gemini notes, on purpose:

- **No compute shaders.** Studio's preview is an ES 2 context, and nothing in Studio uses compute.
  The reductions that would want compute (tile max) run at 1/16 resolution, where a fragment pass is
  far below a millisecond. Compute would add a second GL context type and a Mali barrier risk to
  save nothing measurable. If MotionBench later shows otherwise on ES 3.1 export, the reduction
  can move to compute there alone.
- **No float render targets.** The velocity is packed into RGBA8: 12 bits per axis gives ±128 px at
  1/16 px steps, plus an 8-bit layer depth. That is plenty, and it is identical on Adreno and Mali,
  with no `EXT_color_buffer_half_float` dependency. `highp` is used for UV and velocity math in
  every fragment shader. Both Note 9 GPUs support highp in fragment shaders; the bench checks it.
- **The CPU knows where motion is.** For kinds A and B, `SweptBounds` gives the exact screen
  rectangles that move. The reconstruction is drawn **only over those rectangles**, with a
  pass-through elsewhere, so a still frame costs nothing at all. This beats per-pixel early-outs,
  which the Note 9 driver does not skip.
- **Separate small programs instead of option uniforms** (the Note 9 program-size rule). Variants
  are keyed by (taps, curved, linear, highlights, shape) and compiled lazily, then cached.

### How it plugs into the FX system
A new capability, `MOTION`, in `FxEffectDef`. The planner (`FxCompiler.plan`) treats a MOTION card
as a **subgraph**: velocity gather, tile max, neighbour max and reconstruct, with their own
resolutions. It is not a single SAMPLER pass. `AdjustmentLayerGlEffect` already ping-pongs between
FBOs per pass; it gains low-res targets and a second input (velocity). Preview and export compile
the same source, as the FX system already does, so they cannot drift apart. The AGSL preview
emitter does not get MOTION; motion runs on the GLSL path only, as FEEDBACK does today.

---

## 5. Kind A in detail: exact blur for keyframed items (the first deliverable)

For an item with transform M(s) over the shutter window, and an output pixel p, the item's colour
is `(1/N) Σ w_i · tex(M(s_i)^-1 · p)`. That is one fragment pass over the item's swept quad, with N
taps into **the item's own texture**, which is small and cached, so the taps are cheap. The CPU
precomputes the N inverse matrices as a uniform array, so the shader does no per-pixel matrix work
beyond a 2×3 multiply per tap.

- **Zoom and rotation are exact**, as are curved paths: the keyframe interpolation is sampled, not
  linearised.
- **Edges are right by construction.** The blurred item has soft alpha and is composited over the
  real background, the way a camera sees a moving object in front of a still wall. This case needs
  no reconstruction filter.
- **Parented items** (`SpaceLink`) compose their parents' transforms per sample, so an item riding
  on a moving parent blurs with the parent's motion.
- **Text** blurs as its rendered texture. **Captions** are excluded by default (readability); a tick
  box turns them on.
- **Adaptive N:** N = clamp(ceil(max corner travel in px / 1.5), 2, quality max), so a slow drift
  takes 3 taps and a whip pan takes 16.
- **Preview:** an item with blur on is promoted into the GL chain through the same mechanism as
  "below a blend" (`FxPreviewTextureView.java:1953`). While it stands still it drops back to the
  View path, and so costs nothing.
- **Exact (export):** the same shader with N up to 64 is the ground truth. A CPU version in the JVM
  harness renders the same integral for tests.

## 6. Kind B in detail: puppets, avatars and sprite flipbooks

### 6.1 Puppet meshes
`MeshPoseTrack.valueAt` gives vertex positions at the window's start and end (and its middle, for
curved paths). `MeshVelocityPass` draws the mesh once with the displacement as vertex colour
(packed), with part draw order as depth, into a small velocity target the size of the layer's
bitmap. `ReconstructPass` then smears **that layer only** before it is composited. A swinging arm
smears; the body it passes in front of stays sharp, because the depth comparison keeps the arm's
blur from bleeding the body. Dangle is pre-baked at 33 ms, so samples inside a frame interpolate the
bake.

### 6.2 Avatars (stateful)
`AvatarItemPuppet` steps a forward-only `DiscreteState`, and going back in time resets it. Plan:
- **Preview:** keep the **previous frame's resolved vertex positions** per avatar item (one array
  per item, already computed by the frame before) and use them as the window start. This gives
  backward tracking only, but it matches what the user is seeing, and needs no rewinding.
- **Paused or scrubbed:** fall back to the stateless part of the pose (pins and keys) at t−dt, with
  dangle from the bake. This is slightly less exact, but never a reset.
- **Export:** time only moves forward, so sub-samples are evaluated in ascending order inside each
  frame. The one ordering trap (phase > 0 makes the next frame's window start before this frame's
  end) is handled by evaluating samples from a cloned state. A test locks this.

### 6.3 Sprite flipbooks ("smooth out sprite animations")
A sprite at 8–12 fps in a 30 fps project jumps between cells. Motion blur from keyframes does not
touch that: the cell changes, but the transform does not move. When the sheet is imported (and
lazily for old sheets), Studio computes **cell-to-cell flow** once and caches it as derived data
beside the sheet (a regenerable cache, not a format field). A per-sprite **Cell blend** control
offers: Off / Crossfade / Flow. Flow smears each held cell toward the next one over the shutter
window at the moment of the cell change, so a flipbook reads as fluid motion without new frames.
This is cheap: the sheets are small and the flow is computed once. It also serves SpriteLab's
preview later.

---

## 7. Kind C and the Motion Blur card on an adjustment layer

### 7.1 Velocity field for the composite
The layers under the adjustment layer write packed velocity and depth (layer order) into a velocity
target as they composite: kind A items analytically, kind B from their mesh, and video from flow.
The reconstruction then runs once on the composite: McGuire 2012 TileMax and NeighborMax with
depth-aware soft comparison, Guertin 2014's centre-weighted samples and curved option, and
interleaved-gradient jitter.

**Prerequisite (a separate, small job):** in export, text, sprites and captions composite *after*
adjustment layers (`ExportManager.java:7856`). Until that ordering is fixed, the card blurs video
and PiPs in export, while items rely on their own tick box. The same ordering bug affects every
effect on an adjustment layer, so it should be fixed for its own sake. Kind A and B items do not
depend on it.

### 7.2 Optical flow on the Note 9
- **Preview (live, backward):** FxHistory already holds the previous frame. Build a 3-level
  luminance pyramid from 1/8 resolution (240×135 for a 1080p source), run a DIS-style patch inverse
  search per level (Kroeger 2016; it is a classical method and the paper is open), densify, then
  upsample with a joint-bilateral filter against full-res luma so velocity edges follow picture
  edges. The tiny resolution makes this small: its cost is a MotionBench line item, with a budget of
  ≤ 3 ms on Adreno 630.
- **Export (forward + backward, higher detail):** flow at 1/4 or 1/2 resolution in both directions,
  with a consistency check: where forward and backward disagree, the pixel is an occlusion, so its
  velocity is taken from the background side and its confidence lowered. Low-confidence pixels
  fall back to the "camera move only" global fit, never to garbage. Export has both neighbouring
  frames, because decoding is sequential.
- **Studio knows things RSMB has to guess:**
  - **Cuts:** `FlowSchedule` never pairs frames across a clip boundary or a transition's two
    sides. At a clip's first frame it switches to Next, and at its last frame to Previous.
  - **Speed:** the flow between source frames is scaled by the clip's playback speed (2× speed
    doubles the smear) and flipped for reversed clips. Freeze frames get zero.
  - **Clip transforms:** the footage flow is pushed through the clip's own moving transform, and
    the transform's analytic velocity is added on top.
- **Caching:** none at first, because live flow is cheap at 1/8 resolution. A per-clip flow cache
  (keyed through `RenderCacheKeys`) is added only if MotionBench shows the export flow is the
  bottleneck.

### 7.3 Deliberately not in this plan
- **Neural flow (RAFT, SEA-RAFT) and frame synthesis (RIFE):** too heavy for the Note 9 floor, and
  each needs its weights' licence checked. A possible later "Exact for video" export option on
  newer phones, behind a device-capability check, and only after the classical path is proven.
- **Rolling-shutter simulation, depth-of-field interplay:** these are separate effects.

---

## 8. Performance budget (Note 9, preview at the editor's surface size)

These are **targets to measure, not measurements**. MotionBench checks every line on both Note 9
GPUs where available, with phone temperature logged (memory `probe-phone-temperature`).

| Situation | Target added per frame |
|---|---|
| Nothing moving | **0 ms** (passes skipped; bounds empty) |
| One kind A item moving, 12 taps over its swept quad | ≤ 1.5 ms |
| Puppet layer (mesh velocity + per-layer reconstruct) | ≤ 2.5 ms |
| Adjustment card, layers only, half-res gather (Draft) | ≤ 3 ms |
| Adjustment card with live flow (Good) | ≤ 6 ms (inside the spec's 8 ms) |
| Export Best, 1080p | No live budget; must not slow export by more than 2× |

If a line misses its target, the order of retreat is: fewer taps, then half-res gather with
bilateral upsample, then Draft by default. Exact blur for keyframed items is never removed, because
it is the cheap one.

---

## 9. Proof (how we know it is good, not just running)

**Ground truth.** The JVM harness renders kind A and B scenes by brute-force accumulation (256 sub-frames), and
every faster path is compared to it (PSNR/SSIM thresholds locked per scene). Per the break-test
rule, each test is shown failing on the wrong behaviour first.

| Scene | What it catches |
|---|---|
| Still frame, blur on | Output bit-exact to input (no silent softening) |
| Sprite slides 40 px/frame, then 0.3 px/frame | Correct length; jitter threshold |
| Logo zooms 1×→3× in 10 frames | Radial smear from zoom (the owner's move) |
| Wheel with spokes spinning | Curved paths; no straight-line smear |
| Puppet arm swings in front of torso | Torso stays sharp; no halo |
| Flipbook at 8 fps in a 30 fps project | Cell blend smooths without ghosting |
| Pan across a fence (filmed) | Flow stability; no jelly |
| Person crossing a still background (filmed) | Occlusion handling; background not dragged |
| Cut between two moving shots | No smear across the cut |
| Clip at 2×, reversed, frozen | Smear doubled, flipped, zero |
| Lamp at night, panning | Highlight streaks stay bright |

**Phone proofs (agent, then owner):** before/after screenshots and a screen recording per kind
(`tasks/screens/motionblur-*`). Export is checked frame by frame against the editor's preview at
Good. MotionBench numbers go into the card. The owner looks at a **mix-and-match page** with
shutter shapes, streak amounts and Draft/Good/Best side by side, then picks the defaults. Those
picks become the spec's §K.

---

## 10. Steps (in order; each one fits a session)

0. **MotionBench + shutter core.** Studio GPU timer, `MotionShutter`, a `double`-time overload for
   `KeyframeTrack.valueAt`, `VelocityPack`, the JVM ground-truth renderer. Tests with negative
   controls.
1. **Kind A in export.** `ItemShutterPass` for sprites, pictures, text, PiP and the spine clip,
   with the per-item tick box. Golden tests against ground truth. Note 9 export check.
2. **Kind A in preview.** Promote moving blurred items into GL, drop them back when still. Bench
   lines.
3. **Owner look #1:** the mix-and-match page plus a phone session. Defaults locked.
4. **Kind B.** Puppet mesh velocity, avatar previous-pose cache, sprite cell-to-cell flow and Cell
   blend.
5. **MOTION capability in FxCompiler** and the Motion Blur card with **Layers' own motion** only.
   Includes the adjustment-layer ordering fix, as its own card if another lane owns ExportManager
   ordering.
6. **Live flow (preview)** and **export flow** (forward/backward, occlusion, cut, speed and reverse
   awareness), with Camera move only.
7. **Look extras:** curved paths, highlight streaks, shutter shapes, Show motion.
8. **Joybot and help:** a `motion_blur` help topic, a tip ("things moving fast? tick Motion blur"),
   a Joybot tool that can switch it on for an item. STATE.md row with its proof level.
9. **Owner look #2** on the Note 9 (his own clips) and the Note 20.

**Model use** (memory `sonnet-medium-for-grunt-work`):
- Opus: steps 0, 1, 5 and 6, which are the shader maths, the planner change and flow quality.
- Sonnet at medium: the UI rows, the parameter plumbing, help and tips, and the bench screens.
- Fable: not needed. It is a reserve only if flow quality stalls on the Mali variant.

## 11. Risks

| Risk | Mitigation |
|---|---|
| The adjustment layer cannot see sprites or text in export (ordering) | Items get their own exact blur first. The ordering fix is a named prerequisite, and it improves every effect |
| ES 2 preview context limits | Everything is designed to ES 2: RGBA8 packing, fragment-only, highp checked by the bench |
| 8-bit banding at low tap counts | Interleaved-gradient jitter; adaptive taps |
| Flow artefacts on flat or noisy footage | Confidence map, fallback to the global camera fit; Ignore small motion |
| Avatar state rewinds | Previous-pose cache in preview, cloned state in export, test-locked |
| Mali-G72 bandwidth | Low-res tile passes, scissored reconstruction, no extra full-res ping-pong when nothing moves |
| Thermal throttling in long previews | Bench logs temperature. Draft is the automatic fallback if frame time climbs |

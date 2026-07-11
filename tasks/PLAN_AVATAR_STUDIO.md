# PLAN — Avatar Studio (Character-Animator-class puppets, offline, on-device)

> **🟢 2026-07-11 — JOYRAPTOR FULL GO-AHEAD (supersedes all gates):** MediaPipe dependency UNGATED —
> D4 (FaceLandmarker TrackingSource) is being built NOW by a Fable-directed Opus subagent per the
> delegation spec §D4. The whole product loop is approved for execution: real face tracking → avatar
> LIBRARY (save once, pick anywhere) → A4 recorder integration → bake-to-keyframes in the editor →
> point-at-a-video (offline video → tracked → baked) → A5 AI rigging. JoyRaptor also permits two
> Fable-directed subagents (one Opus, one Sonnet) on this mission.
> **LIBRARY CONTRACT LANDED (this session):** `avatar/AvatarLibrary.java` — a library entry is a
> SELF-CONTAINED BUNDLE dir `files/avatar_library/<name>-<id8>.avatar/` = `avatar.json`
> (libSchemaVersion + rig JSON + embedded sheet defs with RELATIVE sheetUris) + `sheets/<sheetId>.png`
> bytes. Rationale: a rig without its sheets is useless cross-project; same movable-bundle philosophy
> as `project://`. Consumers to build next: Studio "Save to library" button (wait until the D4 agent's
> AvatarStudioActivity edits land to avoid collisions), recorder avatar picker (A4), editor
> avatar-item insert + import-into-project glue (copy sheets into project assets, re-register).
> ⛏️ GLM-5.1 MINING RESULTS (2026-07-04, orchestrator-vetted — JoyRaptor: fold these in; full analysis below §MINED).
> Fable architectural plan 2026-07-03 (user's vision: webcam-driven sprite avatars replacing the webcam
> bubble in screen recordings). Companion external research from another AI incoming — merge its findings
> into §Candidates when it arrives. Builds ON TOP of sprite Build 1 (PLAN_SPRITE_ANIMATION) and its RIG
> VISION section; nothing here blocks S2–S7.
>
> **USER DECISIONS (2026-07-03 eve):** (1) Avatar Studio is JOYRAPTOR'S work lane — other sessions coordinate
> via this doc + handoff, don't double-build. (2) ARCHITECTURE CONTRACT: Avatar Studio is SEPARATE from
> the editor. The editor consumes avatars as STANDALONE DROPPABLE OBJECTS (like sprites/overlays) — each
> placed avatar carries its OWN settings for integrating into the video (its integration config lives on
> the placed object, not in the studio). Editor-side work = a thin "avatar item" object type + drop/insert
> path + per-item settings surface; everything else stays in Avatar Studio.

## Feasibility verdict
FEASIBLE, offline, on phone-class hardware including the Note 9 tier. A 2D sprite-swap puppet is orders of
magnitude cheaper than 3D VTuber rendering; the only real cost is face tracking (~10–30ms/frame at reduced
input, own thread, 15–20fps tracking interpolated to render rate). Rig evaluation <1ms; rendering = the
sprite engine's bitmap sub-rect blits. Recorder integration reuses the FloatingWebcamService/
GLWatermarkRenderer surface slot — encoder pipeline untouched.

## Core model — pose matrix = 2D pose SPACE
Moho/CTA/ToonBoom's 3x3/5x5 extreme grids: each cell = a COMPLETE per-part pose snapshot (pos, rot, scale,
z-order, flip, sprite cell) pinned at a (yaw, pitch) coordinate. Runtime:
- CONTINUOUS properties (pos/rot/scale) → bilinear blend of the 4 surrounding cells.
- DISCRETE properties (sprite swap, z-reorder, flip) → threshold switching WITH HYSTERESIS (debounce at
  boundaries or it flickers at the midline — the feel-detail competitors get wrong).
This is the FrameTrack-vs-KeyframeSet split from sprite S1 at rig level. Single-authority rule carries
over: ONE pure `resolvePuppetPose(rig, driverParams, timeMs)` that editor-preview/recorder/AI all call.

## Schema (additive to S1, LLM-legible like everything else)
AvatarRig {
  id, name, parts: [ { id, sheetId|imageUri, anchorX/Y (per-part override of sheet pivot), parentId,
    followWeight (how much of parent motion inherits), zByPose } ],
  poseGrid: { cols, rows, cells: [ { yaw, pitch, perPart: {pos, rot, scale, z, flip, cell} } ] },
  triggers: [ blink, expression loops (S1 Presets REUSED VERBATIM), custom keys ],
  visemeMap: { visemeClass -> mouth sheet cellIndex },
  physics: [ { partId, type: "dangle", stiffness } ]   // cheap verlet, hair/ears
}
Sidecar-exportable like .sprite.json → sharing + the AI contract.

## Tracking stack (offline tiers)
- FACE (primary): MediaPipe Tasks **Face Landmarker** — head yaw/pitch/roll + ~52 blendshapes (jawOpen,
  blink L/R, brows, smile, eyeLook*) in one model, TFLite under the hood, CPU/GPU delegates.
- FACE (light fallback): ML Kit Face Detection — euler angles + eye-open/smile probs; enough for v1.
- AUDIO/LIPS: v1 amplitude→jawOpen; v2 cheap spectral → 5–8 viseme classes; v3 option UNIQUE TO US:
  Vosk (already shipped in-app) partial-word → viseme assist.
- LIMBS (optional, off by default): MediaPipe Pose Landmarker — heavy; "hand enters frame" trigger, v2+.
- SMOOTHING: One-Euro filter on every continuous param (tiny, standard).
- Threading: camera(320x240) → tracker thread → smoothed params → render thread. Webcam pixels are NEVER
  rendered (privacy feature — market it).

## Recorder integration
FloatingWebcamService + GLWatermarkRenderer already composite a camera surface into the recording. Avatar
renderer is a drop-in replacement for that surface; camera keeps feeding ONLY the tracker. Placement/
resize/record behavior unchanged.

## AI rigging (existing conventions, no new chat UI)
describe_sprite_sheet (fast-follow B) → `author_avatar_rig` emits rig JSON against a BUILT-IN GENERALIZED
BIPED TEMPLATE (canonical part names: head/body/armL/armR/handL/handR/mouth; default anchors; default 3x3
grid) → lands in the editor → human nudges pivots + extremes → save. Propose-then-confirm, undoable,
key-gated. AI does structure; human does taste.

## Editor UX essentials
Matrix panel: ARM a cell → arrange parts → auto-snapshot into the cell. Onion-skin ghosts of neighbor
extremes. LIVE webcam preview driving the rig while editing. Per-part pivot handles (crop-editor pattern).
Z-order list per cell. Mirror-pose button (L↔R for free). Deferred: 5x5 upgrade path (grid size is data,
not code).

## Phasing (Fable builds, per user tiering policy; each milestone device-verified + review-gated)
- A1 rig schema + matrix editor, scrub/slider-driven (NO ML — proves the whole model risk-free)
- A2 face tracking driver + One-Euro + hysteresis
- A3 audio visemes (amplitude → spectral classes)
- A4 recorder integration (avatar replaces webcam bubble)
- A5 AI rigging tools
- A6 limbs trigger + dangle physics
Sequencing vs sprites: A1 needs sprite S2 (sheet slicing) + benefits from S4 (preview view). Target: start
Avatar A1 after sprite S4 lands.

## Risks
Thermal on long recordings (tracking-fps governor + input downscale); boundary flicker (hysteresis +
pin-snap crossfade — see §Pin-warp); old-device GPU delegate quirks (CPU fallback path required); scope
seduction toward DENSE Live2D-style mesh authoring — still out of scope (see §Pin-warp for what IS in).

## Candidates to verify against external research (merge their findings here)
MediaPipe Tasks (Face/Pose Landmarker), ML Kit Face Detection, TFLite/ONNX Runtime Mobile, One-Euro filter,
Vosk (in-app already), OpenSeeFace (PC reference impl), Inochi2D (open puppet format, inspiration),
VTube Studio / Animaze (UX reference), Adobe Character Animator (triggers/visemes/behaviors reference),
Cartoon Animator G3 360 head (the matrix reference), Moho smart bones/actions.

## MERGE 2026-07-03 eve — user's pin-warp design (BINDING) + external (z.ai) research adoption

### Pin-warp limbs (user's Adobe-Ch rig design — IN SCOPE, revises the old "no mesh warp" line)
The user's proven Character Animator workflow: limbs are LOW-COUNT PIN-WARPED strips, not just swaps.
An arm = a quad-strip mesh (~8–20 triangles) with 3 pins (shoulder/elbow/wrist) set PER CELL of a 5-cell
angle strip (front → extended → overhead). Between thresholds the strip WARPS (2-bone-IK/bezier through
the pins); past a threshold it HOT-SWAPS to the next cell's art. Mirror a finished limb for the other
side, or author asymmetric sets. **Perf verdict: effectively free** — 3-pin skinning of 9–20 vertices is
microseconds on CPU; a dozen such strips is nothing to any phone GPU; the cost center remains tracking.
The line I originally drew excluded DENSE Live2D-style mesh authoring (hundreds of art-directed vertices
+ weight painting — an authoring-tool explosion). That stays out. Sparse PIN warp is in.
**Pose domains generalized (schema rev):** poseGrid becomes per-GROUP pose domains — HEAD: 2-D grid
(yaw × pitch, 3x3/5x5); LIMB/BODY/LEGS: 1-D 5-cell strips over their driver angle; MISC (tail/parallax):
1-D strips over any driver. Each cell stores per-part {pos, rot, scale, z, flip, spriteCell, pins[]}.
The user's 5x5 budget intuition (5 head / 5 arm / 5 body / 5 legs / 5 misc) = five 1-D/2-D domains.
**Pin-snap crossfade (adopted from z.ai — genuinely good):** at a swap threshold, draw BOTH cells for
3–5 frames, alpha-crossfading, with pins mapped to IDENTICAL world positions — the swap happens over the
same geometric pose and reads as a smooth 3D turn, not a glitch. Combine with hysteresis (crossfade fixes
the visual pop; hysteresis still prevents rapid re-triggering at the boundary). Mouth visemes stay HARD
snaps (crisp reads better for lips); crossfade is for profile/limb-angle swaps.
**Renderer implication:** limbs need a GL path earlier than planned (vertex warp ≠ Canvas blit). A1 can
still prove the model with Canvas + rigid parts; the GL strip renderer lands with the limb phase.

### Adopted from the z.ai report (verified sensible)
- **TarsosDSP** (pure-Java DSP): formant/pitch extraction for viseme classes — the v2 audio tier
  candidate; far cheaper than a neural audio model. Small buffers (~1024 samples) for lip-sync latency.
- **Gotcha — camera single-owner:** the tracking camera must live ENTIRELY in FloatingWebcamService
  during recording and ENTIRELY in the Avatar Studio activity during editing — never both (Android
  camera-in-use exceptions). Design the tracker engine as a component both hosts mount exclusively.
- **Gotcha — texture edge padding:** warped strips need edge clamping / a ~2px transparent border on
  limb art or edges tear at warp extremes (art-import step should auto-pad).
- **References:** libGDX TextureAtlas/SpriteBatch (pattern for the GL sprite batch), Spine 2D's format
  (bones/slots/attachments — sanity-check our rig JSON against it), CameraX → tracker frame pipeline.
- Convergent with this plan (independent confirmation): MediaPipe FaceLandmarker + blendshapes, bilinear
  matrix interpolation, threshold z/flip snapping, FloatingWebcamService surface swap, cloud-assisted
  auto-rig with offline runtime, avatar-as-timeline-object.

### Rejected/corrected from that report
- "Bilinearly lerp zOrder" — no: z is discrete, threshold+hysteresis only (the report contradicts itself
  two sections later; our rule stands).
- "<10% CPU, NPU-accelerated, 30+fps" — flagship-optimistic; plan for CPU/GPU delegates on the Note 9
  tier at 15–20fps tracking, interpolated rendering (already our budget).
- OpenCV Haar-cascade fallback — dead end; ML Kit IS the light fallback tier.
- Crossfade for EVERY sprite swap — no; visemes hard-snap (see above).

## Status
- [x] merge external research (z.ai merged; GLM-5.1 mining merged 827947c — bake-to-param-track,
      driverType split, life package, empty-cell inheritance, Spine license landmine all folded in)
- [x] A1 MODEL COMPLETE (a9d6cc5): AvatarRig schema (parts/parentId/anchors/followWeight, 1D+2D
      PoseDomains with per-cell PartPose incl. warp pins, visemeMap, self-serializing JSON) +
      PuppetPoseResolver — pure single-authority evaluator: bilinear blend of continuous props AND pins,
      EMPTY-CELL INHERITANCE via weight renormalization, discrete cell/z/flip via dominant-corner
      hysteresis + swapped flag (= pin-snap crossfade trigger), caller-owned DiscreteState so baked-param
      replay is deterministic. FaditorProject.avatarRigs[] + storage round-trip, dual-write stamp v10.
      STILL OWED for A1 sign-off: resolver review gate + a scrub-driven matrix-editor scaffold (the UI
      half of A1) — next session's first work item.
- [x] A1-UI matrix editor SHIPPED (bb68685, 2026-07-05): AvatarStudioActivity + PuppetPreviewView
      (parent∘child matrix composition at draw time, MISSING placeholder, drag-to-pose when armed) +
      PoseMatrixView (authored solid / auto-blend dashed / armed ring / live blend marker). Arm-a-cell
      seeds poses from the CURRENT blended state; clear-cell restores auto-blend. Entry: Sprites tool →
      Avatar Studio → rig list. Review-gate fixes landed first (eafb687): per-part discrete hysteresis
      (DiscreteState keyed domainId/partId), swapped-on-source-change, tolerant fromJson, pin
      renormalization over pin-carriers — all proven by a JVM harness (7 cases green). OWED: on-device
      launch verify once the build watcher returns; user feel-test of the blend.
- [ ] A2 tracking driver (BLOCKED on watcher for the MediaPipe gradle dep)  - [ ] A3 visemes  - [ ] A4 recorder integration
- [ ] A5 AI rigging
- [~] A6 limbs — **PIN-WARP CORE LANDED 2026-07-06 (5e3a94d)**: PinWarpStrip math (JVM harness
      PinWarpTest 16/16 — identity/translation/90° bend/guards/degenerates), Part.restPins
      schema (additive, tolerant read; ResolverGateTest re-run green), PuppetPreviewView warp
      draw + 130ms pin-snap crossfade on the resolver's swapped signal (old cell rides the
      SAME verts = swap over identical geometry). NOTE §Pin-warp's "renderer implication" is
      CORRECTED: Canvas.drawBitmapMesh does sparse warps natively (hardware-accelerated, same
      Canvas stack as every compositing surface — one vertex authority for preview AND export);
      no GL renderer needed at puppet scale, revisit only if profiling disagrees. REMAINING:
      pin authoring UI (place/drag rest pins in the studio — design pass first), FABRIK→posed-
      pins tracking hookup (A2), dangle physics.
      **DEVICE SMOKE PASSED 2026-07-06 ~23:00 (Note 9):** injected "a6-smoke-rig" (3-pin arm,
      3-cell yaw strip, cell swap at the right extreme) into the bdd51919 sandbox → Avatar
      Studio opened it, yaw slider drove the blend, and frame extraction proved: warp bends
      per the authored pins at BOTH extremes, the discrete swap fires, and the pin-snap
      crossfade was caught MID-FADE with both cells double-drawn on the same warped verts.
      Zero crashes. The rig is left in the sandbox for the user's feel-test (on-device
      backup: project.json.bak-a6-20260706).

## USER FEEDBACK 2026-07-06 (first A6 hand-test — "looks great") + roadmap candidates
User read strong bends as CHOPPY → fixed same night: WARP_SEGMENTS 10→24 (GPU cost is a
rounding error — drawBitmapMesh is hardware-accelerated; even 50 bands ≈ 100 tris/part vs
the millions mobile GPUs push) + JOINT-SMOOTHED normals in PinWarpStrip (per-joint averaged
segment directions, lerped per row — the crease at each pin was the bigger chop source than
density). Mesh WIREFRAME overlay added: pin mode now draws the live warp grid ("why does it
bend like that" view, user-requested).
**Recorded candidates (user asked; not built, honest status):**
- **Alpha-traced contour mesh** (Ch-style: trace opaque pixels + padding, mesh only there):
  at our poly counts it saves nothing — its value is warp QUALITY on wide/irregular art. It
  drags in the dense-mesh pipeline (marching squares → triangulation → distance-weighted
  skinning) this plan deliberately excluded. PARKED as an A6 fast-follow experiment; a cheap
  middle step if wanted sooner: per-row alpha-extent clamping of the strip width.
- **Vector draw tools** (lines/curves/shapes with handles): NOT on the roadmap — all art is
  bitmap sprite-sheet based. Recorded as a candidate feature family; would be its own plan.
- **Mesh density / algorithm options** (Adobe ships 3 algorithms + density settings): we ship
  ONE algorithm (smoothed strip sweep). Per-part density is data-not-code whenever UI appetite
  exists (WARP_SEGMENTS → a Part field + a stepper chip; delegable once designed).

## MINED — GLM-5.1 external review, orchestrator-vetted (2026-07-04)
ADOPT AS BINDING:
- **Bake-to-parameter-track architecture**: recording saves RESOLVED driver params (yaw/pitch/visemes/pin coords) into a hidden track; export replays them through resolveTransformAt — webcam never re-runs at export. Matches our preview==export doctrine (same lesson L2 taught). Plus "Bake to Video" tool to flatten a rig to a normal clip.
- **driverType schema split**: 2D_MATRIX (heads) vs 1D_ANGLE (limbs, 5-cell strip + pin-warp crossfade). Limbs are 1D; do not force the 9-cell grid on them.
- **Tracking/render decoupling**: MediaPipe 15-20fps on its own thread, GL/render 60fps, interpolate last pose. + One-Euro filter on ALL tracking inputs (tiny, implement from paper, no dep).
- **Life package** (near-zero cost, huge feel): idle-loop fallback w/ -45dB/2s audio gate; breathing sine on body scaleY; saccade micro-darts every 3-5s; asymmetric blink (1-frame eye delay).
- **Authoring UX**: pose-grid EMPTY-CELL INHERITANCE (rig 3 cells → auto-blend the rest, dashed "auto-blended" cells + completeness glow); pin auto-weighting by distance; Pin Dial radial rotate control (touch-first); viseme CALIBRATION flow ("say Ah/Eh/Ee/Oh/Oo" → personal formant polygon in rig JSON).
- **TarsosDSP** (pure Java, GPL-compatible) for formant→viseme + amplitude; **FABRIK** for IK (implement, ~50 lines); **behavior trigger hotbar** during recording (tap preset override, blend back to live); **texture edge padding** (auto-duplicate edge pixels 2px on import — prevents warp tearing); **thermal governor** (PowerManager thermal listener → degrade tracking fps → physics off) + Eco/Balanced/High toggle; **anchor snapping + bounding-box auto-crop** for placement in recordings; mouth mask driven by amplitude (scale) / visemes (shape) — implement via Canvas clipPath on the Canvas path, GL stencil ONLY where a GL surface already exists (recorder).
SKIP / PARK (with reasons):
- ❌ **Spine Runtimes as code reference — LICENSE LANDMINE**: GLM claimed Apache-2.0; the Spine Runtimes license actually REQUIRES a paid Spine editor license for runtime use. Do not copy/port their code. FABRIK + own math instead.
- Runtime texture-atlas baking / libGDX SpriteBatch adaptation: premature at our scale (1 avatar, ~10 parts); sheet-decode-once + sub-rect blits already is a de-facto atlas. Revisit only if profiling shows bind overhead.
- JOML: 3D GL math lib for a 2D affine problem — android.graphics.Matrix suffices.
- MobileSAM auto-segmentation + AI-generated 3/4-view extremes: park as fast-follow experiments (model size, stylized-art quality risk, pivot inference fragile). Aligns with sprite plan fast-follow B when it comes.

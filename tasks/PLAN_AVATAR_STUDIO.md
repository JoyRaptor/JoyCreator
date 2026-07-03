# PLAN — Avatar Studio (Character-Animator-class puppets, offline, on-device)
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
Thermal on long recordings (tracking-fps governor + input downscale); boundary flicker (hysteresis is
non-negotiable); scope seduction toward Live2D-style MESH WARPING — explicitly OUT OF SCOPE, sprite-swap
rigs are the lane; old-device GPU delegate quirks (CPU fallback path required).

## Candidates to verify against external research (merge their findings here)
MediaPipe Tasks (Face/Pose Landmarker), ML Kit Face Detection, TFLite/ONNX Runtime Mobile, One-Euro filter,
Vosk (in-app already), OpenSeeFace (PC reference impl), Inochi2D (open puppet format, inspiration),
VTube Studio / Animaze (UX reference), Adobe Character Animator (triggers/visemes/behaviors reference),
Cartoon Animator G3 360 head (the matrix reference), Moho smart bones/actions.

## Status
- [ ] merge external research  - [ ] A1  - [ ] A2  - [ ] A3  - [ ] A4  - [ ] A5  - [ ] A6

# Fable-day 2026-07-06 — delegation-ready specs (periphery of A2 + compositing family)

Core landed + device-proven today (commits `05cf3ac` A2 tracking core, `c7188c4` compositing
family + PiP y-flip fix). Everything below is PERIPHERY: UI, adapters, polish — buildable by
any lane against the frozen contracts named here. Do NOT redesign the contracts; they are
device-proven.

## Frozen contracts (what the periphery builds against)

1. **Driver params ARE the tracking contract** (`avatar/TrackingFrame`): plain
   `Map<String,Float>` — `yaw`/`pitch`/`roll` normalized [-1..1] (the names a rig's
   PoseDomains declare), blendshapes 0..1 by MediaPipe-style names (`jawOpen`, …),
   limb IK targets as `pinTarget.<partId>.x|.y` (view-normalized 0..1). The bus
   (`TrackingDriverBus`) smooths (One-Euro), merges life + amplitude-jaw, and republishes;
   the renderer FABRIK-re-aims any part with a target (`PuppetPreviewView.trackedPins`).
2. **CompositingSpec** (`model/CompositingSpec`, on `Clip.compositing`, JSON key
   `"compositing"`): `masks[] {cx,cy,w,h,corner,rot,sub}` canvas-normalized +
   `invertMasks`; `chromaKey {color,tolerance,fuzziness,offset}`; `matte {peerId,
   mode:"luma"}`. Empty spec == null == absent in JSON. `MaskPathBuilder` is the ONLY
   path authority (preview + export both call it).
3. **Every PiP composites via `BlendModeGlEffect`** in the effect chain (z-unification);
   masks apply in `PipFrameOverlay` (Canvas clip), key + matte in the shader.
   The shader samples overlay/matte textures V-FLIPPED (bitmap Y-down vs UV Y-up) —
   do not "simplify" this away; pre-fix, every PiP exported upside-down-in-place
   (authored y=.378 rendered at .622).

## D1. Mask editing UI (user scenario B1) — HIGH value, medium size
- Entry: clip selected → tool drawer chip "Mask" (site: FaditorToolRegistry + the
  bottom-sheet pattern every tool uses).
- On-canvas: draw the spec's shapes as outlined rounded rects over the preview
  (SpriteOverlayView/OverlayVideoPreviewView gesture conventions: drag=move,
  pinch=resize; handles optional v1). "+ shape" adds additive; long-press a shape
  toggles subtract (render subtract shapes in a different outline color);
  corner-roundness slider bound to the selected shape's `corner`; "invert" chip.
- Writes: mutate `clip.getCompositing()` (create via `new CompositingSpec()` +
  `clip.setCompositing`), autosave + ONE undo step per gesture (KeyframeSet-snapshot
  precedent in OverlayVideoPreviewView.onOverlayVideoManipulated).
- Preview updates free (drawChild clip reads the spec each frame; just invalidate).
- ACCEPTANCE: author 2 shapes (one sub) by hand on device, save, reopen, export —
  hole matches preview (the c7188c4 A/B technique, tasks/ has the recipe).

## D2. Chroma-key UI (B2) — small
- Drawer: "Key" chip → enable toggle, color swatch, tolerance/fuzziness/offset sliders
  (0..1, 0..1, −1..1) → `compositing.keyEnabled/keyColor/...`.
- Swatch-drag color sampling: extract the playhead frame (MasterPlaybackEngine already
  has still-extraction; the PiP still-fallback decodes MMR stills too) → finger position
  → content-rect-normalized → read pixel → keyColor. Cheap, no GL.
- Preview shows the clip UNKEYED (probe-#4 rule: export = ground truth). Show a "keyed
  on export" badge on the clip row (LayerRowRenderer labelFor pattern).
- ACCEPTANCE: sample the PiP's dominant color on device, export, keyed-region colorful
  fraction jumps (0.04 → 0.64 in today's proof; same measurement script pattern).

## D3. Track-matte pairing UI (B3) — medium
- Flow: select clip → "Video alpha" → pick role (this clip = RECIPIENT) → pick the peer
  from a list of other overlay clips → writes `compositing.matte.peerId` on the recipient.
- Timeline: dotted line connecting the two items' corners while either is selected
  (EditorTimelineView draw pass; both stay independently movable/trimmable — the export
  `activeAt` gate already handles partial overlap).
- PREVIEW DIVERGENCE TO CLOSE (small): preview currently still renders the matte peer
  as a normal PiP. Hide it: `LayerPreviewController.visibleOverlayVideoClips` should
  drop clips whose id appears as any recipient's `mattePeerId` (mirror ExportManager's
  `servingMatteIds` logic — keep the two in lockstep). Recipient renders unmatted in
  preview + badge, per probe-#4.
- ACCEPTANCE: pair, export, recipient alpha follows matte luma while overlapped and
  renders unmatted after the matte's window (today's t=2.0 / t=3.4 measurements).

## D4. MediaPipe FaceLandmarker TrackingSource (A2 final hookup) — USER-GATED (gradle dep)
- Implement `TrackingSource`: CameraX 320x240 front feed → FaceLandmarker (LIVE_STREAM
  mode, CPU delegate baseline for the Note 9) on its own executor.
- Param mapping per frame (all through ONE TrackingFrame):
  `yaw = clamp(headYawDeg / 45)`, `pitch = clamp(headPitchDeg / 30)` (sign: right/up
  positive — match the studio sliders), `roll` likewise /45;
  blendshapes: pass through by their MediaPipe names 0..1 (`jawOpen`,
  `eyeBlinkLeft`→`blinkL`, `eyeBlinkRight`→`blinkR`, `browInnerUp`, `mouthSmile*`…);
  `audioDb`: NaN (mic stays with A3) — the life package then idles on quiet correctly.
- Tracking loss: emit NOTHING (no frames) and call nothing — the bus's last snapshot
  freezes, LifeSignals keeps breathing. On re-acquire, `TrackingParamPipeline.reset()`
  via bus restart if the gap exceeded ~1s (snap, never glide).
- Threading/lifecycle: mount/unmount EXACTLY like the studio's SyntheticTrackingSource
  ("🎯 Track" chip swaps source — one-line change in startTracking); the camera
  single-owner rule is already enforced by the bus.
- Thermal: PowerManager thermal listener → drop tracker to 10fps / disable limb targets
  first (plan §MINED thermal governor).
- ACCEPTANCE: the a6-smoke-rig nods with the user's head; screenrecord + frame-diff
  (the 05cf3ac technique) + zero dropped-frame jank at 15fps tracking.

## D5. Masks on sprite/text items — small, mechanical
- `CompositeExportOverlay`: wrap each sprite/text draw in
  `save(); MaskPathBuilder.clipCanvas(canvas, item.getCompositing(), outW, outH); …restore()`.
- Requires `compositing` on SpriteOverlayItem/TextOverlayItem (copy the Clip pattern:
  field + copy() + serializer sites in their own toJson/fromJson).
- Preview: same wrap in SpriteOverlayView / TextOverlayLayer draw loops.
- Keep coordinates CANVAS-normalized (the §C decision — holes don't travel with items).

## D6. Recorded polish (do not lose)
- clipPath is not antialiased → soft-mask follow-up: render mask to ALPHA_8 bitmap,
  DST_OUT composite (both preview + PipFrameOverlay; keep MaskPathBuilder authority).
- Feathered mask edge (blur radius on the ALPHA_8 pass) = the same follow-up.
- PiP does NOT composite over IMAGE/gap master clips (pre-existing, re-confirmed today
  at t=4.2: gap-black item renders no PiP at all). Fix shape: image-clip items must get
  the same BlendModeGlEffect chain; check `usesLayerFeaturesAffectingExport` too.
- Loop-for-duration on overlay clips (B1 mentions looping overlay animations) — rides
  the ping-pong/loop unpark (PLAN_PINGPONG_UNPARK.md), unchanged.
- Chroma-key spill suppression (desaturate near-key edge pixels) — v2 knob, shader-side.
- Master-clip chroma key (key the MAIN track) needs the GL preview wave (M-COMP-2
  read in §C stands); spec unchanged, do not bolt onto BlendModeGlEffect.

## Measurement recipes (reuse verbatim)
- A/B export diff: inject spec via project.json push (run-as cp; backup first), export
  twice, `ffmpeg -ss T -frames:v 1`, block-density diff → geometry (scripts in the
  2026-07-06 session transcript; hole bbox matched authored to ±0.01).
- PiP-region composition stats: median-color distance fraction + saturation fraction
  (proved key: gray 0.82→0.05; matte: 0.82→0.48→0.83 across the window).

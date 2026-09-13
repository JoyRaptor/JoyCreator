# SPEC Z — Corner pin and mesh bend on sprites, PiP, text and the spine

**Difficulty: LARGE, in four slices. DEPENDS ON SPEC Y — do not start before it lands.**
**Read `_RULES_READ_FIRST.md`, `SPEC_Y_one_transform_surface.md`, then
`SPEC_H_enable_bend.md` and `SPEC_R_mesh_inverted.md` for how the mesh path actually works.**

JoyRaptor, 2026-09-13: **"They ALL need to get done."** Sprites first — *"that is the biggest win
for the animation wing of Joy Creator."*

---

## The rule that gates every slice

`FaditorEditorActivity` ~25544 states it:

> "A text box has no pinned render path in either, so giving it these handles would author a
> distortion neither surface could draw."

**A capability is switched on only when BOTH the preview and the export can draw it.** That is the
preview/export parity rule applied to gestures, it is why text/PiP/spine are affine-only today, and
it is not up for negotiation. Each slice below is therefore mostly RENDERER work; flipping
`canPin()`/`canWarp()` (SPEC Y) is the last line, not the first.

## What is already shared, and must stay that way

- **`MeshStampGl`** takes a GL texture and warps it. ONE shader string, used by preview
  (`FxPreviewTextureView`) and export (`ImageBlendGlEffect`). It does not know what the texture
  holds — which is precisely why it can serve all four. **Do not write a second warp anywhere.**
- **`TransformQuad`** — pure maths, no type knowledge, harness-covered.
- **`MeshWarpSpec`** — self-serialising, already tolerant on read.
- **`OverlayTextureCache`** already rasterises text AND sprites to bitmaps, with ONE predicate —
  `canUseTexture` — deciding texture path vs Canvas fallback. Its own comment: *"One predicate, one
  place."* Extending that predicate is the intended seam, not a new branch beside it.

---

## Slice 1 — SPRITES (first, and the reason for the whole spec)

### The design constraint, in his words

> "The distortion should be on the sprite itself — NOT the cells living inside, which are
> transient. If I'm animating squash and stretch, bend to the head, I want the mouth or facial
> expressions to follow underneath. This is what will give layering animation sophistication no
> other mobile app has."

A `SpriteOverlayItem` owns a `FrameTrack` (which cell shows at time t) and optionally an
`avatarRigId` (a live puppet composing parts). **The warp sits OUTSIDE both**, on the composed
raster, as the last stage before the sprite is placed on the canvas:

```
FrameTrack picks a cell  ->  rig composes parts  ->  per-cell transform (SpriteLab)
    ->  ONE sprite-level pin + mesh  ->  place on canvas
```

Authored once, inherited by every cell and every rig pose. Per-cell would mean re-authoring the
bend for every mouth shape and the mouth would NOT follow the head — the exact opposite of what is
wanted. **If the implementation ever needs the warp to know which cell is showing, it is in the
wrong place.**

### What to build

1. **Model.** `SpriteOverlayItem` gains a corner pin and a mesh, mirroring `TextOverlayItem`'s
   fields exactly — same units (pin offsets as fractions of the item's own size), same
   `MeshWarpSpec`. Same-shaped code, because two shapes is how two behaviours start.
   Persistence, `copyWithNewId`, and the undo `TransformSnapshot` all carry them.
2. **Sprites are not on the transform surface AT ALL yet.** Selecting one routes to
   `ensurePreviewHandlesOverlay().setTarget(spriteHandlesTarget(...))` — the legacy handle overlay.
   So slice 1 starts by giving sprites a SPEC Y adapter and the same handles every other type has.
   **That step is worth landing and testing on its own**, before any pin or mesh.
3. **Preview.** Sprites already ride the texture path when `canUseTexture(sprite)` says so — today
   only when keyframed. Extend that predicate: a sprite carrying a pin or a mesh takes the texture
   path too. Then feed the sprite's texture through the existing `MeshStampGl` route.
4. **Export.** The matching path, through the SAME stamp. Name the file you added it to and show
   it calls the shared code rather than a copy.
5. **Coordinate with SpriteLab.** Per-cell transforms are in flight right now
   (`tasks/SPEC_20260910_SPRITELAB_MODEL.md`). The sprite-level warp composes ON TOP of a per-cell
   transform, and the two must not fight. Read that spec before designing, and say in the report
   how they compose.

---

## Slice 2 — PiP / overlay video

Lowest render risk of the four: a PiP is **already a live GL texture in both renderers**
(`FxPreviewTextureView.Pip`, `GlPipFrameOverlay`), which is exactly what `MeshStampGl` consumes.
Nothing to rasterise.

The work is model plumbing on `Clip`: pin and mesh fields, persistence, undo snapshot, copy
constructor. `ProjectStorage` currently drops a `mesh` member on a Clip ON PURPOSE (~1481, ~1852,
with the reasoning written out) — extend that scoping rather than working around it, and keep the
byte-identical-save discipline for clips that carry none.

---

## Slice 3 — TEXT

The model is **already done**: `cornerPin` and `mesh` live on `TextOverlayItem`, and text and image
share that class, so a text box can hold a bend today — nothing draws it. Persistence is gated by
one `if (o.isImage())` at `ProjectStorage` ~2449.

Preview and export both already have a GL text route (`canUseTexture` for preview,
`TextFxGlEffect` for export) — both conditional. Bending text means making "carries a pin or a
mesh" another reason to take that route, in both.

**The real risk is sharpness.** A warped text box is a rasterised text box, and rastering at the
wrong size gives soft edges. The image path already solves this with `maxDrawnHeightFactor`, which
folds size, keyframes, per-axis scale and pin excursion into one decode bound. Text needs the
equivalent. **Compare a bent and an unbent text box at the same point size on the device, and show
both.** If the bent one is visibly softer, the slice is not done.

---

## Slice 4 — SPINE

**A correction, recorded because it was made in this session and it was wrong.** The claim was that
bending the spine would disturb the overlays above it, because the spine is the coordinate system.
JoyRaptor: *"one can already adjust the canvas crop without it messing up overlay layers above
it."* He is right, and `SpineTransformExportEffect`'s own class doc says so:

> "the frame it is handed is the finished, canvas-shaped clip picture — geometry, crop, grade and
> effects all applied — and what it does is PLACE that picture. Overlays, PiPs, adjustment layers
> and captions then draw over the canvas at their own coordinates, unmoved."

So the spine transform is already a full-frame shader pass placing a finished texture, with
overlays explicitly unaffected, and it already has ONE authority shared by preview and export
(`SpineTransformExportEffect` + `FxPreviewTextureView.drawSpineTransform` — its doc: *"this feature
has one"*). A mesh there is the same shape as what is already there. Extend `Clip#spinePoseAt`'s
pose to carry a pin and a mesh, and warp in the pass that already exists.

Slice 2 and slice 4 share the `Clip` model work — do them adjacent, and do 2 first.

---

## Acceptance criteria

1. Each slice: the capability is ON only when both renderers draw it. For each, name the preview
   file and the export file and show they call the SAME stamp.
2. **A bend authored on a sprite survives a cell change and a rig pose change**, unmodified. This
   is slice 1's headline test and JoyRaptor will run it first.
3. Sprites reach the transform surface with the same handles, same ring, same grammar as every
   other type — no sprite-only gestures, no sprite-only wording.
4. Preview and export agree, per type. **Export a frame with a bend on each of the four and compare
   against the preview at the same timestamp.** `run-preview-parity` and `run-frame-parity` stay
   green, and say what new coverage you added to them.
5. Nothing regresses for images: pin and bend behave exactly as they do today.
6. A project saved before any of this loads unchanged, and one saved after opens on an older build
   without losing its clips (tolerant read, as `MeshWarpSpec` already does).
7. `./gradlew assembleDefaultDebug --console=plain` → BUILD SUCCESSFUL, and the full standing
   harness set stays green.

## Boundaries

- Do **not** write a second warp, a second pin matrix, or a second fold. If the shared code does
  not fit a type, say so and stop — that is a finding, not a licence to copy.
- Do **not** start before SPEC Y lands. Adding a fifth hard-coded host is the thing this pair of
  specs exists to prevent.
- Do not change image behaviour.
- **The sandbox phone is the test device. NEVER install to or write on the Note 20** — it holds
  JoyRaptor's real projects.

## Deliver

Per slice: the model change, the preview file, the export file, the proof they share one stamp, and
device evidence. For slice 1, the cell/rig composition answer and the survives-a-cell-change test.
For slice 3, the two text screenshots. Build verdict; compile-verified vs device-verified, per
slice.

# SPEC ZB — Give `Clip` a corner pin and a mesh (the PiP and spine foundation)

**Dispatchable. Difficulty: MEDIUM — pure mirroring of work already done on the sprite model.**
**Read `_DISPATCH_RULES_20260913.md` first.**

This sheet adds **no behaviour**. It gives PiP clips and spine clips somewhere to HOLD a distortion,
so the shader work (SPEC ZD, not yours) has a model to read. Nothing will look different when you
are done, and that is the acceptance test.

---

## What already exists to copy

`SpriteOverlayItem` was given exactly this on 2026-09-13. **Read that diff first** — commit
`58cd3894` for the model and `73b90334` for the persistence — and mirror it. Two shapes is how two
behaviours start; if you find yourself designing something, you have drifted off the pattern.

The pattern, in `app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteOverlayItem.java`:

| piece | what it is |
|---|---|
| `private final float[] cornerPin = new float[CornerPin.SIZE];` | eight offsets, **fractions of the item's own untransformed size** |
| `@Nullable private MeshWarpSpec mesh;` | the bend |
| `getCornerPin(corner, axis)` / `setCornerPin(corner, axis, v)` / `setCornerPin(float[])` / `copyCornerPinInto` / `clearCornerPin` | the accessors, named identically |
| `hasCornerPin()` | reads the TRACKS, not a sampled time — see its doc for why the answer must not flicker mid-animation |
| `animatedCornerPin(long, float[])` | evaluated on the item's own local clock |
| `cornerPinMatrix(Matrix, long, left, top, w, h)` | **the one method preview and export both call** |
| `hasMesh` / `getMesh` / `setMesh` / `installMeshCurve` / `meshLocalTime` | the mesh, with `MeshCurves.install` as the single easing authority |
| `wantsGl()` | the GL-promotion predicate |

## What to build

### 1. The fields and accessors on `Clip`

`app/src/main/java/com/fadcam/ui/faditor/model/Clip.java`. Mirror the list above. `Clip` already
implements `AudioParams` and carries a `SpineTransform` pose; put the warp beside the existing
geometry, not inside the spine pose.

**Both a PiP clip and a spine clip are `Clip`.** One set of fields serves both — a PiP is a `Clip`
with `isOverlayClip()`, the spine is a `Clip` on `getClips()`. Do not add two.

### 2. Copy and snapshot

- The copy constructor and `copyWithNewId`-equivalent must deep-copy both. **A copy sharing a
  `MeshWarpSpec` reference means bending the copy bends the original** — the sprite work hit this
  and the comment there explains it.
- The undo snapshot must carry both, and must compare meshes by their **serialised form**
  (`toJson().equals`), not field by field. That gives "same bend" one definition and it is the
  file format's. See `SpriteOverlayItem.TransformSnapshot.meshEqual`.

### 3. Persistence

`app/src/main/java/com/fadcam/ui/faditor/project/ProjectStorage.java`.

The clip writer/reader already has a **deliberate** exclusion you must extend rather than work
around — read it before editing:

- ~line 1481: *"SPEC E mesh: v1 is image overlays only (TextOverlayItem), so a Clip carries no mesh
  field and writes nothing here"*
- ~line 1852: the read side drops a stale `"mesh"` member on a Clip on purpose

Write it the way the sprite writer does (~line 2650): **eight sparse keys via
`CornerPin.jsonKeyFor`, present only when that corner is off zero, plus a `mesh` object only when a
bend is authored.** A clip with no warp must add **not one byte**, and its file must round-trip
byte-identically. Reading is tolerant: absent is zero, zero is undistorted.

### 4. Extend the persistence lint — this is not optional

`tools/jvm-harness/persist_lint.py` gained `SpriteOverlayItem` as a target on 2026-09-13 and
immediately found three unaccounted fields. `Clip` is already a target, so **your two new fields
will make it go red** until you handle them.

- `mesh` matches by name and should pass once written and read.
- `cornerPin` will NOT: its eight keys are built at runtime by `CornerPin.jsonKeyFor`, so there is
  no literal for the lint to find. **Add a `CUSTOM` entry, not an `EXEMPT` one.** The sprite entry
  shows the form: name the getter and setter calls that must be present, with the variable prefix
  that makes it this model's block rather than "some pin block exists somewhere". An exemption would
  pass even if the whole block were deleted, which is the outcome that lint's own history warns
  about.
- **Then prove it fails:** delete the writer, watch the lint go red on that field, restore it. Put
  that in your report (rule 4).

## Acceptance criteria

1. `Clip` carries a corner pin and a mesh with the accessor names above — same names as the sprite
   and image models, checked by reading all three side by side.
2. A copy gets its OWN mesh. Prove it: copy a clip with a bend, change the copy's bend, show the
   original unchanged.
3. A project with no warped clip saves **byte-identically**. Say how you verified it (save, diff).
4. A project saved WITH a warped clip loads on a build that predates this sheet without losing the
   clip — the tolerant-read contract.
5. `run-persist-lint` green, **and** the negative control demonstrated.
6. **Nothing looks different.** No renderer reads these fields yet. If any picture changes, you have
   done more than this sheet asks.
7. `bash tools/build-verify.sh cornerPinMatrix` → VERIFIED, and the standing harnesses stay green.

## Boundaries

- You own `model/Clip.java`, the clip blocks of `project/ProjectStorage.java`, and
  `tools/jvm-harness/persist_lint.py`.
- Do **not** touch any renderer — not `FxPreviewTextureView`, not `ExportManager`, not
  `SpineTransformExportEffect`, not `GlPipFrameOverlay`. Reading these fields is SPEC ZD's job.
- Do **not** touch `transform/**` or `sprite/**`.

## Deliver

The field list with the sprite equivalents beside them. The persistence keys. The lint entry AND the
negative control. The byte-identical proof for acceptance 3. Build verdict via the dex.

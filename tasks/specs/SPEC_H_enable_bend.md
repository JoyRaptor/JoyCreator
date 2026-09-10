# SPEC H — Turn the Bend button on (SPEC E step 5)

**Difficulty: MEDIUM-HIGH. The renderer exists; this makes it reachable.**
**Read `_RULES_READ_FIRST.md` first, then `SPEC_E_mesh_gl_renderer.md`.**

## Where things stand — verified 2026-09-06, do not re-derive

The SPEC E lane landed the mesh renderer and deliberately stopped short of the button, because the
button lives in SPEC D's files and the two ran under a file-ownership boundary. Verified facts
about the CURRENT tree (commit `bed9c816`):

- `TransformOverlayView.setBendAvailable(boolean)` exists at line 701 and **has no callers**.
- `TextOverlayItem.setMesh(...)` is reachable **only** from `ProjectStorage` (loading a saved
  mesh). No UI path can create one.
- Therefore the whole mesh lane is **dormant**: no existing project can trigger a single line of
  it. The build is green and `run-mesh.sh` passes 66/66.

That is the safest possible starting point, and it is also why nothing bends yet.

## The task

1. Call `setBendAvailable(true)` for **image overlays only** — `CornerPinTransformHost` is the only
   host with a pin/mesh render path. Text and PiP are `setAffineOnly(true)` and must stay that way.
2. Wire the net's dots to `MeshProjection.dragToHandle(...)`, guarded by `MeshGuard.accepts(...)`.
3. One drag = one `MeshPoseTrack.put(...)` = **one** undo press. The snapshot already carries the
   bend — read `TextOverlayItem.TransformSnapshot` before adding anything.
4. Turn the button on only once a drag actually bends the picture in the preview **and** the export.

## THE TRAP — a known parity hole you must close, not inherit

**The mirror is not applied on the mesh path.**

The SPEC G lane added `flipH`/`flipV` with one definition — `TextOverlayItem.mirrorSignX/Y` — read
by three renderers: `ImageOverlayDraw` (`canvas.concat`), `CornerPinImageView` (`preConcat`), and
`TextOverlayLayer.buildPip` (signed half-extents). The GL **export blend** path is already safe,
because `ImageOverlayFrameOverlay` composites through `ImageOverlayDraw.draw(...)`, which mirrors.

The mesh branch does **not**: `ImageBlendGlEffect` calls `ImageOverlayDraw.decode(...)` for the raw
bitmap only, then stamps the mesh itself. So **a mirrored, bent image will export unmirrored while
the preview shows it mirrored.** The G lane flagged this as a known-divergent combo and left it
because Bend was unlanded. It is now yours.

Fix it from the SAME `mirrorSignX/Y` definition — do not transcribe the mirror a fourth time. State
where you applied it on each surface and how you proved they agree.

## Acceptance criteria

1. A project with no bend renders and exports byte-identically to today. Prove it.
2. A bend looks the same in the preview and the export. Name the shared source of truth.
3. A bent AND mirrored image is identical on both surfaces.
4. One drag = one undo press.
5. `bash tools/jvm-harness/run-mesh.sh` stays green (66/66).
6. `./gradlew assembleDefaultDebug --console=plain` → BUILD SUCCESSFUL.

## Deliver

Where the button is enabled and for which types; the mirror-on-mesh fix and its parity evidence;
the undo proof; measured or reasoned cost against SPEC E's budget (~1.5–2 ms per warped object per
frame on a Note 20); the build verdict; compile-verified vs device-verified.

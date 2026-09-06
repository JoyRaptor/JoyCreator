# SPEC E — Render the mesh warp (GL preview + export)

**Difficulty: HIGH. Parity-critical GL work. Give this to your strongest agent, not the cheapest.**
**Read `_RULES_READ_FIRST.md` first.**

## Background

The mesh warp ENGINE is built, tested and green — 11 classes in
`app/src/main/java/com/fadcam/ui/faditor/transform/mesh/`, 66/66 in
`bash tools/jvm-harness/run-mesh.sh`, zero `android.*` imports. It does the maths and produces
triangles. **Nothing renders it yet.** That is this spec.

Read first: `tasks/SPEC_20260902_MESH_WARP.md` (architecture and cost),
`tasks/SPEC_20260904_PUPPET_ARCHITECTURE.md` (why topology and deformer are separate — do not fuse
them), and the engine's own classes.

## The contract the engine hands you

Hold one `MeshEngine` per GL thread. Per frame:

```java
if (!engine.update(spec, timeMs)) {
    drawTheOrdinaryUnbentWay();   // false == identity fast path
} else {
    // upload engine.buffers().positions      EVERY frame
    // upload engine.buffers().uvs / indices  ONLY when buffers().topologyStamp() changes
    // glDrawElements(GL_TRIANGLES, buffers().indexCount(), GL_UNSIGNED_SHORT, 0)
}
```

- `update(...)` returning **false** means null spec, unknown topology, or a pose that deforms
  nothing. A project with no bend must therefore create **no FBO and compile no program**.
- **Back-face culling OFF** — a deliberate fold reverses winding and must still draw.
- `positions` are in object-local **unit space**. Push them through the corner-pin homography, then
  the placement matrix.
- **Do NOT divide by `w` in the vertex shader.** Hand the rasteriser a real `w`; that is what avoids
  the diagonal perspective seam across the picture.

## What to build

1. **Preview.** Render the mesh in the GL chain where the flat image quad is drawn today — see
   `compositor/FxPreviewTextureView` (`drawPip`, `Pip.ofImage`) and
   `compositor/FxLivePreviewController` (`fxPipFor`, `glImageOverlays`).
2. **Export.** The same mesh, same geometry, in `export/` — look at how `ImageBlendGlEffect` and
   `ImageOverlayFrameOverlay` place an image today.
3. The "stamp" approach from SPEC_20260902 §1: render the warped mesh into a frame-sized texture, so
   the result is a drop-in replacement for the frame-sized bitmap the blend stage already consumes.
   **That is what keeps blend modes, chroma key, masks, per-object FX and adjustment layers working
   over a warped image without touching any of them.** Verify that claim against the current code
   before relying on it, and say what you found.
4. Model + storage wiring the engine could not do itself:
   - `model/TextOverlayItem`: a nullable `MeshWarpSpec mesh` field, `hasMesh()` returning
     `mesh != null && mesh.hasWarp()`, and `wantsGlExport() |= hasMesh()`.
   - `project/ProjectStorage`, field name `"mesh"`, in the four places it already handles
     `"compositing"`: clip write ~1479, clip read ~1844, overlay-object write ~2417, overlay-object
     read ~3212. `MeshWarpSpec.toJson()` returns null for an identity pose, so a project that opens
     the tool and changes nothing saves byte-identically — keep that property.
5. **Enable the Bend button.** `TransformOverlayView.setBendAvailable(boolean)` exists with no
   callers; the ring slot is drawn greyed and inert. Turn it on only once the renderer genuinely
   works, and wire the net's dots to `MeshProjection.dragToHandle(...)` +
   `MeshGuard.accepts(...)` before committing. One drag = one `MeshPoseTrack.put(...)` = one undo.

## Cost — JoyRaptor has been given these numbers, so do not exceed them quietly

~1.5–2 ms per warped object per frame on his Note 20 (Exynos 990); 8.29 MB VRAM at 1080p; roughly
3.6 s of extra export time per minute of video per warped object. One to three warped objects should
be invisible; five noticeable; eight will drop frames. If your implementation costs materially more,
say so plainly rather than shipping it.

## Acceptance criteria

1. A project with no mesh renders and exports **byte-identically** to today. Prove it.
2. Preview and export produce the same warp. State the shared source of truth — one method or one
   shader string, never two transcriptions.
3. A warped image still composites correctly with blend modes, masks and adjustment layers above it.
4. `bash tools/jvm-harness/run-mesh.sh` stays green (66/66).
5. One drag = one undo press.
6. `./gradlew assembleDefaultDebug --console=plain` → BUILD SUCCESSFUL after your last edit.

## Deliver

Where the mesh is rendered in each surface and what guarantees parity; the stamp-approach
verification; measured or reasoned per-frame cost; the no-mesh no-op proof; harness results; the
build verdict; compile-verified vs device-verified.

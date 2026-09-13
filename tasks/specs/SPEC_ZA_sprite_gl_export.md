# SPEC ZA — The sprite export must go through GL, like the preview now does

**Dispatchable. Difficulty: MEDIUM — one new class that mirrors an existing one closely.**
**Read `_DISPATCH_RULES_20260913.md` first. Then `SPEC_Z_warpable_objects.md` for context.**

**THIS IS THE FIRST SHEET OUT THE DOOR. It closes a live preview/export gap.**

---

## The gap, stated plainly

On 2026-09-13 sprites became GL citizens in the PREVIEW: a sprite that carries a corner pin or a
mesh is emitted into the composite plan by `FxLivePreviewController.spritePip` and drawn inside the
shader, where a blend above it can sample it, a mask can cut it and an adjustment layer can grade
it.

**The export did not move with it.** A bent sprite is still drawn by
`CompositeExportOverlay`'s Canvas pass, via the `SpriteMeshDraw` fallback. So today:

| | preview | export |
|---|---|---|
| a bent sprite's warp | GL stamp | Canvas `drawBitmapMesh` |
| blend above it samples | the sprite | **the video** |
| a mask cuts it | yes | **no** |
| an adjustment layer grades it | yes | **no** |

The warp itself matches (one deformation authority — `LatticeDeformer.eval`), so this is not a
shape mismatch. It is a **compositing** mismatch, and it is exactly the class of bug rule 7 exists
to prevent. Close it.

## What to build

### 1. `SpriteBlendGlEffect`, mirroring `ImageBlendGlEffect`

`app/src/main/java/com/fadcam/ui/faditor/export/ImageBlendGlEffect.java` (`final class`, line 54)
is the image's equivalent and is the model to follow closely. It:

- holds one `TextOverlayItem` (line 57) plus the timeline duration and a time offset,
- builds a `Program` that samples the video texture and stamps the item over it,
- calls `item.installMeshCurve()`, reads `item.getMesh()`, refuses a null/identity spec and falls
  back to a flat draw (~line 558-572),
- stamps through the SHARED `MeshStampGl`.

Write the sprite equivalent against `SpriteOverlayItem`. It already exposes everything needed and
the names match on purpose: `hasMesh()`, `getMesh()`, `installMeshCurve()`, `meshLocalTime(long)`,
`hasCornerPin()`, `animatedCornerPin(long, float[])`, `animatedCenterX/Y`, `animatedSizeFraction`,
`animatedRotation`, `animatedOpacity`, `isFlipH/isFlipV`.

**Do not write a second warp.** `MeshStampGl` is the one stamp and it takes a texture; it does not
know or care what the texture holds, which is exactly why it can serve sprites unchanged.

**The texture.** The image effect decodes its own bitmap. A sprite's pixels come from its sheet —
get the cell via the sheet renderer the Canvas export path already uses
(`CompositeExportOverlay`'s sprite block, ~line 640-690, uses `r.drawCell(...)` and
`SpriteFrameResolver.resolveCellAt`). Rasterise **whatever the sprite shows** — cell or rig — into
one bitmap and stamp that. `SpriteMeshDraw`'s class doc explains why rasterise-then-warp is the
design and not a convenience: it is what makes the bend a property of the SPRITE rather than of a
cell, so a cell swap cannot change it.

### 2. Emit it from `ExportManager`

`ExportManager.java` ~line 4155-4165 is the loop that emits `ImageBlendGlEffect` for every image
where `wantsGlExport()`. Add the sprite equivalent beside it, gated on
`SpriteOverlayItem.wantsGl()` — the predicate already exists and is the same one the preview uses.

**Chain position IS z-order.** Read the comment at ~4165 about where adjustment layers are inserted
before you choose where to append, and say in your report why your position is right.

### 3. Drop it from the Canvas pass — exactly complementary

`CompositeExportOverlay.filterSpriteItems` (line 454) currently keeps every sprite whose time range
overlaps. It must now also DROP any sprite that GL is drawing, exactly as `filterTextOverlays`
(line 519) does for images.

**`filterTextOverlays`'s own doc says the two decisions must be exactly complementary or the item
is drawn twice or not at all. Read it.** Getting this wrong gives you a double-drawn sprite (the
Canvas copy painted on top of the graded GL one) or an invisible one, and both look like a
different bug than they are.

### 4. Leave the Canvas fallback in place

`SpriteMeshDraw` stays. It is the fallback for anything GL cannot take — the same texture-vs-Canvas
split `OverlayTextureCache.canUseTexture` already documents as "one predicate, one place". Do not
delete it and do not route around it.

## Acceptance criteria

1. A sprite with a bend exports through GL, and `filterSpriteItems` drops it from the Canvas pass.
   **Show the two decisions agreeing** — name the predicate both consult.
2. A sprite with NO pin and NO mesh is untouched: same effect chain, same Canvas draw, byte-identical
   export. Prove it by stating what `wantsGl()` returns for it and which branch runs.
3. **A blend above a bent sprite composites against the SPRITE, not the video.** This is the whole
   point of the sheet. Export a frame with a SCREEN-blended image over a bent sprite and show it.
4. Preview and export agree: export one frame and compare against the preview at the same timestamp.
   `run-preview-parity` and `run-frame-parity` stay green.
5. `bash tools/build-verify.sh SpriteBlendGlEffect` → VERIFIED.
6. The standing harnesses stay green: spect 11/11, specr 13/13, specq 7/7, mesh 66/66, spech 36/36,
   adjust 38/38, matte 25/25, persist-lint, bakepop, escape, pinbudget, speck, flip, rotation.

## Boundaries

- You own `export/SpriteBlendGlEffect.java` (new), the emission site in `export/ExportManager.java`,
  and `filterSpriteItems` in `export/CompositeExportOverlay.java`.
- Do **not** touch `MeshStampGl`, `transform/mesh/**`, `TransformQuad`, `CornerPinTransformHost`,
  `AffineTransformHost`, or `SpriteMeshDraw`.
- Do **not** touch `sprite/SpriteSheetEditorActivity.java`, `sprite/SpriteBaker.java`,
  `sprite/SpriteSheet.java` or `tools/spritelab/**` — another lane owns those.
  `sprite/SpriteSheetRenderer.java` is READ-ONLY: call `drawCell`/`cellRectBitmap`, change nothing.

## Deliver

The new class and where it is emitted. The proof that the GL decision and the Canvas drop are
complementary. The blend-over-bent-sprite frame for acceptance 3. Build verdict via the dex.
Compile-verified vs device-verified, per item.

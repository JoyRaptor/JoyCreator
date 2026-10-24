# Watercolour line wash frame design (2026-10-23)

Read-only architecture note for L4/L5. No production files changed.

## Transform contract

`Homography` supports translation, rotation, separate X/Y scales, skew and perspective. `LineTransform.apply` accepts every such map for ordinary records, maps each sample independently, and already computes a local Jacobian for sensor directions and a centroid scale. Therefore origin + angle + scalar scale only follows translation, rotation and uniform scale exactly; it cannot represent nonuniform scale, shear, or perspective.

For exact wash-coordinate invariance under every invertible projective transform, store a wash-to-document homography `F` (nine normalized f64 coefficients; homogeneous scale is irrelevant). Initially `F = identity`, preserving the existing POC and legacy sampling. For a transform `h`, update `F' = h ∘ F`. The finish shader samples with `F'⁻¹(docPx)`. Corresponding points then have the same wash coordinate: `F'⁻¹(h(docPx)) = F⁻¹(docPx)`. Refuse/fallback explicitly if the composed frame is singular or cannot be represented; do not silently manufacture NaNs. A 2×3 affine frame (six values) is smaller but cannot preserve texture under perspective. Four values (origin, angle, scale) are suitable only if the product contract is deliberately limited to uniform scale.

Keep the current seeded phase in wash-local doc-pixel coordinates: `WatercolourPass.begin` derives `phase=(-backdropOffsetX,-backdropOffsetY) * 1254 * textureScale`; sampling is `(localWashPx + phase) / u_backdropWorld`. With identity `F`, this is byte-for-byte the current coordinate equation. Identity/null legacy frames therefore reproduce current initial POC output. Recolour remains a finish-only change.

## Shader and paper boundary

Today `jb_wc_finish.frag` samples the backdrop at `(v_docPx + u_backdropOffset) / u_backdropWorld`; tiled boards call `jb_tileTextureR`, which calculates periodic blend weights in document coordinates, computes derivatives before wrapping, then samples four document-period copies. For a line-local frame, retain those document-space weights and periodic candidate points, map each candidate through `F⁻¹`, and use derivatives of the unwrapped local coordinate for `textureGrad`. Add a frame-aware tile texture helper rather than changing paper's `jb_tilePaperSurface(docPx)` path. Ordinary non-tiled boards sample the transformed local coordinate directly.

Paper height/cut and fine grain stay document/world anchored. Pooling gravity remains document-down `(0,+1)`. Consequently a whole finished watercolour raster cannot be expected to equal an affine-transformed old raster: these are intentional world-space effects. Tests should assert wash-local coordinate invariance separately, and assert paper/grain/gravity retain their current document-space inputs.

## Record and format compatibility

Append a defaulted nullable wash-frame value to the end of `StrokeRecord` for source compatibility. Null is legacy identity. New live watercolour strokes may explicitly carry identity. Include the frame in `GlWatercolourLineRenderer.Geometry` equality so frame edits rebuild geometry/finish state; continue excluding tint so recolour does not re-dab.

`StrokeCodec` is its own binary format boundary: versions 1–4 are accepted today; version-gated flags are bits 0–2 for sample channels, bits 3–4 from v3, and bit 5 from v4. Unknown bits are refused, v4 records/lists reject trailers, and `decodeAll` derives v1/v2 sequence from file order. Encode a frame only under a new codec version and new version-gated flag, in a documented optional block before `sampleCount`; older records decode to null identity. Keep strict trailer refusal for the new version. Round-trip exact matrix bits and exercise old versions.

The document archive stores `strokes.jbs` as opaque codec records. Modern archives embed frozen brush copies under `brushes/`, and `JbArchive.read` verifies that frozen identities cover the document's brush list; replay after reopen should resolve from that map without consulting the external library. `DOC_VERSION` is 11 and its schema gates document JSON semantics; R30 says document/brush version numbers are assigned at landing, while R31's `DOC_VERSION` bump rule concerns new serialized fields. The frame is a field in the separately versioned `StrokeCodec` record, not a `JbDocument` JSON field. The appropriate direct refusal gate is therefore the stroke codec version; have the lead apply the repository's R31 landing policy before deciding whether to redundantly bump the enclosing document version.

## Minimal implementation and test checklist

- `core/stroke/StrokeRecord.kt`: append frame type/property with legacy identity semantics.
- `core/stroke/StrokeCodec.kt`: new codec version/flag/block; old versions decode identity; unknown future versions and flags refuse in words.
- `core/select/LineTransform.kt`: compose frame with `h`, preserving the `apply(r,h,Weight)` signature and existing sample/weight rules.
- `androidkit/gl/watercolour/GlWatercolourLineRenderer.kt`: include frame in geometry cache identity.
- `androidkit/gl/watercolour/WatercolourPass.kt`, `shaders/lines/jb_wc_finish.frag`, `shaders/jb_tile_paper.glsl`: pass/invert frame for backdrop only; preserve tile blending in document coordinates and fixed-world paper/grain.
- JVM transform tests: identity fallback; translated, rotated, separate-axis scaled, sheared and perspective point pairs preserve local wash coordinates; singular-frame policy; captured-record path unchanged.
- Codec tests: current version exact frame round-trip; genuine v1–v4 records decode identity; future version/unknown frame flag refuse; trailer policy.
- Cache/shader tests: frame-only edits invalidate finish state; tint-only edits use recolour; paper, fine grain and gravity remain document-space.
- Archive round trip: frozen watercolour preset plus framed record reopens and resolves/replays from archive-owned brush definitions with external library absent. Compare the replayed record/frame and wash coordinates; do not assert whole-finish raster equality under world-anchored paper/grain/gravity.

No build, device, or UI verification was run for this design note.

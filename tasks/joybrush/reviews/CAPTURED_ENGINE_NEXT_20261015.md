# Next captured engine integration — root design constraints

The exact249 private captured copy is now device-proven. It is not callable as a
production renderer: its admission requires empty native/history/pools and no bound
scene. The new explicit Scene capability is separately under backend verification;
the existing GlPaintEngine renderer does not implement it and still refuses captured
records. Keep cold load, saved-look validation, archive writing, Select and View gates.

The next useful change is an engine-owned rendering transaction, then opt-in of the
real recorded renderer. Deliver actual captured add/integer move/undo/redo/bake on an
occupied root; do not make another empty-engine diagnostic wrapper the endpoint.

- Keep raw replay in capture coordinates, complete watercolor pooling first, apply
  persisted domain/exclusions, then integer placement with CapturedSourceCopyPlan.
  Return the placed allocation keys, including transparent surviving keys. Current
  physical-cel composition/bake clipping happens afterward and is a separate contract.
- Reuse private beginStrokeBuffer rather than public beginStroke/endStroke. Preserve
  source opacity once and its operator separately; never use the final saved look as
  a canonical source or transfer its texture as a slab.
- Admit mutable working-tile bytes under an explicit budget before allocation: native/mask/media owners,
  history, slabs, scene caches, pools, already staged fresh results, canonical replay,
  watercolor scratch and all planned destination tiles. Fixed paper/grain/mip resources
  need separate allocation metadata before anyone claims a whole-engine byte total.
  Count distinct tile names with their real format; float registry entries alone are not ownership. The empty-engine
  per-attempt bound cannot be relabeled as a production total-memory bound.
- Scene-staged fresh results are not yet in publishedPaint or activeTextureOwners.
  An engine-owned, physical-epoch allocation ledger must track every returned line/look/
  bake result until release, including ordinary units staged earlier in the same edit.
  Retire entries centrally in recycleLayerTex and direct-delete paths, and clear them
  on physical forget/release. Renderer.release alone is insufficient: successful bake
  outputs become history/slab owners and retire through UndoLog/reset source release,
  while external cache-retirement acknowledgement can also bypass renderer.release.
  A closure-local ledger would retain stale entries across deletion/name reuse.
  Deduplicate the ledger against published owners when accounting. Failed temporary
  allocations never enter reusable pools; old root/source/cache names remain borrowed.
- Reserve before ordinary lines, looks and bake callbacks too. A later captured admission
  cannot account for earlier private output batches by inspecting only published owners.
  Reserve an entire bake batch before its first tile, or register every private output
  immediately. One composeRecordedTile peak is two RGBA32F scratch tiles plus its RGBA8
  output (36 bytes/texel); returned RGBA8 bytes alone miss that peak. Recycled outputs
  remain resident in pools. Stroke/smudge pools have their actual R8/R16F/RGBA8/RGBA16F
  formats, and physical context retirement clears accounting without deleting old names.
- Generalizing capturedRecipeLease must validate its canonical and placed names against
  both engine owners and those staged results before use. Removing only requireLocalPaint
  and the empty-pool checks leaves the ownership and admission contract incomplete.
- Document one scratch-render GL contract. Explicitly set copy/read/draw bindings and
  unpack/PBO state required by the operation; do not inherit the debug probe's hostile
  state claim without implementing it. Preserve the original failure if cleanup throws.
- Capture physical context identity before work. Common prepareRecordedLine cleanup
  currently touches GL in finally without an epoch guard; audit that path before a
  general captured transaction claims context-retirement safety. Abandon old names
  after retirement and never delete or restore them into a replacement context.

Acceptance must exercise real scene publication with prior native owners, populated
pools, multiple staged lines, clipping holes, negative placement, transparent keys,
failed admission/allocation and actual undo/redo/bake. Compare complete source and
display pixels on Ink and stock-size watercolor. Cold/save/recovery remain separate
gates; compilation or fake handles establish no GPU parity or live View connection.

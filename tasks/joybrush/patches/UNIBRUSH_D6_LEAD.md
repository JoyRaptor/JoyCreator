# D6 held Lead adapters

Incremental patch: apply after UNIBRUSH_D4_LEAD.patch and both D5 engine/View patches.
Four proposed files: GlPaintEngine, RecordedPromotionExternalTextureInventory,
JbCanvasView and ColdRecordedLoadPlan. Canonical protected files are untouched.
This patch is uncompiled as a combined application and has no device acceptance.

UI and GL each own a separate StampTipResolver. UI selects one contact seed before
resolving resources; BrushDabber and capture use that same seed. The PNG provider
is consulted for ANY BrushAssetPaths.required image (including a dual-only image).
Candidate scene callbacks resolve once from their own immutable PNG bundle and
record seed, then pass both primary images and dual mask through admitted replay,
ordinary replay and captured-source leases. A mask must match recipe and seed.

One DualMaskTextures owner is created/released with the physical GL context, uploads
before the first draw, and ends on finish/cancel. Context replacement abandons old
names. All protected-name inventories include the owner; a failed retirement stays
reported. Fixed CPU/GPU resource estimates remain separate from mutable tile bytes.

Cold static admission preserves source images without baking until first edit.
Legacy NativeLines and diagnostic routes retain sampled-or-dual refusals: they have
no immutable source-bearing root. D5 core CPU selection/export overloads carry only
sampledLookup; dual rendering there requires the separate current InkTiles.prepare
dualLookup(record,preset) overload and matching host adapters. A brush-id-only lookup
cannot resolve a seed-specific dual mask. These unadapted routes must keep refusing.

Remaining Lead work includes bound live RecordedScene activation, resource-aware
selection/rebrush/export consumers, combined compilation, candidate cancellation,
undo/redo and cold first-edit tests, then Note9 raster/editable line checks. The
standalone shader and resource-owner tests do not establish those transactions.

# Bounded captured renderer — working checkpoint

Source `1bfbe8c987bf5dbd6364078ff90368ae6cafde7a` passed full backend **850 tests in
85 suites**, zero failures/errors/skips, and Android **203 tests in24 suites**, zero
failures/errors/skips. APK assembly passed in2m20. Exact preserved APK:
`C:/Temp/jb-lead-ops/apk-1bfbe8c98/joybrush-1bfbe8c98-arm64.apk`,170874056bytes,
SHA256 `5153badbe47c1d2596b03e0691a41347e1adbabe2060f4f9539243fb33f49580`.
The fresh Home-readiness installation gate refused; this APK has no device acceptance yet.

`createCapturedRecordedScene` is an internal explicit opt-in to the real renderer.
It can stage captured sources on an occupied engine with native siblings, source slabs,
history and pools. Replay finishes full-resolution pooling before applying the historical
capture domain and exact integer placement. Transparent destination allocation keys remain
source identity. Current physical-cel clipping is applied to composition and bake afterward.
The ordinary factory remains captured-incapable; View/live recording, Select, source save/
cold-load/export and paper changes remain guarded.

This first connection deliberately keeps its diagnostic-sized raw/path/brush/footprint
profile. It is **not** a general live-contact policy. Do not expose Editable lines by
treating its64-sample or64-tile limits as production requirements. Live finalization must
adopt actual pooled scratch sources without first committing native pixels, and general
replay/admission must support the accepted normal-length contact families.

Mutable working-tile admission counts actual native/mask/media/history/slab/cache/pool/WC
tile owners, direct replay/composer returns, earlier staged callback outputs and pending
mixed-step endpoints. Names deduplicate at the larger format cost. Whole replay/captured
destination and full bake/composition peaks are checked before allocation; two RGBA32F
scratch tiles contribute32 bytes/texel beyond the output batch. Fixed paper/grain/images,
screen targets, buffers/programs and driver overhead are outside this tile allowance.
Metadata tracking is not GPU ownership. Central retirement removes tags while recycled
pool allocations stay resident. Physical retirement clears metadata without deleting old
context names. New returned raw units retain an epoch; old debug-constructed units can
remain epochless and do not establish general stale-unit safety.

Cleanup now preserves the first failure, guards physical context and reclaims successful
outputs whose final cleanup cancels their return. These source changes and host tests do
not prove real OOM/driver/context-loss recovery. The existing cold injected-fault device
evidence covers its own loader, not this new renderer. Original paper material provenance
is still a durable-source design requirement; keep paper immutable for the admitted scene.

The debug-only `recordedCapturedJointProbe` retains independent canonical GPU readbacks,
uses a separately tested per-texel CPU placement oracle, then checks actual cached source
keys/bytes and composed display under mixed native publication, public undo/redo and
source-copy bake/undo/redo. It also requests zero-budget refusal on an occupied engine
before GPU replay. Compilation is its only evidence until the phone run completes.

Earlier failures remain visible: the first WIP adoption missed a test directory and failed
test compilation; it is not exact-source proof. Exact cfe1eb152 passed33 focused tests,
then its full850 run exposed one older reflection fixture needing the original private
three-argument diagnostic signature. A compatibility overload restored that entry point;
the later exact1bf full850 passed. Failed XML/log/count are preserved in
`C:/Temp/jb-lead-ops/captured-admission-backend-failure-cfe1eb152`.

Next work is a proper bound source-bake proposal and engine publication API, followed by
actual live scratch handoff, continued ordinary/media joint writers, asynchronous Select
completion and source-aware picking, then save/cold recovery. None is marked complete here.

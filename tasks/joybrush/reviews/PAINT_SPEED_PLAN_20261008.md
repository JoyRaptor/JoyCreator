# Painting speed: preserve quality, measure the whole path

Owner target: three to four times faster painting and substantially lower visible latency. Target is not a measured result or guarantee. Preserve all real pen history, brush physics, float precision, texture detail and original compositing.

Current evidence: desktop large-fast-C pipeline cost changes little across raw input densities while geometry degrades sharply at lower density. Do not thin input as the first optimization.

Verified root checkpoint: actual DryStrokeBoundsTest1/0failure, Androidkit compile SUCCESS39s with768m heap. Periodic Tile brush wiring committedbe616a0a after112 GPU probes/56 seam pairs and mutation checks. No phone deployment or measured speedup. Media-look conditional paper-read candidate remains uncommitted pending exact GPU output comparison.

OpenCode assignments:
- Count actual dry/wet GPU work, copies, uploads, allocations and queue/scheduling behavior. Source workload estimates must stay separate from measured timings.
- Add disabled-by-default Android trace slices around dry upload/delta/apply/copy and look submission. These measure CPU command submission, not GPU execution.
- Finish geometry helper verification and root Androidkit compile under bounded memory admission.

Root candidates requiring measurement and pixel equivalence:
- Avoid a canonical paper surface read when the media look has relief0 and is not height-debug mode1. Current renderLook always uses relief0/mode0; shader still reads the surface. Other paper/bake/crush inputs remain required. Headless original/candidate comparison must cover relief and debug modes, all media and mixtures before any shader landing.
- Reuse direct upload staging storage without changing Float bits, instance order or buffer limits. Measure allocation/GC contribution first; it may be a small fraction of total time.
- Avoid unnecessary look rerenders/store copies. First establish all shader read/shadow halos and persistence/Undo semantics. Do not simply crop to stroke bounds or defer durability without proving correctness.
- Correct window crossing with frozen per-frame paper input and disjoint write interiors, not sequential dryFrame chunks. See DRY_WINDOW_RENDERING_PLAN_20261008.md.

Device acceptance needs CPU/GPU frame times, input-to-visible latency, sustained fast strokes, stalls, memory and heat; median alone is insufficient. Compare the same recorded input and document at the same zoom/brush settings. Desktop SwiftShader timing cannot establish Note20 speedup. Note20 wireless currently undiscovered; Note9 untouched.

Primary references: https://developer.android.com/agi/sys-trace/long distinguishes CPU active/submission/wait time from GPU slices; https://developer.android.com/topic/performance/tracing/custom-events describes app trace markers. No changes to floating-point precision or brush appearance are implied.

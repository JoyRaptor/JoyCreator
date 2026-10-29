# Approved paper evaluation library — 2026-10-29

Owner selected45 entries in joybrush-material-choices.json. These are evaluation
materials, not the final APK selection or accepted physical feel. Three expanded
white papers were already complete;42 others use exactly3 native image edits each
(vertical, horizontal, centre) with D overlap. Existing material ids/defaults remain
unchanged; proposed catalogue entries use eval_ ids. No engine/View/Activity edits.

Source branch codex/paper-approved-20261029 starts at origin/joy-creator
aa6cc4143 in C:/Temp/jb-paper-approved-20261029. Main remains untouched. Original
selection/hash manifest, raw images, prompts, stage receipts and source-preserving
composites are outside the checkout at:
C:/Users/JoyRaptor/.codex/visualizations/2026/10/01/01a0f85c-d650-7f61-82d4-ba5eed71ea4f/approved-surfaces-20261029.

## Plan / current gate

- [x] Verify45 selections against known gallery source hashes; preserve notes.
- [x] Prepare42 offset templates; retain3 already-expanded originals.
- [x] Complete126 remote edits; all45 source composites exist, originals hash-verified,3x3 repeats inspected. Regular weaves still need visual judgement; no invisible-repeat or phone-quality claim.
- [ ] Bake material-specific height/slopes/fluid maps under shared resource admission.
- [ ] Validate runtime catalogue/packing compatibility and exact file/aggregate budget.
- [ ] Publish isolated resource overlay + test request to root; owner phone feel remains.

## What reactivity requires

The same visible structure must drive brush contact at the same document pitch.
Height/slopes determine dry contact and directional catch; fluid maps and catalogue
numbers model formation, fibre direction, pore capacity, sizing, compliance,
absorption and wicking. Those properties are hypotheses requiring real-pencil and
paint judgement, not physical measurements recoverable perfectly from a photo.

build_approved_evaluation.py preserves the selected colour look, derives bounded
material-specific relief, uses shared fixed slopeRange0.10 (avoiding normalization
that erases strong/subtle amplitude differences), and computes h² from actual8-bit
height samples. Rice/chunk/chalk use structural edge energy rather than absolute
brightness-to-height; dark pigment is not defined to be a pit. Smooth parchment,
silk and handled paper keep lower physical tooth than jute/cement/firm crumple.
Primed canvas has lower absorption than raw canvas. These are evaluation defaults.

All look/surface entries use rotatable=false to preserve photographed lighting and
weave orientation; light defaults off to avoid double lighting. Matched2048 grids,
512 fluid maps, explicit pitch conversion and matched hex cell document dimensions
preserve visible/physical registration. Height-only PNGs are expanded by the
existing SurfaceMaps loader; packed diagnostic maps are not runtime payload.
Master16-bit relief and raw sources remain outside the shipped resource set.

## Resource and phone ownership

Root grants only sequential Python baking: one2048 material, shared jb-gradle.lock
via exclusive CreateNew, >=2200MiB free after acquisition, inspect heavy jobs and
watchers, release owned claim in finally after child exits. No waiting/retry wrapper,
watcher killing, Gradle/APK or phone grant. First bake must record observed peak
RAM, duration and bytes before repeating. At last root snapshot1572MiB was free,
so no bake was admitted. Remote image calls hold no local build/device lock.

Current PaperResources/GrainTextures read packaged catalogue/resources; an external
paper-pack loader is not present. An evaluation overlay is therefore a preparation
artifact, not a claim that copying a ZIP to the Note9 activates these papers. Root
will sequence a test APK after file/size/source evidence and fresh phone readiness.
No installation is authorized for this lane; the owner is plugging in the Note9.

## Handoff requirements

Return source/base/HEAD, exact selection-to-eval-id mapping, catalogue overlay,
per-file dimensions/format/bytes/SHA256, aggregate incremental bytes versus the same
latest baseline, diagnostics vs runtime assets, compatibility tests actually run,
failures and gates. Root preserves existing ids/defaults and reconciles its newer
coordinator source before building. No GooglePlay size-limit assumption is used as
a licence to discard owner-selected evaluation materials.

## Monitor review follow-up

The monitor now uses canonical LOCALAPPDATA/Temp, protects claim initialization,
stops only its own child on monitor/CIM/Refresh failure, waits for confirmed
termination, and retains the claim when termination is unresolved. Unknown peaks
for a too-fast child remain null, never a measured0. Uncertain watchers defer.
The injectable lease lifecycle passed5 fault cases (write-before, write-after,
monitor failure, unresolved termination retaining claim, quick-child unknown peak).
Tests use an isolated fake claim and fake child, never the shared lock or2048 work.
Recipe4 small tests passed after bounded-array changes. No full-size bake yet.

## Source completion and current block

All45 expanded colour sources are prepared:126 native edits for42,3 prior crumples
retained. First6 final overlaps were corrected from125 to D126px without another
generation; their125px centre-input context remains recorded. Older1024px originals
become1536px;1254px originals become1882px. Some generated returns are1254px for a
1024px input: only generated inserts were resampled to their contextual coordinates.
Original source files were verified against all45 owner-selection SHA hashes.

[Exact source receipt](approved_paper_source_files_20261029.json):45 PNGs,
246,362,635 bytes outside runtime assets. Existing packaged paper payload remains
25,201,898 bytes; added runtime asset bytes0. APK/install delta is unmeasured.
No full-size reactive maps are baked and no phone capability has been added.
Raw inputs/outputs/prompts/hashes are in the local generation-receipts.json; all126
stages exist. The source gallery is index.html beside that file.

First real bake admission returned NOT_ADMITTED at1256MiB free, below2200.
No shared claim or child was created. No retry loop or unrelated process killing.
The owner was asked whether they can free memory or prefer baking later; no answer
is assumed. First admitted material should be expanded-firm, then STOP for root's
peak/time/size review before repeating any full-size job.

## Phone profiles and exact evidence limits

approved_phone_profile.py prepares512/1024 candidates from a completed master:
area-filter height, retain E[h-squared] independently, recompute production-packed
slopes with per-texel slope-range/pitch scaling, and average fluid double-angle
vectors while reducing coherence. Look/surface/fluid/hex document pitch stays
paired. Packed profiles preserve unresolved tooth variance; new ids avoid all
existing/default mappings. At most3 materials in the first phone batch. No engine,
cache, loader, default, APK or phone changes were granted or made.

Small recipe/profile tests6/0 in0.211s include checkerboard variance and physical
slope-unit checks. Lease fault tests6/0 in0.419s additionally refuse a changed
claim identity before release. These are synthetic/fault tests, not production
Kotlin, full-size baking, GPU, APK or device acceptance. Full master/profile baking
is still UNRUN. Modelled RGBA8 logical texel+mip payload:44MiB per2048 material
(45 about1.93GiB),11MiB at1024 (first3 about33MiB),2.75MiB at512. This excludes driver
padding/other textures and is NOT measured residency. GrainTextures retains visited
names until release/forget; no eviction was added. Current decoded CPU images,
lazy CPU mip chains and temporary bitmap/IntArray/byte/direct uploads need separate
measured headroom. Compressed JPEG size does not establish runtime memory usage.

## Sequential master checkpoint

Seven of45 offline masters now baked: expanded-firm, linen1/2/3, duck1/2/3.
Root verified the first pilot and cleared sequential masters under unchanged
per-material resource gates. The sequence stopped at jute-2:1277MiB free,
below2200 admission. No retry, active child, build or phone action.
[Aggregate output hashes and resource receipts](approved_paper_bakes_20261029.json)
record each completed material; earlier UNRUN statements describe the previous
checkpoint. Added packaged runtime bytes remain0; APK delta unmeasured.

Phone profile encoding is PNG RGBA8, packed=true: R/G are encoded slopes,
B is area-filtered height, A is independently area-filtered E[h-squared].
SurfaceMaps.expand returns packed bytes unchanged. GrainTextures decodes with
inPremultiplied=false, extracts straight RGBA and uploads GL_RGBA8, then generates
mips. This source contract supports the separate second moment; an L-only profile
would not. This is source inspection, not actual Android decode/GPU verification.
Phone-profile generation remains gated; at most3 initial materials.

## Actual phone-profile experiment

Both authorized expanded-firm profiles completed under the protected lease,
one job at a time, with no package or phone changes.
[Exact profile/input/output/resource receipts](approved_paper_profiles_20261029.json):
512 encoded697966bytes, modelled GPU+mips2883580bytes, decoded base2162688bytes;
1024 encoded3138430bytes, modelled GPU+mips11534332bytes, decoded base8650752bytes.
Master encoded3825625bytes. Peaks159.96/183.19MiB and durations0.960/1.424seconds
are observed PC child measurements; GPU/decoded quantities are models.
Exact serialized RGBA, independent second moment, dimensions and document pitch
assertions passed. Android decode, GL upload, residency and brush feel remain
unverified. Stop after these two outputs for root comparison/first phone batch;
master sequence remains7/45 stopped. Earlier UNRUN references are historical.

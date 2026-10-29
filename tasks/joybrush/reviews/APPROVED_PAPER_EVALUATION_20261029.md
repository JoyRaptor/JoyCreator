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
- [ ] Complete126 remote edits and inspect repeat/colour/structural QA; retain failures.
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

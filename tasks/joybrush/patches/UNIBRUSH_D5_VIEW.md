# D5 held View / scene / cold adapters

This directory contains exact proposed source and a generated patch. No canonical file was edited, no build was started, and no device was used. These proposals and the five proposed tests are uncompiled.

`UNIBRUSH_D5_VIEW_HELD.patch` contains seven files. `make_view_patch.py` regenerates them with exact replacement-count assertions. JbCanvasView uses the D4 `lead-proposed` source as its base; all other files use the canonical source as read on 2026-10-20. Do not apply blindly over other Lead edits.

## Implemented interfaces

- `RecordedScene.State.brushAssets` and `scene.brushAssets` retain immutable `FrozenBrushPngs` owners. Every nonempty State construction carries them. An attempted replacement/removal of previously frozen bytes refuses before rendering. Both current and historic definitions stay retained through undo/redo.
- `Renderer.line(address,record,preset,pngs)` defaults to the existing three-argument callback. Every actual warm/render call uses the four-argument callback with its own old/candidate resources. `CapturedSourceRenderer.admitCapturedSource` has the corresponding four-argument default overload; captured admission also uses explicit candidate context.
- `stageJoint(...,frozenAssets:Map<String,FrozenBrushPngs>?=null)` and `stageJointProjected` match the engine proposal. `stage(...,frozenAssets=brushAssets)`, `create(...,frozenAssets=emptyMap())` and `createSaved(...,frozenAssets=emptyMap())` append arguments. Maps are detached before callbacks. New bundles independently undergo `FrozenBrushAssets.prepare(...,retainEntries=false)` at the scene boundary; history-only reuse does not rehash. Because staging validates more than once, a newly introduced bundle can currently be rehashed more than once during a proposal. This is bounded but can be optimized after correctness validation with a single admitted-candidate value.
- `LineSnapshot(...,brushAssets=emptyMap())` appends the resource map.
- `RecordedSnapshot.capture(...,brushAssets=emptyMap())` filters by exported frozen definitions, then validates manifest/64MiB closure before readback callbacks. `LineSaveMapping.apply(...,brushAssets=c.brushAssets)` preserves the exported closure.
- `ColdRecordedLoadPlan.Prepared.frozenBrushAssetsCopy()` exposes a detached map retaining stable immutable bundle owners. Cold preflight accounts encoded ownership and a conservative largest PNG export plus manifest before JSON/arrays/PNG preflight. Image masks remain unresolved until first edit; saved looks permit static cold admission. All new variable profile/quad/fade/sampled fields are included in cheap accounting.
- `FrozenBrushCache.freeze(revision,known,knownAssets,preset,candidate,newId)` is the asset-aware overload. It keys actual bytes, retains only one modern candidate, drops the prior candidate before producing another, and performs full archive admission on a cache miss. Existing legacy signatures/test injection remain.
- View `brushPngs:(BrushPreset)->FrozenBrushPngs?` is a stable owner provider called once per contact on the UI thread. A separate UI `SampledTipResolver` resolves masks before BrushDabber. Live TipShape comes from the first actual dab, including sampled set and profile/quad/fade. A candidate's new brushes/assets travel only in `LineContact`, then `setPendingLine(line,newBrushes,newAssets)`. Cancel publishes nothing. Opaque legacy image-only recipes explicitly refuse rather than draw a guessed procedural tip.

## Required engine / host hookups

The engine agent was given these exact APIs. The engine must pass `scene.brushAssets` when producing LineSnapshot, pass root assets to RecordedSnapshot, pass cold `frozenBrushAssetsCopy()` to createSaved, override captured admission's four-argument overload, and publish pending newAssets only with accepted line/brush root publication. The engine proposal owns these call sites.

The host that chooses a library preset must set the View `brushPngs` provider from the stable BrushLibrary resource owner. The same provider must be installed on tuning/preview views. A fresh `FrozenBrushPngs.snapshot` per lookup defeats the resolver's identity cache; retain the same immutable bundle while that brush's bytes stay unchanged.

The ordinary View load/save/line edit path is currently legacy NativeLines. Its sampled-image refusal stays in place because its history has no immutable resource-bearing root. Its diagnostic capture/probe also has image refusal because the downstream probe accepts only a preset/record and cannot transport assets yet. Those guards must be lifted only when their consumers accept complete frozen resources. No sampled tips are enabled on smudge/watercolour/tuft, external grain, or unadapted legacy line consumers by this proposal. The resource-bearing RecordedScene path is enabled through the engine patch instead.

## Review / validation

The generator ran successfully, producing 714 patch lines. Manual review checked every State construction and every actual line/captured-admission callback. Five proposed JVM tests under `tests/` cover candidate cancellation, candidate-specific callback bytes, successful publish/undo/redo resource retention, missing/replaced bundle refusal before rendering, actual-byte cache identity, malformed PNG admission and single modern cache eviction (two files, five test methods total).

After Lead applies/rebases both held proposals, run the proposed tests and existing scene/snapshot/cold/cache tests, plus full core/backend checks. Add a real cold snapshot/archive reopen/first edit transaction with no installed library, multi-resource rebrush undo/redo, and context loss/atlas-owner probes. Existing shader correctness does not certify application resource publication or memory ownership.

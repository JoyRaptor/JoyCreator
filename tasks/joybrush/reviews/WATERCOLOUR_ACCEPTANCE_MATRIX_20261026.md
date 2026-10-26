# Watercolor acceptance matrix — isolated fixture candidate

Base: coordinator4b9ed850c. This patch adds a standalone source-only fixture helper,
its focused tests and this plan. Root retains engine/View/BenchRunner/Activity
ownership. There is no new dispatch, capability or recording activation here.
The ordinary diagnostic compiled previously in070a99885; this NEW helper/tests
are uncompiled/unrun until a separately admitted job. Do not transfer that result.

## Debug fixture contract for root dispatch

Call WatercolourOrdinaryFixture.prepare(selectedSource, operator), then pass
prepared.frozen.preset and prepared.record() through a NEW disposable-engine
ordinary diagnostic branch. The existing View wrapper freezes the supplied
preset again and remaps record.brushId to that exact freeze id; preserve that
identity mapping rather than resolving a mutable library later.

The helper validates and detaches the source, snapshots its effective generic
knobs into the watercolor recipe, disables only runtime paperCut/grain, caps size
at300, and optionally chooses normal/multiply. It freezes a diagnostic copy in
an empty registry. No library object, asset file, preference or document is
written. metadata() reports source/frozen ids, effective values and every
intentional changed field. Log that metadata plus frozen bytes/hash, code source
and installed APK hash, rather than describing the result as the shipped preset.

The raw trace has24 samples, seed78234, smoothing.2, pressure.7, tilt.4rad,
azimuth.3rad, missing barrel, colour#205ca9 and an explicit wash-frame offset
(17,-23). It crosses negative coordinates and tile boundaries. Repeated calls
must preserve raw values/seed; no reseeding for translation/tint/Undo.

| Gate | Exact run/invocation contract | Required evidence and limit |
|---|---|---|
| Ordinary normal | New root-owned debug case -> prepare(source,"normal") -> WatercolourOrdinaryProbe.run on fresh one-layer engine | Add; independent (+37,-19) RGBA transport; tint control AND actual changed pigment counts>0; all7 Add/edit/history phase rows; complete tile union; every passed flag true. No beginLift/cache/fade claim. |
| Ordinary multiply | Same case with prepare(source,"multiply"), separately named receipt | Same pixel/history checks; transparent backing only. Does not establish multiply over arbitrary coloured content. |
| Negative control review | Independently inspect expected transport, full blank/old/halo-key union and nonidentity frame; retain any native mismatch | Do not derive moved expectation from moved replay. Do not omit transparent keys, compare only alpha, loosen byte tolerance or disable pooling to force green. |
| Selected cache | Existing recordedWatercolourEditProbe, separate unchanged dispatch | Deposition/finish counters, canonical RGBA and Undo. Ordinary probe results cannot certify finish-only reuse or single-selected-line lift. |
| Archived cold first-bake | Existing recordedSavedProbe using WC; existing dispatcher sets constant opacity.55/normal | Snapshot/write/read and cold saved-scene first-bake/Undo report. The scene uses the frozen preset. No new loader. Not real View recovery/edit proof. |
| Real View save/reopen | Fresh disposable benchmark document through actual snapshot/load; save->force-stop->reopen->first move/tint/Undo | Before/after complete pixel hashes, records/frame/frozen brush, archive and first-edit identity. Root must specify scene-to-View path; do not substitute disposable saved-scene probe. |
| Graphics recovery | Controlled graphics restart of the disposable test drawing, then first edit | Pixels/records and one history operation; separately captured failure/rollback. Process force-stop alone is not GLES context-loss proof. |
| Cold/fade L1/L2/L7 | Existing topScribble/full-tail matrix:5 alternating OFF/ON launches,2 strokes each; actual size300/spacing15% source | Exact APK/source/fixture,20 frame rows, temperature, cold vs warm and full tail. Never settleWatercolourFade during timing. Idle polling is an upper bound, glFinish time intrusive, not pen-to-photon. |
| Artist appearance L6 | Approved POC seeds78234–78236 and original grain/paper settings on phone | Separate current-source images and owner's judgement. Debug paperCut/grain0 evidence makes no claim about the grainy preset or physical paper quality. |

## Device/admission sequence (no job reserved)

Root selects the final dispatch source and shared-locked compile/tests/APK first.
Only after release/admission: verify canonical sandbox model/API, unlock/current
foreground and watcher ownership; claim phone lock exclusively; preserve saved
root/Working hashes; verify installed APK hash before and after the run; resolve
and launch the explicit debug Activity with am start (never monkey). Record every
requested case and its terminal result; timeout/missing result is unfinished,
not pass. Release only the owned lock. Install-r only, no uninstall/clear-data,
rotation changes, or edits to existing owner drawings.

Current host measurement helper in C:/Temp/jb-watercolour-ops defaults to the CACHE
case; do not call its probe action expecting this ordinary fixture. A new ordinary
case is not runnable until root wires/reviews it. Keep full raw logs, source refs,
fixture metadata/bytes, PNGs and proof receipts. Existing red/older evidence stays.

Touched-section Slice/smudge is a separate unfinished feature: pure planner and
ownership tests do not activate the ordered-contribution design or solve mixed
event pigment transport. Existing ordinary paint/media smudge behavior stays.

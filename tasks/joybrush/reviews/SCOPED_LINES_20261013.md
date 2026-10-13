# Scoped line identity prerequisite — October13

Source f8e9454d in C:/Temp/jb-scoped-lines, lead/jb-scoped-lines, based on96dc25a4.

## Change

Line IDs are cel-local. Selection now retains LineAddress(layerId, nullablecelId, recordId)
through tap/lasso/cycling/count, flags/groups, glow/tips and every line edit. Shared planes
use null; frame planes use their actual cel ID. Geometry keeps the true record ID. New
ordinal picker/lasso APIs preserve candidate order and the existing string APIs' behavior.
AddressedStroke keeps records paired with ownership through snapshots, preview and commit.
Transforms/recolour/rebrush/reweight/nudge retain seq; duplicate carries the source
destination with a fresh ID and lets the host assign new seqs. Host batches must validate
all addressed targets before writing. No saved-format bump or synthetic global ID.

Tip/nudge completion now densifies only changed records. Previously it could resample
another selected line that had not bent. A real stylus drag regression covers identical-ID
siblings with both selected, retaining the untouched sibling exactly.

## Verification

Root serialized all checks with C:/Temp/jb-lead-ops/lead-build.ps1 and the shared CreateNew
lock, offline, one worker, Gradle768MiB and test512MiB. XML counts were read directly.

- SelectionStateTest18 + ScopedSelectionTest5:23 tests, zero failures/errors/skips.
  Backend compileKotlin also passed. Build3m30s; scoped-core.log.
- StrokePickerTest29 + StrokePickerRankTest3 + LineLassoTest6:38 tests, zero
  failures/errors/skips. Build12s; scoped-algorithms.log.
- Full native175/0/0/0,20 suites: PASS1m12s; scoped-native-repaired.log.
  Initial2m34s run had two failures in the new fake host: its half-width array was
  hardcoded to two samples after densification. Sized to record.samples.size;
  assertions and production behavior were retained. Initial scoped-native.log kept.

Total affected core61/0/0/0 in two focused runs. This is not a new full core/backend
regression claim. Native compilation and the full native suite passed on current source.

New regression cases cover same record ID in a shared plane, another cel and another
layer; distinct pick cycles; all-layer lasso reread; scoped flags/count; addressed move,
weight preview/reset/recolour; unsorted multi-record edit pairing/seq; direct fallback;
duplicate destination/new seq; delete/bake scope; stale count ownership; nudge and tip.
Existing native fake adapters and assertions use explicit addresses.

## Boundaries

This is the prerequisite API migration. Select Activity routing and the live engine host
remain absent. JB-5.20d capture, immutable frozen brushes, ordered slab/look replay and
Step.lines undo/redo still need implementation. No app assembly or phone acceptance was
performed for this slice. Owner-approved full-resolution watercolor, defaults, shaders,
presets, saved document format and atlas implementation are outside these edits.

Helpers reached usage limits after partial core/test edits. Root inspected and finished
them, restored assertions to full-address comparisons, and ran the integrated checks.

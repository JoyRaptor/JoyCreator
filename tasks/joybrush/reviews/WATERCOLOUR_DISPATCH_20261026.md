# Ordinary watercolor debug dispatch — source only

Baseecc2ea8e8, isolated vector/watercolour-dispatch-20261026. Temporary exclusive
paths: JbCanvasView.runRecordedReplayProbe and BenchRunner watercolor probe sites.
No GlPaintEngine, shader, save, schema, preferences or product activation changes.

Distinct case: recordedWatercolourOrdinaryProbe. The existing cache case
recordedWatercolourEditProbe remains unchanged in meaning. Ordinary mode derives
and freezes the reviewed fixture only inside the debug disposable-engine path,
then passes its matching record/preset to WatercolourOrdinaryProbe. It never calls
beginLift. Output includes actual override metadata, exact frozen brush JSON and
SHA256. Generic pre-derivation size/opacity fields are not emitted as if they
described this fixture. The source/library preset is never mutated.

The original public Kotlin/JVM probe signature remains available as a forwarding
overload. A required explicit ordinary flag selects the new overload; all11 other
probe flags are mutually incompatible with ordinary. Debug-only gating, GL queue,
engine initialization/paper/disposable layer/release and exception convention stay
in the existing path. Old cases retain their record/preset and scope handling.

Six reviewed fixture tests are selectively adopted from2d6f64998. Two new
Robolectric tests cover all conflicting modes before queue/callback and original
positional signature/cache/new-option debug refusal. These are authored UNRUN;
they do not execute GPU code or establish runtime case success. No build/APK/ADB
job is started in this lane. Root must compile and review before adoption/testing.

After exact-source APK admission, request case recordedWatercolourOrdinaryProbe
with shipped joybrush.lines.watercolour (or joybrush.watercolour) explicitly as
jb_bench_brushes and jb_bench_runs=1, through the existing resolved debug Activity.
The fixture retains source normal/multiply operator; log actual fixtureOperator.
The ordinary result must have passed=true, seven pixel/history rows, both tint
changed-pixel counts>0, release GL error0 and the frozen fixture metadata/hash.
Every invocation remains subject to sandbox/model/hash/phone lock and saved-root
preservation checks; this document grants no device slot.

Cache, saved cold first-bake, actual View save/reopen/graphics recovery, physical
full fade, speed and owner-preset grain/appearance remain separate gates. The
grain0/paperCut0 fixture isolates wash travel and makes no physical-paper claim.

# Adversarial review — JB-0.03 Brush preset format + dynamics evaluator

- Reviewer: muse-spark (cross-reviewer, different family from builder stealth/space-bunny-alpha).
- Task status: 🟧 Built. Commit reviewed: `5295eacf` ("JB-0.03: brush preset + dynamics").
- Spec reviewed: `tasks/joybrush/specs/JB-0.03_brush_preset_and_dynamics.md` (incl. the builder's six Questions, which I re-probed rather than re-asserted).
- §5b checks: diff `5295eacf` touches only `joybrush/brushes/*`, `core/build.gradle.kts` (the shared serialization edit, idempotent per spec), `core/brush/*`, `BrushTest.kt`, the ROADMAP row, and a Questions appendix in the spec — inside the owner area. Spec command `./gradlew -p joybrush :core:jvmTest` run 2026-09-28: BrushTest 8/8, 0 failures, incl. the 1M-eval perf test and the `NaN`-in-ranged-number tests.
- Severity scale: High = stroke silently vanishes / work destroyed; Medium = hostile-file DoS or silent wrong paint; Low = unspecified edge; Info = verified-good.

## Finding 1 (High): `size.base = +Inf` (JSON `1e999`) passes validation, then the stroke stalls after one dab
Proof chain, each link cited:
1. `BrushValidate.kt:28` — `if (!(p.size.base > 0f))`. `+Inf > 0f` is true, so no error. (NaN *is* caught here; ±Inf is not. The ranged rules at `:34-40` use `!in`, which catches both — size.base is the odd one out.)
2. `Dynamics.kt:50-55` — `eval` is intentionally unclamped (documented `:18-19`), so size evaluates to `+Inf`.
3. `DabPlacer.kt:40,56` — first dab placed, then `untilNext = step(s) = max(2·Inf·spacing, 0.5) = Inf`. Every later sample: `along (Inf) <= len (finite)` is false, so zero dabs; `untilNext = Inf - len = Inf` forever.
4. Net effect: any stroke with this brush draws exactly one dot and swallows the rest — silent work loss. Trigger: a hand-edited, Wi-Fi-hot-reloaded (JB-1.21), or Phase-8-imported brush containing `"base": 1e999` (the spec's own Q3 names this literal). The existing test at `BrushTest.kt:342-349` proves the adjacent case (NaN hardness passes validation, encode throws) — i.e. the suite *documents* that unranged non-finite values reach the engine.
Fix direction (Lead call): range `size.base` as `> 0 && finite` (and ideally opacity/flow bases), or clamp in `eval`'s callers. I changed nothing.

## Finding 2 (Medium): no count limits — a hostile brush file can demand ~10 MB of LUTs and unbounded per-dab work
Proof: the builder's Q1, confirmed in code. `Compiled.of` (`Dynamics.kt:74-84`) builds one 256-float `Curve` per input with no cap on inputs-per-Param or points-per-curve; `BrushValidate` has no count rule (`BrushValidate.kt:42-52` checks shape only). Reachable via the same channels as Finding 1 (hot-reload over Wi-Fi, Phase 8 imports of strangers' files). Note the cache does not save us: `CurveCache` caps *entries* at 32 but not entry *size*, and on eviction-overflow it discards the new entry and rebuilds every eval (`Dynamics.kt:121-124` — see Finding 4). Suggested ruling: cap inputs per Param and points per curve in validation.

## Finding 3 (Medium): unknown `BrushInput` enum value makes the whole brush unopenable (forward-compat)
Proof: `BrushInput` (`BrushPreset.kt:5`) is a plain kotlinx enum; `BrushJson` sets `ignoreUnknownKeys = true` (`BrushJson.kt:20-24`) but that covers keys, not enum values — a future input (e.g. `"shear"`) throws at decode → `BrushException`. Same defect class as JB-0.02 Finding 1, smaller blast radius (one brush, not one document). The engine/accumulate/blend *strings* degrade gracefully via validation; the input enum does not.

## Finding 4 (Low): `CurveCache` eviction silently stops caching (perf cliff, unbounded battery, bounded memory)
Proof: `Dynamics.kt:121-124` — when `snapshot.size >= MAX_ENTRIES`, `size` becomes 1 and `Array(1) { i -> if (i < snapshot.size) snapshot[i] else … }` keeps `snapshot[0]` and drops the just-built entry, while `builds++` already fired. Past 32 distinct `Param` instances every `eval` rebuilds curves (allocation + 256 exact-evals per curve per dab). Real presets need a handful (documented `:99-100`), so this bites only a program inventing Params — but that program is JB-1.04/JB-1.05's dynamics wiring if it allocates per-dab Params. The `curvesAreBuiltOnceAndThenReused` test pins the ≤32 behaviour; nothing pins the >32 path. Fix is a real LRU, or documenting "don't".

## Finding 5 (Low): `version < 1` read as v1; curve-x outside 0..1 and typo'd `source` pass silently (spec Q2/Q4, confirmed)
Proof: version check is `p.version > BRUSH_VERSION` only (`BrushValidate.kt:18`); curve-x range unchecked (`:44-50` shape only, `Curve` clamps at eval); `source` only inspects `"image"` (`:68-70`), so `"sorce": "cloud"`-style typos render as procedural with no message. All fail-safe (never crash, never corrupt), all silent. Batch with the JB-0.02 version ruling.

## Verified good (with proof)
- NaN-input discipline in both combine modes + tilt-only-survives test (`BrushTest.kt:165-172`; `Dynamics.kt:44-55`).
- `normalise` formulae incl. attack wrap (`Dynamics.kt:27-38`, `BrushTest.everyInputIsNormalisedToZeroOne`); `cap1` lets NaN through deliberately (`:59`).
- Ranged rules catch NaN *and* ±Inf via `!in` (`aNaNInARangedNumberIsAProblemNotAPass`); shipped `ink`/`pencil` files decode-validate-round-trip, and I diffed `joybrush/brushes/*/brush.json` against the test's embedded copies — content identical, no drift (Q5 risk not yet realised).
- Malformed curves never throw in-stroke (`Compiled.of` drops empty/malformed, `BrushTest.kt:176-177`); non-finite *encode* fails loudly as `BrushException` (`BrushJson.kt:26-32`, test `:348-349`).

## Recommendation
Send-back candidate, Lead's call: Finding 1 is the only High in this review round and it eats strokes. Cheapest correct ruling is `size.base` must be finite (one predicate, one test), which downgrades this review to clean. Findings 2–3 deserve rulings before JB-1.21/Phase 8 widen the attacker set; Findings 4–5 are backlog.

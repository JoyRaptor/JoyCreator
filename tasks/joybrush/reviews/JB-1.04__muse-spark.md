# Adversarial review — JB-1.04 BrushDabber (brush file drives every dab)

- Reviewer: muse-spark (cross-reviewer; builder was an unrelated subagent — different family).
- Task status: 🟧 Built. Commit reviewed: `46addb2c`.
- Spec reviewed: `tasks/joybrush/specs/JB-1.04_brush_dabber.md` (existing contracts, API, decisions 1–8, tests 1–8 + "Do not").
- §5b checks: diff touches only NEW `BrushDabber.kt`, NEW `SplitMix.kt`, NEW `BrushDabberTest.kt` — inside the owner area; `DabPlacer`/`Dynamics`/`BrushPreset`/view untouched per "Do not"; no `kotlin.random`. Suite evidence: `BrushDabberTest` 18/18, 0 failures (verification run 2026-09-28 19:11; fresh re-run blocked — see top note in JB-0.02b file). All eight spec tests map to named tests (replay determinism, ink radii, wash cap, followDirection, speed convergence, NaN-tilt, 3N+1 draws, buildup opacity).
- This task consumes my round-1 JB-0.07 Finding 2 (double-ask): verified fixed — the placer asks once (`DabPlacer.kt:74-75`) and the dabber's kdoc pins the discipline (`BrushDabber.kt:23-26`), with `oneCallIsOneDab` pinning both halves.

## Finding 1 (MINOR): spec test-2 shorthand omits the `minPx` floor the code (correctly) applies
Proof: spec test 2 says "pressure 0 → radius = 0.15 × base/2". The implementation is `radius = max(diameter, minPx) / 2` (decision 4, `BrushDabber.kt:110`), and with the shipped ink preset (`minPx` default 1) pressure 0 gives `max(0.9, 1.0)/2 = 0.5`, not 0.45. The *test* documents this honestly (`BrushDabberTest.kt:233-243`: asserts 0.5 with the floor, 0.45 with `minPx = 0.25f`, plus the float-0.15 tolerance note). Decision 4 itself is stated correctly; only the test-description line is imprecise. Clarity fix in the spec, no code change. (The floor is load-bearing safety: zero/negative-curve sizes bottom out at `minPx`, proven by `minPxKeepsAStrokeVisibleWhenTheFileAsksForNoSize`.)

## Verified (proof)
- Decision 1 (deterministic randomness): `strokeRandom` = constructor's first draw, then exactly 3/dab in `random/size/angle` order (`BrushDabber.kt:36, 87-89`); `nDabsDrawExactlyThreeFloatsEach` reads all three back bit-exactly, and the no-jitter case proves the stream never leaks into unasked settings (`BrushDabberTest.kt:165-198`, incl. an honest note on what cannot be asserted through the 256-entry table).
- Decision 2 (speed): dab-over-dab filter with `1 − exp(−dt/50ms)`, dt ≤ 0 keeps previous, first dab 0 (`BrushDabber.kt:145-164`); convergence/monotonicity, stationary, shared-timestamp, and NaN-time cases all pinned (`:353-390`).
- Decision 3 (direction/lean/tilt/barrel): tracker fed interpolated dab samples, NaN passes through to `Dynamics`' skip (`:81, 93-103`); NaN-tilt-equals-base and all-channels-NaN cases pinned (`:394-435`).
- Decisions 4–7: diameter→jitter→`minPx`-floor→halve (`:106-110`); degree angle + followDirection + jitter with NaN-angle guard preserving the tracker (`:114-118`, pinned `:506-511`); flow/cap/hardness/opacity through `unit()` with the NaN-flow→1 / NaN-cap→placer-sentinel distinction (`:120-131`, pinned `:514-538`); buildup `strokeOpacity` first-dab capture, wash 1 (`:126-131`, pinned `:290-311`).
- Decision 8: `spacing = preset.spacing` (`:50`).
- Hostile-input posture (unvalidated preset — see JB-0.03b Finding 1): every output is placer-safe by trace — NaN radius → placer 0 (`DabPlacer.kt:78`), ±Inf → 2048 clamp, NaN angle → 0, NaN flow → `unit` fallback, finite-huge angle stays finite into cos/sin (bounded mush, no crash). The dabber is safe to drive with an unchecked file; it is just not the place that refuses one.
- `SplitMix`: exact two's-complement arithmetic, top-24-bits floats in [0,1), seed-sensitivity + 64-bit uniqueness + projection-collision bounds pinned with the flaky-assertion lesson written down (`BrushDabberTest.kt:108-149`).

## Explicitly not filed
- `exp()/sin()/cos()` are not bit-promised across JVM↔ART, so cross-device replay can differ in the last ulp of speed/direction-derived radii — same already-noted class as the smoother's `exp/acos` (round-1 JB-0.01). Visually nil, structurally worth one line when timelapse replay (a re-render consumer) is specified.
- NaN sample coordinates freeze `travelled` (placer `untilNext` goes NaN, stroke stops dabbing silently): root cause is my round-1 JB-0.01 Finding 1 (`screenPerDoc = 0`), still open; the placer/dabber contain it without corrupting tiles. Not re-filed.

## Recommendation
No send-back. One MINOR (spec prose), otherwise the round's best-tested stateful contract: 18/18 with the stream discipline pinned from both sides.

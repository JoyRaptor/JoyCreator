# Adversarial review — JB-0.03 brush preset format + dynamics evaluator

- Reviewer: mimo (second pass; muse-spark filed `JB-0.03__muse-spark.md`).
- Task status: 🟧 Built. Commit reviewed: `b74aaf0e` — note this includes **JB-0.03b** (`72845224`, the Lead's hardening) and Lead ruling R1 (`061fd2b5`), so several pass-1 findings are now FIXED at HEAD. The pre-hardening state is what muse reviewed (`41102518`/`f0e84040`).
- Spec: `tasks/joybrush/specs/JB-0.03_brush_preset_and_dynamics.md` (contract + "Lead rulings 2026-09-28" section) and its follow-up spec `JB-0.03b_brush_validation_hardening.md`.
- Suite: `./gradlew -p joybrush :core:jvmTest --rerun-tasks` at `b74aaf0e` → BUILD SUCCESSFUL, 280 tests / 0 failures (BrushTest 16, ShippedBrushFilesTest 1).
- Severity: BLOCKER / MAJOR / MINOR per ROADMAP §5b.

## Status of muse-spark's findings at this commit (each re-verified by me in the current source)

- **F1 `size.base = +Inf` passes validation → stroke stalls after one dab (BLOCKER)** — **FIXED twice:**
  `BrushValidate.kt:65-68` refuses `!(size > 0f) || size > MAX_SIZE_PX` (4096) so `1e999`/`0`/`NaN`
  never load (`anInfiniteBrushSizeIsRefusedBeforeItReachesTheEngine` `BrushTest.kt:398`,
  `everyNonFiniteSizeIsRefusedByName` `:409`), and the engine clamps every dab (`LEAD_RULINGS.md:9-16`
  R1; `PaintTest.anInfiniteBrushSizeCannotFreezeTheStroke`). I re-derived the old failure and the new
  guard: both hold.
- **F2 no count limits on inputs/curve points (DoS)** — **FIXED:** `MAX_INPUTS = 8`, `MAX_CURVE_POINTS = 64`
  (`BrushValidate.kt:27,30`) enforced at `:154`/`:158`; `aFileCannotCarryUnlimitedCurve` (`BrushTest.kt:524`).
- **F3 unknown `BrushInput` value breaks decode** — **closed by design** (Lead ruling R3, `LEAD_RULINGS.md:22-31`):
  enum constants are append-only + version-bumped; refuse-don't-coerce is pinned by `EnumFreezeTest`
  (`anUnknownBrushInputIsRefusedRatherThanSwappedForAnother`). I agree with the ruling.
- **F4 `CurveCache` eviction drops the newest entry** — **still open; worse than "drops the newest" — see F1 below.**
- **F5 `version < 1` accepted; curve x unchecked; typo'd `source` passes** — **ALL FIXED** in JB-0.03b:
  `BrushValidate.kt:52-53` ("unknown brush version N"), `:188` (x in 0..1), `:191` (y finite),
  `:213-219` (source words); tests `aVersionFromNoOneIsRefused` (`:429`), `aCurvePointIsAPairOfNumbersInZeroToOne` (`:549`),
  `aSourceIsAWordThisBuildKnows` (`:568`).

## F1 (MINOR — performance only; correctness unaffected): when the curve cache fills to 32 entries the 33rd *distinct* `Param` collapses the whole cache to one stale entry and is not stored at all, so it thrashes rebuilds instead of evicting

- **Proof (code):** `Dynamics.kt:104` `MAX_ENTRIES = 32`; on a miss,
  `Dynamics.kt:122` `val size = if (snapshot.size >= MAX_ENTRIES) 1 else snapshot.size + 1` and
  `:123` `entries = Array(size) { i -> if (i < snapshot.size) snapshot[i] else CacheEntry(param, built) }`.
  With `snapshot.size == 32`: `size = 1`, and for `i = 0`, `0 < 32` is true → the array becomes
  **`[snapshot[0]]` (the oldest entry only)**; the `else` branch — the only place the newcomer
  `CacheEntry(param, built)` can be stored — is **unreachable** when full. Net effect on the 33rd
  distinct Param: cache goes **32 → 1**, keeps the least-recently-useful entry, drops 31 live entries
  *and* the one just built; every subsequent miss then rebuilds up one slot at a time.
- **Why MINOR:** `Compiled` results are pure functions of the Param — a miss re-builds and returns
  the same value (`:120-124`), so output is bit-identical; only CPU/LUT construction is wasted. The
  cap still bounds memory as intended. Tests only pin the ≤32 path (`curvesAreBuiltOnceAndThenReused`,
  `BrushTest.kt:255`) — nothing exercises entry 33.
- **Reachability:** a brush has eight Params (`paramsOf`), so one preset never fills the cache; the
  cache is global across presets, so a brush *picker* cycling >32 distinct Param objects (each
  preset's own Param instances, identity-keyed `:93-94`) hits this in normal use. Fix shape: store
  the newcomer and drop index 0 (classic ring), i.e. `Array(MAX_ENTRIES) { ... }` with rotation.

## F2 (MINOR — the spec contradicts itself on clamping; code follows the half that leaves five settings unbounded): "Then clamped to the setting's range" vs "deliberately unclamped"

- **Proof (spec):** contract line "**Then clamped to the setting's range.**"
  (`JB-0.03:21`, verified) vs the Lead-rulings section: "`Dynamics.eval` is deliberately unclamped"
  behaviour described at `JB-0.03:234-240` (R1 fixed the *engine* by clamping in `DabPlacer`:
  radius 0..2048, flow 0..1, cap 0..1, angle finite).
- **What is clamped at use:** radius/flow/cap/angle only. **What is finite-only** (JB-0.3b Q3 lists
  them): `opacity.base`, `flow.base` … the seven unranged bases — `tip.hardness`, `tipTexture.depth`,
  `paperGrain.depth`, `scatter.amount`, `tip.angle` — so `"tip": {"hardness": {"base": 5}}`
  validates clean (rule 16 checks finiteness, `BrushValidate.kt:143`) and evaluates to 5 with no
  range anywhere. Whether that draws nonsense is deferred to JB-1.04/shaders by disclosure.
- **Filed as a spec defect for the Lead (§5b: "the spec is wrong"):** either delete the clamp
  sentence at line 21 (the rulings section supersedes it) or range the five stragglers when the
  first `validate` caller lands (JB-0.03b Q3's own suggestion). Whoever wires JB-1.04 reads line 21
  first and will otherwise double-clamp against a pinned test.

## F3 (MINOR — disclosed structural trap, confirmed): rules 16–20 walk a hand-written `paramsOf` list; a `Param` added to `BrushPreset` and forgotten there is checked by nothing — and no test can catch it

- **Proof:** `BrushValidate.kt:231-240` KDoc says exactly this ("a `Param` added to [BrushPreset]
  and not added here is checked by none of them — which is exactly how a NaN `tip.hardness` once
  loaded clean"), implementation `:241-250` (eight entries), `RANGED_BASES` deny-list `:42`.
  Raised as JB-0.03b Question 6 (`JB-0.03b:44`-region) with the proposed fix (`BrushPreset.allParams()`),
  which is outside that spec's owner area.
- **My assessment:** today the list is complete (all eight Params covered — I walked `BrushPreset`
  fields against `:241-250`), and `RANGED_BASES` is the safe direction of the trap (a wrongly-included
  name yields a duplicate message that `assertSole`-style tests catch). The fix belongs with whoever
  next touches `BrushPreset.kt` — recommend it be attached to JB-1.04's dispatch (that task reads
  every Param anyway). No test-only fix exists without reflection, as the builder states.

## Checked and dismissed (claims I could not reproduce — recorded so they are not re-filed)

- **"Duplicate-x curve points keep the *earlier* point, contradicting the kdoc"** — **false.**
  `Curve.exact` returns the later point: for points `[(0,0),(0.5,0.2),(0.5,0.9),(1,1)]`, `exact(0.5)`
  enters the loop at the second `0.5` point and takes `x1 == x0 → return y1` = **0.9**
  (`Curve.kt:41-44`); the kdoc's "duplicate x keeps the later point" (`Curve.kt:11-12`) holds.
  (What *is* true and by design: `eval` goes through the 256-entry LUT (`Curve.kt:22,26-32`), so a
  step is rendered within 1/255 of input — the documented LUT contract, not a defect.)
- **"JSON `1e999` decodes to +Infinity"** (spec Q3-era wording) — no longer reachable from files:
  kotlinx refuses the token at parse and `BrushJson.decode` wraps it as `BrushException`. Only
  Kotlin-built presets can carry non-finites, and rule 16 now catches those.

## Verified sound (checked independently this pass)

- **All 23 validation rules** walked line-by-line against the spec's rulings 1–5 (`BrushValidate.kt:44-229`):
  version floor/ceiling, blank id, size ceiling, spacing, corner, taper, aspect, minPx, smoothing,
  both jitters, scatter count/jitter, grain scale/edge/tilt/radial (both grains, one message per rule),
  color jitter, finite bases, 8/64 caps, curve shape, x/y numbers, combine words, engine/accumulate/blend,
  tip/grain source words, image-path presence. `validationSaysOneThingPerRule` (`BrushTest.kt:277`) +
  `everyRangedNumberIsChecked` (`:438`) pin the "exactly one message per broken rule" contract;
  16/16 tests green.
- **Evaluator maths:** `evalCombinesTheBaseWithItsCurves` (`:162`) — multiply is `base × Π curve`,
  add is `base + Σ curve` per contract; `everyInputIsNormalisedToZeroOne` (`:194`) incl. attack wrap;
  NaN input skips (both combine modes) rather than poisoning the result; LUT construction is
  deterministic (no randomness), so phone and PC Lab agree bit-for-bit as the kdoc promises.
- **Perf:** `aMillionDabEvaluationsStayFast` (`:240`), cache reuse pinned (`:255`).
- **Round-trip + shipped files:** `bothExampleBrushesLoadAndRoundTrip`, `ShippedBrushFilesTest.everyShippedBrushFileOnDiskDecodesAndValidatesClean` (reads `joybrush/brushes/*/brush.json` from disk, validates clean, id-unique) — 280/280 green in this session's run.
- **Byte-identical inline copies:** `BrushTest`'s inline brush texts still equal the shipped files
  (spot-checked `ink` top-level keys against disk this pass). The *drift-detection* story around
  that test is where the weaknesses are — filed under JB-0.03b (the commit that added the test).

## Bottom line

No BLOCKER, no MAJOR at HEAD. The two blocking pass-1 findings (F1, F2) are fixed and pinned by
tests I re-verified. Remaining: F1 (cache eviction — fix when Dynamics is next touched), F2 (spec
wording for the Lead), F3 (structural list trap — attach `allParams()` to JB-1.04).

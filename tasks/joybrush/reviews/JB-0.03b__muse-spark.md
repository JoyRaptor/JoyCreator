# Adversarial review — JB-0.03b Brush validation hardening (Lead's rulings on JB-0.03's questions)

- Reviewer: muse-spark (cross-reviewer; builder was an unrelated subagent — different family).
- Task status: 🟧 Built. Commit reviewed: `72845224`.
- Spec reviewed: `tasks/joybrush/specs/JB-0.03b_brush_validation_hardening.md` (rulings 1–6 + builder Questions 1–6).
- §5b checks: diff touches only `BrushValidate.kt`, `BrushTest.kt`, NEW `ShippedBrushFilesTest.kt`, and the two spec appendices — inside the owner area; `BrushPreset`/`BrushJson`/`Dynamics` untouched per "Do not". Suite evidence: `BrushTest` 16/16 + `ShippedBrushFilesTest` 1/1, 0 failures (verification run 2026-09-28 19:11; fresh re-run blocked — see top note in JB-0.02b file, same shared-tree cause).
- This task closes my round-1 JB-0.03 Findings 1 (size Infinity), 2 (no count caps), 5 (version<1, curve-x, source typos): all three verified fixed below. Round-1 Finding 3 (unknown `BrushInput`) is now Lead R3 + JB-0.02b, verified there. Round-1 Finding 4 (cache eviction) is untouched by this task and stands as filed.

## Finding 1 (MAJOR): the refusal is still advisory — nothing on the load path calls `validate` (spec Q1, independently confirmed)
Proof:
1. Grep of `joybrush/` for `BrushValidate.validate` outside tests finds only the KDoc mention in `BrushPreset.kt:19`. Every other hit is `BrushTest.kt`, `ShippedBrushFilesTest.kt:36`, or in-flight `brush/imports/MypaintImportTest.kt` (another agent's untracked work). No production caller.
2. The first real consumer, `BrushDabber(preset, seed)` (`brush/BrushDabber.kt:31`), takes any decoded preset with no check — spec-compliant (its spec forbids touching validation), but it means a hostile `brush.json` flows `decode → Dabber → DabPlacer` with zero refusal.
3. Consequence is silent-wrong-paint, not a freeze (the Lead's R1 engine clamp in `DabPlacer.emit` bounds every dab: `DabPlacer.kt:78-84`): e.g. `"opacity": {"base": 5}` validates dirty yet dabs at cap 1 (`BrushDabber.unit` coerces); `"size": {"base": 5000}` dabs at the 2048-px clamp. Nothing tells the person their file was refused, because nothing refused it.
This is the spec's own goal ("must be REFUSED with a readable message, never … silent nonsense") failing on the only path that matters. It is MAJOR rather than BLOCKER solely because of the engine backstop. Endorsed fix is the builder's: a `BrushJson.decodeChecked` owned by whoever owns `BrushJson.kt`, called by the brush shelf and JB-1.21's hot-reload — needs a Lead ruling, not a code edit here.

## Finding 2 (MINOR): Q2's premise is stale — a denormal size draws at `minPx`, not "nothing, silently"
Proof: `BrushDabber.kt:110` — `radius = max(diameter, preset.tip.minPx) / 2f`. A `1e-40` diameter is finite, so it loses to the `minPx` floor (≥ 0.25 by new rule 8) and draws visible `minPx/2` dots at full flow — pinned clean by `BrushTest.kt:420` (`size = Param(1e-40f)` validates empty). The question's premise ("`DabPlacer` clamps a sub-pixel radius to 0, so such a brush draws nothing") describes a placer that no longer exists: the Lead's clamp maps non-finite → 0 but leaves finite values to the dabber's floor. Q2's actual question ("should the floor be `tip.minPx`?") is therefore already answered — it is. Suggest correcting the question rather than changing code; no behaviour asks to change.

## Verified (each ruling + named test)
- R1 caps: 8 inputs / 64 points (`BrushValidate.kt:153-164`), boundary-pinned (64/8 clean, 65/9 refused with names: `BrushTest.kt:526-540`).
- R2: curve-x in 0..1 + finite, curve-y finite (`:183-196`), incl. NaN/±Inf spellings both sides (`:553-560`); sources whitelisted (`:210-219`, typo `"Photo"` refused).
- R3: every number finite — `!in` ranges plus rule 16's `isFinite` sweep over all eight bases (`:141-147`); my round-1 `1e999` case is now a named refusal (`everyNonFiniteSizeIsRefusedByName`, `:409-417`, incl. the 4096/4097 boundary); unranged-base sweep named (`everyBaseNoRuleRangedMustStillBeANumber`, `:490+`).
- R4: `version < 1` refused by name (`:50-56`, tests `:431-434`).
- R5: `ShippedBrushFilesTest` reads every on-disk brush, demands decode + zero problems + round-trip + unique ids + presence of ink/pencil (`ShippedBrushFilesTest.kt:22-50`).
- R6 (Lead's DabPlacer fix): exactly-once `look` with distance+index, NaN-cap sentinel, finite-step guarantee (`DabPlacer.kt:28-33,74-84` + `PaintTest` pins).
- `paramsOf` is complete today: 8 entries (`BrushValidate.kt:241-250`) vs 8 `Param` fields in `BrushPreset.kt` — and the KDoc (`:231-240`) + `RANGED_BASES` deny-list design means a wrongly-omitted ranged name fails loudly via double messages. The structural risk (Q6's `allParams()`) stands as documented, not as a defect.
- Q3's seven unranged bases are consumer-safe today: `BrushDabber` clamps flow/cap/hardness/opacity through `unit()` (`BrushDabber.kt:123-131`), placer clamps radius/angle/flow/cap (`DabPlacer.kt:78-81`), shader/CPU clamp hardness. Ranging them remains good hygiene for the first `validate` caller (ties to Finding 1).

## Recommendation
Fix Finding 1 by ruling (who owns the first `validate` call + `decodeChecked`), correct Finding 2's premise in the spec appendix. Nothing in the built code is wrong — all six rulings hold with named tests.

# Adversarial review — JB-8.03 MyPaint `.myb` import

- Reviewer: muse-spark (cross-reviewer; builder was an unrelated subagent — different family).
- Task status: 🟧 Built. Commit reviewed: `1124a91b`.
- Spec reviewed: `tasks/joybrush/specs/JB-8.03_mypaint_import.md` (mapping table, contract, tests 1–5 + builder Questions 1–10).
- §5b checks: diff touches only NEW `brush/imports/MypaintImport.kt`, NEW `brush/imports/MypaintImportTest.kt`, and the spec's Questions appendix — inside the owner area. Spec command run by me 2026-09-29: `MypaintImportTest` 23/23, 0 failures (full `:core:jvmTest`: 335 tests, 1 failure — the triage-added JB-5.10 perf reproducer, unrelated to this task).
- Severity scale per §5b: BLOCKER / MAJOR / MINOR.

## Finding 1 (MAJOR): the table drops `opaque`'s (and `hardness`'s) input curves — two of three fixture brushes lose their opacity ramp; one imports nearly invisible
Proof:
1. The mapping table gives `opaque` a base rule and `opaque_multiply` a pressure-curve rule, but no rule for `opaque`'s own inputs. Code implements it literally (`MypaintImport.kt:331-335` reads only the base; `opaque` is absent from `DYNAMIC`, `:236-239`).
2. The fixtures prove the loss is real, not hypothetical: charcoal's `opaque` carries `pressure: [[0,0],[1,0.4]]` on base 0.4 (`MypaintImportTest.kt:126-127`) — its pressure ramp is dropped, importing flat at 0.4. Worse, `basic_digital_brush`'s `opaque` is base `2.5e-05` with a 5-point pressure ramp to 1.0 (`:186-188`): in MyPaint (final = `opaque × opaque_multiply`, both evaluated) the ramp IS the opacity; imported flat, the brush draws at 2.5e-05 — effectively invisible. Same class: `basic_digital`'s `hardness` pressure ramp (`:180-181`) is dropped (table gives `hardness` a base only; `DYNAMIC` has no entry), and charcoal's `offset_by_random` pressure modulation (`:122-123`) is reduced to its base.
3. This is the builder's own Q2 ("the mapping I would most want a second opinion on"), independently confirmed against the fixtures: the table reads more like an omission than a decision, and it lands on the exact brushes this task exists to ship (CC0 set, `deevad/basic_digital_brush.myb`).
Fix needs a Lead ruling since it changes the table: map `opaque`'s pressure/tilt curves the way `opaque_multiply`'s are mapped (multiply-combined — Joy's `Param` supports two multiply inputs natively), and decide `hardness` inputs likewise. Filed MAJOR (spec-table omission with fixture proof and a shipped-invisible brush as consequence) rather than duplicating Q2's words — Q2 asks; this finding supplies the proof that the answer matters.

## Finding 2 (MINOR): `smudge: 0` still warns about smudge
Proof: `MypaintImport.kt:418-425` — the smudge-specific wording (and `continue`) fires only for `amount > 0`; a `smudge` base of 0 — i.e. an ordinary stamp brush, the common case — falls through to the generic "smudge setting with no Joy Brush equivalent" warning (`:426-430`). Nearly every stamp brush imported will therefore carry a junk warning about an engine it never needed, training users to ignore the warnings this importer is otherwise careful to make screen-worthy (per its own kdoc, `:20-28`). One-line skip (`amount == 0` ⇒ no warning, or no warning when the whole smudge family is zero) — behaviour-preserving for every real smudge brush.

## Verified (proof)
- Transforms spot-checked: pen `radius_logarithmic` 0.96 → `2·e^0.96` diameter (test pins within 1e-4); pressure curve → multiply with `e^y` points; spacing `1/(2(a+b))` with the `a+b ≤ 0 → 0.1` fallback + warning (`:352-359`); aspect `1 − 1/max(r,1)` never negative (`:383`); scatter `o/2` with negative clamp (`:387-392`); smoothing `slow/10` coerced (`:395-398`); tilt x/90 (`:313, :339`); sizeJitter from random-curve max, bounded (`:314-325`, `exp` overflow → coerce to 1, traced).
- Hostile-file posture (this reads strangers' files): 256 KB length cap + 64-deep bracket pre-scan with string/escape-correct scanner (`balanced`, `:491-517` — `\u0022` introduces no raw quote, traced); 256-setting cap (`:281-283`); per-curve 64-point cap + x-domain + finite-y refusals (`pointsOf`, `:95-127`); shape gate on *every* setting's curves whether mapped or not (`:288-296`, the consistency the kdoc claims); Double-mediated number parsing so `1e999`/`1e40` throw instead of becoming Infinity (`numberAt`, `:135-149`, pinned `:559-574`); version gating incl. `3.5`/`"3"` refusals (`:475-480`, pinned `:554-555`); `inputs`-not-object, missing base, non-object setting all refuse with naming messages. Total work bounded by file bytes; `extensions`/`warnings` bounded by file size/setting count.
- `radius` fallback goes through `diameterOf` like the mapped path (the first-cut log-space leak pinned by `theAbsentRadiusFallbackIsInDiameterNotLogSpace`, spec Q8).
- Q1 (tilt only on the two pressure-ruled settings) implemented as `DYNAMIC` (`:236-239`); unmapped inputs warned once per setting with raw kept (`:400-412`); smudge>0 keeps-stamp + warns (`:418-424`); license reads four header fields, never guesses (`license`, `:527-531`); hardness-out-of-range passes through + warns per the deliberate Q10 pin (`:375-379`, test named to be deleted when the `BrushValidate` range lands); leftover validate problems become warnings, never silent (Q9, `:454-456`).
- `id` blank refused (`:276`); result always decode-clean or warning-annotated.

## Explicitly not filed (builder's Questions, endorsed as asked)
- Q1/Q3/Q4/Q6/Q7/Q8/Q9 above; Q5's refuse-don't-trim harshness (consistent throughout); Q10's `hardness` range belonging in `BrushValidate` (JB-0.03b), not here.
- `smoothing` uses `coerceIn(0,1)` where the table writes `min(1, s/10)`: identical for every real (non-negative) input; for negative input the code warns where the literal formula would route through Q9's warning — same warning either way. Deviation without consequence.

## Recommendation
Rule on Finding 1 before the CC0 bundle ships (it decides whether two flagship brushes arrive broken-quiet or fixed-loud); Finding 2 is one line. The importer itself — bounds, transforms, warnings, tests (23/23) — is sound.

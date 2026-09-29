# Adversarial review — JB-2.16a Size & opacity drag maths + zoom-scaled nudge

- Reviewer: muse-spark (cross-reviewer; builder was an unrelated subagent — different family).
- Task status: 🟧 Built. Commit reviewed: `da6623b9` (drag/nudge half; sprite-grid half is JB-4.01a, reviewed separately).
- Spec reviewed: `tasks/joybrush/specs/JB-2.16a_size_opacity_drag.md` (contract, decisions 1–6, tests 1–8 + builder Questions 1–5 with four rulings).
- §5b checks: diff touches only NEW `tool/SizeOpacityDrag.kt`, NEW `tool/Nudge.kt`, NEW drag/nudge tests, spec questions — inside the owner area; no swatch UI per "Do not". Suite run by me in a clean HEAD worktree: `SizeOpacityDragTest` 13/13 + `NudgeTest` 4/4, 0 failures.
- Severity scale per §5b: BLOCKER / MAJOR / MINOR.

## Findings: none on the code. Verified instead (including two spec corrections confirmed from the code side)

1. **All 8 spec tests map 1:1 to passing assertions:** sub-12 px `NONE`; +160 px doubles / −160 halves / ±10000 clamps (0.5/4096); no-jump at lock (within 1e-4); opacity 150-up→1.0 / 300-down→0.01 with SIZE frozen; cross-axis frozen after lock; density-2 doubling of both lock (24 px) and span (320 px); preview `10 px @ zoom 4 = 20`; nudge 1/0.25/2/big-5/zero-guard-1.
2. **Lock semantics exact:** straight-line `sqrt(dx²+dy²)` (the `(9,9)` test discriminates against `max(|dx|,|dy|)` — Q1); `>=` comparison (the density-2 "needs 24 px" test fails under `<=` — Q2); ties → SIZE; axis fixed for the gesture; `lockDx/lockDy` snapshot makes the lock frame exponent/numerator exactly 0.
3. **Guards as ruled in Q3, all pinned:** `density ≤ 0/non-finite → 1` (else div-by-zero + lock-on-first-pixel); `startOpacity` coerced to 0.01..1 (else "NaN" on the swatch); `startSize ≤ 0/non-finite → 1`; `screenPerDoc ≤ 0/non-finite → 1` on both classes. The `NaN` arms of `clampSize/clampOpacity` are dead-but-harmless (inputs provably finite upstream).
4. **Q4 (`screenPerDoc` IS `view.zoom`) confirmed from both ends:** `previewRadius = size/2 × zoom` (multiply) and `stepDoc = screenPx / zoom` (divide) agree with `ViewTransform` (`screen = zoom·document`, inverse `/zoom`). An inverted caller shrinks the preview on zoom-in and grows the nudge on zoom-in — opposite of the owner's rule; the direction is pinned by test 8 (`zoom 4 → 0.25`).
5. **Q5's premise is stale — the maintenance trap it fears no longer exists.** The spec says `BrushValidate.MAX_SIZE_PX` is `private`, forcing a local `4096f` copy that can silently drift. In the landed tree `MAX_SIZE_PX` is **public** (`BrushValidate.kt:24-25`, "Public so every size control clamps to the SAME number"), `SizeOpacityDrag.MAX_SIZE` is a direct alias (not a literal), and a test asserts the equality. Spec Q5 needs updating, not the code. (The alias-assertion is vacuous while aliased but trips if anyone replaces it with a literal — correct tripwire placement.)
6. Spec-faithful tension, recorded not filed: `baseSize` guards `>0` but not the `[0.5,4096]` clamp, so `startSize=0.1` (legal per `BrushValidate`) reads 0.1 pre-lock then jumps to 0.5 on the first locked move — against the *spirit* of "does not jump" (tested only at 37). Opacity has no analogue (`baseOpacity` pre-coerced). Fix would clamp at init, but the literal spec clamps only formula output — implementation is spec-faithful, so this goes to the Lead as spec precision, not a code finding.

## Recommendation
No send-back. No BLOCKER, MAJOR, or MINOR open. Update spec Q5's premise (public alias, trap closed) and consider pinning the sub-floor start at init.

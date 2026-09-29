# Adversarial review — JB-4.01a Sprite grid maths (by size / count, cell lookup, edge drag, sub-grids)

- Reviewer: muse-spark (cross-reviewer; builder was an unrelated subagent — different family).
- Task status: 🟧 Built. Commit reviewed: `da6623b9` (grid half; drag/nudge half is JB-2.16a, reviewed separately).
- Spec reviewed: `tasks/joybrush/specs/JB-4.01a_sprite_grid_math.md` (contract, decisions 1–6, tests 1–8 + builder Questions 1–4 with two rulings and two Lead referrals).
- §5b checks: diff touches only NEW `sprite/SpriteGridMath.kt`, NEW `SpriteGridMathTest.kt`, spec questions — inside the owner area; `SpriteGrid`/format untouched per "Do not". Suite run by me in a clean HEAD worktree: `SpriteGridMathTest` 35/35, 0 failures (builder reported 33; the delta is exactly the two LEAD R19 regression tests — consistent, not drift).
- Severity scale per §5b: BLOCKER / MAJOR / MINOR.

## Finding 1 (MINOR): the ties-away-from-zero ruling is implemented but unpinned — `kotlin.math.round` would pass the suite
Proof: `roundedPx` (`SpriteGridMath.kt:321-328`: `floor(v+0.5)`/`ceil(v−0.5)`) correctly implements Q2's ties-away-from-zero (deliberately NOT `round`'s ties-to-even), but the cited pinning test (`aDragIsSharedOutOverTheCellsAndRoundedToWholePixels`) uses 4/3 ≈ 1.33 and −2/2 = −1.0 — neither is a ±0.5 tie. No test does the spec's own example (4 px over 8 cols = 0.5). Swapping in `round` keeps the suite green (`round(1.33)=1`, `round(−1.0)=−1`). Missing test: `dragEdge(8 cols, RIGHT, ±4f)` → ∓1 px. Implementation ✅, coverage ❌.

## Verified (proof — both Lead referrals are already closed by LEAD R19 in the landed code)
- **Q3 (`Int` overflow → silent wrong cell): FIXED.** `cellRect` (`:142-146`) computes in `Long` and `require`s the result in range → throws `IAE` instead of wrapping; `subGridLines` bounds all `Long` (`:250-253`); `grownCell` sums in `Long` with two-sided clamp. Pinned by the R19 tests (cells 0–1 fit, 2–3 refused — refusal beats wrap). The only surviving `Int` multiplies (`fitRect`, `cols·rows−1`) have both factors pre-clamped ≤4096 (≤~16M — provably in range).
- **Q4 (JVM-only `Float.toInt()` saturation): FIXED.** `cellAt` (`:162-179`) converts to `Double`, range-tests BEFORE any `toInt`, and only converts after proving `< rows/cols ≤ 4096` — huge `3.0e38f` returns −1 via comparison, never via saturation. Pinned. All other `toInt()`s are pre-ranged (post-`require`, post-clamp, or ±4096).
- Contract: `bySize` whole-cells ≥1; `byCount` floor ≥1 px (negative/zero extents clamp, commented); `fitRect` keeps top-left; `cellRect` reading order (`index%cols`, `index/cols`) with `IAE` on no-grid/out-of-range, negative origins handled; `cellAt` half-open with −1 outside/spare-strip/non-finite; `dragEdge` RIGHT/BOTTOM/CORNER axis isolation (pinned with `∓9999` on the ignored axis), counts fixed, min-1, saturation, NaN no-op; `subGridLines` div 2..8 interior-only in reading order, verticals-before-horizontals; no-grid → `cellAt −1` / `cellRect` throws / lines empty; over-cap clamps rows-give-way (cols first, then `4096/cols`).
- Packer agreement stronger than spec: formula test AND a real `SpritePacker.pack` + red-channel pixel read at each `cellRect(i)` top-left. The builder's fragility warning (a JB-4.03a signature change breaks a grid test for a non-grid reason) is accurate — worth it, no action.
- Notes (not filed): `Float` signatures ceiling at far origins (`cellRect.x` past 2²⁴ doesn't survive `.toFloat()` round-trip — inherent to `cellAt(board, x: Float)` / `List<FloatArray>`, fixable only by a signature change); "cols/rows stay the same" is literally true only for already-valid grids (invalid ones normalise per Decision 2 — consistent, spec-precision only).

## Recommendation
No send-back. One MINOR (add the ±0.5 tie test so the Q2 ruling is actually pinned). R19's two fixes verified present with regression tests.

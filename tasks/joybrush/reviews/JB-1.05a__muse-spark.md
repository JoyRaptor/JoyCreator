# Adversarial review — JB-1.05a Scatter and count (landed code, row still Ready)

- Reviewer: muse-spark (cross-reviewer; builder was an unrelated subagent — different family).
- Status disclaimer: the board row for JB-1.05a still says 🟦 Ready, but commit `9c7cc9a6` (`Scatter.kt` + `ScatterTest.kt`, 520 lines) is landed on this branch and JB-1.05b (🟧 Built) lists it in Needs and calls it. I review the landed code as new stuff; the board-status gap is noted under JB-1.05b, not double-filed here. If triage keys files off Built rows, treat this as an annex to the 1.05b review.
- Spec reviewed: `tasks/joybrush/specs/JB-1.05a_scatter.md` (contract, decisions 1–5, tests 1–6).
- §5b checks: the commit touches only the two NEW owner-area files. Suite: `ScatterTest` 11/11, 0 failures (fresh `:core:jvmTest` run by me: 410/0 total). All six spec tests map (identity at amount-0/count-1, 3× expansion, perpendicularity at `bothAxes=false`, diameter bound, seed determinism + jitter floor, 1+2n draws regardless of `bothAxes`).
- Severity scale per §5b: BLOCKER / MAJOR / MINOR.

## Finding 1 (MINOR): unvalidated specs are unbounded — giant `count` hangs, NaN angle poisons (defence in depth)
Proof (`Scatter.kt`):
1. `copies` derives from `spec.count` with no cap (`:82-91`): the `isFinite` guard stops NaN (which would otherwise throw in `roundToInt` — commented), but a finite `count` of e.g. 10⁹ passes straight into `repeat(copies)` (`:104`), drawing 2·10⁹ RNG values and a billion-Dab list on the render thread. `BrushValidate` caps count at 1..16 — but only for files that went through it, and this is a public API.
2. `cosA/sinA` of a NaN `dab.angle` are NaN, making every copy's `dx/dy` NaN (`:101, :115-116`) — NaN coordinates into the tile pipeline. (NaN `amount`/`radius` are guarded to scale 0 at `:99`; the angle is not.)
Both are the same advisory-validation gap filed as JB-0.03b Finding 1: safe on the shipped path (`BrushLibrary` validates; `DabPlacer` clamps), unguarded as an API. A per-dab copy cap matching the validator (16) and a finite-angle guard would close it without touching the contract. Filed MINOR (unreachable through validated callers; hostile to direct ones).

## Verified (proof)
- Decision 1 (draw order `c`, then interleaved `a`,`b` per copy): `:81, :106-109`, with the why-interleaved correlation note (`:32-37`).
- Decision 2 (`n = max(1, round(count·(1−countJitter·c)))`): `:82-91`, floor at 1 incl. the jitter-1-rounds-to-0 case.
- Decision 3 (across/along geometry, `b` always drawn): `:110-116` + tangent/perpendicular without a second trig call (`:111-116`, `:22-24`); scale `amount·2·radius` per the contract's diameter wording (`:94`).
- Decision 4 (amount-0/count-1 identity): via `shifted` returning the SOURCE dab on zero offset (`:131-132`) — with the `-0.0f` rationale written down, so the promise is exact, not mostly.
- Decision 5 (copies keep everything but x/y): `dab.copy(x, y)` (`:132`); `inputsOf` once per input dab (`:93`, contract's performance term).
- Empty list draws nothing AND touches nothing (`:76`) — stream preservation for the caller, tested.
- Determinism claim holds structurally (fixed draw count/order, no platform calls besides `cos/sin` — same last-ulp caveat class as the smoother/dabber, negligible).

## Contract tension (endorsed to the Lead, not a new finding)
- `Scatter`'s kdoc requires the caller to hand it the SAME generator the dabber drew from (`:14-18`); JB-1.05b's spec decision 3 mandates a second salted `SplitMix`. Both cannot be the contract — this is JB-1.05b Who-note Q1, confirmed from this side. The view follows its spec; this kdoc then misdescribes the only production caller. One of the two texts must change.

## Recommendation
No send-back (and no row to send back — see disclaimer). One MINOR (copy-count/angle guards). Flip the row to 🟧 Built or slate the guards first; a Built dependent on a Ready row should not stand either way.

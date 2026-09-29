# Adversarial review — JB-3.05a Playback clock (loop / ping-pong / once + audio position)

- Reviewer: muse-spark (cross-reviewer; builder was the orchestrator on the main thread after three empty dispatches — different family).
- Task status: 🟧 Built. Commit reviewed: `bceafe3b`.
- Spec reviewed: `tasks/joybrush/specs/JB-3.05a_playback_clock.md` (contract, decisions 1–7, tests 1–8 + the orchestrator ruling that supersedes Decisions 3/5 on seam, backward interval, audio, `nextChangeMs`, 1-frame, fps, and ulp-exact boundaries).
- §5b checks: diff touches only NEW `anim/PlaybackClock.kt`, NEW `PlaybackClockTest.kt`, board row, spec ruling — inside the owner area; `AnimOps` called, not changed, per "Do not". Suite run by me in a clean HEAD worktree: `PlaybackClockTest` 12/12, 0 failures (full `:core:jvmTest` 584/3, the 3 being JB-3.08a WIP reds + JB-5.10 flaky timing — none here). The ruling's ulp warning (derive boundaries from `frameStartsMs`, never accumulate `hold·1000/fps`) is honoured in both code and tests.
- Severity scale per §5b: BLOCKER / MAJOR / MINOR.

## Finding 1 (MAJOR): `nextChangeMs` at the exact seam (and every interior backward boundary) returns `seam + ε` — the exact thing the ruling forbids
Proof: ruling — "`nextChangeMs` at the seam returns `rangeMs`… the infimum… Do not 'fix' it to `rangeMs + ε`." Code (`PlaybackClock.kt:149-157`): `cyclePos < rangeMs` takes the forward branch; at `cyclePos == rangeMs` it falls into `nextBoundaryOnTheBackwardLeg`, which returns strictly later. Concrete (even 4-frame board, frame `d`): `nextChangeMs(4d)` → `backPos = 3d` → `k = 2` → `4d + (3d−2d) = 5d`. Required: `4d`. Effect: a player that sleeps until the seam, wakes exactly at it, and re-queries gets `5d` — holding D through `(4d, 5d)` where the clock says C. Same root at every falling-edge interior boundary (`nextChangeMs(5d) == 6d`, not `5d`). From-interior queries correctly return the infimum, from-exact queries skip it — inconsistent, and it reintroduces drift-by-sleeping into the one component whose whole reason is no drift. The code's own comment (`:140` "rangeMs is where the player must wake, and it is what this returns") states the ruling intent the code violates. No test pins `nextChangeMs` queried from exact boundaries (only `seam ± 0.5d`).

## Verified (proof)
- Range clamp/swap (swap-then-clamp == clamp-then-swap via monotonic `coerceIn`); `rangeMs` range-relative by subtraction from `AnimOps.frameStartsMs` (same arithmetic, cannot disagree); empty board → `IllegalArgumentException`.
- Board time `t = elapsed·speed`, speed clamped 0.1..4 with non-finite → 1; negative/NaN elapsed → 0 (plus `±Inf → 0` extension, pinned and documented — beyond spec text, harmless).
- LOOP `t mod rangeMs`; ONCE `min(t, rangeMs−1e-6)` with `isFinished = board ≥ rangeMs` (epsilon far below 16.7 ms, far above any ulp — cannot move a boundary).
- PING_PONG per the ruling: `cycleMs = rangeMs + (starts[count−1] − starts[1])` for ≥3 frames else `rangeMs`; backward `backPos = starts[count−1] − (cyclePos − rangeMs)` traversing `[starts[1], starts[count−1])`; seam half-open forward `[0,rangeMs)` / backward `[rangeMs,cycleMs)` with the last forward frame still on screen AT the seam; `A B C D C B A…` pinned dense; 1–2-frame ranges never leave the forward leg.
- Frame lookup largest-`k`-with-`starts[k] ≤ pos` on range-relative starts, both sides of every boundary pinned.
- Audio follows the corrected ruling: LOOP `rangeStartMs + (t mod rangeMs)`; ONCE same until finished, then null; PING_PONG forward `rangeStartMs + cyclePos` (NOT `t mod rangeMs` — the as-written formula desyncs after cycle 1, correctly superseded), backward null; seam measure-zero (frame D, audio null) as stated.
- No drift by construction (vals only, every answer from `elapsedMs`); 1-frame `nextChangeMs = ∞` all modes; ONCE-finished `= ∞`; unplayable fps surfaces `AnimOps`' own exception (no second rule to disagree).

## Recommendation
Fix Finding 1 (one branch: `cyclePos == rangeMs` — and symmetric falling-edge boundaries — must return the infimum, i.e. `clean` itself). Everything else in this task matches the ruling exactly.

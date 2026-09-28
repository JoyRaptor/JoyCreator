# Adversarial review — JB-5.02 stroke picking in dense line work

- Reviewer: mimo (second adversarial pass; muse-spark did not review this task — this is its first review).
- Task status: 🟧 Built. Commit reviewed: `b74aaf0e`.
- Spec: `tasks/joybrush/specs/JB-5.02_stroke_picking.md` (incl. Questions 1–6).
- Suite: `./gradlew -p joybrush :core:jvmTest --rerun-tasks` at `b74aaf0e` → BUILD SUCCESSFUL, 280 tests / 0 failures (StrokePickerTest 23).
- §5b checks: commit `b74aaf0e` touches only `vector/StrokePicker.kt`, `StrokePickerTest.kt`, the spec and the board row (owner area). No edits by this reviewer.
- Severity: BLOCKER / MAJOR / MINOR per ROADMAP §5b.

**Verdict: no BLOCKER. 2 MAJOR, 2 MINOR.** Both MAJORs sit in the two behaviours the task is *named* for — the second tap of a cycle, and dense layers where several lines tie — and both are currently open builder questions (`Confirm the anchor`, the duplicate-id stutter). First-tap picking, tolerance maths, zoom behaviour, determinism and the degenerate-geometry paths all verified sound. Latent: `StrokePicker` has no production caller yet, so nothing is broken in the app today.

## F1 (MAJOR — fails the tie-break edge case Decision 2 names; builder Question 2 is the blocking open question): with three or more candidates straddling a cluster boundary, the 2nd tap can return a line that loses a pairwise tie

- **Proof (code):** `StrokePicker.kt:119-130`. Clusters are anchored at the first score and grown
  with `byScore[end].score - best <= tie` (`:122`) — membership is measured against the **anchor**,
  not against every member. Recency ordering (`:127`, `compareByDescending { index }`) applies only
  *inside* one cluster; a line in the next cluster is always emitted after every member of the
  previous cluster, whatever its pairwise ties say.
- **Proof (spec):** "Sort by score; **scores within 1 screen px of each other are ties → the more
  recent line first**" (`JB-5.02:32-33`) — a pairwise promise. The builder flagged the non-transitivity
  itself and asked "**Confirm the anchor**" (`JB-5.02:63-69`) — **unanswered in `LEAD_RULINGS.md`.**
- **Concrete input (zoom 1, tap at (50, 0), all three horizontal lines x ∈ [0,100], halfWidth 1.0,
  drawn in order `old` (y = 1.5, index 0), `mid` (y = 2.2, index 1), `new` (y = 1.0, index 2)):**
  - `scoreOf` (`:143-168`): `new` = 1.0 − 1.0 = **0.0**, `old` = **0.5**, `mid` = **1.2** (all ≤ slop 12 → candidates, `:112`).
  - Sorted (`:116`) → [new 0.0, old 0.5, mid 1.2]. Cluster loop: anchor 0.0 absorbs `old` (0.5 ≤ 1.0),
    rejects `mid` (1.2 > 1.0) → cluster 1 = {new, old} recency-sorted, cluster 2 = {mid}.
  - Ranking = **[new, old, mid]**. Tap 1 → `new`. Tap 2 inside the 400 ms / 6 px window
    (`nextAfter` `:191-196`) → **`old`**.
  - Pairwise Decision 2 says otherwise: `old` (0.5) and `mid` (1.2) differ by **0.7 ≤ 1.0 → a tie**,
    and `mid` (index 1) is **more recent** than `old` (index 0) → `mid` must precede `old`. The
    unique fully-consistent order is [new, mid, old]; the code gives [new, **old**, mid].
- **Effect:** tap 1 is never wrong (the global minimum always heads cluster 1); the error appears
  **from tap 2 onward — i.e. the "tap again to cycle" feature — and only with ≥ 3 candidates, i.e.
  exactly dense line work.** No test puts three candidates straddling a cluster boundary (verified:
  the tie tests at `StrokePickerTest.kt:120-137` use scores 0.0/0.5/1.5 — `mid`-width pairs across a
  boundary are untested).
- **Filed for the Lead** as the answer to Question 2 ("Confirm the anchor"): either the spec adopts
  the anchor-cluster rule explicitly (and says the pairwise sentence holds only inside a cluster), or
  the code chains ties pairwise (`old`–`mid` is one tie group, then order by recency across it).

## F2 (MAJOR — cycle becomes a permanent no-op and a candidate is dropped): two lines sharing an id (permitted by `InkLine`) make `indexOf` stop on the first copy, so taps 3+ return the same line forever and a distinct candidate becomes unreachable

- **Proof (code):** `nextAfter` uses `ranking.indexOf(previous)` then `ranking[(at + 1) % ranking.size]`
  (`StrokePicker.kt:191-196`). `indexOf` returns the **first** occurrence of a duplicated id.
  `InkLine` requires only array lengths (`InkGeometry.kt:25-31`) — ids are free strings, and the
  spec concedes "`InkLine` permits it" (`JB-5.02:72-75`).
- **Concrete input:** three identical horizontal lines (y = 0, halfWidth 1.0): ids `["dup", "dup", "X"]`,
  tap (50, 0) four times at t = 0/10/20/30 ms (inside the cycle window, inside reach).
  Equal scores → one cluster → recency index-desc (`:127`) → ranking **["X", "dup", "dup"]**.
  - Tap 1 → "X". Tap 2 → `indexOf("X") = 0` → ranking[1] = "dup". Tap 3 → `indexOf("dup") = 1`
    (first copy) → ranking[2] = "dup". Tap 4 → again `indexOf("dup") = 1` → ranking[2] = "dup" …
  - Result: **X, dup, dup, dup, …** — taps 3+ are no-ops, and the candidate at ranking[1]… the
    second "dup" position is never *entered* from a distinct state again; with adjacent duplicates
    the cycle is absorbing on one element.
- **Spec vs code:** the spec's Question 3 (`JB-5.02:72-75`) and the KDoc (`StrokePicker.kt:48-50`)
  describe this input as returning "the same line **twice in a row**" — a two-tap stutter. The
  implementation is worse: a *permanently stuck* cycle that also hides a different id, which is the
  §5b "no-op cycling / dropped candidate" case. The contract assumes ids are unique (the spec says
  so) but nothing enforces it, and the id namespace is shared with JB-5.10, whose
  `EraseResult.survivors: Map<String, List<Piece>>` yields **two pieces with one `sourceId`** for a
  mid-stroke partial erase (`JB-5.10:41`, `:37`) — so a future spec that rebuilds each `Piece` as an
  `InkLine(id = sourceId)` feeds the picker duplicate ids directly. Fix options for the Lead: rank
  by index instead of id (`indexOf` → track position), or require unique ids at the pick API.

## MINOR findings

- **m1 (MINOR, unpinned boundary):** tie membership is **inclusive** — `score - best <= tie` at
  `StrokePicker.kt:122` — but no test exercises a score exactly `1.0` from the anchor (the tie tests
  use 0.5/1.5 spreads), so the inclusive-vs-strict reading the ranking depends on is chosen in code
  only. Combined with F1's cross-cluster effect, the "exactly one screen px" case is the least-pinned
  number in the feature. One assertion at exactly 1.0 closes it.
- **m2 (MINOR, spec wording vs code):** Question 5 says a non-positive or NaN `screenPerDoc` reads
  as "**'no cycle'** and 'zoom 1'" (`JB-5.02:84`), but the code only reinterprets the *zoom*
  (`StrokePicker.kt:78` region, `:178` uses `CYCLE_REACH / zoom`) — under a NaN zoom the cycle still
  proceeds (at zoom-1 distances). The test (`StrokePickerTest.kt:262-273`) only taps **once** under
  those zooms, so the "no cycle" half is asserted nowhere. Either the sentence or the code should
  change; as written they disagree.

## Verified sound (checked independently)

- **Decision 1 (tolerance) inclusive and literal:** `score <= slop` with `slop = 12 / zoom` —
  `theToleranceEdgeIsInclusiveAtExactlyHalfWidthPlusSlop` (`StrokePickerTest.kt:251-258`),
  the 12 → 1.5 doc px zoom test (`:65`), endpoint handling (`:150`).
- **Decision 1's ribbon model:** distance to nearest segment with **interpolated** halfWidth at the
  nearest parameter (`StrokePicker.kt:148-168`, `pointAt`) — pinned by
  `theHalfWidthIsInterpolatedAtTheNearestPointNotAtAVertex` (`:95`) and
  `aCrossingIsRankedByHowFarInsideTheInkTheTapLands` (`:162`).
- **Decision 3/4 (cycle):** wrap, never-null-while-candidates-exist, 400 ms window, 6 px reach
  inclusive (7 px restarts) — `aCycleWalksEveryOverlappingStrokeAndComesBackToTheFirst` (`:203`),
  `cyclingMoreTimesThanThereAreCandidatesNeverReturnsNull` (`:212`), `aSecondTapTooFarAwayStartsTheCycleAgain` (`:223`),
  `aTapAfterTheCycleWindowStartsAtTheFirstCandidateAgain` (`:50`), no-candidate tap resets (`:239`).
- **Determinism:** no hashing anywhere; stable sort + unique index ordering inside clusters
  (`:116,:127`) → identical results across runs and platforms. Pinned by
  `anExactlyEqualTieAlwaysResolvesTheSameWay` (`:120`).
- **Degenerate/hostile geometry:** NaN coordinates never win `d < bestD` (`:163`) → score
  `MAX_VALUE` → not a candidate, without hiding other lines —
  `aLineWithBrokenCoordinatesIsNeverPickedAndDoesNotHideTheOthers` (`:285`). One-point lines handled
  by `segmentCountOf(1) = 1` (`:149-150`).
- **All six numbered spec tests present** (mapping table in the spec's Steps §Tests 1–6 →
  `:26, :34, :42, :50, :57, :65`), plus reach/tie/zoom/crossing tests beyond the list.
- **Performance:** one tap is O(total segments) + O(k log k); no line×line loop, no recursion —
  the spec carries no perf clause (unlike JB-5.10 Decision 5) and nothing here is quadratic.

## Open items for the Lead

- Question 2 → **F1** (anchor confirmed or pairwise rule adopted) — blocking for "Built → Reviewed".
- Question 3 → **F2** (duplicate-id stutter is worse than documented; decide enforcement vs tolerance).
- Question 1 / Question 6 verified consistent with the implementation (zoom effect on ranking;
  crossing scored by ink depth with recency tiebreak) — no action unless the owner wants the top
  line to win at a crossing.

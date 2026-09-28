# Adversarial review — JB-5.10 vector eraser geometry

- Reviewer: mimo (second adversarial pass; muse-spark did not review this task — this is its first review).
- Task status: 🟧 Built. Commit reviewed: `b74aaf0e` (branch tip at review end; spec updated by rulings `fed130e3`/`061fd2b5` during the session).
- Spec: `tasks/joybrush/specs/JB-5.10_vector_eraser_geometry.md` (146 lines, incl. builder Questions 1–6).
- Suite: `./gradlew -p joybrush :core:jvmTest --rerun-tasks` at `b74aaf0e` → BUILD SUCCESSFUL, 280 tests / 0 failures (VectorEraserTest 22, InkGeometryTest 18).
- §5b checks: reviewed files = `vector/VectorEraser.kt` (485), `vector/InkGeometry.kt` (103), `vector/Intersections.kt` (145), both tests, the spec. No edits made by this reviewer.
- Severity: BLOCKER / MAJOR / MINOR per ROADMAP §5b.

**Verdict: no BLOCKER, no MAJOR.** The geometry is sound: I re-derived the quadratic touch solver, the crossing sweep, the complement/merge logic, and the dot-segment handling independently and found them correct. Two MINOR findings are spec-ambiguities that need a Lead ruling (both echo questions the builder already raised — reproduced here with a worked input). Safe for 🟨 Reviewed once the orchestrator closes spec Question 1 with the Lead.

## F1 (MINOR, spec ambiguity — my independent reproduction of the builder's Question 1): `TO_INTERSECTION` keeps sub-0.5 px slivers that `PARTIAL` drops, so the same geometry yields a kept speck or a dropped speck depending only on mode

- **Proof (code):** the speck filter runs only in the PARTIAL branch — `VectorEraser.kt:107`
  `complementOf(line, touched).filter { it.arcLengthIn(line) >= MIN_PIECE_ARC }` (`MIN_PIECE_ARC = 0.5`, `:68`).
  The TO_INTERSECTION branch returns the unfiltered complement — `VectorEraser.kt:120`
  `out[line.id] = complementOf(line, cuts)`.
- **Proof (spec):** Decision 2 says "Drop surviving pieces shorter than 0.5 doc px of arc length"
  (spec `JB-5.10:58`); Decision 4 says only "The complement is the survivor list" (spec `:69`).
  The builder flagged exactly this in Question 1 (spec `:119-122`) and the orchestrator escalated it
  to the Lead; this file is the independent second reading §5b asks for. The two readings of the spec
  genuinely differ, so **the spec — not the code — is what needs the ruling.**
- **Concrete input (worked):** line A horizontal `(0,0)→(120,0)`, sampled every 2 px, halfWidth 1.0.
  Two other lines cross it: B vertical at `x=100`, C vertical at `x=100.3` (both `y ∈ [−20,20]`).
  One eraser gesture = two dots, radius 3 (reach = 3+1 = 4): dot 1 at `(95,0)`, dot 2 at `(105,0)`.
  - Crossings of A: parameters `50.0` (B) and `50.15` (C).
  - Touched stretches: `[45.5, 49.5]` and `[50.5, 54.5]`.
  - `TO_INTERSECTION` cuts (VectorEraser.kt:111-119): cut₁ = `[0, 50.0]`, cut₂ = `[50.15, last]`
    → survivor = `Piece(from = 50.0, to = 50.15)` = **0.3 doc px of centreline, kept** (`:120`).
  - The identical geometry through `PARTIAL` produces the same 0.3 px piece and **drops it** (`:107`).
- **Why it matters:** JB-5.11 will rebuild strokes from these pieces; a kept 0.3 px sliver becomes a
  speck of ink the eraser was asked to remove the neighbourhood of; mode switching changes what is
  kept for the same gesture.
- **Reaches the Lead as:** "spec is ambiguous" per §5b. Options: extend the filter to all modes
  (one line at `VectorEraser.kt:120`), or state in Decision 4 that TO_INTERSECTION keeps slivers.

## F2 (MINOR, spec wording defect — the code chose the sane reading, the spec says something else): the cut runs to the crossing *at or before* the stretch, but the spec says *BEFORE*, and the two readings differ by keeping vs deleting the whole line

- **Proof (code):** `VectorEraser.kt:115` `val lo = nearestAtOrBefore(crosses, t.from) ?: 0.0` —
  "at or before": a crossing whose parameter equals the touched stretch's start is used as the cut's
  start. `nearestAtOrBefore` (`:470-476`) returns `v <= t`.
- **Proof (spec):** Decision 4: "remove the span from the nearest crossing **BEFORE** its start (or
  the line's start, 0, if none)" (spec `JB-5.10:66-67`) — strictly before, so a crossing exactly at
  the start means "none" → `0.0`.
- **Concrete input:** A `(0,0)→(120,0)` sampled every 2 px (halfWidth 1), B vertical `(100,−20)→(100,20)`,
  eraser dot at `(106,0)` radius 5 → reach 6 → touched stretch starts exactly at `x = 100`,
  i.e. `t.from = 50.0`, which equals B's crossing parameter. Line end `lastParam = 60`.
  - Code: `lo = 50.0` → cut `[50, 60]` → survivor `Piece(0, 50)` = the line keeps `[0 → x=100]`.
  - Spec-literal (strictly before): no crossing `< 50` → `lo = 0.0` → cut `[0, 60]` →
    survivor list empty → **the entire line is deleted**.
- The code's answer (trim up to the intersection you rubbed onto) is the behaviour the spec's own
  box-overhang rationale wants; the literal wording deletes a line the eraser never passed over.
  Reachability is the exact-equality case (eraser boundary landing precisely on a crossing), rare in
  freehand but exactly the geometry a snapped corner edit produces. **Spec wording should become
  "at or before".** Per §5b this is filed as a spec defect for the Lead, not "fixed" here.

## Verified sound (checked independently this pass — file/line evidence)

- **Dot handling:** `segmentCountOf(pointCount) = if (pointCount <= 1) 1 else pointCount - 1`
  (`InkGeometry.kt:59`) — a one-sample eraser or a one-point line gets one degenerate segment, so a
  *tap* erases. Pinned by `anEraserWithTwoIdenticalSamplesIsStillADot`, `aSinglePointLineIsAllOrNothing`.
  (This was my prime suspect; it is correct.)
- **Touch solver:** I re-derived `appendTouchingRanges` (`VectorEraser.kt:196-288`): distance² to the
  eraser's closest feature (end/interior regime split by the perpendicular foot, roots at `:219-227`)
  minus (radius + linearly interpolated halfWidth)² (`:208-209`, `:249-251`) is a quadratic per regime;
  `appendQuadLeq` (`:295-330`) handles both root forms with the stable `q` formula; threshold-negative
  regimes skipped at `:257`. Matches spec Decision 1 (segment distance, interpolated halfWidth).
  Pinned by `exactlyEqualDistanceAtTheBoundaryCounts`, `theHalfWidthIsWhatTheEraserIsMeasuredAgainst`.
- **Complement/merge:** `mergeRanges` (`:450-467`) sorts+merges and truncates correctly;
  `complementOf` (`:437-447`) only emits strict gaps, so pieces are ordered, disjoint and inside
  `[0, lastParam]` — pinned by `survivorsAreOrderedDisjointAndInsideTheLine`.
- **Crossings:** sweep stops at the first line whose box starts past ours (`:402`) — sound early exit;
  self-crossings skipped by id (`:408`), matching spec "Do not count a line's crossings with itself"
  (spec `:106`) and builder Question 4; collinear overlap returns both ends as two crossings
  (`Intersections.kt:127-138`), pinned by `aCollinearNeighbourIsACrossingAtBothEndsOfTheOverlap` —
  consistent with the orchestrator's provisional ruling on Question 3.
- **Degenerate doubling-back:** a zero-length segment consumes a parameter step; the spec's own
  worked example answer 2.6 is implemented and pinned by `aZeroLengthSegmentStillSplitsTheLine`
  (spec Question 5 / ruling).
- **Determinism:** no hashing or iteration over unordered maps; `crosses.sort()`, stable
  `sortBy`, LinkedHashMap output in line order — same input, same bytes, JVM/ART identical.
  Pinned by the perf test's cold/warm key-set equality (`VectorEraserTest.kt:216`).
- **Spec tests 1–10 all present:** `partialCutsOnlyTheStretchUnderTheEraser`, `partialMiss…`,
  `partialAtAnEnd…`, `wholeStroke…`, `toIntersectionTrimsAnOverhang…`, `…BetweenTwoCrossings…`,
  `…WithNoCrossings…`, `twoTouchedStretches…`, `aZigZagEraserPath…`, `fiftyLinesOfFiveHundredPointsEraseQuickly`
  (+ a TO_INTERSECTION timing test beyond spec, `:223`).
- **Hostile numerics:** NaN/Inf coordinates or half-widths do not crash — they make every comparison
  false, so no touch range is appended (silent no-op, `ParamRange`'s `require(from <= to)` at
  `Intersections.kt:24` is never fed NaN by this path). Spec does not define NaN input; recorded as
  checked, not a finding.

## Open items this reviewer agrees should stay open

- Builder Question 1 → **F1 above, reproduced.** Lead must rule before JB-5.11 treats sliver behaviour
  as settled (orchestrator log already carries this warning).
- Builder Question 2 (tolerant `EraserPath`) → matches implementation (`VectorEraser.kt:71-73`,
  shared-prefix sweep `:72`); orchestrator ruled "keep tolerant" — I agree, an empty path is a real
  mid-stroke state.
- Builder Question 6 (quadratic worst case, no timing test for 50 mutually-overlapping lines) →
  still true: the two perf tests use disjoint cells by construction (`VectorEraserTest.kt:224-227`).
  Not blocking; a spatial grid is the fix if a real layer looks like that.

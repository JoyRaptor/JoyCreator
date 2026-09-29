# Adversarial review — JB-5.10 Vector eraser geometry (partial, whole-stroke, to-intersection)

- Reviewer: muse-spark (cross-reviewer; builder was the orchestrator's subagent — different family).
- Task status: 🟧 Built. Commit reviewed: `31d4b007`.
- Spec reviewed: `tasks/joybrush/specs/JB-5.10_vector_eraser_geometry.md` (API, decisions 1–6, tests 1–10 + builder Questions 1–6).
- §5b checks: diff touches only NEW `vector/{InkGeometry,VectorEraser,Intersections}.kt`, NEW `vector/{InkGeometryTest,VectorEraserTest}.kt`, and the spec's Questions appendix — inside the owner area; no strokes/layers/undo/UI per "Do not". Suite evidence: `VectorEraserTest` 22/22 + `InkGeometryTest` 18/18, 0 failures (verification run 2026-09-28 19:11; fresh re-run blocked — see top note in JB-0.02b file). All ten spec tests map (middle cut ±6 px incl. the 44/56 edges, miss, end cut, whole-stroke, overhang trim, between-crossings, lone-line removal, two-stretch single path, zig-zag segments, 50×500 in < 500 ms). (The two failures I saw in this area in round 1 were this task's pre-Built in-flight state — fixed before landing.)
- Severity scale per §5b: BLOCKER / MAJOR / MINOR.

## Finding 1 (MAJOR): TO_INTERSECTION's known worst case breaks decision 5's promise — no grid, sweep can't prune it
Proof (`VectorEraser.kt`):
1. Spec decision 5: "50 lines × 500 points must erase in under 50 ms"; test 10 pins < 500 ms for the *tested* shape. Builder Q6 admits 50 long lines sharing one region is not timing-tested.
2. `crossingsOf` (`:385-434`) runs per *touched* line over the left-to-right sweep; when all boxes overlap, the sweep prunes nothing (`:402` never breaks early) and every touched line pays ~49 others × 500 × 500 segment pairs = ~12M `boxesMayTouch` + `segmentIntersections` calls; 5–10 touched lines per rub ⇒ 60–120M pair tests per gesture. At ~5–20 ns per cheap reject this is tenths of seconds to seconds — 10–100× past the 50 ms goal — as a UI-thread freeze on every rub in exactly the dense cross-hatching this feature exists for.
3. PARTIAL/WHOLE modes are unaffected (no crossing search; per-line cost is segs × eraser-segs box checks — 500 × 200 = 100k/line, fast). The hole is TO_INTERSECTION-only.
Reproducer for the orchestrator (triage rule 1 — run it; rule 4 if it doesn't reproduce). Pasted, not added to the tree:
```kotlin
@Test
fun toIntersectionOverFiftyOverlappingLinesStaysInteractive() {
    val rnd = kotlin.random.Random(5)
    fun hatch(k: Int): InkLine {
        val n = 500
        // All 50 lines wander inside one shared 200x200 region: no box prunes another.
        val xs = DoubleArray(n) { 100.0 + rnd.nextDouble(-100.0, 100.0) }
        val ys = DoubleArray(n) { 100.0 + rnd.nextDouble(-100.0, 100.0) }
        // …sorted so each line is a dense scribble, not white noise…
        return InkLine("l$k", xs, ys, DoubleArray(n) { 1.0 })
    }
    val lines = List(50, ::hatch)
    val t = kotlin.time.TimeSource.Monotonic.markNow()
    val r = VectorEraser.erase(lines, EraserPath(doubleArrayOf(100.0), doubleArrayOf(100.0), 3.0), EraseMode.TO_INTERSECTION)
    assertTrue(r.survivors.isNotEmpty())
    assertTrue(t.elapsedNow().inWholeMilliseconds < 500, "took ${t.elapsedNow()}")
}
```
Notes: scribble lines maximise segment-pair box overlap (worst case); straight parallel hatch would prune better. Fix direction (Lead call): uniform grid over line segments (the builder's own suggestion), or scope the promise to "lines that do not all overlap". Filed MAJOR (fails the performance edge decision 5 names, on an input the feature's own cross-hatching use case produces) with the predicted-not-measured caveat stated plainly.

## Finding 2 (MINOR): the `len` kdoc inverts `hypot`
Proof: `Intersections.kt:18` — "Cheaper and steadier than hypot, which overflows far sooner." Backwards: `hypot` exists precisely to avoid the `dx*dx` overflow that `sqrt(dx*dx + dy*dy)` suffers (at ~1e200 coords; document scale never nears it, which is why this is clarity-only). Cheaper is true; "steadier … overflows sooner" is false. One-word fix ("Cheaper than hypot; equivalent at document scale").

## Verified (proof)
- Touching solved exactly per regime as quadratics with the threshold-zero break (`appendTouchingRanges`, `:196-288`), stable root form (`appendQuadLeq`, `:295-330`), `breaks` array sized exactly 5 for its 2+2+1 maximum (`:211-237` — counted, no overflow); zero-length segments consume parameter steps per spec Q5's worked 2.6 example (`:176-180`, pinned by `aZeroLengthSegmentStillSplitsTheLine`).
- PARTIAL complement + 0.5-px speck filter (`:106-107`); WHOLE maps to empty; TO_INTERSECTION nearest-crossing-outward + merge + complement (`:108-121`); lone line ⇒ whole length removed (`:115-116` with nulls); crossings skip self by index AND id (`:403, :408`, spec Q4 incl. duplicated ids); collinear overlap ⇒ entry+exit pair (`segmentIntersections`, `:127-138`, spec Q3 as implemented).
- `mergeRanges`/`complementOf` accounting sound (sorted+merged preconditions hold at both call sites: `:183`, `:119`); `ParamRange` refuses inversions (`:23-25`), and every emission site guards (`appendQuadLeq` clamps, `:305-328`; touch mapping `to >= from`, `:179`).
- NaN-safety traced fail-safe, never corrupting: NaN radius ⇒ `pad` NaN ⇒ box comparisons false ⇒ exact solve yields no ranges ⇒ line untouched (`:157-167` + quad-NaN analysis: `NaN > 0` false both ways, nothing emitted); NaN coords likewise untouched; NaN crossing params sort last and are skipped by `nearestAtOrBefore/After` (`:470-484`); NaN dot "crossing" (`Intersections.kt:103-109`, `NaN > EPS` false) can only add an ignorable NaN. No `require` needed on these paths.
- Leniency per spec Q2 confirmed safe: empty/mismatched eraser path ⇒ empty map (`VectorEraser.kt:72-73`, shared-prefix sweep); no `EraserPath` requires, documented in its kdoc (`:24-27`).
- `InkLine` contract enforced (`InkGeometry.kt:25-31`: parallel arrays, non-empty), content equality for copy-comparison (`:33-46`), dot-lines legal with one degenerate segment (`segmentCountOf`, `:59`), `pointAt`/`arcLengthBetween` clamped (`:65-97`).
- TO_INTERSECTION sliver policy (PARTIAL-only filter) and orphan/duplicate-id/collinear choices all match the spec's Questions as documented — endorsed, not re-filed.

## Recommendation
Rule on Finding 1 before dense-ink work depends on TO_INTERSECTION timing (grid it, or narrow decision 5's promise to non-pathological layouts, with the reproducer as the acceptance test). Finding 2 is one word. No BLOCKER open.

# JB-5.10 — Vector eraser geometry: partial, whole-stroke, and erase-to-intersection

| | |
|---|---|
| **Tier** | T2 (no vision needed) |
| **Status** | see ROADMAP.md |
| **Depends on** | none (self-contained geometry) |
| **Owner area** | `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/vector/` (new folder: `InkGeometry.kt`, `VectorEraser.kt`, `Intersections.kt`), `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/vector/` |
| **Estimated size** | ~350 lines + ~300 lines of tests |

## Goal
On Ink (vector) layers the eraser erases LINES, not pixels (owner, 2026-09-28). Three modes:
- **Partial** — cut out the part of any line under the eraser, at the eraser's width (Concepts).
- **Whole stroke** — any line the eraser touches disappears entirely.
- **To intersection** — erase only the stretch of a line between the two nearest places where it
  crosses other lines. Sketch a rough box with overhanging corners, rub the overhangs, and they are
  trimmed back to exactly where the lines cross, leaving clean corners (Clip Studio's "erase up to
  intersection").
This spec builds the geometry only. Turning the results into new strokes on a layer, undo and the UI
are later specs.

## The API to build
```kotlin
package cc.joycreator.joybrush.core.vector

/** One ink line as the eraser sees it: its centreline and the half-width at each point (doc px). */
data class InkLine(val id: String, val xs: DoubleArray, val ys: DoubleArray, val halfWidths: DoubleArray)
// (require all three arrays the same length ≥ 1; implement equals/hashCode by content)

/** The eraser's path this gesture, and its radius (doc px). */
data class EraserPath(val xs: DoubleArray, val ys: DoubleArray, val radius: Double)

/**
 * A piece of a line that SURVIVES. Parameters are fractional point indices along the source line:
 * 3.25 means a quarter of the way from point 3 to point 4. [0, n-1] is the whole line.
 */
data class Piece(val sourceId: String, val from: Double, val to: Double)

data class EraseResult(
    /** Lines that changed. A line absent from this map is untouched. An empty list = line removed. */
    val survivors: Map<String, List<Piece>>,
)

enum class EraseMode { PARTIAL, WHOLE_STROKE, TO_INTERSECTION }

object VectorEraser {
    fun erase(lines: List<InkLine>, eraser: EraserPath, mode: EraseMode): EraseResult
}
```

## Decisions already made
1. **Touching** = the distance from the eraser path (as line segments; a single point is a dot) to
   the line's centreline is `≤ eraser.radius + halfWidth` at that point of the line. Use
   segment-to-segment distance, linearly interpolating halfWidth along each line segment.
2. **PARTIAL:** remove every stretch of centreline whose distance to the eraser path is
   `≤ radius + halfWidth`. Compute the removed intervals exactly per line segment (solve where the
   distance crosses the threshold; bisection to 1e-6 of a segment is fine), merge them, and return
   the complement as `Piece`s. Drop surviving pieces shorter than 0.5 doc px of arc length. A line
   with no touching stretch is not in the map.
3. **WHOLE_STROKE:** every touched line maps to an empty list.
4. **TO_INTERSECTION:** for each touched line L:
   - Find **crossing parameters** of L's centreline with the centrelines of every OTHER line in
     `lines` (proper segment–segment intersections; a touching endpoint counts). Ignore
     self-intersections of L.
   - Take the parameters `t` where the eraser touches L (all touched stretches).
   - For each touched stretch, remove the span from the nearest crossing BEFORE its start (or the
     line's start, 0, if none) to the nearest crossing AFTER its end (or the line's end, n−1, if
     none). Merge overlapping spans.
   - The complement is the survivor list. Lines with no crossings at all behave like WHOLE_STROKE.
5. Use doubles throughout. Bounding-box prefilter per segment pair.
   **NARROWED BY R28 — read this before believing the number this line used to carry.** It said
   "50 lines × 500 points must erase in under 50 ms on a desktop JVM, measured in a test with a
   generous timeout". That was **three mistakes at once**: the bound is not reachable for the
   fully-overlapping layout, a *wall-clock* assertion is flaky by construction (it failed for the
   Lead only because another Gradle job was running), and no spatial index would have delivered it
   either — the eraser already has a per-line bounding-box prefilter, and a grid has a ~3.7× ceiling
   on the adversarial layout. **The promise now applies to REALISTIC layouts** — lines spread
   across the layer — and the adversarial layout is bounded by a **deterministic work counter**
   (segment-pair tests ≤ a stated N, reproducible on every machine), never by milliseconds. The
   operative text and the bounds are in "Decision 5, as narrowed by R28" below.
6. The eraser does not care which line is "on top"; ink lines are all equal.

## Steps
1. Tests first. 2. `Intersections.kt`: segment–segment intersection (with parameters on both),
segment–segment distance, point–segment distance. 3. `InkGeometry.kt`: arc length, parameter
helpers. 4. `VectorEraser`.

## Tests (`VectorEraserTest.kt`)
Helper: build straight lines by sampling every 2 doc px; halfWidth 1.0 unless stated.
1. **Partial middle cut:** horizontal line (0,0)→(100,0); eraser dot at (50,0) radius 5 →
   survivors ≈ [0 → ~(44/2)] and [~(56/2) → 50] in parameter terms (i.e. the cut edges within 0.1
   doc px of x = 44 and x = 56).
2. **Partial miss:** eraser at (50,20) radius 5 → result map empty.
3. **Partial at an end:** eraser at (0,0) → one survivor starting near x = 6.
4. **Whole stroke:** two lines, eraser touches one → that id → empty list; other absent.
5. **To intersection, overhang:** horizontal A (0,0)→(120,0) and vertical B (100,−20)→(100,100).
   Rub A near (112,0) (eraser dot radius 3) → A survives as [0 → param at x=100]; B absent.
6. **To intersection, between two crossings:** A (0,0)→(200,0); verticals at x=50 and x=150.
   Rub A at (100,0) → A survives as [0→x=50] and [x=150→200].
7. **To intersection, no crossings:** a lone line rubbed → removed entirely.
8. **Two stretches:** A crosses verticals at x=50,100,150; rub at x=25 and x=125 in one eraser path
   (a path from (25,0) up and over to (125,0) that only touches A at those two places) → survivors
   [x=50→x=100] and [x=150→end].
9. **Zig-zag eraser path** (as segments, not dots) cuts a line in two places in PARTIAL mode.
10. **Performance:** 50 random lines × 500 points and a 200-point eraser path completes in < 500 ms
    (generous; the goal is 50 ms).

**Command:** `./gradlew -p joybrush :core:jvmTest`. Passing = `BUILD SUCCESSFUL`, the suite with 0
failures in `joybrush/core/build/test-results/jvmTest/`.

## Do not
- Do not create new strokes, layers, undo steps or UI — geometry only.
- Do not touch anything outside the owner area. No dependencies.
- Do not treat the eraser as pixel-based; it works on centrelines + half-widths.
- Do not count a line's crossings with itself.

## Definition of done
- [ ] tests pass (paste the output)
- [ ] only owner-area files changed (paste `git status --short`)
- [ ] committed as `JB-5.10: vector eraser geometry`; pushed
- [ ] ROADMAP.md row → 🟧 Built

## Questions

*Raised by the JB-5.10 build, 2026-09-28. None of these blocked the build; they are decisions I took
that a later spec may want to overrule.*

1. **The 0.5 doc px speck filter — PARTIAL only.** Decision 2 mandates dropping surviving pieces
   under 0.5 doc px; decision 4 says only "the complement is the survivor list". I implemented the
   filter for PARTIAL only, because that is the literal reading. TO_INTERSECTION can therefore
   return a sub-0.5 doc px sliver. Either say so, or extend the filter to all modes.
2. **An empty or half-built `EraserPath` touches nothing** rather than throwing. `InkLine` has
   `require`s; `EraserPath` has none, so `EraserPath(DoubleArray(0), DoubleArray(0), r)` is legal and
   means "the pen has not moved yet". Mismatched `xs`/`ys` lengths are tolerated the same way (only
   the shared prefix is swept) rather than rejected. Confirm, or add `require`s to `EraserPath`.
3. **Collinear overlap is a crossing, reported at both ends.** "Proper segment–segment
   intersections" does not say what to do when two lines lie on top of each other. I treat the
   overlap's entry and exit as two crossings, so a line lying along another trims to where the
   overlap starts and ends. The alternative is to ignore collinear pairs entirely.
4. **A line is never compared with another line of the same `id`.** The ids in `lines` are assumed
   unique because `survivors` is keyed by them; the self-crossing rule is enforced on the id, not on
   object identity, so a duplicated id is also skipped.
5. **A degenerate segment still consumes a parameter step, so the answer is 2.6 — not 2.0.**
   Worked example, which is also a test: a line through (0,0), (10,0), (10,0), (20,0) (it doubles
   back, so points 1 and 2 are the same place under two different parameters), half-width 1, and a
   dot eraser of radius 5 sitting on (10,0). The eraser reaches 5 + 1 = 6 doc px, so it cuts the
   centreline from x = 4 to x = 16. Those are parameter 0.4 (on the point 0 → 1 segment) and
   parameter 2.6 (on the point 2 → 3 segment). The zero-length segment between points 1 and 2 is
   erased too, so the erased stretch is one unbroken run from 0.4 to 2.6 and the survivor restarts
   at 2.6 — not at 1.0, and not at 2.0. The survivor does begin at the right *place* (x = 10), so
   the picture is correct either way, but the numbers a later spec receives depend on this.
6. **TO_INTERSECTION is quadratic in the number of lines.** With a left-to-right bounding-box sweep
   it is fine for lines that do not all overlap (the tested case), but 50 long lines sharing one
   region is the known worst case and is *not* covered by a timing test. A uniform grid over the
   eraser path would fix it if a real layer turns out to look like that.

   **RESOLVED (review Finding 1, 2026-09-28).** Real, and worse than filed: the sweep is not
   bypassing anything, it simply has nothing to prune when all the boxes coincide. Traced on the
   reviewer's own geometry (50 × 500 points inside a 200x200 region, so every line's box is the
   whole region and `minX ≈ 0.4, maxX ≈ 199.6` for all 50): all 50 lines are touched, the break at
   the old `:402` never fires, the y-reject never fires, and the cost is
   50 × 49 × 499 × 499 = **6.1e8** `boxesMayTouch` calls, ~15% of which reach
   `segmentIntersections`. Worse, the real cost was downstream of that: the crossing list is
   `ArrayList<Double>`, and two random 104 px segments in a 200x200 box cross with probability
   ≈ 0.11, so each line collected ≈ 1.4e6 **boxed** crossings — sorted, then scanned linearly once
   per touched stretch. Seconds to tens of seconds, and ~20 MB of garbage per line.
   **I did not build the grid**, and the reason is arithmetic rather than taste: a bucket grid over
   that region cannot subdivide it. With S segments of mean length L spread over area A, the best a
   grid can do on a query is ≈ S·L²/A candidates however small the cells get (occupancy
   S(1+L/c)·(1+L/c)·c²/A minimises to that as c → 0), and here A = 40 000 px² with L ≈ 104 px, so
   A/L² ≈ 1 — **the grid's ceiling in this layout is 25 000 → ~6 800 candidates, a factor of 3.7**,
   bought with ~1.3M insertions. It would not have fixed the case, only moved it.
   What did fix it is not asking the question the old code was asking: only the *nearest* crossing
   beside each touched stretch is ever used, so `crossingTrims` walks outward from the stretches and
   stops at the first crossing, with a shared frontier and cursor per direction so a segment is
   compared once per direction. The same 6.1e8 becomes ~2 000 segment scans (each still comparing
   one segment against the other 49 lines' 499), and the crossing list drops from 1.4e6 entries per
   line to a handful. Answers are unchanged — the values are the same largest-at-or-before and
   smallest-at-or-after the full list would have given, and
   `toIntersectionOverFiftyOverlappingLinesStaysInteractive` now times it.
7. **NEW: the fix costs up to two passes over a line's segments where the old code cost one.** The
   backwards and forwards walks share nothing but the line, so a line with several touched stretches
   and *no crossings anywhere near them* is walked once left and once right — 2× the old pair count
   in a layout that was already slow. It is 2× and not more because the frontier is shared between
   stretches, and it is the only case that pays it: as soon as there is a crossing within a segment
   or two, both walks stop. I took this knowingly rather than adding a per-segment crossing cache
   (~40 more lines of index bookkeeping in geometry code I cannot compile-test) for a 1.5–2× on a
   case no real layer produces. Reversible if a real layer does.
8. **NEW: decision 5's "under 50 ms" is not reachable for the fully-overlapping layout, by this or
   by a grid.** A single segment of such a line crosses ≈ 2 750 of the other 24 999 segments (they
   are packed so densely that the mean gap between crossings along a line is 0.04 doc px), so an
   exact answer costs at least a few thousand segment-segment tests per segment examined however
   the candidates are indexed. My estimate for the reviewer's data is 150–250 ms warm, against
   6–10 s before; the test asserts < 500 ms, the same generous bound the other timing tests use.
   My ask: either narrow decision 5 to "layouts where a segment's box does not span the layer" —
   which is every real ink layer, and where the sweep alone already gives two orders of magnitude
   — or accept that a deliberately adversarial 50-line scribble may take a couple of hundred ms
    once per gesture. If you want the number under 50 ms anyway, the next step is a segment-level
    index, and its ceiling in *this* layout is the 3.7× above; it pays properly only when segments
    are short relative to how far they are spread (A/L² ≫ 1), which is the sparse case the sweep
    already handles.

## Lead rulings

### R28 (2026-09-29) — applied to JB-5.10 as a rework of landed work

**The ruling.** Three parts.

1. **Do NOT build a spatial index.** The builder's own arithmetic puts a bucket grid's ceiling on
   the adversarial layout at ~3.7x (A/L² ≈ 1, Q6 and Q8), so a grid would not deliver the 50 ms
   promise there anyway — it would only move the number. The per-line bounding-box prefilter is what
   a real layer needs, and it is already there.
2. **Decision 5's promise applies to REALISTIC layouts** (lines spread across the layer). The
   adversarial layout is bounded by a **deterministic work counter** — segment-pair tests ≤ a stated
   N — and never by milliseconds. The counter is an `internal` test hook; the `< 2 500 ms` assertion
   is deleted. The "known-red" test was misdiagnosed: it passes alone and failed for the Lead only
   while another Gradle job was running, because a wall-clock assertion is flaky by construction.
3. **Q1 is decided: the 0.5 doc px speck rule applies to ALL THREE modes**, implemented once in
   `VectorEraser`, so JB-5.11 inherits it with no further edit.

#### What was done

1. **No index was built.** `crossingTrims` and the two prefilters stand exactly as they landed. Q6
   and Q8 are answered: not built, and not needed for any layer a person draws on.
2. `VectorEraser` grew two `internal` counters and nothing else — no behaviour change to the
   geometry:
   - `crossingPairTests` — segment pairs the TO_INTERSECTION crossing search **examines**, counted
     BEFORE the segment-box test. Counting before it is deliberate: a prefilter that stops working
     shows up as more pairs examined, which is the work, whereas counting only the pairs that reach
     the intersection maths hides the regression inside the count.
   - `touchPairTests` — line segment against eraser segment pairs that **pass** the box test, i.e.
     the pairs that then get the exact quadratic treatment. Counted after it, because in that loop
     the box test is the only pruning there is; counting before it would measure nothing but the
     length of the loop.
   Both are zeroed by `resetWorkCounters()`. The eraser never reads either. They are not
   thread-safe, deliberately: a test counts one single-threaded call at a time.
3. The speck filter now runs once, after the `when`, on whatever the mode produced:
   `out[line.id] = pieces.filter { it.arcLengthIn(line) >= MIN_PIECE_ARC }`. It is vacuous for
   WHOLE_STROKE, which produces no pieces at all. **Q1 is closed.**

#### Decision 5, as narrowed by R28 — this is the operative text

> 5. Use doubles throughout. Bounding-box prefilter per segment pair. **The "50 lines × 500 points in
> under 50 ms" promise is made about REALISTIC layouts — lines spread across the layer, where a
> stroke's box does not span the layer — and it is kept there by a deterministic work bound rather
> than by a clock. A deliberately adversarial layout (many strokes scribbling through one small
> region, so that no box prunes another) carries NO time promise at all: it is bounded by a stated
> number of segment-pair comparisons, which is the only thing a work counter can honestly assert,
> and which is reproducible on every machine. Decision 5's original line, further up, still reads
> "under 50 ms ... measured in a test with a generous timeout"; this rework was scoped append-only
> and did not edit that line, so where the two differ, this paragraph governs.**

#### The bounds, and the arithmetic behind each N

| test | measured today | N | what a regression there costs |
|---|---|---|---|
| `fiftyLinesOfFiveHundredPointsCompareBoundedWork` (PARTIAL + WHOLE_STROKE, 50 lines in one 400 px cell) | 14 567 | 50 000 | 4 970 050 = the complete double loop, 50 × 499 line segments × 199 eraser segments |
| `toIntersectionOnFiftySeparateCellsComparesNoPairAtAll` (1 stroke per cell, 100 px of clear air) | 0 | exactly 0 | 637 365 714 with the line-box prefilter deleted |
| `toIntersectionOnFiftySpreadStrokesComparesBoundedWork` (2 strokes per cell, 5 × 5 grid) | 3 822 340 | 10 000 000 | 24 900 100 for a whole-line walk (50 × 2 × 499 × 1 × 499); 119 435 151 with the line-box prefilter deleted |
| `toIntersectionOverFiftyOverlappingLinesComparesBoundedWork` (adversarial: 50 scribbles through one 200 px box) | 59 513 734 | 200 000 000 | 1 220 104 900 for a whole-line walk, 50 × 2 × 499 × 49 × 499 — 6.1e8 was the pre-fix cost |

Each measured figure is reproducible rather than merely repeatable: a seeded `kotlin.random.Random`
or a fixed 48-bit LCG, and every comparison the counters tally is an exact min/max on doubles, so
the same integer comes out on every platform and every run. Two further tests pin the counters
themselves to hand-countable numbers (3 pairs for the touch test, 2 + 4 for the crossing test),
because a counter that is never incremented is a test that never fails.

**The old "fifty disjoint lines" layout was replaced on purpose.** Fifty strokes with 100 doc px of
clear air between their cells have no crossings at all, so the counter read exactly 0 there — and a
bound over 0 passes for the wrong reason. That layout is kept, and now asserts `== 0` on purpose,
because "these strokes cannot be compared with each other, and the prefilter is what stops them"
is precisely the guarantee R28 credits the eraser for, and it is the only one of the four layouts
where the prefilter is load-bearing. The realistic-layout test now uses two strokes per cell, so the
crossing search genuinely runs and the walk's early stop is what the bound measures.

#### What the deleted clocks caught, and what nothing catches now

The three deleted assertions (and they were three, not the one the ruling names — see the note
below) would have caught:

- **more pairs compared** — the pruning regressing, e.g. the walk no longer stopping at the first
  crossing. Caught now, by the work bounds, and on a quiet machine as well as a busy one.
- **the prefilter rejecting less** — caught now, by the two TO_INTERSECTION bounds and the `== 0`.
- **a per-PAIR cost increase** — one pair becoming several times more expensive without becoming
  more numerous. **NOT caught.** A work counter counts pairs; it says nothing about what a pair
  costs, so a regression hidden inside `segmentIntersections` or inside allocation would pass all
  four tests. The deleted clocks were the only thing standing behind that, and they stood behind it
  by being flaky, which is the same property that made them worthless.
- **allocation or GC pressure** — a change that allocates per pair and is still within N pairs would
  pass every bound here. The old `< 500 ms` bounds were, on a quiet machine, the only guard.

#### Mutation evidence (each run pasted, each reverted)

- **A — the walk stops stopping** (both `break`s in `crossingTrims` removed, i.e. the exact shape the
  fix removed; the answers were unchanged, so only the work assertions fired):
  `723 tests completed, 2 failed` — adversarial 59 513 734 → **1 165 392 544** (predicted ceiling
  1 220 104 900), spread 3 822 340 → **23 597 710** (predicted 24 900 100). Both regressions landed
  within 5% of the arithmetic in the table, which is the check that the arithmetic is real.
- **B — the line-box prefilter deleted** (that check only): `724 tests completed, 2 failed` —
  separate cells 0 → **637 365 714**, spread 3 822 340 → **119 435 151**. The adversarial test is
  correctly unaffected, because in that layout every box is the whole region and the prefilter never
  rejected anything; that is why the prefilter is guarded over there and not here.
- **C — the speck rule neutered** (`>= MIN_PIECE_ARC` → `>= 0.0`): `724 tests completed, 3 failed` —
  both new TO_INTERSECTION speck tests plus the pre-existing PARTIAL one, which is the evidence that
  the "once, in `VectorEraser`" edit really covers all three modes.

#### Notes for the board

- **PROVISIONAL — Claude to confirm.** The ruling names one assertion (`< 2 500 ms`). I removed
  **all three** wall-clock assertions in the file, on the ruling's own argument that a wall-clock
  assertion is flaky by construction — an argument that does not stop at the first test, and two of
  the three were in the same file under the same ruling. If the Lead wants the other two back as
  clocks, they are one line each and the work bounds can stay.
- **PROVISIONAL — Claude to confirm.** The two hand-counted counter tests (`3`, and `2 + 4`) pin the
  counters' meaning as tightly as the current implementation defines it. If a future change to the
  walk alters how many pairs a tiny geometry costs, those tests are the ones that will complain
  first — deliberately, but it is worth knowing they are coupled to the implementation's shape.
- The ruling asked for the counter "as an `internal` test hook"; that is what these are, matching the
  existing `StrokeSmoother.holdRelease` convention rather than inventing a second one.

#### Question for the Lead

- R28 requires the caveat to live in decision 5's own text, and it does — in the amended decision 5
  above. But this rework's owner area allowed me to **append** to this spec, not to edit the
  "Decisions already made" list, so line 5 still reads "under 50 ms ... measured in a test with a
  generous timeout" and the two disagree in wording. I have not touched it. One line of edit by
  whoever owns the list would settle it; nothing else in the repo depends on the old wording.

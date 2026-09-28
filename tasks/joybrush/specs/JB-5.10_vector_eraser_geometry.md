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
5. Use doubles throughout. Bounding-box prefilter per segment pair (performance: 50 lines × 500
   points must erase in under 50 ms on a desktop JVM — measured in a test with a generous timeout).
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

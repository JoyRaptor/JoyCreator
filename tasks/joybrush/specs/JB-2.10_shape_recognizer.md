# JB-2.10 — Hold-to-shape: recognise a rough shape and perfect it, keeping the pen's feel

| | |
|---|---|
| **Tier** | T2 (no vision needed) |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-0.01 (done: `PenSample`) |
| **Owner area** | `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/shape/` (new folder: `Shape.kt`, `ShapeRecognizer.kt`, `ShapePerfecter.kt`, `Geometry.kt`), `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/shape/` |
| **Estimated size** | ~450 lines + ~300 lines of tests |

## Goal
The painter draws a rough circle, triangle, rectangle, line or arc and holds the pen still at the
end. The app replaces it with a precise shape **while keeping the stroke's pressure, tilt and timing**
(owner: "so things still seem a little more organic rather than completely computationally
perfect"). This spec builds the maths only: recognise, then perfect. The UI (the hold timer, the
preview, dragging to resize before lifting) is a later spec.

## Contract (verbatim — exists, do not change)
```kotlin
package cc.joycreator.joybrush.core.input
data class PenSample(
    val x: Float, val y: Float, val timeMs: Double,
    val pressure: Float = 1f, val tilt: Float = Float.NaN, val azimuth: Float = Float.NaN,
    val barrel: Float = Float.NaN, val tool: Tool = Tool.STYLUS, val predicted: Boolean = false,
)
```

## The API to build
```kotlin
package cc.joycreator.joybrush.core.shape

data class Pt(val x: Double, val y: Double)

sealed class Shape {
    data class Line(val a: Pt, val b: Pt) : Shape()
    /** Circle when rx == ry. rotation in radians. */
    data class Ellipse(val center: Pt, val rx: Double, val ry: Double, val rotation: Double) : Shape()
    /** Arc of a circle from startAngle sweeping sweep radians (sign = direction drawn). */
    data class Arc(val center: Pt, val radius: Double, val startAngle: Double, val sweep: Double) : Shape()
    /** Closed polygon with corners in drawing order: triangle (3) or rectangle/quad (4). */
    data class Polygon(val corners: List<Pt>) : Shape()
}

object ShapeRecognizer {
    /**
     * @param points the stroke's (smoothed) samples in document px, in drawing order.
     * @param screenPerDoc current zoom; tolerances are in SCREEN px so recognition feels the same at any zoom.
     * @return the recognised shape, or null when the stroke is not clearly a shape (leave it alone).
     */
    fun recognize(points: List<PenSample>, screenPerDoc: Float = 1f): Shape?
}

object ShapePerfecter {
    /**
     * Moves every sample onto [shape], keeping its pressure, tilt, azimuth, barrel, tool and time.
     * Sample i goes to the point at the same FRACTION OF ARC LENGTH along the shape as it had along
     * the original stroke. Returns exactly points.size samples.
     */
    fun perfect(points: List<PenSample>, shape: Shape): List<PenSample>
}
```

## Decisions already made (in this order inside `recognize`)
Let `P` = points converted to screen px (× screenPerDoc). `L` = path length of P. `D` = diagonal of
P's bounding box.
1. **Too small:** fewer than 5 points, or `L < 12` screen px → `null`.
2. **Closed?** closed if `dist(first, last) < max(20.0, 0.12 * L)`.
3. **Simplify** P with Ramer–Douglas–Peucker, tolerance `eps = max(3.0, 0.035 * D)`. Then remove
   any interior vertex whose turning angle is under 25° (merge nearly-straight runs), repeating until
   none is removed. For a closed stroke, drop the final vertex if it lies within `max(20, 0.12L)` of
   the first (it is the closure).
4. **Line** (open only): simplified to 2 vertices AND max distance of any P point from the chord
   `< 0.04 * chordLength` → `Line(first, last)` (original first and last points, not fitted).
5. **Polygon** (closed only): simplified vertex count is 3 → triangle; 4 → quad.
   - Each polygon side must be at least `0.15 * L / n` long, else not a polygon (fall through).
   - Corners = the simplified vertices.
   - **Rectangle snap:** for a quad whose four interior angles are each within 90° ± 18°, replace it
     with the best-fit rectangle: rotation θ = mean edge direction folded into [0, 90°) (average the
     vectors (cos 4φ, sin 4φ) of the 4 edge angles φ, then θ = atan2(...)/4); centre = mean of the
     corners; width/height = mean of opposite side lengths measured along θ and θ+90°. Output corners
     starting from the rectangle corner nearest the stroke's first point, in drawing direction.
6. **Ellipse** (closed only, and not a polygon): fit by PCA of P — centre = mean point; axes =
   eigenvectors of the 2×2 covariance matrix; `r = sqrt(2 * eigenvalue)` for each axis (exact for
   evenly spaced points on an ellipse). Residual = mean over points of |ρ − 1| where ρ is the point's
   normalised elliptical radius. Accept if residual `< 0.10`. If `min(rx,ry)/max(rx,ry) > 0.88`,
   make it a circle: rx = ry = their mean, rotation 0.
7. **Arc** (open only, not a line): least-squares algebraic circle fit (Kåsa). Accept if mean
   |dist(p, centre) − R| `< 0.05 * R` AND `|sweep| < 330°` AND `R < 4 * D`. `startAngle` = angle of
   the first point; `sweep` = signed angle travelled to the last point, unwrapped along the path.
8. Otherwise → `null`.
Convert results back to document px (÷ screenPerDoc).

**Perfecting:** compute cumulative arc-length fractions `f_i` of the ORIGINAL points (0 … 1).
Parametrise the shape by arc length (Line, Arc: from its start; Polygon: from corner 0 in order and
back to corner 0; Ellipse: sample 720 points around it, starting at the angle of the first original
point and going in the direction the stroke was drawn — the sign of the stroke's shoelace area).
Sample i → position at `f_i × perimeter`. Copy every other field of sample i unchanged.

## Steps
1. Tests first. 2. `Geometry.kt`: RDP, turning angle, PCA 2×2 eigen, Kåsa fit, polygon/ellipse
arc-length parametrisation. 3. `ShapeRecognizer`. 4. `ShapePerfecter`.

## Tests (`ShapeRecognizerTest.kt`, `ShapePerfecterTest.kt`)
Generate synthetic strokes with a seeded `kotlin.random.Random` (seed 1…10), 240 Hz timing, points
every ~1.5 px, with jitter ±1.5 px and a slow wobble of amplitude 2 px, and pressure varying
0.3→0.9→0.4 along the stroke:
1. A 300 px horizontal line → `Line`, endpoints within 2 px of the drawn ends.
2. A circle r = 100, 360° + 10% overlap → `Ellipse` with rx == ry within 5 px of 100.
3. An ellipse 160 × 80 rotated 30° → `Ellipse`, rotation within 5° of 30° (mod 180°), radii within 8 px.
4. A 200 × 120 rectangle rotated 15°, drawn corner to corner and closed → 4-corner `Polygon` that is a
   true rectangle (all angles 90° ± 0.5°), corners within 6 px of the true ones.
5. A triangle (0,0) (200,0) (100,170) → 3-corner `Polygon`, corners within 8 px.
6. A 120° arc of radius 150 → `Arc`, radius within 6 px, |sweep| within 8° of 120°.
7. A scribble (random walk, 200 points) → `null`.
8. A 6 px dot → `null`.
9. Zoom invariance: the circle from test 2 scaled by 0.25 with `screenPerDoc = 4` → same answer.
10. Perfecting keeps feel: perfect the circle → same count; every output pressure/tilt/time equals
    the input's; every output point lies on the circle within 0.5 px.
11. Perfecting a line keeps drawing direction (first output point is at the start).

**Command:** `./gradlew -p joybrush :core:jvmTest`. Passing = `BUILD SUCCESSFUL` and both suites
with 0 failures in `joybrush/core/build/test-results/jvmTest/`.

## Do not
- Do not touch `input/`, `stroke/` or any other existing file.
- Do not add dependencies; pure Kotlin common code only (`kotlin.math`).
- Do not "improve" the thresholds; if a test cannot pass with them, write it in *Questions* and stop.
- Do not fit shapes in document px — tolerances are screen px.

## Definition of done
- [ ] tests pass (paste the output)
- [ ] only owner-area files changed (paste `git status --short`)
- [ ] committed as `JB-2.10: shape recognizer`; pushed
- [ ] ROADMAP.md row → 🟧 Built

## Questions

**Built by the Lead (2026-09-29).** Four dispatches failed, and the likely reason is the spec, not the
builders: Decision 6's plain PCA is exact only for points spread evenly in the ellipse's own angle,
but a drawn stroke is spread evenly in arc length. On test 3 (rx 160, ry 80) plain PCA gives
rx ≈ 147, so the test cannot pass (checked by mutation). Three changes, all marked `Lead:` in code:
1. **Ellipse fit:** cut a closed stroke after exactly one lap (the overlap is drawn twice and pulls
   the centre), then re-weight each point by the span of ellipse angle it covers and refit (3 rounds).
   Radii now land within ~1 px.
2. **Closure:** after dropping the final vertex, also drop trailing vertices within 2·eps of the
   start (an overshoot leaves a second copy of the start corner).
3. **Merging on closed strokes is cyclic**, vertex 0 included, so a rectangle begun mid-side still
   has 4 corners (new test `aRectangleBegunMidSideStillHasFourCorners`).
Test 1's "within 2 px of the drawn ends" now reads as specified: the ends ARE the drawn samples
(asserted equal); they are ≤ 4.5 px from the ideal ends because of the roughening. All tests run
over seeds 1…10.

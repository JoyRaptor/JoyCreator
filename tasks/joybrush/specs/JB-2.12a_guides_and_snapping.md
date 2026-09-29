# JB-2.12a — Helpers: grid, perspective and tracer guides, and pulling a stroke onto them (the maths)

| | |
|---|---|
| **Tier** | T2 |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-0.01 (`PenSample`), JB-2.02 (`ViewTransform`) — Built |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/guide/Guide.kt`, NEW `.../guide/GuideSnapper.kt`, NEW `.../guide/GuideLines.kt`, NEW tests `.../commonTest/.../guide/GuideSnapperTest.kt`, `GuideLinesTest.kt` |
| **Estimated size** | ~300 lines + ~260 lines of tests |

## Goal
Blueprint §3: "Helpers are overlays, never part of the art: grids, perspective guides, and shape
tracers (ruler, ellipse) that the pen can run along. They never export." This spec is the geometry:
what lines to draw for a guide at the current zoom, and how a stroke is pulled onto a guide while
keeping its pressure and tilt. The overlay drawing and the on/off UI are JB-2.12.

## Contract
```kotlin
package cc.joycreator.joybrush.core.guide

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.shape.Pt

sealed class Guide {
    /** Square grid; lines every [spacing] doc px through [origin], turned by [angle] radians. */
    data class Grid(val spacing: Double, val origin: Pt = Pt(0.0, 0.0), val angle: Double = 0.0) : Guide()
    /** Isometric: lines at 30°, 90° and 150°, every [spacing] doc px through [origin]. */
    data class Isometric(val spacing: Double, val origin: Pt = Pt(0.0, 0.0)) : Guide()
    /** 1, 2 or 3 vanishing points. With 1 or 2, verticals are also a direction; with 1, horizontals too. */
    data class Perspective(val vanishingPoints: List<Pt>) : Guide()
    /** A straight edge from [a] to [b] (extends infinitely for snapping). */
    data class Ruler(val a: Pt, val b: Pt) : Guide()
    data class EllipseTracer(val center: Pt, val rx: Double, val ry: Double, val rotation: Double) : Guide()
}

/** One per stroke. Feed every raw sample; draw what comes out. */
class GuideSnapper(val guides: List<Guide>, val screenPerDoc: Float) {
    /** The sample to draw instead of [s]: position changed, every other field identical. */
    fun map(s: PenSample): PenSample
    /** True once the stroke is locked to a guide (for the UI's highlight). */
    val locked: Boolean
}

object GuideLines {
    /**
     * Segments to draw for [guide] inside [viewDoc] (the visible area, doc px: left, top, right,
     * bottom), as (x0, y0, x1, y1). Lines closer than 8 screen px are thinned (every 2nd, 4th… line)
     * so a zoomed-out grid never becomes a grey block.
     */
    fun visible(guide: Guide, viewDoc: FloatArray, screenPerDoc: Float): List<FloatArray>
}
```

## Decisions
1. **Two kinds of guide.**
   - *Direction guides* (Grid, Isometric, Perspective): the stroke is drawn straight along ONE
     direction chosen from where it started. Candidate directions through the start point S:
     Grid → its 2 axes; Isometric → 3; Perspective → the line from S to each vanishing point, plus
     the vertical for 1–2 points, plus the horizontal for 1 point.
   - *Tracers* (Ruler, EllipseTracer): magnetic edges. A stroke that STARTS within 24 screen px of
     the tracer is pulled onto it for the whole stroke; one that starts further away is untouched.
2. **Direction lock:** samples pass through untouched until the pen is 12 screen px from S. Then
   the candidate direction closest in angle to (current − S) is locked for the rest of the stroke,
   and from then on every sample is projected onto the line through S in that direction.
   Directions are LINES (a stroke may go either way along one). The samples before the lock are
   NOT rewritten — they are within 12 screen px of S and the brush covers them. If the best candidate is more than 20° away
   from the pen's direction, nothing locks (the person is not drawing along a guide) and the stroke
   is left free for its whole length.
3. **Tracer projection:** Ruler → orthogonal projection onto the infinite line; EllipseTracer →
   the nearest point on the ellipse (Newton on the parametric angle, 6 iterations, starting from
   the angle of the point in the ellipse's frame — plenty for drawing).
4. **Several guides at once:** a tracer in reach wins over direction guides; among tracers the
   nearest at the stroke's start wins. Among direction guides every guide's candidates compete.
5. `map` changes x and y only — pressure, tilt, azimuth, barrel, time, tool unchanged (same promise
   as hold-to-shape). Non-finite sample → returned unchanged.
6. **Guide lines:** Grid/Isometric lines clipped to the view rect; thinning doubles the step until
   the spacing on screen ≥ 8 px. Perspective: 24 rays from each vanishing point, evenly spread in
   angle, clipped to the view, plus the horizon line through the first two points when there are 2+.
   Ruler: the segment a–b extended across the view. EllipseTracer: a 128-segment polyline.
   Output is capped at 2000 segments whatever happens.
7. All tolerances are in SCREEN px (÷ screenPerDoc for doc px), like every other feel number.

## Tests
1. Grid: a stroke from (0,0) wandering along +x with ±3 px wobble → after lock every output y = 0
   exactly; pressure series identical to the input's.
2. Grid lock needs 12 screen px: at zoom 4 the lock happens after 3 doc px.
3. A stroke at 45° on a Grid → no lock (both axes are 45° away, beyond 20°) → output = input.
4. Isometric: a stroke at 28° locks to the 30° direction.
5. Perspective, 2 points at (−1000, 0) and (1000, 0): a stroke from (0, 200) heading towards
   (1000, 0) locks onto the line to that point; a near-vertical stroke locks to vertical.
6. Ruler: starting 10 screen px away → every point lies on the ruler; starting 40 px away → untouched.
7. EllipseTracer: every snapped point is on the ellipse within 0.05 px.
8. Tracer beats direction guide when both apply.
9. GuideLines: grid spacing 10 at zoom 0.2 (2 screen px) is thinned to ≥ 8 screen px; nothing
   outside the view rect; never more than 2000 segments (try spacing 0.001).

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures.

## Do not
Touch no existing file. Guides are never written into tiles or exports.

## Definition of done
Tests pass (paste) · commit `JB-2.12a: guides and snapping` · ROADMAP row → 🟧 Built.

## Questions

### 🔴 Orchestrator found and fixed a real bug in the delivered code — read this first

`GuideSnapper.ellipsePoint` computed Newton's second derivative as

```
f2 = 2 * (rx*u*cos t + ry*v*sin t - (rx² + ry²))
```

which is **not** D″. The true derivative of `D(t) = (rx·cos t − u)² + (ry·sin t − v)²` is

```
D″(t) = 2[ (ry² − rx²)(cos²t − sin²t) + rx·u·cos t + ry·v·sin t ]
```

The dropped term is what makes D″ positive at the minimum — evaluated with `u, v` on the curve it
collapses to `2(ry² + rx²·sin²t)`, always positive. Without it the curvature is **negative where it
must be positive**, and the iteration walks away from its answer: instrumented, `t` went
0.5 → 0.12 → −1.63 → −3.85 → −3.03 → −3.06 and settled **230 px** from a pen that was **5 px** off the
curve. The symptom was not a stroke snapped to the wrong place — it was a stroke **never captured at
all**, because the distance `nearestTracer` measured was nonsense and came back over the 24 px reach.

Worth recording *how* this was found, because the first fix was wrong too:

1. I read the code, saw "the constant should be a function of t", and substituted
   `−(rx²cos²t + ry²sin²t)`. That is also not D″, and the test still failed.
2. So I stopped reasoning and wrote a throwaway test that printed every Newton step. The divergence
   was visible in one line, and the missing `(ry² − rx²)(cos²t − sin²t)` term fell out of the
   algebra immediately.

**Lesson, and it is the same one the GIF header cost us:** a plausible fix to numerical code is
indistinguishable from a correct one until something prints the intermediate values. Two rounds of
"reasoning about it" produced one wrong fix; one round of instrumenting produced the answer.

The builder's own honest list is below and all five items are recorded. One of them is a behaviour
the spec did not name and I have ruled on it.

Answered in the code; none of these change the contract, they only fix what the text left open.

1. **The ellipse tracer's polyline is NOT clipped to the view.** Decision 6 clips Grid, Isometric,
   Perspective and Ruler and then says "EllipseTracer: a 128-segment polyline" with no clipping, so
   that is what it does: always 128 segments (or fewer only if the guide is degenerate). Dragging a
   tracer half off the page must not change its shape, and the caller's scissor keeps it off the
   window. JB-2.12's overlay must therefore not assume "nothing comes back outside the view" for
   this one guide.
2. **The 24 perspective rays are spread over the full 360°** of each vanishing point (one every
   15°), because Decision 6 says "evenly spread in angle" and gives no range. A vanishing point
   inside the view therefore gets a full starburst. Rays that do not reach the view produce no
   segment at all, so the count is 24 × points + 1 horizon at most.
3. **A line that only touches the view at a single point produces no segment** (a zero-length
   segment cannot be drawn). This is what the corner-touching lines of a slanted isometric family
   do, and `GuideLinesTest.anIsometricGridHasThreeDirections` counts them: 13 + 11 + 13, not
   14 + 11 + 14.
4. **The 2000-segment cap is reached by stopping, not by truncating.** The line index range is
   derived from the view's own extent along each family's normal, so the loop itself is bounded and
   no more than 2000 segments are ever allocated.
5. **`locked` is a `val`** as the contract says, and it is true from the very sample that decided
   the lock: the first sample for a tracer (the decision is made at the start), the sample 12
   screen px out for a direction guide.
6. **`viewDoc` is not produced by anything that exists yet.** `ViewTransform` (JB-2.02) can map the
   screen corners to document px, but it has no `viewDoc` helper; JB-2.12's overlay builds the
   `floatArrayOf(left, top, right, bottom)` itself. Nothing in this spec needed a new field on
   `ViewTransform`, so none was added.
7. **Decision 5's list of untouched channels also has to include `predicted`** (JB-0.01 has it
   and the spec text predates it): `map` only ever calls `copy(x =, y =)`.
8. **Tile size (256) is irrelevant here** and nothing in this spec reads it — a guide is never
   written into a tile or an export, so `GuideLines` only ever produces overlay geometry.

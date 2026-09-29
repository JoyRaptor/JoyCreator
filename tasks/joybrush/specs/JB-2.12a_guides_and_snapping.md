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

package cc.joycreator.joybrush.core.guide

import cc.joycreator.joybrush.core.shape.Pt

/**
 * A drawing helper. Blueprint §3: "Helpers are overlays, never part of the art: grids, perspective
 * guides, and shape tracers (ruler, ellipse) that the pen can run along. They never export."
 *
 * A guide is nothing but geometry — no state, no device types, nothing to draw yet. The two
 * questions a guide raises are answered by the other two files in this package: [GuideLines] turns
 * one into the segments an overlay draws, [GuideSnapper] turns a stroke into a stroke that runs
 * along it.
 *
 * Every length is in DOCUMENT px, every angle in RADIANS, like every other geometry in Joy Brush.
 * How big a length is *on screen* is the view's business and arrives as `screenPerDoc`.
 */
sealed class Guide {

    /**
     * A square grid: lines every [spacing] document px through [origin], the whole grid turned by
     * [angle] radians (0 = the document's own axes). It is the union of two line families, so a
     * stroke drawn along it runs either along [angle] or 90° from it.
     */
    data class Grid(val spacing: Double, val origin: Pt = Pt(0.0, 0.0), val angle: Double = 0.0) : Guide()

    /**
     * An isometric grid: the same idea at three directions 30°, 90° and 150° apart, every
     * [spacing] document px through [origin]. Angles are in the document frame, where y grows down
     * like it does on screen.
     */
    data class Isometric(val spacing: Double, val origin: Pt = Pt(0.0, 0.0)) : Guide()

    /**
     * One to three vanishing points. Every point is a direction a stroke can run towards; with one
     * or two points the vertical is a direction too (that is the "two-point / three-point
     * perspective" of a drawing app), and with a single point the horizontal as well. A
     * perspective grid has no spacing and no origin: where its lines fall is the artist's problem,
     * the vanishing points decide the directions.
     */
    data class Perspective(val vanishingPoints: List<Pt>) : Guide()

    /**
     * A straight edge from [a] to [b]. The overlay draws the segment; for snapping it counts as
     * the INFINITE line through a and b, because a stroke that runs off the end of the ruler is
     * still running along the ruler.
     */
    data class Ruler(val a: Pt, val b: Pt) : Guide()

    /**
     * An ellipse the pen can trace. [rotation] is radians and [rx], [ry] are the radii along the
     * ellipse's own axes — the same shape [cc.joycreator.joybrush.core.shape.Shape.Ellipse] is, so
     * one drawn ellipse can be pushed straight back onto its tracer.
     */
    data class EllipseTracer(val center: Pt, val rx: Double, val ry: Double, val rotation: Double) : Guide()
}

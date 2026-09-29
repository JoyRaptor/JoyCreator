package cc.joycreator.joybrush.core.shape

/** A point in document (or, inside the recogniser, screen) px. */
data class Pt(val x: Double, val y: Double)

/** A perfect shape that a rough stroke was recognised as (JB-2.10). All values in document px. */
sealed class Shape {
    data class Line(val a: Pt, val b: Pt) : Shape()

    /** Circle when rx == ry. rotation in radians. */
    data class Ellipse(val center: Pt, val rx: Double, val ry: Double, val rotation: Double) : Shape()

    /** Arc of a circle from startAngle sweeping sweep radians (sign = direction drawn). */
    data class Arc(val center: Pt, val radius: Double, val startAngle: Double, val sweep: Double) : Shape()

    /** Closed polygon with corners in drawing order: triangle (3) or rectangle/quad (4). */
    data class Polygon(val corners: List<Pt>) : Shape()
}

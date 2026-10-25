package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.select.Homography
import cc.joycreator.joybrush.core.shape.Pt
import kotlin.math.min

/** The one canonical span owning a query's nearest centreline parameter. */
data class LineSliceOwner(val parameter: Double, val spanIndex: Int, val span: LineSliceSpan)

/**
 * Binary ownership oracle for a [LineSlicePlan]. It uses the plan's single canonical geometry and
 * interval partition: every finite in-range arithmetic query has exactly one owner, including
 * points outside the stroke's optical halo. Ties in squared distance select the earliest
 * fractional point parameter. Boundaries belong to the following half-open span; the final
 * endpoint belongs to the final span, and a dot's only span owns parameter zero.
 *
 * This is pure geometric bookkeeping. Ownership is binary and one-hot; it is not an alpha mask,
 * does not assert pigment or rendered-pixel equivalence, and does not define runtime slicing,
 * slice UX, branch physics, reshape, or rebrush acceptance. Every piece must use this same
 * canonical geometry, frame, grid, and tie rule for its results to agree.
 */
class LineSliceOwnership(
    plan: LineSlicePlan,
    sourceToDocument: Homography = Homography.IDENTITY,
    limits: Limits = Limits(),
) {
    /** Query work is linear in the source point count and refused above this deterministic cap. */
    data class Limits(val maxSegmentTests: Int = 65_536) {
        init { require(maxSegmentTests in 1..65_536) }
    }

    private val geometry: InkLine = plan.geometry
    /** The same immutable canonical partition consumed by this predicate. */
    val spans: List<LineSliceSpan> = plan.spans
    /** Inverted once at construction, so every document query uses the same canonical map. */
    private val documentToSource: Homography = sourceToDocument.inverse()?.also { inverse ->
        require(inverse.m.all { it.isFinite() }) { "Inverse homography must be finite" }
    } ?: throw IllegalArgumentException("Source-to-document homography must be invertible")
    private val lastParameter = geometry.lastParam
    private val segmentTests = segmentCountOf(geometry.pointCount)
    val spanCount: Int get() = spans.size

    init {
        require(segmentTests <= limits.maxSegmentTests) { "Line slice ownership segment-work budget exceeded" }
        validateGeometryAndSpans()
    }

    /** Return the unique owner for a point already expressed in the canonical source frame. */
    fun ownerAtCanonical(point: Pt): LineSliceOwner {
        require(point.x.isFinite() && point.y.isFinite()) { "Ownership query must be finite" }
        var bestDistance = Double.POSITIVE_INFINITY
        var bestParameter = 0.0
        for (i in 0 until segmentTests) {
            val i0 = min(i, geometry.pointCount - 1)
            val i1 = min(i + 1, geometry.pointCount - 1)
            val ax = geometry.xs[i0]; val ay = geometry.ys[i0]
            val dx = geometry.xs[i1] - ax
            val dy = geometry.ys[i1] - ay
            val qx = point.x - ax
            val qy = point.y - ay
            require(dx.isFinite() && dy.isFinite() && qx.isFinite() && qy.isFinite()) {
                "Ownership query arithmetic is outside the finite range"
            }
            val length2 = dx * dx + dy * dy
            require(length2.isFinite() && (length2 > 0.0 || (dx == 0.0 && dy == 0.0))) {
                "Ownership segment arithmetic is outside the finite range"
            }
            val local = if (length2 == 0.0) 0.0 else {
                val dot = qx * dx + qy * dy
                require(dot.isFinite()) { "Ownership projection arithmetic is outside the finite range" }
                (dot / length2).coerceIn(0.0, 1.0)
            }
            val nearestX = ax + local * dx
            val nearestY = ay + local * dy
            val ex = point.x - nearestX
            val ey = point.y - nearestY
            val distance2 = ex * ex + ey * ey
            require(nearestX.isFinite() && nearestY.isFinite() && distance2.isFinite() &&
                (distance2 > 0.0 || (ex == 0.0 && ey == 0.0))) {
                "Ownership distance arithmetic is outside the finite range"
            }
            // Strict comparison preserves the first (earliest parameter) segment on exact ties.
            if (distance2 < bestDistance) {
                bestDistance = distance2
                bestParameter = i + local
            }
        }
        val bounded = bestParameter.coerceIn(0.0, lastParameter)
        val index = spanFor(bounded)
        return LineSliceOwner(bounded, index, spans[index])
    }

    /** Map a document-space query through the inverse homography captured once at construction. */
    fun ownerAtDocument(point: Pt): LineSliceOwner {
        require(point.x.isFinite() && point.y.isFinite()) { "Ownership query must be finite" }
        val canonical = documentToSource.apply(point)
        require(canonical.x.isFinite() && canonical.y.isFinite()) {
            "Document query has no finite point in the canonical source frame"
        }
        return ownerAtCanonical(canonical)
    }

    private fun spanFor(parameter: Double): Int {
        var low = 0
        var high = spans.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val span = spans[mid]
            if (parameter < span.from) high = mid - 1
            else if (parameter > span.to || (parameter == span.to && !span.ownsEnd)) low = mid + 1
            else return mid
        }
        error("LineSlicePlan does not cover its canonical parameter domain")
    }

    private fun validateGeometryAndSpans() {
        require(geometry.id.isNotBlank() && geometry.pointCount > 0) { "Ownership geometry is invalid" }
        for (i in 0 until geometry.pointCount) {
            require(geometry.xs[i].isFinite() && geometry.ys[i].isFinite() &&
                geometry.halfWidths[i].isFinite() && geometry.halfWidths[i] >= 0.0) {
                "Ownership geometry must have finite coordinates and widths"
            }
            if (i > 0) {
                val dx = geometry.xs[i] - geometry.xs[i - 1]
                val dy = geometry.ys[i] - geometry.ys[i - 1]
                require(dx.isFinite() && dy.isFinite() && (dx * dx + dy * dy).isFinite()) {
                    "Ownership geometry arithmetic is outside the finite range"
                }
            }
        }
        require(spans.isNotEmpty() && spans.first().from == 0.0 && spans.last().to == lastParameter) {
            "LineSlicePlan must cover the full canonical domain"
        }
        if (lastParameter == 0.0) {
            require(spans.size == 1 && spans[0].from == 0.0 && spans[0].to == 0.0 && spans[0].ownsEnd) {
                "A dot needs exactly one owner span at parameter zero"
            }
            return
        }
        var previous = 0.0
        for ((index, span) in spans.withIndex()) {
            require(span.from.isFinite() && span.to.isFinite() && span.from == previous && span.from < span.to) {
                "LineSlicePlan spans must be ordered, non-empty and gap-free"
            }
            require(span.ownsEnd == (index == spans.lastIndex)) {
                "Only the final line span may own its terminal endpoint"
            }
            previous = span.to
        }
    }
}

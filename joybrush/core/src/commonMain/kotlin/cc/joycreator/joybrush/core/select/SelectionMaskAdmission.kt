package cc.joycreator.joybrush.core.select

import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.shape.Pt
import kotlin.math.ceil
import kotlin.math.floor

/** A bounded, allocation-free check before rasterising a polygon into a [SelectionMask]. */
object SelectionMaskAdmission {
    /** Coverage uses one byte per pixel, so 64 tiles cap a full mask at 4 MiB. */
    const val MAX_COVERAGE_TILES: Int = 64

    data class Plan(
        /** Same floor/ceil pixel rectangle used by [Lasso.rasterise]. */
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        /** Conservative count of tiles the rasteriser may touch for this rectangle. */
        val coverageTileCount: Int,
    )

    sealed interface Result {
        data class Accepted(val plan: Plan) : Result
        data class Refused(val reason: String) : Result
    }

    /**
     * Preflight a polygon using the lasso rasteriser's exact floor/ceil pixel extent. This counts
     * every tile intersected by that extent, including sub-pixel antialiased edge coverage, before
     * the rasteriser allocates any coverage tile.
     */
    fun preflightPolygon(points: List<Pt>): Result {
        if (points.size < 3) return Result.Refused("A selection needs at least three points")

        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (point in points) {
            if (!point.x.isFinite() || !point.y.isFinite()) {
                return Result.Refused("Selection coordinates must be finite")
            }
            minX = minOf(minX, point.x)
            minY = minOf(minY, point.y)
            maxX = maxOf(maxX, point.x)
            maxY = maxOf(maxY, point.y)
        }

        val left = floor(minX)
        val top = floor(minY)
        val right = ceil(maxX)
        val bottom = ceil(maxY)
        val spanX = right - left
        val spanY = bottom - top
        if (spanX <= 0.0 || spanY <= 0.0) {
            return Result.Refused("The selection has no area")
        }
        if (!spanX.isFinite() || !spanY.isFinite() || spanX > MAX_SELECT_SPAN || spanY > MAX_SELECT_SPAN) {
            return Result.Refused("The selection is too large")
        }

        // Match Lasso's addressable-origin limit before converting to integers. Its maximum span
        // then guarantees that the exclusive far edges also fit in Int arithmetic.
        val maxCoord = LASSO_MAX_COORD.toDouble()
        if (left < -maxCoord || left > maxCoord || top < -maxCoord || top > maxCoord) {
            return Result.Refused("The selection is too far from the canvas origin")
        }

        // All conversions happen only after finite, span and origin checks. Long arithmetic keeps
        // the exclusive edge and tile-grid calculations from overflowing Int at negative seams.
        val x0 = left.toLong()
        val y0 = top.toLong()
        val width = spanX.toLong()
        val height = spanY.toLong()
        val rightExclusive = x0 + width
        val bottomExclusive = y0 + height
        if (rightExclusive > Int.MAX_VALUE.toLong() || bottomExclusive > Int.MAX_VALUE.toLong() ||
            x0 < Int.MIN_VALUE.toLong() || y0 < Int.MIN_VALUE.toLong()) {
            return Result.Refused("The selection is too far from the canvas origin")
        }

        val minTileX = floorDiv(x0, Tiles.SIZE.toLong())
        val maxTileX = floorDiv(rightExclusive - 1L, Tiles.SIZE.toLong())
        val minTileY = floorDiv(y0, Tiles.SIZE.toLong())
        val maxTileY = floorDiv(bottomExclusive - 1L, Tiles.SIZE.toLong())
        val columns = maxTileX - minTileX + 1L
        val rows = maxTileY - minTileY + 1L
        val tileCount = columns * rows // spans are capped above, so this is at most 65 × 65.
        if (tileCount > MAX_COVERAGE_TILES) {
            return Result.Refused("The selection covers too many tiles; make a smaller selection")
        }

        return Result.Accepted(
            Plan(
                x = x0.toInt(),
                y = y0.toInt(),
                width = width.toInt(),
                height = height.toInt(),
                coverageTileCount = tileCount.toInt(),
            ),
        )
    }

    private fun floorDiv(value: Long, divisor: Long): Long {
        val quotient = value / divisor
        return if (value % divisor < 0L) quotient - 1L else quotient
    }

    /** Keep in sync with Lasso.rasterise's addressable origin check. */
    private const val LASSO_MAX_COORD = 1_073_741_824L
}

package cc.joycreator.joybrush.core.brush

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** DRAFT UI geometry; no Android dependency or brush-rendering role. Document y points down. */
data class QuadEditorPosition(val x: Float, val y: Float)

data class QuadEditorGeometry(
    val centerX: Float,
    val centerY: Float,
    val halfSide: Float,
    val arrowRadius: Float,
) {
    init {
        require(centerX.isFinite() && centerY.isFinite() && halfSide.isFinite() && halfSide > 0f &&
            arrowRadius.isFinite() && arrowRadius > 0f)
    }

    fun toView(point: QuadPoint) = QuadEditorPosition(centerX + point.x * halfSide, centerY + point.y * halfSide)

    fun toPoint(x: Float, y: Float): QuadPoint {
        require(x.isFinite() && y.isFinite())
        return QuadPoint(((x - centerX) / halfSide).coerceIn(-1f, 1f),
            ((y - centerY) / halfSide).coerceIn(-1f, 1f))
    }

    fun arrow(facingDegrees: Float): QuadEditorPosition {
        val angle = degrees(facingDegrees) * (PI / 180)
        return QuadEditorPosition(centerX + cos(angle).toFloat() * arrowRadius,
            centerY + sin(angle).toFloat() * arrowRadius)
    }

    /** Zero degrees faces +x; positive angles turn toward +y, matching the pen engine. */
    fun facing(x: Float, y: Float, previous: Float, deadRadius: Float = 0f): Float {
        if (!x.isFinite() || !y.isFinite()) return degrees(previous)
        val dx = x.toDouble() - centerX; val dy = y.toDouble() - centerY
        if (hypot(dx, dy) <= deadRadius.coerceAtLeast(0f)) return degrees(previous)
        return degrees((atan2(dy, dx) * (180 / PI)).toFloat())
    }

    /** Nearest wins overlapping 44dp targets; selected wins an exact coincident tie. */
    fun pick(x: Float, y: Float, shape: QuadShapeSpec, facing: Float, reach: Float, selected: Int, includeArrow: Boolean = true): Int {
        if (!x.isFinite() || !y.isFinite() || reach <= 0f || !reach.isFinite()) return -1
        var chosen = -1
        var best = Double.POSITIVE_INFINITY
        for (i in 0..(if (includeArrow) 4 else 3)) {
            val point = if (i == 4) arrow(facing) else toView(shape.points[i])
            val d = hypot(x.toDouble() - point.x, y.toDouble() - point.y)
            if (d <= reach && (d < best || (d == best && i == selected))) { chosen = i; best = d }
        }
        return chosen
    }

    companion object {
        /** Fit a square plus outer arrow ring, keeping every handle's full hit target inside. */
        fun fit(left: Float, top: Float, width: Float, height: Float,
                handleRadius: Float, arrowGap: Float): QuadEditorGeometry? {
            if (listOf(left, top, width, height, handleRadius, arrowGap).any { !it.isFinite() } ||
                width <= 0f || height <= 0f || handleRadius < 0f || arrowGap < 0f) return null
            val radius = min(width, height) * .5f - handleRadius
            val half = (radius - arrowGap) / sqrt(2f)
            if (half <= 0f) return null
            return QuadEditorGeometry(left + width * .5f, top + height * .5f, half, radius)
        }

        /** Stable [-180,180) output; NaN/Infinity use zero, never poison a UI position. */
        fun degrees(value: Float): Float {
            if (!value.isFinite()) return 0f
            var angle = (value.toDouble() + 180) % 360
            if (angle < 0) angle += 360
            return (angle - 180).toFloat()
        }

        /** Copy only one normalized handle; preserve aspect/profile/roundness and point order. */
        fun move(shape: QuadShapeSpec, index: Int, x: Float, y: Float): QuadShapeSpec {
            require(index in 0..3 && shape.points.size == 4 && x.isFinite() && y.isFinite())
            val point = QuadPoint(x.coerceIn(-1f, 1f), y.coerceIn(-1f, 1f))
            return shape.copy(points = shape.points.mapIndexed { i, old -> if (i == index) point else old })
        }
    }
}

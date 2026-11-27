package cc.joycreator.joybrush.android.chrome

import android.graphics.Path
import cc.joycreator.joybrush.core.brush.QuadMath
import cc.joycreator.joybrush.core.brush.QuadShapeSpec
import cc.joycreator.joybrush.core.brush.TipQuads
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * A Unibrush tip shape's outline as a [Path]: the four points with their rounded corners, the shape's own -1..1 square
 * centred on ([centerX], [centerY]) and scaled by [halfSide]. Shared by the shape editor and the pen monitor's A/B shapes
 * (BRUSH-UI §L 4), so both draw exactly what the brush stamps. Clears [into] first; an empty shape leaves it empty.
 */
object QuadSilhouette {

    fun build(into: Path, shape: QuadShapeSpec, centerX: Float, centerY: Float, halfSide: Float) {
        build(into, QuadMath.prepare(TipQuads.interpolate(shape, null, 0f)), centerX, centerY, halfSide)
    }

    fun build(into: Path, prepared: QuadMath.Prepared, centerX: Float, centerY: Float, halfSide: Float) {
        into.reset(); into.fillType = Path.FillType.EVEN_ODD
        if (prepared.empty) return
        fun px(x: Double) = centerX + (x * halfSide).toFloat()
        fun py(y: Double) = centerY + (y * halfSide).toFloat()
        val corners = prepared.corners
        val first = corners.first()
        into.moveTo(px(first.inX), py(first.inY))
        for (i in corners.indices) {
            val corner = corners[i]
            if (!corner.rounded) into.lineTo(px(corner.outX), py(corner.outY))
            else {
                val start = atan2(corner.unitInY, corner.unitInX)
                var sweep = atan2(corner.unitOutY, corner.unitOutX) - start
                while (sweep > PI) sweep -= 2 * PI
                while (sweep < -PI) sweep += 2 * PI
                if (abs(abs(sweep) - PI) < 1e-10) sweep = corner.arcSign * PI
                // <=45deg cubic arcs give subpixel error and avoid enormous arcTo rectangles
                // at nearly collinear vertices. Controls are calculated from stable endpoints.
                val steps = ceil(abs(sweep) / (PI / 4)).toInt().coerceAtLeast(1)
                for (step in 0 until steps) {
                    val a = start + sweep * step / steps
                    val b = start + sweep * (step + 1) / steps
                    val k = 4.0 / 3 * tan((b - a) / 4) * corner.radius
                    val ax = corner.inX + corner.radius * (cos(a) - corner.unitInX)
                    val ay = corner.inY + corner.radius * (sin(a) - corner.unitInY)
                    val bx = if (step + 1 == steps) corner.outX else corner.inX + corner.radius * (cos(b) - corner.unitInX)
                    val by = if (step + 1 == steps) corner.outY else corner.inY + corner.radius * (sin(b) - corner.unitInY)
                    into.cubicTo(px(ax - sin(a) * k), py(ay + cos(a) * k),
                        px(bx + sin(b) * k), py(by - cos(b) * k), px(bx), py(by))
                }
            }
            val next = corners[(i + 1) % corners.size]
            into.lineTo(px(next.inX), py(next.inY))
        }
        into.close()
    }
}

package cc.joycreator.joybrush.core.brush

import kotlinx.serialization.Serializable

const val VERSION_TIP_QUAD = 14

/** Clockwise editor handles in normalized tip space. Order is meaningful, including crossed tips. */
@Serializable data class QuadShapeSpec(
    val points: List<QuadPoint> = listOf(QuadPoint(-1f, -1f), QuadPoint(1f, -1f), QuadPoint(1f, 1f), QuadPoint(-1f, 1f)),
    val roundness: Float = 1f,
    /** Null inherits the tip's scalar/dynamic thinness. */
    val aspect: Float? = null,
    /** Null inherits tip.profile, including its legacy coverage when absent. */
    val profile: TipProfileSpec? = null,
)

@Serializable data class TipQuadSpec(
    val a: QuadShapeSpec = QuadShapeSpec(),
    val b: QuadShapeSpec? = null,
    /** 0 = A, 1 = B. No B means A regardless of this curve. */
    val morph: Param = Param(0f),
)

/** Per-dab interpolation only: no random draws or mutable stroke state. */
object TipQuads {
    fun interpolate(a: QuadShapeSpec, b: QuadShapeSpec?, amount: Float): QuadDab {
        val t = if (b == null || !amount.isFinite()) 0f else amount.coerceIn(0f, 1f)
        val target = b ?: a
        fun x(i: Int) = lerp(a.points[i].x, target.points[i].x, t)
        fun y(i: Int) = lerp(a.points[i].y, target.points[i].y, t)
        return QuadDab(x(0), y(0), x(1), y(1), x(2), y(2), x(3), y(3), lerp(a.roundness, target.roundness, t))
    }

    /** Null for two inherited endpoints preserves the old live-aspect sentinel. */
    fun aspect(a: QuadShapeSpec, b: QuadShapeSpec?, amount: Float, inherited: Float): Float? {
        if (a.aspect == null && (b == null || b.aspect == null)) return null
        val t = if (b == null || !amount.isFinite()) 0f else amount.coerceIn(0f, 1f)
        return lerp(a.aspect ?: inherited, (b ?: a).aspect ?: inherited, t).coerceIn(-1f, 1f)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = when (t) {
        0f -> a
        1f -> b
        else -> a + (b - a) * t
    }
}

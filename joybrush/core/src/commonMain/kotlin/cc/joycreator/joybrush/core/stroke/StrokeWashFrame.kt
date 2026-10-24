package cc.joycreator.joybrush.core.stroke

import cc.joycreator.joybrush.core.select.Homography
import cc.joycreator.joybrush.core.shape.Pt

/**
 * The document-space placement of a watercolour wash texture. This matrix maps wash-local
 * document pixels to document pixels. Keeping the nine coefficients as immutable primitives
 * avoids exposing a mutable [DoubleArray] through a value stored in a [StrokeRecord].
 *
 * Homographies are defined up to a non-zero scalar. Identity is the legacy frame: old records
 * sampled the wash in document coordinates, so a null [StrokeRecord.washFrame] means this value.
 */
data class StrokeWashFrame(
    val m00: Double,
    val m01: Double,
    val m02: Double,
    val m10: Double,
    val m11: Double,
    val m12: Double,
    val m20: Double,
    val m21: Double,
    val m22: Double,
) {
    init {
        val h = toHomography()
        require(h.m.all { it.isFinite() }) { "wash frame coefficients must be finite" }
        val inverse = h.inverse()
        require(inverse != null && inverse.m.all { it.isFinite() }) { "wash frame must be invertible" }
    }

    /** The identity frame is how records written before the wash-frame field behave. */
    fun map(local: Pt): Pt = toHomography().apply(local)

    /** Map a document point back to its wash-local coordinate, or return NaNs at infinity. */
    fun localOf(document: Pt): Pt = requireNotNull(toHomography().inverse()).apply(document)

    /** Carry this frame through [h], preserving the wash-local coordinate system. */
    fun transformed(h: Homography): StrokeWashFrame? = from(toHomography().then(h))

    /** Return a fresh matrix: callers cannot mutate the value held by this frame. */
    fun toHomography(): Homography = Homography(doubleArrayOf(
        m00, m01, m02,
        m10, m11, m12,
        m20, m21, m22,
    ))

    companion object {
        val IDENTITY = StrokeWashFrame(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        /** Make a value only when the existing Homography inverse contract accepts it. */
        fun from(h: Homography): StrokeWashFrame? {
            val m = h.m
            if (m.any { !it.isFinite() }) return null
            val inverse = h.inverse() ?: return null
            if (inverse.m.any { !it.isFinite() }) return null
            return StrokeWashFrame(m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8])
        }
    }
}

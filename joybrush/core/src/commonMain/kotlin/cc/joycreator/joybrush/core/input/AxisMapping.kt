package cc.joycreator.joybrush.core.input

import kotlin.math.PI

/**
 * Converting platform pen axes into [PenSample] conventions. Pure maths so it is unit-tested here and
 * the Android shell stays a thin caller (JB-0.05).
 */
object AxisMapping {

    /**
     * Android's AXIS_ORIENTATION for a stylus: 0 = pointing up the screen, +PI/2 = right,
     * -PI/2 = left, ±PI = down. [PenSample.azimuth] uses 0 = +x (right) with y growing DOWN the
     * screen, so up is -PI/2. Then the canvas rotation is removed so the angle is in DOCUMENT space.
     *
     * [calibrationOffset] and [flip] come from the per-device calibration table (some Samsung models
     * are reported to deliver the lean reversed); defaults are the documented convention.
     */
    fun androidOrientationToAzimuth(
        orientation: Float,
        canvasRotation: Float = 0f,
        calibrationOffset: Float = 0f,
        flip: Boolean = false,
    ): Float {
        if (orientation.isNaN()) return Float.NaN
        var a = orientation.toDouble() - PI / 2 - canvasRotation + calibrationOffset
        if (flip) a += PI
        return Angles.wrap(a).toFloat()
    }
}

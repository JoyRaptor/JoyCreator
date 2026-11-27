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

    /**
     * The S Pen reports its lean REVERSED: AXIS_ORIENTATION points away from the barrel, not towards it. Owner, on both
     * the Note 9 and the Note 20 (2026-10-08 "tilt footprint pointing opposite barrel in all cardinal directions";
     * 2026-10-11 "inverted the x and y axis for tilt"). Every tilt brush read the lean 180° off until this was applied.
     * So every pen is flipped; a finger or a mouse has no orientation and is never asked.
     */
    const val STYLUS_FLIP = true

    /**
     * [PenSample.azimuth] for a pen (stylus or its eraser end): the direction the BARREL leans, document space, 0 = +x,
     * y down. This is the physical contract every tilt brush is written against: the side of a pencil lead lies down on
     * the barrel's side of the point.
     */
    fun stylusAzimuth(orientation: Float, canvasRotation: Float = 0f): Float =
        androidOrientationToAzimuth(orientation, canvasRotation, flip = STYLUS_FLIP)

    /**
     * A pen's raw AXIS_ORIENTATION turned so it means "the barrel leans this way", still in Android's own convention
     * (0 = up the screen, +PI/2 = right), for UI that draws the lean on the screen (the brush settings' pen dot).
     */
    fun stylusBarrelOrientation(orientation: Float): Float =
        if (orientation.isNaN()) Float.NaN
        else if (STYLUS_FLIP) Angles.wrap(orientation.toDouble() + PI).toFloat() else orientation
}

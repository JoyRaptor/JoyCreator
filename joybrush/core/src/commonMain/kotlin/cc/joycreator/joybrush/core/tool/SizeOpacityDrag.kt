package cc.joycreator.joybrush.core.tool

import cc.joycreator.joybrush.core.brush.BrushValidate

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * One drag gesture, from finger-down to finger-up. Sizes are DOCUMENT px (LEAD_RULINGS R10); the
 * preview circle is drawn in screen px. Construct at finger-down; call [move] with the TOTAL offset
 * since finger-down (screen px, +x right, +y DOWN).
 *
 * This is the maths behind "values change by dragging on the control" (blueprint §3.5) — drag on the
 * brush swatch to change size, drag up/down for opacity. The three-finger swipe (JB-3.08a) calls the
 * SAME class, so the two gestures cannot drift apart in feel. There is no view, no clock and no
 * platform type in here: the shell hands it numbers and reads them back, and the whole thing is
 * unit-tested on a computer.
 *
 * The two axes fight for the gesture until the finger has travelled [LOCK_TRAVEL_DP] × density, and
 * then the winner keeps it for good (Decision 1). Letting both follow at once is exactly what makes
 * a two-axis control fiddly — the values run away from the finger and there is no way back.
 *
 * Every number is guarded the way the engine guards everything (LEAD_RULINGS R1): a non-finite
 * value from a gesture is ignored rather than allowed to reach the brush.
 *
 * Lead: Decision 5 names `startSize` and `screenPerDoc` only, and two more numbers reach this class
 * from the same gesture stream, so the same guard is applied to both: `density` (a zero density
 * would divide by zero in both mappings and lock on the first pixel) and `startOpacity` (a NaN would
 * print "NaN" on the swatch). Both are marked below and raised as a question for the Lead.
 */
class SizeOpacityDrag(
    val startSize: Float,
    val startOpacity: Float,
    val screenPerDoc: Float,
    val density: Float = 1f,
) {

    /** Which way the drag went. */
    enum class Axis { NONE, SIZE, OPACITY }

    // Decision 5 at finger-down. The four constructor properties above are kept exactly as they were
    // passed (they are the public record of the call); these are the copies the maths actually uses,
    // so one broken number is fixed once here instead of poisoning every later frame.
    private val baseSize: Float = if (startSize.isFinite() && startSize > 0f) startSize else 1f

    // Lead: startOpacity is guarded like startSize is, and is also pulled into the range the control
    // can show, so `size` and `opacity` are always numbers the swatch can display.
    private val baseOpacity: Float = if (startOpacity.isFinite()) {
        startOpacity.coerceIn(MIN_OPACITY, 1f)
    } else {
        1f
    }

    private val ratio: Float = if (screenPerDoc.isFinite() && screenPerDoc > 0f) screenPerDoc else 1f

    // Lead: the density guard, for the reason in the class KDoc.
    private val dp: Float = if (density.isFinite() && density > 0f) density else 1f

    private var lockedAxis = Axis.NONE
    private var lockDx = 0f
    private var lockDy = 0f
    private var currentSize = baseSize
    private var currentOpacity = baseOpacity

    /** NONE until the lock decision, then fixed for the rest of the gesture. */
    val axis: Axis get() = lockedAxis

    /** Current size in DOCUMENT px — this is the number that goes into `size.base`. */
    val size: Float get() = currentSize

    /** Current opacity, [MIN_OPACITY]..1. */
    val opacity: Float get() = currentOpacity

    /**
     * Radius of the circle drawn on the swatch, in SCREEN px: half the size times the zoom, so the
     * circle on screen is the brush's true width on screen while you drag it (LEAD_RULINGS R10).
     * The value written back to the brush is in document px, so a stroke laid down now looks the
     * same when the page is zoomed back out.
     */
    val previewRadiusScreenPx: Float get() = currentSize * 0.5f * ratio

    /**
     * Feed the TOTAL offset since finger-down, in screen px. Call it with the running total, not with
     * the last step: the lock measures distance from where the finger went down, so a slow drag of
     * two pixels a frame still locks after 12 px of travel.
     *
     * A non-finite offset is ignored and the previous values stand. Once [axis] is decided it never
     * changes, and the axis that lost never moves again (Decision 4).
     */
    fun move(dxScreen: Float, dyScreen: Float) {
        if (!dxScreen.isFinite()) return
        if (!dyScreen.isFinite()) return

        if (lockedAxis == Axis.NONE) {
            val travel = travelOf(dxScreen, dyScreen)
            if (travel < (LOCK_TRAVEL_DP * dp).toDouble()) return
            lockDx = dxScreen
            lockDy = dyScreen
            // Ties go to SIZE, so a drag that is evenly split between the two does not sit dead.
            if (abs(dxScreen) >= abs(dyScreen)) lockedAxis = Axis.SIZE else lockedAxis = Axis.OPACITY
        }

        if (lockedAxis == Axis.SIZE) {
            currentSize = sizeAt(dxScreen)
        } else if (lockedAxis == Axis.OPACITY) {
            currentOpacity = opacityAt(dyScreen)
        }
    }

    /** How far the finger has got from where it went down. */
    private fun travelOf(dxScreen: Float, dyScreen: Float): Double {
        val x = dxScreen.toDouble()
        val y = dyScreen.toDouble()
        return sqrt(x * x + y * y)
    }

    /**
     * Decision 2: exponential, so a 2 px brush gets the same fine control as a 200 px one — every
     * [SIZE_PER_DOUBLING_DP] × density of travel to the right doubles it, to the left halves it.
     * Measured from [lockDx], not from zero, so the value does not jump at the moment of the lock.
     * `span` is never 0 because [dp] is guarded, so the division is always safe.
     */
    private fun sizeAt(dxScreen: Float): Float {
        val span = (SIZE_PER_DOUBLING_DP * dp).toDouble()
        val exponent = (dxScreen - lockDx).toDouble() / span
        return clampSize(baseSize.toDouble() * 2.0.pow(exponent))
    }

    /**
     * Decision 3: linear, and UP is more. Measured from [lockDy] for the same reason as the size.
     * The floor is [MIN_OPACITY], never 0 — an invisible brush looks broken rather than switched off.
     */
    private fun opacityAt(dyScreen: Float): Float {
        val span = (OPACITY_SPAN_DP * dp).toDouble()
        val raw = baseOpacity.toDouble() - (dyScreen - lockDy).toDouble() / span
        return clampOpacity(raw)
    }

    private fun clampSize(v: Double): Float {
        if (v.isNaN()) return MIN_SIZE
        if (v < MIN_SIZE.toDouble()) return MIN_SIZE
        if (v > MAX_SIZE.toDouble()) return MAX_SIZE
        return v.toFloat()
    }

    private fun clampOpacity(v: Double): Float {
        if (v.isNaN()) return MIN_OPACITY
        if (v < MIN_OPACITY.toDouble()) return MIN_OPACITY
        if (v > 1.0) return 1f
        return v.toFloat()
    }

    companion object {
        /** Travel before the axis is decided, in dp: 12 dp of finger is a deliberate drag. */
        const val LOCK_TRAVEL_DP = 12f

        /** Travel that doubles the size, in dp. */
        const val SIZE_PER_DOUBLING_DP = 160f

        /** Travel that takes the opacity the whole way from 0 to 1, in dp. */
        const val OPACITY_SPAN_DP = 300f

        /** Smallest size the drag will set, document px — under this a dab is a speck. */
        const val MIN_SIZE = 0.5f

        /** Biggest size the drag will set, document px — BrushValidate's own limit, not a copy of it. */
        const val MAX_SIZE = BrushValidate.MAX_SIZE_PX

        /** Opacity never reaches 0: an invisible brush looks broken. */
        const val MIN_OPACITY = 0.01f
    }
}

package cc.joycreator.joybrush.android.chrome

import cc.joycreator.joybrush.core.guide.SymmetryMath
import cc.joycreator.joybrush.core.view.ViewTransform
import kotlin.math.PI

/**
 * The pure part of the symmetry's overlay and widget: the rules a phone is not needed to prove.
 *
 * [GuideOverlayView] and [SymmetryWidgetView] are Android views, so the unit tests cannot build
 * them; everything here is plain Kotlin (and the core, which is plain Kotlin too), so it can.
 */

/**
 * Who may take hold of the symmetry line.
 *
 * The app's rule is "a pen always draws, a finger navigates". The line is drawn where people
 * paint, so a stylus (or the stylus's eraser end) must fall straight through to the canvas; only
 * a finger may grab the body or an end.
 */
internal object SymmetryGrip {

    // android.view.MotionEvent's public tool types. They are copied as numbers, not imported,
    // so this file stays free of the Android framework and the unit tests can load it.
    const val TOOL_FINGER = 1
    const val TOOL_STYLUS = 2
    const val TOOL_ERASER = 4

    /** True only for a finger: every other tool paints. */
    fun mayGrab(toolType: Int): Boolean = toolType == TOOL_FINGER
}

/**
 * What the safe zone's cached path was built for.
 *
 * The ring is a screen-space circle, so it depends on the guide's centre and radius, the view's
 * size, AND the whole transform: zoom, pan and rotation. Every one of those is in the key, or a
 * panned or turned page would leave the ring behind at its old screen position.
 */
internal data class SafeZoneKey(
    val cx: Double,
    val cy: Double,
    val radius: Float,
    val width: Int,
    val height: Int,
    val zoom: Float,
    val panX: Float,
    val panY: Float,
    val rotation: Float,
) {
    companion object {
        fun of(transform: ViewTransform, cx: Double, cy: Double, radius: Float, width: Int, height: Int): SafeZoneKey =
            SafeZoneKey(cx, cy, radius, width, height, transform.zoom, transform.panX, transform.panY, transform.rotation)
    }
}

/**
 * The soft quarter-turn snap of the line.
 *
 * The DECISION is the core's [SymmetryMath.snapAngle]: the 45-degree step and the 3-degree
 * window live there and nowhere else. What is kept local is the direction: the core folds a line
 * onto 0..180 degrees (a line has no direction), but the overlay's anchored end must stay on the
 * side the finger dragged it to, or the line would jump to the other side of that end.
 */
internal object SymmetrySnap {

    /** The angle [angle] is pulled onto, or null when it is not near a quarter turn. */
    fun target(angle: Double): Double? {
        val folded = SymmetryMath.snapAngle(angle) ?: return null
        var d = angle - folded
        while (d > PI) d -= 2.0 * PI
        while (d <= -PI) d += 2.0 * PI
        return when {
            d > PI / 2.0 -> folded + PI
            d < -PI / 2.0 -> folded - PI
            else -> folded
        }
    }
}

/**
 * What the count pill shows.
 *
 * The pill's number is what the guide asks for. When the brush family's cap bites, the requested
 * number is kept (dimmed by the view) and the EFFECTIVE copy count the core's lane set reports is
 * shown beside it; when no cap bites, [effectiveCopies] is null and only the requested number is
 * shown, so the mark can never linger after the brush changes.
 */
internal data class SymmetryCountDisplay(val requested: Int, val effectiveCopies: Int?) {

    val capped: Boolean get() = effectiveCopies != null

    val requestedText: String get() = "×$requested"

    val effectiveText: String get() = " → $effectiveCopies"

    val text: String get() = if (effectiveCopies == null) requestedText else requestedText + effectiveText

    val spoken: String
        get() = if (effectiveCopies == null) {
            "Copies: $requested. Drag sideways for more or fewer, or tap for one more. Copies always merge in this build."
        } else {
            "Copies: $requested requested; this brush keeps $effectiveCopies. " +
                "Drag sideways for more or fewer, or tap for one more. Copies always merge in this build."
        }
}

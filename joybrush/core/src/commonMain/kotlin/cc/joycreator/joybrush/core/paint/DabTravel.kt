package cc.joycreator.joybrush.core.paint

import kotlin.math.hypot

/** Per-stroke movement of unscattered document points, independent of batch boundaries and pen lean. */
class DabTravel {
    var x: Float = 0f; private set
    var y: Float = 0f; private set
    private var previousX = Float.NaN
    private var previousY = Float.NaN
    fun update(docX: Float, docY: Float) {
        val dx = docX - previousX; val dy = docY - previousY
        val length = hypot(dx, dy)
        x = if (length.isFinite() && length > 0f) dx / length else 0f
        y = if (length.isFinite() && length > 0f) dy / length else 0f
        previousX = docX; previousY = docY
    }
    fun reset() { previousX = Float.NaN; previousY = Float.NaN; x = 0f; y = 0f }
}
package cc.joycreator.joybrush.androidkit.input

import android.view.MotionEvent
import cc.joycreator.joybrush.core.input.AxisMapping
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.Tool

/**
 * MotionEvent → [PenSample] (blueprint §3.4). Uses EVERY sample Android batched into the event
 * (historical ones first), not just the latest — at 240+ Hz most of the pen's path lives in the
 * history. Tilt comes from AXIS_TILT; the lean direction from AXIS_ORIENTATION via [AxisMapping].
 * Fingers and mice report pressure 1 and no tilt.
 */
object MotionEventSamples {

    fun tool(ev: MotionEvent, pointerIndex: Int): Tool = when (ev.getToolType(pointerIndex)) {
        MotionEvent.TOOL_TYPE_STYLUS -> Tool.STYLUS
        MotionEvent.TOOL_TYPE_ERASER -> Tool.ERASER
        MotionEvent.TOOL_TYPE_MOUSE -> Tool.MOUSE
        else -> Tool.FINGER
    }

    /**
     * @param toDoc view px → document px.
     * @param canvasRotation how far the canvas is rotated on screen (radians), removed from the lean.
     */
    fun from(
        ev: MotionEvent,
        pointerIndex: Int,
        toDoc: (Float, Float) -> Pair<Float, Float>,
        canvasRotation: Float = 0f,
    ): List<PenSample> {
        val tool = tool(ev, pointerIndex)
        val pen = tool == Tool.STYLUS || tool == Tool.ERASER
        val n = ev.historySize
        val out = ArrayList<PenSample>(n + 1)
        for (h in 0 until n) {
            val (x, y) = toDoc(ev.getHistoricalX(pointerIndex, h), ev.getHistoricalY(pointerIndex, h))
            out.add(
                PenSample(
                    x = x, y = y,
                    timeMs = ev.getHistoricalEventTime(h).toDouble(),
                    pressure = if (pen) ev.getHistoricalPressure(pointerIndex, h).coerceIn(0f, 1f) else 1f,
                    tilt = if (pen) ev.getHistoricalAxisValue(MotionEvent.AXIS_TILT, pointerIndex, h) else Float.NaN,
                    azimuth = if (pen) AxisMapping.androidOrientationToAzimuth(
                        ev.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex, h), canvasRotation,
                    ) else Float.NaN,
                    tool = tool,
                )
            )
        }
        val (x, y) = toDoc(ev.getX(pointerIndex), ev.getY(pointerIndex))
        out.add(
            PenSample(
                x = x, y = y,
                timeMs = ev.eventTime.toDouble(),
                pressure = if (pen) ev.getPressure(pointerIndex).coerceIn(0f, 1f) else 1f,
                tilt = if (pen) ev.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex) else Float.NaN,
                azimuth = if (pen) AxisMapping.androidOrientationToAzimuth(
                    ev.getAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex), canvasRotation,
                ) else Float.NaN,
                tool = tool,
            )
        )
        return out
    }
}

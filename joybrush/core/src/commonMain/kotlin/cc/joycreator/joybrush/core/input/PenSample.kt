package cc.joycreator.joybrush.core.input

/**
 * One reading from a pen or finger — the unit everything in Joy Brush is built from.
 *
 * This is a CONTRACT (blueprint §3.4). The platform shells (Android today, iOS later) convert their
 * native touch events into these, already calibrated, and nothing downstream ever sees a platform
 * type. Stroke recordings store these exactly, so a stroke can be re-smoothed, re-rendered at any
 * zoom, replayed for a timelapse, or re-drawn with a different brush.
 *
 * Units: positions are DOCUMENT pixels; angles are radians; time is milliseconds (a Double, because
 * some platforms deliver sub-millisecond timestamps and some only whole milliseconds).
 *
 * Channels a device cannot measure are [Float.NaN], never a made-up value — so a brush can tell "the
 * pen is upright" (tilt = 0) from "this pen has no tilt sensor" (tilt = NaN) and fall back properly.
 * The NaN convention is for CHANNELS ONLY. Where the sample is ([x], [y]) and when it was ([timeMs])
 * are not channels: there is no reading of "the pen is nowhere", so a sample whose position or time
 * is not a finite number is not a sample with a missing channel, it is a broken one. [isPlaceable]
 * is that test, and it is deliberately NOT the same test as [hasTilt] and friends.
 *
 * @property pressure 0..1 after the device's pressure calibration. Fingers and pressure-less pens
 *   report 1.
 * @property tilt angle between the pen and the screen's normal: 0 = upright, PI/2 = lying flat.
 *   Every Galaxy Note S Pen reports it (owner, 2026-09-28).
 * @property azimuth the direction the pen LEANS, in document space, -PI..PI, 0 = towards +x. On
 *   Android this comes from AXIS_ORIENTATION for styluses. NaN when unknown.
 * @property barrel twist of the pen around its own axis (Wacom Art Pen, Apple Pencil Pro). NaN on
 *   every S Pen.
 * @property predicted true for points invented by motion prediction. They may be DRAWN for latency
 *   but are never stored in a stroke recording.
 */
data class PenSample(
    val x: Float,
    val y: Float,
    val timeMs: Double,
    val pressure: Float = 1f,
    val tilt: Float = Float.NaN,
    val azimuth: Float = Float.NaN,
    val barrel: Float = Float.NaN,
    val tool: Tool = Tool.STYLUS,
    val predicted: Boolean = false,
) {
    val hasTilt: Boolean get() = !tilt.isNaN()
    val hasAzimuth: Boolean get() = !azimuth.isNaN()
    val hasBarrel: Boolean get() = !barrel.isNaN()

    /**
     * True when this sample says where the pen is and when: `x`, `y` and `timeMs` are all real
     * numbers. A sample that fails this is dropped by [StrokeSmoother] rather than smoothed, because
     * a NaN in the resampler's carried distance is permanent — the stroke stops releasing points for
     * the rest of its life. Zero, negative, NaN and ±Infinity all fail, so a coordinate read from a
     * corrupt recording cannot delete the samples recorded after it.
     *
     * The sensor channels are NOT part of this: a pen with no tilt sensor has `tilt = NaN` and is
     * perfectly placeable, which is the whole point of the NaN convention above.
     */
    val isPlaceable: Boolean
        get() = x.isFinite() && y.isFinite() && timeMs.isFinite()
}

/**
 * What touched the screen. The eraser end of a pen is its own tool, not a flag.
 *
 * FROZEN ORDER: stroke files store the ordinal (JB-0.04), so constants are never reordered or
 * removed — only appended. `ToolOrdinalFreezeTest` enforces it.
 */
enum class Tool { STYLUS, ERASER, FINGER, MOUSE }

package cc.joycreator.joybrush.core.trace

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.InkReplay
import kotlin.math.hypot

/**
 * Traced lines as ordinary editable lines: one [StrokeRecord] each, drawn with [brush], so a traced line moves, recolours,
 * re-weights and erases like one drawn with the pen.
 *
 * A line's width is put back through the brush itself: the brush is asked how wide it draws at each pressure (by
 * replaying a short line, so its pressure curve, response curve and smallest size all count), the line's widest point
 * sets the record's `widthScale`, and each point gets the pressure that gives its own width. A brush whose width does
 * not follow pressure draws the line at its middle width.
 */
object TracedStrokes {

    /** Where the traced picture sits in the drawing: drawing position = origin + picture position x scale. */
    data class Placement(val originX: Float = 0f, val originY: Float = 0f, val scale: Float = 1f)

    /** How fast the made-up pen moves along a traced line, in drawing px per ms (a calm 400 px a second). */
    private const val PX_PER_MS = 0.4

    /** The widths a stroke's widthScale may take (StrokeEdit's range). */
    private const val MIN_SCALE = 0.05f
    private const val MAX_SCALE = 20f

    /**
     * One record per line, in order. [firstSeq] is the first line's place in its cel's time order (the next ones follow
     * it); [idOf] names line number i.
     */
    fun toRecords(lines: List<TracedLine>, brush: BrushPreset, firstSeq: Long, idOf: (Int) -> String,
                  placement: Placement = Placement()): List<StrokeRecord> {
        if (lines.isEmpty()) return emptyList()
        val curve = diameterCurve(brush)
        val flat = curve.last() <= 0f || (curve.last() - curve.first()) / curve.last() < 0.05f
        val s = if (placement.scale.isFinite() && placement.scale > 0f) placement.scale else 1f
        return lines.mapIndexed { i, line ->
            val targets = FloatArray(line.pointCount) { 2f * line.halfWidths[it] * s }
            val widest = targets.maxOrNull() ?: 0f
            val middle = targets.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
            val full = curve.last().takeIf { it > 0f } ?: 1f
            val widthScale = ((if (flat) middle else widest) / full).coerceIn(MIN_SCALE, MAX_SCALE)
            val samples = ArrayList<PenSample>(line.pointCount + 1)
            var t = 0.0
            var px = Float.NaN
            var py = Float.NaN
            val order = if (line.closed && line.pointCount > 2) (0 until line.pointCount) + 0 else (0 until line.pointCount).toList()
            for (k in order) {
                val x = placement.originX + line.xs[k] * s
                val y = placement.originY + line.ys[k] * s
                if (!px.isNaN()) t += hypot((x - px).toDouble(), (y - py).toDouble()) / PX_PER_MS
                px = x
                py = y
                val pressure = if (flat) 1f else pressureFor(curve, targets[k] / widthScale)
                samples += PenSample(x, y, t, pressure = pressure)
            }
            if (samples.size == 1) samples += samples[0].copy(timeMs = 1.0)   // a dot: a tap, held a moment
            StrokeRecord(
                id = idOf(i), brushId = brush.id, seed = 0x7ACEL + i, smoothing = 0f, screenPerDoc = 1f,
                samples = samples, colorArgb = line.colorArgb, widthScale = widthScale, seq = firstSeq + i,
            )
        }
    }

    /** How wide [brush] draws at pressure 0, 1/16, ... 1, in drawing px, at widthScale 1. */
    internal fun diameterCurve(brush: BrushPreset): FloatArray = FloatArray(STEPS + 1) { k ->
        val p = k / STEPS.toFloat()
        val probe = StrokeRecord(
            id = "trace-probe", brushId = brush.id, seed = 1L, smoothing = 0f, screenPerDoc = 1f,
            samples = listOf(PenSample(0f, 0f, 0.0, pressure = p), PenSample(200f, 0f, 500.0, pressure = p)),
        )
        val radii = try {
            InkReplay.dabs(probe, brush).map { it.radius }.filter { it.isFinite() }.sorted()
        } catch (e: Exception) {
            emptyList()
        }
        if (radii.isEmpty()) brush.size.base else 2f * radii[radii.size / 2]
    }

    private const val STEPS = 16

    /** The smallest pressure at which the brush draws [diameter] wide (between the measured steps, a straight line). */
    internal fun pressureFor(curve: FloatArray, diameter: Float): Float {
        if (diameter <= curve.first()) return 0f
        for (k in 1 until curve.size) {
            if (curve[k] >= diameter) {
                val a = curve[k - 1]
                val b = curve[k]
                val f = if (b > a) (diameter - a) / (b - a) else 1f
                return ((k - 1 + f) / (curve.size - 1)).coerceIn(0f, 1f)
            }
        }
        return 1f
    }
}

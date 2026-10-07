package cc.joycreator.joybrush.core.media

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The media engine's CPU half (M5.1): a line-for-line port of the PC media lab (joybrush/lab/media/js), checked
 * number for number against the lab's own outputs (jvmTest MediaGoldenTest, golden.json from
 * tools/media_golden.mjs). The GPU half is joybrush/shaders/media/, shared unchanged with the lab.
 *
 * Everything here works in DOCUMENT pixels (20 per mm, [DOC_PX_PER_MM]), radians and milliseconds, in Double,
 * as the lab does, so the two stay identical. Change the lab first, regenerate golden.json, then port.
 */
const val DOC_PX_PER_MM = 20.0

/** One pen sample as the media strokes read it. tilt: 0 upright … PI/2 flat; az: the way the pen leans. */
data class MediaSample(val x: Double, val y: Double, val p: Double, val tilt: Double, val az: Double, val t: Double)

internal fun smoothstep(a: Double, b: Double, x: Double): Double {
    val t = min(1.0, max(0.0, (x - a) / (b - a)))
    return t * t * (3 - 2 * t)
}

internal fun angleDelta(a: Double, b: Double): Double {
    var d = b - a
    while (d > PI) d -= 2 * PI
    while (d < -PI) d += 2 * PI
    return d
}

internal fun norm2(x: Double, y: Double): DoubleArray {
    val l = hypot(x, y).let { if (it == 0.0) 1.0 else it }
    return doubleArrayOf(x / l, y / l)
}

/** A doc-px rectangle grown to cover a box (null = nothing yet). */
internal fun grow(rect: DoubleArray?, x0: Double, y0: Double, x1: Double, y1: Double): DoubleArray =
    if (rect == null) doubleArrayOf(x0, y0, x1, y1)
    else doubleArrayOf(min(rect[0], x0), min(rect[1], y0), max(rect[2], x1), max(rect[3], y1))

/** Instanced dabs for a media pass: [FLOATS] floats each, in the layout the dab vertex shader reads. */
class DabBatch(val dabs: FloatArray, val count: Int, val dirty: DoubleArray?) {
    companion object { const val FLOATS = 20 }
}

/** Collects dab instances (20 floats each) and the doc-px rectangle they touch. */
internal class DabCollector {
    private val pending = ArrayList<DoubleArray>()
    var dirty: DoubleArray? = null
        private set

    fun push(inst: DoubleArray, box: DoubleArray) {
        pending.add(inst)
        dirty = grow(dirty, box[0], box[1], box[2], box[3])
    }

    fun take(): DabBatch {
        val out = FloatArray(pending.size * DabBatch.FLOATS)
        pending.forEachIndexed { i, inst -> for (k in 0 until DabBatch.FLOATS) out[i * DabBatch.FLOATS + k] = inst[k].toFloat() }
        val batch = DabBatch(out, pending.size, dirty)
        pending.clear()
        dirty = null
        return batch
    }
}

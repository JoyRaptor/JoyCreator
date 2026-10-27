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

/**
 * Instanced dabs for a media pass: [FLOATS] floats each, in the layout the dab vertex shader reads. [boxes]: each dab's
 * own doc-px box (4 doubles per dab), when known, so a frame too big for the media atlas can be split in time ([slice]).
 */
class DabBatch(val dabs: FloatArray, val count: Int, val dirty: DoubleArray?, val boxes: DoubleArray? = null) {
    /**
     * Dabs [from] until [to], in order, with their own dirty box: what a faster phone would have batched into a frame of
     * its own (Media Atlas v2 B3 step 6, the Lead's D3). Null when the boxes are not known.
     */
    fun slice(from: Int, to: Int): DabBatch? {
        val b = boxes ?: return null
        require(from in 0..to && to <= count) { "slice $from..$to of $count dabs" }
        var d: DoubleArray? = null
        for (i in from until to) d = grow(d, b[i * 4], b[i * 4 + 1], b[i * 4 + 2], b[i * 4 + 3])
        return DabBatch(dabs.copyOfRange(from * FLOATS, to * FLOATS), to - from, d, b.copyOfRange(from * 4, to * 4))
    }

    /**
     * ONE batch holding every dab of [batches], in order: the symmetry copies' dabs in ONE frame.
     *
     * This is what makes the copies share ONE water field: the wet stroke's dabs of every lane go
     * into a single batch, the water runs once for the frame, and the copies mix where they meet.
     * The dirty box is the union of the batches' own, and the boxes (when every batch has them)
     * are the batches' own, so a frame can still be cut in time.
     *
     * Null when there is nothing to draw: an empty batch is not a frame.
     */
    companion object {
        const val FLOATS = 20

        fun merge(batches: List<DabBatch>): DabBatch? {
            val live = batches.filter { it.count > 0 }
            if (live.isEmpty()) return null
            if (live.size == 1) return live[0]
            var n = 0
            var withBoxes = true
            for (b in live) { n += b.count; if (b.boxes == null) withBoxes = false }
            val dabs = FloatArray(n * FLOATS)
            val boxes = if (withBoxes) DoubleArray(n * 4) else null
            var dirty: DoubleArray? = null
            var o = 0
            for (b in live) {
                b.dabs.copyInto(dabs, o * FLOATS, 0, b.count * FLOATS)
                o += b.count
                val d = b.dirty
                if (d != null) {
                    dirty = if (dirty == null) d.copyOf() else doubleArrayOf(
                        minOf(dirty[0], d[0]), minOf(dirty[1], d[1]), maxOf(dirty[2], d[2]), maxOf(dirty[3], d[3]))
                }
                val bx = b.boxes
                if (boxes != null && bx != null) bx.copyInto(boxes, (o - b.count) * 4, 0, b.count * 4)
            }
            return DabBatch(dabs, n, dirty, boxes)
        }
    }
}

/** Collects dab instances (20 floats each) and the doc-px rectangle they touch. */
internal class DabCollector {
    private val pending = ArrayList<DoubleArray>()
    private val boxes = ArrayList<DoubleArray>()
    var dirty: DoubleArray? = null
        private set

    fun push(inst: DoubleArray, box: DoubleArray) {
        pending.add(inst)
        boxes.add(box)
        dirty = grow(dirty, box[0], box[1], box[2], box[3])
    }

    fun take(): DabBatch {
        val out = FloatArray(pending.size * DabBatch.FLOATS)
        pending.forEachIndexed { i, inst -> for (k in 0 until DabBatch.FLOATS) out[i * DabBatch.FLOATS + k] = inst[k].toFloat() }
        val bx = DoubleArray(boxes.size * 4)
        boxes.forEachIndexed { i, b -> for (k in 0 until 4) bx[i * 4 + k] = b[k] }
        val batch = DabBatch(out, pending.size, dirty, bx)
        pending.clear()
        boxes.clear()
        dirty = null
        return batch
    }
}

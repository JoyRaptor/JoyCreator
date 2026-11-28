package cc.joycreator.joybrush.core.trace

/**
 * Concepts parity 4c: turn painted pixels into lines.
 *
 * A picture to trace, straight (not premultiplied) ARGB, row-major, [width] x [height]. Pixels whose alpha is under the
 * trace's threshold are empty; the rest are ink.
 */
class TraceImage(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width >= 0 && height >= 0 && argb.size == width * height) { "TraceImage: ${argb.size} pixels for $width x $height" }
    }

    fun alphaAt(x: Int, y: Int): Int = (argb[y * width + x] ushr 24) and 0xFF
}

/**
 * One traced line, in the picture's pixels: a polyline through ([xs], [ys]) with a half-width per point, the same shape an
 * editable line has (`InkLine`). [closed] lines end where they start (a ring, the edge of a filled shape).
 */
class TracedLine(
    val xs: FloatArray,
    val ys: FloatArray,
    val halfWidths: FloatArray,
    val closed: Boolean,
    /** Straight sRGB: the ink's colour, averaged over the pixels this line came from, alpha forced to opaque. */
    val colorArgb: Int,
    val kind: Kind,
) {
    /** A thin mark is traced along its middle; a filled shape along its edge. */
    enum class Kind { CENTRE, OUTLINE }

    val pointCount: Int get() = xs.size
}

/**
 * How the tracer reads a picture. Every length is in the picture's pixels.
 *
 * @property alphaThreshold a pixel at or above this alpha (0..255), after [soften], is ink.
 * @property minPixels a patch of ink smaller than this is dust, and is left out. In grainy ink a patch under 40 pixels is
 *   grain, whatever this says.
 * @property thinHalfWidth a patch wider than twice this anywhere is a filled shape, traced along its edge. A narrower
 *   patch is a mark traced along its middle, unless it is a blob (no longer than it is wide), which is an edge too.
 * @property tolerance how far a traced line may cut a corner when it is simplified.
 * @property outlineHalfWidth the half-width of a line traced along a filled shape's edge.
 * @property soften how far grainy ink is blurred before it is read (a pencil's grain, a scan's speckle, would otherwise
 *   be traced grain by grain); softening only adds ink, so it fills grain and never shaves a tip. [AUTO] softens by 1.5 px
 *   only when the picture is grainy (many specks and pinholes for its ink), and leaves clean ink exact. 0 never softens.
 * @property minHole a gap inside a patch smaller than this many pixels is grain, filled before tracing; a bigger one is a
 *   real hole (the middle of a ring, of an O) and is kept.
 */
data class TraceOptions(
    val alphaThreshold: Int = 128,
    val minPixels: Int = 6,
    val thinHalfWidth: Float = 24f,
    val tolerance: Float = 0.6f,
    val outlineHalfWidth: Float = 1f,
    val soften: Float = AUTO,
    val minHole: Int = 64,
) {
    companion object {
        /** [soften]: decide from the picture. */
        const val AUTO = -1f
    }
}

/**
 * Pixels in, lines out. Pure: the same picture and options always give the same lines, in the same order.
 *
 * Each patch of ink (pixels joined side to side or corner to corner) is traced on its own: along its middle when it is
 * thin everywhere ([TraceOptions.thinHalfWidth]), along its edge when it is a filled shape. Patches come in reading
 * order (top to bottom, then left to right, by their first pixel).
 */
object RasterTracer {

    /** In grainy ink, a patch smaller than this is grain broken off a mark's soft edge, not a mark of its own. */
    private const val GRAIN_DUST = 40

    fun trace(image: TraceImage, options: TraceOptions = TraceOptions()): List<TracedLine> {
        val w = image.width
        val h = image.height
        if (w == 0 || h == 0) return emptyList()
        val threshold = options.alphaThreshold.coerceIn(1, 255)
        // Softening only ever adds ink (it fills grain), never takes it: a blur alone would shave tips and corners.
        val raw = IntArray(w * h) { image.alphaAt(it % w, it / w) }
        val grainy = TraceMath.grainy(w, h, raw, threshold)
        val radius = if (options.soften == TraceOptions.AUTO) (if (grainy) 1.5f else 0f) else options.soften
        val dust = if (grainy) maxOf(options.minPixels, GRAIN_DUST) else options.minPixels
        val soft = TraceMath.soften(w, h, raw, radius)
        val alpha = IntArray(w * h) { maxOf(raw[it], soft[it]) }
        val ink = BooleanArray(w * h) { alpha[it] >= threshold }
        val label = IntArray(w * h) { -1 }
        val out = ArrayList<TracedLine>()
        val stack = IntArrayStack()
        val members = IntArrayStack()
        for (seed in 0 until w * h) {
            if (!ink[seed] || label[seed] >= 0) continue
            val id = seed
            label[seed] = id
            stack.push(seed)
            members.clear()
            var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
            while (stack.size > 0) {
                val p = stack.pop()
                members.push(p)
                val px = p % w
                val py = p / w
                if (px < x0) x0 = px; if (px > x1) x1 = px; if (py < y0) y0 = py; if (py > y1) y1 = py
                for (dy in -1..1) for (dx in -1..1) {
                    val qx = px + dx
                    val qy = py + dy
                    if (qx !in 0 until w || qy !in 0 until h) continue
                    val q = qy * w + qx
                    if (ink[q] && label[q] < 0) { label[q] = id; stack.push(q) }
                }
            }
            if (members.size < dust) continue
            out += tracePatch(image, alpha, members, x0, y0, x1, y1, options)
        }
        return out
    }

    private fun tracePatch(image: TraceImage, alpha: IntArray, members: IntArrayStack,
                           x0: Int, y0: Int, x1: Int, y1: Int, options: TraceOptions): List<TracedLine> {
        val w = image.width
        // The patch's colour: its pixels' straight colour, weighted by how much ink each holds.
        var r = 0.0; var g = 0.0; var b = 0.0; var a = 0.0
        for (k in 0 until members.size) {
            val c = image.argb[members[k]]
            val al = ((c ushr 24) and 0xFF).toDouble()
            r += ((c ushr 16) and 0xFF) * al; g += ((c ushr 8) and 0xFF) * al; b += (c and 0xFF) * al; a += al
        }
        val colour = (0xFF shl 24) or ((r / a + 0.5).toInt().coerceIn(0, 255) shl 16) or
            ((g / a + 0.5).toInt().coerceIn(0, 255) shl 8) or (b / a + 0.5).toInt().coerceIn(0, 255)
        // The patch's own box, with two empty pixels round it.
        val pad = 2
        val bx = x0 - pad
        val by = y0 - pad
        val bw = x1 - x0 + 1 + 2 * pad
        val bh = y1 - y0 + 1 + 2 * pad
        val inside = BooleanArray(bw * bh)
        for (k in 0 until members.size) {
            val p = members[k]
            inside[(p / w - by) * bw + (p % w - bx)] = true
        }
        TraceMath.fillSmallHoles(bw, bh, inside, options.minHole)
        val dist = TraceMath.distanceToOutside(bw, bh, inside)
        var widest = 0f
        for (d in dist) if (d > widest) widest = d
        var thin = widest - 0.5f <= options.thinHalfWidth
        var raw: List<RawLine> = emptyList()
        if (thin) {
            raw = widthFromInk(CentreLines.trace(bw, bh, inside, dist, bx, by, options.tolerance), alpha, w,
                image.height, inside, bx, by, bw, bh)
            // A blob (no longer than it is wide) is a shape, not a mark: its edge says what it is. A dot stays a dot.
            val length = raw.sumOf { lengthOf(it) }
            val points = maxOf(1, raw.sumOf { it.halfWidths.size })
            val width = 2.0 * raw.sumOf { l -> l.halfWidths.sum().toDouble() } / points
            if (width > 6.0 && length < 2.0 * width) thin = false
        }
        if (!thin) {
            // The shape's alpha, with the faint pixels just outside its ink kept so the edge falls between them.
            val value = FloatArray(bw * bh)
            for (yy in 0 until bh) for (xx in 0 until bw) {
                val px = xx + bx
                val py = yy + by
                if (px !in 0 until w || py !in 0 until image.height) continue
                var near = false
                for (dy in -1..1) for (dx in -1..1) {
                    val qx = xx + dx
                    val qy = yy + dy
                    if (qx in 0 until bw && qy in 0 until bh && inside[qy * bw + qx]) near = true
                }
                if (!near) continue
                val a = alpha[py * w + px]
                // a filled-in grain hole counts as ink, so the edge does not dive into it
                value[yy * bw + xx] = (if (inside[yy * bw + xx]) maxOf(a, options.alphaThreshold) else a) / 255f
            }
            raw = Outlines.trace(bw, bh, value, options.alphaThreshold.coerceIn(1, 255) / 255f, bx, by, options.tolerance,
                options.outlineHalfWidth)
        }
        val kind = if (thin) TracedLine.Kind.CENTRE else TracedLine.Kind.OUTLINE
        return raw.filter { it.xs.isNotEmpty() }.map { TracedLine(it.xs, it.ys, it.halfWidths, it.closed, colour, kind) }
    }
}

/**
 * The width of a thin mark, from how much ink it holds. The solid pixels alone under-measure it: a brush's soft edge is
 * pixels under the threshold, half a pixel or so each side. So the mark's whole ink (its soft edge included) divided by
 * its traced length is its width, and each point keeps its own share of it (a tapered end stays thinner).
 */
private fun widthFromInk(lines: List<RawLine>, alpha: IntArray, iw: Int, ih: Int, inside: BooleanArray,
                         bx: Int, by: Int, bw: Int, bh: Int): List<RawLine> {
    var ink = 0.0
    for (yy in 0 until bh) for (xx in 0 until bw) {
        val px = xx + bx
        val py = yy + by
        if (px !in 0 until iw || py !in 0 until ih) continue
        var near = false
        for (dy in -1..1) for (dx in -1..1) {
            val qx = xx + dx
            val qy = yy + dy
            if (qx in 0 until bw && qy in 0 until bh && inside[qy * bw + qx]) near = true
        }
        // a filled-in grain hole counts as ink: the mark is that wide
        if (near) ink += (if (inside[yy * bw + xx]) maxOf(alpha[py * iw + px], 128) else alpha[py * iw + px]) / 255.0
    }
    var length = 0.0
    var sum = 0.0
    var count = 0
    for (l in lines) {
        length += lengthOf(l)
        for (hw in l.halfWidths) { sum += hw; count++ }
    }
    if (length < 1.0 || count == 0 || sum <= 0.0) return lines
    val scale = ((ink / length) / 2.0) / (sum / count)
    return lines.map { l ->
        RawLine(l.xs, l.ys, FloatArray(l.halfWidths.size) { TraceMath.clampMin((l.halfWidths[it] * scale).toFloat(), 0.5f) }, l.closed)
    }
}

internal fun lengthOf(l: RawLine): Double {
    var length = 0.0
    for (i in 1 until l.xs.size) length += kotlin.math.hypot((l.xs[i] - l.xs[i - 1]).toDouble(), (l.ys[i] - l.ys[i - 1]).toDouble())
    if (l.closed && l.xs.size > 2) length += kotlin.math.hypot((l.xs[0] - l.xs.last()).toDouble(), (l.ys[0] - l.ys.last()).toDouble())
    return length
}

/** A growable stack of ints (no boxing: a patch can be millions of pixels). */
internal class IntArrayStack {
    private var data = IntArray(256)
    var size = 0
        private set

    fun push(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    fun pop(): Int = data[--size]

    operator fun get(i: Int): Int = data[i]

    fun clear() { size = 0 }
}

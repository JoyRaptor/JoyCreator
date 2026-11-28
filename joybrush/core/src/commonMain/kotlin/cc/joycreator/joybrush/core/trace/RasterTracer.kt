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
 * @property alphaThreshold a pixel at or above this alpha (0..255) is ink.
 * @property minPixels a patch of ink smaller than this is dust, and is left out.
 * @property thinHalfWidth a patch no wider than twice this, anywhere, is a mark traced along its middle; a wider one is a
 *   filled shape, traced along its edge.
 * @property tolerance how far a traced line may cut a corner when it is simplified.
 * @property outlineHalfWidth the half-width of a line traced along a filled shape's edge.
 */
data class TraceOptions(
    val alphaThreshold: Int = 128,
    val minPixels: Int = 6,
    val thinHalfWidth: Float = 6f,
    val tolerance: Float = 0.6f,
    val outlineHalfWidth: Float = 1f,
)

/** Pixels in, lines out. Pure: the same picture and options always give the same lines, in the same order. */
object RasterTracer {
    /** Today's rule: nothing traces. */
    fun trace(image: TraceImage, options: TraceOptions = TraceOptions()): List<TracedLine> = emptyList()
}

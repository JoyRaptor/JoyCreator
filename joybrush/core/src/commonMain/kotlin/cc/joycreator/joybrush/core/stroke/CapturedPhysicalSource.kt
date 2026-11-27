package cc.joycreator.joybrush.core.stroke

/**
 * Deliberate source-value budget, not the document's board limit. Sixty-four exclusions bound
 * a query to at most 130 horizontal edges and 129 * 65 candidate free spans, independently of
 * its pixel area. Future contact admission must refuse BEFORE replacing the native path when
 * this budget is exceeded; it must never truncate a legitimate dense document's ownership.
 */
const val CAPTURE_DOMAIN_MAX_EXCLUSIONS = 64

/** Separate fail-closed budget for returned fragments; a query never returns a partial mask. */
const val CAPTURE_DOMAIN_MAX_CLIP_RECTS = 4096

private const val CAPTURE_EDGE_MAX = Int.MAX_VALUE + 1L
private val captureRectOrder = compareBy<CaptureRect>({ it.y }, { it.x }, { it.h }, { it.w })

/** An immutable addressable pixel. Mapping a point beyond the Int pixel range is a refusal. */
data class CapturePixel(val x: Int, val y: Int)

/**
 * Half-open integer pixel rectangle. Origins and positive extents are Int; exclusive endpoints
 * are Long and may equal MAX_VALUE + 1 so the final addressable Int pixel is representable.
 * A query cannot span more than Int.MAX_VALUE pixels on either axis. No area is allocated.
 */
data class CaptureRect(val x: Int, val y: Int, val w: Int, val h: Int) {
    init {
        require(w > 0 && h > 0) { "A captured pixel rectangle needs positive extents" }
        require(x.toLong() + w <= CAPTURE_EDGE_MAX && y.toLong() + h <= CAPTURE_EDGE_MAX) {
            "A captured pixel rectangle exceeds addressable pixels"
        }
    }

    val right: Long get() = x.toLong() + w
    val bottom: Long get() = y.toLong() + h

    fun contains(x: Int, y: Int): Boolean =
        x >= this.x && y >= this.y && x.toLong() < right && y.toLong() < bottom

    fun intersect(other: CaptureRect): CaptureRect? {
        val left = maxOf(x, other.x).toLong()
        val top = maxOf(y, other.y).toLong()
        val right = minOf(right, other.right)
        val bottom = minOf(bottom, other.bottom)
        return if (left >= right || top >= bottom) null else fromEdges(left, top, right, bottom)
    }

    companion object {
        /** Explicit caller tile size: this type neither duplicates nor owns an engine constant. */
        fun tile(tx: Int, ty: Int, size: Int): CaptureRect {
            require(size > 0) { "A captured tile needs a positive size" }
            val x = tx.toLong() * size
            val y = ty.toLong() * size
            return fromEdges(x, y, x + size, y + size)
        }

        internal fun fromEdges(left: Long, top: Long, right: Long, bottom: Long): CaptureRect {
            require(left in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() &&
                top in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() &&
                right <= CAPTURE_EDGE_MAX && bottom <= CAPTURE_EDGE_MAX &&
                right > left && bottom > top &&
                right - left <= Int.MAX_VALUE && bottom - top <= Int.MAX_VALUE) {
                "Captured rectangle mapping exceeds addressable pixels or extents"
            }
            return CaptureRect(left.toInt(), top.toInt(), (right - left).toInt(), (bottom - top).toInt())
        }
    }
}

/**
 * A nullable window minus a finite union of exclusions, in immutable capture coordinates.
 * Null means no finite window, not an inferred brush/sample bound. Only caller-supplied bounded
 * queries are enumerated. Overlaps are deliberately accepted as union (unlike the earlier
 * disjoint-only review suggestion); duplicates are removed and values ordered by y,x,h,w.
 * Equality compares this canonical representation, not every possible equivalent rectangle union.
 *
 * Exclusions and clipping results have private array-backed read-only implementations: changing
 * caller lists or attempting a MutableList cast cannot mutate this value or an earlier result.
 * No document, GL, codec, brush, samples, affine transform or native ownership is involved.
 */
class CaptureDomain(window: CaptureRect? = null, excluded: List<CaptureRect> = emptyList()) {
    val window: CaptureRect? = window
    val excluded: List<CaptureRect>

    init {
        // Check supplied count before deduplication/allocation: repeated input is not a budget bypass.
        require(excluded.size <= CAPTURE_DOMAIN_MAX_EXCLUSIONS) { "Captured source has too many exclusions" }
        this.excluded = immutableCaptureRects(excluded.distinct().sortedWith(captureRectOrder))
    }

    fun contains(x: Int, y: Int): Boolean =
        (window == null || window.contains(x, y)) && excluded.none { it.contains(x, y) }

    /** Exact, disjoint world-coordinate rectangles, in deterministic y,x,h,w order. */
    fun clip(query: CaptureRect): List<CaptureRect> {
        val area = if (window == null) query else window.intersect(query) ?: return emptyList()
        val cuts = excluded.mapNotNull { it.intersect(area) }.sortedWith(compareBy({ it.x }, { it.right }))
        if (cuts.isEmpty()) return immutableCaptureRects(listOf(area))
        val edges = (listOf(area.y.toLong(), area.bottom) + cuts.flatMap { listOf(it.y.toLong(), it.bottom) })
            .distinct().sorted()
        val result = ArrayList<CaptureRect>()
        var previous = emptyMap<Span, Int>()
        for (band in 0 until edges.lastIndex) {
            val top = edges[band]
            val bottom = edges[band + 1]
            val current = HashMap<Span, Int>()
            fun keep(left: Long, right: Long) {
                if (left >= right) return
                val span = Span(left, right)
                val old = previous[span]
                val index = if (old != null && result[old].bottom == top) {
                    val prior = result[old]
                    result[old] = CaptureRect.fromEdges(left, prior.y.toLong(), right, bottom)
                    old
                } else {
                    require(result.size < CAPTURE_DOMAIN_MAX_CLIP_RECTS) { "Captured clip has too many fragments" }
                    result.add(CaptureRect.fromEdges(left, top, right, bottom))
                    result.lastIndex
                }
                current[span] = index
            }
            var cursor = area.x.toLong()
            for (cut in cuts) {
                if (cut.y.toLong() > top || cut.bottom < bottom) continue
                keep(cursor, cut.x.toLong())
                cursor = maxOf(cursor, cut.right)
            }
            keep(cursor, area.right)
            previous = current
        }
        return immutableCaptureRects(result.sortedWith(captureRectOrder))
    }

    /** World-coordinate intersections with this one tile; never enumerate the whole complement. */
    fun clipTile(tx: Int, ty: Int, size: Int): List<CaptureRect> = clip(CaptureRect.tile(tx, ty, size))

    override fun equals(other: Any?): Boolean =
        other is CaptureDomain && window == other.window && excluded == other.excluded

    override fun hashCode(): Int = 31 * (window?.hashCode() ?: 0) + excluded.hashCode()
    override fun toString(): String = "CaptureDomain(window=$window, excluded=$excluded)"

    private data class Span(val left: Long, val right: Long)
}

/**
 * Exact source-image translation, not a transformation of raw samples or a rerender policy.
 * All point, rectangle and cumulative mappings calculate in Long and refuse overflow. A mapped
 * query must be wholly addressable; it is never silently clamped to make a mask look valid.
 */
data class SourcePlacement(val dx: Int = 0, val dy: Int = 0) {
    fun sourceToDestination(x: Int, y: Int): CapturePixel = pixel(x.toLong() + dx, y.toLong() + dy)
    fun destinationToSource(x: Int, y: Int): CapturePixel = pixel(x.toLong() - dx, y.toLong() - dy)

    fun sourceToDestination(rect: CaptureRect): CaptureRect = CaptureRect.fromEdges(
        rect.x.toLong() + dx, rect.y.toLong() + dy, rect.right + dx, rect.bottom + dy)

    fun destinationToSource(rect: CaptureRect): CaptureRect = CaptureRect.fromEdges(
        rect.x.toLong() - dx, rect.y.toLong() - dy, rect.right - dx, rect.bottom - dy)

    /** This placement followed by [next]; no sample coordinates or sensor channels are changed. */
    fun then(next: SourcePlacement): SourcePlacement {
        val combined = pixel(dx.toLong() + next.dx, dy.toLong() + next.dy)
        return SourcePlacement(combined.x, combined.y)
    }

    private fun pixel(x: Long, y: Long): CapturePixel {
        require(x in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() &&
            y in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Captured source placement exceeds addressable pixels" }
        return CapturePixel(x.toInt(), y.toInt())
    }
}

/** Pure source-domain/placement value only; integration with a stroke or archive is separate. */
data class CapturedPhysicalSource(
    val domain: CaptureDomain,
    val placement: SourcePlacement = SourcePlacement(),
) {
    /** Clip in destination space by pulling back the bounded query, then placing its exact pieces. */
    fun clipDestination(query: CaptureRect): List<CaptureRect> = immutableCaptureRects(
        domain.clip(placement.destinationToSource(query)).map { placement.sourceToDestination(it) })

    fun clipDestinationTile(tx: Int, ty: Int, size: Int): List<CaptureRect> =
        clipDestination(CaptureRect.tile(tx, ty, size))
}

private fun immutableCaptureRects(rectangles: List<CaptureRect>): List<CaptureRect> =
    ImmutableCaptureRects(rectangles)

private class ImmutableCaptureRects(rectangles: List<CaptureRect>) : AbstractList<CaptureRect>() {
    private val values = rectangles.toTypedArray()
    override val size: Int get() = values.size
    override fun get(index: Int): CaptureRect = values[index]
}

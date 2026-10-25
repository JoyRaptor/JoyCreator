package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.stroke.CapturedStrokeRules
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.math.max
import kotlin.math.min

/** A labelled interval in original fractional InkLine point-index coordinates. */
data class LineSliceSpan(
    val from: Double,
    val to: Double,
    /** True when this span is a validated touched interval; false means it is kept. */
    val touched: Boolean,
    /** Spans own [from,to), except the final span owns its terminal endpoint too. */
    val ownsEnd: Boolean,
)

/** Caller-supplied touched interval, in fractional InkLine point-index coordinates. */
data class LineSliceInterval(val from: Double, val to: Double)

/**
 * Bounded geometric partition of an original recorded line. The [record] snapshot retains every
 * original raw sample and recipe field; [geometry] is the supplied InkLine snapshot and its
 * parameters are fractional point indices, never raw-sample indices. This is planning only: it
 * does not synthesize StrokeRecords, reseed, prove rendered-pixel equivalence, or integrate with
 * native line history or runtime rendering.
 */
class LineSlicePlan private constructor(
    val record: StrokeRecord,
    private val geometryValue: InkLine,
    spans: List<LineSliceSpan>,
    cutPositions: List<Double>,
    val pairTests: Long,
) {
    val spans: List<LineSliceSpan> = ImmutableSliceList(spans)
    val cutPositions: List<Double> = ImmutableSliceList(cutPositions)
    /** Deterministic work/output limits checked before pairwise intersection work starts. */
    data class Limits(
        val maxPairTests: Long = 1_000_000L,
        val maxPieces: Int = 4096,
        val maxInputPoints: Int = 65_536,
        val maxInputIntervals: Int = 4096,
    ) {
        init {
            require(maxPairTests in 0L..1_000_000L && maxPieces in 1..4096 &&
                maxInputPoints in 1..65_536 && maxInputIntervals in 0..4096)
        }
    }

    /** Return fresh arrays so callers cannot mutate this plan's geometry snapshot. */
    val geometry: InkLine get() = geometryValue.copyGeometry()

    companion object {
        /**
         * Split the full parameter domain at every centreline crossing with [cutters]. Crossings
         * have zero width: every resulting span remains kept. A cut at an existing vertex is one
         * boundary, and repeated/equal cuts are deduplicated exactly. Collinear overlap contributes
         * its entry and exit through the established [segmentIntersections] contract.
         */
        fun crossings(
            record: StrokeRecord,
            geometry: InkLine,
            cutters: List<InkLine>,
            limits: Limits = Limits(),
        ): LineSlicePlan {
            requireOrdinaryRecord(record)
            require(geometry.id == record.id) { "InkLine id must match StrokeRecord id" }
            var inputPoints = record.samples.size.toLong() + geometry.pointCount.toLong()
            require(inputPoints <= limits.maxInputPoints) { "Line slice input point budget exceeded" }
            for (cutter in cutters) {
                inputPoints = safeSum(inputPoints, cutter.pointCount.toLong())
                require(inputPoints <= limits.maxInputPoints) { "Line slice input point budget exceeded" }
            }
            validateRecordSamples(record)
            val targetSegments = segmentCountOf(geometry.pointCount).toLong()
            val cutterSegments = cutters.fold(0L) { total, cutter ->
                safeSum(total, segmentCountOf(cutter.pointCount).toLong())
            }
            val pairCount = safeProduct(targetSegments, cutterSegments)
            require(pairCount <= limits.maxPairTests) { "Line slice intersection pair budget exceeded" }
            val target = snapshotAndValidate(geometry)
            val cutCopies = cutters.map(::snapshotAndValidate)
            validateCombinedArithmetic(target, cutCopies)

            val cuts = HashSet<Double>()
            val hit = DoubleArray(4)
            for (i in 0 until segmentCountOf(target.pointCount)) {
                val i0 = min(i, target.pointCount - 1)
                val i1 = min(i + 1, target.pointCount - 1)
                val ax = target.xs[i0]; val ay = target.ys[i0]
                val bx = target.xs[i1]; val by = target.ys[i1]
                for (cutter in cutCopies) for (j in 0 until segmentCountOf(cutter.pointCount)) {
                    val j0 = min(j, cutter.pointCount - 1)
                    val j1 = min(j + 1, cutter.pointCount - 1)
                    val count = segmentIntersections(ax, ay, bx, by,
                        cutter.xs[j0], cutter.ys[j0], cutter.xs[j1], cutter.ys[j1], hit)
                    for (k in 0 until count) {
                        val t = hit[2 * k]
                        require(t.isFinite() && t in 0.0..1.0) { "Non-finite line intersection" }
                        val at = i + t
                        if (at > 0.0 && at < target.lastParam) {
                            cuts += at
                            require(cuts.size + 1 <= limits.maxPieces) { "Line slice piece budget exceeded" }
                        }
                    }
                }
            }
            return build(record, target, cuts.toList().sorted(), pairCount, limits)
        }

        /**
         * Partition by caller-supplied validated touched ranges. Their union is labelled touched;
         * the complementary intervals are kept. Overlaps merge, zero-width intervals are rejected
         * except [0,0] on a dot, and the complete [0,lastParam] domain is covered once using
         * half-open ownership, with the final span owning the last endpoint. This is suitable for
         * later planning, not evidence that a pixel-space brush footprint is represented exactly by
         * centreline intervals.
         */
        fun touchedIntervals(
            record: StrokeRecord,
            geometry: InkLine,
            intervals: List<LineSliceInterval>,
            limits: Limits = Limits(),
        ): LineSlicePlan {
            requireOrdinaryRecord(record)
            require(geometry.id == record.id) { "InkLine id must match StrokeRecord id" }
            require(record.samples.size.toLong() + geometry.pointCount.toLong() <= limits.maxInputPoints) {
                "Line slice input point budget exceeded"
            }
            require(intervals.size <= limits.maxInputIntervals) { "Line slice input interval budget exceeded" }
            validateRecordSamples(record)
            val target = snapshotAndValidate(geometry)
            val domainEnd = target.lastParam
            val sorted = intervals.map { range ->
                val dotTouch = domainEnd == 0.0 && range.from == 0.0 && range.to == 0.0
                require(range.from.isFinite() && range.to.isFinite() && (range.from < range.to || dotTouch) &&
                    range.from >= 0.0 && range.to <= domainEnd) { "Invalid touched line interval" }
                range
            }.sortedWith(compareBy<LineSliceInterval> { it.from }.thenBy { it.to })
            val merged = ArrayList<LineSliceInterval>()
            for (range in sorted) {
                val previous = merged.lastOrNull()
                if (previous != null && range.from <= previous.to) {
                    merged[merged.lastIndex] = LineSliceInterval(previous.from, max(previous.to, range.to))
                } else merged += range
            }
            val spans = ArrayList<LineSliceSpan>()
            var at = 0.0
            for (range in merged) {
                if (range.from > at) spans += LineSliceSpan(at, range.from, false, false)
                spans += LineSliceSpan(range.from, range.to, true, false)
                at = range.to
                require(spans.size <= limits.maxPieces) { "Line slice piece budget exceeded" }
            }
            if (at < domainEnd || spans.isEmpty()) spans += LineSliceSpan(at, domainEnd, false, true)
            else spans[spans.lastIndex] = spans.last().copy(ownsEnd = true)
            require(spans.size <= limits.maxPieces) { "Line slice piece budget exceeded" }
            return LineSlicePlan(CapturedStrokeRules.snapshot(record), target, spans.toList(), emptyList(), 0L)
        }

        private fun build(
            record: StrokeRecord, geometry: InkLine, cuts: List<Double>, pairTests: Long, limits: Limits,
        ): LineSlicePlan {
            val bounds = (listOf(0.0) + cuts + geometry.lastParam).distinct().sorted()
            val spans = if (bounds.size == 1) listOf(LineSliceSpan(bounds[0], bounds[0], false, true))
                else bounds.zipWithNext().mapIndexed { index, (from, to) ->
                    LineSliceSpan(from, to, false, index == bounds.lastIndex - 1)
                }
            require(spans.size <= limits.maxPieces) { "Line slice piece budget exceeded" }
            return LineSlicePlan(CapturedStrokeRules.snapshot(record), geometry, spans,
                cuts.toList(), pairTests)
        }

        private fun requireOrdinaryRecord(record: StrokeRecord) {
            require(record.capturedPhysicalSource == null) { "Captured records cannot be sliced" }
        }

        private fun validateRecordSamples(record: StrokeRecord) {
            require(record.samples.all { it.isPlaceable }) {
                "Original stroke samples must have finite positions and times"
            }
        }

        private fun snapshotAndValidate(line: InkLine): InkLine {
            require(line.pointCount > 0)
            for (i in 0 until line.pointCount) {
                require(line.xs[i].isFinite() && line.ys[i].isFinite() &&
                    line.halfWidths[i].isFinite() && line.halfWidths[i] >= 0.0) { "Invalid InkLine coordinate or width" }
                if (i > 0) {
                    val dx = line.xs[i] - line.xs[i - 1]
                    val dy = line.ys[i] - line.ys[i - 1]
                    require(dx.isFinite() && dy.isFinite() && (dx * dx + dy * dy).isFinite()) {
                        "InkLine segment arithmetic exceeds finite range"
                    }
                }
            }
            return line.copyGeometry()
        }

        private fun validateCombinedArithmetic(target: InkLine, cutters: List<InkLine>) {
            var minX = target.xs.minOrNull()!!; var maxX = target.xs.maxOrNull()!!
            var minY = target.ys.minOrNull()!!; var maxY = target.ys.maxOrNull()!!
            for (line in cutters) {
                minX = min(minX, line.xs.minOrNull()!!); maxX = max(maxX, line.xs.maxOrNull()!!)
                minY = min(minY, line.ys.minOrNull()!!); maxY = max(maxY, line.ys.maxOrNull()!!)
            }
            val dx = maxX - minX; val dy = maxY - minY
            require(dx.isFinite() && dy.isFinite() && (dx * dx + dy * dy).isFinite() &&
                (2.0 * dx * dy).isFinite()) {
                "Line intersection coordinate range exceeds finite arithmetic"
            }
        }

        private fun safeProduct(a: Long, b: Long): Long =
            if (a == 0L || b == 0L) 0L else if (a > Long.MAX_VALUE / b) Long.MAX_VALUE else a * b

        private fun safeSum(a: Long, b: Long): Long =
            if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
    }
}

private fun InkLine.copyGeometry() = InkLine(id, xs.copyOf(), ys.copyOf(), halfWidths.copyOf())

private class ImmutableSliceList<T>(values: List<T>) : AbstractList<T>() {
    private val items = values.toList()
    override val size: Int get() = items.size
    override fun get(index: Int): T = items[index]
}

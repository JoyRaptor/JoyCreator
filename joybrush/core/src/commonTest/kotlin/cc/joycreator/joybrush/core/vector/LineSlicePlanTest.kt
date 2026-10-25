package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.Tool
import cc.joycreator.joybrush.core.stroke.CaptureDomain
import cc.joycreator.joybrush.core.stroke.CaptureRect
import cc.joycreator.joybrush.core.stroke.CapturedPhysicalSource
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.stroke.StrokeWashFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LineSlicePlanTest {
    private fun record(id: String = "line", samples: List<PenSample> = mutableListOf(
        PenSample(0f, 0f, 1.0, pressure = .25f, tilt = .4f, azimuth = -.8f,
            barrel = .3f, tool = Tool.STYLUS),
        PenSample(4f, 0f, 2.0, pressure = .9f, tilt = Float.NaN, azimuth = 1.2f,
            barrel = Float.NaN, tool = Tool.ERASER),
    )) = StrokeRecord(id, "brush", 918L, .37f, 2.5f, samples,
        colorArgb = 0x7F123456, widthScale = 1.7f, seq = 12L, locked = true, groupId = "g",
        washFrame = StrokeWashFrame(1.0, .2, 3.0, .1, 1.0, -2.0, .001, 0.0, 1.0))

    private fun line(id: String = "line", xs: DoubleArray, ys: DoubleArray = DoubleArray(xs.size),
                     widths: DoubleArray = DoubleArray(xs.size) { .75 }) = InkLine(id, xs, ys, widths)

    @Test fun zeroWidthCrossingsPartitionAtCrossingsTangenciesVerticesAndEndpoints() {
        val target = line(xs = doubleArrayOf(0.0, 4.0, 8.0))
        val cutters = listOf(
            line("cross", doubleArrayOf(2.0, 2.0), doubleArrayOf(-1.0, 1.0)),
            line("vertex", doubleArrayOf(4.0, 4.0), doubleArrayOf(-1.0, 1.0)),
            line("end", doubleArrayOf(0.0, 0.0), doubleArrayOf(-1.0, 1.0)),
            line("tangent", doubleArrayOf(6.0, 8.0), doubleArrayOf(0.0, 2.0)),
        )
        val plan = LineSlicePlan.crossings(record(), target, cutters)
        assertEquals(listOf(.5, 1.0, 1.5), plan.cutPositions)
        assertEquals(listOf(0.0, .5, 1.0, 1.5), plan.spans.map { it.from })
        assertEquals(listOf(.5, 1.0, 1.5, 2.0), plan.spans.map { it.to })
        assertTrue(plan.spans.all { !it.touched })
        assertTrue(plan.spans.last().ownsEnd)
        assertTrue(plan.spans.dropLast(1).none { it.ownsEnd })
        assertEquals(0.0, plan.geometry.pointAt(0.0).x)
        assertEquals(8.0, plan.geometry.pointAt(2.0).x)
    }

    @Test fun repeatedCutsDeduplicateAndCollinearOverlapAddsBothBoundaries() {
        val target = line(xs = doubleArrayOf(0.0, 10.0))
        val cutter = line("overlap", doubleArrayOf(3.0, 7.0))
        val plan = LineSlicePlan.crossings(record(), target, listOf(cutter, cutter))
        assertEquals(listOf(.3, .7), plan.cutPositions)
        assertEquals(3, plan.spans.size)
        assertEquals(2L, plan.pairTests)
    }

    @Test fun dotHasOneDegenerateFullDomainSpan() {
        val target = line(xs = doubleArrayOf(3.0))
        val plan = LineSlicePlan.crossings(record(), target, listOf(line("dot-cutter", doubleArrayOf(3.0))))
        assertTrue(plan.cutPositions.isEmpty())
        assertEquals(listOf(LineSliceSpan(0.0, 0.0, false, true)), plan.spans)
        val touched = LineSlicePlan.touchedIntervals(record(), target, listOf(LineSliceInterval(0.0, 0.0)))
        assertEquals(listOf(LineSliceSpan(0.0, 0.0, true, true)), touched.spans)
        val kept = LineSlicePlan.touchedIntervals(record(), target, emptyList())
        assertEquals(listOf(LineSliceSpan(0.0, 0.0, false, true)), kept.spans)
    }

    @Test fun touchedIntervalsMergeAndPartitionTheWholeDomainWithHalfOpenOwnership() {
        val plan = LineSlicePlan.touchedIntervals(record(), line(xs = DoubleArray(11) { it.toDouble() }),
            listOf(LineSliceInterval(2.0, 4.0), LineSliceInterval(3.0, 6.0), LineSliceInterval(8.0, 10.0)))
        assertEquals(listOf(
            LineSliceSpan(0.0, 2.0, false, false), LineSliceSpan(2.0, 6.0, true, false),
            LineSliceSpan(6.0, 8.0, false, false), LineSliceSpan(8.0, 10.0, true, true),
        ), plan.spans)
        assertEquals(10.0, plan.spans.sumOf { it.to - it.from })
    }

    @Test fun planningSnapshotsGeometryAndEveryOriginalRecipeFieldWithoutResampling() {
        val samples = mutableListOf(PenSample(1f, 2f, 3.0, .2f, .3f, .4f, .5f, Tool.MOUSE))
        val original = record(samples = samples)
        val xs = doubleArrayOf(1.0, 9.0)
        val ys = doubleArrayOf(2.0, 3.0)
        val widths = doubleArrayOf(.4, .8)
        val plan = LineSlicePlan.touchedIntervals(original, line(xs = xs, ys = ys, widths = widths), emptyList())
        xs[0] = 999.0; ys[0] = 999.0; widths[0] = 999.0
        samples.clear()
        assertEquals(original.copy(samples = listOf(PenSample(1f, 2f, 3.0, .2f, .3f, .4f, .5f, Tool.MOUSE))), plan.record)
        assertEquals(1.0, plan.geometry.xs[0])
        val escaped = plan.geometry
        escaped.xs[0] = -100.0
        assertEquals(1.0, plan.geometry.xs[0])
        assertEquals(1, plan.record.samples.size)
        assertEquals(original.seed, plan.record.seed)
        assertEquals(original.washFrame, plan.record.washFrame)
    }

    @Test fun finiteGeometryAndIntervalDomainAreValidated() {
        assertFailsWith<IllegalArgumentException> {
            LineSlicePlan.crossings(record(), line(xs = doubleArrayOf(0.0, Double.NaN)), emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            LineSlicePlan.crossings(record(), line(xs = doubleArrayOf(0.0, 1.0), widths = doubleArrayOf(1.0, Double.POSITIVE_INFINITY)), emptyList())
        }
        val target = line(xs = doubleArrayOf(0.0, 10.0))
        for (range in listOf(LineSliceInterval(-1.0, 1.0), LineSliceInterval(2.0, 11.0),
            LineSliceInterval(3.0, 3.0), LineSliceInterval(Double.NaN, 4.0))) {
            assertFailsWith<IllegalArgumentException> {
                LineSlicePlan.touchedIntervals(record(), target, listOf(range))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            LineSlicePlan.crossings(record("other"), target, emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            LineSlicePlan.crossings(record(samples = listOf(PenSample(Float.NaN, 0f, 0.0))), target, emptyList())
        }
    }

    @Test fun pairAndPieceBudgetsFailWithoutReturningPartialPlans() {
        val target = line(xs = DoubleArray(1003) { it.toDouble() })
        val cutter = line("many", DoubleArray(1003) { it.toDouble() }, DoubleArray(1003) { 2.0 })
        assertFailsWith<IllegalArgumentException> {
            LineSlicePlan.crossings(record(), target, listOf(cutter))
        }
        assertFailsWith<IllegalArgumentException> {
            LineSlicePlan.crossings(record(), line(xs = doubleArrayOf(0.0, 10000.0)),
                (1..5).map { i -> line("$i", doubleArrayOf(i * 100.0, i * 100.0), doubleArrayOf(-1.0, 1.0)) },
                LineSlicePlan.Limits(maxPairTests = 100, maxPieces = 4))
        }
        assertFailsWith<IllegalArgumentException> {
            LineSlicePlan.touchedIntervals(record(), line(xs = doubleArrayOf(0.0, 10.0)),
                listOf(LineSliceInterval(.1, .2), LineSliceInterval(.4, .5)),
                LineSlicePlan.Limits(maxPieces = 3))
        }
        assertFailsWith<IllegalArgumentException> {
            LineSlicePlan.crossings(record(), line(xs = doubleArrayOf(0.0, 1.0, 2.0, 3.0)), emptyList(),
                LineSlicePlan.Limits(maxInputPoints = 5))
        }
        assertFailsWith<IllegalArgumentException> {
            LineSlicePlan.touchedIntervals(record(), line(xs = doubleArrayOf(0.0, 1.0)),
                listOf(LineSliceInterval(.1, .2), LineSliceInterval(.4, .5)),
                LineSlicePlan.Limits(maxInputIntervals = 1))
        }
    }

    @Test fun capturedRecordsRefuseBeforeEmptyOrDotShortcuts() {
        val captured = record().copy(capturedPhysicalSource = CapturedPhysicalSource(
            CaptureDomain(CaptureRect(0, 0, 2, 2))))
        assertFailsWith<IllegalArgumentException> {
            LineSlicePlan.crossings(captured, line(xs = doubleArrayOf(0.0)), emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            LineSlicePlan.touchedIntervals(captured, line(xs = doubleArrayOf(0.0)), emptyList())
        }
    }

    @Test fun oversizedInputsStopBeforeReadingTheUnboundedTailOrCopyingInvalidGeometry() {
        val cutters = object : AbstractList<InkLine>() {
            override val size: Int get() = Int.MAX_VALUE
            override fun get(index: Int): InkLine {
                check(index == 0) { "Budget check traversed an unbounded input tail" }
                return line("over-budget", doubleArrayOf(0.0, Double.NaN))
            }
        }
        val failure = assertFailsWith<IllegalArgumentException> {
            LineSlicePlan.crossings(record(), line(xs = doubleArrayOf(0.0, 1.0)), cutters,
                LineSlicePlan.Limits(maxInputPoints = 5))
        }
        assertTrue(failure.message.orEmpty().contains("input point budget"))
        val intervals = object : AbstractList<LineSliceInterval>() {
            override val size: Int get() = 1
            override fun get(index: Int): LineSliceInterval = error("Over-budget intervals were visited")
        }
        assertFailsWith<IllegalArgumentException> {
            LineSlicePlan.touchedIntervals(record(), line(xs = doubleArrayOf(0.0, 1.0)), intervals,
                LineSlicePlan.Limits(maxInputIntervals = 0))
        }
    }

    @Test fun repeatedCallsAreDeterministicAndDoNotTreatNearCutsAsSpecks() {
        val target = line(xs = doubleArrayOf(0.0, 1.0))
        val cutters = listOf(.1, .10000001, .9).mapIndexed { index, x ->
            line("$index", doubleArrayOf(x, x), doubleArrayOf(-1.0, 1.0))
        }
        val a = LineSlicePlan.crossings(record(), target, cutters)
        val b = LineSlicePlan.crossings(record(), target, cutters)
        assertEquals(a.cutPositions, b.cutPositions)
        assertEquals(a.spans, b.spans)
        assertEquals(4, a.spans.size)
        assertTrue(a.spans[1].to - a.spans[1].from > 0.0)
        assertNotEquals(a.spans[1].from, a.spans[1].to)
    }
}

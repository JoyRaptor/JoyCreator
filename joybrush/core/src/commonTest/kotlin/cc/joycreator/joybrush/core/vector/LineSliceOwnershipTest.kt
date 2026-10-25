package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.select.Homography
import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LineSliceOwnershipTest {
    private fun line(xs: DoubleArray, ys: DoubleArray = DoubleArray(xs.size)) =
        InkLine("line", xs, ys, DoubleArray(xs.size) { 1.0 })

    private fun plan(geometry: InkLine, cuts: List<Pair<Double, Double>> = emptyList()): LineSlicePlan =
        LineSlicePlan.touchedIntervals(
            StrokeRecord("line", "ink", 42L, .2f, 1f, listOf(PenSample(0f, 0f, 0.0))),
            geometry,
            cuts.map { LineSliceInterval(it.first, it.second) },
        )

    @Test fun selfCrossingsAndRetracesChooseTheEarliestFractionalParameter() {
        val crossing = LineSliceOwnership(plan(line(
            doubleArrayOf(0.0, 2.0, 0.0, 2.0), doubleArrayOf(0.0, 2.0, 2.0, 0.0)),
            listOf(.5 to 1.5)))
        val owner = crossing.ownerAtCanonical(Pt(1.0, 1.0))
        assertEquals(.5, owner.parameter, 1e-12)
        assertEquals(1, owner.spanIndex, "a boundary belongs to the span starting there")

        val retrace = LineSliceOwnership(plan(line(doubleArrayOf(0.0, 2.0, 0.0))))
        assertEquals(.5, retrace.ownerAtCanonical(Pt(1.0, 0.0)).parameter, 1e-12)
    }

    @Test fun zeroLengthSegmentsVerticesEndpointsAndHalfOpenBoundariesHaveOneOwner() {
        val geometry = line(doubleArrayOf(0.0, 0.0, 2.0))
        val ownership = LineSliceOwnership(plan(geometry, listOf(1.0 to 1.5)))
        assertEquals(0.0, ownership.ownerAtCanonical(Pt(0.0, 0.0)).parameter)
        val interior = ownership.ownerAtCanonical(Pt(.5, 0.0))
        assertEquals(1.25, interior.parameter)
        assertEquals(1, interior.spanIndex)
        val split = LineSliceOwnership(plan(line(doubleArrayOf(0.0, 1.0, 2.0)), listOf(1.0 to 1.5)))
        val atVertex = split.ownerAtCanonical(Pt(1.0, 0.0))
        assertEquals(1.0, atVertex.parameter)
        assertEquals(1, atVertex.spanIndex)
        assertEquals(0, split.ownerAtCanonical(Pt(0.0, 0.0)).spanIndex)
        val end = split.ownerAtCanonical(Pt(2.0, 0.0))
        assertEquals(2.0, end.parameter)
        assertEquals(split.spanCount - 1, end.spanIndex)
    }

    @Test fun dotOwnsZeroAndFiniteQueriesOutsideAnyOpticalHaloStillMapToOneSpan() {
        val dot = LineSliceOwnership(plan(line(doubleArrayOf(-3.0), doubleArrayOf(4.0))))
        assertEquals(0.0, dot.ownerAtCanonical(Pt(-3.0, 4.0)).parameter)
        assertEquals(0, dot.ownerAtCanonical(Pt(1e100, -1e100)).spanIndex)

        val ownership = LineSliceOwnership(plan(line(doubleArrayOf(-2.0, 2.0)), listOf(.5 to .75)))
        assertEquals(0, ownership.ownerAtCanonical(Pt(-1e4, -1e4)).spanIndex)
        assertEquals(ownership.spanCount - 1, ownership.ownerAtCanonical(Pt(1e4, 1e4)).spanIndex)
        assertFailsWith<IllegalArgumentException> { ownership.ownerAtCanonical(Pt(Double.NaN, 0.0)) }
        assertFailsWith<IllegalArgumentException> { ownership.ownerAtCanonical(Pt(Double.MAX_VALUE, 0.0)) }
    }

    @Test fun documentQueriesUseOneCapturedInverseForExactQuarterTurnAndIntegerTranslation() {
        val plan = plan(line(doubleArrayOf(0.0, 10.0)), listOf(.5 to .8))
        val transform = Homography(doubleArrayOf(0.0, -1.0, 30.0, 1.0, 0.0, -7.0, 0.0, 0.0, 1.0))
        val sourcePoint = Pt(7.0, 2.0)
        val documentPoint = transform.apply(sourcePoint)
        val expected = LineSliceOwnership(plan).ownerAtCanonical(sourcePoint)
        val ownership = LineSliceOwnership(plan, transform)
        transform.m[2] += 1000.0 // mutable source matrix cannot change the already captured inverse
        val actual = ownership.ownerAtDocument(documentPoint)
        assertEquals(expected.spanIndex, actual.spanIndex)
        assertEquals(expected.parameter, actual.parameter, 1e-10)
    }

    @Test fun sampledGridHasExactlyOneBinaryOwnerAndReconstructsSourceOverOnce() {
        val ownership = LineSliceOwnership(plan(line(doubleArrayOf(0.0, 5.0, 10.0)), listOf(.4 to .9, 1.4 to 1.8)))
        val source = Rgba(.2, .1, .05, .4) // premultiplied
        val backdrop = Rgba(.08, .12, .16, .5) // premultiplied
        for (x in -2..12) for (y in -2..2) {
            val owner = ownership.ownerAtCanonical(Pt(x.toDouble(), y.toDouble()))
            // Independent scalar projection onto this straight 0,5,10-x grid.
            val expectedParameter = (x.toDouble() / 5.0).coerceIn(0.0, 2.0)
            assertEquals(expectedParameter, owner.parameter, 1e-12)
            val expectedIndex = when {
                expectedParameter < .4 -> 0
                expectedParameter < .9 -> 1
                expectedParameter < 1.4 -> 2
                expectedParameter < 1.8 -> 3
                else -> 4
            }
            assertEquals(expectedIndex, owner.spanIndex)
            val parameter = expectedParameter
            val actualOwners = ownership.spans.count { span ->
                parameter >= span.from && (parameter < span.to || (span.ownsEnd && parameter == span.to))
            }
            assertEquals(1, actualOwners, "one owner at ($x,$y)")
            val reconstructed = ownership.spans.mapIndexed { index, _ ->
                if (index == owner.spanIndex) source else Rgba(0.0, 0.0, 0.0, 0.0)
            }.fold(Rgba(0.0, 0.0, 0.0, 0.0), ::sourceOver)
            assertEquals(source, reconstructed)
            assertEquals(multiplyOver(source, backdrop), multiplyOver(reconstructed, backdrop))
        }
    }

    @Test fun fractionalHalfMasksAreNotOneHotAndLoseSourceAlpha() {
        val source = Rgba(.2, .1, .05, .4)
        val half = source.scale(.5)
        val split = sourceOver(half, half)
        assertEquals(.36, split.a, 1e-12)
        assertTrue(split.a < source.a)
    }

    @Test fun geometryAndPerQueryWorkLimitsAreChecked() {
        val many = plan(line(DoubleArray(6) { it.toDouble() }))
        assertFailsWith<IllegalArgumentException> {
            LineSliceOwnership(many, limits = LineSliceOwnership.Limits(maxSegmentTests = 4))
        }
        assertFailsWith<IllegalArgumentException> {
            LineSliceOwnership(many).ownerAtCanonical(Pt(Double.MAX_VALUE, 0.0))
        }
    }

    @Test fun nonFiniteQueriesSingularFramesAndProjectivePolesAreRefused() {
        val source = plan(line(doubleArrayOf(0.0, 1.0)))
        val ownership = LineSliceOwnership(source)
        assertFailsWith<IllegalArgumentException> { ownership.ownerAtCanonical(Pt(Double.NaN, 0.0)) }
        assertFailsWith<IllegalArgumentException> { ownership.ownerAtDocument(Pt(0.0, Double.POSITIVE_INFINITY)) }
        assertFailsWith<IllegalArgumentException> { LineSliceOwnership(source, Homography(DoubleArray(9))) }
        val projective = LineSliceOwnership(source,
            Homography(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0)))
        assertFailsWith<IllegalArgumentException> { projective.ownerAtDocument(Pt(1.0, 0.0)) }
    }

    @Test fun defaultFrameIsFreshEvenIfTheSharedIdentityArrayWasModified() {
        val previous = Homography.IDENTITY.m.copyOf()
        try {
            Homography.IDENTITY.m[2] = 1000.0
            val ownership = LineSliceOwnership(plan(line(doubleArrayOf(0.0, 1.0))))
            assertEquals(.25, ownership.ownerAtDocument(Pt(.25, 0.0)).parameter, 1e-12)
        } finally {
            previous.copyInto(Homography.IDENTITY.m)
        }
    }

    @Test fun nonzeroUnderflowingGeometryAndDistancesAreRefusedInsteadOfGuessingOwners() {
        val tiny = LineSliceOwnership(plan(line(doubleArrayOf(0.0, 1e-200))))
        assertFailsWith<IllegalArgumentException> { tiny.ownerAtCanonical(Pt(5e-201, 0.0)) }
        val ordinary = LineSliceOwnership(plan(line(doubleArrayOf(0.0, 1.0))))
        assertFailsWith<IllegalArgumentException> { ordinary.ownerAtCanonical(Pt(.5, 1e-200)) }
    }

    private data class Rgba(val r: Double, val g: Double, val b: Double, val a: Double) {
        fun scale(value: Double) = Rgba(r * value, g * value, b * value, a * value)
    }

    /** Independent scalar premultiplied source-over oracle. */
    private fun sourceOver(src: Rgba, dst: Rgba): Rgba {
        val remain = 1.0 - src.a
        return Rgba(src.r + dst.r * remain, src.g + dst.g * remain,
            src.b + dst.b * remain, src.a + dst.a * remain)
    }

    /** Premultiplied multiply blend, applied once after the disjoint piece source is reconstructed. */
    private fun multiplyOver(src: Rgba, dst: Rgba): Rgba {
        val remainSrc = 1.0 - src.a
        val remainDst = 1.0 - dst.a
        return Rgba(src.r * dst.r + src.r * remainDst + dst.r * remainSrc,
            src.g * dst.g + src.g * remainDst + dst.g * remainSrc,
            src.b * dst.b + src.b * remainDst + dst.b * remainSrc,
            src.a + dst.a * remainSrc)
    }

}

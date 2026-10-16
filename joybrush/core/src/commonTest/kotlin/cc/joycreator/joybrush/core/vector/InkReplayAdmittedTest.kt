package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.*
import cc.joycreator.joybrush.core.grain.GrainMath
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.StrokeSmoother
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.DabLook
import cc.joycreator.joybrush.core.paint.DabPlacer
import cc.joycreator.joybrush.core.stroke.CaptureDomain
import cc.joycreator.joybrush.core.stroke.CapturedPhysicalSource
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.test.*

/** CPU stream equivalence/admission only; no GPU, live capture or retained-history memory claim. */
class InkReplayAdmittedTest {
    private fun dynamic(base: Float, input: BrushInput = BrushInput.pressure) =
        Param(base, listOf(InputCurve(input, listOf(listOf(0f, .2f), listOf(1f, .95f)))))
    private fun stamp(version: Int = BRUSH_VERSION) = BrushPreset(
        id = "dynamic", name = "Dynamic", version = version, size = dynamic(12f),
        spacing = .17f, sizeJitter = .19f, angleJitter = 17f, flow = dynamic(.8f),
        opacity = dynamic(.7f), accumulate = "buildup",
        tip = TipSpec(angle = dynamic(31f), hardness = dynamic(.85f),
            aspectDynamics = if (version >= VERSION_CONTACT) dynamic(.5f) else null,
            anchorDynamics = if (version >= VERSION_CONTACT) dynamic(.6f) else null),
        tipTexture = GrainSpec(enabled = true, depth = dynamic(.75f)),
        paperGrain = GrainSpec(enabled = true, depth = dynamic(.65f)),
        paper = PaperResponse(influence = .4f, directional = .3f, wet = .2f),
        response = ResponseSpec(pressure = listOf(.8f, .1f, .9f, .4f), tilt = listOf(.3f, .2f, .7f, .8f)),
        scatter = ScatterSpec(amount = dynamic(.35f, BrushInput.tilt), count = 4, countJitter = .45f),
    )
    private fun rawSamples() = (0..11).map { i -> PenSample(
        x = if (i < 7) i * 6f else (14 - i) * 6f,
        y = when (i % 4) { 0 -> -4f; 1 -> 3f; 2 -> 12f; else -> 4f },
        timeMs = i * 8.25,
        pressure = .15f + i * .06f,
        tilt = if (i % 5 == 0) Float.fromBits(0x7fc01234) else .1f + i * .065f,
        azimuth = if (i % 3 == 0) Float.NaN else i * .47f,
        barrel = if (i % 4 == 0) -0.0f else i * .31f,
    ) }
    private fun record(brush: BrushPreset, smoothing: Float = .6f, zoom: Float = 1f,
                       width: Float = 1f, samples: List<PenSample> = rawSamples()) =
        StrokeRecord("raw", brush.id, 914L, smoothing, zoom, samples, widthScale = width)

    private fun dabBits(d: Dab) = floatArrayOf(d.x, d.y, d.radius, d.angle, d.flow, d.cap,
        d.pressure, d.tilt, d.azimuth, d.travelX, d.travelY, d.aspect, d.hardness,
        d.tipDepth, d.paperDepth, d.anchor, d.band0, d.band1, d.band2,
        d.bandWeight0, d.bandWeight1, d.bandWeight2).map { it.toRawBits() }
    private fun assertDabs(expected: List<Dab>, actual: List<Dab>, label: String = "") {
        assertEquals(expected.size, actual.size, label)
        for (i in expected.indices) {
            assertEquals(dabBits(expected[i]), dabBits(actual[i]), "$label dab $i float bits")
            assertEquals(expected[i].travelKnown, actual[i].travelKnown, "$label dab $i travel")
            assertEquals(expected[i].sampleIndex, actual[i].sampleIndex, "$label dab $i image")
        }
    }
    private fun grainBits(g: GrainMath.GrainUniforms) =
        floatArrayOf(g.pitchPx, g.depth, g.edge, g.tiltGradient, g.radial).map { it.toRawBits() }
    private fun assertDescriptor(expected: InkReplay.Replayed, actual: InkReplay.Replayed) {
        assertEquals(expected.strokeHardness.toRawBits(), actual.strokeHardness.toRawBits())
        assertEquals(expected.strokeOpacity.toRawBits(), actual.strokeOpacity.toRawBits())
        assertEquals(grainBits(expected.strokeGrain.tip), grainBits(actual.strokeGrain.tip))
        assertEquals(grainBits(expected.strokeGrain.paper), grainBits(actual.strokeGrain.paper))
        assertEquals(expected.strokeGrain.tip.asset, actual.strokeGrain.tip.asset)
        assertEquals(expected.strokeGrain.paper.asset, actual.strokeGrain.paper.asset)
        val a = expected.strokeGrain.paperResponse; val b = actual.strokeGrain.paperResponse
        assertEquals(listOf(a.influence, a.directional, a.wet).map { it.toRawBits() },
            listOf(b.influence, b.directional, b.wet).map { it.toRawBits() })
    }
    private fun sampleBits(s: PenSample) =
        floatArrayOf(s.x, s.y, s.pressure, s.tilt, s.azimuth, s.barrel).map { it.toRawBits() }

    @Test fun admittedStampMatchesExistingReplayBitForBitAcrossSmoothingZoomWidthAndContactVersions() {
        for (version in listOf(6, BRUSH_VERSION)) for (amount in listOf(0f, .6f, 1f))
            for (zoom in listOf(.125f, 1f, 12f)) for (width in listOf(.2f, 1f, 3.75f)) {
                val brush = stamp(version)
                val raw = record(brush, amount, zoom, width)
                val expected = InkReplay.replay(raw, brush)
                val visited = arrayListOf<Dab>()
                val actual = InkReplay.replayAdmitted(raw, brush) { visited += it }
                val label = "version=$version smoothing=$amount zoom=$zoom width=$width"
                assertTrue(expected.dabs.size > 10, label)
                assertTrue(expected.dabs.map { it.radius.toRawBits() }.toSet().size > 1, label)
                assertDabs(expected.dabs, actual.dabs, label)
                assertDabs(expected.dabs, visited, "$label admission")
                assertDescriptor(expected, actual)
                for (i in visited.indices) assertSame(visited[i], actual.dabs[i])
            }
        val brush = stamp()
        val raw = record(brush)
        val a = InkReplay.replayAdmitted(raw, brush) { }.dabs.map(::dabBits)
        val b = InkReplay.replayAdmitted(raw.copy(seed = raw.seed + 1), brush) { }.dabs.map(::dabBits)
        assertNotEquals(a, b) // The fixture is sensitive to the actual dabber/scatter seed streams.
    }

    @Test fun finalSmootherPointSinksMatchExistingListsIncludingCatchupAndDroppedSamples() {
        val input = rawSamples() + listOf(PenSample(100f, 10f, 100.0, predicted = true),
            PenSample(Float.NaN, 0f, 110.0), PenSample(22.25f, -3.75f, 120.0))
        for (amount in listOf(0f, .6f, 1f)) for (zoom in listOf(.125f, 1f, 12f)) {
            val old = StrokeSmoother(amount, zoom)
            val expected = input.flatMap { old.add(it) } + old.finish()
            val stream = StrokeSmoother(amount, zoom)
            val actual = arrayListOf<PenSample>()
            for (sample in input) stream.addTo(sample) { actual += it }
            stream.finishTo { actual += it }
            stream.finishTo { fail("Finished smoother must not emit twice") }
            assertEquals(old.droppedSamples, stream.droppedSamples)
            assertEquals(1, stream.droppedSamples)
            assertEquals(expected.size, actual.size)
            assertTrue(actual.isNotEmpty())
            for (i in expected.indices) {
                assertEquals(sampleBits(expected[i]), sampleBits(actual[i]), "sample $i smoothing=$amount zoom=$zoom")
                assertEquals(expected[i].timeMs.toRawBits(), actual[i].timeMs.toRawBits())
                assertEquals(expected[i].tool, actual[i].tool)
                assertEquals(expected[i].predicted, actual[i].predicted)
            }
        }
    }

    private fun inputs(dab: Dab) = DabInputs(pressure = dab.pressure, tilt = dab.tilt,
        speedPxPerS = Float.NaN, direction = Float.NaN, lean = Float.NaN, distancePx = Float.NaN,
        random = Float.NaN, strokeRandom = Float.NaN, barrel = Float.NaN)

    @Test fun placerAndBothScatterSinkFormsKeepBatchingFieldsAndExactRandomDrawCount() {
        val brush = stamp()
        val seed = 101L
        val oldDabber = BrushDabber(brush, seed)
        val expectedPlaced = DabPlacer(oldDabber.spacing, oldDabber::look).add(rawSamples())
        val streamDabber = BrushDabber(brush, seed)
        val placer = DabPlacer(streamDabber.spacing, streamDabber::look)
        val placed = arrayListOf<Dab>()
        for (chunk in rawSamples().chunked(3)) placer.addTo(chunk) { placed += it }
        assertDabs(expectedPlaced, placed)
        for (both in listOf(false, true)) for (jitter in listOf(0f, .65f)) {
            val spec = brush.scatter.copy(bothAxes = both, countJitter = jitter)
            val oldRng = SplitMix(seed xor Scatter.SALT)
            val expected = Scatter.expand(expectedPlaced, spec, oldRng, ::inputs)
            val streamRng = SplitMix(seed xor Scatter.SALT)
            val actual = arrayListOf<Dab>()
            var inputCalls = 0
            for (dab in placed) Scatter.expandTo(dab, spec, streamRng, { inputCalls++; inputs(it) }) { actual += it }
            assertDabs(expected, actual)
            assertEquals(placed.size, inputCalls)
            val nextDraw = oldRng.nextLong()
            assertEquals(nextDraw, streamRng.nextLong())
            val listRng = SplitMix(seed xor Scatter.SALT)
            val listOutput = arrayListOf<Dab>()
            Scatter.expandTo(placed, spec, listRng, ::inputs) { listOutput += it }
            assertDabs(expected, listOutput)
            assertEquals(nextDraw, listRng.nextLong())
        }
        val zero = Dab(-0.0f, -0.0f, 2f)
        Scatter.expandTo(zero, ScatterSpec(count = 4), SplitMix(7L), ::inputs) {
            assertSame(zero, it) // Zero offsets keep signed zero and the original object.
        }
    }

    @Test fun hugeTwoPointSegmentCanRefuseAtFortyFinalDabsWithoutFullExpansion() {
        val brush = BrushPreset(id = "tiny", name = "Tiny", size = Param(4f), spacing = .1f,
            scatter = ScatterSpec(amount = Param(.25f), count = 3))
        for (amount in listOf(0f, .6f, 1f)) for (zoom in listOf(.125f, 1f, 12f)) {
            val raw = record(brush, amount, zoom, 3f, listOf(
                PenSample(0f, 0f, 0.0), PenSample(1_000_000_000f, 0f, 1_000_000_000.0)))
            val refusal = IllegalStateException("tile admission refused")
            var calls = 0
            val actual = assertFails {
                InkReplay.replayAdmitted(raw, brush) { dab ->
                    assertEquals(6f.toRawBits(), dab.radius.toRawBits()) // Admission sees final width.
                    assertTrue(dab.x < 100f)
                    calls++
                    if (calls == 40) throw refusal
                }
            }
            assertSame(refusal, actual)
            assertEquals(40, calls)
        }
    }

    @Test fun sinkRefusalEscapesPlacerBeforeTheRestOfItsSegmentOrAnotherLook() {
        var looks = 0
        val placer = DabPlacer(spacing = .1f, look = { _, _, _ -> looks++; DabLook(1f) })
        val refusal = IllegalArgumentException("no more source tiles")
        var calls = 0
        val actual = assertFails {
            placer.addTo(listOf(PenSample(0f, 0f, 0.0), PenSample(1_000_000_000f, 0f, 1.0))) {
                calls++
                if (calls == 40) throw refusal
            }
        }
        assertSame(refusal, actual)
        assertEquals(40, calls)
        assertEquals(40, looks)
    }

    private val watercolor = BrushPreset(id = "wc", name = "Watercolor", engine = ENGINE_WATERCOLOUR,
        size = Param(1f), watercolour = WatercolourSpec(size = 1.0, minimum = 1.0, taper = 0.0,
            spacing = .5, smoothPath = 0.0, smoothPressure = 0.0, smoothTilt = 0.0, scatter = 0.0))
    private fun wash(end: Float) = record(watercolor, width = 1.7f,
        samples = listOf(PenSample(0f, 0f, 0.0), PenSample(end, 0f, 1.0)))

    @Test fun watercolorKeepsExactFieldsDescriptorAdmissionFailureAndExistingCapRefusal() {
        val raw = wash(24f)
        val expected = InkReplay.replay(raw, watercolor)
        val visited = arrayListOf<Dab>()
        val actual = InkReplay.replayAdmitted(raw, watercolor) { visited += it }
        assertTrue(expected.dabs.size > 12)
        assertDabs(expected.dabs, actual.dabs)
        assertDabs(expected.dabs, visited)
        assertDescriptor(expected, actual)
        val refusal = IllegalStateException("WC tile admission")
        var calls = 0
        assertSame(refusal, assertFails { InkReplay.replayAdmitted(raw, watercolor) {
            calls++; if (calls == 12) throw refusal
        } })
        assertEquals(12, calls)
        val capped = wash(3000f)
        val oldFailure = assertFailsWith<InkReplayException> { InkReplay.replay(capped, watercolor) }
        calls = 0
        val newFailure = assertFailsWith<InkReplayException> { InkReplay.replayAdmitted(capped, watercolor) { calls++ } }
        assertEquals(oldFailure.message, newFailure.message)
        assertEquals(0, calls) // The finite WC branch never returns an accepted truncated prefix.
    }

    @Test fun emptyTapAndUnusableSamplesKeepStartingDescriptorAndAllExistingRefusals() {
        val brush = stamp()
        for (samples in listOf(emptyList(), listOf(PenSample(-0.0f, 0f, 0.0)),
            listOf(PenSample(0f, 0f, 0.0, predicted = true), PenSample(Float.NaN, 0f, 1.0)))) {
            val raw = record(brush, samples = samples)
            val old = InkReplay.replay(raw, brush)
            val admitted = InkReplay.replayAdmitted(raw, brush) { }
            assertDabs(old.dabs, admitted.dabs)
            assertDescriptor(old, admitted)
        }
        val ordinary = record(brush)
        for ((raw, preset) in listOf(ordinary to null, ordinary to brush.copy(engine = "smudge"),
            ordinary.copy(capturedPhysicalSource = CapturedPhysicalSource(CaptureDomain())) to brush,
            wash(5f) to watercolor.copy(watercolour = null))) {
            val old = assertFailsWith<InkReplayException> { InkReplay.replay(raw, preset) }
            val actual = assertFailsWith<InkReplayException> { InkReplay.replayAdmitted(raw, preset) {
                fail("Refused source must not reach admission")
            } }
            assertEquals(old.message, actual.message)
        }
        val shaped = brush.copy(line = LineStyleSpec(taperOut = 2f))
        assertTrue(InkReplay.replay(ordinary, shaped).dabs.isNotEmpty()) // Existing shaping stays available.
        assertFailsWith<InkReplayException> { InkReplay.replayAdmitted(ordinary, shaped) {
            fail("Whole-stroke shaping cannot enter this streaming adapter")
        } }
    }
}

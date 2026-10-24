package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.*
import cc.joycreator.joybrush.core.input.*
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.DabPlacer
import cc.joycreator.joybrush.core.stroke.StrokeCodec
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** DRAFT — not compiled/run. Independent streamed live order versus BOTH replay entry points. */
class ActualStabilizerReplayTest {
    private val limits = ShapedReplayLimits(1000, 10000, 20000, 40000)
    private val raw = (0..35).map { i -> PenSample(i * 4f, ((i % 4) - 2) * 3f, 10.0 + i * 7.5,
        pressure = .1f + (i % 9) / 10f, tilt = if (i % 7 == 0) Float.NaN else .4f,
        azimuth = 2.9f - i * .02f, barrel = Float.NaN) }
    private fun brush() = BrushPreset(id = "frozen", name = "Test", size = Param(11f),
        tip = TipSpec(hardness = Param(.9f), angleSource = TipAngleSource.stroke),
        flow = Param(.63f), opacity = Param(.57f), spacing = .15f,
        response = ResponseSpec(pressure = listOf(.05f, .8f, .4f, .95f)),
        scatter = ScatterSpec(amount = Param(.12f), count = 2, countJitter = .3f),
        color = ColorJitter(dynamics = ColorDynamicsSpec(hueRangeDegrees = 20f, secondaryMix = .3f, pressureHueDegrees = 35f)),
        smoothing = .91f)
    private fun record(spec: StabilizerSpec?, zoom: Float, width: Float = 1f) = StrokeRecord(
        "contact", "frozen", 0x1ABCL, .27f, zoom, raw, colorArgb = 0xFFB24418.toInt(), widthScale = width,
        secondaryColorArgb = 0xFF1867CC.toInt(), stabilizer = spec)

    private fun scatterInputs(d: Dab) = DabInputs(d.pressure, d.tilt,
        speedPxPerS = Float.NaN, direction = Float.NaN, lean = Float.NaN, distancePx = Float.NaN,
        random = Float.NaN, strokeRandom = Float.NaN, barrel = Float.NaN)

    /** Captures the live contract: response BEFORE actual contact filter, final shaping on lift. */
    private fun live(r: StrokeRecord, p: BrushPreset, applyResponseTwice: Boolean = false): List<Dab> {
        val dabber = BrushDabber(p, r.seed, StrokeColors(r.colorArgb, r.secondaryColorArgb ?: -1))
        val placer = DabPlacer(spacing = dabber.spacing, look = dabber::look)
        val filter = StrokeStabilizer(r.stabilizer, r.smoothing, r.screenPerDoc)
        val placed = ArrayList<Dab>()
        val placedSink: (Dab) -> Unit = { placed += it }
        val released: (PenSample) -> Unit = { placer.addTo(it, placedSink) }
        // Chunks model native event batches. Neither mode nor brush suggestion is re-resolved here.
        for (batch in r.samples.chunked(3)) for (raw in batch) {
            val heard = p.response.apply(raw)
            filter.addTo(if (applyResponseTwice) p.response.apply(heard) else heard, released)
        }
        filter.finishTo(released)
        val scale = if (r.widthScale.isFinite()) r.widthScale else 1f
        val style = LineStyle.of(p.line)
        val diameter = p.size.base * scale
        val shaped = LineShaper.dash(LineShaper.taper(placed, style, diameter), style, diameter)
        val scattered = Scatter.expand(shaped, p.scatter, SplitMix(r.seed xor Scatter.SALT), ::scatterInputs)
        if (scale == 1f) return scattered
        return scattered.map { d ->
            val wanted = (if (d.radius.isFinite()) d.radius else 0f) * scale
            val radius = if (wanted.isFinite()) wanted.coerceIn(0f, DabPlacer.MAX_RADIUS_PX) else 0f
            if (radius == d.radius) d else d.copy(radius = radius)
        }
    }

    @Test fun everyActualModeMatchesLiveCodecAndUnshapedAdmittedReplayExactly() {
        val p = brush()
        for (mode in StabilizerMode.entries) for (zoom in listOf(.5f, 2f)) for (width in listOf(1f, 1.7f)) {
            val actual = StabilizerSpec(mode, .73f, 19f, 6)
            val r = StrokeCodec.decode(StrokeCodec.encode(record(actual, zoom, width)))
            val expected = live(r, p)
            val replay = InkReplay.replay(r, p)
            assertEquals(expected, replay.dabs, "$mode zoom$zoom width$width")
            val seen = ArrayList<Dab>()
            val admitted = InkReplay.replayAdmitted(r, p) { seen += it }
            assertEquals(expected, admitted.dabs)
            assertEquals(expected, seen)
            assertEquals(replay.strokeHardness.toRawBits(), admitted.strokeHardness.toRawBits())
            assertEquals(replay.strokeOpacity.toRawBits(), admitted.strokeOpacity.toRawBits())
            assertEquals(replay.strokeGrain, admitted.strokeGrain)
        }
    }

    @Test fun bothShapedPassesUseFrozenActualModeIncludingShortTwoEndedStrokeAndTap() {
        val p = brush().copy(line = LineStyleSpec(taperIn = 4f, taperOut = 7f, taperTip = .12f, taperShape = 2f,
            dashOn = 2.5f, dashOff = 1f))
        for (mode in StabilizerMode.entries) for (path in listOf(raw, raw.take(4), raw.take(1))) {
            val r = record(StabilizerSpec(mode, .7f, 13f, 5), 1.5f).copy(samples = path)
            val expected = live(r, p)
            assertEquals(expected, InkReplay.replay(r, p).dabs, "$mode length${path.size}")
            val admitted = InkReplay.replayAdmitted(r, p, null, null, limits) { }
            assertEquals(expected, admitted.dabs, "shaped admitted $mode length${path.size}")
        }
    }

    @Test fun suggestionsAndCurrentSmoothingCannotOverrideSavedActualSettings() {
        val r = record(StabilizerSpec(StabilizerMode.movingAverage, .9f, 5f, 8), 1f)
        val p = brush()
        val expected = InkReplay.dabs(r, p)
        for (suggestion in listOf(StabilizerSpec(StabilizerMode.off), StabilizerSpec(StabilizerMode.pulledString, 1f, 50f)))
            assertEquals(expected, InkReplay.dabs(r, p.copy(stabilizer = suggestion, smoothing = 0f)))
        val legacy = r.copy(stabilizer = null)
        assertEquals(live(legacy, p), InkReplay.dabs(legacy, p.copy(stabilizer = StabilizerSpec(StabilizerMode.off))))
    }

    @Test fun wrongModeAndDoubleResponseControlsActuallyChangeTheMark() {
        val p = brush()
        val r = record(StabilizerSpec(StabilizerMode.movingAverage, .9f, 13f, 8), 1f)
        val correct = InkReplay.dabs(r, p)
        assertNotEquals(correct, InkReplay.dabs(r.copy(stabilizer = StabilizerSpec(StabilizerMode.off)), p))
        assertNotEquals(correct, live(r, p, applyResponseTwice = true))
        assertTrue(correct.isNotEmpty())
    }

    @Test fun nullLegacyAndExplicitSmoothAtSameAmountRemainBitIdentical() {
        val p = brush()
        val legacy = record(null, 1f)
        val explicit = legacy.copy(stabilizer = StabilizerSpec(StabilizerMode.smooth, legacy.smoothing))
        assertEquals(live(legacy, p), live(explicit, p))
        assertEquals(InkReplay.dabs(legacy, p), InkReplay.dabs(explicit, p))
    }
}

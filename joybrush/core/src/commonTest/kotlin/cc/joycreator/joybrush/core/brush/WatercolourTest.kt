package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.input.PenSample
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.*

class WatercolourTest {
    private fun brush() = BrushPreset(id = "wc", name = "WC", size = Param(300f),
        engine = ENGINE_WATERCOLOUR, watercolour = WatercolourSpec(), blend = BLEND_MULTIPLY)
    private fun samples() = (0..100).map { i -> PenSample(i * 6.3f, 20f + i % 7,
        i * 8.0, pressure = (i % 19) / 18f, tilt = (i % 61 * PI / 180).toFloat()) }

    @Test fun determinismAndLow32SeedContract() {
        val s = samples()
        val a = WatercolourDabber(WatercolourSpec(), 78234).replay(s)
        assertTrue(a.size > 100)
        assertEquals(a, WatercolourDabber(WatercolourSpec(), 78234).replay(s))
        assertEquals(a, WatercolourDabber(WatercolourSpec(), 78234 + (1L shl 32)).replay(s))
        assertNotEquals(a, WatercolourDabber(WatercolourSpec(), 78235).replay(s))
    }

    @Test fun inputRadiansFineTipAndTiltWindow() {
        val d = WatercolourDabber(WatercolourSpec(), 12)
        assertEquals(1.0, d.diameter(0.0, 25.0), 1e-12)
        assertEquals(24.92, d.diameter(1.0, 40.0), 1e-10)
        assertEquals(300.0, d.diameter(1.0, 60.0), 1e-10)
        assertEquals(165.45, d.diameter(0.0, 60.0), 1e-10)
        assertEquals(0..7, d.dabWindow(0.0))
        assertEquals(0..7, d.dabWindow(40.0))
        assertEquals(17..24, d.dabWindow(60.0))
        val dab = d.begin(PenSample(0f, 0f, 0.0, 1f, (PI / 3).toFloat())).single()
        assertEquals(300f, dab.radius * 2, 1e-4f)
        assertTrue(dab.sampleIndex in 17..24)
        assertEquals((PI / 3).toFloat(), dab.tilt, 1e-6f)
    }

    @Test fun windowSupportsOtherAssetCounts() {
        val spec = WatercolourSpec(dabImages = (0 until 16).map { "dabs/$it.png" })
        val dabber = WatercolourDabber(spec, 1)
        assertEquals(8..15, dabber.dabWindow(60.0))
        assertEquals(0..7, dabber.dabWindow(0.0))
        assertTrue(dabber.replay(samples()).all { it.sampleIndex in 0..15 })
    }

    @Test fun modulationInversionsAndMinimumSpacing() {
        val d = WatercolourDabber(WatercolourSpec(), 0)
        val p = WatercolourDabber.Point(0.0, 0.0, .25, 50.0)
        assertEquals(1.0, d.modulation(p, WatercolourInputs()))
        val pressure = d.modulation(p, WatercolourInputs(pressure = true))
        val tilt = d.modulation(p, WatercolourInputs(tilt = true))
        assertEquals(pressure * tilt, d.modulation(p, WatercolourInputs(true, true)), 1e-12)
        assertEquals(1 - pressure, d.modulation(p, WatercolourInputs(pressure = true, invertPressure = true)), 1e-12)
        assertEquals(1 - tilt, d.modulation(p, WatercolourInputs(tilt = true, invertTilt = true)), 1e-12)
        assertEquals(.5, d.spacing(p.copy(pressure = 0.0)))
    }

    @Test fun drynessBandsPreserveTotalDepositAndReachFullyDryBand() {
        val dabs = WatercolourDabber(WatercolourSpec(), 78).replay(samples())
        for (dab in dabs) {
            assertEquals(1f, dab.bandWeight0 + dab.bandWeight1 + dab.bandWeight2, 1e-6f)
            assertEquals(dab.flow, 1 - (1 - dab.band0) * (1 - dab.band1) * (1 - dab.band2), 2e-6f)
            assertTrue(listOf(dab.x, dab.y, dab.radius, dab.angle, dab.flow, dab.band0, dab.band1, dab.band2).all { it.isFinite() })
        }
        val dry = WatercolourDabber(WatercolourSpec(dryness = 1.0), 78).begin(samples().first()).single()
        assertEquals(0f, dry.band0)
        assertEquals(0f, dry.band1)
        assertEquals(dry.flow, dry.band2)
        val noPaper = WatercolourDabber(WatercolourSpec(dryness = 1.0, paperCut = 0.0), 78)
            .begin(samples().first()).single()
        assertEquals(noPaper.flow, noPaper.band0)
        assertEquals(0f, noPaper.band2)
    }

    @Test fun stopCapAndBrokenInputCannotHangOrDuplicateEndpoint() {
        val d = WatercolourDabber(WatercolourSpec(), 2, maxDabs = 20)
        val first = PenSample(0f, 0f, 0.0, .1f)
        val out = d.begin(first) + d.add(PenSample(100000f, 0f, 1.0, .1f))
        assertEquals(20, out.size)
        assertTrue(d.capped)
        assertEquals(emptyList(), d.finish())
        assertEquals(emptyList(), d.add(first.copy(x = 1f)))
        val good = WatercolourDabber(WatercolourSpec(), 2)
        good.begin(first)
        assertEquals(emptyList(), good.add(first.copy(x = Float.NaN)))
        assertTrue(good.add(first.copy(x = 100f, timeMs = 2.0)).isNotEmpty())
        assertTrue(out.all { it.tilt == 0f }) // no hardware tilt means upright
    }

    @Test fun strokeOffsetsIndependentAndStable() {
        val a = WatercolourDabber(WatercolourSpec(), 42)
        val b = WatercolourDabber(WatercolourSpec(backdropOffset = 0.0), 42)
        assertTrue(a.backdropOffsetX in 0.0..1.0 && a.backdropOffsetY in 0.0..1.0)
        assertEquals(0.0, b.backdropOffsetX)
        assertEquals(a.replay(samples()), b.replay(samples()))
    }

    @Test fun formatVersionRoundTripAndLowestRequiredVersion() {
        val b = brush()
        assertEquals(emptyList(), BrushValidate.validate(b))
        assertEquals(b, BrushJson.decodeChecked(BrushJson.encode(b)))
        assertEquals(VERSION_WATERCOLOUR, BrushJson.decode(BrushJson.encode(b.copy(version = 1))).version)
        assertFailsWith<BrushException> { BrushJson.decode(BrushJson.encode(b).replace("\"version\": 10", "\"version\": 9")) }
        val old = BrushPreset(version = 1, id = "old", name = "Old", size = Param(4f))
        assertEquals(1, BrushJson.decode(BrushJson.encode(old)).version)
        assertEquals(null, BrushRules.refusalFor(ENGINE_WATERCOLOUR, LayerKind.INK))
    }

    @Test fun malformedWatercolourNeverSilentlyFallsBack() {
        val b = brush()
        fun refuses(spec: WatercolourSpec, text: String) {
            assertTrue(BrushValidate.validate(b.copy(watercolour = spec)).any { text in it }, text)
        }
        refuses(WatercolourSpec(window = 26), "window")
        refuses(WatercolourSpec(tipAngle = 60.0), "tipAngle")
        refuses(WatercolourSpec(opacity = Double.NaN), "opacity")
        refuses(WatercolourSpec(travel = Double.POSITIVE_INFINITY), "travel")
        refuses(WatercolourSpec(pressureBezier = emptyList()), "pressureBezier")
        refuses(WatercolourSpec(dabImages = listOf("../outside.png"), window = 1), "assets")
        assertTrue(BrushValidate.validate(b.copy(watercolour = null)).any { "needs a watercolour section" in it })
        assertTrue(BrushValidate.validate(b.copy(engine = "stamp")).any { "only read by" in it })
        assertTrue(BrushValidate.validate(b.copy(line = LineStyleSpec())).any { "does not support the line shaping" in it })
    }
}

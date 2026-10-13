package cc.joycreator.joybrush.core.chrome

import cc.joycreator.joybrush.core.brush.*
import kotlin.math.PI
import kotlin.test.*

class WatercolourKnobsTest {
    private fun brush(w: WatercolourSpec = WatercolourSpec()) = BrushPreset(
        id = "wc-test", name = "Watercolour", version = VERSION_WATERCOLOUR,
        engine = ENGINE_WATERCOLOUR, watercolour = w, size = Param(w.size.toFloat()),
        flow = Param(w.opacity.toFloat()), opacity = Param(w.strokeOpacity.toFloat()),
        spacing = w.spacing.toFloat(), blend = BLEND_MULTIPLY,
    )
    private fun knob(p: BrushPreset, key: String) = BrushKnobs.forBrush(p).single { it.key == key }
    private fun tune(p: BrushPreset, key: String, value: Float): BrushPreset =
        BrushTuning.apply(p, mapOf(p.id to mapOf(key to value)))
    private fun dabs(p: BrushPreset, pressure: Double = .4, tilt: Double = 50.0) =
        WatercolourDabber(p.watercolourRuntime(), 78234).begin(
            WatercolourDabber.Point(100.0, 100.0, pressure, tilt))

    @Test fun savedNoOpPreservesAllApprovedDoublesAndTheSeededDabs() {
        val p = brush()
        val positions = BrushKnobs.forBrush(p).associate { it.key to it.get(p) }
        val stored = BrushTuning.decode(BrushTuning.encode(mapOf(p.id to positions)))
        val out = BrushTuning.apply(p, stored)
        assertEquals(p, out)
        assertEquals(dabs(p), dabs(out))
        assertEquals(emptyList(), BrushValidate.validate(out))
    }

    @Test fun nativeCurvesChangeTheActualDabsAndRetainSampleSeed() {
        val p = brush()
        for (curve in listOf("pressure", "tilt")) {
            val out = tune(p, "response.$curve.1", 1f)
            assertNotEquals(dabs(p), dabs(out), "$curve must affect placement")
            assertEquals(dabs(out), dabs(out), "same seed must replay exactly")
            assertEquals(p.watercolour!!.sampleSeeds, out.watercolour!!.sampleSeeds)
            assertEquals(p.watercolour!!.dabImages, out.watercolour!!.dabImages)
            assertEquals(p.response, out.response, "generic curves are unused here")
            assertEquals(1f, BrushKnobs.curveHandles(out, curve)[1])
        }
    }

    @Test fun crossedHandlesAndInvalidSavedValuesCannotBreakTheLookupTable() {
        val p = brush()
        for (curve in listOf("pressure", "tilt")) {
            val out = BrushTuning.apply(p, mapOf(p.id to mapOf(
                "response.$curve.0" to 1f, "response.$curve.2" to 0f)))
            val h = BrushKnobs.curveHandles(out, curve)
            assertTrue(h[0] <= h[2])
            assertEquals(emptyList(), BrushValidate.validate(out))
            assertTrue(dabs(out).isNotEmpty())
            assertEquals(p, tune(p, "response.$curve.1", Float.NaN))
        }
    }

    @Test fun poolingAndBreakupControlsAreIndependentOfDabGeometry() {
        val p = brush()
        val w = p.watercolour!!
        val cases = listOf(
            "edgePool" to w.copy(edgePool = 1.0),
            "edgeAlpha" to w.copy(edgeAlpha = 1.0),
            "gravityPool" to w.copy(gravityPool = 1.0),
            "grain" to w.copy(grain = 1.0),
            "paperCut" to w.copy(paperCut = 1.0),
        )
        for ((field, expected) in cases) {
            val out = tune(p, "watercolour.$field", 1f)
            assertEquals(expected, out.watercolour, "$field changes one field")
            assertEquals(dabs(p), dabs(out), "$field belongs to finish, not geometry")
        }
    }

    @Test fun dedicatedControlsDriveRuntimeAndDoNotOfferDeadGenericKnobs() {
        val p = brush()
        val keys = BrushKnobs.forBrush(p).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.none { it.startsWith("stamp.") || it.startsWith("paper.") || it == "smoothing" })
        for ((key, read) in listOf<Pair<String, (WatercolourSpec) -> Double>>(
            "size" to { it.size }, "spacing" to { it.spacing },
            "opacity" to { it.opacity }, "strokeOpacity" to { it.strokeOpacity })) {
            val out = tune(p, "watercolour.$key", .5f)
            assertNotEquals(read(p.watercolourRuntime()), read(out.watercolourRuntime()))
            assertEquals(read(out.watercolour!!), read(out.watercolourRuntime()))
        }
        val out = tune(p, "watercolour.scatterInputs.invertPressure", 1f)
        assertTrue(out.watercolour!!.scatterInputs.invertPressure)
        assertEquals(p.watercolour!!.spacingInputs, out.watercolour!!.spacingInputs)
        assertNotEquals(dabs(p), dabs(out))
    }

    @Test fun smallestLegalBrushHasFiniteMinimumSliderAndValidEdits() {
        val p = brush(WatercolourSpec(size = .25, minimum = .25))
        val k = knob(p, "watercolour.minimum")
        assertEquals(0f, k.get(p))
        for (v in listOf(0f, .5f, 1f)) {
            assertEquals(p, k.set(p, v))
            assertEquals(emptyList(), BrushValidate.validate(k.set(p, v)))
        }
        assertTrue(BrushKnobs.forBrush(p).all { it.get(p).isFinite() })
    }

    @Test fun tiltMarkerIncludesTipAllowanceAndGenericReadingIsUnchanged() {
        val p = brush()
        fun radians(degrees: Double) = (degrees * PI / 180).toFloat()
        assertEquals(0f, BrushKnobs.tiltCurveInput(p, radians(25.0)))
        assertEquals(.5f, BrushKnobs.tiltCurveInput(p, radians(50.0)), 1e-6f)
        assertEquals(1f, BrushKnobs.tiltCurveInput(p, radians(70.0)))
        assertTrue(BrushKnobs.tiltCurveInput(p, Float.NaN).isNaN())
        assertTrue(BrushKnobs.tiltCurveInput(p, Float.POSITIVE_INFINITY).isNaN())
        val generic = BrushPreset(id = "ink", name = "Ink", size = Param(6f))
        val angle = radians(120.0)
        assertEquals(angle / (PI.toFloat() / 2f), BrushKnobs.tiltCurveInput(generic, angle))
        val out = tune(generic, "response.pressure.1", .8f)
        assertEquals(.8f, out.response.pressure[1])
        assertEquals(out.response.pressure, BrushKnobs.curveHandles(out, "pressure"))
    }
}

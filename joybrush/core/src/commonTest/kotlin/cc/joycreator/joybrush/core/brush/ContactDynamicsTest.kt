package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.grain.GrainMath
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.*
import kotlin.math.PI
import kotlin.test.*

class ContactDynamicsTest {
    private fun curve(input: BrushInput, vararg points: Pair<Float, Float>) =
        InputCurve(input, points.map { listOf(it.first, it.second) })

    private fun pencil(version: Int = VERSION_CONTACT): BrushPreset = BrushPreset(
        id = "contact-pencil", name = "Contact pencil", version = version,
        size = Param(3f, listOf(curve(BrushInput.tilt, 0f to 1f, 0.5f to 16f, 1f to 20f))),
        tip = TipSpec(
            hardness = Param(1f, listOf(curve(BrushInput.pressure, 0f to 0.2f, 1f to 0.95f))),
            aspectDynamics = Param(0f, listOf(curve(BrushInput.tilt, 0f to 0f, 0.5f to -0.86f, 1f to -0.9f)), "add"),
            anchorDynamics = Param(0f, listOf(curve(BrushInput.tilt, 0f to 0f, 0.5f to 1f, 1f to 1f)), "add"),
            angle = Param(0f, listOf(curve(BrushInput.lean, 0f to -180f, 1f to 180f)), "add"),
        ),
        paperGrain = GrainSpec(enabled = true, depth = Param(1f,
            listOf(curve(BrushInput.pressure, 0f to 0.15f, 1f to 0.9f)))),
    )

    @Test fun fortyFiveDegreesMakesLongSideWithoutLosingUprightPoint() {
        val b = BrushDabber(pencil(), 1)
        val point = b.look(PenSample(0f, 0f, 0.0, pressure = 0.5f, tilt = 0f, azimuth = 0f), 0f, 0)
        val side = b.look(PenSample(1f, 0f, 1.0, pressure = 0.5f, tilt = (PI / 4).toFloat(), azimuth = 0f), 1f, 1)
        assertEquals(1.5f, point.radius, 1e-4f)
        // x = 0.5 lies halfway between LUT samples 127/255 and 128/255.
        // A kink is averaged across those two sides: correction = (rightSlope-leftSlope)/1020.
        // These independent values preserve the production Curve's intentional 256-entry contract.
        assertEquals(24f + (8f - 30f) / 1020f * 1.5f, side.radius, 1e-4f)
        assertEquals(-0.86f + (-0.08f + 1.72f) / 1020f, side.aspect, 1e-4f)
        assertEquals(1f + (0f - 2f) / 1020f, side.anchor, 1e-4f)
        val shape = TipShape(aspect = side.aspect, hardness = 0.95f, anchor = side.anchor)
        assertTrue(TipMath.coverage(-24f, 0f, side.radius, 0f, shape) > 0.9f)
        assertEquals(0f, TipMath.coverage(5f, 0f, side.radius, 0f, shape), 1e-4f)
        assertEquals(0f, TipMath.coverage(-24f, 5f, side.radius, 0f, shape), 1e-4f)
    }

    @Test fun forceChangesPaperReachAndHardnessInsideOneStroke() {
        val b = BrushDabber(pencil(), 2)
        val soft = b.look(PenSample(0f, 0f, 0.0, pressure = 0.1f), 0f, 0)
        val firm = b.look(PenSample(1f, 0f, 1.0, pressure = 0.9f), 1f, 1)
        assertEquals(0.225f, soft.paperDepth, 1e-5f)
        assertEquals(0.825f, firm.paperDepth, 1e-5f)
        assertTrue(firm.hardness > soft.hardness)
        val tooth = 0.4f
        assertEquals(0f, GrainMath.heightCoverage(tooth, soft.paperDepth, 0.1f), 1e-5f)
        assertEquals(1f, GrainMath.heightCoverage(tooth, firm.paperDepth, 0.1f), 1e-5f)
        assertEquals(soft.paperDepth, b.strokeGrain.paper.depth, 1e-5f, "legacy fallback remains first-dab")
    }

    @Test fun oldVersionKeepsStrokeUniformsAndMissingChannelsUsePoint() {
        val old = BrushDabber(pencil().copy(version = 6, tip = TipSpec()), 3)
        assertTrue(old.look(PenSample(0f, 0f, 0.0), 0f, 0).paperDepth.isNaN())
        assertTrue(old.look(PenSample(1f, 0f, 1.0), 1f, 1).hardness.isNaN())
        val point = BrushDabber(pencil(), 3).look(PenSample(0f, 0f, 0.0), 0f, 0)
        assertEquals(1.5f, point.radius, 1e-4f)
        assertEquals(0f, point.aspect)
        assertEquals(0f, point.anchor)
        // Missing sensor channels use the upright bases; invalid overrides remain sentinels.
        val placer = DabPlacer(0.1f, { _, _, _ -> DabLook(2f, aspect = Float.POSITIVE_INFINITY,
            hardness = -2f, paperDepth = Float.NaN, anchor = 9f) })
        val d = placer.add(listOf(PenSample(0f, 0f, 0.0))).single()
        assertTrue(d.aspect.isNaN())
        assertTrue(d.paperDepth.isNaN())
        assertEquals(0f, d.hardness)
        assertEquals(1f, d.anchor)
        assertTrue(point.radius.isFinite())
        assertEquals(TipShape(), Dab(0f, 0f, 1f).tipShape(TipShape()))
    }

    @Test fun leanReversalMirrorsFootprintAroundPen() {
        val tip = TipShape(aspect = -0.8f, hardness = 0.8f, anchor = 1f)
        for (x in -40..40 step 2) for (y in -8..8 step 2) {
            val a = TipMath.coverage(x.toFloat(), y.toFloat(), 20f, 0f, tip)
            val b = TipMath.coverage(-x.toFloat(), -y.toFloat(), 20f, PI.toFloat(), tip)
            // fwidth forward differences differ under mirror only near the antialiased rim.
            if (a > 0.99f || b > 0.99f || (a == 0f && b == 0f)) assertEquals(a, b, 0.025f)
        }
        assertTrue(TipMath.coverage(-20f, 0f, 20f, 0f, tip) > 0.99f)
        assertTrue(TipMath.coverage(20f, 0f, 20f, PI.toFloat(), tip) > 0.99f)
    }

    @Test fun sideContactCrossingTileBorderIsNotClipped() {
        val d = Dab(270f, 30f, 24f, aspect = -0.86f, hardness = 0.95f, anchor = 1f)
        assertTrue(Tiles.key(0, 0) in Tiles.touchedBy(d))
        assertTrue(Tiles.key(1, 0) in Tiles.touchedBy(d))
        val c = RefCanvas()
        c.beginStroke("p", 0f, 0f, 0f, 1f, Accumulate.WASH, StrokeBlend.NORMAL, TipShape())
        c.addDabs(listOf(d)); c.endStroke()
        assertTrue(c.pixel("p", 246, 30)[3] > 0.9f)
        assertTrue(c.pixel("p", 258, 30)[3] > 0.5f)
        assertEquals(0f, c.pixel("p", 275, 30)[3])
    }

    @Test fun contactFormatRoundTripsAndRefusesOldVersionAndBadCurves() {
        val p = pencil()
        assertEquals(p, BrushJson.decodeChecked(BrushJson.encode(p)))
        assertEquals(7, BrushJson.decode(BrushJson.encode(p.copy(version = 1))).version)
        assertFailsWith<BrushException> { BrushJson.decode(BrushJson.encode(p).replace("\"version\": 7", "\"version\": 6")) }
        assertTrue(BrushValidate.validate(p.copy(tip = p.tip.copy(anchorDynamics = Param(-0.1f)))).any { "anchorDynamics" in it })
        assertTrue(BrushValidate.validate(p.copy(tip = p.tip.copy(aspectDynamics = Param(0f,
            listOf(curve(BrushInput.tilt, 0f to 0f, 1f to 1.2f)))))).any { "aspectDynamics" in it })
        assertTrue(BrushValidate.validate(p.copy(tip = p.tip.copy(anchorDynamics = Param(0f,
            listOf(InputCurve(BrushInput.pressure, listOf(listOf(0f), listOf(1f, 0.8f)))))))).any { "anchorDynamics" in it })
    }
}

package cc.joycreator.joybrush.core.grain

import cc.joycreator.joybrush.core.brush.*
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.DabPlacer
import cc.joycreator.joybrush.core.paint.DabTravel
import cc.joycreator.joybrush.core.paper.HexTile
import cc.joycreator.joybrush.core.paper.PaperTexture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaperDepositTest {
    private fun effective(slope: Float, vx: Float, wet: Float = 0f, height: Float = 0.5f, coarse: Float = 0.5f) =
        GrainMath.paperEffectiveHeight(floatArrayOf(slope, 0f, height, height * height), coarse, vx, 0f, 0.1f, 1f, wet)

    @Test fun rightToLeftLoadsTheEastFaceOfANorthSouthRidgeFirst() {
        // v=(-1,0): face=-dh/dx/range. East faces FALL toward +x, so they collect paint first.
        assertEquals(0.85f, effective(-0.1f, -1f), 1e-6f)
        assertEquals(0.15f, effective(0.1f, -1f), 1e-6f)
        assertEquals(0.15f, effective(-0.1f, 1f), 1e-6f)
        assertEquals(0.85f, effective(0.1f, 1f), 1e-6f)
        assertEquals(0.5f, effective(-0.1f, 0f))
        // Sign mutation changes these coverages, not merely a named constant.
        assertTrue(GrainMath.heightCoverage(effective(-0.1f, -1f), 0.5f, 0.3f) >
            GrainMath.heightCoverage(effective(0.1f, -1f), 0.5f, 0.3f))
    }
    @Test fun wetPaintRaisesPitsTowardTheirSurroundingsAndFadesDirection() {
        assertEquals(0.7f, effective(0f, 0f, wet = 1f, height = 0.2f, coarse = 0.7f), 1e-6f)
        assertTrue(effective(0f, 0f, wet = 1f, height = 0.2f, coarse = 0.7f) > effective(0f, 0f, height = 0.2f))
        assertEquals(effective(-0.1f, -1f, wet = 1f), effective(-0.1f, 1f, wet = 1f))
        assertEquals(0.675f, effective(-0.1f, -1f, wet = 0.5f), 1e-6f)
    }
    @Test fun coarseHeightAveragesTheActualHexSurfaceBChannel() {
        // Alternating B-only pits/peaks: every eight-texel box has mean 0.5 while slopes remain zero.
        val bytes = ByteArray(16 * 16 * 4) { i -> when (i % 4) {
            0, 1 -> 127.toByte(); 2 -> if ((i / 4) % 2 == 0) 0 else 255.toByte(); else -> 255.toByte()
        } }
        val tex = PaperTexture(16, 16, bytes)
        val sampled = FloatArray(4)
        var foundPit = false
        for (i in 0..30) {
            val x = i * 0.13
            val coarse = GrainMath.paperCoarseHeight(tex, x, 0.0, 16.0, false, 0.1f)
            assertEquals(0.5f, coarse, 0.03f) // Documented CPU box vs GPU coarse-mip tolerance.
            HexTile.sampleSurface(tex, x, 0.0, 16.0, false, 0.1f, sampled)
            if (sampled[2] < 0.3f) {
                foundPit = true
                assertTrue(GrainMath.paperEffectiveHeight(sampled, coarse, 0f, 0f, 0.1f, 0f, 1f) > sampled[2])
            }
        }
        assertTrue(foundPit, "the real hex read must exercise a pit rather than a synthetic scalar")
    }
    @Test fun responseFlowsToBothEnginesAndUniversalPaperLeavesTipTextureUntouched() {
        val response = PaperResponse(0.4f, 0.7f, 0.2f)
        val preset = BrushPreset(id = "test", name = "Test", size = Param(12f), paper = response,
            tipTexture = GrainSpec(enabled = true, image = "cloud_256.png", scale = 8f))
        val grain = BrushDabber(preset, 1L).strokeGrain
        assertEquals(response, grain.paperResponse)
        assertEquals(0.85f, grain.paper.depth); assertEquals(0.3f, grain.paper.edge)
        assertEquals(0f, grain.paper.tiltGradient); assertEquals(0f, grain.paper.radial)
        assertEquals(8f, grain.tip.pitchPx); assertEquals("cloud_256.png", grain.tip.asset)
        assertEquals(response, TuftStroke.shading(preset, 1L).paperResponse)
        assertFalse(GrainMath.strokeUniformsFor(preset.copy(paper = PaperResponse())).paper.enabled)
        val legacy = preset.copy(paperGrain = GrainSpec(enabled = true, depth = Param(0.62f), edge = 0.25f))
        assertEquals(0.62f, GrainMath.strokeUniformsFor(legacy).paper.depth)
        assertEquals(0.25f, GrainMath.strokeUniformsFor(legacy).paper.edge)
    }
    @Test fun travelIsUnitAndFirstDwellAndResetHaveNoDirection() {
        val travel = DabTravel()
        travel.update(100f, 80f); assertEquals(0f, travel.x); assertEquals(0f, travel.y)
        travel.update(103f, 84f); assertEquals(0.6f, travel.x); assertEquals(0.8f, travel.y)
        travel.update(103f, 84f); assertEquals(0f, travel.x); assertEquals(0f, travel.y)
        travel.update(100f, 84f); assertEquals(-1f, travel.x); assertEquals(0f, travel.y)
        travel.reset(); travel.update(-100f, 0f); assertEquals(0f, travel.x)
    }
    @Test fun actualTuftFootprintsCarryDocTravelBeforeTiltOffsets() {
        val preset = BrushPreset(id = "tuft-travel", name = "Tuft travel", engine = ENGINE_TUFT,
            size = Param(18f), paper = PaperResponse(0.5f, 1f, 0f))
        for (azimuth in listOf(1.5707964f, -1.5707964f)) {
            val samples = (0..80).map { i -> PenSample(x = i.toFloat(), y = 0f, timeMs = i * 2.0,
                pressure = 0.8f, tilt = 1f, azimuth = azimuth) }
            val stroke = TuftStroke(preset, 7L)
            val footprints = stroke.add(samples).filter { it.kind == cc.joycreator.joybrush.core.paint.TuftStamp.KIND_FOOTPRINT }
            assertTrue(footprints.size > 3)
            assertEquals(0f, footprints.first().travelX); assertEquals(0f, footprints.first().travelY)
            val moving = footprints.drop(1).filter { it.travelX != 0f || it.travelY != 0f }
            assertTrue(moving.isNotEmpty())
            for (stamp in moving) {
                assertEquals(1f, stamp.travelX, 1e-6f); assertEquals(0f, stamp.travelY, 1e-6f)
            }
            // The tilted belly and trailing tip do not define stroke direction.
            assertTrue(footprints.any { kotlin.math.abs(it.by - it.ay) > 0.01f })
            val dwell = stroke.dwell(samples.last().copy(timeMs = 500.0, pressure = 1f, azimuth = -azimuth))
            val dwellFootprints = dwell.filter { it.kind == cc.joycreator.joybrush.core.paint.TuftStamp.KIND_FOOTPRINT }
            assertTrue(dwellFootprints.isNotEmpty())
            for (stamp in dwellFootprints) {
                assertEquals(0f, stamp.travelX); assertEquals(0f, stamp.travelY)
            }
        }
    }
    @Test fun emittedTravelDoesNotDependOnSampleBatchingOrPenLean() {
        val samples = listOf(PenSample(x = 0f, y = 0f, timeMs = 0.0, pressure = 1f, azimuth = 2f),
            PenSample(x = 12f, y = 0f, timeMs = 20.0, pressure = 1f, azimuth = 2f),
            PenSample(x = 12f, y = 12f, timeMs = 40.0, pressure = 1f, azimuth = 2f))
        fun placer() = DabPlacer(spacing = 0.5f, radiusOf = { 2f })
        val all = placer().add(samples)
        val split = placer(); val batches = samples.flatMap { split.add(listOf(it)) }
        assertEquals(all, batches)
        assertTrue(all.all { it.travelKnown })
        assertEquals(0f, all.first().travelX); assertEquals(0f, all.first().travelY)
        assertEquals(1f, all[1].travelX); assertEquals(0f, all[1].travelY)
        assertEquals(0f, all.last().travelX); assertEquals(1f, all.last().travelY)
    }
}

package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.*
import cc.joycreator.joybrush.core.grain.GrainMath
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.StrokeSmoother
import cc.joycreator.joybrush.core.paint.DabPlacer
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.test.*

class InkReplayDescriptorTest {
    private fun dynamic(base: Float) = Param(base, listOf(
        InputCurve(BrushInput.pressure, listOf(listOf(0f, .15f), listOf(1f, .9f))),
    ))

    @Test fun descriptorKeepsTheLiveDabbersFirstDabUniforms() {
        // Legacy contact values are absent: the first-dab uniforms really control the GPU.
        val brush = BrushPreset(id = "grain", name = "Grain", version = 6,
            size = Param(18f), sizeJitter = .2f, spacing = .1f, accumulate = "buildup",
            tip = TipSpec(hardness = dynamic(.8f)), opacity = dynamic(.7f),
            tipTexture = GrainSpec(enabled = true, depth = dynamic(.9f)),
            paperGrain = GrainSpec(enabled = true, depth = dynamic(.6f)))
        val record = StrokeRecord("line", brush.id, 914L, .35f, 1.5f,
            (0..30).map { PenSample(it * 3f, it * 2f, it * 7.0,
                pressure = .2f + it * .02f, tilt = .4f, azimuth = .7f) })
        val live = BrushDabber(brush, record.seed)
        val smoother = StrokeSmoother(record.smoothing, record.screenPerDoc)
        val points = record.samples.flatMap { smoother.add(brush.response.apply(it)) } + smoother.finish()
        val expected = DabPlacer(spacing = live.spacing, look = live::look).add(points)
        val replay = InkReplay.replay(record, brush)
        assertTrue(expected.size > 2)
        assertEquals(expected, replay.dabs)
        assertEquals(live.strokeHardness, replay.strokeHardness)
        assertEquals(live.strokeOpacity, replay.strokeOpacity)
        assertEquals(live.strokeGrain, replay.strokeGrain)
        // A descriptor that substitutes preset bases must fail all four dynamic captures.
        assertNotEquals(brush.tip.hardness.base, replay.strokeHardness)
        assertNotEquals(brush.opacity.base, replay.strokeOpacity)
        assertNotEquals(brush.tipTexture.depth.base, replay.strokeGrain.tip.depth)
        assertNotEquals(brush.paperGrain.depth.base, replay.strokeGrain.paper.depth)
        val again = InkReplay.replay(record.copy(), brush)
        assertEquals(replay.dabs, again.dabs)
        assertEquals(replay.strokeHardness, again.strokeHardness)
        assertEquals(replay.strokeOpacity, again.strokeOpacity)
        assertEquals(replay.strokeGrain, again.strokeGrain)
        assertNotEquals(replay.dabs, InkReplay.replay(record.copy(seed = record.seed + 1), brush).dabs)
    }

    private val watercolor = BrushPreset(id = "wc", name = "Watercolor", engine = ENGINE_WATERCOLOUR,
        size = Param(1f), spacing = .5f, flow = Param(.89f), opacity = Param(.8f),
        tipTexture = GrainSpec(enabled = true), paperGrain = GrainSpec(enabled = true),
        watercolour = WatercolourSpec(size = 1.0, minimum = 1.0, taper = 0.0, spacing = .5,
            smoothPath = 0.0, smoothPressure = 0.0, smoothTilt = 0.0, scatter = 0.0))

    private fun watercolorRecord(end: Float) = StrokeRecord("wash", watercolor.id, 78234L, .9f, 8f,
        listOf(PenSample(0f, 0f, 0.0, pressure = 1f), PenSample(end, 0f, 1.0, pressure = 1f)))

    @Test fun exactly6000DabsRemainReplayableAndUseExplicitOffStampGrain() {
        val record = watercolorRecord(2999.5f)
        val live = WatercolourDabber(watercolor.watercolourRuntime(), record.seed)
        val expected = live.replay(record.samples)
        assertEquals(6000, expected.size)
        assertFalse(live.capped)
        val replay = InkReplay.replay(record, watercolor)
        assertEquals(expected, replay.dabs)
        assertEquals(live.strokeOpacity, replay.strokeOpacity)
        assertEquals(watercolor.tip.hardness.base, replay.strokeHardness)
        assertTrue(GrainMath.strokeUniformsFor(watercolor).tip.enabled)
        assertTrue(GrainMath.strokeUniformsFor(watercolor).paper.enabled)
        assertEquals(GrainMath.StrokeGrain(GrainMath.GrainUniforms.OFF, GrainMath.GrainUniforms.OFF),
            replay.strokeGrain)
        assertEquals(replay.dabs, InkReplay.replay(record.copy(), watercolor).dabs)
    }

    @Test fun aRequired6001stDabRefusesEditableReplayWithoutChangingLiveRasterCap() {
        val record = watercolorRecord(3000f)
        val live = WatercolourDabber(watercolor.watercolourRuntime(), record.seed)
        assertEquals(6000, live.replay(record.samples).size)
        assertTrue(live.capped)
        val thrown = assertFailsWith<InkReplayException> { InkReplay.replay(record, watercolor) }
        assertTrue(thrown.message.orEmpty().contains(record.id))
        assertTrue(thrown.message.orEmpty().contains("6000"))
        assertTrue(thrown.message.orEmpty().contains("cannot replay"))
        assertEquals(thrown.message,
            assertFailsWith<InkReplayException> { InkReplay.dabs(record, watercolor) }.message)
    }

    @Test fun publicReplayKeepsMissingAndUnsupportedBrushRefusals() {
        val record = watercolorRecord(10f)
        for (brush in listOf(null, watercolor.copy(engine = "smudge"), watercolor.copy(watercolour = null))) {
            val why = assertNotNull(InkReplay.refusal(record, brush))
            assertEquals(why, assertFailsWith<InkReplayException> { InkReplay.replay(record, brush) }.message)
        }
    }
}

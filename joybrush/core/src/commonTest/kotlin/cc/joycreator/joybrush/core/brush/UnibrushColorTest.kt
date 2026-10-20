package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.StrokeSmoother
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.DabPlacer
import cc.joycreator.joybrush.core.stroke.StrokeCodec
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.InkReplay
import kotlin.test.*

class UnibrushColorTest {
    @Test fun masksConvertResolvedColourRatherThanOnlyTheStartingPalette() {
        assertEquals(ColorDab(54f/255f,54f/255f,54f/255f),ColorDab(1f,0f,0f).toMaskGray())
        assertEquals(ColorDab(182f/255f,182f/255f,182f/255f),ColorDab(0f,1f,0f).toMaskGray())
        val generator=ColorDynamics(ColorDynamicsSpec(saturationRange=1f,hueRangeDegrees=180f),33L,
            StrokeColors(0xFF808080.toInt(),-1))
        var colored=false
        for(i in 0..40) {
            val c=generator.at(i*3f,1f)
            colored = colored || c.r != c.g || c.g != c.b
            val gray=c.toMaskGray()
            assertEquals(gray.r,gray.g);assertEquals(gray.g,gray.b)
        }
        assertTrue(colored,"gray input alone must not hide the need for post-dynamics conversion")
    }
    private val palette = StrokeColors(0xFF986438.toInt(), 0xFF2898C4.toInt())
    private val settings = ColorDynamicsSpec(hueRangeDegrees = 28f, saturationRange = .2f,
        valueRange = .1f, secondaryMix = .3f, pressureHueDegrees = 15f)
    private fun brush(mode: ColorMode) = BrushPreset(id = "color", name = "Color", size = Param(16f),
        sizeJitter = .3f, angleJitter = 30f, color = ColorJitter(dynamics = settings.copy(mode = mode)),
        response = ResponseSpec(pressure = listOf(.2f,.1f,.7f,.95f)))

    @Test fun seededColourCannotChangeLegacyDabGeometryOrOtherRandomStreams() {
        for (mode in ColorMode.entries) {
            val p = brush(mode)
            val colored = BrushDabber(p, 76L, palette)
            val old = BrushDabber(p.copy(color = ColorJitter(hue=.9f,saturation=.8f,value=.7f,perStroke=true)), 76L)
            val zero = BrushDabber(p.copy(color = ColorJitter(dynamics = ColorDynamicsSpec())), 76L)
            for (i in 0..80) {
                val s = PenSample(i*2f,(i%7)*3f,i*8.0,(i%11)/10f)
                val a = colored.look(s,i*3f,i)
                val b = old.look(s,i*3f,i)
                assertNotNull(a.color)
                assertNull(b.color)
                assertEquals(b,a.copy(color=null))
                assertEquals(b,zero.look(s,i*3f,i))
            }
            assertFailsWith<BrushException> { BrushDabber(p,76L) }
        }
    }

    @Test fun actualStreamingPipelineAndFrozenRecordReplayAgreeForEveryModeAndSeed() {
        val samples = (0..38).map { i -> PenSample(i*4f,(i%6)*2f,i*9.0,(i%9)/8f,i*.02f,i*.04f) }
        for (mode in ColorMode.entries) for (seed in listOf(0L,Long.MIN_VALUE,213L)) {
            val p = brush(mode)
            val record = StrokeRecord("rgb",p.id,seed,.3f,1.7f,samples,palette.primaryArgb,
                secondaryColorArgb=palette.secondaryArgb)
            val dabber = BrushDabber(p,seed,palette)
            val placer = DabPlacer(dabber.spacing,dabber::look)
            val smoother = StrokeSmoother(record.smoothing,record.screenPerDoc)
            val live = ArrayList<Dab>()
            for (s in samples) placer.addTo(smoother.add(p.response.apply(s)),live::add)
            placer.addTo(smoother.finish(),live::add)
            val back = StrokeCodec.decode(StrokeCodec.encode(record))
            val frozen = BrushJson.decodeChecked(BrushJson.encode(p))
            assertEquals(live,InkReplay.dabs(back,frozen))
            assertTrue(live.size>50 && live.all { it.color != null })
            assertNotEquals(live.map { it.color },InkReplay.dabs(back.copy(seed=seed xor 91L),frozen).map { it.color })
            val scatter = p.copy(scatter=ScatterSpec(count=3))
            val leaves = InkReplay.dabs(back,scatter)
            assertEquals(live.size*3,leaves.size)
            for (i in live.indices) for (j in 0..2) assertSame(leaves[i*3].color,leaves[i*3+j].color)
        }
    }

    @Test fun modernColourWordsAreVersionedAndAllNumbersValidated() {
        val p = brush(ColorMode.perDab).copy(version=1)
        val json = BrushJson.encode(p)
        assertTrue(json.contains("\"version\": 15"))
        assertFailsWith<BrushException> { BrushJson.decode(json.replace("\"version\": 15","\"version\": 14")) }
        assertFailsWith<BrushException> { BrushJson.decode(json.replace("perDab","unknownColourMode")) }
        assertFalse(BrushJson.encode(p.copy(color=ColorJitter())).contains("\"dynamics\":"))
        for (s in listOf(settings.copy(hueRangeDegrees=Float.NaN),settings.copy(saturationRange=1.1f),
            settings.copy(valueRange=-.1f),settings.copy(secondaryMix=Float.POSITIVE_INFINITY),
            settings.copy(pressureHueDegrees=-181f),settings.copy(driftDistancePx=0f))) {
            assertTrue(BrushValidate.validate(p.copy(version=15,color=ColorJitter(dynamics=s))).isNotEmpty())
        }
        assertTrue(BrushValidate.validate(p.copy(version=15,engine="smudge")).isNotEmpty())
    }
}

package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.StrokeSmoother
import cc.joycreator.joybrush.core.paint.*
import cc.joycreator.joybrush.core.stroke.StrokeCodec
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.InkReplay
import cc.joycreator.joybrush.core.vector.InkReplayException
import cc.joycreator.joybrush.core.vector.InkRaster
import cc.joycreator.joybrush.core.vector.InkTiles
import kotlin.test.*

class SampledReplayTest {
    private val paths = listOf("dot.png", "edge.png")
    private val set = SampledTipSet(paths, listOf(ImageMask.fromR8(1,1,byteArrayOf(-1)),
        ImageMask.fromR8(2,1,byteArrayOf(0,-1))))
    private fun brush(spec: SampledTipSpec = SampledTipSpec(paths)) = BrushPreset(id="sampled",name="Sampled",size=Param(13f),
        tip=TipSpec(sampled=spec),sizeJitter=.3f,angleJitter=15f,response=ResponseSpec(pressure=listOf(.2f,.1f,.6f,.9f)))
    private val raw = (0..25).map { PenSample(3f*it, (it%6)*2f, it*8.0, (it%9)/8f, .2f, .5f) }

    @Test fun sampledWordsAreVersionedAndMasksAreRequiredRatherThanReplacedWithCircles() {
        val p=brush().copy(version=1)
        val json=BrushJson.encode(p)
        assertTrue(json.contains("\"version\": 16"))
        assertFailsWith<BrushException> { BrushJson.decode(json.replace("\"version\": 16","\"version\": 15")) }
        assertFailsWith<BrushException> { BrushDabber(p,1L) }
        val record=StrokeRecord("line",p.id,1L,.3f,1f,raw)
        assertFailsWith<InkReplayException> { InkReplay.dabs(record,p) }
        assertEquals(listOf("dot.png","edge.png"),BrushAssetPaths.required(p))
        assertEquals(listOf("sequence","random","byPressure","byDirection"),SamplePick.entries.map { it.name })
        assertEquals(listOf("alpha","luminance"),SampleCoverage.entries.map { it.name })
        assertTrue(BrushValidate.validate(p.copy(version=16)).isEmpty())
        assertTrue(BrushValidate.validate(p.copy(engine="smudge")).isNotEmpty())
    }

    @Test fun everyPickModeAndSeedReplaysTheSameRawStrokeAfterBothCodecs() {
        for(mode in SamplePick.entries) for(seed in listOf(0L,Long.MIN_VALUE,76L)) {
            val p=brush(SampledTipSpec(paths,mode,rotateJitterDegrees=37f,spacingJitter=.7f))
            val record=StrokeRecord("line",p.id,seed,.3f,1.7f,raw)
            val dabber=BrushDabber(p,seed,sampledTips=set)
            val placer=DabPlacer(dabber.spacing,dabber::look)
            val smoother=StrokeSmoother(record.smoothing,record.screenPerDoc)
            val live=ArrayList<Dab>()
            for(sample in raw) placer.addTo(smoother.add(p.response.apply(sample)),live::add)
            placer.addTo(smoother.finish(),live::add)
            val decoded=StrokeCodec.decode(StrokeCodec.encode(record))
            val frozen=BrushJson.decodeChecked(BrushJson.encode(p))
            assertEquals(live,InkReplay.dabs(decoded,frozen,set))
            val admitted=ArrayList<Dab>()
            assertEquals(live,InkReplay.replayAdmitted(decoded,frozen,set,admitted::add).dabs)
            assertEquals(live,admitted)
            assertTrue(live.size>30 && live.all { it.sampled?.set === set })
        }
    }

    @Test fun imageSelectionUsesIndependentRandomnessAndScatterSharesTheChosenMask() {
        val p=brush(SampledTipSpec(paths,SamplePick.random))
        val colored=BrushDabber(p,41L,sampledTips=set)
        val old=BrushDabber(p.copy(tip=p.tip.copy(sampled=null)),41L)
        for(i in raw.indices) assertEquals(old.look(raw[i],i*4f,i),colored.look(raw[i],i*4f,i).copy(sampled=null))
        val record=StrokeRecord("line",p.id,41L,0f,1f,raw)
        val a=InkReplay.dabs(record,p,set)
        val scattered=InkReplay.dabs(record,p.copy(scatter=ScatterSpec(count=3)),set)
        assertEquals(a.size*3,scattered.size)
        for(i in a.indices) for(j in 0..2) assertSame(scattered[i*3].sampled,scattered[i*3+j].sampled)
        assertNotEquals(a.map { it.sampled?.index },InkReplay.dabs(record.copy(seed=42L),p,set).map { it.sampled?.index })
    }

    @Test fun decodedSetCannotBeReusedAfterCoverageOrInversionChanges() {
        assertFalse(set.matches(SampledTipSpec(paths,invert=true)))
        assertFalse(set.matches(SampledTipSpec(paths,coverage=SampleCoverage.luminance)))
        assertFailsWith<BrushException> { BrushDabber(brush(SampledTipSpec(paths,invert=true)),1L,sampledTips=set) }
    }

    @Test fun lowResolutionStampsKeepTheirCompleteBilinearSupportAndPngCorners() {
        val stamp=SampledStamp(set,0,1f)
        val tip=TipShape(sampled=stamp)
        val radius=80f
        // A 1x1 pixel is a bilinear tent extending to twice its nominal half-width.
        assertTrue(TipMath.coverage(130f,0f,radius,0f,tip)>0f)
        assertTrue(TipMath.coverage(60f,60f,radius,0f,tip)>0f,"the default procedural circle must not clip image corners")
        assertEquals(0f,TipMath.coverage(161f,0f,radius,0f,tip))
        val dab=Dab(0f,0f,radius,sampled=stamp)
        assertTrue(Tiles.key(2,0) in Tiles.touchedBy(dab,64),"tile ownership must include the image's full support")
        val canvas=RefCanvas(tileSize=64)
        canvas.beginStroke("layer",1f,0f,0f,1f,Accumulate.WASH,StrokeBlend.NORMAL,tip)
        canvas.addDabs(listOf(dab)); canvas.endStroke()
        assertTrue(canvas.pixel("layer",130,0)[3]>0f)
        val pixels=InkRaster.stamps(listOf(dab),tip,Accumulate.WASH,1f,-1,128,0,8,8,1f)!!
        assertTrue((pixels[3].toInt() and 255)>0)
        val quad=tip.copy(quad=TipQuads.interpolate(QuadShapeSpec(roundness=1f),null,0f))
        assertEquals(0f,TipMath.coverage(130f,0f,radius,0f,quad),"an explicit quad masks the image")
    }

    @Test fun cpuInkPreparationNeedsItsFrozenSetAndRendersItWhenSupplied() {
        val p=brush()
        val record=StrokeRecord("line",p.id,1L,0f,1f,raw)
        assertEquals(1,InkTiles.prepare(listOf(record)) { p }.refusals.size)
        val prepared=InkTiles.prepare(listOf(record),{p},{set})
        assertTrue(prepared.refusals.isEmpty())
        assertNotNull(InkTiles.render(prepared,0,0).pixels)
    }
}

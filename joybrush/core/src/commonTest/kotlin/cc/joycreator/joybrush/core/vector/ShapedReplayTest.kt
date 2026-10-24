package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.*
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.stroke.*
import kotlin.test.*

class ShapedReplayTest {
    private val allowance=ShapedReplayLimits(1000,10000,10000,30000)
    private val raw=(0..23).map { PenSample(it*2.3f,(it%5)*1.7f,it*11.0,(it%9)/8f,.3f,.6f) }
    private val primary=SampledTipSet(listOf("a.png","b.png"),listOf(
        ImageMask.fromR8(1,1,byteArrayOf(-1)),ImageMask.fromR8(2,1,byteArrayOf(0,-1))))
    private fun brush(style:LineStyleSpec)=BrushPreset(id="b",name="Brush",size=Param(9f),
        line=style,sizeJitter=.2f,angleJitter=15f,scatter=ScatterSpec(amount=Param(.3f),count=2,countJitter=.4f),
        response=ResponseSpec(pressure=listOf(.2f,.1f,.7f,.95f)))

    @Test fun boundedTwoPassTranscriptionMatchesExistingWholeStrokeReplayAcrossFeatures() {
        val styles=listOf(LineStyleSpec(taperIn=2f,taperOut=3f),
            LineStyleSpec(taperIn=20f,taperOut=30f,taperTip=.02f,taperShape=3f),
            LineStyleSpec(taperIn=1f,taperOut=2f,dashOn=2f,dashOff=1.5f))
        for(style in styles) for(feature in 0..4) {
            var p=brush(style)
            when(feature) {
                1->p=p.copy(tip=p.tip.copy(quad=TipQuadSpec(),fade=TipFadeSpec(amount=Param(.6f))))
                2->p=p.copy(tip=p.tip.copy(sampled=SampledTipSpec(primary.paths(),pick=SamplePick.random,spacingJitter=.3f)))
                3->p=p.copy(tip=p.tip.copy(dual=DualTipSpec()))
                4->p=p.copy(color=ColorJitter(dynamics=ColorDynamicsSpec(hueRangeDegrees=70f,secondaryMix=.4f)))
            }
            val set=primary.takeIf { feature==2 }
            val mask=p.tip.dual?.let { DualTips.bake(it,91) }
            val r=StrokeRecord("s",p.id,91,.4f,1.7f,raw,colorArgb=0xff18a5cc.toInt(),
                secondaryColorArgb=0xffe6a123.toInt(),widthScale=1.8f)
            val expected=InkReplay.replay(r,p,set,mask)
            val emitted=ArrayList<Dab>()
            val actual=ShapedReplay.replayAdmitted(r,p,set,mask,allowance) { emitted+=it }
            assertEquals(expected.dabs,actual.dabs,"style=$style feature=$feature")
            assertEquals(actual.dabs,emitted)
            assertEquals(expected.strokeHardness,actual.strokeHardness)
            assertEquals(expected.strokeOpacity,actual.strokeOpacity)
            assertEquals(expected.strokeGrain,actual.strokeGrain)
        }
    }

    @Test fun tapsShortFlicksAndPlainValueWidthScalesRetainLegacyArithmetic() {
        val p=brush(LineStyleSpec(taperIn=8f,taperOut=12f,taperTip=0f))
        for(path in listOf(emptyList(),raw.take(1),raw.take(2),raw)) for(scale in listOf(0f,-1f,1f,2f,100f,Float.NaN)) {
            val r=StrokeRecord("s",p.id,17,0f,1f,path,widthScale=scale)
            assertEquals(InkReplay.replay(r,p).dabs,
                ShapedReplay.replayAdmitted(r,p,null,null,allowance) {}.dabs,"count=${path.size} scale=$scale")
        }
    }

    @Test fun everyWorkAllowanceAndTheAdmissionSinkCanRefuseWithoutPartialReturn() {
        val p=brush(LineStyleSpec(taperIn=2f,taperOut=3f))
        val r=StrokeRecord("s",p.id,3,.5f,1f,raw)
        for(limit in listOf(allowance.copy(maxRawSamples=1),allowance.copy(maxSmoothedSamples=1),
            allowance.copy(maxPlacedDabs=1),allowance.copy(maxFinalDabs=1))) {
            assertFailsWith<InkReplayException> { ShapedReplay.replayAdmitted(r,p,null,null,limit) {} }
        }
        val stopped=IllegalStateException("source tile allowance")
        var calls=0
        val thrown=assertFailsWith<IllegalStateException> {
            ShapedReplay.replayAdmitted(r,p,null,null,allowance) { if(++calls==3) throw stopped }
        }
        assertSame(stopped,thrown); assertEquals(3,calls)
    }

    @Test fun explicitAllowanceEntryPointRequiresTheShapedRecipeItCanBudget() {
        val p=brush(LineStyleSpec(taperIn=2f,taperOut=3f))
        val r=StrokeRecord("s",p.id,3,.3f,1f,raw)
        assertEquals(InkReplay.replay(r,p).dabs,
            InkReplay.replayAdmitted(r,p,null,null,allowance) {}.dabs)
        assertFailsWith<IllegalArgumentException> {
            InkReplay.replayAdmitted(r,p.copy(line=null),null,null,allowance.copy(maxPlacedDabs=1)) {}
        }
    }
}

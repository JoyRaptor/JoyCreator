package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.StrokeSmoother
import cc.joycreator.joybrush.core.paint.*
import cc.joycreator.joybrush.core.stroke.*
import cc.joycreator.joybrush.core.vector.*
import kotlin.test.*

class DualReplayTest {
    private val raw = (0..30).map { PenSample(it*2.1f,(it%7)*1.3f,it*9.0,(it%11)/10f,.3f,.7f) }
    private val secondary = SampledTipSet(listOf("dual.png"),listOf(ImageMask.fromR8(3,2,byteArrayOf(0,-1,32,-1,0,100))))
    private val primary = SampledTipSet(listOf("main.png"),listOf(ImageMask.fromR8(2,1,byteArrayOf(-1,64))))
    private fun pen(mode: DualMode, image: Boolean) = BrushPreset(id="dual",name="Dual",size=Param(13f),
        sizeJitter=.2f,angleJitter=35f,tip=TipSpec(sampled=if(image)SampledTipSpec(listOf("main.png")) else null,
            dual=DualTipSpec(shape=null,image=DualImageSpec("dual.png"),mode=mode)),
        response=ResponseSpec(pressure=listOf(.2f,.1f,.7f,.95f)))

    private fun live(p: BrushPreset,r: StrokeRecord,set: SampledTipSet?,mask: DualMask): List<Dab> {
        val dabber=BrushDabber(p,r.seed,StrokeColors(r.colorArgb,r.secondaryColorArgb?:-1),set,mask)
        val placer=DabPlacer(spacing=dabber.spacing,look=dabber::look)
        val smoother=StrokeSmoother(r.smoothing,r.screenPerDoc)
        val out=ArrayList<Dab>()
        for (s in r.samples) for (smooth in smoother.add(p.response.apply(s))) placer.addTo(smooth) { out+=it }
        for (s in smoother.finish()) placer.addTo(s) { out+=it }
        return out
    }

    @Test fun frozenJsonAndRawCodecRebuildSameSeededDabsAndCoverageForEveryMode() {
        for (mode in DualMode.entries) for (image in listOf(false,true)) {
            val p=pen(mode,image)
            val r=StrokeRecord("s",p.id,933,.42f,1.7f,raw)
            val mask=DualTips.bake(p.tip.dual!!,r.seed,secondary)
            val set=primary.takeIf { image }
            val streamed=live(p,r,set,mask)
            val reopened=StrokeCodec.decode(StrokeCodec.encode(r))
            val frozen=BrushJson.decodeChecked(BrushJson.encode(p))
            val replayMask=DualTips.bake(frozen.tip.dual!!,reopened.seed,secondary)
            val replayed=InkReplay.dabs(reopened,frozen,set,replayMask)
            assertTrue(streamed.isNotEmpty())
            assertEquals(streamed.map { it.copy(dual=null) },replayed.map { it.copy(dual=null) })
            assertTrue(streamed.all { it.dual === mask }); assertTrue(replayed.all { it.dual === replayMask })
            assertContentEquals(mask.bytes(),replayMask.bytes())
            val admitted=ArrayList<Dab>()
            val bounded=InkReplay.replayAdmitted(reopened,frozen,set,replayMask) { admitted+=it }
            assertEquals(replayed,bounded.dabs); assertEquals(replayed,admitted)
            val result=InkTiles.prepare(listOf(reopened),{frozen},{set}) { _,_->replayMask }
            assertTrue(result.refusals.isEmpty())
            assertNotNull(InkTiles.render(result,0,0).pixels)
            val old=InkReplay.dabs(r,p.copy(tip=p.tip.copy(dual=null)),set)
            assertEquals(old,streamed.map { it.copy(dual=null) },"dual consumes no existing random draws")
        }
    }

    @Test fun wrongSeedOrRecipeNeverFallsBackAndBoundsStayPrimary() {
        val p=pen(DualMode.multiply,false)
        val r=StrokeRecord("s",p.id,4,0f,1f,raw)
        val mask=DualTips.bake(p.tip.dual!!,4,secondary)
        assertFailsWith<BrushException> { BrushDabber(p,5,dualMask=mask) }
        assertFailsWith<InkReplayException> { InkReplay.dabs(r,p) }
        assertFailsWith<InkReplayException> { InkReplay.dabs(r.copy(seed=5),p,dualMask=mask) }
        assertNotNull(InkReplay.refusal(r,p.copy(tip=p.tip.copy(dual=p.tip.dual!!.copy(mode=DualMode.min))),dualMask=mask))
        val main=TipShape(aspect=.25f,taper=.4f,anchor=.5f,fadeAmount=.7f,fadeAngle=.8f)
        var changed=0
        for(y in -25..25) for(x in -25..25) {
            val a=TipMath.coverage(x+.5f,y+.5f,17f,.7f,main)
            val b=TipMath.coverage(x+.5f,y+.5f,17f,.7f,main.copy(dual=mask))
            assertTrue(b>=0f && b<=a,"secondary may remove coverage only")
            if(a-b>.01f) changed++
        }
        assertTrue(changed>50)
        val dab=Dab(255f,255f,17f,angle=.7f,aspect=.25f,anchor=.5f)
        assertEquals(Tiles.touchedBy(dab),Tiles.touchedBy(dab.copy(dual=mask)))
    }

    @Test fun dualWordAndCompleteOrderedImageClosureAreFrozen() {
        val p=pen(DualMode.subtract,true).copy(version=1)
        val json=BrushJson.encode(p)
        assertEquals(17,BrushJson.decodeChecked(json).version)
        assertFailsWith<BrushException> { BrushJson.decode(json.replace("\"version\": 17","\"version\": 16")) }
        assertEquals(listOf("main.png","dual.png"),BrushAssetPaths.required(p))
        val weighted=p.copy(tip=p.tip.copy(sampled=SampledTipSpec(listOf("dual.png","dual.png"))))
        assertEquals(listOf("dual.png","dual.png","dual.png"),BrushAssetPaths.ordered(weighted))
        assertEquals(listOf("dual.png"),BrushAssetPaths.required(weighted))
        assertFalse(BrushJson.encode(p.copy(tip=TipSpec())).contains("\"dual\":"))
        assertEquals(listOf(1,2,3),DualMode.entries.map { it.code })
    }
}

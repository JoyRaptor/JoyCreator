package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.*
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.StrokeEdit
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.test.*

class WatercolourReplayTest {
    private val brush=BrushPreset(id="wc",name="Watercolor",engine=ENGINE_WATERCOLOUR,
        size=Param(300f),flow=Param(.89f),opacity=Param(.8f),spacing=.15f,accumulate="buildup",
        blend="multiply",watercolour=WatercolourSpec())
    private val samples=(0..50).map { i -> PenSample(i*7f,i*2f,i*5.0,pressure=.2f+i*.012f,tilt=.7f+i*.005f) }
    private fun record()=StrokeRecord("line","wc",78234L,0f,1f,samples,colorArgb=0xff94f92f.toInt())
    @Test fun unchangedGenericKnobsPreserveApprovedDoubleMaths() {
        assertEquals(brush.watercolour,brush.watercolourRuntime())
        val unchanged=brush.copy(size=Param(brush.size.base),flow=Param(brush.flow.base),
            opacity=Param(brush.opacity.base),spacing=brush.spacing)
        assertEquals(brush.watercolour,unchanged.watercolourRuntime())
    }
    @Test fun missingSectionAndUnsupportedShapingAreReadableRefusals() {
        val r=record()
        for(bad in listOf(brush.copy(watercolour=null),brush.copy(line=LineStyleSpec()))) {
            val why=assertNotNull(InkReplay.refusal(r,bad))
            assertTrue(why.contains(if(bad.watercolour==null) "missing watercolor settings" else "does not support"))
            val thrown=assertFailsWith<InkReplayException> { InkReplay.dabs(r,bad) }
            assertEquals(why,thrown.message)
            assertEquals(why,InkTiles.render(listOf(r),0,0) { bad }.refusals.single().reason)
        }
    }
    @Test fun replayIsLiveDabsWithNoDoubleSmoothingOrScatter() {
        val r=record(); val live=WatercolourDabber(brush.watercolourRuntime(),r.seed).replay(samples)
        assertEquals(live,InkReplay.dabs(r,brush)); assertTrue(live.isNotEmpty())
        assertTrue(live.all { it.sampleIndex in 0..24 }); assertEquals(null,InkReplay.refusal(r,brush))
        assertTrue(StrokeEdit.drawsInkLines(ENGINE_WATERCOLOUR))
    }
    @Test fun recolourKeepsGeometrySamplesAndSeed() {
        val r=record(); val changed=r.copy(colorArgb=0xffcc2244.toInt())
        assertEquals(InkReplay.dabs(r,brush),InkReplay.dabs(changed,brush))
        assertNotEquals(r.colorArgb,changed.colorArgb); assertEquals(r.seed,changed.seed)
    }
    @Test fun translatedLineReplaysAtNewPositionAndWidthOnlyScalesRadius() {
        // Distance filters are translation-invariant: a vector move does not deposit into old pixels.
        val r=record(); val moved=r.copy(samples=samples.map { it.copy(x=it.x+512f,y=it.y-256f) })
        val a=InkReplay.dabs(r,brush);val b=InkReplay.dabs(moved,brush)
        assertEquals(a.size,b.size)
        a.zip(b).forEach { (x,y)->assertEquals(x.sampleIndex,y.sampleIndex);assertEquals(x.radius,y.radius,1e-4f)
            assertEquals(x.x+512f,y.x,1e-4f);assertEquals(x.y-256f,y.y,1e-4f) }
        val wide=InkReplay.dabs(r.copy(widthScale=2f),brush)
        a.zip(wide).forEach { (x,y)->assertEquals(x.x,y.x);assertEquals(x.y,y.y);assertEquals(x.radius*2f,y.radius) }
    }
    @Test fun genericBrushKnobsAreNotDeadAndCpuCannotPretendToRenderImages() {
        val tuned=brush.copy(size=Param(120f),flow=Param(.4f),opacity=Param(.25f),spacing=.2f).watercolourRuntime()
        assertEquals(120.0,tuned.size); assertEquals(.4f.toDouble(),tuned.opacity);assertEquals(.25,tuned.strokeOpacity)
        val result=InkTiles.render(listOf(record()),0,0) { brush }
        assertTrue(result.refusals.single().reason.contains("GPU look"));assertEquals(null,result.pixels)
    }
}

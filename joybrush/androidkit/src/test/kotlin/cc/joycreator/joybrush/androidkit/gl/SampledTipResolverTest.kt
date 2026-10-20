package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.androidkit.io.PngWriter
import cc.joycreator.joybrush.core.brush.*
import kotlin.test.*

class SampledTipResolverTest {
    @Test fun cachedMasksFollowByteOwnerCoverageAndInversionAndFailedDecodeDoesNotPublish() {
        val png=PngWriter.encode(1,1,byteArrayOf(-1,-1,-1,-1))
        val a=FrozenBrushPngs.snapshot(mapOf("a.png" to png))
        val b=FrozenBrushPngs.snapshot(mapOf("a.png" to png))
        var calls=0
        val resolver=SampledTipResolver { prepared,spec ->
            calls++
            SampledTipSet(prepared.paths(),listOf(ImageMask.fromR8(1,1,byteArrayOf(-1))),spec.coverage,spec.invert)
        }
        val p=BrushPreset(id="t",name="T",size=Param(10f),tip=TipSpec(sampled=SampledTipSpec(listOf("a.png"))))
        val first=resolver.resolve(p,a)
        assertSame(first,resolver.resolve(p,a))
        assertSame(first,resolver.resolve(p.copy(tip=p.tip.copy(sampled=p.tip.sampled!!.copy(pick=SamplePick.random,spacingJitter=.5f))),a))
        assertEquals(1,calls)
        assertNotSame(first,resolver.resolve(p,b));assertEquals(2,calls)
        val inverted=p.copy(tip=p.tip.copy(sampled=p.tip.sampled!!.copy(invert=true)))
        assertNotSame(first,resolver.resolve(inverted,b));assertEquals(3,calls)
        assertEquals(1L,resolver.heldMaskBytes())
        assertFailsWith<BrushException> { resolver.resolve(p,null) }
        val bad=FrozenBrushPngs.snapshot(mapOf("a.png" to byteArrayOf(1,2,3)))
        assertFailsWith<BrushException> { resolver.resolve(inverted,bad) }
        assertEquals(3,calls)
        resolver.clear();assertEquals(0L,resolver.heldMaskBytes())
        assertNotNull(resolver.resolve(p,a));assertEquals(4,calls)
    }
}

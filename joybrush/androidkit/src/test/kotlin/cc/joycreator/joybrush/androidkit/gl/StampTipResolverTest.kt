package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.androidkit.io.PngWriter
import cc.joycreator.joybrush.core.brush.*
import kotlin.test.*

class StampTipResolverTest {
    @Test fun completeSourceClosurePrecedesDecodeAndCacheTracksBytesRecipeAndSeed() {
        val png = PngWriter.encode(1, 1, byteArrayOf(-1, -1, -1, -1))
        val images = FrozenBrushPngs.snapshot(mapOf("main.png" to png, "dual.png" to png))
        var calls = 0
        val resolver = StampTipResolver { prepared, spec ->
            calls++
            SampledTipSet(prepared.paths(), prepared.paths().map { ImageMask.fromR8(1,1,byteArrayOf(-1)) },
                spec.coverage, spec.invert)
        }
        val pen = BrushPreset(id="p",name="Pen",size=Param(10f),tip=TipSpec(
            sampled=SampledTipSpec(listOf("main.png")),
            dual=DualTipSpec(shape=null,image=DualImageSpec("dual.png"))))
        val first = resolver.resolve(pen, images, 17)
        assertEquals(2, calls)
        val again = resolver.resolve(pen, images, 17)
        assertSame(first.sampled, again.sampled); assertSame(first.dual, again.dual)
        val seeded = resolver.resolve(pen, images, 18)
        assertSame(first.sampled, seeded.sampled); assertNotSame(first.dual, seeded.dual)
        assertTrue(seeded.dual!!.matches(pen.tip.dual!!,18)); assertEquals(2,calls)
        assertEquals(65538L,resolver.heldMaskBytes())
        val missing = FrozenBrushPngs.snapshot(mapOf("main.png" to png))
        assertFails { resolver.resolve(pen, missing, 17) }
        assertEquals(2,calls,"the complete missing dual closure refuses before primary decoding")
        assertEquals(0L,resolver.heldMaskBytes())
        val changed = FrozenBrushPngs.snapshot(mapOf("main.png" to png,"dual.png" to png))
        assertNotSame(first.sampled,resolver.resolve(pen,changed,17).sampled)
        assertEquals(4,calls)
        resolver.clear(); assertEquals(0L,resolver.heldMaskBytes())
    }

    @Test fun proceduralDualNeedsNoPngAndChangingModeRepreparesTheMatchingMask() {
        val resolver = StampTipResolver { _,_ -> error("procedural mask cannot decode a PNG") }
        val p = BrushPreset(id="p",name="P",size=Param(10f),tip=TipSpec(dual=DualTipSpec()))
        val a = resolver.resolve(p,null,1)
        assertNull(a.sampled); assertNotNull(a.dual)
        val changed = p.copy(tip=p.tip.copy(dual=p.tip.dual!!.copy(mode=DualMode.subtract)))
        val b = resolver.resolve(changed,null,1)
        assertEquals(DualMode.subtract,b.dual!!.mode)
        assertContentEquals(a.dual!!.bytes(),b.dual!!.bytes(),"mode changes combine, not the seeded pattern")
        assertNull(resolver.resolve(p.copy(tip=TipSpec()),null,1).dual)
        assertEquals(0L,resolver.heldMaskBytes())
    }

    @Test fun aggregatePrimaryAndDualPixelsRefuseBeforeAnyDecoderAllocation() {
        val tiny=PngWriter.encode(1,1,byteArrayOf(-1,-1,-1,-1))
        // Valid PNG framing with an untrusted large IHDR. Preflight must charge its claim
        // before a decoder can allocate; decoding would subsequently reject its small payload.
        val huge=tiny.copyOf()
        fun intAt(offset:Int,value:Int) { for(i in 0..3) huge[offset+i]=(value ushr(24-8*i)).toByte() }
        intAt(16,2048); intAt(20,2048)
        val crc=java.util.zip.CRC32().also { it.update(huge,12,17) }.value.toInt()
        intAt(29,crc)
        val pngs=FrozenBrushPngs.snapshot(mapOf("main.png" to huge,"dual.png" to tiny))
        val resolver=StampTipResolver { _,_->error("aggregate source preflight must precede every decode") }
        val p=BrushPreset(id="p",name="P",size=Param(10f),tip=TipSpec(
            sampled=SampledTipSpec(listOf("main.png")),dual=DualTipSpec(shape=null,image=DualImageSpec("dual.png"))))
        assertFails { resolver.resolve(p,pngs,1) }
        assertEquals(0L,resolver.heldMaskBytes())
    }
}

package cc.joycreator.joybrush.androidkit.lines

import cc.joycreator.joybrush.androidkit.io.PngWriter
import cc.joycreator.joybrush.core.brush.*
import kotlin.test.*

/** Proposed JVM tests for the held asset-aware cache; not executed. */
class FrozenBrushAssetCacheHeldTest {
    private fun pen()=BrushPreset(id="library",name="Sample",size=Param(8f),
        tip=TipSpec(sampled=SampledTipSpec(images=listOf("tips/a.png"))))
    private fun png(a:Int)=FrozenBrushPngs.snapshot(mapOf("tips/a.png" to
        PngWriter.encode(1,1,byteArrayOf(-1,-1,-1,a.toByte()))))
    @Test fun actualPngBytesChooseFrozenIdentityAndEqualBytesHitCache() {
        val cache=FrozenBrushCache(); var serial=0
        val first=cache.freeze(0,emptyMap(),emptyMap(),pen(),png(255)){"f-${++serial}"}
        val same=cache.freeze(0,emptyMap(),emptyMap(),pen(),png(255)){"f-${++serial}"}
        assertSame(first,same); assertEquals(1,serial)
        val changed=cache.freeze(0,emptyMap(),emptyMap(),pen(),png(128)){"f-${++serial}"}
        assertNotEquals(first.id,changed.id)
        assertFalse(first.assets.getValue(first.id).sameBytes(changed.assets.getValue(changed.id)))
        cache.freeze(0,emptyMap(),emptyMap(),pen(),png(255)){"f-${++serial}"}
        assertEquals(3,cache.computed,"Only one modern candidate is retained")
    }
    @Test fun malformedPngIsNeverRememberedOrPublished() {
        val cache=FrozenBrushCache(); var serial=0
        repeat(2) {
            val refused = assertFailsWith<cc.joycreator.joybrush.androidkit.io.JbArchiveException> {
                cache.freeze(0,emptyMap(),emptyMap(),pen(),FrozenBrushPngs.snapshot(
                    mapOf("tips/a.png" to byteArrayOf(1,2,3)))){"f-${++serial}"}
            }
            assertEquals("Frozen image is not a bounded PNG", refused.message)
        }
        assertEquals(2,serial)
    }
}

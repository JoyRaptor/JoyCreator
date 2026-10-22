package cc.joycreator.joybrush.androidkit.preview

import cc.joycreator.joybrush.core.brush.FrozenBrushPngs
import kotlin.test.*

class PreviewResourceFingerprintTest {
    @Test fun immutableContentIdentityIsStableAcrossOwnersAndDoesNotAliasChangedBytesOrPaths() {
        val bytes=byteArrayOf(1,2,3)
        fun bundle()=FrozenBrushPngs.snapshot(linkedMapOf("b.png" to byteArrayOf(4,5),"a.png" to bytes))
        val first=bundle()
        val expected=PreviewResourceFingerprint.of(first)
        assertEquals(expected,PreviewResourceFingerprint.of(bundle()))
        assertEquals(expected,PreviewResourceFingerprint.of(FrozenBrushPngs.snapshot(linkedMapOf("a.png" to bytes,"b.png" to byteArrayOf(4,5)))))
        bytes[0]=9
        assertEquals(expected,PreviewResourceFingerprint.of(first))
        assertNotEquals(expected,PreviewResourceFingerprint.of(bundle()))
        assertNotEquals(expected,PreviewResourceFingerprint.of(FrozenBrushPngs.snapshot(mapOf("other.png" to byteArrayOf(1,2,3),"b.png" to byteArrayOf(4,5)))))
        assertNotEquals(PreviewResourceFingerprint.of(null),PreviewResourceFingerprint.of(FrozenBrushPngs.empty()))
    }
}

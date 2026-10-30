package cc.joycreator.joybrush.androidkit

import cc.joycreator.joybrush.core.brush.*
import cc.joycreator.joybrush.androidkit.io.FrozenBrushAssets
import javax.imageio.ImageIO
import java.io.ByteArrayInputStream
import kotlin.test.*

class UnibrushResourcesTest {
    @Test fun builtInImageArtworkSurvivesPngAndFrozenArchiveClosureExactly() {
        val source=UnibrushResources()
        for(p in UnibrushModes.presets()) {
            val required=BrushAssetPaths.required(p).toSet()
            val bundle=source.pngs(p)
            if(required.isEmpty()) { assertNull(bundle); continue }
            assertNotNull(bundle);assertEquals(required,bundle.paths);assertSame(bundle,source.pngs(p))
            val entries=FrozenBrushAssets.prepare(mapOf(p.id to p),mapOf(p.id to bundle)).toMap()
            val reopened=FrozenBrushAssets.reopen(entries[FrozenBrushAssets.MANIFEST_ENTRY],
                entries.filterKeys(FrozenBrushAssets::isBlobEntry),mapOf(p.id to p)).getValue(p.id)
            assertTrue(bundle.sameBytes(reopened))
            for(path in required) {
                val mask=UnibrushModes.imageMask(path)!!
                val image=ImageIO.read(ByteArrayInputStream(reopened.requirePng(path)))
                assertEquals(mask.width,image.width);assertEquals(mask.height,image.height)
                val alpha=mask.bytes()
                for(y in 0 until image.height) for(x in 0 until image.width) {
                    val argb=image.getRGB(x,y)
                    assertEquals(alpha[y*image.width+x].toInt() and 255,argb ushr 24)
                    assertEquals(0x00ffffff,argb and 0x00ffffff)
                }
            }
        }
    }
    @Test fun userAndUnknownImageSourcesAreNeverSubstitutedByBuiltInArtwork() {
        val provider=UnibrushResources()
        val p=UnibrushModes.presets().first { BrushAssetPaths.required(it).isNotEmpty() }
        assertNull(provider.pngs(p.copy(id="unibrush.user.unknown",extensions=mapOf("unibrush.family" to "user"))))
        assertFailsWith<BrushException> { provider.pngs(p.copy(tip=p.tip.copy(sampled=SampledTipSpec(listOf("imported.png"))))) }
        assertNotNull(provider.pngs(p))
        assertNull(provider.pngs(UnibrushModes.presets().first()))
        assertNotNull(provider.pngs(p))
    }
}

package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.grain.GrainMath
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class GrainTexturesTest {
    @Test fun alphaIsDataEvenWhenZero() {
        assertContentEquals(byteArrayOf(31, 97, 127, 0, -2, 2, 3, -128),
            GrainTextures.rgbaBytes(intArrayOf(0x001f617f, 0x80fe0203.toInt())))
    }

    @Test fun onlyTheCatalogueSurfaceUsesThePaperFolder() {
        assertEquals("paper", GrainTextures.folderFor(GrainMath.DEFAULT_SURFACE))
        assertEquals("grain", GrainTextures.folderFor("surface_custom_cloud.png"))
        assertEquals("grain", GrainTextures.folderFor("cloud_fine_256.png"))
    }
}

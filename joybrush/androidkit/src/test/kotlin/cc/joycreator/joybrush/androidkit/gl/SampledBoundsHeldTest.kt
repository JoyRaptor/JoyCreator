package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.brush.ImageMask
import cc.joycreator.joybrush.core.brush.SampledStamp
import cc.joycreator.joybrush.core.brush.SampledTipSet
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Held proposal only; not installed or run. */
class SampledBoundsHeldTest {
    private fun imageDab(): Dab {
        val set = SampledTipSet(listOf("one.png"), listOf(ImageMask.fromR8(1,1,byteArrayOf(-1))))
        return Dab(30f,256f,15f,aspect=0f,anchor=0f,sampled=SampledStamp(set,0,1f))
    }

    @Test fun nativeOnePixelSupportCreatesThePeriodicCopyAcrossTheLeftSeam() {
        val image = imageDab()
        val copies = TilePainting.dabs(RectPx(0,0,512,512), image)
        assertTrue(copies.any { it.x == 542f && it.y == 256f })
        assertEquals(1, TilePainting.dabs(RectPx(0,0,512,512), image.copy(sampled=null)).size)
        assertTrue(copies.all { it.sampled === image.sampled })
    }

    @Test fun admissionUsesTheSameWidenedKeysAsTheAllocatorBeforeItRetainsTheDab() {
        val image = imageDab()
        val expected = setOf(Tiles.key(-1,0), Tiles.key(0,0))
        assertEquals(expected,Tiles.touchedBy(image,512).toSet())
        assertEquals(expected,RecordedReplayTilePlan.keys(listOf(image),2,512))
        assertFailsWith<IllegalArgumentException> { RecordedReplayTilePlan.keys(listOf(image),1,512) }
        assertEquals(setOf(Tiles.key(0,0)),RecordedReplayTilePlan.keys(listOf(image.copy(sampled=null)),1,512))
    }
}

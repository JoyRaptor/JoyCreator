package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.Tiles
import org.junit.Assert.*
import org.junit.Test

class TilePaintingTest {
    @Test fun seamFootprintsWrapAtNegativeOriginsAndRetainPressureAndTravel() {
        val r=RectPx(-20,-10,10,8)
        val dab=Dab(-10f,-6f,1f,0f,0.4f,0.7f)
        val copies=TilePainting.dabs(r,dab)
        assertEquals(setOf(-20f,-10f),copies.map { it.x }.toSet())
        assertTrue(copies.all { it.y == -6f && it.flow == dab.flow && it.cap == dab.cap })
        assertEquals(copies,TilePainting.dabs(r,dab.copy(x=dab.x+1000f,y=dab.y+800f)))
    }
    @Test fun cornerFootprintHitsFourCopiesButNeverLeaksBeyondExactCrop() {
        val r=RectPx(250,254,10,8)
        val copies=TilePainting.dabs(r,Dab(250f,254f,2f,0f,1f,1f))
        assertEquals(4,copies.size)
        val a=TilePainting.clip(r,Tiles.key(0,0))!!
        val b=TilePainting.clip(r,Tiles.key(1,1))!!
        assertEquals(listOf(250,254,6,2),listOf(a.x,a.y,a.w,a.h))
        assertEquals(listOf(0,0,4,6),listOf(b.x,b.y,b.w,b.h))
        assertNull(TilePainting.clip(r,Tiles.key(-1,-1)))
    }
    @Test fun excessiveBrushFootprintRefusesBeforeProducingCopies() {
        try { TilePainting.dabs(RectPx(0,0,1,1),Dab(0f,0f,1000f,0f,1f,1f)); fail("must refuse") }
        catch(_: IllegalArgumentException) {}
    }
    @Test(timeout = 1000) fun finiteFootprintBeyondLongRangeRefusesWithoutIterating() {
        // Extent is finite, but conversion of its copy indices saturates Long.
        // Subtracting those endpoints used to overflow and bypass MAX_COPIES.
        try { TilePainting.dabs(RectPx(0,0,10,8),Dab(0f,0f,1e20f,0f,1f,1f)); fail("must refuse") }
        catch(e: IllegalArgumentException) { assertEquals("Choose a smaller brush for this tile",e.message) }
    }
}

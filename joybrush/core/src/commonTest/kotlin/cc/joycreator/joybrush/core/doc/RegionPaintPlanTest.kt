package cc.joycreator.joybrush.core.doc

import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.random.Random
import kotlin.test.*

class RegionPaintPlanTest {
    private fun frame(rect: RectPx, board: String = "board", cel: String = "paint") =
        RegionFrame(board, rect, "$board-frame", cel)
    private fun plan(rect: RectPx) = RegionPaintPlan("shared", listOf(frame(rect)))

    @Test fun `edges are exact and half open`() {
        val p = plan(RectPx(17, 29, 31, 43))
        assertEquals("paint", p.planeAt(17, 29).celId)
        assertEquals("paint", p.planeAt(47, 71).celId)
        for ((x,y) in listOf(16L to 29L, 17L to 28L, 48L to 71L, 47L to 72L)) {
            assertEquals(p.shared, p.planeAt(x,y))
        }
        assertEquals(RegionTileRect(17,29,31,43), p.tileSlices(Tiles.key(0,0)).single { it.plane.boardId != null }.rect)
    }

    @Test fun `negative coordinates straddle four tiles without snapping`() {
        val p = plan(RectPx(-7,-11,20,30))
        assertEquals(RegionTileRect(249,245,7,11), p.tileSlices(Tiles.key(-1,-1)).single { it.plane.boardId != null }.rect)
        assertEquals(RegionTileRect(0,0,13,19), p.tileSlices(Tiles.key(0,0)).single { it.plane.boardId != null }.rect)
        for (x in -8L..14L) for (y in -12L..20L) {
            assertEquals(if (x in -7L..12L && y in -11L..18L) "paint" else "shared", p.planeAt(x,y).celId)
        }
    }

    @Test fun `two regions share a tile without sharing outside paint`() {
        val p = RegionPaintPlan("shared", listOf(frame(RectPx(3,7,20,30),"a","a-cel"),frame(RectPx(30,4,17,40),"b","b-cel")))
        assertEquals("a-cel",p.planeAt(3,7).celId)
        assertEquals("b-cel",p.planeAt(30,7).celId)
        assertEquals("shared",p.planeAt(24,7).celId)
        assertPartition(p,0,0)
    }

    @Test fun `touching boards do not overlap`() {
        val p = RegionPaintPlan("shared",listOf(frame(RectPx(0,0,128,256),"a","a-cel"),frame(RectPx(128,0,128,256),"b","b-cel")))
        assertEquals(2,p.tileSlices(Tiles.key(0,0)).size)
        assertPartition(p,0,0)
    }

    @Test fun `all tile pixels partition exactly once for varied regions`() {
        val random = Random(4871)
        repeat(16) {
            val x=random.nextInt(-255,256); val y=random.nextInt(-255,256)
            val p=plan(RectPx(x,y,random.nextInt(1,310),random.nextInt(1,310)))
            for (ty in -1..1) for (tx in -1..1) assertPartition(p,tx,ty)
        }
    }

    @Test fun `pixel ownership agrees when region list order changes`() {
        val frames=listOf(frame(RectPx(0,0,13,200),"a","a-cel"),frame(RectPx(20,0,30,120),"b","b-cel"),frame(RectPx(60,30,150,40),"c","c-cel"))
        val a=RegionPaintPlan("shared",frames);val b=RegionPaintPlan("shared",frames.reversed())
        assertContentEquals(ownership(a,0,0),ownership(b,0,0))
        assertPartition(a,0,0)
    }

    @Test fun `held layer has shared pixels everywhere`() {
        val p=RegionPaintPlan("shared",emptyList())
        assertEquals(listOf(RegionTileSlice(p.shared,RegionTileRect(0,0,256,256))),p.tileSlices(Tiles.key(-12,9)))
        assertEquals(p.shared,p.planeAt(Long.MIN_VALUE,Long.MAX_VALUE))
    }

    @Test fun `changing frame leaves outside plane untouched`() {
        val a=plan(RectPx(11,19,37,53))
        val b=RegionPaintPlan("shared",listOf(a.frames.single().copy(frameId="next",celId="next-cel")))
        assertEquals(a.shared,b.planeAt(10,19))
        assertEquals("next-cel",b.planeAt(11,19).celId)
        assertEquals("paint",a.planeAt(11,19).celId)
        assertEquals(a.tileSlices(0).map{it.rect},b.tileSlices(0).map{it.rect})
    }

    @Test fun `stroke plan snapshots caller's region list`() {
        val frames=mutableListOf(frame(RectPx(0,0,11,11)))
        val p=RegionPaintPlan("shared",frames); frames.clear()
        assertEquals("paint",p.planeAt(1,1).celId)
        assertEquals("shared",p.planeAt(12,1).celId)
    }

    @Test fun `extreme coordinates never wrap into a different tile`() {
        val p=plan(RectPx(Int.MAX_VALUE-2,Int.MIN_VALUE,10,5))
        assertEquals("paint",p.planeAt(Int.MAX_VALUE.toLong()+5,Int.MIN_VALUE.toLong()+4).celId)
        assertEquals("shared",p.planeAt(Int.MIN_VALUE.toLong(),Int.MIN_VALUE.toLong()).celId)
        assertPartition(p,Int.MAX_VALUE/256,Int.MIN_VALUE/256)
        assertEquals(p.shared,p.tileSlices(Tiles.key(Int.MAX_VALUE,Int.MIN_VALUE)).single().plane)
    }

    @Test fun `overlap and ambiguous addresses are refused`() {
        val a=frame(RectPx(0,0,20,20),"a","a-cel")
        for (b in listOf(frame(RectPx(19,19,20,20),"b","b-cel"),a.copy(rect=RectPx(30,0,10,10)),a.copy(boardId="b",rect=RectPx(30,0,10,10)),a.copy(celId="shared"))) {
            assertFailsWith<IllegalArgumentException> {RegionPaintPlan("shared",listOf(a,b))}
        }
        assertFailsWith<IllegalArgumentException>{plan(RectPx(0,0,0,1))}
        assertFailsWith<IllegalArgumentException>{RegionPaintPlan("",emptyList())}
    }

    @Test fun `slice copy preserves exact premultiplied bytes and untouched pixels`() {
        val random=Random(74);val source=random.nextBytes(256*256*4)
        val dest=ByteArray(source.size){99};val rect=RegionTileRect(13,27,41,19)
        RegionPaintPlan.copyRgbaSlice(source,dest,rect)
        for(y in 0..255) for(x in 0..255) for(c in 0..3) {
            val i=(y*256+x)*4+c
            assertEquals(if(x in 13..53 && y in 27..45)source[i] else 99.toByte(),dest[i])
        }
        assertFailsWith<IllegalArgumentException>{RegionPaintPlan.copyRgbaSlice(ByteArray(3),dest,rect)}
        assertFailsWith<IllegalArgumentException>{RegionTileRect(255,0,2,1)}
    }

    @Test fun `slices reassemble all original paint without losses or extra coverage`() {
        val source=Random(918).nextBytes(256*256*4)
        val p=RegionPaintPlan("shared",listOf(frame(RectPx(17,29,31,43))))
        val rebuilt=ByteArray(source.size)
        for(slice in p.tileSlices(0)) {
            val plane=ByteArray(source.size)
            RegionPaintPlan.copyRgbaSlice(source,plane,slice.rect)
            RegionPaintPlan.copyRgbaSlice(plane,rebuilt,slice.rect)
        }
        assertContentEquals(source,rebuilt)
    }

    private fun ownership(p:RegionPaintPlan,tx:Int,ty:Int):Array<String> {
        val actual=Array(256*256){""}
        for(slice in p.tileSlices(Tiles.key(tx,ty))) for(y in slice.rect.y until slice.rect.bottom) for(x in slice.rect.x until slice.rect.right) {
            val i=y*256+x
            check(actual[i].isEmpty()) {"Pixel routed twice: $x,$y"}
            actual[i]=slice.plane.celId
        }
        return actual
    }

    private fun assertPartition(p:RegionPaintPlan,tx:Int,ty:Int) {
        val actual=ownership(p,tx,ty)
        val expected=Array(256*256){i->
            val x=tx.toLong()*256+i%256;val y=ty.toLong()*256+i/256
            p.frames.firstOrNull { f ->
                x>=f.rect.x.toLong() && y>=f.rect.y.toLong() &&
                    x-f.rect.x<f.rect.w && y-f.rect.y<f.rect.h
            }?.celId ?: "shared"
        }
        assertContentEquals(expected,actual)
    }
}

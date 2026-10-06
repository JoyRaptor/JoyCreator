package cc.joycreator.joybrush.core.doc

import cc.joycreator.joybrush.core.paint.Tiles
import java.io.File
import kotlin.test.*

/** Desktop driver fixtures come from the actual ownership rule, including negative-coordinate edge tiles. */
class RegionGpuFixtureTest {
    @Test fun writeProductionOwnershipFixtures() {
        val cases=listOf(
            Triple(0,0,listOf(RectPx(17,23,101,93),RectPx(150,16,45,65))),
            Triple(-1,-1,listOf(RectPx(-40,-30,60,55))))
        val fixtures=cases.map { (tx,ty,rects) ->
            val frames=rects.mapIndexed{i,r->RegionFrame("b$i",r,"f$i","plane${i+1}")}
            val plan=RegionPaintPlan("plane0",frames)
            val slices=plan.tileSlices(Tiles.key(tx,ty))
            val owners=IntArray(256*256){i->plan.planeAt(tx.toLong()*256+i%256,ty.toLong()*256+i/256).celId.removePrefix("plane").toInt()}
            assertEquals(65536,slices.sumOf{it.rect.w*it.rect.h})
            "{\"planes\":${frames.size+1},\"slices\":["+slices.joinToString(","){s->
                "{\"plane\":${s.plane.celId.removePrefix("plane")},\"rect\":[${s.rect.x},${s.rect.y},${s.rect.w},${s.rect.h}]}"
            }+"],\"owners\":["+owners.joinToString(",")+"]}"
        }
        File(System.getProperty("java.io.tmpdir"),"jb-region-gpu-fixtures.json").writeText("["+fixtures.joinToString(",")+"]")
    }
}

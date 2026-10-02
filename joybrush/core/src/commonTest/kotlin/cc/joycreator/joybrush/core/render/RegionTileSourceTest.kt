package cc.joycreator.joybrush.core.render

import cc.joycreator.joybrush.core.doc.*
import kotlin.test.*

class RegionTileSourceTest {
    private val rect = RectPx(1, 0, 2, 2)
    private fun layer(id: String = "paint", held: Boolean = false) = Layer(id, id, LayerKind.PAINT,
        cels = listOf(Cel("shared"), Cel("first"), Cel("second")), sharedCelId = "shared",
        regions = listOf(RegionFrames("board", mapOf("f1" to "first", "f2" to "second"), held)))
    private fun doc(layers: List<Layer> = listOf(layer())) = JbDocument(id = "doc", name = "Test", layers = layers,
        boards = listOf(Board("board", "Animation", BoardKind.ANIMATION, rect,
            frames = listOf(Frame("f1"), Frame("f2")), currentFrameId = "f2")))
    private fun solid(r: Int, g: Int, b: Int, a: Int = 255) = ByteArray(RegionRenderer.TILE_BYTES).also {
        for (i in it.indices step 4) { it[i]=r.toByte(); it[i+1]=g.toByte(); it[i+2]=b.toByte(); it[i+3]=a.toByte() }
    }
    private val colours = mapOf("shared" to solid(255,0,0), "first" to solid(0,255,0), "second" to solid(0,0,255))
    private fun render(d: JbDocument, frame: String? = null, source: TileSource = TileSource { _,cel,_,_ -> colours[cel] }) =
        RegionRenderer.render(d, source, RectPx(0,0,4,1), frame, null).map { it.toInt() and 255 }

    @Test fun savedCursorAndExportOverrideChangeOnlyInsideBoard() {
        val d=doc(); assertTrue(DocOps.validate(d).isEmpty())
        assertEquals(listOf(255,0,0,255, 0,0,255,255, 0,0,255,255, 255,0,0,255), render(d))
        assertEquals(listOf(255,0,0,255, 0,255,0,255, 0,255,0,255, 255,0,0,255), render(d,"f1"))
        assertEquals("f2",d.boards.single().currentFrameId)
    }
    @Test fun blankFrameDoesNotLeakSharedPixelsAndHeldUsesShared() {
        val source=TileSource { _,cel,_,_ -> if(cel=="second") null else colours[cel] }
        assertEquals(listOf(255,0,0,255, 0,0,0,0, 0,0,0,0, 255,0,0,255),render(doc(),source=source))
        assertEquals(List(4){listOf(255,0,0,255)}.flatten(), render(doc(listOf(layer(held=true))),source=source))
    }
    @Test fun twoBoardsInSameTileKeepIndependentCursors() {
        val first=doc(); val b=Board("other","Other",BoardKind.ANIMATION,RectPx(3,0,1,1),
            frames=listOf(Frame("g1"),Frame("g2")),currentFrameId="g2")
        val l=first.layers.single().copy(cels=first.layers.single().cels+Cel("yellow")+Cel("cyan"),
            regions=first.layers.single().regions+RegionFrames("other",mapOf("g1" to "yellow","g2" to "cyan")))
        val source=TileSource { _,cel,_,_ -> colours[cel] ?: when(cel){"yellow"->solid(255,255,0); "cyan"->solid(0,255,255); else->null} }
        assertEquals(listOf(255,0,0,255, 0,255,0,255, 0,255,0,255, 0,255,255,255),render(first.copy(layers=listOf(l),boards=first.boards+b),"f1",source))
    }
    @Test fun masksAndClipBaseUseTheirOwnLayerRegionPixels() {
        val base=layer("base").copy(mask=Cel("mask"))
        val top=layer("top").copy(clip=true)
        val source=TileSource { id,cel,_,_ -> when {
            cel=="mask" -> solid(128,128,128)
            id=="base" && cel=="second" -> null
            id=="base" -> colours[cel]
            else -> solid(0,255,0)
        } }
        val out=render(doc(listOf(base,top)),source=source)
        assertEquals(listOf(0,0,0,0),out.subList(4,8))
        assertEquals(listOf(0,0,0,0),out.subList(8,12))
        assertTrue(out[1]>0 && out[0]>0)
    }
    @Test fun negativeTileEdgesFetchEachPhysicalCelOnceAndNeverMutateSource() {
        val d=doc().copy(boards=doc().boards.map{it.copy(rect=RectPx(-1,-1,2,2))})
        val calls=mutableListOf<String>();val original=colours.getValue("shared").copyOf()
        val projected=RegionTileSource(d,TileSource { _,cel,_,_ -> calls+=cel;colours[cel] },null)
        val tile=projected.tile("paint","shared",-1,-1)!!
        assertEquals(255,tile[0].toInt() and 255)
        assertEquals(255,tile[tile.size-2].toInt() and 255)
        assertEquals(2,calls.size);assertEquals(2,calls.toSet().size)
        assertContentEquals(original,colours.getValue("shared"))
        assertNull(projected.tile("paint","mask",0,0))
        assertEquals("mask",calls.last())
    }
    @Test fun unknownFramesAndMalformedTilesAreRefused() {
        assertFailsWith<DocException>{render(doc(),"missing")}
        assertFailsWith<IllegalArgumentException>{render(doc(),source=TileSource { _,_,_,_ -> ByteArray(1) })}
    }
}

package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.render.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.*

/** An independent byte executor checks the plan against the unchanged region renderer. */
class AnimationCombineRenderTest {
    private var n=0
    private fun id()="render-${++n}"

    @Test fun combinedComponentDrawingsKeepTheirPixelsAndAliasesThroughArchive() {
        var d=DocOps.newDocument("doc","Linked",4,4,::id)
        d=RegionDocumentOps.create(d,"Left",RectPx(0,0,1,1),::id).doc
        val a=d.activeBoardId!!
        d=RegionDocumentOps.addFrame(d,a,NewFrame.BLANK,::id).doc
        d=RegionDocumentOps.create(d,"Right",RectPx(1,0,1,1),::id).doc
        val b=d.activeBoardId!!
        repeat(3) { d=RegionDocumentOps.addFrame(d,b,NewFrame.BLANK,::id).doc }
        d=AnimationBoardOps.combine(d,a,b,::id).doc
        val board=d.boards.first { it.id==d.activeBoardId }; val layer=d.layers.single()
        val first=RegionDocumentOps.paintPlan(d,layer.id,mapOf(board.id to board.frames[0].id))
        val third=RegionDocumentOps.paintPlan(d,layer.id,mapOf(board.id to board.frames[2].id))
        val left=first.planeAt(0,0).celId
        assertEquals(left,third.planeAt(0,0).celId)
        val right=first.planeAt(1,0).celId
        val bytes=mutableMapOf<Triple<String,String,String>,ByteArray>()
        fun colour(cel:String,x:Int,r:Int,g:Int) {
            bytes.getOrPut(Triple(layer.id,cel,"0_0")) { ByteArray(256*256*4) }.apply {
                this[x*4]=r.toByte(); this[x*4+1]=g.toByte(); this[x*4+3]=255.toByte()
            }
        }
        colour(left,0,255,0); colour(right,1,0,255)
        val saved=d.copy(layers=d.layers.map { it.copy(cels=it.cels.map { c -> c.copy(tiles=if(bytes.keys.any { key -> key.second==c.id }) listOf("0_0") else emptyList()) }) })
        val archive=ByteArrayOutputStream().also { JbArchive.write(it,JbContents(saved,bytes,emptyMap())) }.toByteArray()
        val reopened=JbArchive.read(ByteArrayInputStream(archive))
        assertEquals(saved,reopened.doc)
        val tiles=TileSource { l,c,x,y -> reopened.tiles[Triple(l,c,"${x}_${y}")] }
        val frame1=RegionRenderer.render(reopened.doc,tiles,board.rect,board.frames[0].id,null)
        val frame3=RegionRenderer.render(reopened.doc,tiles,board.rect,board.frames[2].id,null)
        assertEquals(255,frame1[0].toInt() and 255)
        assertEquals(255,frame1[5].toInt() and 255)
        assertEquals(255,frame3[0].toInt() and 255)
        assertEquals(0,frame3[7].toInt() and 255)
        // Painting the shared physical drawing changes every composite that links it.
        reopened.tiles.getValue(Triple(layer.id,left,"0_0"))[0]=80
        assertEquals(80,RegionRenderer.render(reopened.doc,tiles,board.rect,board.frames[0].id,null)[0].toInt() and 255)
        assertEquals(80,RegionRenderer.render(reopened.doc,tiles,board.rect,board.frames[2].id,null)[0].toInt() and 255)
    }
    @Test fun retimedFramesLookIdenticalIncludingStillArtInTheExpandedCrop() {
        var d=DocOps.newDocument("doc","Scene",4,4,::id)
        val bg=d.layers.single().copy(id="bg",name="Sky")
        val ch=Layer("ch","Character",LayerKind.PAINT,cels=listOf(Cel("ch-shared")))
        d=BoardDocumentOps.createAnimation(d.copy(layers=listOf(bg,ch),activeLayerId="ch"),"Walk",RectPx(0,0,2,2),::id).doc
        val walkId=d.boards.last().id
        d=RegionDocumentOps.addFrame(d,walkId,NewFrame.BLANK,::id).doc
        d=BoardDocumentOps.createAnimation(d.copy(activeLayerId="bg"),"Sky",RectPx(1,1,2,2),::id).doc
        val skyId=d.boards.last().id
        d=d.copy(boards=d.boards.map { if(it.id==skyId) it.copy(fps=6f) else it })
        val original=LinkedHashMap<Pair<String,String>,ByteArray>()
        fun pixel(layer:String,cel:String,x:Int,y:Int,r:Int,g:Int,b:Int) {
            val bytes=original.getOrPut(layer to cel) { ByteArray(256*256*4) }; val at=(y*256+x)*4
            bytes[at]=r.toByte();bytes[at+1]=g.toByte();bytes[at+2]=b.toByte();bytes[at+3]=255.toByte()
        }
        val walk=d.boards.first { it.id==walkId }; val sky=d.boards.first { it.id==skyId }
        val character=d.layers.first { it.id=="ch" }; val background=d.layers.first { it.id=="bg" }
        val wc=character.regions.first { it.boardId==walkId }
        for((i,f) in walk.frames.withIndex()) for(y in 0..1) for(x in 0..1)
            pixel("ch",wc.frameCel.getValue(f.id),x,y,if(i==0) 255 else 0,0,if(i==1) 255 else 0)
        val sc=background.regions.first { it.boardId==skyId }.frameCel.values.first()
        for(y in 1..2) for(x in 1..2) pixel("bg",sc,x,y,0,255,0)
        pixel("ch",character.sharedCelId!!,2,0,255,255,0)
        pixel("bg",background.sharedCelId!!,0,0,0,255,255)
        val change=AnimationBoardOps.combine(d,walkId,skyId,::id)
        val after=original.mapValues { it.value.copyOf() }.toMutableMap()
        fun erase(layer:String,cel:String,r:RectPx) {
            val bytes=after.getOrPut(layer to cel) { ByteArray(256*256*4) }
            for(y in r.y until r.y+r.h) for(x in r.x until r.x+r.w) for(c in 0..3) bytes[(y*256+x)*4+c]=0
        }
        for(c in change.clears) erase(c.layerId,c.celId,c.rect)
        for(t in change.transfers) {
            val dest=after.getOrPut(t.layerId to t.toCelId) { ByteArray(256*256*4) }
            val source=original[t.layerId to t.fromCelId]
            for(y in 0 until t.sourceRect.h) for(x in 0 until t.sourceRect.w) for(c in 0..3)
                dest[((t.destinationRect.y+y)*256+t.destinationRect.x+x)*4+c]=source?.get(((t.sourceRect.y+y)*256+t.sourceRect.x+x)*4+c) ?: 0
        }
        for(drop in change.drops) after.remove(drop.layerId to drop.celId)
        fun tiles(data:Map<Pair<String,String>,ByteArray>)=TileSource { l,c,x,y -> if(x==0 && y==0) data[l to c] else null }
        val stage=change.doc.boards.first { it.id==change.doc.activeBoardId }
        for((tick,f) in stage.frames.withIndex()) {
            val frames=mapOf(walkId to walk.frames[tick].id,skyId to sky.frames.first().id)
            val expected=RegionRenderer.renderWithPreviews(d,tiles(original),stage.rect,null,null,brushLookup=null,frameOverrides=frames)
            val actual=RegionRenderer.render(change.doc,tiles(after),stage.rect,f.id,null)
            assertContentEquals(expected,actual,"tick $tick")
        }
    }
}

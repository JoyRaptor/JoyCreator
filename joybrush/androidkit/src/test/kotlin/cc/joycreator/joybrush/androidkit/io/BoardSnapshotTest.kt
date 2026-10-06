package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.test.*

class BoardSnapshotTest {
    private var serial=0
    private fun ids()="id-${++serial}"
    private fun drawing(): JbDocument {
        val base=DocOps.newDocument("doc","Art",512,512,::ids)
        return RegionDocumentOps.create(base,"Loop",RectPx(17,23,101,93),::ids).doc
    }
    @Test fun snapshotsEveryFrameNotJustTheDisplayedProjectionAndReopens() {
        val first=drawing();val board=first.boards.last()
        val doc=RegionDocumentOps.addFrame(first,board.id,NewFrame.BLANK,::ids).doc
        val layer=doc.layers.single();val colours=layer.cels.mapIndexed{i,c->c.id to ByteArray(TILE_BYTES).also{it[0]=(i+1).toByte();it[3]=-1}}.toMap()
        val snapshot=BoardSnapshot.capture(doc,{_,_->listOf(Tiles.key(-1,0))},{_,c,_->colours[c]})
        assertEquals(3,snapshot.tiles.size)
        val output=java.io.ByteArrayOutputStream();JbArchive.write(output,snapshot)
        val reopened=JbArchive.read(java.io.ByteArrayInputStream(output.toByteArray()))
        assertEquals(doc.boards,reopened.doc.boards)
        for(cel in layer.cels)assertContentEquals(colours[cel.id],reopened.tiles[Triple(layer.id,cel.id,"-1_0")])
    }
    @Test fun linkedFrameStoresOnePhysicalPayloadAndBlankFramesStayBlank() {
        val first=drawing();val doc=RegionDocumentOps.addFrame(first,first.boards.last().id,NewFrame.LINK,::ids).doc
        val frameCel=doc.layers.single().regions.single().frameCel.values.first()
        val sample=ByteArray(TILE_BYTES).also{it[3]=-1}
        val snapshot=BoardSnapshot.capture(doc,{_,c->if(c==frameCel)listOf(0L)else emptyList()},{_,_,_->sample})
        assertEquals(1,snapshot.tiles.size)
        assertEquals(2,snapshot.doc.layers.single().cels.size)
    }
    @Test fun missingReadbackFailsRatherThanSavingHalfADrawing() {
        assertFailsWith<JbArchiveException>{BoardSnapshot.capture(drawing(),{_,_->listOf(0L)},{_,_,_->null})}
    }
    @Test fun blankMaskIsRetainedWhileBlankPaintIsOmitted() {
        val first=drawing();val layer=first.layers.single();val doc=first.copy(layers=listOf(layer.copy(mask=Cel("mask"))))
        val snapshot=BoardSnapshot.capture(doc,{_,_->listOf(0L)},{_,_,_->ByteArray(TILE_BYTES)})
        assertEquals(setOf(Triple(layer.id,"mask","0_0")),snapshot.tiles.keys)
        assertTrue(snapshot.doc.layers.single().cels.all{it.tiles.isEmpty()})
    }
}

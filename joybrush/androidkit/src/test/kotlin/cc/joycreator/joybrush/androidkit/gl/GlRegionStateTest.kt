package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.paint.UndoLog
import kotlin.test.*

class GlRegionStateTest {
    private var serial=0
    private fun ids()="id-${++serial}"
    private fun doc():JbDocument {
        val base=DocOps.newDocument("doc","Art",256,256,::ids)
        return RegionDocumentOps.create(base,"Loop",RectPx(17,23,100,90),::ids).doc
    }
    @Test fun undoAddressesPhysicalCelsIndependentlyAndRetainsInactiveFrames() {
        val doc=doc();val layer=doc.layers.single();val e=GlPaintEngine();e.addLayer(layer.id);e.setBoardDocument(doc)
        val shared=layer.sharedCelId!!;val current=layer.regions.single().frameCel.values.single()
        e.undo.push(UndoLog.Step(listOf(UndoLog.TileChange(layer.id,0,null,41,shared),UndoLog.TileChange(layer.id,0,null,42,current))))
        assertTrue(e.undoStep());assertTrue(e.celTileKeys(layer.id,shared).isEmpty())
        assertTrue(e.redoStep());assertEquals(listOf(0L),e.celTileKeys(layer.id,shared));assertEquals(listOf(0L),e.celTileKeys(layer.id,current))
        assertEquals(2,e.heldTextureNames())
        val blank=RegionDocumentOps.addFrame(doc,doc.boards.last().id,NewFrame.BLANK,::ids).doc
        e.setBoardDocument(blank)
        assertEquals(listOf(0L),e.celTileKeys(layer.id,current))
        assertTrue(e.celTileKeys(layer.id,blank.layers.single().cels.last().id).isEmpty())
        assertEquals(2,e.heldTextureNames())
    }
    @Test fun metadataUndoRestoresEachBoardsSavedFrameWithoutTextureAllocation() {
        val first=doc();val next=RegionDocumentOps.addFrame(first,first.boards.last().id,NewFrame.BLANK,::ids).doc
        val layer=first.layers.single();val e=GlPaintEngine();e.addLayer(layer.id);e.setBoardDocument(next)
        e.undo.push(UndoLog.Step(emptyList(),documentBefore=first,documentAfter=next))
        assertTrue(e.undoStep());assertEquals(first,e.boardDocument)
        assertTrue(e.redoStep());assertEquals(next,e.boardDocument);assertEquals(0,e.heldTextureNames())
    }

    @Test fun deletingLayerAndUndoingRetainsSharedAndInactiveFrameTextures() {
        val first=doc(); val layer=first.layers.single()
        val current=layer.regions.single().frameCel.values.single()
        val next=RegionDocumentOps.addFrame(first,first.boards.last().id,NewFrame.BLANK,::ids).doc
        val inactive=next.layers.single().regions.single().frameCel.values.last()
        val e=GlPaintEngine();e.addLayer(layer.id);e.setBoardDocument(next)
        e.undo.push(UndoLog.Step(listOf(UndoLog.TileChange(layer.id,0,null,41,layer.sharedCelId),
            UndoLog.TileChange(layer.id,0,null,42,current),UndoLog.TileChange(layer.id,0,null,43,inactive))))
        assertTrue(e.undoStep());assertTrue(e.redoStep())
        val before=e.stack(layer.id);val after=before.add("other")
        e.pushStackStep(before,after)
        val removal=after.delete(layer.id)!!
        e.deleteLayerStep(layer.id,after,removal)
        assertEquals(listOf("other"),e.boardDocument!!.layers.map{it.id})
        assertTrue(e.undoStep())
        assertEquals(listOf(0L),e.celTileKeys(layer.id,current))
        assertEquals(listOf(0L),e.celTileKeys(layer.id,inactive))
        assertEquals(listOf(0L),e.celTileKeys(layer.id,layer.sharedCelId!!))
        assertEquals(3,e.heldTextureNames())
        assertTrue(e.redoStep());assertEquals(listOf("other"),e.boardDocument!!.layers.map{it.id})
        assertTrue(e.undoStep());assertEquals(3,e.heldTextureNames())
    }

    @Test fun newAndDuplicatedLayerFrameIdentitiesSurviveUndoRedo() {
        val doc=doc();val layer=doc.layers.single();val e=GlPaintEngine()
        e.addLayer(layer.id);e.setBoardDocument(doc)
        val before=e.stack(layer.id);val added=before.add("other")
        e.pushStackStep(before,added)
        val addedDoc=e.boardDocument!!
        assertTrue(addedDoc.layers.last().regions.single().frameCel.isNotEmpty())
        assertTrue(e.undoStep());assertEquals(doc,e.boardDocument)
        assertTrue(e.redoStep());assertEquals(addedDoc,e.boardDocument)
        val after=added.duplicate(layer.id,"copy")!!
        e.duplicateLayerStep(layer.id,"copy",added,after)
        val duplicated=e.boardDocument!!
        val clone=duplicated.layers.first{it.id=="copy"}
        assertEquals(layer.regions.map{it.boardId},clone.regions.map{it.boardId})
        assertNotEquals(layer.sharedCelId,clone.sharedCelId)
        assertTrue(e.undoStep());assertEquals(addedDoc,e.boardDocument)
        assertTrue(e.redoStep());assertEquals(duplicated,e.boardDocument)
    }
}

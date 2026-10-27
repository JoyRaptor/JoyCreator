package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.layers.*
import cc.joycreator.joybrush.androidkit.layers.MergeDownPlan
import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.test.*

/** Empty physical stores exercise real publication/history without pretending to verify GPU pixels. */
class AnimationClipHistoryTest {
    private var serial=0
    private fun id()="history-${++serial}"
    private fun scene(): JbDocument {
        var d=DocOps.newDocument("doc","History",16,16,::id)
        val lower=d.layers.single().copy(id="lower",name="Lower")
        val upper=Layer("upper","Upper",LayerKind.PAINT,cels=listOf(Cel("upper-shared")))
        d=BoardDocumentOps.createAnimation(d.copy(layers=listOf(lower,upper),activeLayerId="upper"),"A",RectPx(0,0,8,8),::id).doc
        d=RegionDocumentOps.addFrame(d,d.activeBoardId!!,NewFrame.LINK,::id).doc
        return BoardDocumentOps.createAnimation(d.copy(activeLayerId="lower"),"B",RectPx(4,4,8,8),::id).doc
    }
    private fun stack(doc:JbDocument)=LayerStack(doc.layers.map { LayerState(it.id,it.name) },doc.activeLayerId!!)
    private fun engine(doc:JbDocument)=GlPaintEngine().apply {
        initWith { }; setStack(stack(doc)); setBoardDocument(doc)
    }
    @Test fun combineAndMoveEachPublishOneUndoStep() {
        val d=scene(); val clips=d.boards.filter { it.kind==BoardKind.ANIMATION }
        val e=engine(d)
        val combined=AnimationBoardOps.combine(d,clips[0].id,clips[1].id,::id)
        e.applyBoardChange(combined)
        assertEquals(1,e.undo.undoDepth); assertEquals(combined.doc,e.boardDocument)
        assertTrue(e.undoStep()); assertEquals(d,e.boardDocument)
        assertTrue(e.redoStep()); assertEquals(combined.doc,e.boardDocument)
        val moved=AnimationBoardOps.moveAll(combined.doc,combined.doc.activeBoardId!!,30,40)
        e.applyBoardChange(moved)
        assertEquals(2,e.undo.undoDepth)
        assertTrue(e.undoStep()); assertEquals(combined.doc,e.boardDocument)
    }
    @Test fun flattenCombinesAndFlattensAsOnePublicHistoryEntry() {
        val d=scene(); val e=engine(d); val before=stack(d)
        val after=LayerStack(listOf(LayerState("lower","Lower")),"lower")
        assertNotNull(e.flattenStep("lower",listOf("lower","upper"),emptyList(),before,after))
        val flattened=e.boardDocument!!
        assertEquals(1,flattened.layers.size); assertEquals(1,flattened.boards.count { it.kind==BoardKind.ANIMATION })
        assertEquals(1,e.undo.undoDepth)
        assertTrue(e.undoStep()); assertEquals(d,e.boardDocument)
        assertTrue(e.redoStep()); assertEquals(flattened,e.boardDocument)
    }
    @Test fun frameOnlyMergeDetachesTheCurrentLinkedFrameAndUndoRestoresAliases() {
        var d=scene(); val a=d.boards.first { it.name=="A" }
        // Move the other clip away before the lower layer starts animating in A.
        val b=d.boards.first { it.name=="B" }
        d=AnimationBoardOps.moveAll(d,b.id,100,100).doc
        d=RegionDocumentOps.setHeld(d,a.id,"lower",false).doc
        val e=engine(d); val before=stack(d)
        e.mergeDownStep("upper","lower",MergeDownPlan.Way.MOVE_ITEMS,"normal",false,before,before,a.id)
        val next=e.boardDocument!!
        assertEquals(2,next.layers.size)
        for(l in next.layers) {
            val old=d.layers.first { it.id==l.id }.regions.first { it.boardId==a.id }
            val region=l.regions.first { it.boardId==a.id }
            assertNotEquals(old.frameCel[a.currentFrameId],region.frameCel[a.currentFrameId])
            for(f in a.frames.filter { it.id!=a.currentFrameId }) assertEquals(old.frameCel[f.id],region.frameCel[f.id])
        }
        assertEquals(1,e.undo.undoDepth)
        assertTrue(e.undoStep()); assertEquals(d,e.boardDocument)
        assertTrue(e.redoStep()); assertEquals(next,e.boardDocument)
    }
    @Test fun flattenPreservesSharedLinesOnKeptStillLayerAndRetiresOnlyOldHeldFrameItems() {
        val source=scene()
        val clips=source.boards.filter { it.kind==BoardKind.ANIMATION }
        val mappings=clips.associate { b -> b.id to b.frames.associate { it.id to id() } }
        val kept=Layer("kept","Hidden kept",LayerKind.PAINT,visible=false,sharedCelId="kept-shared",
            cels=listOf(Cel("kept-shared"))+mappings.values.flatMap { it.values }.map { Cel(it) },
            regions=clips.map { RegionFrames(it.id,mappings.getValue(it.id),held=true) })
        val d=source.copy(layers=source.layers+kept)
        val e=GlPaintEngine().apply {
            initWith { }
            setStack(LayerStack(d.layers.map { LayerState(it.id,it.name,visible=it.visible) },d.activeLayerId!!))
            setBoardDocument(d)
        }
        val shared=LinePlane("kept","kept-shared")
        val retired=LinePlane("kept",kept.regions.first().frameCel.values.first())
        val keepLine=StrokeRecord("keep","frozen",19L,0f,1f,listOf(PenSample(20f,20f,0.0)),seq=1L)
        val retiredLine=keepLine.copy(id="old-held-frame")
        e.nativeLines.put(shared,keepLine.id,keepLine)
        e.nativeLines.put(retired,retiredLine.id,retiredLine)
        val before=e.stack("lower")
        val after=LayerStack(before.layers.filter { it.id in listOf("lower","kept") },"lower")
        e.flattenStep("lower",listOf("lower","upper"),emptyList(),before,after)
        assertEquals(1,e.undo.undoDepth)
        assertEquals(keepLine,e.nativeLines.record(shared,keepLine.id),"held metadata must not freeze the kept layer's shared items")
        assertNull(e.nativeLines.record(retired,retiredLine.id),"orphaned held-frame items retire with their old cel graph")
        assertEquals("kept-shared",e.boardDocument!!.layers.first { it.id=="kept" }.sharedCelId)
        assertTrue(e.undoStep()); assertEquals(d,e.boardDocument)
        assertEquals(keepLine,e.nativeLines.record(shared,keepLine.id))
        assertEquals(retiredLine,e.nativeLines.record(retired,retiredLine.id))
        assertTrue(e.redoStep())
        assertEquals(keepLine,e.nativeLines.record(shared,keepLine.id))
        assertNull(e.nativeLines.record(retired,retiredLine.id))
    }

}

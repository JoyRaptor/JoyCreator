package cc.joycreator.joybrush.core.doc

import kotlin.test.*

class ComponentCelTest {
    private var n=0
    private fun id()="part-${++n}"
    @Test fun changingSavedEndUpgradesAnOlderDocumentVersion() {
        val d=RegionDocumentOps.create(DocOps.newDocument("doc","Old",16,16,::id),"Clip",RectPx(0,0,8,8),::id).doc.copy(version=11)
        val changed=BoardDocumentOps.setEnd(d,d.activeBoardId!!,"blank")
        assertEquals(12,changed.version)
        assertEquals("blank",DocJson.decode(DocJson.encode(changed)).boards.first { it.id==d.activeBoardId }.end)
    }
    private fun combined(): JbDocument {
        var d=DocOps.newDocument("doc","Parts",512,512,::id)
        d=RegionDocumentOps.create(d,"Character",RectPx(0,0,10,10),::id).doc
        val a=d.activeBoardId!!
        d=RegionDocumentOps.addFrame(d,a,NewFrame.BLANK,::id).doc
        d=RegionDocumentOps.create(d,"Background",RectPx(10,0,10,10),::id).doc
        val b=d.activeBoardId!!
        repeat(3) { d=RegionDocumentOps.addFrame(d,b,NewFrame.BLANK,::id).doc }
        return AnimationBoardOps.combine(d,a,b,::id).doc
    }
    @Test fun sameLayerComponentsRetainDrawingIdentityAcrossDifferentCompositesAndSave() {
        val d=DocJson.decode(DocJson.encode(combined()))
        val b=d.boards.first { it.kind==BoardKind.ANIMATION }; val l=d.layers.single(); val region=l.regions.single()
        val first=RegionDocumentOps.paintPlan(d,l.id,mapOf(b.id to b.frames[0].id))
        val third=RegionDocumentOps.paintPlan(d,l.id,mapOf(b.id to b.frames[2].id))
        assertNotEquals(region.frameCel[b.frames[0].id],region.frameCel[b.frames[2].id])
        assertEquals(first.planeAt(2,2).celId,third.planeAt(2,2).celId,"the character drawing is ONE physical cel in both composites")
        assertNotEquals(first.planeAt(12,2).celId,third.planeAt(12,2).celId,"the other drawing changes independently")
        assertTrue(DocOps.validate(d).isEmpty(),DocOps.validate(d).toString())
        assertEquals(13,d.version)
    }
    @Test fun deletingACompositeKeepsComponentsReferencedByOtherFrames() {
        val d=combined(); val b=d.boards.first { it.kind==BoardKind.ANIMATION }; val l=d.layers.single()
        val used=RegionDocumentOps.paintPlan(d,l.id,mapOf(b.id to b.frames[2].id)).planeAt(2,2).celId
        val change=RegionDocumentOps.deleteFrame(d,b.id,b.frames[0].id)
        assertTrue(change.doc.layers.single().cels.any { it.id==used }); assertTrue(change.drops.none { it.celId==used })
        assertTrue(DocOps.validate(change.doc).isEmpty())
    }
    @Test fun duplicateDetachesTheWholeGraphAndMoveTranslatesItsGeometry() {
        val d=combined(); val b=d.boards.first { it.kind==BoardKind.ANIMATION }; val l=d.layers.single()
        val copy=RegionDocumentOps.addFrame(d,b.id,NewFrame.DUPLICATE,::id)
        val clone=copy.doc.boards.first { it.id==b.id }.currentFrameId!!
        val before=RegionDocumentOps.paintPlan(d,l.id)
        val after=RegionDocumentOps.paintPlan(copy.doc,l.id,mapOf(b.id to clone))
        assertNotEquals(before.planeAt(2,2).celId,after.planeAt(2,2).celId)
        assertTrue(copy.copies.size>=3,"root and both physical drawings are copied")
        val move=AnimationBoardOps.moveAll(d,b.id,100,50)
        val moved=RegionDocumentOps.paintPlan(move.doc,l.id)
        assertEquals(before.planeAt(2,2).celId,moved.planeAt(102,52).celId)
        assertEquals(before.planeAt(12,2).celId,moved.planeAt(112,52).celId)
        assertEquals(moved.shared,moved.planeAt(2,2))
        assertEquals(move.doc,AnimationBoardOps.previewMove(d,b.id,100,50))
        assertTrue(DocOps.validate(move.doc).isEmpty())
    }
    @Test fun duplicateBoardAndRemovalUsePhysicalFragmentAddresses() {
        val d=combined(); val b=d.boards.first { it.kind==BoardKind.ANIMATION }; val l=d.layers.single()
        val copy=AnimationBoardOps.duplicate(d,b.id,100,50,::id)
        assertTrue(DocOps.validate(copy.doc).isEmpty(),DocOps.validate(copy.doc).toString())
        val newBoard=copy.doc.boards.last()
        val oldGraph=CelParts.reachable(l,l.regions.single().frameCel.values)
        val copiedLayer=copy.doc.layers.single()
        val newGraph=CelParts.reachable(copiedLayer,copiedLayer.regions.first { it.boardId==newBoard.id }.frameCel.values)
        assertTrue(oldGraph.intersect(newGraph).isEmpty())
        val removed=AnimationBoardOps.remove(d,b.id)
        assertEquals(CelParts.reachable(l,listOf(l.regions.single().frameCel.getValue(b.currentFrameId!!))).size,
            removed.drops.count { it.celId in CelParts.reachable(l,listOf(l.regions.single().frameCel.getValue(b.currentFrameId!!))) })
        assertTrue(removed.transfers.size>=2)
        assertTrue(DocOps.validate(removed.doc).isEmpty())
    }
    @Test fun malformedAndCrossBoardGraphsAreRefused() {
        val d=combined(); val l=d.layers.single(); val c=l.cels.first { it.parts.isNotEmpty() }
        val bad=d.copy(layers=listOf(l.copy(cels=l.cels.map { if(it.id==c.id) it.copy(parts=listOf(CelPart(c.id,RectPx(0,0,10,10)))) else it })))
        assertTrue(DocOps.validate(bad).any { it.contains("Circular") })
        assertFailsWith<DocException> { RegionDocumentOps.setHold(d,d.activeBoardId!!,"",1) }
    }

    @Test fun sharedCanvasCannotBorrowFrameComponentsThatSharedRoutingWouldIgnore() {
        val d=DocJson.decode(DocJson.encode(combined()))
        val layer=d.layers.single()
        val component=layer.cels.first { it.parts.isNotEmpty() }.parts.first()
        val malformed=d.copy(layers=listOf(layer.copy(cels=layer.cels.map { cel ->
            if(cel.id==layer.sharedCelId) cel.copy(parts=listOf(component)) else cel
        })))
        val reopened=DocJson.decode(DocJson.encode(malformed))
        assertTrue(DocOps.validate(reopened).any { "shared canvas cannot contain linked drawing pieces" in it })
        assertFailsWith<DocException> { RegionDocumentOps.paintPlan(reopened,layer.id) }
        assertTrue(DocOps.validate(d).isEmpty(),"ordinary frame components remain valid after save/reopen")
    }

    @Test fun frameOnlyMergePreparationDetachesOnlyTheCurrentComposite() {
        val d=combined(); val b=d.boards.first { it.kind==BoardKind.ANIMATION }; val l=d.layers.single()
        val other=b.frames.first { it.id!=b.currentFrameId }.id
        val before=RegionDocumentOps.paintPlan(d,l.id,mapOf(b.id to other))
        val change=AnimationBoardOps.isolateFrame(d,b.id,l.id,false,::id)
        val current=RegionDocumentOps.paintPlan(change.doc,l.id)
        val untouched=RegionDocumentOps.paintPlan(change.doc,l.id,mapOf(b.id to other))
        assertNotEquals(RegionDocumentOps.paintPlan(d,l.id).planeAt(2,2).celId,current.planeAt(2,2).celId)
        assertEquals(before.frames,untouched.frames)
        assertTrue(change.copies.size>=3)
        val emptied=AnimationBoardOps.isolateFrame(d,b.id,l.id,true,::id)
        assertEquals(before.frames,RegionDocumentOps.paintPlan(emptied.doc,l.id,mapOf(b.id to other)).frames)
        val root=emptied.doc.layers.single().regions.single().frameCel.getValue(b.currentFrameId!!)
        assertTrue(emptied.doc.layers.single().cels.first { it.id==root }.parts.isEmpty())
        assertTrue(emptied.copies.isEmpty())
        assertTrue(DocOps.validate(change.doc).isEmpty())
        assertTrue(DocOps.validate(emptied.doc).isEmpty())
    }
}

package cc.joycreator.joybrush.core.doc

import kotlin.test.*

class AnimationCombineTest {
    private var n = 0
    private fun id() = "clip-${++n}"
    private fun pair(): JbDocument {
        val d = DocOps.newDocument("doc", "Clips", 1024, 1024, ::id)
        val bg = d.layers.single().copy(id = "bg", name = "Background")
        val ch = Layer("ch", "Character", LayerKind.PAINT, cels = listOf(Cel("ch-shared")))
        var next = BoardDocumentOps.createAnimation(d.copy(layers = listOf(bg, ch), activeLayerId = "ch"), "Walk", RectPx(0,0,256,256), ::id).doc
        next = RegionDocumentOps.addFrame(next, next.activeBoardId!!, NewFrame.BLANK, ::id).doc
        return BoardDocumentOps.createAnimation(next.copy(activeLayerId = "bg"), "Sky", RectPx(128,128,512,512), ::id).doc
    }
    @Test fun unionTimingLinksStillAreaAndOldClipsRemoved() {
        val d = pair(); val clips = d.boards.filter { it.kind == BoardKind.ANIMATION }
        val a = clips[0]; val b = clips[1]
        val tuned = d.copy(boards = d.boards.map { if (it.id == a.id) it.copy(fps=24f) else if (it.id == b.id) it.copy(fps=6f) else it })
        val change = AnimationBoardOps.combine(tuned, a.id, b.id, ::id)
        val out = change.doc; val stage = out.boards.single { it.kind == BoardKind.ANIMATION }
        assertEquals(RectPx(0,0,640,640), stage.rect)
        assertEquals("Sky", stage.name); assertEquals(24f, stage.fps); assertEquals(4, stage.frames.size)
        assertEquals(stage.id, out.activeBoardId)
        assertTrue(DocOps.validate(out).isEmpty(), DocOps.validate(out).toString())
        val ch = out.layers.first { it.id == "ch" }.regions.single()
        val cels = stage.frames.map { ch.frameCel.getValue(it.id) }
        assertEquals(cels[0], cels[2]); assertEquals(cels[1], cels[3]); assertNotEquals(cels[0],cels[1])
        val bg = out.layers.first { it.id == "bg" }.regions.single()
        assertEquals(1,bg.frameCel.values.toSet().size)
        assertTrue(change.transfers.any { it.layerId == "ch" && it.fromCelId == out.layers.first { it.id == "ch" }.sharedCelId })
        assertEquals(2,change.clears.size)
        assertTrue(change.drops.isNotEmpty())
    }
    @Test fun endRulesAtRetimedTicksAndLinkedDrawings() {
        val b = Board("a","A",BoardKind.ANIMATION,RectPx(0,0,10,10),fps=6f,
            frames=listOf(Frame("a",1),Frame("b",2),Frame("c",1)))
        fun ticks(end: String) = ClipTimeline(b.copy(end=end),12f).let { t -> (0..15).map { t.frameAt(it) } }
        assertEquals(listOf("a","a","b","b","b","b","c","c"),ticks("loop").take(8))
        assertEquals("a",ticks("loop")[8]); assertEquals("b",ticks("pingpong")[8])
        assertEquals("c",ticks("hold")[15]); assertNull(ticks("blank")[8])
    }
    @Test fun directMoveLeavesStillArtworkAndMasksInPlace() {
        val d = pair(); val clip = d.boards.first { it.kind == BoardKind.ANIMATION }
        val move = AnimationBoardOps.moveAll(d,clip.id,-20,-40)
        assertEquals(setOf("ch"), move.transfers.map { it.layerId }.toSet())
        assertTrue(move.maskTransfers.isEmpty()); assertTrue(move.maskClears.isEmpty())
        assertEquals(clip.rect.copy(x=-20,y=-40),move.doc.boards.first { it.id == clip.id }.rect)
    }
    @Test fun blankPreviewDoesNotRemoveOwnershipOrModifySavedFrames() {
        val d = pair(); val b = d.boards.first { it.kind == BoardKind.ANIMATION }
        val plan = RegionDocumentOps.paintPlan(d,"ch",mapOf(b.id to RegionDocumentOps.BLANK_PREVIEW))
        assertTrue(plan.frames.single().blank)
        assertTrue(plan.tileSlices(cc.joycreator.joybrush.core.paint.Tiles.key(0,0)).single().plane.blank)
        assertEquals(b.currentFrameId,d.boards.first { it.id == b.id }.currentFrameId)
        assertTrue(RegionDocumentOps.paintPlan(d,"bg",mapOf(b.id to "")).frames.none { it.boardId == b.id })
    }
    @Test fun touchingSameLayerCombinesButOverlappingSameLayerIsRefused() {
        var d = pair(); val a = d.boards.first { it.kind == BoardKind.ANIMATION }
        val b = d.boards.last()
        d = d.copy(boards=d.boards.map { if(it.id==b.id) it.copy(rect=RectPx(256,0,256,256)) else it })
        d = RegionDocumentOps.setHeld(d,b.id,"ch",false).doc
        assertTrue(DocOps.validate(AnimationBoardOps.combine(d,a.id,b.id,::id).doc).isEmpty())
    }
}

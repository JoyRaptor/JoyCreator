package cc.joycreator.joybrush.core.doc

import kotlin.test.*

class AnimationFlattenPlanTest {
    private var n=0
    private fun id()="flatten-${++n}"
    private fun source(): JbDocument {
        val d=DocOps.newDocument("doc","Scene",2048,2048,::id)
        val a=d.layers.single().copy(id="a",name="Character")
        val b=Layer("b","Sky",LayerKind.PAINT,cels=listOf(Cel("b-shared")))
        var doc=BoardDocumentOps.createAnimation(d.copy(layers=listOf(a,b),activeLayerId="a"),"Walk",RectPx(0,0,256,256),::id).doc
        doc=RegionDocumentOps.addFrame(doc,doc.activeBoardId!!,NewFrame.BLANK,::id).doc
        return BoardDocumentOps.createAnimation(doc.copy(activeLayerId="b"),"Sky",RectPx(128,128,512,512),::id).doc
    }
    @Test fun combinedSamplesReferOnlyToOriginalDrawingsAndStillLayersRemainStill() {
        val doc=source(); val old=doc.boards.filter { it.kind == BoardKind.ANIMATION }
        val plan=AnimationFlattenPlan.build(doc,setOf("a","b"),::id)
        val stage=plan.doc.boards.single { it.kind == BoardKind.ANIMATION }
        assertEquals(RectPx(0,0,640,640),stage.rect)
        assertEquals(2,stage.frames.size)
        assertEquals(old.map { it.id }.toSet(),plan.samples.getValue(stage.id to stage.frames.first().id).keys)
        assertEquals(old[0].frames[0].id,plan.samples.getValue(stage.id to stage.frames.first().id)[old[0].id])
        assertEquals(old[0].frames[1].id,plan.samples.getValue(stage.id to stage.frames.last().id)[old[0].id])
        assertTrue(DocOps.validate(plan.doc).isEmpty())
        assertEquals(2,doc.boards.count { it.kind == BoardKind.ANIMATION },"plan never changes its input")
    }
    @Test fun nonOverlappingClipsKeepTheirBoardsAndFrameIds() {
        val doc=source().let { d -> d.copy(boards=d.boards.map { if(it.name=="Sky") it.copy(rect=RectPx(1024,0,512,512)) else it }) }
        val plan=AnimationFlattenPlan.build(doc,setOf("a","b"),::id)
        assertEquals(doc,plan.doc); assertTrue(plan.combinedNames.isEmpty())
    }
    @Test fun overlappingClipOnAnUnmergedLayerKeepsItsIndependentTimeline() {
        val doc=source()
        val plan=AnimationFlattenPlan.build(doc,setOf("a"),::id)
        assertEquals(doc,plan.doc)
        assertTrue(plan.combinedNames.isEmpty())
    }
    @Test fun unionIncludesAThirdClipWhenAHiddenLayersOwnershipRequiresIt() {
        val clips=listOf(
            Board("a","A",BoardKind.ANIMATION,RectPx(0,0,10,10),frames=listOf(Frame("af")),currentFrameId="af"),
            Board("b","B",BoardKind.ANIMATION,RectPx(8,8,10,10),frames=listOf(Frame("bf")),currentFrameId="bf"),
            Board("c","C",BoardKind.ANIMATION,RectPx(12,0,3,3),frames=listOf(Frame("cf")),currentFrameId="cf"))
        fun layer(id:String,animated:Set<String>)=Layer(id,id,LayerKind.PAINT,
            cels=listOf(Cel("$id-shared"))+clips.map { Cel("$id-${it.id}") },sharedCelId="$id-shared",
            regions=clips.map { RegionFrames(it.id,mapOf(it.frames.single().id to "$id-${it.id}"),held=it.id !in animated) })
        val doc=JbDocument(id="d",name="Union",boards=clips,
            layers=listOf(layer("one",setOf("a")),layer("two",setOf("b")),layer("hidden",setOf("a","c")).copy(visible=false)))
        assertTrue(DocOps.validate(doc).isEmpty())
        val plan=AnimationFlattenPlan.build(doc,setOf("one","two"),::id)
        assertEquals(1,plan.doc.boards.size)
        assertEquals(setOf("a","b","c"),plan.samples.values.single().keys)
        assertTrue(DocOps.validate(plan.doc).isEmpty())
    }
}

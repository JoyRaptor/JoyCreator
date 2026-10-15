package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.androidkit.gl.lines.RecordedScene
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.RecordedCel
import kotlin.test.*

/** Snapshot admission and EMPTY source snapshot only. Synthetic handles are never read with GL. */
class GlRecordedRootSnapshotTest {
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner:Any,name:String):T = owner.javaClass.getDeclaredField(name).also { it.isAccessible=true }.get(owner) as T
    private fun set(owner:Any,name:String,value:Any?) = owner.javaClass.getDeclaredField(name).also { it.isAccessible=true }.set(owner,value)
    private fun fixture(regional:Boolean=false):Pair<GlPaintEngine,JbDocument> {
        val ids=ArrayDeque(listOf("board","paint","shared"))
        var doc=DocOps.newDocument("doc","Art",512,512) { ids.removeFirst() }
        if(regional) { var serial=0; doc=RegionDocumentOps.create(doc,"Loop",RectPx(0,0,256,256)) { "r-${++serial}" }.doc
            val board=doc.boards.single { it.kind==BoardKind.ANIMATION }
            doc=RegionDocumentOps.addFrame(doc,board.id,NewFrame.BLANK) { "r-${++serial}" }.doc }
        return GlPaintEngine().also { it.initWith {}; it.addLayer("paint"); it.setBoardDocument(doc) } to doc
    }
    private fun assertRefusedBeforeRead(e:GlPaintEngine) {
        val root=field<Any>(e,"publishedPaint"); val cursor=e.undo.snapshotCursor()
        val pools=listOf("freeLayerTex","freeStrokeTex","freeSmudgeTex","freeFloatTex")
            .associateWith { field<ArrayDeque<Int>>(e,it).toList() }
        assertFails { e.snapshotJointRecordedArtwork() }
        assertSame(root,field(e,"publishedPaint")); assertSame(cursor,e.undo.snapshotCursor())
        pools.forEach { (name,values) -> assertEquals(values,field<ArrayDeque<Int>>(e,name).toList()) }
        assertEquals(0L,e.recordedLinePreparationCalls); assertEquals(0L,e.recordedCompositionCalls)
    }
    @Test fun emptyPromotedRootCapturesCanonicalSharedAndEveryEmptyInactiveFrameWithoutGL() {
        for(regional in listOf(false,true)) {
            val(e,doc)=fixture(regional); assertTrue(e.preparePlainRecordingPromotion("paint").publish())
            val root=field<Any>(e,"publishedPaint"); val cursor=e.undo.snapshotCursor()
            val captured=e.snapshotJointRecordedArtwork()
            assertEquals(doc.id,captured.doc.id); assertEquals(doc.boards,captured.doc.boards)
            assertEquals(doc.layers.map { it.cels.map { cel -> cel.id } },captured.doc.layers.map { it.cels.map { cel -> cel.id } })
            assertTrue(captured.tiles.isEmpty()); assertTrue(captured.strokes.isEmpty()); assertTrue(captured.slabs.isEmpty())
            assertTrue(captured.looks.isEmpty()); assertTrue(captured.brushes.isEmpty()); assertTrue(captured.mediaTiles.isEmpty())
            assertSame(root,field(e,"publishedPaint")); assertSame(cursor,e.undo.snapshotCursor())
            assertEquals(0L,e.recordedLinePreparationCalls); assertEquals(0L,e.recordedCompositionCalls)
        }
    }
    @Test fun completeDocumentMustDescribeDisjointNativeOwnersBeforeAnySourceRead() {
        val(e,_)=fixture(); e.addLayer("unlisted")
        val owners=field<Map<String,Any>>(e,"localLayers")
        field<MutableMap<Long,Int>>(owners.getValue("paint"),"tiles")[0]=10
        assertTrue(e.preparePlainRecordingPromotion("paint").publish()); assertRefusedBeforeRead(e)
    }
    @Test fun sourceFloatPoolGlobalAndHistoryAliasRefuseBeforeAnyGLRead() {
        for(kind in listOf("float","pool","global","history")) {
            val(e,_)=fixture(); val owner=field<Map<String,Any>>(e,"localLayers").getValue("paint")
            field<MutableMap<Long,Int>>(owner,"tiles")[0]=10
            assertTrue(e.preparePlainRecordingPromotion("paint").publish())
            when(kind) {
                "float" -> field<MutableSet<Int>>(e,"floatNames").add(10)
                "pool" -> field<ArrayDeque<Int>>(e,"freeLayerTex").addLast(10)
                "global" -> set(e,"clearTex",10)
                "history" -> {
                    // Deliberately corrupt only the immutable root's cursor for this refusal fixture.
                    val root=field<Any>(e,"publishedPaint")
                    val step=cc.joycreator.joybrush.core.paint.UndoLog.Step(listOf(
                        cc.joycreator.joybrush.core.paint.UndoLog.TileChange("paint",0,10,11)))
                    val proposal=e.undo.prepareNew(step,e.undo.snapshotCursor())
                    set(root,"cursor",proposal.cursor)
                }
            }
            assertRefusedBeforeRead(e)
        }
    }
    @Test fun coldUnmeasuredRecordedIndexRefusesInsteadOfReplayingOrSavingAnUnverifiedLook() {
        val(e,_)=fixture(); val plane:Pair<String,String?> = "paint" to null
        val brush=BrushPreset(id="frozen",name="Pen",size=Param(4f))
        val record=StrokeRecord("line",brush.id,19,0f,1f,listOf(PenSample(0f,0f,0.0)),seq=1)
        var contexts=0; var releases=0
        val renderer=object:RecordedScene.Renderer<Int> {
            override fun contextCurrent():Boolean { contexts++; return true }
            override fun line(address:LineAddress,record:StrokeRecord,preset:BrushPreset):RecordedScene.Unit<Int> = error("must not replay")
            override fun look(plane:Pair<String,String?>,key:Long,sources:List<RecordedScene.Source<Int>>):Int = error("must not compose")
            override fun release(texture:Int) { releases++ }
        }
        val scene=RecordedScene.createSaved(mapOf(plane to RecordedCel("paint",null,listOf(record))),
            mapOf(brush.id to brush),e.undo,renderer,mapOf(plane to setOf(0L)),mapOf(plane to mapOf(0L to 50)))
        e.registerRecordedScene(scene); e.bindRecordedPaintPublication(); contexts=0
        assertRefusedBeforeRead(e); assertEquals(0,contexts); assertEquals(0,releases)
        assertFalse(scene.isMaterialized(plane)); assertEquals(50,scene.look(plane,0))
    }
}

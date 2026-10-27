package cc.joycreator.joybrush.core.export

import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.anim.PlaybackClock
import kotlin.test.*

class SceneAnimExportPlanTest {
    private fun b(id:String,n:Int,end:String="loop",fps:Float=12f)=Board(id,id,BoardKind.ANIMATION,RectPx(0,0,256,256),fps=fps,
        frames=List(n) { Frame("$id-$it") },currentFrameId="$id-0",end=end)
    private fun doc(vararg clips:Board)=JbDocument(id="d",name="Scene",boards=clips.toList(),layers=emptyList())
    @Test fun fullLoopIsLcmAndBlankDoesNotChangeSavedCursors() {
        val a=b("a",3); val blank=b("b",4,"blank"); val c=b("c",5)
        val d=doc(a,blank,c)
        val normal=SceneAnimExportPlan.build(d)
        assertEquals(5,normal.previews.size); assertEquals("",normal.previews[4]["b"])
        val full=SceneAnimExportPlan.build(d,true)
        assertEquals(15,full.previews.size); assertFalse(full.capped)
        assertEquals("a-0",full.previews[3]["a"])
        assertEquals("a-0",d.boards.first().currentFrameId)
    }
    @Test fun fullLoopCapIsExplicitAndPingPongUsesBothLegs() {
        val full=SceneAnimExportPlan.build(doc(b("a",29),b("b",31)),true)
        assertEquals(600,full.previews.size); assertTrue(full.capped)
        val ping=SceneAnimExportPlan.build(doc(b("p",4,"pingpong"),b("l",5)),true)
        assertEquals(30,ping.previews.size)
        val source=b("p",4,"pingpong")
        val clock=PlaybackClock(source)
        ping.previews.forEachIndexed { i, frames ->
            assertEquals(source.frames[clock.frameIndexAt(i*1000.0/ping.fps)].id,frames["p"],"tick $i")
        }
    }
    @Test fun sceneSamplesIndependentClocksWithoutRoundingEachDrawing() {
        val slow=b("slow",24,fps=24f)
        val fast=b("fast",60,fps=60f)
        val plan=SceneAnimExportPlan.build(doc(slow,fast))
        assertEquals(60,plan.previews.size)
        assertEquals(1000,plan.previews.indices.sumOf { plan.delayMsAt(it) })
        for(source in listOf(slow,fast)) {
            val clock=PlaybackClock(source)
            plan.previews.forEachIndexed { i,frames ->
                assertEquals(source.frames[clock.frameIndexAt(i*1000.0/plan.fps)].id,frames[source.id],"${source.id}, tick $i")
            }
        }
        assertEquals(24,plan.previews.map { it[slow.id] }.distinct().size)
    }
    @Test fun sameCadenceNeverSkipsADrawingAtAnExactExposureBoundary() {
        for(fps in 1..60) {
            val source=b("clip",120,fps=fps.toFloat())
            val plan=SceneAnimExportPlan.build(doc(source))
            val clock=PlaybackClock(source)
            assertEquals(120,plan.previews.size,"fps $fps")
            assertEquals(source.frames.map { it.id },plan.previews.map { it[source.id] },"fps $fps")
            val starts=AnimOps.frameStartsMs(source)
            starts.forEachIndexed { i, ms ->
                assertEquals(i*1000.0/fps,ms,"fps $fps, drawing $i has its canonical tick boundary")
                assertEquals(i,clock.frameIndexAt(ms),"fps $fps, drawing $i starts at its boundary")
            }
            assertEquals(120_000.0/fps,AnimOps.totalDurationMs(source))
        }
    }
    @Test fun fullLoopEndsOnCycleBoundaryAfterFiniteClipsFinish() {
        val plan=SceneAnimExportPlan.build(doc(b("loop",4),b("hold",5,"hold")),true)
        assertEquals(8,plan.previews.size)
        assertTrue(plan.loops)
        assertFalse(plan.capped)
        assertEquals("hold-4",plan.previews.last()["hold"])
    }
    @Test fun fullLoopWithOnlyFiniteClipsDoesNotRequestGifLooping() {
        val plan=SceneAnimExportPlan.build(doc(b("hold",5,"hold"),b("blank",3,"blank")),true)
        assertEquals(5,plan.previews.size)
        assertFalse(plan.loops)
        assertEquals(RegionDocumentOps.BLANK_PREVIEW,plan.previews.last()["blank"])
    }
    @Test fun gifPlansCarrySavedEndAndUnknownValuesReadAsLoop() {
        for(end in BoardEnd.ALL) assertEquals(end,AnimExport.plan(b("a",3,end),"A").end)
        val d=DocJson.decode(DocJson.encode(doc(b("a",3,"spin"))))
        assertEquals("loop",d.boards.single().end)
    }
}

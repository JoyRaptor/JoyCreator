package cc.joycreator.joybrush.core.paint

import cc.joycreator.joybrush.core.doc.*
import kotlin.test.*

class RegionUndoTest {
    @Test fun sharedAndTwoBoardPlanesOnOneTileUndoAsOneStrokeWithoutCollisions() {
        val released=mutableListOf<Int>();val log=UndoLog<Int>(100,{1},released::add)
        log.push(UndoLog.Step(listOf(
            UndoLog.TileChange("layer",0,1,2,"shared"),UndoLog.TileChange("layer",0,3,4,"frame-a"),
            UndoLog.TileChange("layer",0,5,6,"frame-b"))))
        assertEquals(1,log.undoDepth)
        val step=assertNotNull(log.undo());assertEquals(listOf("shared","frame-a","frame-b"),step.changes.map{it.celId})
        assertSame(step,log.redo());assertTrue(released.isEmpty())
    }
    @Test fun mergingGestureKeepsPlanesDistinctAndOldestMetadata() {
        var i=0;val a=DocOps.newDocument("d","A",20,20){"id-${++i}"};val b=a.copy(name="B");val c=a.copy(name="C")
        val released=mutableListOf<Int>();val log=UndoLog<Int>(100,{1},released::add)
        log.push(UndoLog.Step(listOf(UndoLog.TileChange("l",0,1,2,"shared")),documentBefore=a,documentAfter=b))
        log.push(UndoLog.Step(listOf(UndoLog.TileChange("l",0,2,3,"shared"),UndoLog.TileChange("l",0,4,5,"frame")),documentBefore=b,documentAfter=c))
        log.mergeNewest(2);val step=assertNotNull(log.undo())
        assertEquals(2,step.changes.size);assertEquals(a,step.documentBefore);assertEquals(c,step.documentAfter)
        assertEquals(listOf(2),released);assertEquals(1,step.changes.first().before)
    }
}

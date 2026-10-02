package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.paint.UndoLog
import kotlin.test.*

class GlPaperHistoryTest {
    @Test fun visitIsOneUndoAndRedoWithoutPaintTextureChanges() {
        val engine=GlPaintEngine();val before=Paper();val after=before.copy(color="#306090",screenTransparent=true)
        engine.setDocumentPaper(after)
        engine.recordPaperChange(before,after)
        assertEquals(1,engine.undo.undoDepth);assertEquals(0L,engine.undo.heldBytes)
        assertEquals(0,engine.heldTextureNames())
        assertTrue(engine.undoStep());assertEquals(before,engine.documentPaper)
        assertTrue(engine.redoStep());assertEquals(after,engine.documentPaper)
        assertEquals(0,engine.heldTextureNames())
        engine.recordPaperChange(after,after);assertEquals(1,engine.undo.undoDepth)
    }
    @Test fun paintAndPaperUndoInOrderAndNewEditDropsRedo() {
        val engine=GlPaintEngine();val a=Paper();val b=a.copy(color="#306090");val c=a.copy(color="#903060")
        engine.undo.push(UndoLog.Step(listOf(UndoLog.TileChange("paint",0L,null,42))))
        engine.setDocumentPaper(b);engine.recordPaperChange(a,b)
        assertTrue(engine.undoStep());assertEquals(a,engine.documentPaper)
        assertTrue(engine.undoStep());assertEquals(a,engine.documentPaper)
        assertTrue(engine.redoStep());assertEquals(a,engine.documentPaper)
        engine.setDocumentPaper(c);engine.recordPaperChange(a,c)
        assertFalse(engine.undo.canRedo);assertEquals(c,engine.documentPaper)
    }
}

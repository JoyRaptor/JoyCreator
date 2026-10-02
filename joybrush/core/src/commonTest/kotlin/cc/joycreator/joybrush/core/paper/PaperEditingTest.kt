package cc.joycreator.joybrush.core.paper

import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.paint.UndoLog
import kotlin.test.*

class PaperEditingTest {
    @Test fun transparentIsSavedIndependentlyFromFlatShowAndExport() {
        val paper=Paper(color="#123456",show=0f,bite=.4f,screenTransparent=true)
        val doc=JbDocument(id="d",name="Drawing",paper=paper,
            boards=listOf(Board("b","Canvas",BoardKind.CANVAS,RectPx(0,0,10,10))),
            layers=listOf(Layer("l","Paint",LayerKind.PAINT,cels=listOf(Cel("c")))))
        assertEquals(doc,DocJson.decode(DocJson.encode(doc)))
        assertTrue(DocOps.validate(doc.copy(version=6)).isNotEmpty())
        val catalogue=PaperCatalogue(surfaces=emptyList(),looks=emptyList())
        assertTrue(PaperState.resolve(paper,catalogue).screenTransparent)
        assertFalse(PaperState.resolve(paper.copy(screenTransparent=false),catalogue).screenTransparent)
    }
    @Test fun transparentPreviewsDoNotLoadTexturesAndColourRestoresPaper() {
        val previews=PaperPreviews(render={_,_->error("Checker must not load material")})
        val paper=ResolvedPaper(null,null,0xFF123456.toInt(),1f,0f,.4f,false,screenTransparent=true)
        val crop=previews.crop(paper,16)
        assertEquals(204,crop[0].toInt() and 255)
        assertEquals(230,crop[8*4].toInt() and 255)
        assertEquals(255,crop[3].toInt() and 255)
        crop[0]=0
        assertEquals(204,previews.crop(paper,16)[0].toInt() and 255)
        val chosen=PaperPreviews.customColour(Paper(screenTransparent=true,bite=.4f),"#306090")
        assertFalse(chosen.screenTransparent);assertEquals(.4f,chosen.bite)
    }
    @Test fun paperVisitSharesPaintChronologyWithoutOwningTiles() {
        val released=mutableListOf<Int>(); val log=UndoLog<Int>(1024,{4L},{released+=it})
        val before=Paper();val after=before.copy(color="#306090",bite=.4f)
        log.push(UndoLog.Step(listOf(UndoLog.TileChange("layer",1,null,42))))
        log.push(UndoLog.Step(emptyList(),paperBefore=before,paperAfter=after))
        assertEquals(2,log.undoDepth);assertEquals(0L,log.heldBytes)
        val paperStep=log.undo()!!;assertTrue(paperStep.changes.isEmpty());assertEquals(before,paperStep.paperBefore)
        assertEquals(42,log.undo()!!.changes.single().after)
        assertEquals(42,log.redo()!!.changes.single().after)
        assertEquals(after,log.redo()!!.paperAfter);assertTrue(released.isEmpty())
    }
    @Test fun mergedVisitsKeepFirstAndLastPaperSettings() {
        val log=UndoLog<Int>(1024,{4L},{})
        val a=Paper();val b=a.copy(color="#306090");val c=b.copy(screenTransparent=true)
        log.push(UndoLog.Step(emptyList(),paperBefore=a,paperAfter=b))
        log.push(UndoLog.Step(emptyList(),paperBefore=b,paperAfter=c))
        log.mergeNewest(2)
        assertEquals(1,log.undoDepth);val merged=log.undo()!!
        assertEquals(a,merged.paperBefore);assertEquals(c,merged.paperAfter)
    }
}

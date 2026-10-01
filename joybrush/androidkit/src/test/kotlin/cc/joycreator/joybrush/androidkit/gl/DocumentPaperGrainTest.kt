package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.grain.GrainMath
import cc.joycreator.joybrush.core.paper.ResolvedPaper
import cc.joycreator.joybrush.core.paper.SurfaceEntry
import kotlin.test.*

class DocumentPaperGrainTest {
    private val brush=GrainMath.GrainUniforms(2f,0.8f,0.3f,0.2f,0f,"brush.png")
    private val surface=SurfaceEntry("weave","Weave","weave.png",64,5f,0.1f,32f,false)
    private val paper=ResolvedPaper(surface,null,-1,2f,1f,0.25f,true)
    @Test fun documentSurfaceControlsAssetAndPitchWhileBiteScalesTheFinalCoverage() {
        val actual=documentPaperGrain(brush,paper)
        assertEquals("weave.png",actual.asset)
        assertEquals(10f,actual.pitchPx)
        assertEquals(0.8f,actual.depth,"9.08 moves bite to influence; multiplying threshold depth too would apply bite twice")
        assertEquals(brush.edge,actual.edge)
        assertEquals(brush.tiltGradient,actual.tiltGradient)
    }
    @Test fun smoothDocumentTurnsPaperOff() {
        assertEquals(GrainMath.GrainUniforms.OFF,documentPaperGrain(brush,paper.copy(surface=null)))
    }
    @Test fun legacyCallersAndUniversalBrushesKeepTheirExpectedThresholds() {
        assertEquals(brush,documentPaperGrain(brush,null))
        val universal=documentPaperGrain(GrainMath.GrainUniforms.OFF,paper)
        assertEquals(0.85f,universal.depth)
        assertEquals(0.3f,universal.edge)
        assertEquals(0f,universal.tiltGradient)
        assertEquals(0f,universal.radial)
    }
    @Test fun zeroInfluenceOrBiteTurnsTheDocumentGrainOff() {
        assertEquals(GrainMath.GrainUniforms.OFF,documentPaperGrain(brush,paper,cc.joycreator.joybrush.core.brush.PaperResponse()))
        assertEquals(GrainMath.GrainUniforms.OFF,documentPaperGrain(brush,paper.copy(bite=0f)))
    }
}

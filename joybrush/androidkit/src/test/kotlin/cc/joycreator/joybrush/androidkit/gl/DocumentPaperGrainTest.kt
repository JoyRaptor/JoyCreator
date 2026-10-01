package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.grain.GrainMath
import cc.joycreator.joybrush.core.paper.ResolvedPaper
import cc.joycreator.joybrush.core.paper.SurfaceEntry
import kotlin.test.*

class DocumentPaperGrainTest {
    private val brush=GrainMath.GrainUniforms(2f,0.8f,0.3f,0.2f,0f,"brush.png")
    private val surface=SurfaceEntry("weave","Weave","weave.png",64,5f,0.1f,32f,false)
    private val paper=ResolvedPaper(surface,null,-1,2f,1f,0.25f,true)
    @Test fun documentSurfaceControlsAssetPitchAndBiteWhileBrushKeepsThresholdShape() {
        val actual=documentPaperGrain(brush,paper)
        assertEquals("weave.png",actual.asset)
        assertEquals(10f,actual.pitchPx)
        assertEquals(0.2f,actual.depth)
        assertEquals(brush.edge,actual.edge)
        assertEquals(brush.tiltGradient,actual.tiltGradient)
    }
    @Test fun smoothDocumentTurnsPaperOff() {
        assertEquals(GrainMath.GrainUniforms.OFF,documentPaperGrain(brush,paper.copy(surface=null)))
    }
    @Test fun legacyCallersAndBrushesWithoutPaperRemainCompatible() {
        assertEquals(brush,documentPaperGrain(brush,null))
        assertEquals(GrainMath.GrainUniforms.OFF,documentPaperGrain(GrainMath.GrainUniforms.OFF,paper))
    }
}

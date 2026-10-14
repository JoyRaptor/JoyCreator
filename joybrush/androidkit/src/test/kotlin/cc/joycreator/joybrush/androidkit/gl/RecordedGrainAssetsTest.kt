package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.brush.PaperResponse
import cc.joycreator.joybrush.core.grain.GrainMath
import cc.joycreator.joybrush.core.paint.TipShape
import cc.joycreator.joybrush.core.paper.ResolvedPaper
import cc.joycreator.joybrush.core.paper.SurfaceEntry
import kotlin.test.*

class RecordedGrainAssetsTest {
    private val off = GrainMath.GrainUniforms.OFF
    private val tip = GrainMath.GrainUniforms(2f, 0.8f, 0.3f, 0.2f, 0f, "tip.png")
    private val paper = ResolvedPaper(SurfaceEntry("weave", "Weave", "weave.png", 64, 5f, 0.1f, 32f, false),
        null, -1, 2f, 1f, 0.25f, true)
    private fun look(requested: GrainMath.StrokeGrain, paper: ResolvedPaper?, assets: Map<String, Int> = emptyMap()) =
        StampPass(object : StampPass.GrainSource {
            override val placeholder = 1
            override fun textureFor(asset: String): Int? = assets[asset]
            override fun paperSize(asset: String) = 64
        }).lookFor(TipShape(), requested, paper, null, null, null, null)
    private fun modified(actual: StampPass.StrokeLook, texture: Int = actual.tipGrainTex,
                         grain: GrainMath.StrokeGrain = actual.grain) = StampPass.StrokeLook(
        actual.tip, grain, texture, actual.paperGrainTex, actual.surface, actual.paperScale,
        actual.paperResponse, actual.paperInfluence, actual.tilePaperRect, actual.fluidTexture)

    @Test fun missingTipFallbackCannotBecomeEditableReplayTruth() {
        val requested = GrainMath.StrokeGrain(tip, off)
        val actual = look(requested, null)
        assertFalse(actual.grain.tip.enabled)
        assertFailsWith<IllegalArgumentException> { requireRecordedGrainAssets(requested, actual, null) }
    }

    @Test fun missingDocumentUniversalGrainRefusesEvenWithoutBrushGrain() {
        val requested = GrainMath.StrokeGrain(off, off, PaperResponse(influence = 1f))
        val actual = look(requested, paper)
        assertTrue(documentPaperGrain(off, paper, actual.paperResponse).enabled)
        assertFalse(actual.grain.paper.enabled)
        assertFailsWith<IllegalArgumentException> { requireRecordedGrainAssets(requested, actual, paper) }
    }

    @Test fun intentionallyDisabledPaperInfluenceNeedsNoGrainPicture() {
        val requested = GrainMath.StrokeGrain(off, off, PaperResponse())
        val actual = look(requested, paper)
        assertFalse(actual.grain.paper.enabled)
        requireRecordedGrainAssets(requested, actual, paper)
    }

    @Test fun availableGrainPassesButZeroNameOrWrongAssetRefuses() {
        val requested = GrainMath.StrokeGrain(tip, off, PaperResponse(influence = 1f))
        val actual = look(requested, paper, mapOf("tip.png" to 2, "weave.png" to 3))
        assertTrue(actual.grain.tip.enabled && actual.grain.paper.enabled)
        requireRecordedGrainAssets(requested, actual, paper)
        assertFailsWith<IllegalArgumentException> {
            requireRecordedGrainAssets(requested, modified(actual, texture = 0), paper)
        }
        assertFailsWith<IllegalArgumentException> {
            requireRecordedGrainAssets(requested, modified(actual, grain = actual.grain.copy(
                tip = actual.grain.tip.copy(asset = "different.png"))), paper)
        }
    }
}

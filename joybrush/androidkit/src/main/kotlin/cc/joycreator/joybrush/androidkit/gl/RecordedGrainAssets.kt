package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.grain.GrainMath
import cc.joycreator.joybrush.core.paper.ResolvedPaper

/** Editable replay must not adopt the ordinary raster path's missing-grain fallback as its truth. */
internal fun requireRecordedGrainAssets(requested: GrainMath.StrokeGrain, actual: StampPass.StrokeLook,
                                       paper: ResolvedPaper?) {
    require(!requested.tip.enabled || (actual.grain.tip.enabled && actual.tipGrainTex != 0 &&
        actual.grain.tip.asset == requested.tip.asset)) {
        "Recorded replay needs the frozen brush's tip grain picture"
    }
    val expectedPaper = documentPaperGrain(requested.paper, paper, actual.paperResponse)
    require(!expectedPaper.enabled || (actual.grain.paper.enabled && actual.paperGrainTex != 0 &&
        actual.grain.paper.asset == expectedPaper.asset)) {
        "Recorded replay needs the document's paper grain picture"
    }
}

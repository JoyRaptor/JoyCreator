package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.grain.GrainMath
import cc.joycreator.joybrush.core.paper.ResolvedPaper
import cc.joycreator.joybrush.core.brush.PaperResponse

/** Physical pitch comes from the document, while the brush keeps its threshold settings. */
internal fun documentPaperGrain(brush: GrainMath.GrainUniforms, paper: ResolvedPaper?, response: PaperResponse = PaperResponse(influence=1f)): GrainMath.GrainUniforms {
    if (paper == null) return brush
    val surface = paper.surface ?: return GrainMath.GrainUniforms.OFF
    if (response.influence <= 0f || paper.bite <= 0f) return GrainMath.GrainUniforms.OFF
    // Bite scales the final grain factor in 9.08, not threshold depth (avoid applying it twice).
    val chosen = if (brush.enabled) brush else GrainMath.GrainUniforms(
        surface.texelPx,GrainMath.UNIVERSAL_DEPTH,GrainMath.UNIVERSAL_EDGE,0f,0f,surface.file)
    return chosen.copy(asset=surface.file,pitchPx=surface.texelPx*paper.scale)
}

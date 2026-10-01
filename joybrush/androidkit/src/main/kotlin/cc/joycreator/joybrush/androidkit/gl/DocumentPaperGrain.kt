package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.grain.GrainMath
import cc.joycreator.joybrush.core.paper.ResolvedPaper

/** Physical pitch comes from the document, while the brush keeps its threshold settings. */
internal fun documentPaperGrain(brush: GrainMath.GrainUniforms, paper: ResolvedPaper?): GrainMath.GrainUniforms {
    if (paper == null) return brush
    val surface = paper.surface ?: return GrainMath.GrainUniforms.OFF
    if (!brush.enabled) return GrainMath.GrainUniforms.OFF
    return brush.copy(asset=surface.file,pitchPx=surface.texelPx*paper.scale,depth=brush.depth*paper.bite)
}

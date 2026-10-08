package cc.joycreator.joybrush.androidkit.tools

import cc.joycreator.joybrush.core.shape.Pt

/** Immutable UI snapshot; geometry is in document pixels, like the recorded stroke. */
data class FillPenPreviewState(val points: List<Pt>, val argb: Int, val opacity: Float)

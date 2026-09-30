package cc.joycreator.joybrush.core.render

import cc.joycreator.joybrush.core.doc.Layer

/**
 * The masking and clipping RULES (JB-2.23, Lead ruling R48), defined once and called by both compositors: the export's
 * [RegionRenderer] and the GPU engine. Two copies of "which layer do I clip into" or "what is a mask's coverage" is
 * exactly how a preview and an export come to disagree, so neither compositor holds its own.
 *
 * A layer's contribution, per pixel, before its opacity and before its blend (the Photoshop order):
 *
 *     contribution = pixel × [coverage] of its own mask × [clipAlpha] of its clip base
 */
object LayerMask {

    /** The cel id a layer's mask is saved under. One mask per layer, so one fixed id. */
    const val MASK_CEL = "mask"

    /**
     * Coverage 0..1 at byte offset [i] of a mask tile: its R channel. Alpha is IGNORED — a mask tile's alpha says nothing
     * about coverage. A missing tile ([maskTile] null) is full coverage: a new, empty mask shows everything, and a layer
     * with no mask at all is the same as one whose mask was never painted.
     */
    fun coverage(maskTile: ByteArray?, i: Int): Float = if (maskTile == null) 1f else (maskTile[i].toInt() and 0xFF) / 255f

    /**
     * The index of the layer that layer [index] clips into, or null when it is not clipped. Photoshop's rule: the NEAREST
     * unclipped layer below, so a run of clipped layers all clip into the same base. A clipped layer with no unclipped
     * layer below it (the bottom one) is not clipped — [cc.joycreator.joybrush.core.doc.DocOps.validate] refuses such a
     * file in words, and this agrees rather than guessing.
     *
     * [isClipped] says whether the layer at an index (0 = bottom) is clipped, so the engine, whose layers are not doc
     * [Layer]s, asks the same function.
     */
    fun clipBase(index: Int, isClipped: (Int) -> Boolean): Int? {
        if (index <= 0 || !isClipped(index)) return null
        var i = index - 1
        while (i >= 0) {
            if (!isClipped(i)) return i
            i--
        }
        return null
    }

    fun clipBaseOf(index: Int, layers: List<Layer>): Int? = clipBase(index) { layers[it].clip }

    /**
     * How much of a clipped layer shows at byte offset [i]: the base's own alpha there, times the base's own mask. Where
     * the base has no tile ([baseTile] null) nothing shows. The base's OPACITY is not in it: in Photoshop a clipping
     * group's base opacity fades the base, not the shape the clipped layers are cut to.
     */
    fun clipAlpha(baseTile: ByteArray?, baseMask: ByteArray?, i: Int): Float {
        if (baseTile == null) return 0f
        return ((baseTile[i + 3].toInt() and 0xFF) / 255f) * coverage(baseMask, i)
    }
}

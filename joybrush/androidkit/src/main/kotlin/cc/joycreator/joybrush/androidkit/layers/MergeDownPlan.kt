package cc.joycreator.joybrush.androidkit.layers

import cc.joycreator.joybrush.core.doc.BlendMode

/**
 * LAYER_OPS L2/L3 (tasks/joybrush/specs/LAYER_OPS_20261018.md §2): what Merge Down does with an upper layer U and the layer
 * L right under it, decided before anything is touched. Pure: the canvas asks, the engine follows.
 *
 * A layer is an ordered stack of sheets (its base pixels, then lines and pixel slabs in drawing order), so merging puts
 * U's sheets on top of L's. When U is plain, U's lines move across and stay editable ([Way.MOVE_ITEMS]). When U has a
 * whole-layer setting (blend, opacity, mask, clip) or a line that only makes sense against U's own pixels (erase, behind,
 * multiply, screen), U is pressed into ONE pixel sheet on L first ([Way.PRESS]); L's own lines stay editable when that
 * sheet's operator is one L can keep in order, else L's lines become paint too ([Way.PIXELS]). With no lines on either,
 * it is a plain pixel merge ([Way.PIXELS]).
 */
object MergeDownPlan {

    /** What the plan needs to know about one layer. */
    data class Side(
        val name: String,
        val blend: BlendMode = BlendMode.NORMAL,
        val opacity: Float = 1f,
        val hasMask: Boolean = false,
        val clip: Boolean = false,
        val locked: Boolean = false,
        val visible: Boolean = true,
        /** It holds editable lines or pixel slabs on any plane. */
        val hasItems: Boolean = false,
        /** Every item's operator is "normal" (a line or slab that erases, multiplies… only means that against its own layer). */
        val itemsAllNormal: Boolean = true,
        /** It animates in a board (frame cels): L4. */
        val hasFrames: Boolean = false,
        /** It holds pencil, watercolour or oil state: L5. */
        val hasMedia: Boolean = false,
        /**
         * Merging on an animation frame (owner, 2026-10-18: only the frame being worked on merges, the animation stays):
         * null = a whole-layer merge; true/false = this layer does / does not animate in that board.
         */
        val animatesHere: Boolean? = null,
    )

    enum class Way { PIXELS, MOVE_ITEMS, PRESS }

    sealed class Result {
        /** Refused, as a whole sentence. */
        data class Refused(val sentence: String) : Result()
        /**
         * Go ahead. [way]: how. [sheetOp]: with [Way.PRESS], the operator U's pressed sheet keeps on L. [flattenLower]: L's
         * own lines and slabs become paint in the same step. [said]: one sentence for the person, or null.
         */
        data class Merge(val way: Way, val sheetOp: String = "normal", val flattenLower: Boolean = false, val said: String? = null) : Result()
    }

    /** Operators a sheet can keep in L's order (NativeLines.OPERATORS without "behind", which only works under its own layer). */
    private val SHEET_OPS = mapOf(BlendMode.NORMAL to "normal", BlendMode.MULTIPLY to "multiply", BlendMode.SCREEN to "screen")

    fun decide(upper: Side?, lower: Side?): Result {
        if (upper == null || lower == null) return Result.Refused("The bottom layer has nothing under it to merge into.")
        if (upper.locked) return Result.Refused("\"${upper.name}\" is locked. Unlock it to merge it.")
        if (lower.locked) return Result.Refused("\"${lower.name}\" is locked. Unlock it to merge into it.")
        if (!upper.visible) return Result.Refused("\"${upper.name}\" is hidden. Show it to merge it.")
        if (!lower.visible) return Result.Refused("\"${lower.name}\" is hidden. Show it to merge into it.")
        val frameMode = upper.animatesHere != null
        if (frameMode) {
            if (upper.animatesHere != true) return Result.Refused("\"${upper.name}\" doesn't animate on this board, so there is no frame of it to merge.")
            if (lower.animatesHere != true) return Result.Refused("\"${lower.name}\" doesn't animate on this board yet. Merging a frame into a still layer is coming next.")
        } else if (upper.hasFrames || lower.hasFrames) return Result.Refused("These layers have animation frames. Pick the animation board to merge the frame you're on.")
        if (upper.hasMedia || lower.hasMedia) return Result.Refused("Merging layers with pencil, watercolour or oil is coming next.")
        // L's own whole-layer settings would also act on U's pixels once they are in L: the picture would change.
        if (lower.clip) return Result.Refused("\"${lower.name}\" is clipped. Unclip it to merge into it.")
        if (lower.blend != BlendMode.NORMAL) return Result.Refused("\"${lower.name}\" uses the ${blendWord(lower.blend)} blend. Set it to Normal to merge into it.")
        if (lower.opacity < 0.999f) return Result.Refused("\"${lower.name}\" is at ${(lower.opacity * 100).toInt()}% opacity. Set it to 100% to merge into it.")
        if (lower.hasMask) return Result.Refused("\"${lower.name}\" has a mask. Apply or delete it to merge into it.")

        if (!upper.hasItems && !lower.hasItems) return Result.Merge(Way.PIXELS)
        val plain = upper.blend == BlendMode.NORMAL && upper.opacity >= 0.999f && !upper.hasMask && !upper.clip && upper.itemsAllNormal
        if (plain) return Result.Merge(Way.MOVE_ITEMS)
        val op = SHEET_OPS[upper.blend]
        val upperLines = if (upper.hasItems) "lines on \"${upper.name}\" became paint" else null
        if (op == null) {
            // A blend L can't keep as a sheet in its order: the merged pixels are simply the picture.
            val who = listOfNotNull(upperLines, if (lower.hasItems) "lines on \"${lower.name}\" became paint" else null)
            return Result.Merge(Way.PIXELS, flattenLower = lower.hasItems,
                said = who.takeIf { it.isNotEmpty() }?.let { cap(it.joinToString(" and ")) + " to keep the ${blendWord(upper.blend)} blend." })
        }
        return Result.Merge(Way.PRESS, sheetOp = op,
            said = upperLines?.let { cap(it) + " to keep its ${settingWord(upper)}; \"${lower.name}\" keeps its lines." })
    }

    private fun cap(s: String) = s.replaceFirstChar { it.uppercaseChar() }

    private fun blendWord(b: BlendMode): String = b.name.lowercase().replace('_', ' ')

    private fun settingWord(u: Side): String = when {
        u.blend != BlendMode.NORMAL -> "${blendWord(u.blend)} blend"
        u.opacity < 0.999f -> "opacity"
        u.hasMask -> "mask"
        u.clip -> "clipping"
        else -> "look"
    }
}

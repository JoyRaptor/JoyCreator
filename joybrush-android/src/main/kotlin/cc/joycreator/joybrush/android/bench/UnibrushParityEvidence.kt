package cc.joycreator.joybrush.android.bench

import cc.joycreator.joybrush.core.paint.Tiles

/**
 * Necessary checks for the U8 recolour/replay subcase, not whole-U8 or device acceptance.
 * Inputs must be fenced, immutable RGBA8 readbacks from the SAME run/plane. The host still owns
 * generation, APK/run identity, GL completion, actual undo/redo, save/reopen and screenshot proof.
 * Not wired to a runner yet: do not interpret this helper's presence as an accepted device run.
 */
internal object UnibrushParityEvidence {
    data class Frame(val visible: Map<Long, ByteArray>?, val undoDepth: Int, val lineCount: Int)
    data class Result(val matchingRecolourReplay: Boolean, val reason: String)

    private val tileBytes = Tiles.SIZE * Tiles.SIZE * 4
    private const val MAX_TILES = 64 // Per readback; bounded diagnostic, not the drawing's capacity.

    fun evaluate(before: Frame, drawn: Frame, middle: Frame, replayed: Frame): Result {
        val frames = listOf(before, drawn, middle, replayed)
        for (frame in frames) {
            val plane = frame.visible ?: return refuse("Missing visible-plane readback")
            if (plane.size > MAX_TILES) return refuse("Readback exceeds the diagnostic tile budget")
            if (plane.values.any { it.size != tileBytes }) return refuse("Malformed RGBA8 tile readback")
            if (frame.undoDepth < 0 || frame.lineCount < 0) return refuse("Invalid history or line count")
        }
        // Admission and exact history steps must be observed, not inferred from equal pictures.
        if (listOf(drawn, middle, replayed).any { it.lineCount.toLong() != before.lineCount.toLong() + 1L })
            return refuse("The stroke did not remain exactly one additional editable line")
        if (drawn.undoDepth.toLong() != before.undoDepth.toLong() + 1L ||
            middle.undoDepth.toLong() != drawn.undoDepth.toLong() + 1L ||
            replayed.undoDepth.toLong() != middle.undoDepth.toLong() + 1L)
            return refuse("Expected one stroke step and one step per recolour edit")
        val baseline = checkNotNull(before.visible)
        val a = checkNotNull(drawn.visible)
        val m = checkNotNull(middle.visible)
        val b = checkNotNull(replayed.visible)
        if (!hasAlpha(a) || !hasAlpha(m) || !hasAlpha(b)) return refuse("Blank output cannot establish replay parity")
        if (MatrixDiff.same(baseline, a)) return refuse("The stroke did not change its baseline")
        if (MatrixDiff.same(baseline, m)) return refuse("The intermediate recolour only restored the baseline")
        if (MatrixDiff.same(a, m)) return refuse("The intermediate recolour did not change output")
        if (!MatrixDiff.same(a, b)) return refuse("Replayed pixels differ from the original stroke")
        return Result(true, "Matching nonblank recolour/replay subcase; other U8 gates remain separate")
    }

    private fun hasAlpha(plane: Map<Long, ByteArray>): Boolean = plane.values.any { tile ->
        (3 until tile.size step 4).any { (tile[it].toInt() and 255) != 0 }
    }
    private fun refuse(reason: String) = Result(false, reason)
}

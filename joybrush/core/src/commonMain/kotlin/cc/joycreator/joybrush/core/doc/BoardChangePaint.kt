package cc.joycreator.joybrush.core.doc

/**
 * Whether a board edit touches paint: it moves, copies, clears or drops pixels, or it changes where a layer's frames lie
 * or which of them shows. An edit that does neither (a new name, a lock, a frame's hold, the speed) leaves wet
 * watercolour alone; any other makes the wash set first, because what the wash is painted over is about to change.
 *
 * Codex audit R2-JB-02 (2026-11-15): every board edit dried and printed the wet sheet, a rename included.
 */
object BoardChangePaint {
    /**
     * [liveFrames]: each live layer's frames as the renderer holds them now (null: it has no plan yet).
     * [previews]: the frame previews in force (board id to frame id), as the renderer will keep them.
     * When in doubt, true.
     */
    fun writes(change: RegionChange, liveFrames: Map<String, List<RegionFrame>?>, previews: Map<String, String>): Boolean {
        if (change.copies.isNotEmpty() || change.drops.isNotEmpty() || change.transfers.isNotEmpty() || change.maskTransfers.isNotEmpty() ||
            change.clears.isNotEmpty() || change.maskClears.isNotEmpty() || change.frameDuplicate != null) return true
        return try {
            val kept = previews.filter { (boardId, frameId) ->
                change.doc.boards.any { it.id == boardId && it.kind == BoardKind.ANIMATION && (frameId == RegionDocumentOps.BLANK_PREVIEW || it.frames.any { f -> f.id == frameId }) }
            }
            if (kept != previews) return true
            val plans = RegionDocumentOps.paintPlans(change.doc, kept)
            plans.keys != liveFrames.keys || liveFrames.any { (id, frames) -> frames == null || plans.getValue(id).frames != frames }
        } catch (e: Exception) { true }
    }
}

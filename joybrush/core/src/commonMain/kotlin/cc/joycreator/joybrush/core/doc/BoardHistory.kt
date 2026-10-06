package cc.joycreator.joybrush.core.doc

/** Restore history metadata without rolling unrelated, more recent frame navigation backwards. */
object BoardHistory {
    /**
     * [target] is the snapshot being restored, [opposite] is the other side of the same history
     * operation, and [live] is the current document. Actual cursor edits restore their target.
     * Other edits preserve live navigation only while that frame still exists in the target board.
     * No pixels, layer metadata or active-board selection are taken from [live].
     */
    fun restore(target: JbDocument, opposite: JbDocument, live: JbDocument): JbDocument {
        BoardDocumentOps.valid(target)
        BoardDocumentOps.valid(opposite)
        BoardDocumentOps.valid(live)
        if (target.id != opposite.id || target.id != live.id) throw DocException("History snapshots belong to different drawings")
        val otherBoards = opposite.boards.associateBy { it.id }
        val liveBoards = live.boards.associateBy { it.id }
        val result = target.copy(boards = target.boards.map { board ->
            val other = otherBoards[board.id]
            val current = liveBoards[board.id]?.currentFrameId
            if (board.kind == BoardKind.ANIMATION && other != null && other.kind == board.kind &&
                board.currentFrameId == other.currentFrameId && current != null && board.frames.any { it.id == current }) {
                board.copy(currentFrameId = current)
            } else board
        })
        for (board in result.boards) {
            if (board.currentFrameId != null && board.frames.none { it.id == board.currentFrameId }) {
                throw DocException("Restored board cursor does not name a surviving frame")
            }
        }
        return BoardDocumentOps.checked(result)
    }
}

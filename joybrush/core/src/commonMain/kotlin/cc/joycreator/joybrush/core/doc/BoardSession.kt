package cc.joycreator.joybrush.core.doc

/** Ephemeral selection/feature arming. Lock and saved tiling history remain document properties. */
data class BoardSession(val selectedBoardId: String? = null, val armedBoardId: String? = null) {
    fun select(doc: JbDocument, id: String?): BoardSession {
        BoardDocumentOps.valid(doc)
        if (id != null) BoardDocumentOps.board(doc, id)
        return copy(selectedBoardId = id)
    }

    fun arm(doc: JbDocument, id: String?): BoardSession {
        BoardDocumentOps.valid(doc)
        if (id != null) {
            val board = BoardDocumentOps.board(doc, id)
            if (board.kind != BoardKind.CANVAS && board.kind != BoardKind.SPRITE) {
                throw DocException("Only Image tiling and Sprite rearrangement can be armed")
            }
        }
        return copy(armedBoardId = id)
    }

    fun reconcile(doc: JbDocument): BoardSession {
        BoardDocumentOps.valid(doc)
        val live = doc.boards.mapTo(HashSet()) { it.id }
        val armable = doc.boards.filter { it.kind == BoardKind.CANVAS || it.kind == BoardKind.SPRITE }.mapTo(HashSet()) { it.id }
        return copy(selectedBoardId = selectedBoardId?.takeIf { it in live },
            armedBoardId = armedBoardId?.takeIf { it in armable })
    }
}

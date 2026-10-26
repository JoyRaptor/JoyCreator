package cc.joycreator.joybrush.core.doc

/** Validates the exact metadata operation carried by a runtime duplicate-frame marker. */
object FrameDuplicatePlan {
    /**
     * Returns null when [change] is an ordinary region edit. If it carries a duplicate marker,
     * reconstructs the only canonical DUPLICATE result from [before] and the IDs in its result
     * document. Every document and operation list must match that reconstruction exactly.
     */
    fun validate(before: JbDocument, change: RegionChange): List<RegionCopy>? {
        val marker = change.frameDuplicate ?: return null
        validateDocument(before, "before")
        validateDocument(change.doc, "after")

        val board = before.boards.firstOrNull { it.id == marker.boardId }
            ?: throw DocException("Duplicate marker names a missing animation board")
        if (board.kind != BoardKind.ANIMATION || before.layers.none { layer ->
                layer.regions.any { it.boardId == marker.boardId }
            }) {
            throw DocException("Duplicate marker does not name a region animation board")
        }
        if (board.currentFrameId != marker.sourceFrameId || board.frames.none { it.id == marker.sourceFrameId }) {
            throw DocException("Duplicate marker source is not the board's current frame")
        }
        if (marker.targetFrameId.isBlank()) throw DocException("Duplicate marker target frame ID is empty")

        val afterBoard = change.doc.boards.firstOrNull { it.id == marker.boardId }
            ?: throw DocException("Duplicate result removed its animation board")
        if (afterBoard.frames.none { it.id == marker.targetFrameId }) {
            throw DocException("Duplicate result is missing its target frame")
        }
        val afterLayers = change.doc.layers.associateBy { it.id }
        val targetCelIds = ArrayList<String>()
        for (layer in before.layers) {
            if (layer.regions.none { it.boardId == marker.boardId }) continue
            val afterLayer = afterLayers[layer.id]
                ?: throw DocException("Duplicate result removed layer \"${layer.name}\"")
            val afterRegion = afterLayer.regions.singleOrNull { it.boardId == marker.boardId }
                ?: throw DocException("Duplicate result changed layer \"${layer.name}\"'s animation region")
            targetCelIds += afterRegion.frameCel[marker.targetFrameId]
                ?: throw DocException("Duplicate result has no target cel for layer \"${layer.name}\"")
        }

        val ids = (listOf(marker.targetFrameId) + targetCelIds).iterator()
        val canonical = RegionDocumentOps.addFrame(before, marker.boardId, NewFrame.DUPLICATE) {
            if (ids.hasNext()) ids.next() else throw DocException("Duplicate result is missing fresh cel IDs")
        }
        if (ids.hasNext()) throw DocException("Duplicate result contains extra fresh cel IDs")
        if (canonical != change) throw DocException("Frame duplicate is not the canonical DUPLICATE operation")
        return canonical.copies
    }

    private fun validateDocument(doc: JbDocument, label: String) {
        val errors = DocOps.validate(doc)
        if (errors.isNotEmpty()) throw DocException("Duplicate $label document is invalid: ${errors.joinToString("; ")}")
    }
}

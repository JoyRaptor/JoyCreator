package cc.joycreator.joybrush.core.doc

/** Bounded rectangle plans, never pixels. Execute atomically, with all transfer sources snapshotted
 * before clears/copies/drops; publish metadata last and retain one undo for the whole operation. */
object AnimationBoardOps {
    /** B6/H1: moving/resizing a single unlocked frame changes its crop, not world pixel positions. */
    fun resize(doc: JbDocument, boardId: String, requestedRect: RectPx): RegionChange {
        val board = board(doc, boardId)
        if (board.locked) throw DocException("Unlock this board before moving or resizing it")
        if (board.frames.size != 1) throw DocException("Animation with multiple frames is fixed; use Move all frames")
        val next = geometry(doc, board, requestedRect)
        if (requestedRect == board.rect) return RegionChange(doc)
        val leaving = subtract(board.rect, requestedRect)
        val entering = subtract(requestedRect, board.rect)
        val transfers = ArrayList<RegionTransfer>()
        val clears = ArrayList<RegionClear>()
        for (layer in doc.layers) {
            val region = layer.regions.firstOrNull { it.boardId == boardId } ?: continue
            val shared = requireNotNull(layer.sharedCelId)
            val cel = region.frameCel.getValue(requireNotNull(board.currentFrameId))
            if (!region.held) for (rect in leaving) transfers += RegionTransfer(layer.id, cel, shared, rect, rect)
            for (rect in leaving) clears += RegionClear(layer.id, cel, rect)
            for (rect in entering) transfers += RegionTransfer(layer.id, shared, cel, rect, rect)
            // Nothing hides under a board: art the growing board takes in leaves the shared canvas (held layers keep
            // reading and writing the shared canvas, so theirs stays).
            if (!region.held) for (rect in entering) clears += RegionClear(layer.id, shared, rect)
        }
        // Masks are world-space coverage: passive crop changes do not move them.
        return RegionChange(next, transfers = transfers, clears = clears)
    }

    /** C3: deliberate translation, permitted even when locked. Each unique linked cel moves once.
     * Nothing hides under a board (creation and growth move the art into the frames), so the old location is left
     * empty. Shared art already at the destination stays, hidden, and shows again if the board moves away.
     * Held shared artwork and global masks travel with the board; old-only mask pixels become white. */
    fun moveAll(doc: JbDocument, boardId: String, x: Int, y: Int): RegionChange {
        val board = board(doc, boardId)
        val destination = board.rect.copy(x = x, y = y)
        val next = geometry(doc, board, destination)
        if (destination == board.rect) return RegionChange(doc)
        val transfers = ArrayList<RegionTransfer>()
        val clears = ArrayList<RegionClear>()
        val masks = ArrayList<RegionMaskTransfer>()
        val maskClears = ArrayList<RegionMaskClear>()
        val leaving = subtract(board.rect, destination)
        for (layer in doc.layers) {
            val region = layer.regions.firstOrNull { it.boardId == boardId }
            val shared = shared(layer)
            val cels = region?.frameCel?.values?.toSet().orEmpty().toMutableSet()
            if (region == null || region.held) cels.add(shared)
            for (cel in cels) {
                transfers += RegionTransfer(layer.id, cel, cel, board.rect, destination)
                for (rect in leaving) clears += RegionClear(layer.id, cel, rect)
            }
            if (layer.mask != null) {
                masks += RegionMaskTransfer(layer.id, board.rect, destination)
                for (rect in leaving) maskClears += RegionMaskClear(layer.id, rect)
            }
        }
        return RegionChange(next, transfers = transfers, maskTransfers = masks, clears = clears, maskClears = maskClears)
    }

    /** Clone at an explicit non-overlapping destination. Links remain links WITHIN the clone,
     * but no cloned frame cel aliases the original. Held flags, timing and selected frame survive. */
    fun duplicate(doc: JbDocument, boardId: String, x: Int, y: Int, ids: () -> String): RegionChange {
        val source = board(doc, boardId)
        val destination = source.rect.copy(x = x, y = y)
        BoardDocumentOps.checkRect(destination)
        val fresh = RegionDocumentOps.generator(doc, ids)
        val newBoardId = fresh()
        val frames = source.frames.associate { it.id to it.copy(id = fresh()) }
        val names = doc.boards.mapTo(HashSet()) { it.name }
        var number = 2
        while ("${source.name} $number" in names) number++
        val copy = source.copy(id = newBoardId, name = "${source.name} $number", rect = destination,
            frames = source.frames.map { frames.getValue(it.id) }, currentFrameId = frames.getValue(requireNotNull(source.currentFrameId)).id)
        val transfers = ArrayList<RegionTransfer>()
        val masks = ArrayList<RegionMaskTransfer>()
        val layers = doc.layers.map { layer ->
            val region = layer.regions.firstOrNull { it.boardId == boardId }
            if (region == null || region.held) {
                val shared = shared(layer)
                transfers += RegionTransfer(layer.id, shared, shared, source.rect, destination)
            }
            if (layer.mask != null) masks += RegionMaskTransfer(layer.id, source.rect, destination)
            if (region == null) return@map layer
            val cels = region.frameCel.values.toSet().associateWith { fresh() }
            for ((old, new) in cels) transfers += RegionTransfer(layer.id, old, new, source.rect, destination)
            layer.copy(cels = layer.cels + cels.values.map { Cel(it) }, regions = layer.regions +
                region.copy(boardId = newBoardId, frameCel = region.frameCel.map { (frame, cel) ->
                    frames.getValue(frame).id to cels.getValue(cel)
                }.toMap()))
        }
        val next = BoardDocumentOps.checked(doc.copy(layers = layers, boards = doc.boards + copy, activeBoardId = copy.id))
        return RegionChange(next, transfers = transfers, maskTransfers = masks)
    }

    /** Flatten the saved current frame before removing ownership. Never drops a still-referenced cel. */
    fun remove(doc: JbDocument, boardId: String): RegionChange {
        val board = board(doc, boardId)
        val remaining = doc.boards.filterNot { it.id == boardId }
        if (remaining.isEmpty()) throw DocException("Keep at least one board")
        val transfers = ArrayList<RegionTransfer>()
        val drops = ArrayList<RegionDrop>()
        val layers = doc.layers.map { layer ->
            val region = layer.regions.firstOrNull { it.boardId == boardId } ?: return@map layer
            val shared = shared(layer)
            if (!region.held) transfers += RegionTransfer(layer.id,
                region.frameCel.getValue(requireNotNull(board.currentFrameId)), shared, board.rect, board.rect)
            val regions = layer.regions.filterNot { it.boardId == boardId }
            val retained = regions.flatMap { it.frameCel.values }.toSet() + shared + layer.frameCel.values
            val removed = region.frameCel.values.toSet() - retained
            drops += removed.map { RegionDrop(layer.id, it) }
            layer.copy(cels = layer.cels.filterNot { it.id in removed }, regions = regions)
        }
        val next = BoardDocumentOps.checked(doc.copy(layers = layers, boards = remaining,
            activeBoardId = if (doc.activeBoardId == boardId) remaining.first().id else doc.activeBoardId))
        return RegionChange(next, transfers = transfers, drops = drops)
    }

    private fun board(doc: JbDocument, id: String): Board {
        RegionDocumentOps.paintPlans(doc, emptyMap()) // Validates legacy and current ownership.
        val board = BoardDocumentOps.board(doc, id)
        if (board.kind != BoardKind.ANIMATION || doc.layers.none { l -> l.regions.any { it.boardId == id } }) {
            throw DocException("Select a region Animation board")
        }
        BoardDocumentOps.checkRect(board.rect)
        return board
    }

    private fun shared(layer: Layer) = layer.sharedCelId ?: layer.cels.single().id

    private fun geometry(doc: JbDocument, board: Board, rect: RectPx): JbDocument {
        BoardDocumentOps.checkRect(rect)
        return BoardDocumentOps.checked(doc.copy(boards = doc.boards.map { if (it.id == board.id) it.copy(rect = rect) else it }))
    }

    /** A minus B: at most four disjoint rectangles. Long edge math also handles negative origins. */
    private fun subtract(a: RectPx, b: RectPx): List<RectPx> {
        val left = maxOf(a.x.toLong(), b.x.toLong()); val top = maxOf(a.y.toLong(), b.y.toLong())
        val right = minOf(a.x.toLong() + a.w, b.x.toLong() + b.w)
        val bottom = minOf(a.y.toLong() + a.h, b.y.toLong() + b.h)
        if (right <= left || bottom <= top) return listOf(a)
        val result = ArrayList<RectPx>(4)
        fun add(x: Long, y: Long, r: Long, d: Long) {
            if (r > x && d > y) result += RectPx(x.toInt(), y.toInt(), (r - x).toInt(), (d - y).toInt())
        }
        add(a.x.toLong(), a.y.toLong(), a.x.toLong() + a.w, top)
        add(a.x.toLong(), bottom, a.x.toLong() + a.w, a.y.toLong() + a.h)
        add(a.x.toLong(), top, left, bottom)
        add(right, top, a.x.toLong() + a.w, bottom)
        return result
    }
}

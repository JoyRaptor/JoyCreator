package cc.joycreator.joybrush.core.doc

/** Caller performs bounded copies before publishing the new metadata as one undoable edit. */
data class RegionCopy(val layerId: String, val fromCelId: String, val toCelId: String, val rect: RectPx)
data class RegionChange(val doc: JbDocument, val copies: List<RegionCopy> = emptyList())

/** Region metadata operations. No legacy conversion, pixel buffers, GPU or history owned here. */
object RegionDocumentOps {
    fun create(doc: JbDocument, name: String, rect: RectPx, ids: () -> String): RegionChange {
        valid(doc)
        if (doc.layers.isEmpty()) throw DocException("Add a paint layer before creating an animation board")
        if (doc.layers.any { it.animatedIn != null || it.kind != LayerKind.PAINT }) {
            throw DocException("Start a new paint drawing to use region animation")
        }
        val fresh = generator(doc, ids)
        val boardId = fresh(); val frameId = fresh()
        val copies = ArrayList<RegionCopy>()
        val layers = doc.layers.map { layer ->
            val shared = layer.sharedCelId ?: layer.cels.single().id
            val celId = fresh()
            copies += RegionCopy(layer.id, shared, celId, rect)
            layer.copy(sharedCelId = shared, cels = layer.cels + Cel(celId),
                regions = layer.regions + RegionFrames(boardId, mapOf(frameId to celId)))
        }
        val next = doc.copy(version = DOC_VERSION, layers = layers,
            boards = doc.boards + Board(boardId,name,BoardKind.ANIMATION,rect,
                frames = listOf(Frame(frameId)), currentFrameId = frameId), activeBoardId = boardId)
        valid(next)
        return RegionChange(next,copies)
    }

    fun selectFrame(doc: JbDocument, boardId: String, frameId: String): JbDocument {
        valid(doc)
        val board = board(doc,boardId)
        if (board.frames.none { it.id == frameId }) throw DocException("That frame is not in this board")
        return doc.copy(boards = doc.boards.map { if (it.id == boardId) it.copy(currentFrameId = frameId) else it })
    }

    fun addFrame(doc: JbDocument, boardId: String, mode: NewFrame, ids: () -> String): RegionChange {
        valid(doc)
        val board = board(doc,boardId)
        val sourceId = board.currentFrameId ?: throw DocException("Select a frame first")
        val index = board.frames.indexOfFirst { it.id == sourceId }
        val fresh = generator(doc,ids); val frameId = fresh()
        val copies = ArrayList<RegionCopy>()
        val layers = doc.layers.map { layer ->
            val region = layer.regions.firstOrNull { it.boardId == boardId } ?: return@map layer
            val source = region.frameCel.getValue(sourceId)
            val cel = if (mode == NewFrame.LINK) source else fresh()
            if (mode == NewFrame.DUPLICATE) copies += RegionCopy(layer.id,source,cel,board.rect)
            layer.copy(cels = if (cel == source) layer.cels else layer.cels + Cel(cel),
                regions = layer.regions.map { if (it.boardId == boardId) it.copy(frameCel = it.frameCel + (frameId to cel)) else it })
        }
        val frames = board.frames.toMutableList().apply { add(index+1,Frame(frameId)) }
        val next = doc.copy(layers = layers,boards = doc.boards.map {
            if (it.id == boardId) it.copy(frames = frames,currentFrameId = frameId) else it
        })
        valid(next)
        return RegionChange(next,copies)
    }

    /** Held regions deliberately use the shared plane; frame mappings remain available for unhold. */
    fun paintPlan(doc: JbDocument, layerId: String): RegionPaintPlan {
        valid(doc)
        return paintPlanUnchecked(doc, layerId)
    }

    /** For render callers which validated on open; override only the board owning that frame. */
    internal fun paintPlanUnchecked(doc: JbDocument, layerId: String, frameId: String? = null): RegionPaintPlan {
        val layer = doc.layers.firstOrNull { it.id == layerId } ?: throw DocException("No such layer")
        if (layer.animatedIn != null) throw DocException("Whole-layer animation is an obsolete test format")
        val shared = layer.sharedCelId ?: layer.cels.singleOrNull()?.id ?: throw DocException("No shared paint")
        val frames = layer.regions.filterNot { it.held }.map { region ->
            val board = doc.boards.first { it.id == region.boardId }
            val current = frameId?.takeIf { requested -> board.frames.any { it.id == requested } }
                ?: board.currentFrameId ?: throw DocException("Select a frame first")
            RegionFrame(board.id,board.rect,current,region.frameCel.getValue(current))
        }
        return RegionPaintPlan(shared,frames)
    }

    private fun board(doc: JbDocument,id: String): Board {
        val board = doc.boards.firstOrNull { it.id == id } ?: throw DocException("No such board")
        if (board.kind != BoardKind.ANIMATION || doc.layers.none { l -> l.regions.any { it.boardId == id } }) {
            throw DocException("This is not a region animation board")
        }
        return board
    }

    private fun valid(doc: JbDocument) {
        val errors = DocOps.validate(doc)
        if (errors.isNotEmpty()) throw DocException(errors.joinToString("; "))
    }

    private fun generator(doc: JbDocument, ids: () -> String): () -> String {
        val used = HashSet<String>()
        used += doc.id
        for (b in doc.boards) { used += b.id; used += b.frames.map { it.id } }
        for (l in doc.layers) { used += l.id; used += DocOps.storedCels(l).map { it.id } }
        return {
            val id = ids()
            if (id.isBlank() || !used.add(id)) throw DocException("ID generator returned an empty or existing ID")
            id
        }
    }
}

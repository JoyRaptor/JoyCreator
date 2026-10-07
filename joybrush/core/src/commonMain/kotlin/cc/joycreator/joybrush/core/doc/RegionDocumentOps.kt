package cc.joycreator.joybrush.core.doc

/** Caller copies the owned content (raster pixels or vector records) before publishing one edit. */
data class RegionCopy(val layerId: String, val fromCelId: String, val toCelId: String, val rect: RectPx)
data class RegionDrop(val layerId: String, val celId: String)
/**
 * Exact replacement, including transparent source pixels. Every source is read from the state
 * BEFORE the entire transaction, never from another transfer's staged destination. Executors
 * split at both source and destination tile boundaries; no scaling or compositing is permitted.
 */
data class RegionTransfer(val layerId: String, val fromCelId: String, val toCelId: String,
    val sourceRect: RectPx, val destinationRect: RectPx) {
    init { checkTransferRects(sourceRect, destinationRect) }
}

/** Same snapshot/replacement contract for a layer's separate mask; absent pixels are WHITE. */
data class RegionMaskTransfer(val layerId: String, val sourceRect: RectPx, val destinationRect: RectPx) {
    init { checkTransferRects(sourceRect, destinationRect) }
}

/** Rectangular erase, preserving every pixel outside [rect]. Applied before snapshot transfers. */
data class RegionClear(val layerId: String, val celId: String, val rect: RectPx) {
    init { checkTransferRects(rect, rect) }
}

/** Rectangular reset to full coverage (white), not transparent black. */
data class RegionMaskClear(val layerId: String, val rect: RectPx) {
    init { checkTransferRects(rect, rect) }
}

private fun checkTransferRects(source: RectPx, destination: RectPx) {
    require(source.w > 0 && source.h > 0 && source.w == destination.w && source.h == destination.h) {
        "Region transfer rectangles must have equal positive dimensions"
    }
    for (rect in listOf(source, destination)) {
        require(rect.x.toLong() + rect.w <= Int.MAX_VALUE && rect.y.toLong() + rect.h <= Int.MAX_VALUE) {
            "Region transfer exceeds addressable canvas coordinates"
        }
    }
}

data class RegionChange(val doc: JbDocument, val copies: List<RegionCopy> = emptyList(), val drops: List<RegionDrop> = emptyList(),
    val transfers: List<RegionTransfer> = emptyList(), val maskTransfers: List<RegionMaskTransfer> = emptyList(),
    val clears: List<RegionClear> = emptyList(), val maskClears: List<RegionMaskClear> = emptyList())

/** Region metadata operations. No legacy conversion, pixel buffers, GPU or history owned here. */
object RegionDocumentOps {
    fun create(doc: JbDocument, name: String, rect: RectPx, ids: () -> String): RegionChange {
        valid(doc)
        BoardDocumentOps.checkRect(rect)
        if (name.isBlank()) throw DocException("Give this board a name")
        if (doc.layers.isEmpty()) throw DocException("Add a layer before creating an animation board")
        if (doc.layers.any { it.animatedIn != null }) {
            throw DocException("Start a new drawing to use region animation")
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

    /** Last-frame deletion is refused; linked cels survive while another frame names them. */
    fun deleteFrame(doc: JbDocument, boardId: String, frameId: String): RegionChange {
        valid(doc)
        val target = board(doc, boardId)
        val index = target.frames.indexOfFirst { it.id == frameId }
        if (index < 0) throw DocException("That frame is not in this board")
        if (target.frames.size == 1) throw DocException("Keep at least one animation frame")
        val frames = target.frames.filterNot { it.id == frameId }
        val cursor = if (target.currentFrameId == frameId) frames[minOf(index, frames.lastIndex)].id else target.currentFrameId
        val drops = ArrayList<RegionDrop>()
        val layers = doc.layers.map { layer ->
            val region = layer.regions.firstOrNull { it.boardId == boardId } ?: return@map layer
            val mappings = region.frameCel - frameId
            val removed = region.frameCel.getValue(frameId)
            val survives = removed in mappings.values
            if (!survives) drops += RegionDrop(layer.id, removed)
            layer.copy(cels = if (survives) layer.cels else layer.cels.filterNot { it.id == removed },
                regions = layer.regions.map { if (it.boardId == boardId) it.copy(frameCel = mappings) else it })
        }
        val next = doc.copy(layers = layers, boards = doc.boards.map {
            if (it.id == boardId) it.copy(frames = frames, currentFrameId = cursor) else it
        })
        valid(next)
        return RegionChange(next, drops = drops)
    }

    fun reorderFrames(doc: JbDocument, boardId: String, frameIds: List<String>): JbDocument {
        valid(doc)
        val target = board(doc, boardId)
        if (frameIds.size != target.frames.size || frameIds.toSet() != target.frames.mapTo(HashSet()) { it.id }) {
            throw DocException("Reorder must contain every frame exactly once")
        }
        val byId = target.frames.associateBy { it.id }
        val next = doc.copy(boards = doc.boards.map {
            if (it.id == boardId) it.copy(frames = frameIds.map { id -> byId.getValue(id) }) else it
        })
        valid(next)
        return next
    }

    fun setHold(doc: JbDocument, boardId: String, frameId: String, ticks: Int): JbDocument {
        valid(doc)
        val target = board(doc, boardId)
        if (target.frames.none { it.id == frameId }) throw DocException("That frame is not in this board")
        val next = doc.copy(boards = doc.boards.map {
            if (it.id == boardId) it.copy(frames = it.frames.map { f -> if (f.id == frameId) f.copy(holdFrames = ticks.coerceIn(1, 999)) else f }) else it
        })
        valid(next)
        return next
    }

    /** Apply the current-frame/shared copy before publishing the new marker; other frames stay intact. */
    fun setHeld(doc: JbDocument, boardId: String, layerId: String, held: Boolean): RegionChange {
        valid(doc)
        val target = board(doc, boardId)
        val layer = doc.layers.firstOrNull { it.id == layerId } ?: throw DocException("No such layer")
        val region = layer.regions.firstOrNull { it.boardId == boardId } ?: throw DocException("Layer is not in this animation board")
        if (region.held == held) return RegionChange(doc)
        val current = region.frameCel.getValue(requireNotNull(target.currentFrameId))
        val shared = requireNotNull(layer.sharedCelId)
        val copy = if (held) RegionCopy(layerId, current, shared, target.rect) else RegionCopy(layerId, shared, current, target.rect)
        val next = doc.copy(layers = doc.layers.map { l -> if (l.id == layerId) l.copy(
            regions = l.regions.map { if (it.boardId == boardId) it.copy(held = held) else it }) else l })
        valid(next)
        return RegionChange(next, listOf(copy))
    }

    /** Held regions deliberately use the shared plane; frame mappings remain available for unhold. */
    fun paintPlan(doc: JbDocument, layerId: String): RegionPaintPlan {
        valid(doc)
        return paintPlanUnchecked(doc, layerId)
    }

    /** Transient playback/onion preview: override board cursors without changing saved document state.
     * The plan addresses content, independent of raster or vector rendering capability.
     */
    fun paintPlan(doc: JbDocument, layerId: String, frameOverrides: Map<String, String>): RegionPaintPlan {
        valid(doc)
        validateOverrides(doc, frameOverrides)
        return previewPlanUnchecked(doc, layerId, frameOverrides)
    }

    /** Playback validates the complete document once per display tick, rather than once per layer. */
    fun paintPlans(doc: JbDocument, frameOverrides: Map<String, String>): Map<String, RegionPaintPlan> {
        valid(doc)
        validateOverrides(doc, frameOverrides)
        return doc.layers.associate { it.id to previewPlanUnchecked(doc, it.id, frameOverrides) }
    }

    private fun validateOverrides(doc: JbDocument, frameOverrides: Map<String, String>) {
        for ((boardId, frameId) in frameOverrides) {
            val target = doc.boards.firstOrNull { it.id == boardId } ?: throw DocException("No such preview board")
            if (target.kind != BoardKind.ANIMATION || target.frames.none { it.id == frameId }) {
                throw DocException("Preview frame is not in this animation board")
            }
        }
    }

    private fun previewPlanUnchecked(doc: JbDocument, layerId: String, frameOverrides: Map<String, String>): RegionPaintPlan {
        val layer = doc.layers.firstOrNull { it.id == layerId } ?: throw DocException("No such layer")
        if (layer.animatedIn != null) throw DocException("Whole-layer animation is an obsolete test format")
        val shared = layer.sharedCelId ?: layer.cels.singleOrNull()?.id ?: throw DocException("No shared content")
        val frames = layer.regions.filterNot { it.held }.map { region ->
            val target = doc.boards.first { it.id == region.boardId }
            val current = frameOverrides[target.id] ?: target.currentFrameId ?: throw DocException("Select a frame first")
            RegionFrame(target.id, target.rect, current, region.frameCel.getValue(current))
        }
        return RegionPaintPlan(shared, frames)
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

    internal fun generator(doc: JbDocument, ids: () -> String): () -> String {
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

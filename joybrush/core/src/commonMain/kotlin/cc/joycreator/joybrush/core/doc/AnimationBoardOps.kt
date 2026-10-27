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
            if (!region.held) for (rect in leaving) transfers += CelParts.transfers(layer,cel,shared,rect)
            for (rect in leaving) clears += CelParts.clears(layer,cel,rect)
            for (rect in entering) transfers += CelParts.transfers(layer,shared,cel,rect)
            // Nothing hides under a board: art the growing board takes in leaves the shared canvas (held layers keep
            // reading and writing the shared canvas, so theirs stays).
            if (!region.held) for (rect in entering) clears += RegionClear(layer.id, shared, rect)
        }
        // Masks are world-space coverage: passive crop changes do not move them.
        return RegionChange(next, transfers = transfers, clears = clears)
    }

    /** C8: translate animated drawings only. Each unique linked cel moves once.
     * Nothing hides under a board (creation and growth move the art into the frames), so the old location is left
     * empty. Shared art already at the destination stays, hidden, and shows again if the board moves away.
     * Still artwork and world-space masks stay in place. */
    fun moveAll(doc: JbDocument, boardId: String, x: Int, y: Int): RegionChange {
        val board = board(doc, boardId)
        val destination = board.rect.copy(x = x, y = y)
        val next = geometry(doc, board, destination, moveParts = true)
        if (destination == board.rect) return RegionChange(doc)
        val transfers = ArrayList<RegionTransfer>()
        val clears = ArrayList<RegionClear>()
        val masks = ArrayList<RegionMaskTransfer>()
        val maskClears = ArrayList<RegionMaskClear>()
        val leaving = subtract(board.rect, destination)
        for (layer in doc.layers) {
            val region = layer.regions.firstOrNull { it.boardId == boardId }
            if (region == null || region.held) continue
            for (cel in CelParts.reachable(layer,region.frameCel.values)) {
                transfers += RegionTransfer(layer.id, cel, cel, board.rect, destination)
                for (rect in leaving) clears += RegionClear(layer.id, cel, rect)
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
            val (cels, graph) = CelParts.clone(layer,region.frameCel.values,fresh)
            for ((old, new) in cels) transfers += RegionTransfer(layer.id, old, new, source.rect, destination)
            val dx=destination.x.toLong()-source.rect.x; val dy=destination.y.toLong()-source.rect.y
            val moved=graph.map { cel -> cel.copy(parts=cel.parts.map { it.copy(rect=CelParts.translate(it.rect,dx,dy)) }) }
            layer.copy(cels = layer.cels + moved, regions = layer.regions +
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
            if (!region.held) transfers += CelParts.transfers(layer,
                region.frameCel.getValue(requireNotNull(board.currentFrameId)), shared, board.rect)
            val regions = layer.regions.filterNot { it.boardId == boardId }
            val retained = CelParts.reachable(layer,regions.flatMap { it.frameCel.values } + shared + layer.frameCel.values)
            val removed = CelParts.reachable(layer,region.frameCel.values) - retained
            drops += removed.map { RegionDrop(layer.id, it) }
            layer.copy(cels = layer.cels.filterNot { it.id in removed }, regions = regions)
        }
        val next = BoardDocumentOps.checked(doc.copy(layers = layers, boards = remaining,
            activeBoardId = if (doc.activeBoardId == boardId) remaining.first().id else doc.activeBoardId))
        return RegionChange(next, transfers = transfers, drops = drops)
    }

    fun previewMove(doc: JbDocument, boardId: String, x: Int, y: Int): JbDocument {
        val b=board(doc,boardId)
        if(b.locked) throw DocException("Unlock this clip before dragging it")
        return geometry(doc,b,b.rect.copy(x=x,y=y),moveParts=true)
    }

    /** C5: the two timelines become one region; repeated source drawings stay linked.
     * All reads are from the pre-edit snapshot, including the still artwork in the expanded crop. */
    fun combine(doc: JbDocument, a: String, b: String, ids: () -> String): RegionChange {
        if (a == b) throw DocException("Choose two different clips")
        val first = board(doc, a); val second = board(doc, b)
        if (!touches(first.rect, second.rect)) throw DocException("Move these clips together before combining them")
        if (first.locked || second.locked) throw DocException("Unlock both clips before combining them")
        val rect = union(first.rect, second.rect)
        BoardDocumentOps.checkRect(rect)
        val fps = maxOf(first.fps, second.fps)
        val left = ClipTimeline(first, fps); val right = ClipTimeline(second, fps)
        val length = maxOf(left.length, right.length)
        // Bound metadata before allocation. Unlike export's 600-frame cap this never truncates drawings.
        if (length > 100_000) throw DocException("These clips need more than 100,000 frames together; shorten their holds first")
        val fresh = RegionDocumentOps.generator(doc, ids)
        val id = fresh(); val frames = List(length) { Frame(fresh()) }
        val larger = if (first.rect.w.toLong() * first.rect.h >= second.rect.w.toLong() * second.rect.h) first else second
        val combined = Board(id, larger.name, BoardKind.ANIMATION, rect, fps = fps,
            frames = frames, currentFrameId = frames.first().id, end = larger.end)
        val transfers = ArrayList<RegionTransfer>(); val clears = ArrayList<RegionClear>(); val drops = ArrayList<RegionDrop>()
        val layers = doc.layers.map { layer ->
            val ar = layer.regions.firstOrNull { it.boardId == a && !it.held }
            val br = layer.regions.firstOrNull { it.boardId == b && !it.held }
            if (ar != null && br != null && overlaps(first.rect, second.rect))
                throw DocException("\"${layer.name}\" animates in both \"${first.name}\" and \"${second.name}\" here; keep its drawings in separate places")
            val oldRegions = layer.regions.filter { it.boardId == a || it.boardId == b }
            val rest = layer.regions - oldRegions.toSet()
            val shared = shared(layer)
            val cels = ArrayList<Cel>()
            val mappings = LinkedHashMap<String, String>()
            if (ar == null && br == null) {
                val cel = fresh(); cels += Cel(cel)
                frames.forEach { mappings[it.id] = cel }
            } else {
                // Cel pairs are the identity: A on 2s stays linked even when the other layer is on 1s.
                val pairs = HashMap<Pair<String?, String?>, String>()
                var still = listOf(rect)
                if (ar != null) still = still.flatMap { subtract(it, first.rect) }
                if (br != null) still = still.flatMap { subtract(it, second.rect) }
                for ((tick, frame) in frames.withIndex()) {
                    val ac = if (ar == null) null else left.frameAt(tick)?.let { ar.frameCel.getValue(it) }
                    val bc = if (br == null) null else right.frameAt(tick)?.let { br.frameCel.getValue(it) }
                    val cel = pairs.getOrPut(ac to bc) {
                        fresh().also { target ->
                            val sourceId=ac ?: bc
                            val plainSingle=(ar == null || br == null) && (sourceId == null || layer.cels.first { it.id == sourceId }.parts.isEmpty())
                            if(plainSingle) {
                                cels += Cel(target)
                                if(ac != null) transfers += RegionTransfer(layer.id,ac,target,first.rect,first.rect)
                                if(bc != null) transfers += RegionTransfer(layer.id,bc,target,second.rect,second.rect)
                            } else cels += Cel(target,parts=listOfNotNull(ac?.let { CelPart(it,first.rect) },bc?.let { CelPart(it,second.rect) }))
                            for (area in still) transfers += RegionTransfer(layer.id, shared, target, area, area)
                        }
                    }
                    mappings[frame.id] = cel
                }
                clears += RegionClear(layer.id, shared, rect)
            }
            val regions = rest + RegionFrames(id, mappings, held = ar == null && br == null)
            val graph=layer.copy(cels=layer.cels+cels)
            val retained = CelParts.reachable(graph,regions.flatMap { it.frameCel.values } + shared + layer.frameCel.values)
            val removed = CelParts.reachable(layer,oldRegions.flatMap { it.frameCel.values }) - retained
            drops += removed.map { RegionDrop(layer.id, it) }
            layer.copy(cels = layer.cels.filterNot { it.id in removed } + cels, regions = regions)
        }
        val next = BoardDocumentOps.checked(doc.copy(version=DOC_VERSION,layers = layers,
            boards = doc.boards.filterNot { it.id == a || it.id == b } + combined, activeBoardId = id))
        return RegionChange(next, drops = drops, transfers = transfers, clears = clears)
    }

    /** A frame-only structural edit detaches the current drawing, leaving every linked repeat untouched. */
    fun isolateFrame(doc: JbDocument, boardId: String, layerId: String, blank: Boolean, ids: () -> String): RegionChange {
        val b=board(doc,boardId); val frame=requireNotNull(b.currentFrameId)
        val layer=doc.layers.first { it.id==layerId }; val region=layer.regions.first { it.boardId==boardId }
        val source=region.frameCel.getValue(frame); val fresh=RegionDocumentOps.generator(doc,ids)
        val (mapping,cels)=if(blank) emptyMap<String,String>() to listOf(Cel(fresh())) else CelParts.clone(layer,listOf(source),fresh)
        val target=if(blank) cels.single().id else mapping.getValue(source)
        var replacement=layer.copy(cels=layer.cels+cels,regions=layer.regions.map {
            if(it.boardId==boardId) it.copy(frameCel=it.frameCel+(frame to target)) else it
        })
        val retained=CelParts.reachable(replacement,replacement.regions.flatMap { it.frameCel.values }+listOfNotNull(layer.sharedCelId))
        val removed=CelParts.reachable(layer,listOf(source))-retained
        replacement=replacement.copy(cels=replacement.cels.filterNot { it.id in removed })
        val next=BoardDocumentOps.checked(doc.copy(layers=doc.layers.map { if(it.id==layerId) replacement else it }))
        return RegionChange(next,copies=mapping.map { (from,to) -> RegionCopy(layerId,from,to,b.rect) },drops=removed.map { RegionDrop(layerId,it) })
    }

    fun touches(a: RectPx, b: RectPx): Boolean = a.x.toLong() <= b.x.toLong() + b.w && b.x.toLong() <= a.x.toLong() + a.w &&
        a.y.toLong() <= b.y.toLong() + b.h && b.y.toLong() <= a.y.toLong() + a.h

    fun overlaps(a: RectPx, b: RectPx): Boolean = a.x.toLong() < b.x.toLong() + b.w && b.x.toLong() < a.x.toLong() + a.w &&
        a.y.toLong() < b.y.toLong() + b.h && b.y.toLong() < a.y.toLong() + a.h

    fun union(a: RectPx, b: RectPx): RectPx {
        val x = minOf(a.x, b.x); val y = minOf(a.y, b.y)
        val w = maxOf(a.x.toLong() + a.w, b.x.toLong() + b.w) - x
        val h = maxOf(a.y.toLong() + a.h, b.y.toLong() + b.h) - y
        if (w > Int.MAX_VALUE || h > Int.MAX_VALUE) throw DocException("These clips cover too large an area to combine")
        return RectPx(x, y, w.toInt(), h.toInt())
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

    private fun geometry(doc: JbDocument, board: Board, rect: RectPx, moveParts: Boolean = false): JbDocument {
        BoardDocumentOps.checkRect(rect)
        val dx=rect.x.toLong()-board.rect.x; val dy=rect.y.toLong()-board.rect.y
        val layers=if(!moveParts) doc.layers else doc.layers.map { layer ->
            val region=layer.regions.firstOrNull { it.boardId==board.id && !it.held } ?: return@map layer
            val moved=CelParts.reachable(layer,region.frameCel.values)
            layer.copy(cels=layer.cels.map { c -> if(c.id !in moved) c else c.copy(parts=c.parts.map { it.copy(rect=CelParts.translate(it.rect,dx,dy)) }) })
        }
        return BoardDocumentOps.checked(doc.copy(layers=layers,boards = doc.boards.map { if (it.id == board.id) it.copy(rect = rect) else it }))
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

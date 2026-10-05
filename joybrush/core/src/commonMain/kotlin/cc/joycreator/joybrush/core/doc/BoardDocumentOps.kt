package cc.joycreator.joybrush.core.doc

/** Immutable board edits. Passive boards never move or duplicate paint. */
object BoardDocumentOps {
    fun createImage(doc: JbDocument, name: String, rect: RectPx, ids: () -> String): RegionChange =
        createPassive(doc, name, rect, BoardKind.CANVAS, null, ids)

    fun createSprite(doc: JbDocument, name: String, rect: RectPx, grid: SpriteGrid, ids: () -> String): RegionChange =
        createPassive(doc, name, rect, BoardKind.SPRITE, grid, ids)

    fun createAnimation(doc: JbDocument, name: String, rect: RectPx, ids: () -> String): RegionChange {
        checkRect(rect)
        checkName(name)
        return RegionDocumentOps.create(doc, name.trim(), rect, ids)
    }

    fun rename(doc: JbDocument, boardId: String, name: String): JbDocument {
        checkName(name)
        return update(doc, boardId) { it.copy(name = name.trim()) }
    }

    fun select(doc: JbDocument, boardId: String?): JbDocument {
        valid(doc)
        if (boardId != null) board(doc, boardId)
        return checked(doc.copy(activeBoardId = boardId))
    }

    fun setLocked(doc: JbDocument, boardId: String, locked: Boolean): JbDocument =
        update(doc, boardId) { it.copy(locked = locked) }

    /** Typing dimensions anchors the top-left, per H1. */
    fun resizeTyped(doc: JbDocument, boardId: String, width: Int, height: Int): JbDocument =
        update(doc, boardId) {
            geometryAllowed(it)
            val rect = it.rect.copy(w = width, h = height)
            checkRect(rect)
            val grid = if (it.kind == BoardKind.SPRITE) {
                val previous = requireNotNull(it.grid)
                if (width % previous.cellW != 0 || height % previous.cellH != 0) {
                    throw DocException("Sprite board size must be a whole grid of cells")
                }
                previous.copy(cols = width / previous.cellW, rows = height / previous.cellH).also { g -> checkGrid(rect, g) }
            } else it.grid
            it.copy(rect = rect, grid = grid)
        }

    fun move(doc: JbDocument, boardId: String, x: Int, y: Int): JbDocument =
        update(doc, boardId) {
            geometryAllowed(it)
            val rect = it.rect.copy(x = x, y = y)
            checkRect(rect)
            it.copy(rect = rect)
        }

    /** A numbered passive frame over the same canvas; no copy of its artwork. */
    fun duplicatePassive(doc: JbDocument, boardId: String, ids: () -> String): RegionChange {
        valid(doc)
        val source = board(doc, boardId)
        if (source.kind != BoardKind.CANVAS && source.kind != BoardKind.SPRITE) {
            throw DocException("Animation duplication needs a region pixel transaction")
        }
        val names = doc.boards.mapTo(HashSet()) { it.name }
        var number = 2
        while ("${source.name} $number" in names) number++
        val copy = source.copy(id = freshId(doc, ids), name = "${source.name} $number")
        return RegionChange(checked(doc.copy(boards = doc.boards + copy, activeBoardId = copy.id)))
    }

    /** Removing an animation board requires flattening its selected frame first. */
    fun remove(doc: JbDocument, boardId: String): JbDocument {
        valid(doc)
        val source = board(doc, boardId)
        if (source.kind == BoardKind.ANIMATION) throw DocException("Remove animation with a region pixel transaction")
        val remaining = doc.boards.filterNot { it.id == boardId }
        if (remaining.isEmpty()) throw DocException("Keep at least one board")
        if (source.kind == BoardKind.CANVAS && remaining.none { it.kind == BoardKind.CANVAS }) {
            throw DocException("Keep at least one Image board for the canvas")
        }
        return checked(doc.copy(boards = remaining,
            activeBoardId = if (doc.activeBoardId == boardId) remaining.first().id else doc.activeBoardId))
    }

    /** K10: arm alone does not change the saved icon; the first wrapped stroke does. */
    fun markWrappedStroke(doc: JbDocument, boardId: String): JbDocument =
        update(doc, boardId) {
            if (it.kind != BoardKind.CANVAS) throw DocException("Only an Image board can tile")
            it.copy(tiled = true)
        }

    private fun createPassive(doc: JbDocument, name: String, rect: RectPx, kind: BoardKind,
        grid: SpriteGrid?, ids: () -> String): RegionChange {
        valid(doc); checkName(name); checkRect(rect)
        if (grid != null) checkGrid(rect, grid)
        val added = Board(freshId(doc, ids), name.trim(), kind, rect, grid = grid)
        return RegionChange(checked(doc.copy(version = DOC_VERSION, boards = doc.boards + added, activeBoardId = added.id)))
    }

    private fun geometryAllowed(board: Board) {
        if (board.locked) throw DocException("Unlock this board before moving or resizing it")
        if (board.kind == BoardKind.ANIMATION) {
            if (board.frames.size > 1) throw DocException("Animation with multiple frames is fixed; use Move all frames")
            throw DocException("Animation geometry needs a region pixel transaction")
        }
    }

    private fun checkGrid(rect: RectPx, grid: SpriteGrid) {
        if (grid.cols < 1 || grid.rows < 1 || grid.cellW < 1 || grid.cellH < 1 ||
            grid.cols.toLong() * grid.cellW != rect.w.toLong() || grid.rows.toLong() * grid.cellH != rect.h.toLong()) {
            throw DocException("Sprite board size must be a whole grid of cells")
        }
    }

    internal fun checkRect(rect: RectPx) {
        if (rect.w <= 0 || rect.h <= 0 || rect.x.toLong() + rect.w > Int.MAX_VALUE ||
            rect.y.toLong() + rect.h > Int.MAX_VALUE) throw DocException("Board bounds must contain addressable pixels")
    }

    private fun checkName(name: String) {
        if (name.isBlank()) throw DocException("Give this board a name")
    }

    internal fun freshId(doc: JbDocument, ids: () -> String): String {
        val used = buildSet {
            add(doc.id)
            doc.boards.forEach { add(it.id); addAll(it.frames.map(Frame::id)) }
            doc.layers.forEach { add(it.id); addAll(DocOps.storedCels(it).map(Cel::id)) }
        }
        return ids().also { if (it.isBlank() || it in used) throw DocException("ID generator returned an empty or existing ID") }
    }

    private fun update(doc: JbDocument, id: String, edit: (Board) -> Board): JbDocument {
        valid(doc)
        val changed = edit(board(doc, id))
        return checked(doc.copy(boards = doc.boards.map { if (it.id == id) changed else it }))
    }

    internal fun board(doc: JbDocument, id: String): Board =
        doc.boards.firstOrNull { it.id == id } ?: throw DocException("No such board")

    internal fun valid(doc: JbDocument) {
        val errors = DocOps.validate(doc)
        if (errors.isNotEmpty()) throw DocException(errors.joinToString("; "))
    }

    internal fun checked(doc: JbDocument): JbDocument = doc.also { valid(it) }
}

package cc.joycreator.joybrush.core.doc

import cc.joycreator.joybrush.core.sprite.SpriteGridMath

/** Content-only Sprite edits. The caller executes the complete change atomically as one undo. */
object SpriteCellOps {
    /**
     * Swap every layer, including hidden/locked layers, using saved current-frame ownership.
     * Held regions use shared content; linked frames retain their existing physical cel links.
     * Other frames and shared content hidden under animated regions are not touched. PAINT and
     * INK use the same address plan; an executor must refuse unsupported content before mutation.
     * Masks are independent global stores. Paper and all document metadata remain unchanged.
     */
    fun swap(doc: JbDocument, boardId: String, fromIndex: Int, toIndex: Int): RegionChange {
        val plans = RegionDocumentOps.paintPlans(doc, emptyMap())
        val board = BoardDocumentOps.board(doc, boardId)
        if (board.kind != BoardKind.SPRITE) throw DocException("Select a Sprite board to swap cells")
        if (!board.locked) throw DocException("Lock this Sprite board before rearranging cells")
        BoardDocumentOps.checkRect(board.rect)
        val grid = board.grid ?: throw DocException("Sprite board has no grid")
        // cellRect clamps malformed legacy grids; content operations must never silently do so.
        if (grid.cols < 1 || grid.rows < 1 || grid.cols.toLong() * grid.rows > SpriteGridMath.MAX_CELLS ||
            grid.cellW !in 1..SpriteGridMath.MAX_CELL_PX || grid.cellH !in 1..SpriteGridMath.MAX_CELL_PX) {
            throw DocException("Sprite grid exceeds supported cell limits")
        }
        if (grid.cols.toLong() * grid.cellW > board.rect.w || grid.rows.toLong() * grid.cellH > board.rect.h) {
            throw DocException("Sprite cells extend outside the board")
        }
        val count = grid.cols * grid.rows
        if (fromIndex !in 0 until count || toIndex !in 0 until count) throw DocException("No such Sprite cell")
        val a = SpriteGridMath.cellRect(board, fromIndex)
        val b = SpriteGridMath.cellRect(board, toIndex)
        if (fromIndex == toIndex) return RegionChange(doc)
        val transfers = ArrayList<RegionTransfer>()
        val masks = ArrayList<RegionMaskTransfer>()
        for (layer in doc.layers) {
            val plan = plans.getValue(layer.id)
            // Local cell offsets cut at BOTH ownership arrangements. Each resulting pair has
            // exactly one source cel and one destination cel, even across two animation boards.
            val xs = mutableSetOf(0, a.w)
            val ys = mutableSetOf(0, a.h)
            for (frame in plan.frames) for (cell in listOf(a, b)) {
                val left = maxOf(0L, frame.rect.x.toLong() - cell.x)
                val top = maxOf(0L, frame.rect.y.toLong() - cell.y)
                val right = minOf(cell.w.toLong(), frame.rect.x.toLong() + frame.rect.w - cell.x)
                val bottom = minOf(cell.h.toLong(), frame.rect.y.toLong() + frame.rect.h - cell.y)
                if (right > left && bottom > top) {
                    xs.add(left.toInt()); xs.add(right.toInt())
                    ys.add(top.toInt()); ys.add(bottom.toInt())
                }
            }
            val xCuts = xs.sorted(); val yCuts = ys.sorted()
            for (yi in 0 until yCuts.lastIndex) for (xi in 0 until xCuts.lastIndex) {
                val x = xCuts[xi]; val y = yCuts[yi]
                val width = xCuts[xi + 1] - x; val height = yCuts[yi + 1] - y
                val source = RectPx(a.x + x, a.y + y, width, height)
                val destination = RectPx(b.x + x, b.y + y, width, height)
                val sourceCel = plan.planeAt(source.x.toLong(), source.y.toLong()).celId
                val destinationCel = plan.planeAt(destination.x.toLong(), destination.y.toLong()).celId
                transfers += RegionTransfer(layer.id, sourceCel, destinationCel, source, destination)
                transfers += RegionTransfer(layer.id, destinationCel, sourceCel, destination, source)
            }
            if (layer.mask != null) {
                masks += RegionMaskTransfer(layer.id, a, b)
                masks += RegionMaskTransfer(layer.id, b, a)
            }
        }
        return RegionChange(doc, transfers = transfers, maskTransfers = masks)
    }
}

package cc.joycreator.joybrush.core.paint

/**
 * Undo/redo of tile changes, with a memory budget — shared by the GPU engine (T = a texture) and the
 * CPU reference (T = a pixel array).
 *
 * The engine works COPY-ON-WRITE: committing a stroke renders each touched tile into a NEW tile and
 * swaps it in, so the old tile IS the undo snapshot — no copying. One [TileChange] records
 * (layer, tile, before, after); `null` means "the tile did not exist".
 *
 * Ownership, so every tile is released exactly once:
 * - A step in the undo stack owns its `before` tiles (they are not in any layer).
 * - A step in the redo stack owns its `after` tiles.
 * - Everything else is owned by the layers.
 * Dropping the oldest undo step (over budget) releases its `before`s; discarding the redo stack (a new
 * stroke after undoing) releases the redo steps' `after`s.
 *
 * The newest undo step is always kept, even over budget, so one undo always works.
 */
class UndoLog<T : Any>(
    private val budgetBytes: Long,
    private val sizeOf: (T) -> Long,
    private val release: (T) -> Unit,
) {
    class TileChange<T : Any>(val layerId: String, val key: Long, val before: T?, val after: T?)
    class Step<T : Any>(val changes: List<TileChange<T>>)

    private val undoStack = ArrayDeque<Step<T>>()
    private val redoStack = ArrayDeque<Step<T>>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoDepth: Int get() = undoStack.size

    /** Bytes currently held only for undo/redo. */
    val heldBytes: Long
        get() = undoStack.sumOf { s -> s.changes.sumOf { c -> c.before?.let(sizeOf) ?: 0L } } +
            redoStack.sumOf { s -> s.changes.sumOf { c -> c.after?.let(sizeOf) ?: 0L } }

    /** Records a committed stroke. Discards (and releases) anything that could have been redone. */
    fun push(step: Step<T>) {
        while (redoStack.isNotEmpty()) redoStack.removeLast().changes.forEach { c -> c.after?.let(release) }
        undoStack.addLast(step)
        trim()
    }

    /** Pops the newest step. The caller puts each change's `before` back into its layer. */
    fun undo(): Step<T>? {
        val s = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(s)
        return s
    }

    /** Pops the newest undone step. The caller puts each change's `after` back into its layer. */
    fun redo(): Step<T>? {
        val s = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(s)
        trim()
        return s
    }

    /** Releases everything held for undo/redo (document closed). */
    fun clear() {
        undoStack.forEach { s -> s.changes.forEach { c -> c.before?.let(release) } }
        redoStack.forEach { s -> s.changes.forEach { c -> c.after?.let(release) } }
        undoStack.clear(); redoStack.clear()
    }

    private fun trim() {
        while (undoStack.size > 1 && heldBytes > budgetBytes) {
            undoStack.removeFirst().changes.forEach { c -> c.before?.let(release) }
        }
    }
}

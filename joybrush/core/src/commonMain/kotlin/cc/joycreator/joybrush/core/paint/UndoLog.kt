package cc.joycreator.joybrush.core.paint

import cc.joycreator.joybrush.core.layers.LayerStack
import cc.joycreator.joybrush.core.doc.Paper

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

    /**
     * One undo step: the tiles it changed and, for a change to the layers themselves (JB-2.04: add, duplicate, delete,
     * move, opacity, blend, rename), the stack before and after. Null stacks = a pixels-only step, as every stroke is.
     *
     * Applying a step is ALWAYS tiles first, then the stack — in both directions. That one order makes every structural
     * step work: undoing a delete puts the tiles back (which recreates the layer) and then the stack puts it back in its
     * place; undoing a duplicate takes its tiles away and then the stack removes the now-empty layer.
     */
    class Step<T : Any>(
        val changes: List<TileChange<T>>,
        val stackBefore: LayerStack? = null,
        val stackAfter: LayerStack? = null,
        val paperBefore: Paper? = null,
        val paperAfter: Paper? = null,
    )

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

    /**
     * Folds the newest [count] steps into ONE, so a single press that laid several strokes is a single undo (the owner's
     * rule: one press, one step). Per tile, the merged step keeps the oldest `before` and the newest `after`. The tiles in
     * between belonged to nobody but the folded steps — the layer holds the newest, the merged step the oldest — so they are
     * released here. Does nothing for fewer than two steps.
     */
    fun mergeNewest(count: Int) {
        val n = minOf(count, undoStack.size)
        if (n < 2) return
        val steps = ArrayList<Step<T>>(n)
        repeat(n) { steps.add(0, undoStack.removeLast()) }
        val merged = LinkedHashMap<Pair<String, Long>, TileChange<T>>()
        for (s in steps) for (c in s.changes) {
            val k = c.layerId to c.key
            val prev = merged[k]
            if (prev == null) {
                merged[k] = c
            } else {
                // c.before is prev.after: copy-on-write made it, and now nothing will ever put it back.
                c.before?.let(release)
                merged[k] = TileChange(c.layerId, c.key, prev.before, c.after)
            }
        }
        // A layer change inside the batch (JB-2.04) is kept: the merged step goes from the first stack to the last.
        val stackBefore = steps.firstOrNull { it.stackBefore != null }?.stackBefore
        val stackAfter = steps.lastOrNull { it.stackAfter != null }?.stackAfter
        undoStack.addLast(Step(merged.values.toList(), stackBefore, stackAfter,
            steps.firstOrNull { it.paperBefore != null }?.paperBefore,
            steps.lastOrNull { it.paperAfter != null }?.paperAfter))
        trim()
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

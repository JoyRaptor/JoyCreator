package cc.joycreator.joybrush.core.paint

import cc.joycreator.joybrush.core.layers.LayerStack
import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.doc.JbDocument

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
    class TileChange<T : Any>(val layerId: String, val key: Long, val before: T?, val after: T?, val celId: String? = null)

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
        val documentBefore: JbDocument? = null,
        val documentAfter: JbDocument? = null,
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
        val merged = LinkedHashMap<Triple<String, String?, Long>, TileChange<T>>()
        for (s in steps) for (c in s.changes) {
            val k = Triple(c.layerId, c.celId, c.key)
            val prev = merged[k]
            if (prev == null) {
                merged[k] = c
            } else {
                // c.before is prev.after: copy-on-write made it, and now nothing will ever put it back.
                c.before?.let(release)
                merged[k] = TileChange(c.layerId, c.key, prev.before, c.after, c.celId)
            }
        }
        // A layer change inside the batch (JB-2.04) is kept: the merged step goes from the first stack to the last.
        val stackBefore = steps.firstOrNull { it.stackBefore != null }?.stackBefore
        val stackAfter = steps.lastOrNull { it.stackAfter != null }?.stackAfter
        undoStack.addLast(Step(merged.values.toList(), stackBefore, stackAfter,
            steps.firstOrNull { it.paperBefore != null }?.paperBefore,
            steps.lastOrNull { it.paperAfter != null }?.paperAfter,
            steps.firstOrNull { it.documentBefore != null }?.documentBefore,
            steps.lastOrNull { it.documentAfter != null }?.documentAfter))
        trim()
    }

    /**
     * Adds [changes] to the newest step: tiles copied-on-write AFTER that step was pushed, by something that is still part of
     * what the person saw happen while it was the last thing they did. The case is running water (MEDIA_ENGINE_PLAN M5.3c):
     * a drip that reaches a new tile during stroke B joins B's step, so history stays in order and one press is one step.
     *
     * Refused (false, nothing changed) when there is anything to redo, nothing to undo, or [accepts] says the newest step
     * is not one these changes belong in. The caller then pushes a step of its own and extends that one. A tile already in
     * the newest step is written in place by the caller, never added twice.
     */
    fun extendNewest(changes: List<TileChange<T>>, accepts: (Step<T>) -> Boolean): Boolean {
        if (redoStack.isNotEmpty()) return false
        val top = undoStack.lastOrNull() ?: return false
        if (!accepts(top)) return false
        val held = top.changes.mapTo(HashSet()) { Triple(it.layerId, it.celId, it.key) }
        require(changes.none { Triple(it.layerId, it.celId, it.key) in held }) { "a tile already in the newest step was snapshotted again" }
        undoStack[undoStack.lastIndex] = Step(top.changes + changes, top.stackBefore, top.stackAfter, top.paperBefore, top.paperAfter,
            top.documentBefore, top.documentAfter)
        trim()
        return true
    }

    /**
     * Replaces changes of the newest step tile for tile (same layer, cel and key), leaving the rest of the step as it was.
     * A replacement with neither a `before` nor an `after` removes the change: the tile was made and dropped inside the
     * step (water that came and dried); a step with no change left, and nothing else to undo, is popped. The caller
     * releases the `after`s it replaced, which nothing else holds. Refused
     * (false, nothing changed) when there is anything to redo, nothing to undo, or a replacement names a tile the step
     * does not hold.
     */
    fun replaceInNewest(replacements: List<TileChange<T>>): Boolean {
        if (redoStack.isNotEmpty() || replacements.isEmpty()) return false
        val top = undoStack.lastOrNull() ?: return false
        val byTile = replacements.associateBy { Triple(it.layerId, it.celId, it.key) }
        val held = top.changes.mapTo(HashSet()) { Triple(it.layerId, it.celId, it.key) }
        if (!held.containsAll(byTile.keys)) return false
        val changes = top.changes.mapNotNull { c ->
            val r = byTile[Triple(c.layerId, c.celId, c.key)] ?: return@mapNotNull c
            if (r.before == null && r.after == null) null else r
        }
        val bare = top.stackBefore == null && top.stackAfter == null && top.paperBefore == null && top.paperAfter == null &&
            top.documentBefore == null && top.documentAfter == null
        // A step left with nothing in it (all its water came and dried inside it) is popped: an Undo that does nothing
        // visible would break one press, one step. The caller tells the history buttons ([canUndo] may now be false).
        if (changes.isEmpty() && bare) undoStack.removeLast()
        else undoStack[undoStack.lastIndex] = Step(changes, top.stackBefore, top.stackAfter, top.paperBefore, top.paperAfter,
            top.documentBefore, top.documentAfter)
        return true
    }

    /** The step [extendNewest] would extend, or null when there is anything to redo or nothing to undo. */
    fun newestExtendable(): Step<T>? = if (redoStack.isNotEmpty()) null else undoStack.lastOrNull()

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

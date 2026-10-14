package cc.joycreator.joybrush.core.paint

import cc.joycreator.joybrush.core.layers.LayerStack
import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.vector.CelComposer
import cc.joycreator.joybrush.core.vector.InkTiles

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
 * [release] must not throw. Publication callbacks must only assign one already-prepared scene
 * reference, without allocation or history re-entry; that assignment must not throw.
 */
class UndoLog<T : Any>(
    private val budgetBytes: Long,
    private val sizeOf: (T) -> Long,
    private val release: (T) -> Unit,
) {
    class TileChange<T : Any>(val layerId: String, val key: Long, val before: T?, val after: T?, val celId: String? = null)

    /** An ordered pixel slab's texture and the operator used to composite it (JB-5.20 D8a). */
    class SlabValue<T : Any>(val texture: T, val op: String) {
        init { require(InkTiles.knowsOp(op)) { "unsupported slab operator \"$op\"" } }
    }

    /**
     * A slab is identified by layer, cel, tile and seq; null means it did not exist.
     * Both endpoints require distinct copy-on-write textures, even for an operator-only change:
     * history owns the before while the live slab owns the after, so trimming must not release the live texture.
     */
    class SlabChange<T : Any>(
        val layerId: String,
        val celId: String?,
        val key: Long,
        val seq: Long,
        val before: SlabValue<T>?,
        val after: SlabValue<T>?,
    ) {
        init {
            require(seq >= 0L) { "slab seq must be nonnegative" }
            require(before == null || after == null || before.texture != after.texture) {
                "a changed slab requires distinct copy-on-write textures"
            }
        }
    }

    private data class SlabKey(val layerId: String, val celId: String?, val key: Long, val seq: Long)

    /**
     * One editable line's change (JB-5.20 D9): the line before and after, each with its seq, null = it did not exist then.
     * An undone delete therefore goes back to its own place in time order. Lines hold no texture, so a step drops them
     * and never releases them; they count toward the budget by their samples ([bytes]).
     */
    class LineChange(val layerId: String, val celId: String?, val id: String, val before: CelComposer.Line?, val after: CelComposer.Line?) {
        val bytes: Long get() = LINE_BYTES + SAMPLE_BYTES * ((before?.record?.samples?.size ?: 0) + (after?.record?.samples?.size ?: 0))

        companion object {
            /** A pen sample on the heap: ten numbers and an object header, rounded up. */
            const val SAMPLE_BYTES = 64L
            const val LINE_BYTES = 128L
        }
    }

    /**
     * One undo step: the tiles it changed and, for a change to the layers themselves (JB-2.04: add, duplicate, delete,
     * move, opacity, blend, rename), the stack before and after. Null stacks = a pixels-only step, as every stroke is.
     *
     * Applying a step is ALWAYS tiles first, then the stack — in both directions. That one order makes every structural
     * step work: undoing a delete puts the tiles back (which recreates the layer) and then the stack puts it back in its
     * place; undoing a duplicate takes its tiles away and then the stack removes the now-empty layer.
     */
    class Step<T : Any>(
        changes: List<TileChange<T>>,
        val stackBefore: LayerStack? = null,
        val stackAfter: LayerStack? = null,
        val paperBefore: Paper? = null,
        val paperAfter: Paper? = null,
        val documentBefore: JbDocument? = null,
        val documentAfter: JbDocument? = null,
        /** Editable lines this step changed (JB-5.20 D9). One history: a line edit is a step like any stroke. */
        lines: List<LineChange> = emptyList(),
        /** Ordered pixel slabs, with the same copy-on-write ownership as [changes]. */
        slabs: List<SlabChange<T>> = emptyList(),
    ) {
        // Snapshot caller-owned collection backing, including the samples used by line replay.
        val changes: List<TileChange<T>> = changes.toList()
        val lines: List<LineChange> = lines.map { c ->
            LineChange(c.layerId, c.celId, c.id, snapshot(c.before), snapshot(c.after))
        }
        val slabs: List<SlabChange<T>> = slabs.toList()

        init {
            val before = HashSet<T>()
            val after = HashSet<T>()
            for (c in this.changes) {
                require(c.before == null || before.add(c.before)) { "duplicate before texture ownership" }
                require(c.after == null || after.add(c.after)) { "duplicate after texture ownership" }
            }
            // Same-family chains can share an intermediate after/before, and legacy tile no-ops
            // can share their two endpoints. A tile/slab alias cannot be owned by both families.
            val tileBefore = before.toSet()
            val tileAfter = after.toSet()
            for (c in this.slabs) {
                c.before?.texture?.let {
                    require(it !in tileAfter) { "tile/slab cross-endpoint texture ownership" }
                    require(before.add(it)) { "duplicate before texture ownership" }
                }
                c.after?.texture?.let {
                    require(it !in tileBefore) { "tile/slab cross-endpoint texture ownership" }
                    require(after.add(it)) { "duplicate after texture ownership" }
                }
            }
        }

        private fun snapshot(line: CelComposer.Line?): CelComposer.Line? = line?.let {
            CelComposer.Line(it.seq, it.record.copy(samples = it.record.samples.toList()))
        }
    }

    private var undoStack = ArrayDeque<Step<T>>()
    private var redoStack = ArrayDeque<Step<T>>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoDepth: Int get() = undoStack.size

    /** Bytes currently held only for undo/redo. */
    val heldBytes: Long
        get() = heldBytes(undoStack, redoStack)

    private fun heldBytes(undo: Collection<Step<T>>, redo: Collection<Step<T>>): Long =
        undo.sumOf { s -> s.changes.sumOf { c -> c.before?.let(sizeOf) ?: 0L } +
            s.slabs.sumOf { c -> c.before?.texture?.let(sizeOf) ?: 0L } + s.lines.sumOf { it.bytes } } +
            redo.sumOf { s -> s.changes.sumOf { c -> c.after?.let(sizeOf) ?: 0L } +
                s.slabs.sumOf { c -> c.after?.texture?.let(sizeOf) ?: 0L } + s.lines.sumOf { it.bytes } }

    /** Records a committed stroke. Discards (and releases) anything that could have been redone. */
    fun push(step: Step<T>) {
        pushAndPublish(step) {}
    }

    /**
     * Records [step] and publishes its prepared scene as one transaction. Capacity and budget
     * work happen before [publish]; a callback failure keeps both histories and ownership intact.
     * The callback must follow the nonthrowing scene-assignment contract in [UndoLog].
     */
    fun pushAndPublish(step: Step<T>, publish: () -> Unit) {
        val nextUndo = copyWithCapacity(undoStack, 1)
        nextUndo.addLast(step)
        val nextRedo = ArrayDeque<Step<T>>()
        val dropped = prepareTrim(nextUndo, nextRedo)
        val discarded = redoStack
        publish()
        undoStack = nextUndo
        redoStack = nextRedo
        while (discarded.isNotEmpty()) releaseAfter(discarded.removeLast())
        dropped.forEach(::releaseBefore)
    }

    /** Pops the newest step. The caller puts each change's `before` back into its layer. */
    fun undo(): Step<T>? {
        val s = peekUndo() ?: return null
        return if (undoAndPublish(s) {}) s else null
    }

    /**
     * Publishes a prepared undo scene only if [expectedStep] is still the newest step by identity.
     * Returns false before calling [publish] for a stale preflight. Callback failure changes nothing.
     */
    fun undoAndPublish(expectedStep: Step<T>, publish: () -> Unit): Boolean {
        if (peekUndo() !== expectedStep) return false
        val nextUndo = copyWithCapacity(undoStack)
        nextUndo.removeLast()
        val nextRedo = copyWithCapacity(redoStack, 1)
        nextRedo.addLast(expectedStep)
        publish()
        undoStack = nextUndo
        redoStack = nextRedo
        return true
    }

    /** The next undo step, without changing history or ownership, for render preflight. */
    fun peekUndo(): Step<T>? = undoStack.lastOrNull()

    /** The next redo step, without changing history or ownership, for render preflight. */
    fun peekRedo(): Step<T>? = redoStack.lastOrNull()

    /** Pops the newest undone step. The caller puts each change's `after` back into its layer. */
    fun redo(): Step<T>? {
        val s = peekRedo() ?: return null
        return if (redoAndPublish(s) {}) s else null
    }

    /** Like [undoAndPublish], including stale-step refusal, with redo's budget trimming. */
    fun redoAndPublish(expectedStep: Step<T>, publish: () -> Unit): Boolean {
        if (peekRedo() !== expectedStep) return false
        val nextUndo = copyWithCapacity(undoStack, 1)
        nextUndo.addLast(expectedStep)
        val nextRedo = copyWithCapacity(redoStack)
        nextRedo.removeLast()
        val dropped = prepareTrim(nextUndo, nextRedo)
        publish()
        undoStack = nextUndo
        redoStack = nextRedo
        dropped.forEach(::releaseBefore)
        return true
    }

    private fun copyWithCapacity(source: Collection<Step<T>>, extra: Int = 0): ArrayDeque<Step<T>> =
        ArrayDeque<Step<T>>(source.size + extra).also { it.addAll(source) }

    /** Only the prepared deque changes here; no owned resources are released during preflight. */
    private fun prepareTrim(undo: ArrayDeque<Step<T>>, redo: Collection<Step<T>>): List<Step<T>> {
        val dropped = ArrayList<Step<T>>()
        while (undo.size > 1 && heldBytes(undo, redo) > budgetBytes) dropped.add(undo.removeFirst())
        return dropped
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
        val slabs = LinkedHashMap<SlabKey, SlabChange<T>>()
        for (s in steps) for (c in s.slabs) {
            val k = SlabKey(c.layerId, c.celId, c.key, c.seq)
            val prev = slabs[k]
            if (prev == null) {
                slabs[k] = c
            } else {
                // The intermediate slab is owned only by this newer step's before.
                c.before?.texture?.let(release)
                slabs[k] = SlabChange(c.layerId, c.celId, c.key, c.seq, prev.before, c.after)
            }
        }
        // A layer change inside the batch (JB-2.04) is kept: the merged step goes from the first stack to the last.
        val stackBefore = steps.firstOrNull { it.stackBefore != null }?.stackBefore
        val stackAfter = steps.lastOrNull { it.stackAfter != null }?.stackAfter
        // Lines fold the same way as tiles: per line, the oldest `before` and the newest `after`. A line made and removed
        // inside the batch was never there for the person, so it leaves no change at all.
        val lines = LinkedHashMap<Triple<String, String?, String>, LineChange>()
        for (s in steps) for (c in s.lines) {
            val k = Triple(c.layerId, c.celId, c.id)
            val prev = lines[k]
            lines[k] = if (prev == null) c else LineChange(c.layerId, c.celId, c.id, prev.before, c.after)
        }
        undoStack.addLast(Step(merged.values.toList(), stackBefore, stackAfter,
            steps.firstOrNull { it.paperBefore != null }?.paperBefore,
            steps.lastOrNull { it.paperAfter != null }?.paperAfter,
            steps.firstOrNull { it.documentBefore != null }?.documentBefore,
            steps.lastOrNull { it.documentAfter != null }?.documentAfter,
            lines.values.filter { it.before != null || it.after != null },
            slabs.values.filter { it.before != null || it.after != null }))
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
            top.documentBefore, top.documentAfter, top.lines, top.slabs)
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
            top.documentBefore == null && top.documentAfter == null && top.lines.isEmpty() && top.slabs.isEmpty()
        // A step left with nothing in it (all its water came and dried inside it) is popped: an Undo that does nothing
        // visible would break one press, one step. The caller tells the history buttons ([canUndo] may now be false).
        if (changes.isEmpty() && bare) undoStack.removeLast()
        else undoStack[undoStack.lastIndex] = Step(changes, top.stackBefore, top.stackAfter, top.paperBefore, top.paperAfter,
            top.documentBefore, top.documentAfter, top.lines, top.slabs)
        return true
    }

    /** The step [extendNewest] would extend, or null when there is anything to redo or nothing to undo. */
    fun newestExtendable(): Step<T>? = if (redoStack.isNotEmpty()) null else undoStack.lastOrNull()

    /** Releases everything held for undo/redo (document closed). */
    fun clear() {
        undoStack.forEach(::releaseBefore)
        redoStack.forEach(::releaseAfter)
        undoStack.clear(); redoStack.clear()
    }

    private fun trim() {
        while (undoStack.size > 1 && heldBytes > budgetBytes) {
            releaseBefore(undoStack.removeFirst())
        }
    }

    private fun releaseBefore(step: Step<T>) {
        step.changes.forEach { c -> c.before?.let(release) }
        step.slabs.forEach { c -> c.before?.texture?.let(release) }
    }

    private fun releaseAfter(step: Step<T>) {
        step.changes.forEach { c -> c.after?.let(release) }
        step.slabs.forEach { c -> c.after?.texture?.let(release) }
    }
}

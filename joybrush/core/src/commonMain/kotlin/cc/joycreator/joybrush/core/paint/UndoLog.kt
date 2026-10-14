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

    /**
     * Immutable stack backing. Existing Step identities and their values are retained; callers
     * must treat Steps as readonly, as with peekUndo/peekRedo. A cursor borrows history handles:
     * retaining an old cursor never extends their lifetime after publication/retirement.
     */
    class Cursor<T : Any> internal constructor(
        private val owner: Any,
        undo: Collection<Step<T>>,
        redo: Collection<Step<T>>,
    ) {
        private val undo = undo.toList()
        private val redo = redo.toList()
        val canUndo: Boolean get() = undo.isNotEmpty()
        val canRedo: Boolean get() = redo.isNotEmpty()
        val undoDepth: Int get() = undo.size
        /** Detached list backing, preserving the actual immutable Step identities. */
        val undoSteps: List<Step<T>> get() = undo.toList()
        val redoSteps: List<Step<T>> get() = redo.toList()
        fun peekUndo(): Step<T>? = undo.lastOrNull()
        fun peekRedo(): Step<T>? = redo.lastOrNull()
        fun heldTextures(): Set<T> = buildSet {
            undo.forEach { step ->
                step.changes.forEach { it.before?.let(::add) }
                step.slabs.forEach { it.before?.texture?.let(::add) }
            }
            redo.forEach { step ->
                step.changes.forEach { it.after?.let(::add) }
                step.slabs.forEach { it.after?.texture?.let(::add) }
            }
        }
        internal fun belongsTo(identity: Any): Boolean = owner === identity
        internal fun copyUndo(extra: Int = 0): ArrayDeque<Step<T>> =
            ArrayDeque<Step<T>>(undo.size + extra).also { it.addAll(undo) }
        internal fun copyRedo(extra: Int = 0): ArrayDeque<Step<T>> =
            ArrayDeque<Step<T>>(redo.size + extra).also { it.addAll(redo) }
    }

    enum class Direction { NEW, UNDO, REDO }

    /** The actual immutable joint root, shared with other bound facades; lookup is readonly. */
    interface CursorPublication<T : Any> {
        val cursor: Cursor<T>
        val graphicsEpoch: Long
    }

    /**
     * Pure prepared values, NOT a resource-owning lease or a separate commit operation. The engine
     * publishes [cursor] inside its one artwork root only while [expectedCursor] is still current.
     * After successful root publication the engine owns applying [retirements], checking graphics
     * epoch before EACH release. Discarding a proposal releases nothing: these are borrowed handles.
     */
    class PreparedCursor<T : Any> internal constructor(
        val expectedCursor: Cursor<T>,
        val cursor: Cursor<T>,
        val direction: Direction,
        val step: Step<T>,
        retirements: List<T>,
    ) {
        private val retired = retirements.toList()
        /** Ordered exactly as unbound history discard/trim would release its owned endpoints. */
        val retirements: List<T> get() = retired.toList()
    }

    private val cursorIdentity = Any()
    private var localCursor = Cursor<T>(cursorIdentity, emptyList(), emptyList())
    private var boundRead: (() -> Cursor<T>)? = null
    private var bindingVersion = 0L
    private var binding = false
    private var publishing = false
    private var resetPreparing = false

    val isCursorBound: Boolean get() = boundRead != null
    val canUndo: Boolean get() = snapshotCursor().canUndo
    val canRedo: Boolean get() = snapshotCursor().canRedo
    val undoDepth: Int get() = snapshotCursor().undoDepth
    val heldBytes: Long get() {
        val cursor = snapshotCursor()
        return heldBytes(cursor.undoSteps, cursor.redoSteps)
    }
    fun heldTextures(): Set<T> = snapshotCursor().heldTextures()
    fun peekUndo(): Step<T>? = snapshotCursor().peekUndo()
    fun peekRedo(): Step<T>? = snapshotCursor().peekRedo()

    /** One read from a bound accessor, which MUST be a readonly lookup of the current engine root. */
    fun snapshotCursor(): Cursor<T> {
        val cursor = boundRead?.invoke() ?: localCursor
        require(cursor.belongsTo(cursorIdentity)) { "A history cursor belongs to a different source log" }
        return cursor
    }

    /** For an externally staged empty/reset root. Does not drop or release the current history. */
    fun emptyCursor(): Cursor<T> = Cursor(cursorIdentity, emptyList(), emptyList())

    /**
     * Attach once to an engine-owned publication root containing this exact local cursor. No
     * cursor is separately published here. The callback is external: check local identity again
     * after it returns, even if it reentered and mutated the unbound history. After binding ALL
     * unconverted mutators refuse before callbacks, work or resource release.
     */
    fun bindCursor(read: () -> Cursor<T>) {
        checkCursorBindingAllowed()
        val expected = localCursor
        binding = true
        try {
            val supplied = read()
            require(supplied.belongsTo(cursorIdentity)) { "A history cursor belongs to a different source log" }
            check(localCursor === expected && supplied === expected) { "History changed during cursor binding" }
            boundRead = read
            bindingVersion++
        } finally {
            binding = false
        }
    }

    /** Readonly preflight for an engine binding several facades before installing its root. */
    fun checkCursorBindingAllowed() {
        checkWriteAllowed()
        check(!binding && !resetPreparing) { "History is already preparing a cursor binding or reset" }
    }

    /**
     * Prepared reset values borrow old ownership. The engine validates its other slices, publishes
     * the EXACT resetPublication once, then acknowledges this handoff. No arbitrary unbind exists.
     * Caller owns retiring/abandoning retiredHistoryTextures after accepted joint publication.
     */
    inner class ResetHandoff internal constructor(
        val expectedPublication: CursorPublication<T>,
        val resetPublication: CursorPublication<T>,
        val expectedCursor: Cursor<T>,
        val resetCursor: Cursor<T>,
        val expectedEpoch: Long,
        val resetEpoch: Long,
        private val expectedReader: () -> Cursor<T>,
        private val expectedBinding: Long,
        private val readPublication: () -> CursorPublication<T>,
        retirements: List<T>,
    ) {
        private val retired = retirements.toList()
        private var acknowledged = false
        /** Same chronological undo-before then redo-after ownership order as legacy clear(). */
        val retiredHistoryTextures: List<T> get() = retired.toList()

        /** External readers can reenter: recheck root and local binding identities afterwards. */
        fun isCurrent(): Boolean {
            if (acknowledged || binding || publishing || resetPreparing || boundRead !== expectedReader ||
                bindingVersion != expectedBinding) return false
            if (readPublication() !== expectedPublication || snapshotCursor() !== expectedCursor) return false
            return readPublication() === expectedPublication && !binding && !publishing && !resetPreparing &&
                boundRead === expectedReader && bindingVersion == expectedBinding
        }

        /**
         * CALL ONLY AFTER the engine installed resetPublication, after final isCurrent checks.
         * The object argument must be that exact prevalidated root. No external lookup, allocation,
         * publication or release occurs here. Correct caller sequencing makes all guards succeed.
         */
        fun acknowledgeAfterResetPublication(published: CursorPublication<T>) {
            check(!acknowledged && published === resetPublication && !binding && !publishing && !resetPreparing &&
                boundRead === expectedReader && bindingVersion == expectedBinding) { "Stale or incorrect history reset acknowledgement" }
            localCursor = resetCursor
            boundRead = null
            bindingVersion++
            acknowledged = true
        }
    }

    /** Pure preflight; validates owners/emptiness before provider callbacks and releases nothing. */
    fun prepareResetHandoff(expectedPublication: CursorPublication<T>, resetPublication: CursorPublication<T>,
                            readPublication: () -> CursorPublication<T>): ResetHandoff {
        check(!binding && !publishing && !resetPreparing) { "History is already binding, publishing or preparing a reset" }
        val reader = boundRead ?: error("History reset handoff requires bound storage")
        val token = bindingVersion
        val expected = expectedPublication.cursor
        val empty = resetPublication.cursor
        requireOwned(expected)
        requireOwned(empty)
        require(!empty.canUndo && !empty.canRedo) { "Reset history must be truly empty" }
        require(empty !== expected && resetPublication !== expectedPublication &&
            resetPublication.graphicsEpoch > expectedPublication.graphicsEpoch) { "Reset requires a new empty cursor, root and advancing epoch" }
        val oldEpoch = expectedPublication.graphicsEpoch
        val newEpoch = resetPublication.graphicsEpoch
        resetPreparing = true
        try {
            check(readPublication() === expectedPublication && snapshotCursor() === expected &&
                readPublication() === expectedPublication && boundRead === reader && bindingVersion == token &&
                !binding && !publishing) { "History or joint root changed during reset preflight" }
            val retired = expected.undoSteps.flatMap(::beforeHandles) + expected.redoSteps.flatMap(::afterHandles)
            return ResetHandoff(expectedPublication, resetPublication, expected, empty, oldEpoch, newEpoch,
                reader, token, readPublication, retired)
        } finally {
            resetPreparing = false
        }
    }

    /** Checks history identity only. The engine must also check its complete artwork root/epoch. */
    fun isCurrent(prepared: PreparedCursor<T>): Boolean =
        prepared.expectedCursor.belongsTo(cursorIdentity) && prepared.cursor.belongsTo(cursorIdentity) &&
            snapshotCursor() === prepared.expectedCursor

    /** No state changes, publication callbacks or releases; budget callbacks run during preparation. */
    fun prepareNew(step: Step<T>, expectedCursor: Cursor<T> = snapshotCursor()): PreparedCursor<T> {
        requireOwned(expectedCursor)
        val nextUndo = expectedCursor.copyUndo(1).also { it.addLast(step) }
        val nextRedo = ArrayDeque<Step<T>>()
        val dropped = prepareTrim(nextUndo, nextRedo)
        val retired = ArrayList<T>()
        expectedCursor.redoSteps.asReversed().forEach { retired.addAll(afterHandles(it)) }
        dropped.forEach { retired.addAll(beforeHandles(it)) }
        return PreparedCursor(expectedCursor, Cursor(cursorIdentity, nextUndo, nextRedo), Direction.NEW, step, retired)
    }

    fun prepareUndo(
        expectedCursor: Cursor<T> = snapshotCursor(),
        expectedStep: Step<T>? = expectedCursor.peekUndo(),
    ): PreparedCursor<T>? {
        requireOwned(expectedCursor)
        if (expectedStep == null || expectedCursor.peekUndo() !== expectedStep) return null
        val nextUndo = expectedCursor.copyUndo().also { it.removeLast() }
        val nextRedo = expectedCursor.copyRedo(1).also { it.addLast(expectedStep) }
        return PreparedCursor(expectedCursor, Cursor(cursorIdentity, nextUndo, nextRedo), Direction.UNDO, expectedStep, emptyList())
    }

    fun prepareRedo(
        expectedCursor: Cursor<T> = snapshotCursor(),
        expectedStep: Step<T>? = expectedCursor.peekRedo(),
    ): PreparedCursor<T>? {
        requireOwned(expectedCursor)
        if (expectedStep == null || expectedCursor.peekRedo() !== expectedStep) return null
        val nextUndo = expectedCursor.copyUndo(1).also { it.addLast(expectedStep) }
        val nextRedo = expectedCursor.copyRedo().also { it.removeLast() }
        val dropped = prepareTrim(nextUndo, nextRedo)
        return PreparedCursor(expectedCursor, Cursor(cursorIdentity, nextUndo, nextRedo), Direction.REDO, expectedStep,
            dropped.flatMap(::beforeHandles))
    }

    private fun requireOwned(cursor: Cursor<T>) {
        require(cursor.belongsTo(cursorIdentity)) { "A history cursor belongs to a different source log" }
    }
    private fun checkWriteAllowed() {
        check(boundRead == null) { "Bound history mutates only through its joint artwork publisher" }
        check(!publishing) { "History publication or retirement cannot reenter a mutator" }
    }
    private fun heldBytes(undo: Collection<Step<T>>, redo: Collection<Step<T>>): Long =
        undo.sumOf { s -> s.changes.sumOf { c -> c.before?.let(sizeOf) ?: 0L } +
            s.slabs.sumOf { c -> c.before?.texture?.let(sizeOf) ?: 0L } + s.lines.sumOf { it.bytes } } +
        redo.sumOf { s -> s.changes.sumOf { c -> c.after?.let(sizeOf) ?: 0L } +
            s.slabs.sumOf { c -> c.after?.texture?.let(sizeOf) ?: 0L } + s.lines.sumOf { it.bytes } }
    private fun beforeHandles(step: Step<T>): List<T> =
        step.changes.mapNotNull { it.before } + step.slabs.mapNotNull { it.before?.texture }
    private fun afterHandles(step: Step<T>): List<T> =
        step.changes.mapNotNull { it.after } + step.slabs.mapNotNull { it.after?.texture }
    private fun prepareTrim(undo: ArrayDeque<Step<T>>, redo: Collection<Step<T>>): List<Step<T>> {
        val dropped = ArrayList<Step<T>>()
        while (undo.size > 1 && heldBytes(undo, redo) > budgetBytes) dropped.add(undo.removeFirst())
        return dropped
    }

    /** Publish one prebuilt LOCAL cursor; this path is forbidden while externally bound. */
    private fun publishLocal(expected: Cursor<T>, next: Cursor<T>, retired: List<T>, publish: () -> Unit = {}) {
        checkWriteAllowed()
        check(localCursor === expected) { "The history cursor changed during preparation" }
        publishing = true
        try {
            publish() // The caller may only assign its prepared scene reference, with no reentry.
            localCursor = next
            retired.forEach(release)
        } finally {
            publishing = false
        }
    }

    fun push(step: Step<T>) { checkWriteAllowed(); pushAndPublish(step) {} }
    fun pushAndPublish(step: Step<T>, publish: () -> Unit) {
        checkWriteAllowed()
        val prepared = prepareNew(step)
        publishLocal(prepared.expectedCursor, prepared.cursor, prepared.retirements, publish)
    }
    fun undo(): Step<T>? {
        checkWriteAllowed()
        val step = peekUndo() ?: return null
        return if (undoAndPublish(step) {}) step else null
    }
    fun undoAndPublish(expectedStep: Step<T>, publish: () -> Unit): Boolean {
        checkWriteAllowed()
        val prepared = prepareUndo(expectedStep = expectedStep) ?: return false
        if (!isCurrent(prepared)) return false
        publishLocal(prepared.expectedCursor, prepared.cursor, prepared.retirements, publish)
        return true
    }
    fun redo(): Step<T>? {
        checkWriteAllowed()
        val step = peekRedo() ?: return null
        return if (redoAndPublish(step) {}) step else null
    }
    fun redoAndPublish(expectedStep: Step<T>, publish: () -> Unit): Boolean {
        checkWriteAllowed()
        val prepared = prepareRedo(expectedStep = expectedStep) ?: return false
        if (!isCurrent(prepared)) return false
        publishLocal(prepared.expectedCursor, prepared.cursor, prepared.retirements, publish)
        return true
    }

    /** Preserve oldest before / newest after and retire intermediate endpoints only after publication. */
    fun mergeNewest(count: Int) {
        checkWriteAllowed()
        val before = localCursor
        val nextUndo = before.copyUndo()
        val nextRedo = before.copyRedo()
        val n = minOf(count, nextUndo.size)
        if (n < 2) return
        val steps = ArrayList<Step<T>>(n)
        repeat(n) { steps.add(0, nextUndo.removeLast()) }
        val retired = ArrayList<T>()
        val merged = LinkedHashMap<Triple<String, String?, Long>, TileChange<T>>()
        for (s in steps) for (c in s.changes) {
            val key = Triple(c.layerId, c.celId, c.key)
            val prev = merged[key]
            if (prev == null) merged[key] = c
            else {
                c.before?.let { retired.add(it) }
                merged[key] = TileChange(c.layerId, c.key, prev.before, c.after, c.celId)
            }
        }
        val slabs = LinkedHashMap<SlabKey, SlabChange<T>>()
        for (s in steps) for (c in s.slabs) {
            val key = SlabKey(c.layerId, c.celId, c.key, c.seq)
            val prev = slabs[key]
            if (prev == null) slabs[key] = c
            else {
                c.before?.texture?.let { retired.add(it) }
                slabs[key] = SlabChange(c.layerId, c.celId, c.key, c.seq, prev.before, c.after)
            }
        }
        val lines = LinkedHashMap<Triple<String, String?, String>, LineChange>()
        for (s in steps) for (c in s.lines) {
            val key = Triple(c.layerId, c.celId, c.id)
            val prev = lines[key]
            lines[key] = if (prev == null) c else LineChange(c.layerId, c.celId, c.id, prev.before, c.after)
        }
        nextUndo.addLast(Step(merged.values.toList(),
            steps.firstOrNull { it.stackBefore != null }?.stackBefore,
            steps.lastOrNull { it.stackAfter != null }?.stackAfter,
            steps.firstOrNull { it.paperBefore != null }?.paperBefore,
            steps.lastOrNull { it.paperAfter != null }?.paperAfter,
            steps.firstOrNull { it.documentBefore != null }?.documentBefore,
            steps.lastOrNull { it.documentAfter != null }?.documentAfter,
            lines.values.filter { it.before != null || it.after != null },
            slabs.values.filter { it.before != null || it.after != null }))
        prepareTrim(nextUndo, nextRedo).forEach { retired.addAll(beforeHandles(it)) }
        publishLocal(before, Cursor(cursorIdentity, nextUndo, nextRedo), retired)
    }

    /** Running-water extension keeps the same public rules; all backing is replaced privately. */
    fun extendNewest(changes: List<TileChange<T>>, accepts: (Step<T>) -> Boolean): Boolean {
        checkWriteAllowed()
        val before = localCursor
        if (before.canRedo) return false
        val top = before.peekUndo() ?: return false
        if (!accepts(top) || localCursor !== before) return false
        val held = top.changes.mapTo(HashSet()) { Triple(it.layerId, it.celId, it.key) }
        require(changes.none { Triple(it.layerId, it.celId, it.key) in held }) { "a tile already in the newest step was snapshotted again" }
        val nextUndo = before.copyUndo()
        val nextRedo = before.copyRedo()
        nextUndo[nextUndo.lastIndex] = Step(top.changes + changes, top.stackBefore, top.stackAfter, top.paperBefore, top.paperAfter,
            top.documentBefore, top.documentAfter, top.lines, top.slabs)
        val retired = prepareTrim(nextUndo, nextRedo).flatMap(::beforeHandles)
        publishLocal(before, Cursor(cursorIdentity, nextUndo, nextRedo), retired)
        return true
    }

    /** Caller still releases replaced afters; this operation adds no new retirement responsibility. */
    fun replaceInNewest(replacements: List<TileChange<T>>): Boolean {
        checkWriteAllowed()
        val before = localCursor
        if (before.canRedo || replacements.isEmpty()) return false
        val top = before.peekUndo() ?: return false
        val byTile = replacements.associateBy { Triple(it.layerId, it.celId, it.key) }
        val held = top.changes.mapTo(HashSet()) { Triple(it.layerId, it.celId, it.key) }
        if (!held.containsAll(byTile.keys)) return false
        val changes = top.changes.mapNotNull { c ->
            val replacement = byTile[Triple(c.layerId, c.celId, c.key)] ?: return@mapNotNull c
            if (replacement.before == null && replacement.after == null) null else replacement
        }
        val bare = top.stackBefore == null && top.stackAfter == null && top.paperBefore == null && top.paperAfter == null &&
            top.documentBefore == null && top.documentAfter == null && top.lines.isEmpty() && top.slabs.isEmpty()
        val nextUndo = before.copyUndo()
        if (changes.isEmpty() && bare) nextUndo.removeLast()
        else nextUndo[nextUndo.lastIndex] = Step(changes, top.stackBefore, top.stackAfter, top.paperBefore, top.paperAfter,
            top.documentBefore, top.documentAfter, top.lines, top.slabs)
        publishLocal(before, Cursor(cursorIdentity, nextUndo, before.copyRedo()), emptyList())
        return true
    }
    fun newestExtendable(): Step<T>? {
        val cursor = snapshotCursor()
        return if (cursor.canRedo) null else cursor.peekUndo()
    }
    fun clear() {
        checkWriteAllowed()
        val before = localCursor
        val retired = before.undoSteps.flatMap(::beforeHandles) + before.redoSteps.flatMap(::afterHandles)
        publishLocal(before, emptyCursor(), retired)
    }
}

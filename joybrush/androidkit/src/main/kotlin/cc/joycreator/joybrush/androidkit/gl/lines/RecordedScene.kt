package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.core.brush.BrushJson
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.FrozenBrushRegistry
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.media.MediaStores
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.stroke.CapturedStrokeRules
import cc.joycreator.joybrush.core.doc.SlabOps
import cc.joycreator.joybrush.core.vector.RecordedCel
import cc.joycreator.joybrush.core.vector.RecordedEdits
import cc.joycreator.joybrush.core.vector.RecordedTilePlan
import cc.joycreator.joybrush.core.vector.CelComposer

/**
 * GL-thread coordinator for physical recorded cels and their derived caches. Slab textures are
 * borrowed from the engine/history; this object owns line units, composed looks and accepted
 * uploaded saved looks. A cold saved cel retains declared looks without pretending they identify
 * individual lines; its first edit measures and validates the old complete cel privately.
 * Rendering is staged before history moves. Unbound publication assigns one prepared scene
 * reference; bound staging exports values for the engine's one complete artwork root assignment.
 * This coordinator does not activate recording on a view or replace the legacy pixel path.
 */
internal class RecordedScene<T : Any> private constructor(
    private val history: UndoLog<T>,
    private val renderer: Renderer<T>,
) {
    class Unit<T : Any>(val op: String, tiles: Map<Long, T>) {
        val tiles: Map<Long, T> = tiles.toMap()
        init { require(SlabOps.known(op)) { "Unsupported recorded source operator" } }
    }

    class Source<T : Any>(val seq: Long, val texture: T, val op: String)

    /**
     * Each rendering callback stays in the epoch in which it was entered. If a reentrant
     * reset occurs inside a callback, do not mint replacement-context output for that call.
     * The renderer owns cleanup of unreturned partial work in its correct epoch; the scene
     * cannot safely delete a returned handle whose epoch became unknown during the call.
     */
    interface Renderer<T : Any> {
        /** Scene clipping/paper/playback context must still match the one its cache was built for. */
        fun contextCurrent(): Boolean = true
        /** Atomic result: fresh owned textures; on throw the renderer cleans its own partial work. */
        fun line(address: LineAddress, record: StrokeRecord, preset: BrushPreset): Unit<T>
        /**
         * Clone EVERY cached canonical source tile through normal copy and physical-cel clipping,
         * after full WC pooling. Its pixels already include brush opacity exactly once: do not
         * replay, apply opacity again, bake a composed look, or transfer any cached texture.
         * Return the same operator and exact tile keys, with NEW caller-owned textures. An empty
         * authoritative unit stays empty. On throw, clean all partial copies in this context.
         */
        fun copyForBake(address: LineAddress, unit: Unit<T>): Unit<T> =
            throw UnsupportedOperationException("This renderer cannot clone recorded sources for bake")
        /** Borrowed ordered sources. Apply the physical cel's clipping while composing. */
        fun look(plane: Pair<String, String?>, key: Long, sources: List<Source<T>>): T?
        /** Must not throw; release a valid owned cache or uncommitted bake source in this context. */
        fun release(texture: T)
    }

    /**
     * Explicit source-kind opt-in. Ordinary renderers remain incapable, including cold acceptance.
     * Admission is readonly and allocation-free, for this exact physical address, immutable raw
     * recipe/domain/placement and frozen brush. Returning true promises that [Renderer.line]
     * fully pools the original recipe BEFORE capture-domain clipping and exact integer placement,
     * returns fresh placed sources and their actual tile keys, and keeps opacity/operator once.
     * Neither admission nor line rendering may reinterpret today's boards as the historical domain.
     * Unsupported records must refuse the whole operation; an empty actual footprint is legitimate.
     */
    interface CapturedSourceRenderer<T : Any> : Renderer<T> {
        fun admitCapturedSource(address: LineAddress, record: StrokeRecord, preset: BrushPreset): Boolean
    }

    internal class Cached<T : Any>(val record: StrokeRecord, val unit: Unit<T>)
    /** Readonly borrowed slice. Retaining a retired State does not retain cache ownership/lifetime. */
    internal class State<T : Any>(
        private val owner: Any,
        val planes: Map<Pair<String, String?>, RecordedCel<T>>,
        val brushes: Map<String, String>,
        val units: Map<LineAddress, Cached<T>>,
        val plans: Map<Pair<String, String?>, RecordedTilePlan<T>>,
        val looks: Map<Pair<String, String?>, Map<Long, T>>,
        // Loaded declarations are independent of units. Absence of a plan means unresolved
        // per-line footprints, not that every line has an authoritative empty footprint.
        val savedKeys: Map<Pair<String, String?>, Set<Long>>,
    ) {
        internal fun belongsTo(identity: Any): Boolean = owner === identity
        fun cacheTextures(): Set<T> = (units.values.flatMap { it.unit.tiles.values } +
            looks.values.flatMap { it.values }).toSet()
    }

    /** The actual immutable engine root implements this interface; accessor reads allocate nothing. */
    internal interface Publication<T : Any> : UndoLog.CursorPublication<T> {
        val recorded: State<T>
        /** Borrowed sibling/native/global owners, including masks/media/display caches. */
        val protectedTextures: Set<T>
    }

    /** Actual engine root metadata installed only by its accepted native-base transfer ticket. */
    internal interface PromotedPublication<T : Any> : Publication<T> {
        val promotedBases: Set<Pair<String, String?>>
        val legacyBaseSteps: Set<UndoLog.Step<T>>
        /** Proven native shared-cel aliases; recorded addresses remain null for shared. */
        val promotedSharedCels: Map<String, String> get() = emptyMap()
        /** Null for ordinary/reset roots; not a caller-supplied alias permission flag. */
        val baseTransferIdentity: Any?
    }

    private val sceneIdentity = Any()
    private var localState = State<T>(sceneIdentity, emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())
    private var localGeneration = 0L
    private var boundRead: (() -> Publication<T>)? = null
    private var bindingVersion = 0L
    private var binding = false
    private var openLocalLeases = 0
    private var localPreparations = 0
    private var jointPreparations = 0
    private val jointLeases = LinkedHashSet<JointStaged>()
    private var jointOwnershipRevision = 0L
    private var resetPreparing = false
    private var admittingCapturedSource = false
    private val state: State<T> get() = snapshotState()
    private val generation: Long get() = boundRead?.invoke()?.graphicsEpoch ?: localGeneration
    val isStateBound: Boolean get() = boundRead != null

    /** Borrowed state value for initial root construction, or the current bound root's scene slice. */
    fun snapshotState(): State<T> {
        val current = boundRead?.invoke()?.recorded ?: localState
        require(current.belongsTo(sceneIdentity)) { "Recorded state belongs to a different scene" }
        return current
    }

    /** Pure same-scene reset value for a future joint lifecycle adapter; releases nothing. */
    fun emptyState(): State<T> = State(sceneIdentity, emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())

    /**
     * Attach once without publishing anything. Root lookup must be readonly, must return the
     * actual same root instance on each read, and must not recursively call scene/history getters.
     * Bind history to that root's cursor too before stageJoint. No outstanding local lease may cross
     * this storage transition. The external callback may reenter before binding; recheck afterwards.
     */
    fun bindState(read: () -> Publication<T>) {
        checkStateBindingAllowed()
        val before = localState
        val epoch = localGeneration
        val cursor = history.snapshotCursor()
        binding = true
        try {
            val supplied = read()
            require(supplied.recorded.belongsTo(sceneIdentity)) { "Recorded state belongs to a different scene" }
            check(localState === before && localGeneration == epoch && openLocalLeases == 0 && localPreparations == 0 &&
                supplied.recorded === before && supplied.cursor === cursor && history.snapshotCursor() === cursor &&
                read() === supplied && history.snapshotCursor() === cursor && localState === before &&
                localGeneration == epoch && openLocalLeases == 0 && localPreparations == 0) {
                "Recorded scene or history changed during binding"
            }
            boundRead = read
            bindingVersion++
        } finally {
            binding = false
        }
    }

    /** Readonly engine preflight; no provider callback runs before local leases are excluded. */
    fun checkStateBindingAllowed() {
        checkUnbound()
        check(!binding && !resetPreparing && !admittingCapturedSource && openLocalLeases == 0 && localPreparations == 0 &&
            jointPreparations == 0 && jointLeases.isEmpty()) {
            "Cannot bind while local scene preparation or a proposal is outstanding"
        }
    }

    /** Validate replay context while still local; a provider cannot sneak a mutation into binding. */
    fun checkBindingContext() {
        checkStateBindingAllowed()
        val before = localState
        val epoch = localGeneration
        val cursor = history.snapshotCursor()
        binding = true
        try {
            check(renderer.contextCurrent()) { "Recorded rendering context changed before engine binding" }
            check(boundRead == null && openLocalLeases == 0 && localPreparations == 0 &&
                localState === before && localGeneration == epoch && history.snapshotCursor() === cursor) {
                "Recorded state or history changed while validating engine binding"
            }
        } finally {
            binding = false
        }
    }

    private fun checkUnbound() {
        check(boundRead == null) { "Bound recorded state mutates only through its joint artwork publisher" }
    }

    private inline fun <R> prepareLocal(block: () -> R): R {
        check(!admittingCapturedSource) { "Captured source admission cannot reenter scene preparation" }
        checkUnbound()
        localPreparations++
        try { return block() } finally { localPreparations-- }
    }

    private fun samePublication(expected: Publication<T>): Boolean {
        val read = boundRead ?: return false
        val current = read()
        if (current !== expected || !current.recorded.belongsTo(sceneIdentity)) return false
        if (history.snapshotCursor() !== expected.cursor) return false
        // The history accessor is also external. Catch a root switch while it answered.
        return read() === expected
    }

    /** Source-root snapshot admission. Never replay, publish, or certify an unresolved cold index. */
    internal fun checkSnapshotPublication(expected: Publication<T>) {
        fun quiescent() = boundRead != null && history.isCursorBound && !binding && !resetPreparing &&
            openLocalLeases == 0 && localPreparations == 0 && jointPreparations == 0 && jointLeases.isEmpty()
        check(quiescent() && samePublication(expected)) { "Finish the current recorded transaction before saving" }
        require(expected.recorded.planes.all { (plane, cel) -> cel.records.isEmpty() || plane in expected.recorded.plans }) {
            "This cold saved drawing needs its actual source footprints verified before a new save"
        }
        check(renderer.contextCurrent()) { "Recorded source graphics are unavailable for saving" }
        check(quiescent() && samePublication(expected)) { "Recorded artwork changed during snapshot admission" }
    }

    /** Cache release never proceeds to another old name after a reset during one release. */
    private fun releaseInEpoch(textures: Collection<T>, epoch: Long) {
        for (texture in textures) {
            if (generation != epoch) break
            renderer.release(texture)
        }
    }

    /** Borrowed read-only cel identities used by RecordedEdits; mutation stays on the GL thread. */
    val planes: Map<Pair<String, String?>, RecordedCel<T>> get() = state.planes
    val brushes: Map<String, BrushPreset> get() = state.brushes.mapValues { BrushJson.decodeChecked(it.value) }
    fun lookKeys(plane: Pair<String, String?>): Set<Long> = state.looks[plane]?.keys?.toSet().orEmpty()
    /**
     * Archive indexes list line-crossed tiles only; plain slab tiles stay in the pixel payload.
     * On cold planes this is the retained declaration, not yet verified against actual units.
     */
    fun savedLookKeys(plane: Pair<String, String?>): Set<Long> = state.savedKeys[plane]?.toSet().orEmpty()
    /** True only after this physical cel's complete actual per-line footprints were measured. */
    fun isMaterialized(plane: Pair<String, String?>): Boolean = plane in state.plans
    fun look(plane: Pair<String, String?>, key: Long): T? = state.looks[plane]?.get(key)
    val hasContent: Boolean get() = state.planes.values.any { it.records.isNotEmpty() || it.slabs.isNotEmpty() }
    fun heldCacheTextures(): Set<T> = state.cacheTextures()

    enum class Direction { NEW, UNDO, REDO }

    enum class ResetMode {
        /** Same physical context: all joint preparation/lease/retirement must already be quiescent. */
        SAME_CONTEXT,
        /** Actual dead-context transition: old joint names and in-flight outputs are abandoned. */
        CONTEXT_LOST,
    }

    /**
     * Pure preflight for one exact empty engine root and both facade handoffs. Current cache and
     * history ownership is borrowed until publication, then caller-owned for retirement/abandonment.
     * Physical slabs/native sources stay outside these ledgers and belong to the engine plan.
     */
    inner class ResetHandoff internal constructor(
        val expectedPublication: Publication<T>,
        val resetPublication: Publication<T>,
        val mode: ResetMode,
        private val historyHandoff: UndoLog<T>.ResetHandoff,
        private val empty: State<T>,
        val resetEpoch: Long,
        private val expectedReader: () -> Publication<T>,
        private val expectedBinding: Long,
        private val ownershipRevision: Long,
        private val preparationCount: Int,
        private val leases: List<JointStaged>,
        caches: List<T>,
        pending: Set<T>,
    ) {
        private val caches = caches.toList()
        private val pending = pending.toSet()
        private var acknowledged = false
        private var cacheRetirementFinished = false
        /** ONLY current derived cache ownership; no source/history endpoints. */
        val retiredCacheTextures: List<T> get() = caches.toList()
        val retiredHistoryTextures: List<T> get() = historyHandoff.retiredHistoryTextures
        /** Dead-context-only outstanding cache ownership; never execute as a live retirement ledger. */
        val abandonedJointCacheTextures: Set<T> get() = pending.toSet()

        /**
         * Caller supplies a PURE physical-context-token check, not the advanced logical scene
         * epoch. Cache ownership uses this scene's renderer even when the engine owns the other
         * ledgers. Dead-context mode invokes neither checker nor release. Stop once and abandon
         * remaining names if the physical context changes during one renderer release.
         */
        fun releaseRetiredCachesAfterPublication(contextAlive: () -> Boolean) {
            check(acknowledged) { "Cache retirement requires acknowledged empty-root publication" }
            if (cacheRetirementFinished) return
            cacheRetirementFinished = true
            if (mode == ResetMode.CONTEXT_LOST) return
            var i = 0
            while (i < caches.size) {
                if (!contextAlive()) return
                renderer.release(caches[i])
                i++
            }
        }

        /** Use only if the exported cache ledger was externally retired/abandoned exactly once. */
        fun acknowledgeRetiredCachesExternallyAfterPublication() {
            check(acknowledged && !cacheRetirementFinished) { "Reset cache retirement is already finished or was not acknowledged" }
            cacheRetirementFinished = true
        }

        fun isCurrent(): Boolean {
            if (acknowledged || resetPreparing || !resetLocallyAllowed(mode) || boundRead !== expectedReader ||
                bindingVersion != expectedBinding || jointOwnershipRevision != ownershipRevision ||
                jointPreparations != preparationCount) return false
            if (!samePublication(expectedPublication) || !historyHandoff.isCurrent()) return false
            return samePublication(expectedPublication) && !resetPreparing && resetLocallyAllowed(mode) &&
                boundRead === expectedReader && bindingVersion == expectedBinding &&
                jointOwnershipRevision == ownershipRevision && jointPreparations == preparationCount
        }

        /**
         * CALL ONLY AFTER one exact resetPublication assignment, after final isCurrent checks.
         * No root lookup, renderer, allocation, publication or release. The core ticket acknowledges
         * that same object, then every old context-loss lease is invalidated without deleting names.
         */
        fun acknowledgeAfterResetPublication(published: Publication<T>) {
            check(!acknowledged && published === resetPublication && !resetPreparing && resetLocallyAllowed(mode) &&
                boundRead === expectedReader && bindingVersion == expectedBinding &&
                jointOwnershipRevision == ownershipRevision && jointPreparations == preparationCount) {
                "Stale or incorrect recorded reset acknowledgement"
            }
            historyHandoff.acknowledgeAfterResetPublication(published)
            localState = empty
            localGeneration = resetEpoch
            boundRead = null
            bindingVersion++
            var i = 0
            while (i < leases.size) {
                leases[i].invalidateAfterContextReset()
                i++
            }
            jointLeases.clear()
            jointOwnershipRevision++
            acknowledged = true
        }
    }

    private fun resetLocallyAllowed(mode: ResetMode): Boolean =
        !binding && openLocalLeases == 0 && localPreparations == 0 &&
            (mode == ResetMode.CONTEXT_LOST || (jointPreparations == 0 && jointLeases.isEmpty()))

    private fun trulyEmpty(state: State<T>): Boolean = state.planes.isEmpty() && state.brushes.isEmpty() &&
        state.units.isEmpty() && state.plans.isEmpty() && state.looks.isEmpty() && state.savedKeys.isEmpty()

    fun prepareResetHandoff(expectedPublication: Publication<T>, resetPublication: Publication<T>,
                            mode: ResetMode = ResetMode.SAME_CONTEXT): ResetHandoff {
        check(!resetPreparing && resetLocallyAllowed(mode)) { "Recorded reset requires quiescent local/joint work for this mode" }
        val reader = boundRead ?: error("Recorded reset handoff requires bound storage")
        val token = bindingVersion
        val before = expectedPublication.recorded
        val empty = resetPublication.recorded
        require(before.belongsTo(sceneIdentity) && empty.belongsTo(sceneIdentity)) { "Recorded reset state belongs to another scene" }
        require(trulyEmpty(empty) && empty !== before && resetPublication.protectedTextures.isEmpty()) {
            "Reset requires a new truly empty recorded state and persistent ownership set"
        }
        require(resetPublication !== expectedPublication && resetPublication.graphicsEpoch > expectedPublication.graphicsEpoch) {
            "Reset requires a new root and advancing epoch"
        }
        val revision = jointOwnershipRevision
        val preparing = jointPreparations
        resetPreparing = true
        try {
            // Core checks cursor source/emptiness before invoking any external root reader.
            val core = history.prepareResetHandoff(expectedPublication, resetPublication, reader)
            check(samePublication(expectedPublication) && resetLocallyAllowed(mode) && boundRead === reader &&
                bindingVersion == token && jointOwnershipRevision == revision && jointPreparations == preparing) {
                "Recorded ownership or root changed during reset preflight"
            }
            val leases = jointLeases.toList()
            val pending = leases.flatMap { it.pendingCacheOwnership() }.toSet()
            val caches = before.cacheTextures().toList()
            check(samePublication(expectedPublication) && core.isCurrent() && resetLocallyAllowed(mode) &&
                boundRead === reader && bindingVersion == token && jointOwnershipRevision == revision &&
                jointPreparations == preparing) { "Recorded reset ownership changed before ticket acceptance" }
            return ResetHandoff(expectedPublication, resetPublication, mode, core, empty,
                resetPublication.graphicsEpoch, reader, token, revision, preparing, leases,
                caches, pending)
        } finally {
            resetPreparing = false
        }
    }

    /**
     * Derived-cache lease for a complete engine transaction. It never publishes scene or history.
     * The full Step lives in historyProposal; only its recorded projection was rendered here.
     * Native tile/media/slab outputs remain the caller's responsibility until joint publication.
     */
    inner class JointStaged internal constructor(
        val expectedPublication: Publication<T>,
        val candidateState: State<T>,
        val historyProposal: UndoLog.PreparedCursor<T>,
        fresh: Set<T>,
        retired: Set<T>,
        val affected: Map<Pair<String, String?>, Set<Long>>,
    ) : AutoCloseable {
        private val fresh = fresh.toSet()
        private val retired = retired.toList()
        private val epoch = expectedPublication.graphicsEpoch
        private var finished = false
        private var adopted = false
        private var retiredReleased = false
        private var invalidated = false
        init {
            jointLeases.add(this)
            jointOwnershipRevision++
        }
        internal fun pendingCacheOwnership(): Collection<T> = if (adopted) retired else fresh
        internal fun invalidateAfterContextReset() {
            invalidated = true
            finished = true
            retiredReleased = true
        }
        private fun untrack() {
            if (jointLeases.remove(this)) jointOwnershipRevision++
        }
        /** All freshly minted caches, including transient warm units in the retirement ledger. */
        val freshCacheTextures: Set<T> get() = fresh.toSet()
        /** Old replaced caches plus transient warm units; no native/history-owned source handles. */
        val retiredCacheTextures: List<T> get() = retired.toList()

        /**
         * Check immediately before one engine root assignment. Context lookup is external, so
         * root/scene/cursor/epoch identities are checked again after it answers. The engine must
         * also verify any separately staged native plan's expected root and epoch.
         */
        fun canPublish(): Boolean {
            if (invalidated) return false
            check(!finished) { "This joint recorded proposal is already finished" }
            if (generation != epoch || !samePublication(expectedPublication) || !history.isCurrent(historyProposal)) return false
            if (!renderer.contextCurrent()) return false
            return generation == epoch && samePublication(expectedPublication) && history.isCurrent(historyProposal) &&
                samePublication(expectedPublication)
        }

        /**
         * CALL ONLY AFTER the engine assigned its complete prebuilt root containing candidateState
         * and historyProposal.cursor. This is ownership acknowledgement, not publication. It does
         * no external lookup, allocation, rendering or release. Correct sequencing cannot fail;
         * its finished guard detects publisher misuse. The publisher owns this invariant.
         */
        fun adoptAfterPublication() {
            check(!finished && !invalidated) { "This joint recorded proposal is already finished" }
            finished = true
            adopted = true
            jointOwnershipRevision++
        }

        /** Optional scene-cache ledger executor; native/history retirement remains engine-owned. */
        fun releaseRetiredAfterPublication() {
            if (invalidated) return
            check(adopted) { "Retirement requires successful joint publication and adoption" }
            if (retiredReleased) return
            retiredReleased = true
            jointOwnershipRevision++
            try { releaseInEpoch(retired, epoch) } finally { untrack() }
        }

        /** Use only if the engine executed the exported cache ledger itself, exactly once. */
        fun acknowledgeRetiredExternallyAfterPublication() {
            if (invalidated) return
            check(adopted && !retiredReleased) { "External cache retirement is already finished or was not adopted" }
            retiredReleased = true
            untrack()
        }

        override fun close() {
            if (finished) return
            finished = true
            jointOwnershipRevision++
            // Cancellation owns only newly rendered caches, never borrowed Step endpoints.
            try { releaseInEpoch(fresh, epoch) } finally { untrack() }
        }
    }

    /**
     * First bounded joint adapter: stable document/paper/stack/clip, native planes disjoint from
     * recorded planes. Reject unsupported structural metadata before root/context/budget/render
     * callbacks. The engine owns native target validation and staging. Bake/loader/lifecycle joint
     * adapters are intentionally not activated by this method.
     */
    fun stageJoint(step: UndoLog.Step<T>, direction: Direction = Direction.NEW,
                   frozen: Map<String, BrushPreset>? = null): JointStaged {
        require(step.stackBefore == null && step.stackAfter == null && step.paperBefore == null &&
            step.paperAfter == null && step.documentBefore == null && step.documentAfter == null) {
            "Joint recorded staging does not yet support structural, paper or document metadata"
        }
        require(step.changes.isNotEmpty() || step.lines.isNotEmpty() || step.slabs.isNotEmpty()) {
            "An empty edit cannot publish a joint undo step"
        }
        check(!resetPreparing && !admittingCapturedSource) { "Cannot stage recorded work during reset or source admission" }
        jointPreparations++
        try { return stageJointPrepared(step, direction, frozen) } finally { jointPreparations-- }
    }

    /**
     * Engine-only route: the bound reader must return this exact immutable promoted root. Project
     * proven old native TileChanges onto seq0, but prepare history from the COMPLETE original Step.
     * Native/disjoint target validation and the one artwork assignment remain engine-owned.
     */
    internal fun stageJointProjected(expected: PromotedPublication<T>, fullStep: UndoLog.Step<T>,
                                    direction: Direction = Direction.NEW,
                                    frozen: Map<String, BrushPreset>? = null): JointStaged {
        require(fullStep.stackBefore == null && fullStep.stackAfter == null && fullStep.paperBefore == null &&
            fullStep.paperAfter == null && fullStep.documentBefore == null && fullStep.documentAfter == null) {
            "Joint recorded staging does not yet support structural, paper or document metadata"
        }
        require(fullStep.changes.isNotEmpty() || fullStep.lines.isNotEmpty() || fullStep.slabs.isNotEmpty()) {
            "An empty edit cannot publish a joint undo step"
        }
        check(!resetPreparing && !admittingCapturedSource) { "Cannot stage recorded work during reset or source admission" }
        jointPreparations++
        try { return stageJointPrepared(fullStep, direction, frozen, expected) } finally { jointPreparations-- }
    }

    private fun stageJointPrepared(step: UndoLog.Step<T>, direction: Direction,
                                   frozen: Map<String, BrushPreset>?,
                                   expectedPromotion: PromotedPublication<T>? = null): JointStaged {
        if(direction == Direction.NEW) cc.joycreator.joybrush.core.stroke.CapturedStrokeRules.requireNewStep(step)
        requireSourceKinds(step)
        val read = boundRead ?: error("Joint recorded staging requires an engine publication binding")
        check(history.isCursorBound) { "Joint recorded staging requires history bound to the same root" }
        val publication = read()
        val before = publication.recorded
        requireSourceKinds(before.planes)
        require(before.belongsTo(sceneIdentity)) { "Recorded state belongs to a different scene" }
        val projectedEdit = if (expectedPromotion == null) {
            // The public route keeps its existing native-disjoint refusal and callback ordering.
            require(step.changes.none {
                val owner = MediaStores.parse(it.layerId)?.first ?: it.layerId
                (owner to it.celId) in before.planes
            }) { "Legacy tile edits on recorded planes need a physical-source adapter" }
            require(step.lines.all { (it.layerId to it.celId) in before.planes }) {
                "Every recorded line change needs a known physical cel"
            }
            null
        } else {
            check(publication === expectedPromotion) { "The expected promoted root is no longer current" }
            require(expectedPromotion.baseTransferIdentity != null && expectedPromotion.promotedBases.isNotEmpty()) {
                "A projected joint step needs the engine root's accepted native-base transfer"
            }
            // Never trust a caller's Prepared or replace the actual root with a wrapper. The pure
            // adapter verifies exact eligible Step identity, actual cursor endpoint and seq0 source.
            PromotedBaseProjection.prepare(before.planes, step, direction, publication.cursor,
                expectedPromotion.legacyBaseSteps, expectedPromotion.promotedBases,
                expectedPromotion.promotedSharedCels).recordedEdit
        }
        val endpoints = stepTextures(step) + projectedEdit?.let { stepTextures(it.step) }.orEmpty()
        val cacheOwned = before.cacheTextures()
        require(endpoints.none { it in cacheOwned }) {
            "Joint source/history endpoints cannot take ownership of recorded cache textures"
        }
        val protected = publication.protectedTextures.toSet() + endpoints
        val basis = Basis(before, publication.graphicsEpoch, publication.cursor.peekUndo(),
            publication.cursor.peekRedo(), publication.cursor, publication, protected)
        checkEpoch(basis.epoch, publication)
        // Prepare the COMPLETE step before GL allocation, including native source byte budget.
        val cursor = when (direction) {
            Direction.NEW -> history.prepareNew(step, publication.cursor)
            Direction.UNDO -> history.prepareUndo(publication.cursor, step)
            Direction.REDO -> history.prepareRedo(publication.cursor, step)
        } ?: error("This joint history step is no longer current")
        checkEpoch(basis.epoch, publication)
        val edit = projectedEdit ?: RecordedEdits.prepare(before.planes,
            UndoLog.Step<T>(emptyList(), lines = step.lines,
                slabs = step.slabs.filter { (it.layerId to it.celId) in before.planes }), direction != Direction.UNDO)
        if (projectedEdit != null) check(edit.isCurrent(before.planes)) {
            "The projected recorded sources changed during preparation"
        }
        val presets = frozen ?: before.brushes.mapValues { BrushJson.decodeChecked(it.value) }
        val saved = validateRender(before, edit.after, presets)
        if (edit.changedPlanes.isEmpty()) {
            val candidate = if (saved == before.brushes) before else State(sceneIdentity, before.planes,
                saved, before.units, before.plans, before.looks, before.savedKeys)
            checkEpoch(basis.epoch, publication)
            return JointStaged(publication, candidate, cursor, emptySet(), emptySet(), emptyMap())
        }
        // Freeze newly added definitions too before external callbacks can mutate caller backing.
        val canonicalPresets = saved.mapValues { BrushJson.decodeChecked(it.value) }
        val warmed = warm(before, edit.changedPlanes, saved, basis.epoch, publication, protected)
        var built: Built<T>? = null
        try {
            val ready = buildStage(basis, warmed, edit, canonicalPresets, emptySet())
            built = ready
            checkEpoch(basis.epoch, publication)
            return JointStaged(publication, ready.state, cursor, ready.fresh, ready.retired, ready.affected)
        } catch (failure: Throwable) {
            releaseInEpoch(built?.fresh ?: warmed.fresh, basis.epoch)
            throw failure
        }
    }

    private fun stepTextures(step: UndoLog.Step<T>): Set<T> = buildSet {
        step.changes.forEach { it.before?.let(::add); it.after?.let(::add) }
        step.slabs.forEach { it.before?.texture?.let(::add); it.after?.texture?.let(::add) }
    }

    inner class Staged internal constructor(
        private val before: State<T>,
        private val after: State<T>,
        private val fresh: Set<T>,
        private val retired: Set<T>,
        private val epoch: Long,
        private val step: UndoLog.Step<T>,
        private val direction: Direction,
        private val undoHead: UndoLog.Step<T>?,
        private val redoHead: UndoLog.Step<T>?,
        private val cursor: UndoLog.Cursor<T>,
        val affected: Map<Pair<String, String?>, Set<Long>>,
    ) : AutoCloseable {
        private var finished = false
        init { openLocalLeases++ }

        /** False means no publication. Caller closes this lease to release caches and bake sources. */
        fun commit(): Boolean {
            checkUnbound()
            check(!finished) { "This recorded scene proposal is already finished" }
            if (generation != epoch || state !== before || history.peekUndo() !== undoHead ||
                history.peekRedo() !== redoHead || history.snapshotCursor() !== cursor || !renderer.contextCurrent()) return false
            // A context provider may invalidate the scene/history while answering. Recheck
            // identities after that final callback before publishing into a replacement context.
            if (generation != epoch || state !== before || history.peekUndo() !== undoHead ||
                history.peekRedo() !== redoHead || history.snapshotCursor() !== cursor) return false
            val publish = {
                // Budget callbacks run after commit's preflight. Refuse a reentrant scene reset
                // before assigning old names into a replacement context; UndoLog retains its cursor.
                check(localGeneration == epoch && localState === before && history.snapshotCursor() === cursor) {
                    "Recorded scene or history changed during publication preparation"
                }
                localState = after
            }
            val accepted = try {
                when (direction) {
                    Direction.NEW -> { history.pushAndPublish(step, publish); true }
                    Direction.UNDO -> history.undoAndPublish(step, publish)
                    Direction.REDO -> history.redoAndPublish(step, publish)
                }
            } catch (failure: Throwable) {
                // History does capacity/budget work before its nonthrowing scene publication.
                // A refused publication still leaves both caches and bake sources in this lease.
                close()
                throw failure
            }
            if (!accepted) return false
            finished = true
            openLocalLeases--
            releaseInEpoch(retired, epoch)
            return true
        }

        override fun close() {
            if (finished) return
            finished = true
            openLocalLeases--
            // A name minted by the dead context may name unrelated artwork in the new one.
            releaseInEpoch(fresh, epoch)
        }
    }

    fun stage(edit: RecordedEdits.Prepared<T>, direction: Direction = Direction.NEW,
              frozen: Map<String, BrushPreset> = brushes): Staged {
        return prepareLocal { stagePrepared(edit, direction, frozen, emptySet()) }
    }

    /**
     * Rasterize only these complete physical addresses at their original seqs, in ONE shared
     * history step. Until commit, this lease owns both derived caches and fresh slab sources.
     * After commit, the physical scene/history owns the slabs; close() still owns caches only.
     */
    fun stageBake(addresses: Set<LineAddress>): Staged = prepareLocal { stageBakeLocal(addresses) }

    private fun stageBakeLocal(addresses: Set<LineAddress>): Staged {
        check(renderer.contextCurrent()) { "The recorded renderer's paper or clipping context changed" }
        require(addresses.isNotEmpty()) { "An empty bake cannot publish an undo step" }
        val before = state
        val basis = basis(before)
        // Resolve and validate the ENTIRE scope before any lazy replay or renderer copy call.
        val records = addresses.map { address ->
            val cel = before.planes[address.layerId to address.celId]
                ?: error("No physical cel for this line")
            require(cel.layerId == address.layerId && cel.celId == address.celId) {
                "A physical cel has mismatched ownership"
            }
            val record = cel.records.firstOrNull { it.id == address.recordId }
                ?: error("This line no longer exists")
            require(!record.locked) { "Unlock the line before baking it" }
            address to record
        }
        val frozen = brushes
        val saved = validateRender(before, before.planes, frozen)
        val warmed = warm(before, records.map { it.first.layerId to it.first.celId }.toSet(), saved, basis.epoch)
        val existing = before.cacheTextures() + warmed.fresh + slabTextures(before.planes) + history.heldTextures()
        val sources = LinkedHashSet<T>()
        val freshSources = LinkedHashSet<T>()
        try {
            val baking = records.map { (address, record) ->
                val cached = warmed.state.units[address] ?: error("This line has no cached canonical source")
                require(cached.record == record) { "This line's cached canonical source is stale" }
                require(cached.unit.tiles.values.all { sources.add(it) }) {
                    "Cached canonical sources cannot share texture ownership"
                }
                Triple(address, record, cached.unit)
            }
            val slabs = ArrayList<UndoLog.SlabChange<T>>()
            for ((address, record, unit) in baking) {
                checkEpoch(basis.epoch)
                val copy = renderer.copyForBake(address, unit)
                checkEpoch(basis.epoch)
                var valid = true
                // Visit every returned handle, including fresh siblings of an invalid alias.
                for (texture in copy.tiles.values) {
                    if (texture in existing || !freshSources.add(texture)) valid = false
                }
                require(valid) { "A bake copy must return distinct fresh owned textures" }
                require(copy.op == unit.op && copy.tiles.keys == unit.tiles.keys) {
                    "A bake copy must preserve its canonical operator and exact tile keys"
                }
                for ((key, texture) in copy.tiles) slabs += UndoLog.SlabChange(
                    address.layerId, address.celId, key, record.seq, null,
                    UndoLog.SlabValue(texture, copy.op))
            }
            val lines = baking.map { (address, record, _) -> UndoLog.LineChange(
                address.layerId, address.celId, address.recordId,
                CelComposer.Line(record.seq, record), null)
            }
            val step = UndoLog.Step<T>(emptyList(), lines = lines, slabs = slabs)
            checkEpoch(basis.epoch)
            val edit = RecordedEdits.prepare(before.planes, step, true)
            // This catch owns warm units and copies until the complete proposal is returned.
            return staged(basis, warmed, edit, Direction.NEW, frozen, freshSources.toSet())
        } catch (failure: Throwable) {
            if (generation == basis.epoch) {
                releaseInEpoch(warmed.fresh, basis.epoch)
                releaseInEpoch(freshSources, basis.epoch)
            }
            throw failure
        }
    }

    private fun stagePrepared(edit: RecordedEdits.Prepared<T>, direction: Direction,
                              frozen: Map<String, BrushPreset>, freshSources: Set<T>): Staged {
        if(direction == Direction.NEW) cc.joycreator.joybrush.core.stroke.CapturedStrokeRules.requireNewStep(edit.step)
        requireSourceKinds(edit.before)
        requireSourceKinds(edit.after)
        check(renderer.contextCurrent()) { "The recorded renderer's paper or clipping context changed" }
        require(edit.isCurrent(state.planes)) { "The recorded scene changed before staging" }
        val step = edit.step
        // Legacy tiles, media payloads and structural changes require their own joint publisher.
        // Refuse rather than silently apply only the line half of a mixed step.
        require(step.changes.isEmpty() && step.stackBefore == null && step.stackAfter == null &&
            step.paperBefore == null && step.paperAfter == null &&
            step.documentBefore == null && step.documentAfter == null) { "This mixed history step needs the joint engine publisher" }
        require(step.lines.isNotEmpty() || step.slabs.isNotEmpty()) { "An empty edit cannot publish an undo step" }
        when (direction) {
            Direction.UNDO -> require(history.peekUndo() === step) { "This undo step is no longer current" }
            Direction.REDO -> require(history.peekRedo() === step) { "This redo step is no longer current" }
            Direction.NEW -> kotlin.Unit
        }
        val basis = basis(state)
        val saved = validateRender(basis.before, edit.after, frozen)
        val warmed = warm(basis.before, edit.changedPlanes, saved, basis.epoch)
        try {
            return staged(basis, warmed, edit, direction, frozen, freshSources)
        } catch (failure: Throwable) {
            releaseInEpoch(warmed.fresh, basis.epoch)
            throw failure
        }
    }

    private class Basis<T : Any>(val before: State<T>, val epoch: Long,
                                val undoHead: UndoLog.Step<T>?, val redoHead: UndoLog.Step<T>?,
                                val cursor: UndoLog.Cursor<T>, val publication: Publication<T>? = null,
                                val protected: Set<T> = emptySet())
    private fun basis(before: State<T>): Basis<T> {
        val cursor = history.snapshotCursor()
        return Basis(before, generation, cursor.peekUndo(), cursor.peekRedo(), cursor)
    }

    private fun checkGraphicsEpoch(epoch: Long, joint: Boolean) {
        check(joint || boundRead == null) { "Local preparation cannot cross a scene storage binding" }
        check(generation == epoch) { "The recorded scene context restarted during staging" }
    }

    private fun checkEpoch(epoch: Long, publication: Publication<T>? = null) {
        checkGraphicsEpoch(epoch, publication != null)
        check(publication == null || samePublication(publication)) { "The joint artwork root changed during staging" }
    }

    private class Warmed<T : Any>(val state: State<T>, val fresh: Set<T>)

    /** Materialize the OLD cel in a private baseline before changing its raw records or sources. */
    private fun warm(before: State<T>, touched: Set<Pair<String, String?>>, saved: Map<String, String>, epoch: Long,
                     publication: Publication<T>? = null, protected: Set<T> = emptySet()): Warmed<T> {
        checkEpoch(epoch, publication)
        check(renderer.contextCurrent()) { "The recorded renderer context is unavailable" }
        checkEpoch(epoch, publication)
        val existing = before.cacheTextures() + slabTextures(before.planes) + history.heldTextures() + protected
        val fresh = LinkedHashSet<T>()
        try {
            val units = before.units.toMutableMap()
            val plans = before.plans.toMutableMap()
            for (plane in touched) {
                checkEpoch(epoch, publication)
                val cel = before.planes[plane] ?: error("No physical cel to materialize")
                if (plane in plans) continue
                for (record in cel.records) {
                    val address = LineAddress(cel.layerId, cel.celId, record.id)
                    checkEpoch(epoch, publication)
                    val unit = renderer.line(address, record, BrushJson.decodeChecked(saved.getValue(record.brushId)))
                    // Epoch loss makes returned names unsafe to delete. A same-epoch root switch
                    // still permits collecting/releasing genuinely fresh output before refusal.
                    checkGraphicsEpoch(epoch, publication != null)
                    var valid = true
                    for (texture in unit.tiles.values) {
                        if (texture in existing || !fresh.add(texture)) valid = false
                    }
                    require(valid) { "A renderer returned a texture already owned by this scene" }
                    checkEpoch(epoch, publication)
                    units[address] = Cached(record, unit)
                }
                checkEpoch(epoch, publication)
                val footprints = cel.records.associate { record ->
                    val address = LineAddress(cel.layerId, cel.celId, record.id)
                    address to units.getValue(address).unit.tiles.keys.toSet()
                }
                require(footprints.values.flatMap { it }.toSet() == before.savedKeys.getValue(plane)) {
                    "The materialized recorded-line tile union does not match the declared saved look index"
                }
                checkEpoch(epoch, publication)
                plans[plane] = RecordedTilePlan(cel, footprints)
            }
            checkEpoch(epoch, publication)
            check(renderer.contextCurrent()) { "The recorded renderer context changed during materialization" }
            checkEpoch(epoch, publication)
            return Warmed(State(sceneIdentity, before.planes, before.brushes, units.toMap(), plans.toMap(), before.looks, before.savedKeys), fresh.toSet())
        } catch (failure: Throwable) {
            releaseInEpoch(fresh, epoch)
            throw failure
        }
    }

    private fun staged(basis: Basis<T>, warmed: Warmed<T>, edit: RecordedEdits.Prepared<T>,
                       direction: Direction, frozen: Map<String, BrushPreset>, freshSources: Set<T>): Staged {
        val built = buildStage(basis, warmed, edit, frozen, freshSources)
        try {
            return Staged(basis.before, built.state, built.fresh, built.retired,
                basis.epoch, edit.step, direction, basis.undoHead, basis.redoHead, basis.cursor, built.affected)
        } catch (failure: Throwable) {
            releaseInEpoch(built.fresh - warmed.fresh - freshSources, basis.epoch)
            throw failure
        }
    }

    private class Built<T : Any>(val state: State<T>, val fresh: Set<T>, val retired: Set<T>,
                                 val affected: Map<Pair<String, String?>, Set<Long>>)

    private fun buildStage(basis: Basis<T>, warmed: Warmed<T>, edit: RecordedEdits.Prepared<T>,
                           frozen: Map<String, BrushPreset>, freshSources: Set<T>): Built<T> {
        checkEpoch(basis.epoch, basis.publication)
        val rendered = render(warmed.state, edit.after, frozen, basis.epoch, basis.publication, basis.protected)
        try {
            checkEpoch(basis.epoch, basis.publication)
            val transientWarm = warmed.fresh - rendered.state.cacheTextures()
            return Built(rendered.state, warmed.fresh + rendered.fresh + freshSources,
                (basis.before.cacheTextures() - rendered.state.cacheTextures()) + transientWarm,
                rendered.affected)
        } catch (failure: Throwable) {
            // The caller still owns warm units/bake copies; only this render's additions are ours.
            releaseInEpoch(rendered.fresh, basis.epoch)
            throw failure
        }
    }

    /** Context loss forgets names without issuing deletes on the replacement context. */
    fun forget() {
        checkUnbound()
        localGeneration++
        localState = State(sceneIdentity, emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())
    }

    /** The engine/history still owns the slabs. Closing releases only this scene's derived caches. */
    fun close() {
        checkUnbound()
        val owned = state.cacheTextures()
        forget()
        releaseInEpoch(owned, generation)
    }

    private class Rendered<T : Any>(val state: State<T>, val fresh: Set<T>,
                                    val affected: Map<Pair<String, String?>, Set<Long>>)

    private fun slabTextures(planes: Map<Pair<String, String?>, RecordedCel<T>>): List<T> =
        planes.values.flatMap { cel -> cel.slabs.values.flatMap { stack -> stack.map { it.value.texture } } }

    private fun requireSourceKinds(planes: Map<Pair<String, String?>, RecordedCel<T>>) {
        if (renderer !is CapturedSourceRenderer<*>)
            planes.values.forEach { CapturedStrokeRules.requireOrdinary(it.records) }
    }

    private fun requireSourceKinds(step: UndoLog.Step<T>) {
        if (renderer !is CapturedSourceRenderer<*>) step.lines.forEach { change ->
            CapturedStrokeRules.requireOrdinary(listOfNotNull(change.before?.record, change.after?.record))
        }
    }

    /** No capability callback can cross a local edit, binding, history move or root/context reset. */
    private fun admitCapturedSources(planes: Map<Pair<String, String?>, RecordedCel<T>>, saved: Map<String, String>) {
        val physical = planes.toMap()
        val captured = physical.values.flatMap { cel -> cel.records.filter { it.capturedPhysicalSource != null }.map {
            LineAddress(cel.layerId, cel.celId, it.id) to it
        } }
        if (captured.isEmpty()) return
        val capable = renderer as? CapturedSourceRenderer<T>
            ?: throw IllegalArgumentException("Captured physical sources need an explicitly capable renderer")
        val read = boundRead
        val binding = bindingVersion
        val epoch = generation
        val live = state
        val cursor = history.snapshotCursor()
        val publication = read?.invoke()
        fun checkCurrent() {
            check(boundRead === read && bindingVersion == binding && generation == epoch && state === live &&
                planes.keys == physical.keys && physical.all { (at, cel) -> planes[at] === cel } &&
                history.snapshotCursor() === cursor && (publication == null || samePublication(publication)) &&
                boundRead === read && bindingVersion == binding && generation == epoch && state === live &&
                planes.keys == physical.keys && physical.all { (at, cel) -> planes[at] === cel } &&
                history.snapshotCursor() === cursor) { "Recorded state or history changed during captured source admission" }
        }
        check(!admittingCapturedSource) { "Captured source admission cannot recurse" }
        checkCurrent()
        admittingCapturedSource = true
        try {
            for ((address, record) in captured) {
                require(CapturedStrokeRules.problems(record).isEmpty()) { "Invalid captured source recipe" }
                check(renderer.contextCurrent()) { "The captured renderer context is unavailable" }
                checkCurrent()
                val admitted = capable.admitCapturedSource(address, CapturedStrokeRules.snapshot(record),
                    BrushJson.decodeChecked(saved.getValue(record.brushId)))
                checkCurrent()
                require(admitted) { "The renderer refused this captured physical source" }
                check(renderer.contextCurrent()) { "The captured renderer context changed during admission" }
                checkCurrent()
            }
        } finally {
            admittingCapturedSource = false
        }
    }

    private fun validateRender(before: State<T>, after: Map<Pair<String, String?>, RecordedCel<T>>,
                               frozen: Map<String, BrushPreset>): Map<String, String> {
        requireSourceKinds(before.planes)
        requireSourceKinds(after)
        val saved = FrozenBrushRegistry.validate(frozen).mapValues { BrushJson.encode(it.value) }
        require(before.brushes.all { (id, json) -> saved[id] == json }) { "Frozen brushes cannot be replaced or removed while history can reference them" }
        val borrowed = after.values.flatMap { cel -> cel.slabs.values.flatMap { stack -> stack.map { it.value.texture } } }
        require(borrowed.distinct().size == borrowed.size) { "Physical cels cannot share ownership of a slab texture" }
        for ((plane, cel) in after) {
            require(plane == (cel.layerId to cel.celId)) { "Physical cel ownership does not match its scene address" }
            require(cel.records.all { it.id.isNotBlank() }) { "A recorded line needs its full address" }
            require(cel.records.all { it.brushId in saved }) { "A recorded line has no frozen document brush" }
        }
        // Admit both history endpoints, including records removed by a delete or bake. All
        // records are checked before warm/render allocates any source; admission grants no save,
        // cold-loader, Select or other consumer permission outside this renderer instance.
        admitCapturedSources(before.planes, saved)
        admitCapturedSources(after, saved)
        return saved
    }

    private fun render(before: State<T>, after: Map<Pair<String, String?>, RecordedCel<T>>,
                       frozen: Map<String, BrushPreset>, epoch: Long = generation,
                       publication: Publication<T>? = null, protected: Set<T> = emptySet()): Rendered<T> {
        requireSourceKinds(before.planes)
        requireSourceKinds(after)
        checkEpoch(epoch, publication)
        check(renderer.contextCurrent()) { "The recorded renderer context is unavailable" }
        checkEpoch(epoch, publication)
        val saved = validateRender(before, after, frozen)
        val existing = before.cacheTextures() + slabTextures(before.planes) + slabTextures(after) + history.heldTextures() + protected
        val fresh = LinkedHashSet<T>()
        fun own(textures: Collection<T>) {
            var valid = true
            // Inspect the complete returned batch even if an early name is invalid, so all fresh
            // siblings are reclaimed on refusal without releasing any already-live texture.
            for (texture in textures) {
                if (texture in existing || !fresh.add(texture)) valid = false
            }
            require(valid) { "A renderer returned a texture already owned by this scene" }
        }
        try {
            val units = linkedMapOf<LineAddress, Cached<T>>()
            // New planes are eager; existing cold planes remain untouched. The staging caller
            // must have warmed every old physical cel changed by the proposal.
            val materialized = after.keys.filter { it in before.plans || it !in before.planes }.toSet()
            for ((plane, cel) in after) {
                if (plane !in materialized) {
                    require(cel === before.planes[plane]) { "A changed cold cel must be materialized before editing" }
                    continue
                }
                for (record in cel.records) {
                    val address = LineAddress(cel.layerId, cel.celId, record.id)
                    val previous = before.units[address]
                    val unit = if (previous != null && CapturedStrokeRules.sameRecord(
                            renderingRecord(previous.record), renderingRecord(record))) previous.unit
                    else {
                        checkEpoch(epoch, publication)
                        val rendered = renderer.line(address, record, BrushJson.decodeChecked(saved.getValue(record.brushId)))
                        checkGraphicsEpoch(epoch, publication != null)
                        own(rendered.tiles.values)
                        checkEpoch(epoch, publication)
                        rendered
                    }
                    units[address] = Cached(record, unit)
                }
            }
            checkEpoch(epoch, publication)
            val footprints = units.mapValues { it.value.unit.tiles.keys.toSet() }
            val plans = after.filterKeys { it in materialized }.mapValues { RecordedTilePlan(it.value, footprints) }
            val savedKeys = after.mapValues { (plane, cel) ->
                if (plane !in materialized) before.savedKeys.getValue(plane)
                else cel.records.flatMap { footprints.getValue(LineAddress(cel.layerId, cel.celId, it.id)) }.toSet()
            }
            val affected = linkedMapOf<Pair<String, String?>, Set<Long>>()
            val looks = before.looks.filterKeys { it in after }.toMutableMap()
            for (plane in before.planes.keys + after.keys) {
                if (plane in after && plane !in materialized) continue
                val keys = RecordedTilePlan.affectedTiles(before.plans[plane], plans[plane])
                if (keys.isEmpty()) continue
                affected[plane] = keys
                if (plane !in after) { looks.remove(plane); continue }
                val next = before.looks[plane].orEmpty().toMutableMap()
                for (key in keys) {
                    val items = plans.getValue(plane).itemsOn(key)
                    val sources = items.map { item -> when (item) {
                        is RecordedTilePlan.Pixel -> Source(item.seq, item.value.texture, item.value.op)
                        is RecordedTilePlan.Line -> {
                            val unit = units.getValue(item.address).unit
                            Source(item.seq, unit.tiles.getValue(key), unit.op)
                        }
                    } }
                    val output = if (sources.isEmpty()) null else {
                        checkEpoch(epoch, publication)
                        val rendered = renderer.look(plane, key, sources)
                        checkGraphicsEpoch(epoch, publication != null)
                        rendered
                    }
                    require(output != null || items.none { it is RecordedTilePlan.Line }) {
                        "A line-crossed tile requires a saved look, including fully transparent paint"
                    }
                    if (output == null) next.remove(key) else { own(listOf(output)); next[key] = output }
                    checkEpoch(epoch, publication)
                }
                looks[plane] = next.toMap()
            }
            checkEpoch(epoch, publication)
            return Rendered(State(sceneIdentity, after.toMap(), saved, units.toMap(), plans, looks.toMap(), savedKeys), fresh.toSet(), affected.toMap())
        } catch (failure: Throwable) {
            releaseInEpoch(fresh, epoch)
            throw failure
        }
    }

    private fun renderingRecord(record: StrokeRecord): StrokeRecord = record.copy(locked = false, groupId = null)

    companion object {
        /**
         * Install uploaded saved looks without replay or composition. Every physical cel needs
         * an explicit declared index, including empty no-dab indexes. These declarations are NOT
         * proven replay footprints: the first edit materializes the old cel and checks its union.
         * uploadedLooks contains ONLY line-crossed archive looks. A distant plain seq0 tile or a
         * record-free ordered slab stack is not a saved look, and cannot alias a source handle.
         * This factory does not promise full-plane display: the loader must handle plain pixels
         * and compose slab-only display outside it, without stroke replay. It also does not
         * enforce a runtime unit cache/allocation budget or promise bounded first-edit latency.
         *
         * Inputs are borrowed during validation. Only a successful return transfers ownership of
         * uploadedLooks to the scene. On rejection the caller still owns ALL upload handles and
         * performs rollback; this method never deletes them. Physical slabs remain borrowed.
         * The caller must supply genuinely caller-owned NEW upload handles, and pass old/global
         * owned handles in protectedTextures (for example the previous scene's heldCacheTextures).
         * This pure factory cannot prove ownership of handles omitted from that protected set.
         */
        fun <T : Any> createSaved(planes: Map<Pair<String, String?>, RecordedCel<T>>, brushes: Map<String, BrushPreset>,
                                 history: UndoLog<T>, renderer: Renderer<T>,
                                 savedLookKeys: Map<Pair<String, String?>, Set<Long>>,
                                 uploadedLooks: Map<Pair<String, String?>, Map<Long, T>>,
                                 protectedTextures: Set<T> = emptySet()): RecordedScene<T> {
            val scene = RecordedScene(history, renderer)
            // Detach and validate before calling external context providers. A provider can
            // reenter history or mutate caller collection backing while answering readiness.
            val cursor = history.snapshotCursor()
            val physical = planes.toMap()
            val looks = uploadedLooks.mapValues { it.value.toMap() }
            val keys = savedLookKeys.mapValues { it.value.toSet() }
            val protected = protectedTextures.toSet()
            val saved = scene.validateRender(scene.state, physical, brushes)
            check(renderer.contextCurrent()) { "The recorded renderer context is unavailable" }
            require(keys.keys == physical.keys && looks.keys == physical.keys) {
                "Every physical cel needs an explicit saved look index and uploaded look map"
            }
            for ((plane, cel) in physical) {
                require(looks.getValue(plane).keys == keys.getValue(plane)) {
                    "Uploaded saved looks do not match their declared index"
                }
                require(cel.records.isNotEmpty() || keys.getValue(plane).isEmpty()) {
                    "A physical cel without records cannot have line-crossed saved looks"
                }
            }
            val textures = looks.values.flatMap { it.values }
            require(textures.distinct().size == textures.size) { "Saved looks cannot share texture ownership" }
            val borrowed = scene.slabTextures(physical) + history.heldTextures()
            require(textures.none { it in borrowed }) { "Saved looks cannot own physical source or history textures" }
            require(textures.none { it in protected }) { "Saved looks cannot own protected old or global textures" }
            check(renderer.contextCurrent()) { "The recorded renderer context changed before saved-look acceptance" }
            check(history.snapshotCursor() === cursor) {
                "Recorded history changed before saved-look acceptance"
            }
            scene.localState = State(scene.sceneIdentity, physical, saved, emptyMap(), emptyMap(), looks, keys)
            return scene
        }

        /** Open or promote only after all source units and looks have staged successfully. */
        fun <T : Any> create(planes: Map<Pair<String, String?>, RecordedCel<T>>, brushes: Map<String, BrushPreset>,
                            history: UndoLog<T>, renderer: Renderer<T>): RecordedScene<T> {
            val scene = RecordedScene(history, renderer)
            val rendered = scene.prepareLocal { scene.render(scene.state, planes, brushes) }
            scene.localState = rendered.state
            return scene
        }
    }
}

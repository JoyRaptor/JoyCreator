package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.core.brush.BrushJson
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.FrozenBrushRegistry
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
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
 * Rendering is staged before history moves. Publication assigns one prepared scene reference.
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

    internal class Cached<T : Any>(val record: StrokeRecord, val unit: Unit<T>)
    internal class State<T : Any>(
        val planes: Map<Pair<String, String?>, RecordedCel<T>>,
        val brushes: Map<String, String>,
        val units: Map<LineAddress, Cached<T>>,
        val plans: Map<Pair<String, String?>, RecordedTilePlan<T>>,
        val looks: Map<Pair<String, String?>, Map<Long, T>>,
        // Loaded declarations are independent of units. Absence of a plan means unresolved
        // per-line footprints, not that every line has an authoritative empty footprint.
        val savedKeys: Map<Pair<String, String?>, Set<Long>>,
    ) {
        fun cacheTextures(): Set<T> = (units.values.flatMap { it.unit.tiles.values } +
            looks.values.flatMap { it.values }).toSet()
    }

    private var state = State<T>(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())
    private var generation = 0L

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
        val affected: Map<Pair<String, String?>, Set<Long>>,
    ) : AutoCloseable {
        private var finished = false

        /** False means no publication. Caller closes this lease to release caches and bake sources. */
        fun commit(): Boolean {
            check(!finished) { "This recorded scene proposal is already finished" }
            if (generation != epoch || state !== before || history.peekUndo() !== undoHead ||
                history.peekRedo() !== redoHead || !renderer.contextCurrent()) return false
            // A context provider may invalidate the scene/history while answering. Recheck
            // identities after that final callback before publishing into a replacement context.
            if (generation != epoch || state !== before || history.peekUndo() !== undoHead ||
                history.peekRedo() !== redoHead) return false
            val publish = { state = after }
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
            retired.forEach(renderer::release)
            return true
        }

        override fun close() {
            if (finished) return
            finished = true
            // A name minted by the dead context may name unrelated artwork in the new one.
            if (generation == epoch) fresh.forEach(renderer::release)
        }
    }

    fun stage(edit: RecordedEdits.Prepared<T>, direction: Direction = Direction.NEW,
              frozen: Map<String, BrushPreset> = brushes): Staged =
        stagePrepared(edit, direction, frozen, emptySet())

    /**
     * Rasterize only these complete physical addresses at their original seqs, in ONE shared
     * history step. Until commit, this lease owns both derived caches and fresh slab sources.
     * After commit, the physical scene/history owns the slabs; close() still owns caches only.
     */
    fun stageBake(addresses: Set<LineAddress>): Staged {
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
                warmed.fresh.forEach(renderer::release)
                freshSources.forEach(renderer::release)
            }
            throw failure
        }
    }

    private fun stagePrepared(edit: RecordedEdits.Prepared<T>, direction: Direction,
                              frozen: Map<String, BrushPreset>, freshSources: Set<T>): Staged {
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
            if (generation == basis.epoch) warmed.fresh.forEach(renderer::release)
            throw failure
        }
    }

    private class Basis<T : Any>(val before: State<T>, val epoch: Long,
                                val undoHead: UndoLog.Step<T>?, val redoHead: UndoLog.Step<T>?)
    private fun basis(before: State<T>): Basis<T> = Basis(before, generation, history.peekUndo(), history.peekRedo())

    private fun checkEpoch(epoch: Long) {
        check(generation == epoch) { "The recorded scene context restarted during staging" }
    }

    private class Warmed<T : Any>(val state: State<T>, val fresh: Set<T>)

    /** Materialize the OLD cel in a private baseline before changing its raw records or sources. */
    private fun warm(before: State<T>, touched: Set<Pair<String, String?>>, saved: Map<String, String>, epoch: Long): Warmed<T> {
        checkEpoch(epoch)
        check(renderer.contextCurrent()) { "The recorded renderer context is unavailable" }
        checkEpoch(epoch)
        val existing = before.cacheTextures() + slabTextures(before.planes) + history.heldTextures()
        val fresh = LinkedHashSet<T>()
        try {
            val units = before.units.toMutableMap()
            val plans = before.plans.toMutableMap()
            for (plane in touched) {
                checkEpoch(epoch)
                val cel = before.planes[plane] ?: error("No physical cel to materialize")
                if (plane in plans) continue
                for (record in cel.records) {
                    val address = LineAddress(cel.layerId, cel.celId, record.id)
                    checkEpoch(epoch)
                    val unit = renderer.line(address, record, BrushJson.decodeChecked(saved.getValue(record.brushId)))
                    checkEpoch(epoch)
                    var valid = true
                    for (texture in unit.tiles.values) {
                        if (texture in existing || !fresh.add(texture)) valid = false
                    }
                    require(valid) { "A renderer returned a texture already owned by this scene" }
                    units[address] = Cached(record, unit)
                }
                checkEpoch(epoch)
                val footprints = cel.records.associate { record ->
                    val address = LineAddress(cel.layerId, cel.celId, record.id)
                    address to units.getValue(address).unit.tiles.keys.toSet()
                }
                require(footprints.values.flatMap { it }.toSet() == before.savedKeys.getValue(plane)) {
                    "The materialized recorded-line tile union does not match the declared saved look index"
                }
                checkEpoch(epoch)
                plans[plane] = RecordedTilePlan(cel, footprints)
            }
            checkEpoch(epoch)
            check(renderer.contextCurrent()) { "The recorded renderer context changed during materialization" }
            checkEpoch(epoch)
            return Warmed(State(before.planes, before.brushes, units.toMap(), plans.toMap(), before.looks, before.savedKeys), fresh.toSet())
        } catch (failure: Throwable) {
            if (generation == epoch) fresh.forEach(renderer::release)
            throw failure
        }
    }

    private fun staged(basis: Basis<T>, warmed: Warmed<T>, edit: RecordedEdits.Prepared<T>,
                       direction: Direction, frozen: Map<String, BrushPreset>, freshSources: Set<T>): Staged {
        checkEpoch(basis.epoch)
        val rendered = render(warmed.state, edit.after, frozen, basis.epoch)
        try {
            checkEpoch(basis.epoch)
            val transientWarm = warmed.fresh - rendered.state.cacheTextures()
            return Staged(basis.before, rendered.state, warmed.fresh + rendered.fresh + freshSources,
                (basis.before.cacheTextures() - rendered.state.cacheTextures()) + transientWarm,
                basis.epoch, edit.step, direction, basis.undoHead, basis.redoHead, rendered.affected)
        } catch (failure: Throwable) {
            // The caller still owns warm units/bake copies; only this render's additions are ours.
            if (generation == basis.epoch) rendered.fresh.forEach(renderer::release)
            throw failure
        }
    }

    /** Context loss forgets names without issuing deletes on the replacement context. */
    fun forget() {
        generation++
        state = State(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())
    }

    /** The engine/history still owns the slabs. Closing releases only this scene's derived caches. */
    fun close() {
        val owned = state.cacheTextures()
        forget()
        owned.forEach(renderer::release)
    }

    private class Rendered<T : Any>(val state: State<T>, val fresh: Set<T>,
                                    val affected: Map<Pair<String, String?>, Set<Long>>)

    private fun slabTextures(planes: Map<Pair<String, String?>, RecordedCel<T>>): List<T> =
        planes.values.flatMap { cel -> cel.slabs.values.flatMap { stack -> stack.map { it.value.texture } } }

    private fun validateRender(before: State<T>, after: Map<Pair<String, String?>, RecordedCel<T>>,
                               frozen: Map<String, BrushPreset>): Map<String, String> {
        val saved = FrozenBrushRegistry.validate(frozen).mapValues { BrushJson.encode(it.value) }
        require(before.brushes.all { (id, json) -> saved[id] == json }) { "Frozen brushes cannot be replaced or removed while history can reference them" }
        val borrowed = after.values.flatMap { cel -> cel.slabs.values.flatMap { stack -> stack.map { it.value.texture } } }
        require(borrowed.distinct().size == borrowed.size) { "Physical cels cannot share ownership of a slab texture" }
        for ((plane, cel) in after) {
            require(plane == (cel.layerId to cel.celId)) { "Physical cel ownership does not match its scene address" }
            require(cel.records.all { it.id.isNotBlank() }) { "A recorded line needs its full address" }
            require(cel.records.all { it.brushId in saved }) { "A recorded line has no frozen document brush" }
        }
        return saved
    }

    private fun render(before: State<T>, after: Map<Pair<String, String?>, RecordedCel<T>>,
                       frozen: Map<String, BrushPreset>, epoch: Long = generation): Rendered<T> {
        checkEpoch(epoch)
        check(renderer.contextCurrent()) { "The recorded renderer context is unavailable" }
        checkEpoch(epoch)
        val saved = validateRender(before, after, frozen)
        val existing = before.cacheTextures() + slabTextures(before.planes) + slabTextures(after) + history.heldTextures()
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
                    val unit = if (previous != null && renderingRecord(previous.record) == renderingRecord(record)) previous.unit
                    else {
                        checkEpoch(epoch)
                        val rendered = renderer.line(address, record, BrushJson.decodeChecked(saved.getValue(record.brushId)))
                        checkEpoch(epoch)
                        own(rendered.tiles.values)
                        rendered
                    }
                    units[address] = Cached(record, unit)
                }
            }
            checkEpoch(epoch)
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
                        checkEpoch(epoch)
                        val rendered = renderer.look(plane, key, sources)
                        checkEpoch(epoch)
                        rendered
                    }
                    require(output != null || items.none { it is RecordedTilePlan.Line }) {
                        "A line-crossed tile requires a saved look, including fully transparent paint"
                    }
                    if (output == null) next.remove(key) else { own(listOf(output)); next[key] = output }
                }
                looks[plane] = next.toMap()
            }
            checkEpoch(epoch)
            return Rendered(State(after.toMap(), saved, units.toMap(), plans, looks.toMap(), savedKeys), fresh.toSet(), affected.toMap())
        } catch (failure: Throwable) {
            if (generation == epoch) fresh.forEach(renderer::release)
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
            check(renderer.contextCurrent()) { "The recorded renderer context is unavailable" }
            val scene = RecordedScene(history, renderer)
            val saved = scene.validateRender(scene.state, planes, brushes)
            require(savedLookKeys.keys == planes.keys && uploadedLooks.keys == planes.keys) {
                "Every physical cel needs an explicit saved look index and uploaded look map"
            }
            for ((plane, cel) in planes) {
                require(uploadedLooks.getValue(plane).keys == savedLookKeys.getValue(plane)) {
                    "Uploaded saved looks do not match their declared index"
                }
                require(cel.records.isNotEmpty() || savedLookKeys.getValue(plane).isEmpty()) {
                    "A physical cel without records cannot have line-crossed saved looks"
                }
            }
            val textures = uploadedLooks.values.flatMap { it.values }
            require(textures.distinct().size == textures.size) { "Saved looks cannot share texture ownership" }
            val borrowed = scene.slabTextures(planes) + history.heldTextures()
            require(textures.none { it in borrowed }) { "Saved looks cannot own physical source or history textures" }
            require(textures.none { it in protectedTextures }) { "Saved looks cannot own protected old or global textures" }
            val looks = uploadedLooks.mapValues { it.value.toMap() }
            val keys = savedLookKeys.mapValues { it.value.toSet() }
            check(renderer.contextCurrent()) { "The recorded renderer context changed before saved-look acceptance" }
            scene.state = State(planes.toMap(), saved, emptyMap(), emptyMap(), looks, keys)
            return scene
        }

        /** Open or promote only after all source units and looks have staged successfully. */
        fun <T : Any> create(planes: Map<Pair<String, String?>, RecordedCel<T>>, brushes: Map<String, BrushPreset>,
                            history: UndoLog<T>, renderer: Renderer<T>): RecordedScene<T> {
            val scene = RecordedScene(history, renderer)
            val rendered = scene.render(scene.state, planes, brushes)
            scene.state = rendered.state
            return scene
        }
    }
}

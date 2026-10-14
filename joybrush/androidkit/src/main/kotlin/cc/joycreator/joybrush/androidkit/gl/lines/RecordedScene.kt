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
 * borrowed from the engine/history; this object owns only line units and composed looks.
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
    ) {
        fun cacheTextures(): Set<T> = (units.values.flatMap { it.unit.tiles.values } +
            looks.values.flatMap { it.values }).toSet()
    }

    private var state = State<T>(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())
    private var generation = 0L

    /** Borrowed read-only cel identities used by RecordedEdits; mutation stays on the GL thread. */
    val planes: Map<Pair<String, String?>, RecordedCel<T>> get() = state.planes
    val brushes: Map<String, BrushPreset> get() = state.brushes.mapValues { BrushJson.decodeChecked(it.value) }
    fun lookKeys(plane: Pair<String, String?>): Set<Long> = state.looks[plane]?.keys?.toSet().orEmpty()
    /** Archive indexes list line-crossed tiles only; plain slab tiles stay in the pixel payload. */
    fun savedLookKeys(plane: Pair<String, String?>): Set<Long> = state.units.entries
        .filter { (address, _) -> address.layerId == plane.first && address.celId == plane.second }
        .flatMap { it.value.unit.tiles.keys }.toSet()
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
        val epoch = generation
        val existing = before.cacheTextures() + slabTextures(before.planes) + history.heldTextures()
        val sources = LinkedHashSet<T>()
        // Resolve and validate the ENTIRE scope before the first renderer copy call.
        val baking = addresses.map { address ->
            val cel = before.planes[address.layerId to address.celId]
                ?: error("No physical cel for this line")
            require(cel.layerId == address.layerId && cel.celId == address.celId) {
                "A physical cel has mismatched ownership"
            }
            val record = cel.records.firstOrNull { it.id == address.recordId }
                ?: error("This line no longer exists")
            require(!record.locked) { "Unlock the line before baking it" }
            val cached = before.units[address] ?: error("This line has no cached canonical source")
            require(cached.record == record) { "This line's cached canonical source is stale" }
            require(cached.unit.tiles.values.all { sources.add(it) }) {
                "Cached canonical sources cannot share texture ownership"
            }
            Triple(address, record, cached.unit)
        }
        val freshSources = LinkedHashSet<T>()
        try {
            val slabs = ArrayList<UndoLog.SlabChange<T>>()
            for ((address, record, unit) in baking) {
                val copy = renderer.copyForBake(address, unit)
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
            val edit = RecordedEdits.prepare(before.planes, step, true)
            // Render owns only its own new derived caches on failure. This catch owns the slab
            // copies until stagePrepared successfully transfers them into the returned lease.
            return stagePrepared(edit, Direction.NEW, brushes, freshSources.toSet())
        } catch (failure: Throwable) {
            if (generation == epoch) freshSources.forEach(renderer::release)
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
        val before = state
        val epoch = generation
        val undoHead = history.peekUndo()
        val redoHead = history.peekRedo()
        val rendered = render(before, edit.after, frozen)
        return Staged(before, rendered.state, rendered.fresh + freshSources, before.cacheTextures() - rendered.state.cacheTextures(),
            epoch, step, direction, undoHead, redoHead, rendered.affected)
    }

    /** Context loss forgets names without issuing deletes on the replacement context. */
    fun forget() {
        generation++
        state = State(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())
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

    private fun render(before: State<T>, after: Map<Pair<String, String?>, RecordedCel<T>>,
                       frozen: Map<String, BrushPreset>): Rendered<T> {
        check(renderer.contextCurrent()) { "The recorded renderer context is unavailable" }
        val epoch = generation
        val saved = FrozenBrushRegistry.validate(frozen).mapValues { BrushJson.encode(it.value) }
        require(before.brushes.all { (id, json) -> saved[id] == json }) { "Frozen brushes cannot be replaced or removed while history can reference them" }
        val borrowed = after.values.flatMap { cel -> cel.slabs.values.flatMap { stack -> stack.map { it.value.texture } } }
        require(borrowed.distinct().size == borrowed.size) { "Physical cels cannot share ownership of a slab texture" }
        val existing = before.cacheTextures() + before.planes.values.flatMap { cel ->
            cel.slabs.values.flatMap { stack -> stack.map { it.value.texture } }
        } + borrowed + history.heldTextures()
        for ((plane, cel) in after) {
            require(plane == (cel.layerId to cel.celId)) { "Physical cel ownership does not match its scene address" }
            require(cel.records.all { it.brushId in saved }) { "A recorded line has no frozen document brush" }
        }
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
            for (cel in after.values) for (record in cel.records) {
                val address = LineAddress(cel.layerId, cel.celId, record.id)
                val previous = before.units[address]
                val unit = if (previous != null && renderingRecord(previous.record) == renderingRecord(record)) previous.unit
                else renderer.line(address, record, BrushJson.decodeChecked(saved.getValue(record.brushId))).also { unit ->
                    own(unit.tiles.values)
                }
                units[address] = Cached(record, unit)
            }
            val footprints = units.mapValues { it.value.unit.tiles.keys.toSet() }
            val plans = after.mapValues { RecordedTilePlan(it.value, footprints) }
            val affected = linkedMapOf<Pair<String, String?>, Set<Long>>()
            val looks = before.looks.filterKeys { it in after }.toMutableMap()
            for (plane in before.planes.keys + after.keys) {
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
                    val output = if (sources.isEmpty()) null else renderer.look(plane, key, sources)
                    require(output != null || items.none { it is RecordedTilePlan.Line }) {
                        "A line-crossed tile requires a saved look, including fully transparent paint"
                    }
                    if (output == null) next.remove(key) else { own(listOf(output)); next[key] = output }
                }
                looks[plane] = next.toMap()
            }
            return Rendered(State(after.toMap(), saved, units.toMap(), plans, looks.toMap()), fresh.toSet(), affected.toMap())
        } catch (failure: Throwable) {
            if (generation == epoch) fresh.forEach(renderer::release)
            throw failure
        }
    }

    private fun renderingRecord(record: StrokeRecord): StrokeRecord = record.copy(locked = false, groupId = null)

    companion object {
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

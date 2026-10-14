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
        /** Borrowed ordered sources. Apply the physical cel's clipping while composing. */
        fun look(plane: Pair<String, String?>, key: Long, sources: List<Source<T>>): T?
        /** Must not throw; release a valid cache texture in the CURRENT context only. */
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

        /** False means no publication. Caller closes this lease to release its staged caches. */
        fun commit(): Boolean {
            check(!finished) { "This recorded scene proposal is already finished" }
            if (generation != epoch || state !== before || history.peekUndo() !== undoHead ||
                history.peekRedo() !== redoHead || !renderer.contextCurrent()) return false
            val publish = { state = after }
            val accepted = when (direction) {
                Direction.NEW -> { history.pushAndPublish(step, publish); true }
                Direction.UNDO -> history.undoAndPublish(step, publish)
                Direction.REDO -> history.redoAndPublish(step, publish)
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
              frozen: Map<String, BrushPreset> = brushes): Staged {
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
        return Staged(before, rendered.state, rendered.fresh, before.cacheTextures() - rendered.state.cacheTextures(),
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

    private fun render(before: State<T>, after: Map<Pair<String, String?>, RecordedCel<T>>,
                       frozen: Map<String, BrushPreset>): Rendered<T> {
        check(renderer.contextCurrent()) { "The recorded renderer context is unavailable" }
        val saved = FrozenBrushRegistry.validate(frozen).mapValues { BrushJson.encode(it.value) }
        require(before.brushes.all { (id, json) -> saved[id] == json }) { "Frozen brushes cannot be replaced or removed while history can reference them" }
        val borrowed = after.values.flatMap { cel -> cel.slabs.values.flatMap { stack -> stack.map { it.value.texture } } }
        require(borrowed.distinct().size == borrowed.size) { "Physical cels cannot share ownership of a slab texture" }
        val existing = before.cacheTextures() + before.planes.values.flatMap { cel ->
            cel.slabs.values.flatMap { stack -> stack.map { it.value.texture } }
        } + borrowed
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
            fresh.forEach(renderer::release)
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

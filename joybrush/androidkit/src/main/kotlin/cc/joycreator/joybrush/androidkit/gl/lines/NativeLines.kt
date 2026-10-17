package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.androidkit.lines.LineSnapshot
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.InkReplay
import kotlin.math.floor
import kotlin.math.max

/**
 * Editable lines v1 (tasks/joybrush/specs/NATIVE_LINES_V1_20261016.md): the engine-side bookkeeping beside the ordinary
 * tiles. Pure (no GL): texture names are plain Ints owned as the spec says. GL thread only.
 *
 * For every tile some line of a plane CROSSES (its footprint holds the tile), the plane's ordinary tile is exactly
 *   base ∘ (every line crossing it, in seq order).
 * The base is the tile as it was before the first line crossed it. It exists exactly while a line crosses the tile,
 * which every step keeps true (a step that makes a tile crossed records the base; one that makes it uncrossed drops
 * it; undo and redo replay those as slab changes at seq 0). An ABSENT base on a crossed tile means transparent.
 * Footprints are supersets on purpose: a tile wrongly called crossed only re-renders as base ∘ nothing = itself.
 */
internal class NativeLines(
    /** The brush a record names, from this drawing's frozen copies. */
    private val footprintOf: (StrokeRecord, BrushPreset?) -> Set<Long> = ::cpuFootprint,
) {
    private class Plane {
        val records = ArrayList<StrokeRecord>()          // seq ascending
        val base = HashMap<Long, Int>()                   // tile → base texture; absent on a crossed tile = transparent
    }

    private val planes = LinkedHashMap<LinePlane, Plane>()
    private val brushes = LinkedHashMap<String, BrushPreset>()
    private val footprints = HashMap<StrokeRecord, Set<Long>>()

    var revision = 0L
        private set

    fun brush(id: String): BrushPreset? = brushes[id]
    fun brushes(): Map<String, BrushPreset> = LinkedHashMap(brushes)

    /** New frozen copies. An id is a definition for good: a different definition under a known id is refused. */
    fun addBrushes(entries: Map<String, BrushPreset>) {
        for ((id, preset) in entries) {
            val known = brushes[id]
            require(known == null || known == preset) { "Frozen brush \"$id\" already means another brush" }
        }
        if (entries.keys.any { it !in brushes }) revision++
        brushes.putAll(entries)
    }

    fun lines(plane: LinePlane): List<StrokeRecord> = planes[plane]?.records?.toList().orEmpty()
    fun hasLines(plane: LinePlane): Boolean = planes[plane]?.records?.isNotEmpty() == true
    fun hasLinesOnLayer(layerId: String): Boolean = planes.any { (k, v) -> k.layerId == layerId && v.records.isNotEmpty() }
    fun linePlanes(): Set<LinePlane> = planes.filterValues { it.records.isNotEmpty() }.keys.toSet()

    fun record(plane: LinePlane, id: String): StrokeRecord? = planes[plane]?.records?.firstOrNull { it.id == id }
    fun nextSeq(plane: LinePlane): Long = (planes[plane]?.records?.maxOfOrNull { it.seq } ?: 0L) + 1L

    /** The tiles a line may draw on (cached by value: an undo hands back an equal copy). */
    fun footprint(record: StrokeRecord): Set<Long> =
        footprints.getOrPut(record) { footprintOf(record, brushes[record.brushId]) }

    /** Widen a line's footprint by tiles the GPU actually drew (never narrow it). */
    fun widen(record: StrokeRecord, drawn: Set<Long>) {
        val known = footprint(record)
        if (!known.containsAll(drawn)) footprints[record] = known + drawn
    }

    /** Is [key] crossed by any of [records] (or by the plane's current lines when null)? */
    fun crossed(plane: LinePlane, key: Long, records: List<StrokeRecord>? = null): Boolean =
        (records ?: planes[plane]?.records.orEmpty()).any { key in footprint(it) }

    /** The lines of [records] that cross [key], seq ascending. */
    fun crossing(records: List<StrokeRecord>, key: Long): List<StrokeRecord> =
        records.filter { key in footprint(it) }.sortedBy { it.seq }

    fun base(plane: LinePlane, key: Long): Int? = planes[plane]?.base?.get(key)

    /** Put a line (null = remove) at its id; keeps seq order. */
    fun put(plane: LinePlane, id: String, record: StrokeRecord?) {
        // Validate before touching anything: a refused put leaves the plane exactly as it was.
        if (record != null) {
            require(record.id == id) { "A line is put at its own id" }
            require(record.seq >= 1) { "A line in a plane has a seq of 1 or more" }
            require(planes[plane]?.records.orEmpty().none { it.seq == record.seq && it.id != id }) { "Two lines cannot share seq ${record.seq}" }
        }
        val p = planes.getOrPut(plane) { Plane() }
        p.records.removeAll { it.id == id }
        if (record != null) {
            p.records.add(record)
            p.records.sortBy { it.seq }
        }
        if (p.records.isEmpty() && p.base.isEmpty()) planes.remove(plane)
        revision++
    }

    /** Set (or with null, drop) a tile's base. Ownership follows the step that carries it, never this map. */
    fun setBase(plane: LinePlane, key: Long, texture: Int?) {
        val p = planes.getOrPut(plane) { Plane() }
        if (texture == null) p.base.remove(key) else p.base[key] = texture
        if (p.records.isEmpty() && p.base.isEmpty()) planes.remove(plane)
    }

    /** A shared plane got its document cel id (the first board document): its lines follow it. */
    fun rename(from: LinePlane, to: LinePlane) {
        if (from == to || from !in planes) return
        require(to !in planes) { "Lines cannot merge into a plane that already has them" }
        planes[to] = planes.remove(from)!!
        revision++
    }

    /** Every base texture this bookkeeping currently names (the live owner of each, for accounting). */
    fun baseTextures(): Set<Int> = planes.values.flatMap { it.base.values }.toSet()

    /** Forget everything (a new drawing). Base textures are released by their steps / the caller. */
    fun clear() {
        planes.clear(); brushes.clear(); footprints.clear()
        revision++
    }

    fun snapshot(): LineSnapshot =
        LineSnapshot(revision, planes.filterValues { it.records.isNotEmpty() }.mapValues { it.value.records.toList() }, brushes)

    companion object {
        /** Extra px round each dab: soft edges, anti-aliasing and the watercolour finish stay inside. */
        private const val PAD_PX = 8f

        /**
         * The tiles a record's replayed dabs can reach, padded: a SUPERSET of what the GPU draws. A record the CPU
         * cannot replay (refused brush) has an empty footprint here; the engine widens it from the GPU's tiles.
         */
        fun cpuFootprint(record: StrokeRecord, preset: BrushPreset?): Set<Long> {
            val dabs = try { InkReplay.replay(record, preset).dabs } catch (e: Exception) { return emptySet() }
            val size = Tiles.SIZE.toFloat()
            val out = HashSet<Long>()
            for (d in dabs) {
                val r = max(d.radius, 0f) + PAD_PX
                val x0 = floor((d.x - r) / size).toInt(); val x1 = floor((d.x + r) / size).toInt()
                val y0 = floor((d.y - r) / size).toInt(); val y1 = floor((d.y + r) / size).toInt()
                for (ty in y0..y1) for (tx in x0..x1) out.add(Tiles.key(tx, ty))
            }
            return out
        }
    }
}

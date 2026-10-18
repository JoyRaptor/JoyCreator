package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.androidkit.lines.LineSnapshot
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_WATERCOLOUR
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import java.util.TreeMap
import java.util.TreeSet
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.math.floor
import kotlin.math.max

/**
 * Editable lines v1 + v1.2 (tasks/joybrush/specs/NATIVE_LINES_V1_20261016.md, NATIVE_LINES_V12_HARDENING_20261017.md):
 * the engine-side bookkeeping beside the ordinary tiles. Pure (no GL): texture names are plain Ints owned as the spec
 * says. GL thread only.
 *
 * A plane holds ITEMS in one time order (seq 1, 2, 3...; never two items at one seq):
 *  - LINES: [StrokeRecord]s, re-rendered from their recording;
 *  - SLABS: pixels a stroke or a Rasterize Down left, one premultiplied texture per tile they cross, each with the
 *    operator it is applied with (normal, erase, behind, multiply, screen).
 *
 * For every tile some item of a plane CROSSES (a line's footprint holds the tile, or a slab has a texture for it), the
 * plane's ordinary tile is exactly
 *   base ∘ (every item crossing it, in seq order).
 * The base (seq 0) is the tile as it was before the first item crossed it. It exists exactly while an item crosses the
 * tile, which every step keeps true (a step that makes a tile crossed records the base; one that makes it uncrossed drops
 * it; undo and redo replay those as slab changes). An ABSENT base on a crossed tile means transparent. Footprints are
 * supersets on purpose: a tile wrongly called crossed only re-renders as base ∘ nothing = itself.
 *
 * Bookkeeping is by IDENTITY, never by value: per plane `recordId → footprint` and `tile → item seqs` (an index kept in
 * step with every put / remove / setSlab), so "which items cross this tile" is a lookup and nothing hashes a recording.
 */
internal class NativeLines(
    /** The brush a record names, from this drawing's frozen copies. */
    private val footprintOf: (StrokeRecord, BrushPreset?) -> Set<Long> = ::cpuFootprint,
) {
    /** One slab as the engine sees it: how it is applied, and the texture it has for each tile it crosses. */
    class Slab(val op: String, val tiles: Map<Long, Int>)

    /** A slab's texture on one tile, at its place in time order. */
    class SlabAt(val seq: Long, val op: String, val texture: Int)

    private class SlabEntry(var op: String) {
        val tiles = HashMap<Long, Int>()
    }

    private class Plane {
        val records = ArrayList<StrokeRecord>()          // seq ascending
        val byId = HashMap<String, StrokeRecord>()
        val bySeq = HashMap<Long, StrokeRecord>()
        val footprints = HashMap<String, Set<Long>>()    // record id → tiles it may draw on
        val base = HashMap<Long, Int>()                   // tile → base texture; absent on a crossed tile = transparent
        val slabs = TreeMap<Long, SlabEntry>()           // seq → slab
        val index = HashMap<Long, TreeSet<Long>>()       // tile → seqs of the items crossing it

        val isEmpty: Boolean get() = records.isEmpty() && base.isEmpty() && slabs.isEmpty()

        fun addToIndex(seq: Long, tiles: Iterable<Long>) {
            for (key in tiles) index.getOrPut(key) { TreeSet() }.add(seq)
        }

        fun removeFromIndex(seq: Long, tiles: Iterable<Long>) {
            for (key in tiles) {
                val seqs = index[key] ?: continue
                seqs.remove(seq)
                if (seqs.isEmpty()) index.remove(key)
            }
        }
    }

    private val planes = LinkedHashMap<LinePlane, Plane>()
    private val brushes = LinkedHashMap<String, BrushPreset>()

    /**
     * Tiles the GPU drew beyond the CPU estimate, by (plane, record id). Remembered so an undo that brings a line back
     * gets its widened footprint back too; only ever grows (a superset is always safe), and only when the GPU surprised us.
     */
    private val extras = HashMap<Pair<LinePlane, String>, Set<Long>>()

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
        if (entries.keys.any { it !in brushes }) { revision++; brushRevision++ }
        brushes.putAll(entries)
    }

    /** Grows only when the drawing's kept brushes change (a freeze answer stays good across ordinary line commits). */
    var brushRevision = 0L
        private set

    /** How many slab tiles (above the bases) every plane holds together: each is a 256 KB texture (v1.2 slab budget). */
    fun slabTileCount(): Int = planes.values.sumOf { p -> p.slabs.values.sumOf { it.tiles.size } }

    // ── lines ────────────────────────────────────────────────────────────────

    fun lines(plane: LinePlane): List<StrokeRecord> = planes[plane]?.records?.toList().orEmpty()
    fun hasLines(plane: LinePlane): Boolean = planes[plane]?.records?.isNotEmpty() == true
    fun hasLinesOnLayer(layerId: String): Boolean = planes.any { (k, v) -> k.layerId == layerId && v.records.isNotEmpty() }
    fun linePlanes(): Set<LinePlane> = planes.filterValues { it.records.isNotEmpty() }.keys.toSet()

    fun record(plane: LinePlane, id: String): StrokeRecord? = planes[plane]?.byId?.get(id)

    /** One more than the highest seq of any item (line or slab) in the plane. */
    fun nextSeq(plane: LinePlane): Long {
        val p = planes[plane] ?: return 1L
        return max(p.records.lastOrNull()?.seq ?: 0L, p.slabs.lastEntry()?.key ?: 0L) + 1L
    }

    /** What the CPU thinks a record may draw on (nothing is stored). A brush the drawing does not hold has none. */
    fun estimate(record: StrokeRecord): Set<Long> = footprintOf(record, brushes[record.brushId])

    /** [estimate] with a brush the drawing may not hold yet (a stroke's frozen brush, before it is committed). */
    fun estimate(record: StrokeRecord, preset: BrushPreset?): Set<Long> = footprintOf(record, preset ?: brushes[record.brushId])

    /** The tiles a stored line may draw on. */
    fun footprint(plane: LinePlane, id: String): Set<Long> = planes[plane]?.footprints?.get(id).orEmpty()

    /** Widen a stored line's footprint by tiles the GPU actually drew (never narrow it). */
    fun widen(plane: LinePlane, id: String, drawn: Set<Long>) {
        val p = planes[plane] ?: return
        val record = p.byId[id] ?: return
        val known = p.footprints[id].orEmpty()
        val added = drawn.filterTo(HashSet()) { it !in known }
        if (added.isEmpty()) return
        p.footprints[id] = known + added
        p.addToIndex(record.seq, added)
        val cpu = estimate(record)
        val beyond = added.filterTo(HashSet()) { it !in cpu }
        if (beyond.isNotEmpty()) extras[plane to id] = extras[plane to id].orEmpty() + beyond
    }

    /** Put a line (null = remove) at its id; keeps seq order. [footprint], when known, replaces the CPU estimate. */
    fun put(plane: LinePlane, id: String, record: StrokeRecord?, footprint: Set<Long>? = null) {
        // Validate before touching anything: a refused put leaves the plane exactly as it was.
        if (record != null) {
            require(record.id == id) { "A line is put at its own id" }
            require(record.seq >= 1) { "A line in a plane has a seq of 1 or more" }
            val clash = planes[plane]?.bySeq?.get(record.seq)
            require(clash == null || clash.id == id) { "Two lines cannot share seq ${record.seq}" }
            require(planes[plane]?.slabs?.containsKey(record.seq) != true) { "A line and a slab cannot share seq ${record.seq}" }
        }
        val p = planes.getOrPut(plane) { Plane() }
        p.byId.remove(id)?.let { old ->
            p.records.remove(old)
            p.bySeq.remove(old.seq)
            p.removeFromIndex(old.seq, p.footprints.remove(id).orEmpty())
        }
        if (record != null) {
            val cpu = lazy(LazyThreadSafetyMode.NONE) { estimate(record) }
            val tiles = if (footprint != null) {
                val extra = footprint.filterTo(HashSet()) { it !in cpu.value }
                if (extra.isNotEmpty()) extras[plane to id] = extras[plane to id].orEmpty() + extra
                footprint
            } else cpu.value + extras[plane to id].orEmpty()
            p.records.add(record)
            p.records.sortBy { it.seq }
            p.byId[id] = record
            p.bySeq[record.seq] = record
            p.footprints[id] = tiles
            p.addToIndex(record.seq, tiles)
        }
        if (p.isEmpty) planes.remove(plane)
        revision++
    }

    // ── bases ────────────────────────────────────────────────────────────────

    fun base(plane: LinePlane, key: Long): Int? = planes[plane]?.base?.get(key)

    /** Every base of the plane, by tile. */
    fun bases(plane: LinePlane): Map<Long, Int> = planes[plane]?.base?.let { HashMap(it) }.orEmpty()

    /** Set (or with null, drop) a tile's base. Ownership follows the step that carries it, never this map. */
    fun setBase(plane: LinePlane, key: Long, texture: Int?) {
        val p = planes.getOrPut(plane) { Plane() }
        if (texture == null) p.base.remove(key) else p.base[key] = texture
        if (p.isEmpty) planes.remove(plane)
    }

    // ── slabs ────────────────────────────────────────────────────────────────

    fun slab(plane: LinePlane, seq: Long): Slab? = planes[plane]?.slabs?.get(seq)?.let { Slab(it.op, HashMap(it.tiles)) }
    fun slabSeqs(plane: LinePlane): List<Long> = planes[plane]?.slabs?.keys?.toList().orEmpty()
    fun hasSlabs(plane: LinePlane): Boolean = planes[plane]?.slabs?.isNotEmpty() == true

    /**
     * Set (or with null, drop) one tile of the slab at [seq]. A slab exists while it has a tile; every tile of one slab
     * shares its operator, so the last [op] named is the slab's. Like bases, texture ownership follows the step.
     */
    fun setSlab(plane: LinePlane, seq: Long, key: Long, texture: Int?, op: String = "normal") {
        require(seq >= 1) { "A slab in a plane has a seq of 1 or more" }
        require(op in OPERATORS) { "Unsupported slab operator \"$op\"" }
        require(planes[plane]?.bySeq?.containsKey(seq) != true) { "A slab and a line cannot share seq $seq" }
        val p = planes.getOrPut(plane) { Plane() }
        if (texture == null) {
            val entry = p.slabs[seq]
            if (entry != null && entry.tiles.remove(key) != null) {
                p.removeFromIndex(seq, listOf(key))
                if (entry.tiles.isEmpty()) p.slabs.remove(seq)
            }
        } else {
            val entry = p.slabs.getOrPut(seq) { SlabEntry(op) }
            entry.op = op
            entry.tiles[key] = texture
            p.addToIndex(seq, listOf(key))
        }
        if (p.isEmpty) planes.remove(plane)
        revision++
    }

    // ── crossing (index lookups) ─────────────────────────────────────────────

    /** Do any items of the plane cross [key]? */
    fun isCrossed(plane: LinePlane, key: Long): Boolean = planes[plane]?.index?.containsKey(key) == true

    /** Every tile some item of the plane crosses. */
    fun crossedKeys(plane: LinePlane): Set<Long> = planes[plane]?.index?.keys?.toSet().orEmpty()

    /** The seqs of the items crossing [key], ascending. */
    fun seqsAt(plane: LinePlane, key: Long): List<Long> = planes[plane]?.index?.get(key)?.toList().orEmpty()

    /** The lines crossing [key], seq ascending. */
    fun linesAt(plane: LinePlane, key: Long): List<StrokeRecord> {
        val p = planes[plane] ?: return emptyList()
        return p.index[key].orEmpty().mapNotNull { p.bySeq[it] }
    }

    /** The slab textures on [key], seq ascending. */
    fun slabsAt(plane: LinePlane, key: Long): List<SlabAt> {
        val p = planes[plane] ?: return emptyList()
        return p.index[key].orEmpty().mapNotNull { seq -> p.slabs[seq]?.let { e -> e.tiles[key]?.let { SlabAt(seq, e.op, it) } } }
    }

    // ── items as a whole ─────────────────────────────────────────────────────

    /** Does the plane hold anything the pixels must obey: a line or a slab? */
    fun hasItems(plane: LinePlane): Boolean = planes[plane]?.let { it.records.isNotEmpty() || it.slabs.isNotEmpty() } == true
    fun hasItemsOnLayer(layerId: String): Boolean = planes.any { (k, v) -> k.layerId == layerId && (v.records.isNotEmpty() || v.slabs.isNotEmpty()) }
    fun itemPlanes(): Set<LinePlane> = planes.filterValues { it.records.isNotEmpty() || it.slabs.isNotEmpty() }.keys.toSet()

    /** A shared plane got its document cel id (the first board document): its items follow it. */
    fun rename(from: LinePlane, to: LinePlane) {
        if (from == to || from !in planes) return
        require(to !in planes) { "Lines cannot merge into a plane that already has them" }
        planes[to] = planes.remove(from)!!
        for (entry in extras.entries.filter { it.key.first == from }) {
            extras.remove(entry.key)
            extras[to to entry.key.second] = entry.value
        }
        revision++
    }

    /** Every base texture this bookkeeping currently names (the live owner of each, for accounting). */
    fun baseTextures(): Set<Int> = planes.values.flatMap { it.base.values }.toSet()

    /** Every texture it names: bases and slab tiles. */
    fun textures(): Set<Int> = planes.values.flatMap { p -> p.base.values + p.slabs.values.flatMap { it.tiles.values } }.toSet()

    /** Forget everything (a new drawing). Textures are released by their steps / the caller. */
    fun clear() {
        planes.clear(); brushes.clear(); extras.clear()
        revision++; brushRevision++
    }

    fun snapshot(): LineSnapshot = LineSnapshot(
        revision,
        planes.filterValues { it.records.isNotEmpty() }.mapValues { it.value.records.toList() },
        brushes,
        itemPlanes(),
        brushRevision = brushRevision,
        slabTiles = slabTileCount(),
    )

    companion object {
        /** The operators a slab may carry; the ones `composeRecordedTile` applies. */
        val OPERATORS = setOf("normal", "erase", "behind", "multiply", "screen")

        /** Slack beyond the brush's reach: soft edges, anti-aliasing and the watercolour finish stay inside. */
        private const val PAD_PX = 16f

        /**
         * The tiles a record may draw on, from its SAMPLES and nothing else (no replay): the path between reports widened by
         *   R = 2 · size · widthScale (+ scatter reach) + 16 px.
         * Every report is stamped with R. Between two reports d apart the path (joined, smoothed or a spline through them)
         * is walked in n steps of at most half a tile, each stamped with R + d/(2n) (so no point of the segment is further
         * than that from a stamp) + d/5 (a curve's bulge between the two reports). A guaranteed superset of what the GPU
         * draws, and cheap enough for every put; fast, sparse strokes cannot skip a tile they cross. A brush the drawing
         * does not hold has an empty footprint. The engine widens it from the GPU's own tiles whenever it renders the line.
         */
        fun cpuFootprint(record: StrokeRecord, preset: BrushPreset?): Set<Long> {
            if (preset == null || record.samples.isEmpty()) return emptySet()
            val reach = reachPx(record, preset)
            val size = Tiles.SIZE.toFloat()
            val out = HashSet<Long>()
            var lastX0 = Int.MIN_VALUE; var lastX1 = Int.MIN_VALUE; var lastY0 = Int.MIN_VALUE; var lastY1 = Int.MIN_VALUE
            fun stamp(x: Float, y: Float, r: Float) {
                val x0 = floor((x - r) / size).toInt(); val x1 = floor((x + r) / size).toInt()
                val y0 = floor((y - r) / size).toInt(); val y1 = floor((y + r) / size).toInt()
                if (x0 == lastX0 && x1 == lastX1 && y0 == lastY0 && y1 == lastY1) return   // the same tiles as the last stamp
                lastX0 = x0; lastX1 = x1; lastY0 = y0; lastY1 = y1
                for (ty in y0..y1) for (tx in x0..x1) out.add(Tiles.key(tx, ty))
            }
            var px = Float.NaN; var py = Float.NaN
            for (s in record.samples) {
                if (!s.x.isFinite() || !s.y.isFinite()) continue
                if (px.isNaN()) stamp(s.x, s.y, reach) else {
                    val dx = s.x - px; val dy = s.y - py
                    val d = sqrt(dx * dx + dy * dy)
                    val n = max(1, ceil(d / (size / 2f)).toInt())
                    val r = reach + d / (2f * n) + 0.2f * d
                    for (i in 1..n) stamp(px + dx * i / n, py + dy * i / n, r)
                }
                px = s.x; py = s.y
            }
            return out
        }

        /** R in doc px; see [cpuFootprint]. */
        fun reachPx(record: StrokeRecord, preset: BrushPreset): Float {
            val scale = record.widthScale.takeIf { it.isFinite() }?.let { abs(it) } ?: 1f
            val wc = if (preset.engine == ENGINE_WATERCOLOUR) preset.watercolour else null
            val size = if (wc != null) max(preset.size.base, wc.size.toFloat()) else preset.size.base
            val scatter = if (wc != null) {
                // The wash spreads by its scatter share of a dab and pools out to its gravity and edge widths.
                wc.scatter.toFloat() * size * 1.5f * scale + wc.gravityWidth.toFloat() + wc.edgeWidth.toFloat()
            } else {
                // A scatter copy lands up to √2 · amount · diameter off the path (Scatter: a square of side amount × diameter).
                preset.scatter.amount.base * 2f * size * scale * 1.5f
            }
            val r = 2f * size * scale + max(scatter, 0f) + PAD_PX
            return if (r.isFinite()) r else PAD_PX
        }
    }
}

package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.math.hypot

/**
 * Media Atlas v2 B3, steps 3-5: where the water is, tile by tile, and what may leave the atlas while it runs
 * (tasks/joybrush/specs/MEDIA_ATLAS_B3_PLAN_20261017.md; the Lead's D1/D2). Pure bookkeeping: [MediaAtlas] feeds it what
 * the GPU measured ([observe]) and where the brush put water ([touched]), and asks it what must stay ([mustStay]) and, when
 * a frame does not fit, what to settle first ([plan]).
 *
 * The rules, and why each keeps the picture exact:
 * - A tile with no water and no dampness ([State.DRY]) is a fixed point of the water: nothing in it changes and it lends
 *   nothing to its neighbours. Away from water it may leave and come back without changing one bit.
 * - Water reads [READ] px around itself (flux taps, the bead blur), and may spread [MARGIN] px between two measurements: the
 *   tiles within READ + MARGIN of where the water is (its extent, measured in 16 px blocks) stay. A pool in the middle of
 *   two tiles keeps those two, not a ring of eight.
 * - Nothing that holds water ever leaves ([State.WET], [State.DAMP], and [State.UNKNOWN] until measured).
 * - Room short, D1: a whole connected component of water (tiles whose water comes within [MARGIN] of each other, wet and
 *   damp together) that has been quiet for [quietMs] dries in place, farthest from the pen first. Never part of one:
 *   cutting connected water would draw a wet/dry seam along a tile edge.
 * - Still short, D2: the oldest water (a drip, or the trail of a long wet stroke with the pen at its young end) loses its
 *   measured tile farthest from the pen, dried in place and taken out of the simulation ([excluded]): the water beside it
 *   sees a wall there (no flow, no dry front), as at the window's edge. Never a tile the frame paints. Logged by the host.
 * - Still short: the frame itself is too big (refused in words, or split in time: step 6).
 */
class MediaAtlasWater(val quietMs: Long = 2000) {
    enum class State { UNKNOWN, WET, DAMP, DRY }

    private class Tile(var state: State, var lastFlowMs: Long, var firstWetMs: Long, var ext: IntArray?, var wetExt: IntArray? = null)

    private val tiles = HashMap<Long, Tile>()

    /** Tiles taken out of the simulation (D2: a drip's dried tail): the water passes skip them until the brush comes back. */
    val excluded: MutableSet<Long> = LinkedHashSet()

    fun state(key: Long): State? = tiles[key]?.state

    /** Where the water lies in [key] (layer px, x0 y0 x1 y1, half-open), or null when it holds none. */
    fun extent(key: Long): IntArray? = tiles[key]?.takeIf { holdsWater(key) }?.let { it.ext ?: rectOf(key) }

    /** The water stopped (dried, undone, layer left): forget everything. */
    fun reset() { tiles.clear(); excluded.clear() }

    /**
     * A tile entered the water's domain. Water reaches a tile only from the brush ([touched]), from its own stores
     * ([stored]) or by running in from water within [MARGIN] of it, so it counts as water (there) until measured in those
     * cases, and as dry otherwise (the far corners of a rectangle grown between two separate pools). Decided from the rules
     * alone, never from what a host happens to hold, so atlases of different sizes decide alike.
     */
    fun entered(key: Long, nowMs: Long, stored: Boolean = false) {
        if (key in tiles) return
        val own = rectOf(key)
        if (stored) { tiles[key] = Tile(State.UNKNOWN, nowMs, nowMs, own); return }
        var ext: IntArray? = null
        val x = Tiles.tx(key); val y = Tiles.ty(key)
        for (dy in -1..1) for (dx in -1..1) {
            val n = Tiles.key(x + dx, y + dy)
            val s = tiles[n]?.state
            if (n == key || n in excluded || (s != State.WET && s != State.DAMP)) continue
            ext = union(ext, intersect(grow(extent(n)!!, MARGIN), own))
        }
        tiles[key] = if (ext != null) Tile(State.UNKNOWN, nowMs, nowMs, ext) else Tile(State.DRY, Long.MIN_VALUE, Long.MAX_VALUE, null)
    }

    /** The brush put water on [keys] (whole tiles): wet and moving now, back in the simulation. */
    fun touched(keys: Collection<Long>, nowMs: Long) { for (k in keys) touched(rectOf(k), nowMs) }

    /** The brush put water within [rect] (layer px, x0 y0 x1 y1): every tile it covers is wet and moving now, there. */
    fun touched(rect: IntArray, nowMs: Long) {
        for (k in MediaAtlasPlacement.tiles(rect[0].toDouble(), rect[1].toDouble(), rect[2].toDouble(), rect[3].toDouble(), 0)) {
            val part = intersect(rect, rectOf(k)) ?: continue
            val old = tiles[k]
            val ext = if (old != null && holdsWater(k)) union(old.ext ?: rectOf(k), part) else part
            val t = old ?: Tile(State.WET, nowMs, nowMs, null).also { tiles[k] = it }
            if (t.state != State.WET) t.firstWetMs = nowMs
            t.state = State.WET; t.lastFlowMs = nowMs; t.ext = ext
            excluded.remove(k)
        }
    }

    /**
     * What the GPU measured over [key] (the activity query): the deepest water [maxW] (mm), the dampest paper [maxS], the
     * fastest outflow [maxFlow] (mm/s), and where there is any ([extent], layer px; null = the whole tile). Water above
     * [minMm] is wet; else dampness above 0 is damp; else dry.
     *
     * Moving: water in this simulation never stops flowing until it is dry (it keeps feeding its drying edge: the dark rim),
     * so "quiet" is not "no flow". Water is moving while the extent of its STANDING water ([wetExtent]: blocks deeper than
     * [minMm]) changes, or anything in it runs faster than [flowEps] (a drip); a pool that has stopped spreading for
     * [quietMs] is quiet. Dampness keeps creeping through the fibres long after (the wick), so it counts for residency
     * ([extent]) but not for motion. Measured in the wet harness: a settling pool's fastest outflow stays at 0.2-0.4 mm/s.
     */
    fun observe(key: Long, maxW: Float, maxS: Float, maxFlow: Float, nowMs: Long, minMm: Float, flowEps: Float, extent: IntArray? = null,
                wetExtent: IntArray? = extent) {
        if (key in excluded) return
        val t = tiles.getOrPut(key) { Tile(State.UNKNOWN, nowMs, nowMs, null) }
        val was = t.state
        val wasWet = t.wetExt
        t.state = when { maxW > minMm -> State.WET; maxS > 0f -> State.DAMP; else -> State.DRY }
        t.ext = if (t.state == State.DRY) null else extent
        t.wetExt = if (t.state == State.WET) wetExtent ?: rectOf(key) else null
        if (t.state == State.WET && was == State.WET && !(wasWet ?: rectOf(key)).contentEquals(t.wetExt!!)) t.lastFlowMs = nowMs
        // Water that appears where there was none ran in: it is moving now, and its age starts now.
        if (t.state != State.DRY && was == State.DRY) { t.firstWetMs = nowMs; t.lastFlowMs = nowMs }
        if (maxFlow > flowEps) t.lastFlowMs = nowMs
    }

    /** Dried in place by the host ([plan]'s actions): no water, no dampness, a fixed point from now on. */
    fun dried(keys: Collection<Long>, drain: Boolean) {
        for (k in keys) {
            val t = tiles.getOrPut(k) { Tile(State.DRY, Long.MIN_VALUE, Long.MAX_VALUE, null) }
            t.state = State.DRY; t.ext = null
            if (drain) excluded.add(k)
        }
    }

    fun holdsWater(key: Long): Boolean = key !in excluded && when (tiles[key]?.state) {
        State.WET, State.DAMP, State.UNKNOWN -> true
        else -> false
    }

    /**
     * The tiles that must stay resident while water runs over [domain] (the simulated rectangle's tiles): those holding
     * water, and every tile within [READ] + [MARGIN] of where it lies, as far as the water reads ([reads]: the rectangle's
     * tiles grown by the wet reach). [lookReach] (layer px; the look's halo plus what it reads): the look is redrawn over
     * the water each frame and when a tail drains, so every tile within that of the water stays too, whether the water reads
     * it or not. A look that read a tile already gone drew wrong edges (b3w, 2026-10-18: 86 px at a drained tail's corner).
     */
    fun mustStay(domain: Collection<Long>, reads: Collection<Long>, lookReach: Int = 0): Set<Long> {
        val readable = reads.toHashSet()
        val out = LinkedHashSet<Long>()
        for (k in domain) {
            val e = extent(k) ?: continue
            out += k
            val g = grow(e, READ + MARGIN)
            for (n in MediaAtlasPlacement.tiles(g[0].toDouble(), g[1].toDouble(), g[2].toDouble(), g[3].toDouble(), 0)) if (n in readable) out += n
            if (lookReach > 0) {
                val l = grow(e, lookReach)
                out += MediaAtlasPlacement.tiles(l[0].toDouble(), l[1].toDouble(), l[2].toDouble(), l[3].toDouble(), 0)
            }
        }
        return out
    }

    /** Of [domain]'s tiles holding water, how many have not flowed for [stillMs] (measurement, for the log). */
    fun stillCount(domain: Collection<Long>, nowMs: Long, stillMs: Long): Int = domain.count { k -> holdsWater(k) && still(k, nowMs, stillMs) }

    /** [key]'s water was measured and has not flowed (nor spread) for [stillMs]. Not measured yet = not still. */
    fun still(key: Long, nowMs: Long, stillMs: Long): Boolean =
        tiles[key]?.let { it.state != State.UNKNOWN && nowMs - it.lastFlowMs >= stillMs } == true

    /** One way to make room ([plan]): dry [keys] in place; [drain] = also take them out of the simulation (D2). */
    data class Action(val keys: List<Long>, val drain: Boolean)

    /**
     * What to settle so that [frame] (this frame's own tiles), [held] (everything else that must stay) and what the water
     * then still needs fit in [capacity]; null when nothing more may settle and it still does not fit. [penX], [penY]:
     * where the pen is (layer px), for "farthest first". The plan is applied in order by the host (and [dried] told).
     */
    fun plan(domain: Collection<Long>, reads: Collection<Long>, frame: Set<Long>, held: Set<Long>, capacity: Int,
             penX: Double, penY: Double, nowMs: Long, lookReach: Int = 0): List<Action>? {
        val actions = ArrayList<Action>()
        // Tentative states, so each step sees the previous ones.
        val saved = tiles.mapValues { it.value.state to it.value.ext }
        val savedExcluded = excluded.toSet()
        fun fits() = (frame + held + mustStay(domain, reads, lookReach)).size <= capacity
        fun dry(k: Long) { tiles[k]?.let { it.state = State.DRY; it.ext = null } }
        try {
            while (!fits()) {
                val comps = components(domain).filter { c -> c.none { it in frame } }
                // D1: a whole quiet component, farthest from the pen first.
                val quiet = comps.filter { c -> c.all { k -> tiles[k]?.state != State.UNKNOWN && nowMs - (tiles[k]?.lastFlowMs ?: nowMs) >= quietMs } }
                val pick = quiet.maxByOrNull { c -> c.minOf { dist(it, penX, penY) } }
                if (pick != null) {
                    actions += Action(pick, false); for (k in pick) dry(k)
                    continue
                }
                // D2 (2026-10-21): the oldest whole pool the frame does not touch, dried where it lies. It used to be the oldest
                // water's tile farthest from the pen, taken out by itself: its water stopped at the tile's straight edge and dried
                // there, the creases on the owner's Note 9 (an S-shaped wash and a sweep back up). Water is only ever settled whole.
                // Water not measured yet (all of it, just after the engine was made again) counts too: else a crowded drawing was
                // refused every frame until its old water dried (2026-10-20).
                val pool = comps.minByOrNull { c -> c.minOf { tiles[it]?.firstWetMs ?: Long.MAX_VALUE } } ?: return null
                actions += Action(pool, false); for (k in pool) dry(k)
            }
            return actions
        } finally {
            for ((k, v) in saved) tiles[k]?.let { it.state = v.first; it.ext = v.second }
            excluded.clear(); excluded.addAll(savedExcluded)
        }
    }

    /** Connected groups of the tiles in [domain] that hold water (8-neighbours whose water comes within [MARGIN]), key order. */
    fun components(domain: Collection<Long>): List<List<Long>> {
        val water = domain.filter { holdsWater(it) }.toHashSet()
        val seen = HashSet<Long>()
        val out = ArrayList<List<Long>>()
        for (start in domain.filter { it in water }.sorted()) {
            if (start in seen) continue
            val comp = ArrayList<Long>(); val todo = ArrayDeque(listOf(start)); seen += start
            while (todo.isNotEmpty()) {
                val k = todo.removeFirst(); comp += k
                val x = Tiles.tx(k); val y = Tiles.ty(k)
                val reach = grow(extent(k)!!, MARGIN)
                for (dy in -1..1) for (dx in -1..1) {
                    val n = Tiles.key(x + dx, y + dy)
                    if (n !in water || n in seen) continue
                    if (intersect(reach, extent(n)!!) == null) continue
                    seen += n; todo.addLast(n)
                }
            }
            out += comp.sorted()
        }
        return out
    }

    private fun dist(key: Long, penX: Double, penY: Double): Double =
        hypot((Tiles.tx(key) + 0.5) * Tiles.SIZE - penX, (Tiles.ty(key) + 0.5) * Tiles.SIZE - penY)

    companion object {
        /** How far water reads around itself (layer px): [MediaAtlasPlacement.WET_REACH]. */
        const val READ = MediaAtlasPlacement.WET_REACH
        /** How far water may spread between two measurements (8 frames, the readback's latency): 48 px. */
        const val MARGIN = 48

        fun rectOf(key: Long): IntArray {
            val x = Tiles.tx(key) * Tiles.SIZE; val y = Tiles.ty(key) * Tiles.SIZE
            return intArrayOf(x, y, x + Tiles.SIZE, y + Tiles.SIZE)
        }
        fun grow(r: IntArray, d: Int) = intArrayOf(r[0] - d, r[1] - d, r[2] + d, r[3] + d)
        fun intersect(a: IntArray, b: IntArray): IntArray? {
            val x0 = maxOf(a[0], b[0]); val y0 = maxOf(a[1], b[1]); val x1 = minOf(a[2], b[2]); val y1 = minOf(a[3], b[3])
            return if (x1 > x0 && y1 > y0) intArrayOf(x0, y0, x1, y1) else null
        }
        fun union(a: IntArray?, b: IntArray?): IntArray? = when {
            a == null -> b
            b == null -> a
            else -> intArrayOf(minOf(a[0], b[0]), minOf(a[1], b[1]), maxOf(a[2], b[2]), maxOf(a[3], b[3]))
        }
    }
}

package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.brush.MEDIUM_DRY
import cc.joycreator.joybrush.core.brush.MEDIUM_PASTE
import cc.joycreator.joybrush.core.brush.MEDIUM_WET
import cc.joycreator.joybrush.core.paint.UndoLog

/**
 * A media layer's float state, as tile stores beside its RGBA8 look (MEDIA_ENGINE_PLAN §4, contract point 2): the look
 * keeps the layer's own id, and each float store is `<layerId>#<store>`.
 *
 * At rest a store is RGBA16F, half floats (the Lead's ruling, 2026-10-07): 8 bytes a texel, 512 KB a tile, saved as
 * `<tx>_<ty>.<store>.f16` (little-endian, [HalfFloat]). The window simulates in full float and rounds once per write-back.
 * Stores are made lazily, by the medium that writes them ([forMedium]): a pencil-only layer has no paint planes, a
 * watercolour layer no paper crush, and the water exists only where it is wet.
 */
object MediaStores {
    /** Paint (two pigment planes), the paper's crush, and the water while wet. */
    val ALL: List<String> = listOf("p0", "p1", "paper", "w0", "w1")

    /** The stores that only exist while the layer is wet. */
    val WATER: List<String> = listOf("w0", "w1")

    /** The archive extension of a store tile, after `<tx>_<ty>.<store>`. */
    const val EXT = ".f16"

    /** Bytes per texel at rest: RGBA, a half float each. */
    const val BYTES_PER_TEXEL = 8

    /** Bytes in one store tile at rest, [tile] × [tile] texels. */
    fun tileBytes(tile: Int): Int = tile * tile * BYTES_PER_TEXEL

    /**
     * The stores a medium WRITES, which are the only ones a stroke of it may create (what it only reads, a missing tile
     * gives as zero). Dry media lay graphite as flakes in the paper store (crush, flake volume, flake reflectance) and pass
     * the paint planes through. Watercolour moves water and pigment. Paste lays paint, and thinned paste carries water too.
     */
    fun forMedium(medium: String, thinned: Boolean = false): List<String> = when (medium) {
        MEDIUM_DRY -> listOf("paper")
        MEDIUM_WET -> listOf("p0", "p1", "w0", "w1")
        MEDIUM_PASTE -> if (thinned) listOf("p0", "p1", "w0", "w1") else listOf("p0", "p1")
        else -> throw IllegalArgumentException("unknown medium $medium")
    }

    fun id(layerId: String, store: String): String = "$layerId#$store"

    /** (layer id, store) for a media store id, or null for anything else (a layer, a mask). */
    fun parse(storeId: String): Pair<String, String>? {
        val i = storeId.lastIndexOf('#')
        if (i <= 0) return null
        val store = storeId.substring(i + 1)
        return if (store in ALL) storeId.substring(0, i) to store else null
    }

    fun isMediaStore(storeId: String): Boolean = parse(storeId) != null

    /** A step that changed media tiles and nothing about the stack: one a later drip may join. */
    fun <T : Any> isMediaStep(s: UndoLog.Step<T>): Boolean =
        s.stackBefore == null && s.stackAfter == null && s.paperBefore == null && s.documentBefore == null &&
            s.changes.any { isMediaStore(it.layerId) }

    /**
     * Records tiles the simulation copied-on-write after pen-up (the Lead's running-water rule, M5.3c): into whatever step
     * is on top when that is a media step and there is nothing to redo; otherwise as a fresh "water" step, which the rest
     * of the episode then joins. One wet episode is one step, never one per frame.
     */
    fun <T : Any> recordWater(log: UndoLog<T>, changes: List<UndoLog.TileChange<T>>) {
        if (changes.isEmpty()) return
        if (!log.extendNewest(changes) { isMediaStep(it) }) log.push(UndoLog.Step(changes))
    }
}

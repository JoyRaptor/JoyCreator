package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.brush.MEDIUM_DRY
import cc.joycreator.joybrush.core.brush.MEDIUM_PASTE
import cc.joycreator.joybrush.core.brush.MEDIUM_WET
import cc.joycreator.joybrush.core.paint.UndoLog

/**
 * The media payload of a pixel layer's tiles (MEDIA_ENGINE_PLAN §4, R51): stores beside the RGBA8 look, which keeps the
 * layer's own id, each `<layerId>#<store>`. Only tiles a media stroke, its water or its drip touched have one.
 *
 * The float stores ([ALL]) rest as RGBA16F, half floats (the Lead's ruling, 2026-10-07): 8 bytes a texel, 512 KB a
 * tile, saved as `<tx>_<ty>.<store>.f16` (little-endian, [HalfFloat]). The window simulates in full float and rounds
 * once per write-back. Stores are made lazily, by the medium that writes them ([forMedium]): a pencil-only tile has no
 * paint planes, a watercolour tile no paper crush, and the water exists only where it is wet.
 *
 * The [GROUND] (JB-2.40 §Q1) is the pixels the media lies on, RGBA8 premultiplied like the look, 4 bytes a texel, saved
 * as `<tx>_<ty>.g.rgba`. A tile with a payload and no ground tile lies on nothing (transparent).
 */
object MediaStores {
    /** Paint (two pigment planes), the paper's crush, and the water while wet. */
    val ALL: List<String> = listOf("p0", "p1", "paper", "w0", "w1")

    /** The pixels under the media: RGBA8, premultiplied, exactly the look the first media touch found (JB-2.40 §Q1). */
    const val GROUND = "g"

    /** Every store of a payload: the float state and the ground. */
    val PAYLOAD: List<String> = ALL + GROUND

    /** The archive extension of a ground tile, after `<tx>_<ty>.g`: the same bytes as a paint tile. */
    const val GROUND_EXT = ".rgba"

    /** The stores that only exist while the layer is wet. */
    val WATER: List<String> = listOf("w0", "w1")

    /** The archive extension of a store tile, after `<tx>_<ty>.<store>`. */
    const val EXT = ".f16"

    /** Bytes per texel at rest: RGBA, a half float each. */
    const val BYTES_PER_TEXEL = 8

    /** Bytes in one float store tile at rest, [tile] × [tile] texels. */
    fun tileBytes(tile: Int): Int = tile * tile * BYTES_PER_TEXEL

    /** Bytes in one tile of [store] at rest: a float store's half floats, or the ground's RGBA8. */
    fun tileBytes(tile: Int, store: String): Int = if (store == GROUND) tile * tile * 4 else tileBytes(tile)

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

    /** (layer id, store) for a payload store id, or null for anything else (a layer, a mask). */
    fun parse(storeId: String): Pair<String, String>? {
        val i = storeId.lastIndexOf('#')
        if (i <= 0) return null
        val store = storeId.substring(i + 1)
        return if (store in PAYLOAD) storeId.substring(0, i) to store else null
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

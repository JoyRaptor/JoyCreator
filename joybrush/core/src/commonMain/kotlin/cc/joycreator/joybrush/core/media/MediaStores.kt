package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.paint.UndoLog

/**
 * A media layer's float state, as tile stores beside its RGBA8 look (MEDIA_ENGINE_PLAN §4, contract point 2): the look
 * keeps the layer's own id, and each float store is `<layerId>#<store>`. Saved as `<tx>_<ty>.<store>.f32` (DocModel v8).
 */
object MediaStores {
    /** Paint (two pigment planes), the paper's crush, and the water while wet. */
    val ALL: List<String> = listOf("p0", "p1", "paper", "w0", "w1")

    /** The stores that only exist while the layer is wet. */
    val WATER: List<String> = listOf("w0", "w1")

    /** Bytes in one saved float tile: RGBA, 32-bit float each, [TILE] × [TILE] texels. */
    fun tileBytes(tile: Int): Int = tile * tile * 4 * 4

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

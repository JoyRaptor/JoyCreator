package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.Layer

/** First cels retain legacy undo addresses; other cels have collision-free, internal-only IDs. */
object CelProjection {
    fun storeId(layer: Layer, celId: String): String {
        require(layer.cels.any { it.id == celId })
        return if (layer.cels.first().id == celId) layer.id
        else "\u0000cel:${layer.id.length}:${layer.id}$celId"
    }

    fun frameId(doc: JbDocument, requested: String?): String? {
        val board = doc.boards.single()
        return board.frames.firstOrNull { it.id == requested }?.id ?: board.frames.firstOrNull()?.id
    }

    /** Blank/link frames allocate no paint; copying must leave room for a complete CPU save. */
    fun canAddTiles(existing: Int, additional: Int, heapBytes: Long): Boolean {
        require(existing >= 0 && additional >= 0)
        val limit = ((heapBytes - 64L * 1024 * 1024) / TILE_BYTES).coerceAtLeast(1)
        return additional == 0 || existing.toLong() + additional <= limit
    }
}

package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.core.paint.Tiles

/**
 * Editable lines v1.2 H7: how much GPU memory a Select edit may ask for. An edit renders every line it touches into a
 * texture per tile (a "unit"), then composes each affected tile, and all of that is alive at once. It is estimated BEFORE
 * anything is rendered, so a phone is never taken to the edge half way through an edit.
 */
internal object LineEditBudget {
    /** One RGBA8 tile. */
    const val TILE_BYTES: Long = Tiles.SIZE.toLong() * Tiles.SIZE * 4

    /** Above this an edit is refused in words. */
    const val LIMIT_BYTES: Long = 192L * 1024 * 1024

    const val MESSAGE = "That's too many lines to change at once on this phone; choose fewer."

    /** The units' tiles plus the tiles composed (and the bases copied) from them, in bytes. */
    fun bytes(unitTiles: Long, composedTiles: Long): Long = (unitTiles + composedTiles) * TILE_BYTES

    /** null = the edit fits. */
    fun refusal(bytes: Long): String? = if (bytes > LIMIT_BYTES) MESSAGE else null
}

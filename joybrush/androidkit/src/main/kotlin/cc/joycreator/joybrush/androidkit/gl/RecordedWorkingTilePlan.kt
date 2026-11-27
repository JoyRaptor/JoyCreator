package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.paint.Tiles

/**
 * Conservative additional working-tile bytes, before any replay/composition allocation.
 * These pure bounds do not inventory existing owners, reserve memory, or authorize rendering.
 */
internal object RecordedWorkingTilePlan {
    /**
     * Full canonical replay/pooling plus all planned placed outputs. The potential watercolor
     * empty helper is charged even for a stamp or empty replay; source images are never cropped
     * to reduce the source working set before pooling completes.
     */
    fun replay(sourceTileCount: Int, destinationTileCount: Int = 0, tileSize: Int = Tiles.SIZE): Long {
        require(sourceTileCount >= 0 && destinationTileCount >= 0) { "Replay tile counts must be nonnegative" }
        val area = area(tileSize)
        val source = bytes(sourceTileCount, 32L, area)
        val destination = bytes(destinationTileCount, 4L, area)
        return Math.addExact(Math.addExact(source, destination), Math.multiplyExact(8L, area))
    }

    /**
     * Bound the COMPLETE output batch before the first compose: prior copies survive while
     * one tile uses two RGBA32F scratch targets. An empty batch allocates no scratch or output.
     */
    fun compose(outputTileCount: Int, tileSize: Int = Tiles.SIZE): Long {
        require(outputTileCount >= 0) { "Composition tile count must be nonnegative" }
        val area = area(tileSize)
        if (outputTileCount == 0) return 0L
        return Math.addExact(bytes(outputTileCount, 4L, area), Math.multiplyExact(32L, area))
    }

    private fun area(tileSize: Int): Long {
        require(tileSize > 0) { "Working tile size must be positive" }
        return Math.multiplyExact(tileSize.toLong(), tileSize.toLong())
    }

    private fun bytes(tileCount: Int, bytesPerTexel: Long, area: Long): Long =
        Math.multiplyExact(Math.multiplyExact(tileCount.toLong(), area), bytesPerTexel)
}

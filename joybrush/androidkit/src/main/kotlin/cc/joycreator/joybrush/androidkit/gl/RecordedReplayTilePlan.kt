package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.paint.TipMath
import java.util.Collections
import java.util.LinkedHashSet
import kotlin.math.floor

/** Pure, bounded address planning for a canonical recorded replay. */
internal object RecordedReplayTilePlan {
    fun keys(dabs: List<Dab>, maxTiles: Int, tileSize: Int = Tiles.SIZE): Set<Long> {
        require(maxTiles >= 0) { "Tile budget must be non-negative" }
        require(tileSize > 0) { "Tile size must be positive" }

        val minTile = -((Int.MAX_VALUE.toLong() + 1L) / tileSize)
        val maxTile = Math.floorDiv(Int.MAX_VALUE.toLong() + 1L - tileSize, tileSize.toLong())
        val result = LinkedHashSet<Long>()

        for (dab in dabs) {
            require(dab.x.isFinite() && dab.y.isFinite() && dab.radius.isFinite() && dab.radius >= 0f) {
                "Invalid replay dab coordinates or radius"
            }
            val extent = TipMath.extent(dab.radius, dab.anchor)
            require(extent.isFinite() && extent >= 0f) { "Invalid replay dab extent" }

            // Keep the Float operations and floor semantics used by Tiles.touchedBy, but validate
            // the entire tile range before that helper narrows coordinates or allocates a list.
            val x0 = floor(((dab.x - extent) / tileSize).toDouble()).toLong()
            val x1 = floor(((dab.x + extent) / tileSize).toDouble()).toLong()
            val y0 = floor(((dab.y - extent) / tileSize).toDouble()).toLong()
            val y1 = floor(((dab.y + extent) / tileSize).toDouble()).toLong()
            val rawBounds = listOf(dab.x - extent, dab.x + extent, dab.y - extent, dab.y + extent)
            require(rawBounds.all { it.isFinite() } && x0 >= minTile && x1 <= maxTile &&
                y0 >= minTile && y1 <= maxTile && x0 <= x1 && y0 <= y1) {
                "Replay dab exceeds Int-addressable full-tile bounds"
            }

            val width = x1 - x0 + 1L
            val height = y1 - y0 + 1L
            require(width > 0L && height > 0L && width <= maxTiles.toLong() &&
                height <= maxTiles.toLong() && width <= maxTiles.toLong() / height) {
                "Replay dab footprint exceeds tile budget"
            }

            for (key in Tiles.touchedBy(dab, tileSize)) {
                require(key in result || result.size < maxTiles) { "Replay tile union exceeds tile budget" }
                result.add(key)
            }
        }

        return Collections.unmodifiableSet(LinkedHashSet(result))
    }
}

package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.stroke.CapturedPhysicalSource
import java.util.LinkedHashMap

/** Independent CPU pixel oracle for the first captured-source copy path. */
internal object RecordedCapturedPixelOracle {
    private const val MAX_SOURCE_TILES = 64
    private const val MAX_OUTPUT_TILES = 256

    /**
     * Copies every in-domain texel from existing canonical RGBA8 tiles after exact integer
     * placement. Tile presence is preserved even when every copied byte is zero.
     */
    fun place(
        canonical: Map<Long, ByteArray>,
        source: CapturedPhysicalSource,
        tileSize: Int = Tiles.SIZE,
        maxOutputTiles: Int = MAX_OUTPUT_TILES,
    ): Map<Long, ByteArray> {
        require(tileSize > 0) { "Oracle tile size must be positive" }
        require(canonical.size <= MAX_SOURCE_TILES) { "Oracle source tile limit exceeded" }
        require(maxOutputTiles in 1..MAX_OUTPUT_TILES) { "Oracle output tile limit is invalid" }
        val area = Math.multiplyExact(tileSize.toLong(), tileSize.toLong())
        val tileBytes = Math.multiplyExact(area, 4L)
        require(tileBytes <= Int.MAX_VALUE) { "Oracle tile byte array is too large" }
        val expectedBytes = tileBytes.toInt()
        require(canonical.values.all { it.size == expectedBytes }) { "Every oracle source must be one RGBA8 tile" }

        val size = tileSize.toLong()
        val edgeMax = Int.MAX_VALUE.toLong() + 1L
        val placedTiles = LinkedHashMap<Long, ByteArray>()
        for ((sourceKey, pixels) in canonical) {
            val sourceTx = Tiles.tx(sourceKey)
            val sourceTy = Tiles.ty(sourceKey)
            val sourceLeft = sourceTx.toLong() * size
            val sourceTop = sourceTy.toLong() * size
            val sourceRight = sourceLeft + size
            val sourceBottom = sourceTop + size
            require(sourceLeft >= Int.MIN_VALUE.toLong() && sourceTop >= Int.MIN_VALUE.toLong() &&
                sourceRight <= edgeMax && sourceBottom <= edgeMax) {
                "Oracle source tile exceeds addressable pixels"
            }

            for (localY in 0 until tileSize) {
                val worldY = sourceTop + localY
                for (localX in 0 until tileSize) {
                    val worldX = sourceLeft + localX
                    if (!inside(source.domain.window, worldX, worldY) ||
                        source.domain.excluded.any { contains(it, worldX, worldY) }) continue

                    val destinationX = worldX + source.placement.dx.toLong()
                    val destinationY = worldY + source.placement.dy.toLong()
                    require(destinationX in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() &&
                        destinationY in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                        "Oracle placed pixel exceeds addressable coordinates"
                    }
                    val destinationTx = Math.floorDiv(destinationX, size)
                    val destinationTy = Math.floorDiv(destinationY, size)
                    require(destinationTx in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() &&
                        destinationTy in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                        "Oracle destination tile key exceeds Int coordinates"
                    }
                    val destinationKey = Tiles.key(destinationTx.toInt(), destinationTy.toInt())
                    var destination = placedTiles[destinationKey]
                    if (destination == null) {
                        require(placedTiles.size < maxOutputTiles) { "Oracle output tile limit exceeded" }
                        destination = ByteArray(expectedBytes)
                        placedTiles[destinationKey] = destination
                    }
                    val destinationLocalX = (destinationX - destinationTx * size).toInt()
                    val destinationLocalY = (destinationY - destinationTy * size).toInt()
                    val sourceOffset = (localY * tileSize + localX) * 4
                    val destinationOffset = (destinationLocalY * tileSize + destinationLocalX) * 4
                    for (channel in 0 until 4) destination[destinationOffset + channel] = pixels[sourceOffset + channel]
                }
            }
        }
        return placedTiles
    }

    private fun inside(rect: cc.joycreator.joybrush.core.stroke.CaptureRect?, x: Long, y: Long): Boolean =
        rect == null || contains(rect, x, y)

    private fun contains(rect: cc.joycreator.joybrush.core.stroke.CaptureRect, x: Long, y: Long): Boolean =
        x >= rect.x.toLong() && y >= rect.y.toLong() && x < rect.right && y < rect.bottom
}

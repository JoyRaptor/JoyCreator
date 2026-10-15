package cc.joycreator.joybrush.core.stroke

import cc.joycreator.joybrush.core.paint.Tiles

/**
 * Handle-free exact texel-copy plan for an ALREADY fully rendered/pooled canonical source.
 * Only supplied source tile keys are queried; no raw bounds, current boards, pixels or GL occur.
 * Source keys are allocation support, not proof of nonzero alpha. Transparent texels inside a
 * surviving rectangle are copied too. A missing source tile never manufactures a destination.
 *
 * Input collections must be stable during [prepare]. The returned collections have immutable
 * private backing. Domains/placements/rectangles already are immutable values.
 */
class CapturedSourceCopyPlan private constructor(
    sourceKeys: List<Long>,
    val source: CapturedPhysicalSource,
    val tileSize: Int,
    destinationKeys: List<Long>,
    copies: List<Copy>,
) {
    /** Same-size half-open tile-LOCAL rectangles. No blending, scaling or opacity is implied. */
    data class Copy(val sourceKey: Long, val destinationKey: Long, val from: CaptureRect, val to: CaptureRect) {
        init {
            require(from.x >= 0 && from.y >= 0 && to.x >= 0 && to.y >= 0 && from.w == to.w && from.h == to.h) {
                "A captured copy needs nonnegative local origins and equal extents"
            }
        }
    }

    /**
     * Explicit planning budgets, distinct from CPU dab generation and GPU working-set bytes.
     * Defaults bound descriptor storage and target count for the first narrow adapter; they do
     * not authorize replay, promise editability or replace runtime memory/context admission.
     * Exceeding any budget refuses the WHOLE plan, never a truncated source.
     */
    data class Limits(val maxSourceTiles: Int = 64, val maxDestinationTiles: Int = 64, val maxCopies: Int = 16384) {
        init {
            require(maxSourceTiles > 0 && maxDestinationTiles > 0 && maxCopies > 0) { "Captured copy limits must be positive" }
        }
    }

    val sourceKeys: Set<Long> = FrozenKeySet(sourceKeys)
    val destinationKeys: Set<Long> = FrozenKeySet(destinationKeys)
    val copies: List<Copy> = FrozenCopies(copies)

    companion object {
        private val keyOrder = compareBy<Long>({ Tiles.ty(it) }, { Tiles.tx(it) })

        /**
         * Source tiles must be wholly addressable, including MAX_VALUE + 1 exclusive edges.
         * Only surviving domain fragments need an addressable translated extent. A destination
         * tile may have unused padding outside that extent; every copied pixel is addressable.
         * Long arithmetic and floor division preserve the negative pixel grid exactly.
         */
        fun prepare(sourceKeys: Set<Long>, source: CapturedPhysicalSource, tileSize: Int = Tiles.SIZE,
                    limits: Limits = Limits()): CapturedSourceCopyPlan {
            require(tileSize > 0) { "A captured copy needs a positive tile size" }
            require(sourceKeys.size.toLong() <= limits.maxSourceTiles.toLong()) { "Captured copy source tile budget exceeded" }
            val ordered = sourceKeys.sortedWith(keyOrder)
            val destinations = LinkedHashSet<Long>()
            val copies = ArrayList<Copy>()
            val size = tileSize.toLong()
            for (sourceKey in ordered) {
                val tile = CaptureRect.tile(Tiles.tx(sourceKey), Tiles.ty(sourceKey), tileSize)
                for (fragment in source.domain.clip(tile)) {
                    val placed = source.placement.sourceToDestination(fragment)
                    var y = 0L
                    while (y < placed.h.toLong()) {
                        val worldY = placed.y.toLong() + y
                        val ty = worldY.floorDiv(size)
                        val localY = worldY - ty * size
                        val height = minOf(placed.h.toLong() - y, size - localY)
                        var x = 0L
                        while (x < placed.w.toLong()) {
                            val worldX = placed.x.toLong() + x
                            val tx = worldX.floorDiv(size)
                            val localX = worldX - tx * size
                            val width = minOf(placed.w.toLong() - x, size - localX)
                            require(tx in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() &&
                                ty in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Captured destination tile key overflow" }
                            val destinationKey = Tiles.key(tx.toInt(), ty.toInt())
                            val fromX = fragment.x.toLong() - tile.x + x
                            val fromY = fragment.y.toLong() - tile.y + y
                            // Validate local Long endpoints BEFORE narrowing to CaptureRect Ints.
                            require(width > 0 && height > 0 && fromX >= 0 && fromY >= 0 && localX >= 0 && localY >= 0 &&
                                fromX + width <= size && fromY + height <= size &&
                                localX + width <= size && localY + height <= size) { "Captured copy exceeds a local tile" }
                            // Refuse before growing either result collection. No world-area array.
                            require(copies.size.toLong() + 1L <= limits.maxCopies.toLong()) { "Captured copy descriptor budget exceeded" }
                            if (destinationKey !in destinations) {
                                require(destinations.size.toLong() + 1L <= limits.maxDestinationTiles.toLong()) {
                                    "Captured copy destination tile budget exceeded"
                                }
                                destinations.add(destinationKey)
                            }
                            val from = CaptureRect(fromX.toInt(), fromY.toInt(), width.toInt(), height.toInt())
                            val to = CaptureRect(localX.toInt(), localY.toInt(), width.toInt(), height.toInt())
                            copies.add(Copy(sourceKey, destinationKey, from, to))
                            x += width
                        }
                        y += height
                    }
                }
            }
            return CapturedSourceCopyPlan(ordered, source, tileSize, destinations.sortedWith(keyOrder), copies)
        }
    }

    private class FrozenCopies(values: List<Copy>) : AbstractList<Copy>() {
        private val items = values.toTypedArray()
        override val size: Int get() = items.size
        override fun get(index: Int): Copy = items[index]
    }

    private class FrozenKeySet(values: List<Long>) : AbstractSet<Long>() {
        private val items = values.toLongArray()
        override val size: Int get() = items.size
        override fun iterator(): Iterator<Long> = object : Iterator<Long> {
            private var index = 0
            override fun hasNext(): Boolean = index < items.size
            override fun next(): Long {
                if (!hasNext()) throw NoSuchElementException()
                return items[index++]
            }
        }
    }
}

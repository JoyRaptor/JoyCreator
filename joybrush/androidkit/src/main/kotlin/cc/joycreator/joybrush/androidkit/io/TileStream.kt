package cc.joycreator.joybrush.androidkit.io

/**
 * A map of tiles whose bytes are made or read only when a save writes them (JBX-36, streaming saves).
 *
 * A save asks for each tile's length first ([sizeOf]: every check before the first byte is written uses lengths only),
 * then for its bytes once, in the archive's own order ([take]), and says when it has written them and kept nothing
 * ([written]). So a save holds one tile at a time, however big the drawing: the source can read the next tiles from the
 * graphics chip or from a file while the save writes, and drop each as soon as it is written.
 *
 * A map that is not a [TileStream] is saved as before: its bytes are all in memory already.
 */
interface TileStream<K> {
    /** How long [key]'s tile is, without making or reading it; null when there is no such tile. */
    fun sizeOf(key: K): Int?

    /** [key]'s bytes. A save asks once per tile, in the order it writes them. */
    fun take(key: K): ByteArray

    /** The save has written [key]'s bytes (or decided not to need them) and holds no reference to them. */
    fun written(key: K) {}
}

/**
 * [base] with [extra] tiles added that are not in it (a save's stand-ins for tiles it will take from the older file),
 * without copying [base]: a [TileStream] stays one, and a plain map is not read twice.
 */
internal class TilesWithExtra<K>(
    private val base: Map<K, ByteArray>,
    private val extra: Map<K, ByteArray>,
) : AbstractMap<K, ByteArray>(), TileStream<K> {
    private val all: Set<K> = LinkedHashSet<K>(base.keys).apply { addAll(extra.keys) }

    @Suppress("UNCHECKED_CAST")
    private val stream: TileStream<K>? get() = base as? TileStream<K>

    override val size: Int get() = all.size
    override val keys: Set<K> get() = all
    override fun containsKey(key: K): Boolean = key in all
    override fun get(key: K): ByteArray? = extra[key] ?: base[key]

    override fun sizeOf(key: K): Int? = extra[key]?.size ?: stream?.sizeOf(key) ?: base[key]?.size
    override fun take(key: K): ByteArray = extra[key] ?: stream?.take(key) ?: base.getValue(key)
    override fun written(key: K) { if (key !in extra) stream?.written(key) }

    override val entries: Set<Map.Entry<K, ByteArray>> = object : AbstractSet<Map.Entry<K, ByteArray>>() {
        override val size: Int get() = all.size
        override fun iterator(): Iterator<Map.Entry<K, ByteArray>> = all.iterator().let { keys ->
            object : Iterator<Map.Entry<K, ByteArray>> {
                override fun hasNext() = keys.hasNext()
                override fun next(): Map.Entry<K, ByteArray> {
                    val k = keys.next()
                    return java.util.AbstractMap.SimpleImmutableEntry(k, get(k)!!)
                }
            }
        }
    }
}

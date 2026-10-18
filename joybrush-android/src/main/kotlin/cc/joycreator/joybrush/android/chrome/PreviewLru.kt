package cc.joycreator.joybrush.android.chrome

/**
 * A least-recently-used map held to a byte budget, for the pictures the drawer has shown lately. Reading a key makes it the
 * newest; putting past the budget drops the oldest until it fits. One value larger than the whole budget is kept alone
 * (the picture on screen must stay), and replaces everything else. Not thread-safe: the UI thread owns it.
 */
class PreviewLru<K : Any, V : Any>(private val maxBytes: Long, private val sizeOf: (V) -> Long) {

    private val map = LinkedHashMap<K, V>(16, 0.75f, true)
    private var bytes = 0L

    val size: Int get() = map.size
    val totalBytes: Long get() = bytes

    /** The keys, oldest first (for tests and logs). */
    fun keys(): List<K> = map.keys.toList()

    operator fun get(key: K): V? = map[key]

    fun put(key: K, value: V) {
        map.put(key, value)?.let { bytes -= sizeOf(it) }
        bytes += sizeOf(value)
        val it = map.entries.iterator()
        while (bytes > maxBytes && map.size > 1 && it.hasNext()) {
            val oldest = it.next()
            if (oldest.key == key) continue
            bytes -= sizeOf(oldest.value)
            it.remove()
        }
    }

    fun remove(key: K) {
        map.remove(key)?.let { bytes -= sizeOf(it) }
    }

    fun clear() {
        map.clear()
        bytes = 0L
    }
}

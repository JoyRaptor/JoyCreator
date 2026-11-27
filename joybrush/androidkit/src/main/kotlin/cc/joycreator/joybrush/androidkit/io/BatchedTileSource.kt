package cc.joycreator.joybrush.androidkit.io

/**
 * The tiles of one save, read back a batch at a time on the graphics thread while the save thread writes them (JBX-36
 * S2). The tiles were copied on the graphics chip when the save began, so the drawing is free to change meanwhile and
 * the file is still the drawing as it was at that instant.
 *
 * [order] is every tile in the order the archive writes them (pictures, then paint stores, each in the archive's own
 * order); a tile in [held] is already in memory (it was read the old way) and the rest are read by [read] on the graphics
 * thread, which [post] reaches. At most two batches of about [batchBytes] are in memory at once: the one being written
 * and the next. [pictures] and [media] are the save's two tile maps.
 *
 * When the reading stops (the graphics went, the screen stopped drawing, a tile could not be read) the save is refused
 * with [JbArchiveNeedsBytes]: nothing is written, the older file stays, and the screen reads the whole drawing the old
 * way. [finish] (the archive calls it when the save ends, whichever way) lets go of every copy not read yet.
 */
internal class BatchedTileSource(
    private val order: List<Any>,
    private val sizes: Map<Any, Int>,
    private val held: Map<Any, ByteArray>,
    batchBytes: Long,
    private val post: (() -> Unit) -> Unit,
    private val read: (Any) -> ByteArray,
    private val drop: () -> Unit,
    private val alive: () -> Boolean = { true },
    private val waitMs: Long = 10_000L,
) {
    private val position = HashMap<Any, Int>(order.size * 2).apply { order.forEachIndexed { i, k -> put(k, i) } }
    private val batchOf = IntArray(order.size)
    private val batches: List<IntRange>
    init {
        val made = ArrayList<IntRange>()
        var start = 0; var bytes = 0L
        for (i in order.indices) {
            val n = if (order[i] in held) 0 else sizes.getValue(order[i])
            if (i > start && bytes + n > batchBytes) { made.add(start until i); start = i; bytes = 0L }
            bytes += n
            batchOf[i] = made.size
        }
        if (order.isNotEmpty()) made.add(start until order.size)
        batches = made
    }

    private val lock = Object()
    private val ready = HashMap<Any, ByteArray>()
    private val asked = BooleanArray(batches.size)
    private var failure: String? = null
    private var finished = false

    /** Most tile bytes ever waiting in memory at once (tests and the bench). */
    @Volatile var mostBytesWaiting = 0L
        private set
    private var waiting = 0L

    private fun ask(b: Int) {
        if (b >= batches.size) return
        synchronized(lock) { if (asked[b] || finished) return; asked[b] = true }
        post {
            for (i in batches[b]) {
                val key = order[i]
                if (key in held) continue
                synchronized(lock) { if (finished || failure != null) return@post }
                val bytes = try { read(key) } catch (e: Throwable) {
                    synchronized(lock) { if (failure == null) failure = e.message ?: e.javaClass.simpleName; lock.notifyAll() }
                    return@post
                }
                synchronized(lock) {
                    if (finished) return@post
                    ready[key] = bytes
                    waiting += bytes.size
                    if (waiting > mostBytesWaiting) mostBytesWaiting = waiting
                    lock.notifyAll()
                }
            }
        }
    }

    private fun take(key: Any): ByteArray {
        held[key]?.let { return it }
        val i = position[key] ?: throw JbArchiveException("a tile was asked for that this save did not freeze")
        val b = batchOf[i]
        ask(b); ask(b + 1)
        val deadline = System.currentTimeMillis() + waitMs
        synchronized(lock) {
            while (true) {
                ready.remove(key)?.let { waiting -= it.size; return it }
                failure?.let { throw JbArchiveNeedsBytes("the save's tiles could not be read back: $it") }
                if (finished) throw JbArchiveNeedsBytes("the save ended before its tiles were read")
                val left = deadline - System.currentTimeMillis()
                if (left <= 0 || !alive()) throw JbArchiveNeedsBytes("the drawing stopped answering while it was being saved")
                lock.wait(minOf(left, 250L))
            }
        }
    }

    /** The save has ended: the copies not read yet are let go, on the graphics thread. */
    fun finish() {
        synchronized(lock) {
            if (finished) return
            finished = true
            ready.clear(); waiting = 0
            lock.notifyAll()
        }
        post { drop() }
    }

    private inner class View<K>(private val keys0: Set<K>) : AbstractMap<K, ByteArray>(), TileStream<K> {
        override val keys: Set<K> get() = keys0
        override val size: Int get() = keys0.size
        override fun containsKey(key: K): Boolean = key in keys0
        override fun get(key: K): ByteArray? = if (key in keys0) take(key as Any) else null
        override fun sizeOf(key: K): Int? = if (key in keys0) (held[key as Any]?.size ?: sizes[key as Any]) else null
        override fun take(key: K): ByteArray = this@BatchedTileSource.take(key as Any)
        override fun finished() = finish()
        override val entries: Set<Map.Entry<K, ByteArray>> get() =
            throw UnsupportedOperationException("a streamed save's tiles are taken one at a time, never walked")
        // Never by walking the tiles: a JbContents that holds this map can be logged and hashed.
        override fun toString(): String = "${keys0.size} streamed tiles"
        override fun hashCode(): Int = System.identityHashCode(this)
        override fun equals(other: Any?): Boolean = this === other
    }

    @Suppress("UNCHECKED_CAST")
    val pictures: Map<Triple<String, String, String>, ByteArray> =
        View(order.filterIsInstanceTo(LinkedHashSet<Triple<String, String, String>>()))

    val media: Map<MediaTileKey, ByteArray> = View(order.filterIsInstanceTo(LinkedHashSet<MediaTileKey>()))
}

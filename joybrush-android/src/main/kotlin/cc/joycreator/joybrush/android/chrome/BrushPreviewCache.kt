package cc.joycreator.joybrush.android.chrome

import cc.joycreator.joybrush.androidkit.preview.BrushPreviewStyle
import cc.joycreator.joybrush.core.brush.BrushPreset
import java.io.File
import java.util.concurrent.Executor

/** A way to call a request off. Safe to call more than once and after the picture has arrived. */
fun interface PreviewCancel {
    fun cancel()
}

/** What the drawer asks for a brush's picture. [onReady] runs on the UI thread, at once if the picture is in memory. */
fun interface PreviewSource<V : Any> {
    fun request(preset: BrushPreset, width: Int, height: Int, style: BrushPreviewStyle, onReady: (V) -> Unit): PreviewCancel
}

/** Makes a picture on a miss: `done` is called on the UI thread, with null if none could be made. */
fun interface PreviewMaker<V : Any> {
    fun make(preset: BrushPreset, width: Int, height: Int, style: BrushPreviewStyle, done: (V?) -> Unit): PreviewCancel
}

/** How a picture goes to and from a file, and what it costs in memory. */
interface PreviewCodec<V : Any> {
    /** Writes [value] to [file]; false if it could not. Runs on the IO thread. */
    fun write(value: V, file: File): Boolean
    /** Reads a picture of exactly [width] x [height] from [file], or null if it is missing, damaged or another size. IO thread. */
    fun read(file: File, width: Int, height: Int): V?
    fun bytes(value: V): Long
}

/**
 * The brush drawer's pictures, made once per brush version (owner, 2026-10-17: previews drawn by the real engine). A request
 * is answered from memory (a small LRU), else from the folder (read off the UI thread), else it is made by [maker] on the
 * canvas's GL thread and then kept in both. The stored files are named by [BrushPreviewKey], so a tuned brush gets a new
 * picture and its old one is deleted; the folder is held to [limits].
 *
 * Threads: [request], the callbacks and [maker] are the UI thread's; file reads and writes go to [io], one at a time. Two
 * requests for one picture share one piece of work, and the work is called off only when every one of them has been.
 */
class BrushPreviewCache<V : Any>(
    private val dir: File,
    private val maker: PreviewMaker<V>,
    private val codec: PreviewCodec<V>,
    private val ui: Executor,
    private val io: Executor,
    memoryBytes: Long = 8L shl 20,
    private val limits: PreviewDiskTrim.Limits = PreviewDiskTrim.Limits(),
    private val clock: () -> Long = System::currentTimeMillis,
) : PreviewSource<V> {

    private val memory = PreviewLru<String, V>(memoryBytes, codec::bytes)

    private class Waiter<V : Any>(val onReady: (V) -> Unit)

    private inner class Flight(val key: BrushPreviewKey.Key, val preset: BrushPreset, val style: BrushPreviewStyle) {
        val waiters = ArrayList<Waiter<V>>()
        var making: PreviewCancel? = null
    }

    private val flights = HashMap<String, Flight>()

    /** How many pictures are being fetched or made right now (for tests). */
    val inFlight: Int get() = flights.size

    /** Pictures held in memory (for tests). */
    val inMemory: Int get() = memory.size

    override fun request(preset: BrushPreset, width: Int, height: Int, style: BrushPreviewStyle, onReady: (V) -> Unit): PreviewCancel {
        val key = BrushPreviewKey.of(preset, width, height, style)
        memory[key.stem]?.let { onReady(it); return PreviewCancel { } }
        val waiter = Waiter(onReady)
        val existing = flights[key.stem]
        val flight = existing ?: Flight(key, preset, style).also { flights[key.stem] = it }
        flight.waiters.add(waiter)
        if (existing == null) io.execute { fetch(flight) }
        return PreviewCancel { leave(flight, waiter) }
    }

    /** Forgets every picture held in memory (the folder is left alone). */
    fun trimMemory() = memory.clear()

    // ---- inside ----

    /** IO thread: the stored picture, if there is one. */
    private fun fetch(f: Flight) {
        val file = File(dir, f.key.fileName)
        val found = try { if (file.isFile) codec.read(file, f.key.width, f.key.height) else null } catch (e: Exception) { null }
        if (found != null) file.setLastModified(clock())
        ui.execute { fetched(f, found) }
    }

    /** UI thread. */
    private fun fetched(f: Flight, found: V?) {
        if (found != null) {
            memory.put(f.key.stem, found)
            arrive(f, found)
            return
        }
        // Everyone gave up while the folder was read: nothing to make.
        if (flights[f.key.stem] !== f) return
        f.making = maker.make(f.preset, f.key.width, f.key.height, f.style) { picture -> madeBy(f, picture) }
    }

    /** UI thread: the maker is done. A picture is kept even when nobody waits for it any more. */
    private fun madeBy(f: Flight, picture: V?) {
        if (picture == null) {
            if (flights[f.key.stem] === f) flights.remove(f.key.stem)
            return
        }
        memory.put(f.key.stem, picture)
        io.execute { store(f.key, picture) }
        arrive(f, picture)
    }

    private fun arrive(f: Flight, picture: V) {
        if (flights[f.key.stem] === f) flights.remove(f.key.stem)
        val waiting = f.waiters.toList()
        f.waiters.clear()
        for (w in waiting) w.onReady(picture)
    }

    private fun leave(f: Flight, w: Waiter<V>) {
        if (!f.waiters.remove(w) || f.waiters.isNotEmpty()) return
        // The last one has gone: call the work off, and let the next request start it afresh.
        if (flights[f.key.stem] === f) flights.remove(f.key.stem)
        f.making?.cancel()
        f.making = null
    }

    /** IO thread: write the picture, then throw out what the folder no longer needs. */
    private fun store(key: BrushPreviewKey.Key, picture: V) {
        try {
            dir.mkdirs()
            val temp = File(dir, key.fileName + ".tmp")
            val target = File(dir, key.fileName)
            if (!codec.write(picture, temp)) { temp.delete(); return }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) { temp.delete(); return }
            target.setLastModified(clock())
            val listing = dir.listFiles()?.filter { it.isFile }?.map { PreviewDiskTrim.Entry(it.name, it.length(), it.lastModified()) }.orEmpty()
            for (name in PreviewDiskTrim.victims(listing, key, limits)) File(dir, name).delete()
        } catch (e: Exception) {
            // A full or read-only cache folder costs the next open a re-draw, nothing more.
        }
    }
}

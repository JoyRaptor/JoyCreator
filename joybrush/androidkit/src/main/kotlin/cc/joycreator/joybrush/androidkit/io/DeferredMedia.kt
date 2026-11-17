package cc.joycreator.joybrush.androidkit.io

import java.io.Closeable
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipFile

/**
 * Tiles of a drawing that are still in its file ([JbArchive.openDeferringMedia]): a map like the ones
 * [JbContents.mediaTiles] and [JbContents.tiles] always were, whose values are read from the file each time they are
 * asked for and are not kept. Its keys, its size, what it contains and how long each tile is ([sizeOf]) cost nothing;
 * walking its entries reads one tile at a time.
 *
 * Every tile was read and checked once already when the file was opened. What is handed out here is checked against
 * that reading (the same length, the same CRC): bytes that differ are never handed out, the read fails with a
 * [JbArchiveException] instead.
 *
 * The file is held open ([Source], shared by the maps of one drawing), so it stays readable when its path is deleted or
 * given another file. [close] lets this map's hold go; a read after the last hold is gone opens the path again, and
 * fails in words when the file there is no longer the one that was checked.
 */
internal class DeferredMedia<K>(
    private val source: Source,
    private val noted: Map<K, Entry>,
) : AbstractMap<K, ByteArray>(), Closeable {

    /** One tile as the checking pass met it: where it is in the zip, how long it was and the CRC of its bytes. */
    class Entry(val name: String, val size: Int, val crc: Long)

    /** The open file the maps of one drawing read from. Closed when the last map that holds it lets go. */
    class Source(val file: File, private var zip: ZipFile?) {
        private var holds = 0
        @Synchronized fun hold() { holds++ }
        @Synchronized fun release() {
            if (holds > 0) holds--
            if (holds == 0) { try { zip?.close() } catch (_: Exception) {}; zip = null }
        }
        @Synchronized fun read(e: Entry): ByteArray {
            try {
                val z = zip ?: ZipFile(file).also { zip = it }
                val entry = z.getEntry(e.name) ?: throw JbArchiveException("\"${e.name}\" is no longer in ${file.name}")
                val out = ByteArray(e.size)
                z.getInputStream(entry).use { input ->
                    var at = 0
                    while (at < out.size) {
                        val n = input.read(out, at, out.size - at)
                        if (n < 0) break
                        at += n
                    }
                    if (at != out.size || input.read() >= 0) throw JbArchiveException("\"${e.name}\" is not the ${e.size} bytes it was when ${file.name} was checked")
                }
                if (CRC32().apply { update(out) }.value != e.crc) throw JbArchiveException("\"${e.name}\" changed since ${file.name} was checked")
                return out
            } catch (x: JbArchiveException) {
                throw x
            } catch (x: Exception) {
                throw JbArchiveException("\"${e.name}\" of ${file.name} cannot be read again: ${x.javaClass.simpleName}")
            } finally {
                // Nobody holds the file any more (a read after the maps let go): it is not kept open for the next one.
                if (holds == 0) { try { zip?.close() } catch (_: Exception) {}; zip = null }
            }
        }
    }

    private var held = true
    init { source.hold() }

    /** The file the drawing was read from and checked in. */
    val file: File get() = source.file

    /** How long [key]'s tile is, as the checking pass read it, without reading it again; null when there is none. */
    fun sizeOf(key: K): Int? = noted[key]?.size

    override val size: Int get() = noted.size
    override val keys: Set<K> get() = noted.keys
    override fun containsKey(key: K): Boolean = noted.containsKey(key)
    override fun isEmpty(): Boolean = noted.isEmpty()
    override fun get(key: K): ByteArray? = noted[key]?.let(source::read)

    override val entries: Set<Map.Entry<K, ByteArray>> = object : AbstractSet<Map.Entry<K, ByteArray>>() {
        override val size: Int get() = noted.size
        override fun iterator(): Iterator<Map.Entry<K, ByteArray>> {
            val under = noted.entries.iterator()
            return object : Iterator<Map.Entry<K, ByteArray>> {
                override fun hasNext() = under.hasNext()
                override fun next(): Map.Entry<K, ByteArray> {
                    val (k, e) = under.next()
                    return object : Map.Entry<K, ByteArray> {
                        override val key = k
                        override val value: ByteArray get() = source.read(e)
                    }
                }
            }
        }
    }

    @Synchronized
    override fun close() {
        if (held) { held = false; source.release() }
    }

    companion object {
        private val STAND_INS = HashMap<Int, ByteArray>()

        /** Notes [bytes] (already checked by the reader) for the second reading, and gives a stand-in of its size to hold meanwhile. */
        fun <K> note(into: MutableMap<K, Entry>, key: K, name: String, bytes: ByteArray): ByteArray {
            into[key] = Entry(name, bytes.size, CRC32().apply { update(bytes) }.value)
            return synchronized(STAND_INS) { STAND_INS.getOrPut(bytes.size) { ByteArray(bytes.size) } }
        }

        /** True when the zip's own directory says of every noted tile what the checking pass read: its length and its CRC. */
        fun agrees(zip: ZipFile, noted: Map<*, Entry>): Boolean = noted.values.all { e ->
            val entry = zip.getEntry(e.name)
            entry != null && !entry.isDirectory && entry.size == e.size.toLong() && entry.crc == e.crc
        }

        /** The length of [key]'s tile in [map] without reading a deferred one. */
        fun <K> sizeIn(map: Map<K, ByteArray>, key: K): Int? =
            if (map is DeferredMedia<*>) @Suppress("UNCHECKED_CAST") (map as DeferredMedia<K>).sizeOf(key) else map[key]?.size

        /** The file [contents] still reads from, when any of its tiles are deferred. */
        fun fileOf(contents: JbContents): File? =
            (contents.mediaTiles as? DeferredMedia<*>)?.file ?: (contents.tiles as? DeferredMedia<*>)?.file

        /** Lets go of every hold [contents] has on its file. */
        fun release(contents: JbContents) {
            (contents.mediaTiles as? Closeable)?.close(); (contents.tiles as? Closeable)?.close()
        }
    }
}

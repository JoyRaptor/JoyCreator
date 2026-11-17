package cc.joycreator.joybrush.androidkit.io

import java.io.Closeable
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipFile

/**
 * A drawing's realistic-paint tiles, still in its file ([JbArchive.openDeferringMedia]): a map like the one
 * [JbContents.mediaTiles] always was, whose values are read from the file each time they are asked for and are not kept.
 * Its keys, its size and what it contains cost nothing; walking its entries reads one tile at a time.
 *
 * Every tile was read and checked once already when the file was opened. What is handed out here is checked against
 * that reading (the same length, the same CRC): bytes that differ are never handed out, the read fails with a
 * [JbArchiveException] instead.
 *
 * The file is held open, so it stays readable when its path is deleted or given another file. [close] lets it go;
 * a read after that opens the path again, and fails in words when the file there is no longer the one that was checked.
 */
internal class DeferredMedia(
    /** The file the drawing was read from and checked in. */
    val file: File,
    private var zip: ZipFile?,
    private val noted: Map<MediaTileKey, Entry>,
) : AbstractMap<MediaTileKey, ByteArray>(), Closeable {

    /** One media tile as the checking pass met it: where it is in the zip, how long it was and the CRC of its bytes. */
    class Entry(val name: String, val size: Int, val crc: Long)

    override val size: Int get() = noted.size
    override val keys: Set<MediaTileKey> get() = noted.keys
    override fun containsKey(key: MediaTileKey): Boolean = noted.containsKey(key)
    override fun isEmpty(): Boolean = noted.isEmpty()
    override fun get(key: MediaTileKey): ByteArray? = noted[key]?.let(::read)

    override val entries: Set<Map.Entry<MediaTileKey, ByteArray>> = object : AbstractSet<Map.Entry<MediaTileKey, ByteArray>>() {
        override val size: Int get() = noted.size
        override fun iterator(): Iterator<Map.Entry<MediaTileKey, ByteArray>> {
            val under = noted.entries.iterator()
            return object : Iterator<Map.Entry<MediaTileKey, ByteArray>> {
                override fun hasNext() = under.hasNext()
                override fun next(): Map.Entry<MediaTileKey, ByteArray> {
                    val (k, e) = under.next()
                    return object : Map.Entry<MediaTileKey, ByteArray> {
                        override val key = k
                        override val value: ByteArray get() = read(e)
                    }
                }
            }
        }
    }

    @Synchronized
    private fun read(e: Entry): ByteArray {
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
        }
    }

    @Synchronized
    override fun close() {
        try { zip?.close() } catch (_: Exception) {}
        zip = null
    }

    companion object {
        private val STAND_INS = HashMap<Int, ByteArray>()

        /** Notes [bytes] (already checked by the reader) for the second reading, and gives a stand-in of its size to hold meanwhile. */
        fun note(into: MutableMap<MediaTileKey, Entry>, key: MediaTileKey, name: String, bytes: ByteArray): ByteArray {
            into[key] = Entry(name, bytes.size, CRC32().apply { update(bytes) }.value)
            return synchronized(STAND_INS) { STAND_INS.getOrPut(bytes.size) { ByteArray(bytes.size) } }
        }

        /** True when the zip's own directory says of every noted tile what the checking pass read: its length and its CRC. */
        fun agrees(zip: ZipFile, noted: Map<MediaTileKey, Entry>): Boolean = noted.values.all { e ->
            val entry = zip.getEntry(e.name)
            entry != null && !entry.isDirectory && entry.size == e.size.toLong() && entry.crc == e.crc
        }
    }
}

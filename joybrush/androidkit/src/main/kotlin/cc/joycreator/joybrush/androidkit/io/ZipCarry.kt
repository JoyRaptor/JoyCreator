package cc.joycreator.joybrush.androidkit.io

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * A zip written entry by entry where an entry is either deflated fresh from its bytes or CARRIED from an older zip as its
 * compressed bytes lie there, untouched: the file-writing half of a save that rewrites only what changed
 * (tasks/jobs/ATLAS-SOLID_never_refuse.md, "Streaming saves: what is left").
 *
 * Why our own writer: a `.joybrush` file is a plain zip, and a zip's entries can be copied from file to file byte for
 * byte, but `java.util.zip` only ever inflates and deflates again. On the Note 9 deflating was most of a save: 128 MB of
 * paint stores and a page of pictures took 9 s to write (2026-11-15), every save, though a stroke changes a few tiles.
 *
 * What it writes is an ordinary zip any reader opens (`ZipInputStream`, `ZipFile`, a desktop tool): each entry's sizes
 * and checksum stand in its own header (no data descriptors), names are UTF-8, there is no zip64 (a `.joybrush` file
 * stays far below 4 GB and 65,535 entries: [JbArchive] refuses more than 20,000 entries and 1 GiB), no encryption and no
 * comments. [index] reads the same and what `ZipOutputStream` makes (data descriptors included), and refuses the rest.
 *
 * Pure JVM; no thread of its own.
 */
internal object ZipCarry {

    /** One entry of an existing zip: what its headers say and where its (compressed) data lies in the file. */
    class Kept(val name: String, val method: Int, val crc: Long, val compressedSize: Long, val size: Long, val dataOffset: Long)

    const val STORED = 0
    const val DEFLATED = 8

    private const val SIG_LOCAL = 0x04034b50L
    private const val SIG_CENTRAL = 0x02014b50L
    private const val SIG_END = 0x06054b50L
    private const val FLAG_ENCRYPTED = 0x0001
    private const val FLAG_UTF8 = 0x0800
    /** 1980-01-01 00:00, the zip format's zero: every entry carries it, so the same drawing makes the same file. */
    private const val DOS_DATE = 0x0021
    private const val DOS_TIME = 0x0000
    private const val MAX_ENTRIES = 65_534
    private const val MAX_U32 = 0xFFFFFFFFL

    /**
     * The entries of [file] by name, in the order its directory lists them. Throws [JbArchiveException] when the file is
     * not a zip this writer can carry from: no end record, zip64, several disks, an encrypted entry, a method other than
     * stored or deflated, a name twice, or a header that points outside the file.
     */
    fun index(file: File): LinkedHashMap<String, Kept> = RandomAccessFile(file, "r").use { raf -> index(raf) }

    fun index(raf: RandomAccessFile): LinkedHashMap<String, Kept> {
        val length = raf.length()
        if (length < 22) throw JbArchiveException("not a zip: too short")
        // The end record is the last thing in the file, before a comment of at most 65,535 bytes.
        val tailLen = minOf(length, 22L + 65_535L).toInt()
        val tail = ByteArray(tailLen)
        raf.seek(length - tailLen)
        raf.readFully(tail)
        var end = -1
        for (i in tailLen - 22 downTo 0) if (u32(tail, i) == SIG_END) { end = i; break }
        if (end < 0) throw JbArchiveException("not a zip: no end record")
        val disk = u16(tail, end + 4); val cdDisk = u16(tail, end + 6)
        val here = u16(tail, end + 8); val total = u16(tail, end + 10)
        val cdSize = u32(tail, end + 12); val cdOffset = u32(tail, end + 16)
        if (disk != 0 || cdDisk != 0 || here != total) throw JbArchiveException("a zip in several parts cannot be carried from")
        if (total == 0xFFFF || cdSize == MAX_U32 || cdOffset == MAX_U32) throw JbArchiveException("a zip64 file cannot be carried from")
        if (cdOffset + cdSize > length) throw JbArchiveException("the zip's directory lies outside the file")
        val cd = ByteArray(cdSize.toInt())
        raf.seek(cdOffset)
        raf.readFully(cd)
        val out = LinkedHashMap<String, Kept>(total * 2)
        var p = 0
        val local = ByteArray(30)
        repeat(total) {
            if (p + 46 > cd.size || u32(cd, p) != SIG_CENTRAL) throw JbArchiveException("the zip's directory is damaged")
            val flags = u16(cd, p + 8); val method = u16(cd, p + 10)
            val crc = u32(cd, p + 16); val csize = u32(cd, p + 20); val size = u32(cd, p + 24)
            val n = u16(cd, p + 28); val m = u16(cd, p + 30); val k = u16(cd, p + 32)
            val localOffset = u32(cd, p + 42)
            if (p + 46 + n + m + k > cd.size) throw JbArchiveException("the zip's directory is damaged")
            val name = String(cd, p + 46, n, Charsets.UTF_8)
            p += 46 + n + m + k
            if (flags and FLAG_ENCRYPTED != 0) throw JbArchiveException("an encrypted zip entry cannot be carried: $name")
            if (method != STORED && method != DEFLATED) throw JbArchiveException("zip entry $name uses method $method")
            if (csize == MAX_U32 || size == MAX_U32 || localOffset == MAX_U32) throw JbArchiveException("a zip64 entry cannot be carried: $name")
            if (localOffset + 30 > cdOffset) throw JbArchiveException("zip entry $name starts outside the file")
            raf.seek(localOffset)
            raf.readFully(local)
            if (u32(local, 0) != SIG_LOCAL) throw JbArchiveException("zip entry $name has no header where the directory says")
            val dataOffset = localOffset + 30 + u16(local, 26) + u16(local, 28)
            if (dataOffset + csize > cdOffset) throw JbArchiveException("zip entry $name runs outside the file")
            if (out.put(name, Kept(name, method, crc, csize, size, dataOffset)) != null) throw JbArchiveException("zip entry $name is there twice")
        }
        return out
    }

    /**
     * Writes one zip to [out] (not closed, and not flushed until [finish]). Entries appear in the order they are added;
     * [finish] writes the directory and must be called once, last.
     */
    class Writer(private val out: OutputStream) {
        private class Rec(val name: ByteArray, val method: Int, val crc: Long, val csize: Long, val size: Long, val offset: Long)
        private val recs = ArrayList<Rec>()
        private val names = HashSet<String>()
        private var written = 0L
        private var finished = false
        private val head = ByteArray(46)
        private val copy = ByteArray(64 * 1024)
        private var deflater: Deflater? = null
        private val scratch = ByteArrayOutputStream(1 shl 20)

        /** How many bytes have gone to the stream so far. */
        val bytesWritten: Long get() = written

        /**
         * An entry made from [bytes]: deflated at [level] (0..9), or stored as it is when [level] is negative. Answers the
         * CRC-32 of the bytes written for it (the compressed ones), which a later save checks before carrying them.
         */
        fun fresh(name: String, bytes: ByteArray, level: Int): Long {
            check(!finished) { "this zip is finished" }
            val crc = CRC32().also { it.update(bytes) }.value
            if (level < 0) {
                begin(name, STORED, crc, bytes.size.toLong(), bytes.size.toLong())
                put(bytes, bytes.size)
                return crc
            }
            val d = deflater ?: Deflater(level, true).also { deflater = it }
            d.reset()
            d.setLevel(level)
            d.setInput(bytes)
            d.finish()
            scratch.reset()
            while (!d.finished()) { val n = d.deflate(copy); scratch.write(copy, 0, n) }
            begin(name, DEFLATED, crc, scratch.size().toLong(), bytes.size.toLong())
            scratch.writeTo(out)
            written += scratch.size()
            val made = scratch.toByteArray()
            return CRC32().also { it.update(made) }.value
        }

        /**
         * An entry whose compressed bytes the caller already holds (read from an older zip and checked): the first [n] of
         * [compressed], with what the older zip's directory said of it.
         */
        fun packed(kept: Kept, compressed: ByteArray, n: Int) {
            require(n.toLong() == kept.compressedSize) { "${kept.name}: $n bytes given for ${kept.compressedSize}" }
            begin(kept.name, kept.method, kept.crc, kept.compressedSize, kept.size)
            put(compressed, n)
        }

        /** An entry carried from another zip: [kept]'s compressed bytes, copied from [from] as they lie there. */
        fun carry(kept: Kept, from: RandomAccessFile) {
            begin(kept.name, kept.method, kept.crc, kept.compressedSize, kept.size)
            from.seek(kept.dataOffset)
            var left = kept.compressedSize
            while (left > 0) {
                val n = from.read(copy, 0, minOf(left, copy.size.toLong()).toInt())
                if (n <= 0) throw JbArchiveException("the older file ended inside ${kept.name}")
                put(copy, n)
                left -= n
            }
        }

        /** The directory and the end record. Nothing may be added after it. */
        fun finish() {
            check(!finished) { "this zip is finished" }
            finished = true
            deflater?.end()
            if (recs.size > MAX_ENTRIES) throw JbArchiveException("too many entries for one file: ${recs.size}")
            val cdOffset = written
            for (r in recs) {
                le32(head, 0, SIG_CENTRAL); le16(head, 4, 20); le16(head, 6, if (r.method == STORED) 10 else 20)
                le16(head, 8, FLAG_UTF8); le16(head, 10, r.method); le16(head, 12, DOS_TIME); le16(head, 14, DOS_DATE)
                le32(head, 16, r.crc); le32(head, 20, r.csize); le32(head, 24, r.size)
                le16(head, 28, r.name.size); le16(head, 30, 0); le16(head, 32, 0); le16(head, 34, 0); le16(head, 36, 0)
                le32(head, 38, 0); le32(head, 42, r.offset)
                put(head, 46)
                put(r.name, r.name.size)
            }
            val cdSize = written - cdOffset
            if (cdOffset >= MAX_U32 || cdSize >= MAX_U32) throw JbArchiveException("the file is too large for one zip")
            le32(head, 0, SIG_END); le16(head, 4, 0); le16(head, 6, 0); le16(head, 8, recs.size); le16(head, 10, recs.size)
            le32(head, 12, cdSize); le32(head, 16, cdOffset); le16(head, 20, 0)
            put(head, 22)
            out.flush()
        }

        private fun begin(name: String, method: Int, crc: Long, csize: Long, size: Long) {
            check(!finished) { "this zip is finished" }
            if (!names.add(name)) throw JbArchiveException("zip entry $name is there twice")
            val n = name.toByteArray(Charsets.UTF_8)
            if (n.isEmpty() || n.size > 0xFFFF) throw JbArchiveException("a zip entry's name must be 1 to 65,535 bytes")
            if (csize >= MAX_U32 || size >= MAX_U32 || written >= MAX_U32) throw JbArchiveException("the file is too large for one zip")
            recs += Rec(n, method, crc, csize, size, written)
            le32(head, 0, SIG_LOCAL); le16(head, 4, if (method == STORED) 10 else 20); le16(head, 6, FLAG_UTF8); le16(head, 8, method)
            le16(head, 10, DOS_TIME); le16(head, 12, DOS_DATE); le32(head, 14, crc); le32(head, 18, csize); le32(head, 22, size)
            le16(head, 26, n.size); le16(head, 28, 0)
            put(head, 30)
            put(n, n.size)
        }

        private fun put(b: ByteArray, n: Int) { out.write(b, 0, n); written += n }
    }

    private fun u16(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, i: Int): Long = u16(b, i).toLong() or (u16(b, i + 2).toLong() shl 16)
    private fun le16(b: ByteArray, i: Int, v: Int) { b[i] = v.toByte(); b[i + 1] = (v ushr 8).toByte() }
    private fun le32(b: ByteArray, i: Int, v: Long) { le16(b, i, (v and 0xFFFF).toInt()); le16(b, i + 2, ((v ushr 16) and 0xFFFF).toInt()) }
}

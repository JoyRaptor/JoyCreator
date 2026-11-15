package cc.joycreator.joybrush.androidkit.io

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [ZipCarry]: what it writes is a zip every reader opens, and a carried entry is the same bytes it was. */
class ZipCarryTest {
    private fun temp(name: String) = File.createTempFile(name, ".zip").also { it.deleteOnExit() }

    private fun noise(n: Int, seed: Long) = ByteArray(n).also { Random(seed).nextBytes(it) }
    /** Like a paint store tile: runs of nothing, runs of slowly changing values. */
    private fun paintLike(n: Int, seed: Long): ByteArray {
        val r = Random(seed)
        val b = ByteArray(n)
        var i = 0
        while (i < n) {
            val run = 64 + r.nextInt(4096)
            val v = if (r.nextInt(3) == 0) 0 else r.nextInt(256)
            for (j in i until minOf(n, i + run)) b[j] = (v + (j and 3)).toByte()
            i += run
        }
        return b
    }

    /** Every entry of [bytes] as the stream reader sees it, in order. */
    private fun readStream(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; out += e.name to z.readBytes() }
        }
        return out
    }

    /** Every entry of [file] as the random-access reader sees it, by name. */
    private fun readFile(file: File): Map<String, ByteArray> = ZipFile(file).use { z ->
        z.entries().asSequence().associate { e -> e.name to z.getInputStream(e).readBytes() }
    }

    /** A zip as [JbArchive] writes it today: `mimetype` first and stored, the rest deflated with data descriptors. */
    private fun stockZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, bytes) in entries) {
                val e = ZipEntry(name)
                if (name == "mimetype") {
                    e.method = ZipEntry.STORED; e.size = bytes.size.toLong(); e.compressedSize = bytes.size.toLong()
                    e.crc = java.util.zip.CRC32().also { it.update(bytes) }.value
                }
                z.putNextEntry(e); z.write(bytes); z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    @Test fun freshEntriesMakeAZipBothReadersOpenInTheOrderWritten() {
        val entries = listOf("mimetype" to "application/x-joybrush".toByteArray(), "document.json" to "{\"a\":1}".toByteArray(),
            "layers/a/c/0_0.rgba" to paintLike(262_144, 1), "layers/a/c/0_0.p0.f16" to paintLike(524_288, 2),
            "empty" to ByteArray(0), "noise" to noise(70_000, 3))
        val bos = ByteArrayOutputStream()
        val w = ZipCarry.Writer(bos)
        for ((name, bytes) in entries) w.fresh(name, bytes, if (name == "mimetype") -1 else if (name.endsWith(".f16")) 1 else 6)
        w.finish()
        val zip = bos.toByteArray()
        assertEquals(zip.size.toLong(), w.bytesWritten)
        val seen = readStream(zip)
        assertEquals(entries.map { it.first }, seen.map { it.first })
        for (i in entries.indices) assertContentEquals(entries[i].second, seen[i].second, entries[i].first)
        val f = temp("fresh").also { it.writeBytes(zip) }
        val byName = readFile(f)
        for ((name, bytes) in entries) assertContentEquals(bytes, byName[name], name)
        // The file is identified by its first bytes without inflating anything (JbArchive's rule for `mimetype`).
        assertEquals("mimetype", String(zip, 30, 8, Charsets.US_ASCII))
        assertEquals("application/x-joybrush", String(zip, 38, 22, Charsets.US_ASCII))
    }

    @Test fun entriesAreCarriedFromAStockZipAsTheyLieAndTheRestWrittenFresh() {
        val old = listOf("mimetype" to "application/x-joybrush".toByteArray(), "document.json" to "{\"v\":1}".toByteArray(),
            "t/0_0.rgba" to paintLike(262_144, 10), "t/1_0.rgba" to paintLike(262_144, 11), "t/0_0.p0.f16" to paintLike(524_288, 12),
            "t/1_0.p0.f16" to noise(524_288, 13), "gone" to noise(1000, 14))
        val oldFile = temp("old").also { it.writeBytes(stockZip(old)) }
        val index = ZipCarry.index(oldFile)
        assertEquals(old.map { it.first }, index.keys.toList())
        for ((name, bytes) in old) assertEquals(bytes.size.toLong(), index.getValue(name).size, name)

        val changed = paintLike(262_144, 99)
        val added = paintLike(524_288, 98)
        val bos = ByteArrayOutputStream()
        RandomAccessFile(oldFile, "r").use { raf ->
            val w = ZipCarry.Writer(bos)
            w.fresh("mimetype", old[0].second, -1)
            w.fresh("document.json", "{\"v\":2}".toByteArray(), 6)
            w.carry(index.getValue("t/0_0.rgba"), raf)
            w.fresh("t/1_0.rgba", changed, 6)
            w.carry(index.getValue("t/1_0.p0.f16"), raf)     // carried out of the old order: any order is allowed
            w.carry(index.getValue("t/0_0.p0.f16"), raf)
            w.fresh("t/2_0.p0.f16", added, 1)
            w.finish()
        }
        val want = listOf("mimetype" to old[0].second, "document.json" to "{\"v\":2}".toByteArray(), "t/0_0.rgba" to old[2].second,
            "t/1_0.rgba" to changed, "t/1_0.p0.f16" to old[5].second, "t/0_0.p0.f16" to old[4].second, "t/2_0.p0.f16" to added)
        val seen = readStream(bos.toByteArray())
        assertEquals(want.map { it.first }, seen.map { it.first })
        for (i in want.indices) assertContentEquals(want[i].second, seen[i].second, want[i].first)
        val newFile = temp("new").also { it.writeBytes(bos.toByteArray()) }
        val byName = readFile(newFile)
        assertEquals(want.map { it.first }.toSet(), byName.keys)
        for ((name, bytes) in want) assertContentEquals(bytes, byName[name], name)

        // A second generation: carried again from the file this writer made.
        val index2 = ZipCarry.index(newFile)
        val bos2 = ByteArrayOutputStream()
        RandomAccessFile(newFile, "r").use { raf ->
            val w = ZipCarry.Writer(bos2)
            for ((name, _) in want) if (name == "mimetype") w.fresh(name, old[0].second, -1) else w.carry(index2.getValue(name), raf)
            w.finish()
        }
        val again = readStream(bos2.toByteArray())
        for (i in want.indices) assertContentEquals(want[i].second, again[i].second, "second generation ${want[i].first}")
    }

    @Test fun aCarriedEntryCostsNoDeflatingItIsTheSameCompressedBytes() {
        val tile = paintLike(524_288, 5)
        val oldFile = temp("same").also { it.writeBytes(stockZip(listOf("mimetype" to "x".toByteArray(), "a.f16" to tile))) }
        val kept = ZipCarry.index(oldFile).getValue("a.f16")
        val compressed = ByteArray(kept.compressedSize.toInt())
        RandomAccessFile(oldFile, "r").use { it.seek(kept.dataOffset); it.readFully(compressed) }
        val bos = ByteArrayOutputStream()
        RandomAccessFile(oldFile, "r").use { raf -> ZipCarry.Writer(bos).also { it.carry(kept, raf); it.finish() } }
        val made = bos.toByteArray()
        // local header (30) + the name, then the compressed bytes exactly as they lay.
        assertContentEquals(compressed, made.copyOfRange(30 + 5, 30 + 5 + compressed.size))
        assertTrue(kept.compressedSize < tile.size / 2, "the stand-in for paint deflates: ${kept.compressedSize} of ${tile.size}")
    }

    @Test fun whatIsNotAZipItCanCarryFromIsRefusedInWords() {
        assertFailsWith<JbArchiveException> { ZipCarry.index(temp("short").also { it.writeBytes(ByteArray(10)) }) }
        assertFailsWith<JbArchiveException> { ZipCarry.index(temp("noise").also { it.writeBytes(noise(5000, 7)) }) }
        // A file cut short: the directory is gone.
        val whole = stockZip(listOf("mimetype" to "x".toByteArray(), "a" to paintLike(100_000, 8)))
        assertFailsWith<JbArchiveException> { ZipCarry.index(temp("cut").also { it.writeBytes(whole.copyOf(whole.size - 40)) }) }
        // The same name twice is refused by the writer, as the archive's own rules would.
        val w = ZipCarry.Writer(ByteArrayOutputStream())
        w.fresh("a", ByteArray(3), 6)
        assertFailsWith<JbArchiveException> { w.fresh("a", ByteArray(3), 6) }
        w.finish()
        assertFailsWith<IllegalStateException> { w.fresh("b", ByteArray(3), 6) }
    }

    @Test fun anEmptyZipIsStillAZip() {
        val bos = ByteArrayOutputStream()
        ZipCarry.Writer(bos).finish()
        assertEquals(22, bos.size())
        assertTrue(readStream(bos.toByteArray()).isEmpty())
        assertTrue(ZipCarry.index(temp("empty").also { it.writeBytes(bos.toByteArray()) }).isEmpty())
    }
}

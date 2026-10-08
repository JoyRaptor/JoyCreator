package cc.joycreator.joybrush.core.bench

import cc.joycreator.joybrush.core.stroke.StrokeCodec
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import java.util.zip.Deflater

/** Platform compression and monotonic clock stay out of commonMain. */
object R51JvmMetrics {
    data class Storage(val strokeRawBytes: Int, val strokeDeflatedBytes: Int,
        val tileRawBytes: Long, val tileDeflatedBytes: Long, val nonEmptyTiles: Int,
        val coveredPixels: Long)

    /** Tiles must be the same frame as records, supplied by the landed InkTiles renderer. */
    fun storage(records: List<StrokeRecord>, tiles: List<ByteArray>): Storage {
        require(tiles.all { it.size == 256 * 256 * 4 })
        val bundle = StrokeCodec.encodeAll(records)
        return Storage(bundle.size, deflatedEntry(bundle).size,
            tiles.sumOf { it.size.toLong() }, tiles.sumOf { deflatedEntry(it).size.toLong() },
            tiles.size, tiles.sumOf { tile ->
                (3 until tile.size step 4).count { tile[it] != 0.toByte() }.toLong()
            })
    }

    /** JbArchive: each tile and each cel's encodeAll stroke bundle is a ZIP entry, level 6.
     * Count raw deflate entry payloads, excluding ZIP headers, paths and document.json equally.
     * StrokeCodec.encodeAll supplies the cel bundle; tiles retain separate dictionaries.
     */
    fun deflatedEntry(bytes: ByteArray): ByteArray {
        val compressor = Deflater(6, true)
        return try {
            compressor.setInput(bytes)
            compressor.finish()
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!compressor.finished()) {
                val n = compressor.deflate(buffer)
                check(n > 0) { "deflater stalled" }
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        } finally { compressor.end() }
    }

    data class Timing(val samplesMs: List<Double>, val checksum: Long) {
        val medianMs get() = Bench.median(samplesMs)
        val p95Ms get() = Bench.p95(samplesMs)
    }

    /** Caller constructs fixtures before this bracket. No GC requests or sleeps hide stalls. */
    fun time(warmup: Int = 3, repeats: Int = 9, job: () -> Long): Timing {
        require(warmup >= 1 && repeats >= 1)
        repeat(warmup) { job() }
        var checksum = 0L
        val samples = List(repeats) {
            val start = System.nanoTime()
            val value = job()
            val end = System.nanoTime()
            checksum = checksum xor value
            (end - start) / 1_000_000.0
        }
        return Timing(samples, checksum)
    }

    /** Every output byte participates, so a benchmark cannot report unobserved raster work. */
    fun checksum(bytes: ByteArray): Long {
        var result = 0xcbf29ce484222325UL.toLong()
        for (b in bytes) result = (result xor (b.toLong() and 255)) * 0x100000001b3L
        return result
    }
}

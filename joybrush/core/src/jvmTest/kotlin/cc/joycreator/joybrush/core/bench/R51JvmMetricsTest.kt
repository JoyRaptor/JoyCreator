package cc.joycreator.joybrush.core.bench

import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import cc.joycreator.joybrush.core.stroke.StrokeCodec
import kotlin.test.*

class R51JvmMetricsTest {
    @Test fun storageCompressesOneActualCelBundleAndIndependentTileEntries() {
        val records = R51Fixtures.lines(2)
        val tile = ByteArray(256 * 256 * 4).also { it[3] = 255.toByte() }
        val result = R51JvmMetrics.storage(records, listOf(tile, tile))
        assertEquals(StrokeCodec.encodeAll(records).size, result.strokeRawBytes)
        assertEquals(R51JvmMetrics.deflatedEntry(StrokeCodec.encodeAll(records)).size,
            result.strokeDeflatedBytes)
        assertEquals(2L * tile.size, result.tileRawBytes)
        assertEquals(2L * R51JvmMetrics.deflatedEntry(tile).size, result.tileDeflatedBytes)
        assertEquals(2L, result.coveredPixels)
        assertFailsWith<IllegalArgumentException> { R51JvmMetrics.storage(records, listOf(byteArrayOf(0))) }
    }

    @Test fun rawDeflateRoundTripsAndMatchesZipEntryPayload() {
        val bytes = ByteArray(262144) { (it % 197).toByte() }
        val deflated = R51JvmMetrics.deflatedEntry(bytes)
        val inflater = Inflater(true)
        try {
            inflater.setInput(deflated)
            val restored = ByteArray(bytes.size)
            assertEquals(bytes.size, inflater.inflate(restored))
            assertTrue(inflater.finished())
            assertContentEquals(bytes, restored)
        } finally { inflater.end() }
        val entry = ZipEntry("tile.rgba")
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setLevel(6); zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
        }
        assertEquals(entry.compressedSize, deflated.size.toLong())
    }

    @Test fun quantisationStudyPreservesInputsAndBoundsItsLoss() {
        val originals = R51Fixtures.lines(500)
        val before = StrokeCodec.encodeAll(originals)
        val quantised = R51JvmMetrics.quantisedCopies(originals)
        assertContentEquals(before, StrokeCodec.encodeAll(originals))
        assertEquals(originals.map { it.id }, quantised.map { it.id })
        for (i in originals.indices) for (j in originals[i].samples.indices) {
            val a = originals[i].samples[j]; val b = quantised[i].samples[j]
            assertTrue(kotlin.math.abs(a.x - b.x) <= 1f / 128)
            assertTrue(kotlin.math.abs(a.y - b.y) <= 1f / 128)
            assertTrue(kotlin.math.abs(a.timeMs - b.timeMs) <= .5)
            assertTrue(kotlin.math.abs(a.pressure - b.pressure) <= 1f / 2048)
            assertTrue(kotlin.math.abs(a.tilt - b.tilt) <= 1f / 2048)
            assertTrue(kotlin.math.abs(a.azimuth - b.azimuth) <= 1f / 2048)
            assertTrue(b.barrel.isNaN())
        }
        val output = System.getenv("JOYBRUSH_R51_QUANT_REPORT")
        for ((name, records) in listOf("line-art500" to originals, "painted500" to R51Fixtures.painted(500))) {
            val original = StrokeCodec.encodeAll(records)
            val quantisedBytes = StrokeCodec.encodeAll(R51JvmMetrics.quantisedCopies(records))
            assertEquals(original.size, quantisedBytes.size)
            assertTrue(R51JvmMetrics.deflatedEntry(quantisedBytes).size < R51JvmMetrics.deflatedEntry(original).size)
            output?.let { java.io.File(it).appendText("$name samples=${records.sumOf { r -> r.samples.size }} raw=${original.size} originalDeflated=${R51JvmMetrics.deflatedEntry(original).size} quantisedDeflated=${R51JvmMetrics.deflatedEntry(quantisedBytes).size}\n") }
        }
    }

    @Test fun timingRunsEveryWarmupAndSampleAndObservesOutput() {
        var calls = 0
        val result = R51JvmMetrics.time(2, 3) { calls++; calls.toLong() }
        assertEquals(5, calls)
        assertEquals(3, result.samplesMs.size)
        assertEquals(3L xor 4L xor 5L, result.checksum)
        assertTrue(result.samplesMs.all { it >= 0 })
        assertNotEquals(R51JvmMetrics.checksum(byteArrayOf(1)), R51JvmMetrics.checksum(byteArrayOf(2)))
    }
}

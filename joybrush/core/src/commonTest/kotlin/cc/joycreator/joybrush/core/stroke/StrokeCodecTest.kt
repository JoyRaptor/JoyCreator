package cc.joycreator.joybrush.core.stroke

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.Tool
import cc.joycreator.joybrush.core.io.ByteReader
import cc.joycreator.joybrush.core.io.ByteWriter
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StrokeCodecTest {

    private fun sample(i: Int, allChannels: Boolean, tool: Tool = Tool.STYLUS) = PenSample(
        x = i * 1.5f - 300f,
        y = (i * 0.25f) % 90f,
        timeMs = 1234.5 + i * 0.125,
        pressure = 0.5f + (i % 7) / 14f,
        tilt = if (allChannels) i * 0.01f else Float.NaN,
        azimuth = if (allChannels) i * 0.02f - 1.0f else Float.NaN,
        barrel = if (allChannels) (i % 11) / 11f else Float.NaN,
        tool = tool,
    )

    private fun record(
        samples: List<PenSample>,
        id: String = "s1",
        brush: String = "ink",
        seed: Long = 0x0123456789ABCDEFL,
    ) = StrokeRecord(id, brush, seed, smoothing = 0.35f, screenPerDoc = 2.75f, samples = samples)

    private fun flagsOf(bytes: ByteArray): Int =
        (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8)

    /** Where `sampleCount` sits, straight from the layout table in the spec. */
    private fun countOffset(id: String, brush: String) =
        4 + 2 + 2 + (2 + id.encodeToByteArray().size) + (2 + brush.encodeToByteArray().size) + 8 + 4 + 4

    private fun messageOf(block: () -> Unit): String =
        assertFailsWith<StrokeCodecException> { block() }.message ?: ""

    // --- 1. the whole thing, every channel -------------------------------------------------

    @Test
    fun roundTripWithEveryChannel() {
        val r = record(List(500) { sample(it, allChannels = true) })
        assertEquals(r, StrokeCodec.decode(StrokeCodec.encode(r)))
        // a negative seed must come back negative
        val neg = record(listOf(sample(0, allChannels = false)), seed = -4_294_967_296L)
        assertEquals(neg, StrokeCodec.decode(StrokeCodec.encode(neg)))
    }

    // --- 2. only some channels exist -------------------------------------------------------

    @Test
    fun absentChannelsAreNotStored() {
        val r = record(List(20) { sample(it, allChannels = false).copy(tilt = it * 0.05f) })
        val bytes = StrokeCodec.encode(r)
        assertEquals(0b001, flagsOf(bytes), "tilt only")
        val back = StrokeCodec.decode(bytes)
        assertEquals(r, back)
        assertTrue(back.samples.all { it.azimuth.isNaN() && it.barrel.isNaN() })
        // 8 bytes a sample smaller than storing all three channels
        val all = StrokeCodec.encode(record(List(20) { sample(it, allChannels = true) }))
        assertEquals(0b111, flagsOf(all))
        assertEquals(bytes.size + 20 * 8, all.size)
    }

    // --- 3. a channel some samples have and others do not -----------------------------------

    @Test
    fun oneChannelSomeSamplesHave() {
        val samples = List(6) { i ->
            val s = sample(i, allChannels = false)
            if (i % 2 == 0) s else s.copy(tilt = i * 0.1f)
        }
        val r = record(samples)
        val back = StrokeCodec.decode(StrokeCodec.encode(r))
        assertEquals(r, back)
        for ((i, s) in back.samples.withIndex()) {
            if (i % 2 == 0) assertTrue(s.tilt.isNaN(), "sample $i") else assertEquals(i * 0.1f, s.tilt)
        }
    }

    // --- 4. ids are text, in any script ----------------------------------------------------

    @Test
    fun nonAsciiIdsRoundTrip() {
        for (id in listOf("étoile-星", "", "𝄞 clef", "a".repeat(300))) {
            val r = record(listOf(sample(0, allChannels = false)), id = id, brush = "pinceau-$id")
            assertEquals(r, StrokeCodec.decode(StrokeCodec.encode(r)))
        }
        // the length in the file is BYTES, not characters: "é" is two of them
        val bytes = StrokeCodec.encode(record(emptyList(), id = "é"))
        assertEquals(2, (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8))
    }

    // --- 5. the awkward floats survive ------------------------------------------------------

    @Test
    fun awkwardFloatsKeepTheirBits() {
        val pressures = listOf(Float.NaN, -0.0f, 0f, Float.MIN_VALUE, -Float.MIN_VALUE, Float.MAX_VALUE)
        val r = record(pressures.mapIndexed { i, p -> sample(i, allChannels = false).copy(pressure = p) })
        val back = StrokeCodec.decode(StrokeCodec.encode(r))
        for ((i, p) in pressures.withIndex()) {
            assertEquals(p.toRawBits(), back.samples[i].pressure.toRawBits(), "pressure of sample $i")
        }
        // time is an absolute Double: sub-millisecond survives too
        val times = listOf(0.0, -1.0, 1.0 / 3.0, 1e12 + 0.5, Double.NaN)
        val rt = record(times.mapIndexed { i, t -> sample(i, allChannels = false).copy(timeMs = t) })
        val backT = StrokeCodec.decode(StrokeCodec.encode(rt))
        for ((i, t) in times.withIndex()) assertEquals(t.toRawBits(), backT.samples[i].timeMs.toRawBits())
    }

    // --- 6. every tool ----------------------------------------------------------------------

    @Test
    fun everyToolRoundTrips() {
        val r = record(Tool.entries.mapIndexed { i, t -> sample(i, allChannels = false, tool = t) })
        val back = StrokeCodec.decode(StrokeCodec.encode(r))
        assertEquals(Tool.entries.toList(), back.samples.map { it.tool })
        assertEquals(r, back)
    }

    // --- 7. a stroke with no samples at all ---------------------------------------------------

    @Test
    fun emptySamplesRoundTrip() {
        assertEquals(record(emptyList()), StrokeCodec.decode(StrokeCodec.encode(record(emptyList()))))
        assertEquals(emptyList(), StrokeCodec.decodeAll(StrokeCodec.encodeAll(emptyList())))
    }

    // --- 8. a file of many strokes ------------------------------------------------------------

    @Test
    fun manyRecordsRoundTripInOrder() {
        val records = listOf(
            record(List(30) { sample(it, allChannels = true) }, id = "a"),
            record(List(2) { sample(it, allChannels = false) }, id = "b", brush = "pencil"),
            record(emptyList(), id = "c", brush = "smudge"),
        )
        val all = StrokeCodec.encodeAll(records)
        assertEquals(records, StrokeCodec.decodeAll(all))
        // a u32 count, then for each record a u32 byte length and the record itself
        assertEquals(3L, ByteReader(all).u32())
        assertEquals(4 + records.sumOf { 4 + StrokeCodec.encode(it).size }, all.size)
        assertEquals(listOf(0b111, 0b000, 0b000), records.map { flagsOf(StrokeCodec.encode(it)) })
    }

    // --- 9. rubbish in, loud failure out ---------------------------------------------------------

    @Test
    fun badInputThrows() {
        val good = StrokeCodec.encode(record(List(5) { sample(it, allChannels = true) }))

        assertTrue(messageOf { StrokeCodec.decode(ByteArray(0)) }.contains("truncated"))
        assertTrue(messageOf { StrokeCodec.decode(good.copyOf(10)) }.contains("truncated"))
        assertTrue(messageOf { StrokeCodec.decode(good.copyOf(good.size - 1)) }.contains("truncated"))
        assertTrue(messageOf { StrokeCodec.decode(good.copyOf(8)) }.contains("truncated"))

        val wrongMagic = good.copyOf().also { "XXXX".encodeToByteArray().copyInto(it, 0) }
        assertTrue(messageOf { StrokeCodec.decode(wrongMagic) }.contains("magic"))

        val v2 = good.copyOf().also { it[4] = 2; it[5] = 0 }
        assertEquals("unsupported stroke version 2", messageOf { StrokeCodec.decode(v2) })
        val v0 = good.copyOf().also { it[4] = 0; it[5] = 0 }
        assertEquals("unsupported stroke version 0", messageOf { StrokeCodec.decode(v0) })

        // a sample count the bytes cannot possibly hold
        val lying = good.copyOf()
        val at = countOffset("s1", "ink")
        for (i in 0..3) lying[at + i] = 0xFF.toByte()
        assertTrue(messageOf { StrokeCodec.decode(lying) }.contains("truncated"))

        // an unknown tool ordinal
        val one = StrokeCodec.encode(record(listOf(sample(0, allChannels = false))))
        assertTrue(messageOf { StrokeCodec.decode(one.copyOf().also { b -> b[b.size - 1] = 9 }) }.contains("tool 9"))

        // a flag bit this version does not know: the sample layout would be misread, so refuse
        val oddFlag = one.copyOf().also { it[7] = 0x08 }
        assertTrue(messageOf { StrokeCodec.decode(oddFlag) }.contains("flags"))

        // a file that says it holds nine records and holds nothing
        assertTrue(messageOf { StrokeCodec.decodeAll(byteArrayOf(9, 0, 0, 0)) }.contains("truncated"))
        assertTrue(messageOf { StrokeCodec.decodeAll(ByteArray(3)) }.contains("truncated"))
        assertTrue(messageOf { StrokeCodec.decodeAll(good.copyOf(good.size - 1)) }.contains("truncated"))

        // one record claiming to be four billion bytes long
        val lyingLen = byteArrayOf(1, 0, 0, 0) + byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F)
        assertTrue(messageOf { StrokeCodec.decodeAll(lyingLen) }.contains("truncated"))
    }

    // --- 10. the size is exactly what the layout says -------------------------------------------

    @Test
    fun sizeIsExactlyWhatTheLayoutSays() {
        val id = "stroke-0001"
        val brush = "ink"
        val r = record(List(1000) { sample(it, allChannels = false) }, id = id, brush = brush)
        val bytes = StrokeCodec.encode(r)
        val expected = 4 + 2 + 2 +
            (2 + id.encodeToByteArray().size) + (2 + brush.encodeToByteArray().size) +
            8 + 4 + 4 + 4 +
            1000 * (8 + 4 + 4 + 4 + 1)
        assertEquals(expected, bytes.size)
        assertEquals(0, flagsOf(bytes))
        assertEquals(r, StrokeCodec.decode(bytes))
    }

    // --- the primitives themselves ---------------------------------------------------------------

    @Test
    fun byteWriterAndReaderAreLittleEndianAndGrow() {
        val w = ByteWriter()
        w.u8(0xAB)
        w.u16(0xBEEF)
        w.u32(0xDEADBEEF)
        w.i64(-1L)
        w.f32(PI.toFloat())
        w.f64(PI)
        w.utf8("héllo")
        val out = w.toByteArray()
        assertEquals(1 + 2 + 4 + 8 + 4 + 8 + 2 + 6, out.size)
        assertContentEquals(byteArrayOf(0xAB.toByte(), 0xEF.toByte(), 0xBE.toByte()), out.copyOfRange(0, 3))
        assertContentEquals(
            byteArrayOf(0xEF.toByte(), 0xBE.toByte(), 0xAD.toByte(), 0xDE.toByte()),
            out.copyOfRange(3, 7),
        )

        val r = ByteReader(out)
        assertEquals(0xAB, r.u8())
        assertEquals(0xBEEF, r.u16())
        assertEquals(0xDEADBEEFL, r.u32())
        assertEquals(-1L, r.i64())
        assertEquals(PI.toFloat(), r.f32())
        assertEquals(PI, r.f64())
        assertEquals("héllo", r.utf8())
        assertEquals(0, r.remaining)
        assertFailsWith<StrokeCodecException> { r.u8() }

        // far bigger than the initial buffer, so the doubling path really runs
        val big = ByteWriter()
        val payload = ByteArray(10_000) { (it * 31 % 251).toByte() }
        big.bytes(payload)
        assertContentEquals(payload, big.toByteArray())
        assertContentEquals(payload, ByteReader(big.toByteArray()).bytes(10_000))
    }
}

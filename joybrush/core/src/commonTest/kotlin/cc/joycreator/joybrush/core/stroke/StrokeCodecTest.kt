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

    /**
     * Where `sampleCount` sits in a VERSION 2 recording, straight from the layout table above.
     * Version 2 put `colorArgb` (u32) and `widthScale` (f32) between `screenPerDoc` and the count.
     */
    private fun countOffset(id: String, brush: String) =
        4 + 2 + 2 + (2 + id.encodeToByteArray().size) + (2 + brush.encodeToByteArray().size) + 8 + 4 + 4 + 4 + 4

    /** Where `colorArgb` sits in a version 2 recording: the 8 bytes before the count. */
    private fun colorOffset(id: String, brush: String) = countOffset(id, brush) - 8

    /**
     * A VERSION 1 recording, written here rather than by the encoder, because the encoder is v2 and
     * patching its version field to 1 would not produce a v1 file — it would produce a v1 header on a
     * v2 body, and the reader would read the colour and the weight as the sample count. This is the
     * only honest way to be given a file from before JB-5.03a.
     */
    private fun encodeV1(record: StrokeRecord): ByteArray {
        val w = ByteWriter()
        w.bytes(StrokeCodec.MAGIC.encodeToByteArray())
        w.u16(1)
        var flags = 0
        if (record.samples.any { it.hasTilt }) flags = flags or 1
        if (record.samples.any { it.hasAzimuth }) flags = flags or 2
        if (record.samples.any { it.hasBarrel }) flags = flags or 4
        w.u16(flags)
        w.utf8(record.id)
        w.utf8(record.brushId)
        w.i64(record.seed)
        w.f32(record.smoothing)
        w.f32(record.screenPerDoc)
        w.u32(record.samples.size.toLong())
        for (s in record.samples) {
            w.f64(s.timeMs)
            w.f32(s.x)
            w.f32(s.y)
            w.f32(s.pressure)
            if (flags and 1 != 0) w.f32(s.tilt)
            if (flags and 2 != 0) w.f32(s.azimuth)
            if (flags and 4 != 0) w.f32(s.barrel)
            w.u8(s.tool.ordinal)
        }
        return w.toByteArray()
    }

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

        // Version 2 is what this build writes, so it is of course not refused. A version from the
        // future is, by number, because its layout is not this one.
        val v3 = good.copyOf().also { it[4] = 3; it[5] = 0 }
        assertEquals("unsupported stroke version 3", messageOf { StrokeCodec.decode(v3) })
        val v0 = good.copyOf().also { it[4] = 0; it[5] = 0 }
        assertEquals("unsupported stroke version 0", messageOf { StrokeCodec.decode(v0) })
        val far = good.copyOf().also { it[4] = 0xFF.toByte(); it[5] = 0xFF.toByte() }
        assertEquals("unsupported stroke version 65535", messageOf { StrokeCodec.decode(far) })

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
            8 + 4 + 4 + 4 + 4 + 4 +
            1000 * (8 + 4 + 4 + 4 + 1)
        assertEquals(expected, bytes.size)
        assertEquals(0, flagsOf(bytes))
        assertEquals(r, StrokeCodec.decode(bytes))
    }

    // --- 11. version 2: the colour and the weight -----------------------------------------------

    @Test
    fun aV2RecordingCarriesItsColourAndItsWeight() {
        val r = record(List(60) { sample(it, allChannels = true) })
            .copy(colorArgb = 0x80FF8000.toInt(), widthScale = 2.5f)
        val bytes = StrokeCodec.encode(r)
        assertEquals(2, bytes[4].toInt() and 0xFF, "the writer stamps its own version")
        assertEquals(2, StrokeCodec.VERSION)
        assertEquals(r, StrokeCodec.decode(bytes))

        // The two fields sit exactly where the layout says: colour then weight, between
        // `screenPerDoc` and the sample count, so a file written by another build is read, not guessed.
        val at = colorOffset("s1", "ink")
        val reader = ByteReader(bytes.copyOfRange(at, at + 8))
        assertEquals(0x80FF8000L, reader.u32())
        assertEquals(2.5f, reader.f32())
        assertEquals(countOffset("s1", "ink"), at + 8, "and the sample count starts right after them")

        // A transparent colour and a fractional weight are ordinary values, not extremes to fear.
        for (argb in listOf(0, -1, 1, Int.MIN_VALUE, 0x00FFFFFF.toInt())) {
            for (scale in listOf(0.05f, 0.5f, 1f, 7.75f, 20f)) {
                val c = r.copy(colorArgb = argb, widthScale = scale)
                assertEquals(c, StrokeCodec.decode(StrokeCodec.encode(c)), "argb=$argb scale=$scale")
            }
        }
    }

    // --- 12. a version 1 recording is still a recording ----------------------------------------

    @Test
    fun aV1RecordingReadsAsBlackAtItsOwnSize() {
        val original = record(List(40) { sample(it, allChannels = true) }, id = "old", brush = "pencil")
        val v1 = encodeV1(original)
        assertEquals(1, v1[4].toInt() and 0xFF, "this is a version 1 file, built by hand in the v1 layout")
        assertEquals(v1.size + 8, StrokeCodec.encode(original).size, "v2 is eight bytes longer")

        val back = StrokeCodec.decode(v1)
        assertEquals(original, back, "same id, brush, seed, smoothing, zoom and samples, byte for bit")
        assertEquals(StrokeCodec.DEFAULT_COLOR_ARGB, back.colorArgb, "a v1 stroke is read as black")
        assertEquals(0xFF000000.toInt(), back.colorArgb)
        assertEquals(1f, back.widthScale, "…and at the brush's own size")

        // And the moment it is saved again it is a v2 recording, defaults and all — so the next save
        // makes the file readable by a build that has never heard of version 1.
        val resaved = StrokeCodec.encode(back)
        assertEquals(2, resaved[4].toInt() and 0xFF)
        assertEquals(back, StrokeCodec.decode(resaved))

        // Several at once, through the archive's own entry point.
        val two = listOf(original, original.copy(id = "old2"))
        assertEquals(two, StrokeCodec.decodeAll(StrokeCodec.encodeAll(two)))
    }

    // --- 13. a weight that cannot be a weight is brought into range on the way in --------------

    @Test
    fun aWeightOffTheScaleIsRepairedByTheDecoder() {
        // The record is a plain value (JB-5.03a decision 2), so a hand-built one can hold anything.
        // What must not happen is a stroke that replays at a width of zero or of infinity.
        val base = record(listOf(sample(0, allChannels = false)))
        for ((written, read) in listOf(0f to 0.05f, -3f to 0.05f, 1e9f to 20f, 3e38f to 20f)) {
            val back = StrokeCodec.decode(StrokeCodec.encode(base.copy(widthScale = written)))
            assertEquals(read, back.widthScale, "a stored width of $written")
            assertTrue(back.widthScale in StrokeEdit.MIN_WIDTH_SCALE..StrokeEdit.MAX_WIDTH_SCALE)
        }
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val back = StrokeCodec.decode(StrokeCodec.encode(base.copy(widthScale = bad)))
            assertEquals(1f, back.widthScale, "a stored width of $bad reads as the neutral width")
        }
        // The two bounds are the ones the EDIT uses: one number, in one place (R19).
        assertEquals(0.05f, StrokeEdit.MIN_WIDTH_SCALE)
        assertEquals(20f, StrokeEdit.MAX_WIDTH_SCALE)
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

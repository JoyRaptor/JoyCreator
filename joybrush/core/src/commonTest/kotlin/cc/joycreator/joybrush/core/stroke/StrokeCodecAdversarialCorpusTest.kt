package cc.joycreator.joybrush.core.stroke

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.Tool
import cc.joycreator.joybrush.core.io.ByteWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Adversarial corpus for [StrokeCodec]: deterministic records through the REAL encoder/decoder.
 *
 * Existing [StrokeCodecTest] already covers single-axis cases (500-sample round trip, tilt-only
 * omission, non-ASCII ids, awkward pressure/time floats, every tool, empty/multi records, a few
 * truncation spots, v2 colour/weight, one v1 file, weight repair, ByteWriter/Reader primitives).
 * This corpus adds the COMBINED adversarial matrix the spec demands: sample counts 0..512 with
 * signed seed extrema, Unicode ids, every tool, every sensor-availability combination including
 * changes WITHIN one record, explicit colour/width matrices, every-byte-offset truncation, every
 * unknown flag bit, version boundaries, count lies, a trailing-byte observation and a mutated
 * positive control. Data-class equality is deliberately NOT used for floats (`-0.0f == 0.0f` and
 * `NaN == NaN` under `equals`, so it would hide exactly the corruptions this file hunts);
 * every float is compared with [Float.toRawBits] / [Double.toRawBits].
 *
 * Genuinely BIT-EXACT (raw bits equal after a round trip):
 * - id, brushId, seed, colour (v2), tool per sample, sample count and order.
 * - smoothing, screenPerDoc: the decoder applies no normalization, raw f32 bits survive.
 * - x, y, pressure, timeMs: always stored, raw bits survive (incl. -0.0 and NaN payloads).
 * - tilt/azimuth/barrel, but ONLY when that channel is present in at least one sample (flag set):
 *   then every sample's slot is stored raw, so -0.0 and distinct NaN payloads survive, including
 *   the NaN written for samples that lacked the reading.
 *
 * Intentionally NORMALIZED or OMITTED (documented design, asserted as such, never as bit-exact):
 * - widthScale: finite values are coerced into `StrokeEdit.MIN..MAX_WIDTH_SCALE`; non-finite
 *   reads as 1.
 * - tilt/azimuth/barrel when NO sample has the channel: the channel is omitted and every sample
 *   decodes as canonical `Float.NaN` (payload intentionally not preserved).
 * - version-1 recordings carry no colour/width: they decode as black (`DEFAULT_COLOR_ARGB`) at
 *   scale 1.
 *
 * OBSERVED but NOT DOCUMENTED (uncertainty, see trailing test): [StrokeCodec]'s KDoc never states
 * the trailing-byte policy. The decoder performs no `remaining == 0` check, so trailing bytes are
 * currently ignored. That test records observed behaviour and must not be read as a contract
 * promise; a future strict reader would not break any documented guarantee.
 *
 * Bounds: at most 512 samples per record, ~105 prefix decodes of a ~105-byte record, no crafted
 * multi-gigabyte counts (only the count-FIT check path is exercised). No allocation bombs.
 */
class StrokeCodecAdversarialCorpusTest {

    private data class CorpusEntry(val record: StrokeRecord, val sensorMode: Int)

    // -- deterministic builders (pure formulas, no Random, same bytes on every platform) ----------

    private fun detSample(salt: Int, i: Int, sensorMode: Int, tool: Tool): PenSample {
        val x = i * 1.75f - 100f + salt * 0.125f
        val y = i * -0.5f + 40f - salt * 0.25f
        val timeMs = 1_000_000.0 + i * 16.625 + salt * 0.5
        val pressure = 0.05f + ((i * 37 + salt * 11) % 90) / 100f
        val tilt = if (sensorMode and 1 != 0) i * 0.125f - 1f else Float.NaN
        val azimuth = if (sensorMode and 2 != 0) i * 0.25f - 3f else Float.NaN
        val barrel = if (sensorMode and 4 != 0) ((i + salt) % 5) / 5f else Float.NaN
        return PenSample(
            x = x, y = y, timeMs = timeMs, pressure = pressure,
            tilt = tilt, azimuth = azimuth, barrel = barrel, tool = tool,
        )
    }

    private fun corpusRecord(
        salt: Int,
        sampleCount: Int,
        id: String,
        brush: String,
        seed: Long,
        sensorMode: Int,
        colorArgb: Int,
        widthScale: Float,
        smoothing: Float,
        screenPerDoc: Float,
    ): StrokeRecord {
        val samples = List(sampleCount) { i ->
            detSample(salt, i, sensorMode, Tool.entries[(i + salt) % Tool.entries.size])
        }
        return StrokeRecord(
            id = id, brushId = brush, seed = seed, smoothing = smoothing,
            screenPerDoc = screenPerDoc, samples = samples,
            colorArgb = colorArgb, widthScale = widthScale,
        )
    }

    private fun corpus(): List<CorpusEntry> = listOf(
        CorpusEntry(corpusRecord(0, 0, "", "ink", Long.MIN_VALUE, 0, 0, 1f, 0f, 1f), 0),
        CorpusEntry(corpusRecord(1, 1, "étoile-星", "pinceau-星", Long.MAX_VALUE, 7, -1, 0.05f, -0.0f, -0.0f), 7),
        CorpusEntry(corpusRecord(2, 2, "𝄞 clef", "", -1L, 1, 0x80FF8000.toInt(), 20f, 0.35f, 2.75f), 1),
        CorpusEntry(corpusRecord(3, 3, "stroke-0001", "pencil", 0L, 2, 0x00FFFFFF, 2.5f, 1f, Float.MAX_VALUE), 2),
        CorpusEntry(corpusRecord(4, 7, "s-4", "ink", 1L, 4, Int.MIN_VALUE, 0.5f, Float.MIN_VALUE, Float.MIN_VALUE), 4),
        CorpusEntry(corpusRecord(5, 64, "s-5-" + "x".repeat(64), "smudge", 0x0123456789ABCDEFL, 3, Int.MAX_VALUE, 7.75f, 0.5f, 3f), 3),
        CorpusEntry(corpusRecord(6, 511, "s-6", "ink", -4_294_967_296L, 5, 0xFF000000.toInt(), 0.1f, 0.75f, 0.5f), 5),
        CorpusEntry(corpusRecord(7, 512, "s-7-512", "pencil", 123456789L, 6, 1, 1f, 0.25f, 8f), 6),
        CorpusEntry(corpusRecord(8, 5, "bye-世界", "brush-世界", -987654321L, 7, 0x12345678, 19.999f, 0.05f, 100f), 7),
        CorpusEntry(corpusRecord(9, 16, "s-9", "ink", 42L, 0, 0xABCDEF01.toInt(), 0.050001f, 0.9f, 1.5f), 0),
    )

    // -- assertion core: raw bits for every float, NaN-tolerant only where omission is promised ---

    private fun assertPreservedExact(expected: StrokeRecord, actual: StrokeRecord, label: String) {
        assertEquals(expected.id, actual.id, "$label id")
        assertEquals(expected.brushId, actual.brushId, "$label brush")
        assertEquals(expected.seed, actual.seed, "$label seed")
        assertEquals(expected.colorArgb, actual.colorArgb, "$label colorArgb")
        assertEquals(expected.smoothing.toRawBits(), actual.smoothing.toRawBits(), "$label smoothing bits")
        assertEquals(expected.screenPerDoc.toRawBits(), actual.screenPerDoc.toRawBits(), "$label screenPerDoc bits")
        assertEquals(expected.widthScale.toRawBits(), actual.widthScale.toRawBits(), "$label widthScale bits")
        assertEquals(expected.samples.size, actual.samples.size, "$label sample count")
        val tiltPresent = expected.samples.any { it.hasTilt }
        val azimuthPresent = expected.samples.any { it.hasAzimuth }
        val barrelPresent = expected.samples.any { it.hasBarrel }
        for (i in expected.samples.indices) {
            val e = expected.samples[i]
            val a = actual.samples[i]
            val tag = "$label sample $i"
            assertEquals(e.x.toRawBits(), a.x.toRawBits(), "$tag x bits")
            assertEquals(e.y.toRawBits(), a.y.toRawBits(), "$tag y bits")
            assertEquals(e.timeMs.toRawBits(), a.timeMs.toRawBits(), "$tag timeMs bits")
            assertEquals(e.pressure.toRawBits(), a.pressure.toRawBits(), "$tag pressure bits")
            if (tiltPresent) {
                assertEquals(e.tilt.toRawBits(), a.tilt.toRawBits(), "$tag tilt bits")
            } else {
                assertTrue(e.tilt.isNaN(), "$tag expected tilt absent")
                assertTrue(a.tilt.isNaN(), "$tag absent tilt must decode NaN (payload omitted by design)")
            }
            if (azimuthPresent) {
                assertEquals(e.azimuth.toRawBits(), a.azimuth.toRawBits(), "$tag azimuth bits")
            } else {
                assertTrue(e.azimuth.isNaN(), "$tag expected azimuth absent")
                assertTrue(a.azimuth.isNaN(), "$tag absent azimuth must decode NaN (payload omitted by design)")
            }
            if (barrelPresent) {
                assertEquals(e.barrel.toRawBits(), a.barrel.toRawBits(), "$tag barrel bits")
            } else {
                assertTrue(e.barrel.isNaN(), "$tag expected barrel absent")
                assertTrue(a.barrel.isNaN(), "$tag absent barrel must decode NaN (payload omitted by design)")
            }
            assertEquals(e.tool, a.tool, "$tag tool")
        }
    }

    private fun flagsOf(bytes: ByteArray): Int =
        (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8)

    /** Byte offset of the u32 sample count in a v2 recording (layout-derived probe, not a codec). */
    private fun countOffsetOf(id: String, brush: String): Int =
        4 + 2 + 2 + (2 + id.encodeToByteArray().size) + (2 + brush.encodeToByteArray().size) +
            8 + 4 + 4 + 4 + 4

    private fun messageOf(block: () -> Unit): String {
        return try {
            block()
            throw AssertionError("expected StrokeCodecException but decoded cleanly")
        } catch (e: StrokeCodecException) {
            e.message ?: ""
        }
    }

    /**
     * Test-only writer for the DOCUMENTED v1 layout (versions table on [StrokeCodec]): magic,
     * u16(1), flags, utf8 id/brush, i64 seed, f32 smoothing/screenPerDoc, u32 count, samples with
     * conditional sensor slots and a u8 tool each. No colour/width fields. This exists only
     * because the production encoder always emits v2; patching a v2 header to 1 would fake a v1
     * file (v1 header over a v2 body) rather than build one.
     */
    private fun encodeV1Independent(record: StrokeRecord): ByteArray {
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

    // -- 1. the deterministic corpus ---------------------------------------------------------------

    @Test
    fun deterministicCorpusRoundTripsWithRawBits() {
        val entries = corpus()
        for ((index, entry) in entries.withIndex()) {
            val bytes = StrokeCodec.encode(entry.record)
            // The flag rule is "stored for the WHOLE recording when ANY sample has it": with
            // uniform builders the stored flags must equal the requested sensor mode exactly
            // (empty records store nothing, and mode 0 requests nothing).
            assertEquals(entry.sensorMode, flagsOf(bytes), "corpus $index flags")
            val back = StrokeCodec.decode(bytes)
            assertPreservedExact(entry.record, back, "corpus $index")
        }
        // The archive entry point keeps order across the whole corpus.
        val records = entries.map { it.record }
        val backAll = StrokeCodec.decodeAll(StrokeCodec.encodeAll(records))
        assertEquals(records.size, backAll.size, "corpus batch size")
        for (i in records.indices) assertPreservedExact(records[i], backAll[i], "corpus batch $i")
    }

    // -- 2. signed zero and distinct NaN payloads, only where preservation is promised --------------

    @Test
    fun signedZeroAndDistinctNaNPreservedWherePromised() {
        val present = StrokeRecord(
            id = "nan-zero", brushId = "ink", seed = 99L,
            smoothing = Float.fromBits(0x7F800002), screenPerDoc = -0.0f,
            samples = listOf(
                PenSample(x = -0.0f, y = 0.0f, timeMs = -0.0, pressure = Float.fromBits(0x7F800001), tilt = -0.0f, azimuth = Float.fromBits(0x7FC00001), barrel = 0.0f, tool = Tool.STYLUS),
                PenSample(x = 0.0f, y = -0.0f, timeMs = Double.fromBits(0x7FF0000000000001L), pressure = -0.0f, tilt = Float.fromBits(0x7F800001), azimuth = -0.0f, barrel = Float.fromBits(0x7FC00002), tool = Tool.ERASER),
                PenSample(x = Float.POSITIVE_INFINITY, y = Float.NEGATIVE_INFINITY, timeMs = Double.POSITIVE_INFINITY, pressure = Float.POSITIVE_INFINITY, tilt = 1.5f, azimuth = 2.5f, barrel = 3.5f, tool = Tool.FINGER),
                PenSample(x = Float.MIN_VALUE, y = -Float.MIN_VALUE, timeMs = -1e12, pressure = Float.MAX_VALUE, tilt = Float.fromBits(0x7FC00003), azimuth = Float.fromBits(0x7F800002), barrel = -0.0f, tool = Tool.MOUSE),
            ),
            colorArgb = 7, widthScale = 1f,
        )
        val back = StrokeCodec.decode(StrokeCodec.encode(present))
        assertEquals(0b111, flagsOf(StrokeCodec.encode(present)), "all channels present")
        assertPreservedExact(present, back, "signed-zero/nan-present")

        // Every tilt/azimuth/barrel here is NaN (with DISTINCT payloads), so no channel is stored:
        // the payloads are intentionally omitted and only isNaN() may be asserted on decode.
        val absent = StrokeRecord(
            id = "all-absent", brushId = "ink", seed = 100L,
            smoothing = 0.5f, screenPerDoc = 2f,
            samples = listOf(
                PenSample(x = 1f, y = 2f, timeMs = 3.0, pressure = 0.5f, tilt = Float.fromBits(0x7F800001), azimuth = Float.fromBits(0x7FC00001), barrel = Float.fromBits(0x7F800002), tool = Tool.STYLUS),
                PenSample(x = 4f, y = 5f, timeMs = 6.0, pressure = 0.5f, tilt = Float.fromBits(0x7FC00002), azimuth = Float.fromBits(0x7F800003), barrel = Float.NaN, tool = Tool.STYLUS),
            ),
            colorArgb = 8, widthScale = 1f,
        )
        val absentBytes = StrokeCodec.encode(absent)
        assertEquals(0, flagsOf(absentBytes), "no channel stored")
        val absentBack = StrokeCodec.decode(absentBytes)
        assertTrue(absentBack.samples.all { it.tilt.isNaN() && it.azimuth.isNaN() && it.barrel.isNaN() })
        assertPreservedExact(absent, absentBack, "all-absent")
    }

    // -- 3. sensor availability changes within one record --------------------------------------------

    @Test
    fun changingSensorAvailabilityWithinRecord() {
        // Each channel is present in SOME samples and NaN (with distinct payloads) in others.
        // Because at least one sample has each channel, all three slots are stored and every
        // sample's bits — including the absent samples' NaN payloads — must survive.
        val mixed = List(8) { i ->
            PenSample(
                x = i.toFloat(), y = -i.toFloat(), timeMs = i * 7.5,
                pressure = 0.25f + i * 0.05f,
                tilt = if (i % 2 == 0) i * 0.5f else Float.fromBits(0x7F800001 + i),
                azimuth = if (i % 3 == 0) i * 0.25f - 1f else Float.fromBits(0x7FC00001 + i),
                barrel = if (i % 4 == 3) i * 0.1f else Float.fromBits(0x7F800005 + i),
                tool = Tool.entries[i % Tool.entries.size],
            )
        }
        val r = StrokeRecord("mixed-sensors", "ink", 555L, 0.4f, 2f, mixed, 0xFF112233.toInt(), 1.5f)
        val bytes = StrokeCodec.encode(r)
        assertEquals(0b111, flagsOf(bytes), "each channel present somewhere means all stored")
        assertPreservedExact(r, StrokeCodec.decode(bytes), "mixed-sensors")

        // Tilt-only mix: azimuth/barrel stay entirely absent and decode as NaN.
        val tiltOnly = List(6) { i ->
            val base = detSample(0, i, 0, Tool.STYLUS)
            if (i % 2 == 0) base.copy(tilt = i * 0.1f) else base
        }
        val rt = StrokeRecord("tilt-mix", "pencil", 556L, 0.4f, 2f, tiltOnly, 9, 1f)
        val tiltBytes = StrokeCodec.encode(rt)
        assertEquals(0b001, flagsOf(tiltBytes), "tilt only")
        val tiltBack = StrokeCodec.decode(tiltBytes)
        assertTrue(tiltBack.samples.all { it.azimuth.isNaN() && it.barrel.isNaN() })
        assertPreservedExact(rt, tiltBack, "tilt-mix")
    }

    // -- 4. explicit colour is exact; width is exact in range, repaired out of range ------------------

    @Test
    fun explicitColorAndWidthScaleMatrix() {
        val base = corpusRecord(11, 4, "cw", "ink", 77L, 7, 0, 1f, 0.3f, 2f)
        for (argb in listOf(0, -1, 1, Int.MIN_VALUE, Int.MAX_VALUE, 0x80FF8000.toInt(), 0x00FFFFFF, 0xFF000000.toInt())) {
            val back = StrokeCodec.decode(StrokeCodec.encode(base.copy(colorArgb = argb)))
            assertEquals(argb, back.colorArgb, "colour $argb must be exact")
        }
        // In-range finite widths are stored raw and come back bit-exact.
        for (scale in listOf(0.05f, 0.050001f, 0.5f, 1f, 2.5f, 7.75f, 19.999f, 20f)) {
            val back = StrokeCodec.decode(StrokeCodec.encode(base.copy(widthScale = scale)))
            assertEquals(scale.toRawBits(), back.widthScale.toRawBits(), "width $scale must be bit-exact")
        }
        // Out-of-range finite widths are clamped to the documented edit bounds; non-finite
        // widths read as the neutral width. This is normalization BY DESIGN, not corruption.
        for ((written, read) in listOf(0f to 0.05f, -3f to 0.05f, 0.049f to 0.05f, 20.001f to 20f, 1e9f to 20f, 3e38f to 20f)) {
            val back = StrokeCodec.decode(StrokeCodec.encode(base.copy(widthScale = written)))
            assertEquals(read, back.widthScale, "width $written must clamp to $read")
        }
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val back = StrokeCodec.decode(StrokeCodec.encode(base.copy(widthScale = bad)))
            assertEquals(1f, back.widthScale, "width $bad must read as neutral")
        }
    }

    // -- 5. legacy v1: independently built bytes read with exact defaults -------------------------------

    @Test
    fun legacyV1DefaultsFromIndependentBytes() {
        val v1a = corpusRecord(21, 5, "old- étoile", "pencil", Long.MIN_VALUE, 7, 0, 99f, 0.35f, 2.75f)
        val v1b = corpusRecord(22, 2, "old-b", "ink", Long.MAX_VALUE, 0, 0, 99f, -0.0f, 1f)
        val v1c = corpusRecord(23, 0, "old-empty", "ink", 0L, 0, 0, 99f, 0f, 1f)
        for ((tag, original) in listOf("v1a" to v1a, "v1b" to v1b, "v1c" to v1c)) {
            val v1 = encodeV1Independent(original)
            assertEquals(1, v1[4].toInt() and 0xFF, "$tag is a version-1 file")
            val back = StrokeCodec.decode(v1)
            // A pre-colour recording is read as what it was drawn as: black at neutral weight.
            assertEquals(StrokeCodec.DEFAULT_COLOR_ARGB, back.colorArgb, "$tag v1 colour default")
            assertEquals(0xFF000000.toInt(), back.colorArgb, "$tag v1 colour is black")
            assertEquals(1f, back.widthScale, "$tag v1 width default")
            val expected = original.copy(colorArgb = StrokeCodec.DEFAULT_COLOR_ARGB, widthScale = 1f)
            assertPreservedExact(expected, back, tag)
            // Re-saving upgrades to v2 (exactly 8 bytes: colour + weight) and stays stable.
            val resaved = StrokeCodec.encode(back)
            assertEquals(2, resaved[4].toInt() and 0xFF, "$tag resaved as v2")
            assertEquals(v1.size + 8, resaved.size, "$tag v2 is eight bytes longer")
            assertPreservedExact(back, StrokeCodec.decode(resaved), "$tag resaved")
        }
    }

    // -- 6. truncation at EVERY byte offset ----------------------------------------------------------------

    @Test
    fun truncationAtEveryByteOffsetFails() {
        // Small valid record (~105 bytes): every strict prefix must fail loudly, never half-decode.
        // Bounded by construction: one record, prefixes only, no crafted huge counts.
        val r = corpusRecord(31, 3, "t", "b", 7L, 0, 0xFF112233.toInt(), 1f, 0.5f, 2f)
        val bytes = StrokeCodec.encode(r)
        assertPreservedExact(r, StrokeCodec.decode(bytes), "truncation fixture must be valid")
        for (n in 0 until bytes.size) {
            try {
                StrokeCodec.decode(bytes.copyOf(n))
                throw AssertionError("prefix of $n/${bytes.size} bytes decoded without error")
            } catch (e: StrokeCodecException) {
                // Expected: any truncation is a loud refusal, never a partial stroke.
            }
        }
    }

    // -- 7. bad magic / version / flags / counts ------------------------------------------------------------

    @Test
    fun badMagicVersionFlagsCountsRejected() {
        val good = StrokeCodec.encode(corpusRecord(41, 5, "s1", "ink", 9L, 7, 0, 1f, 0.5f, 2f))
        assertTrue(messageOf { StrokeCodec.decode(ByteArray(0)) }.contains("truncated"), "empty")
        assertTrue(messageOf { StrokeCodec.decode(ByteArray(3)) }.contains("truncated"), "short header")

        val wrongMagic = good.copyOf().also { "XXXX".encodeToByteArray().copyInto(it, 0) }
        assertTrue(messageOf { StrokeCodec.decode(wrongMagic) }.contains("magic"), "bad magic")

        // Boundaries of the supported [OLDEST_VERSION, VERSION] = [1, 2]: 0 below, 3 above.
        assertEquals("unsupported stroke version 0", messageOf {
            StrokeCodec.decode(good.copyOf().also { it[4] = 0; it[5] = 0 })
        })
        assertEquals("unsupported stroke version 3", messageOf {
            StrokeCodec.decode(good.copyOf().also { it[4] = 3; it[5] = 0 })
        })
        assertEquals("unsupported stroke version 65535", messageOf {
            StrokeCodec.decode(good.copyOf().also { it[4] = 0xFF.toByte(); it[5] = 0xFF.toByte() })
        })

        // Every unknown flag bit refuses, because the sample layout would otherwise misread.
        // Flags are a little-endian u16 at bytes 6..7; the record below stores 0b111.
        for (mask in listOf(0x08, 0x10, 0x20, 0x40, 0x80, 0x0100, 0x0200, 0x8000, 0xFFFF)) {
            val probe = good.copyOf()
            val flags = 0b111 or mask
            probe[6] = (flags and 0xFF).toByte()
            probe[7] = ((flags ushr 8) and 0xFF).toByte()
            assertTrue(messageOf { StrokeCodec.decode(probe) }.contains("flags"), "flag mask 0x${mask.toString(16)}")
        }

        // Counts the remaining bytes cannot hold (u32 max, and a modest lie of 1000 samples).
        val at = countOffsetOf("s1", "ink")
        val maxCount = good.copyOf()
        for (i in 0..3) maxCount[at + i] = 0xFF.toByte()
        assertTrue(messageOf { StrokeCodec.decode(maxCount) }.contains("truncated"), "u32-max count")
        val bigCount = good.copyOf()
        bigCount[at] = 0xE8.toByte(); bigCount[at + 1] = 3; bigCount[at + 2] = 0; bigCount[at + 3] = 0
        assertTrue(messageOf { StrokeCodec.decode(bigCount) }.contains("truncated"), "1000-sample lie")

        // Unknown tool ordinals (only 0..3 exist today): the last byte is the last sample's tool.
        val one = StrokeCodec.encode(corpusRecord(42, 1, "one", "ink", 10L, 0, 0, 1f, 0f, 1f))
        assertTrue(messageOf {
            StrokeCodec.decode(one.copyOf().also { it[it.size - 1] = 9 })
        }.contains("tool 9"), "unknown tool 9")
        assertTrue(messageOf {
            StrokeCodec.decode(one.copyOf().also { it[it.size - 1] = 0xFF.toByte() })
        }.contains("tool"), "unknown tool 255")

        // Archive entry point: invented record counts and lengths are truncation, not allocation.
        assertTrue(messageOf { StrokeCodec.decodeAll(byteArrayOf(9, 0, 0, 0)) }.contains("truncated"))
        assertTrue(messageOf { StrokeCodec.decodeAll(ByteArray(3)) }.contains("truncated"))
        assertTrue(messageOf { StrokeCodec.decodeAll(good.copyOf(good.size - 1)) }.contains("truncated"))
        val lyingLen = byteArrayOf(1, 0, 0, 0) + byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F)
        assertTrue(messageOf { StrokeCodec.decodeAll(lyingLen) }.contains("truncated"))
    }

    // -- 8. trailing bytes: observed behaviour, NOT a documented promise ---------------------------------------

    @Test
    fun trailingBytesObservedIgnoredNotPromised() {
        // OBSERVATION, not contract: StrokeCodec's KDoc never states a trailing-byte policy, and
        // decode() performs no `remaining == 0` check after the last sample, so trailing bytes are
        // currently ignored. This test pins CURRENT behaviour so a change is visible; a future
        // strict reader that rejects trailing bytes would not break any documented guarantee.
        val r = corpusRecord(51, 3, "trail", "ink", 11L, 7, 5, 1f, 0.5f, 2f)
        val bytes = StrokeCodec.encode(r)
        assertPreservedExact(r, StrokeCodec.decode(bytes + byteArrayOf(0)), "one trailing zero")
        assertPreservedExact(r, StrokeCodec.decode(bytes + ByteArray(64) { it.toByte() }), "64 trailing bytes")
    }

    // -- 9. mutated positive control: the corpus actually detects corruption ------------------------------------

    @Test
    fun mutatedPositiveControlProvesSensitivity() {
        val id = "seed-ctl"
        val brush = "ink"
        val r = corpusRecord(61, 4, id, brush, 0x1122334455667788L, 7, 0xFF445566.toInt(), 1f, 0.5f, 2f)
        val good = StrokeCodec.encode(r)
        assertPreservedExact(r, StrokeCodec.decode(good), "control fixture must be valid")

        // Corrupting a REQUIRED field (seed low byte) must change the decoded record, and only it.
        val seedOff = 4 + 2 + 2 + (2 + id.encodeToByteArray().size) + (2 + brush.encodeToByteArray().size)
        val mutated = good.copyOf()
        mutated[seedOff] = (mutated[seedOff].toInt() xor 0xFF).toByte()
        val mutatedBack = StrokeCodec.decode(mutated)
        assertTrue(mutatedBack.seed != r.seed, "flipped seed byte must change the decoded seed")
        assertPreservedExact(r.copy(seed = mutatedBack.seed), mutatedBack, "only the seed changed")

        // Corrupting the tool slot and the version must refuse loudly, not misread.
        assertTrue(messageOf {
            StrokeCodec.decode(good.copyOf().also { it[it.size - 1] = 9 })
        }.contains("tool"), "mutated tool refused")
        assertEquals("unsupported stroke version 3", messageOf {
            StrokeCodec.decode(good.copyOf().also { it[4] = 3; it[5] = 0 })
        })
    }
}

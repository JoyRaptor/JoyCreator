package cc.joycreator.joybrush.core.stroke

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.StabilizerMode
import cc.joycreator.joybrush.core.input.StabilizerSpec
import cc.joycreator.joybrush.core.input.Tool
import cc.joycreator.joybrush.core.io.ByteWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DRAFT — uncompiled/untested, authored wire independent of the production encoder. */
class StabilizerStrokeCodecTest {
    private fun base() = StrokeRecord("s", "brush", Long.MIN_VALUE + 7, .73f, 2.5f, listOf(
        PenSample(-0f, 1f, -0.0, -0f, Float.fromBits(0x7FC00123), Float.fromBits(0x7FC00456), Float.fromBits(0x7FC00789)),
        PenSample(12.5f, -3f, 19.25, .8f, .4f, -.7f, 2f, Tool.ERASER)),
        colorArgb = 0x80123456.toInt(), widthScale = 1.5f, seq = 9, locked = true, groupId = "group",
        secondaryColorArgb = 0x12345678)
    private fun captured(record: StrokeRecord) = record.copy(capturedPhysicalSource = CapturedPhysicalSource(
        CaptureDomain(CaptureRect(-30, -40, 90, 100), listOf(CaptureRect(-3, 8, 9, 10))), SourcePlacement(-7, 13)))
    private fun uint(w: ByteWriter, n: Int) = w.u32(n.toLong() and 0xFFFF_FFFFL)
    private fun payload(code: Int = 2, strength: Float = .65f, radius: Float = 17f, window: Int = 9) = ByteWriter().also {
        it.u8(code); it.f32(strength); it.f32(radius); it.u16(window)
    }.toByteArray()
    private data class Wire(val bytes: ByteArray, val payloadOffset: Int)

    /** Independently authors all v1..6 headers, including the preexisting optional source block. */
    private fun wire(version: Int, record: StrokeRecord = base(), actual: ByteArray? = null,
                     extraFlags: Int = 0): Wire {
        val source = record.capturedPhysicalSource.takeIf { version >= 4 }
        var flags = extraFlags
        if (record.samples.any { it.hasTilt } || source != null) flags = flags or 1
        if (record.samples.any { it.hasAzimuth } || source != null) flags = flags or 2
        if (record.samples.any { it.hasBarrel } || source != null) flags = flags or 4
        if (version >= 3 && record.locked) flags = flags or 8
        if (version >= 3 && record.groupId != null) flags = flags or 16
        if (source != null) flags = flags or 32
        if (version >= 6 && record.secondaryColorArgb != null) flags = flags or 128
        if (version >= 6 && actual != null) flags = flags or 256
        val w = ByteWriter()
        w.bytes("JBS1".encodeToByteArray()); w.u16(version); w.u16(flags)
        w.utf8(record.id); w.utf8(record.brushId); w.i64(record.seed); w.f32(record.smoothing); w.f32(record.screenPerDoc)
        if (version >= 2) { uint(w, record.colorArgb); w.f32(record.widthScale) }
        if (version >= 3) { w.i64(record.seq); record.groupId?.let(w::utf8) }
        if (source != null) {
            fun rect(r: CaptureRect) { uint(w, r.x); uint(w, r.y); uint(w, r.w); uint(w, r.h) }
            w.u8(1); w.u8(if (source.domain.window == null) 0 else 1)
            source.domain.window?.let(::rect)
            w.u16(source.domain.excluded.size); source.domain.excluded.forEach(::rect)
            uint(w, source.placement.dx); uint(w, source.placement.dy)
        }
        if (version >= 6) record.secondaryColorArgb?.let { uint(w, it) }
        val at = w.toByteArray().size
        if (version >= 6) actual?.let(w::bytes)
        w.u32(record.samples.size.toLong())
        for (s in record.samples) {
            w.f64(s.timeMs); w.f32(s.x); w.f32(s.y); w.f32(s.pressure)
            if (flags and 1 != 0) w.f32(s.tilt)
            if (flags and 2 != 0) w.f32(s.azimuth)
            if (flags and 4 != 0) w.f32(s.barrel)
            w.u8(s.tool.ordinal)
        }
        return Wire(w.toByteArray(), at)
    }
    private fun list(vararg records: ByteArray): ByteArray = ByteWriter().also { w ->
        w.u32(records.size.toLong()); records.forEach { w.u32(it.size.toLong()); w.bytes(it) }
    }.toByteArray()
    private fun assertSamples(expected: StrokeRecord, actual: StrokeRecord) {
        assertEquals(expected.samples.size, actual.samples.size)
        expected.samples.indices.forEach { i ->
            val a = expected.samples[i]; val b = actual.samples[i]
            assertEquals(a.timeMs.toRawBits(), b.timeMs.toRawBits(), "time $i")
            for ((x, y) in listOf(a.x to b.x, a.y to b.y, a.pressure to b.pressure, a.tilt to b.tilt,
                a.azimuth to b.azimuth, a.barrel to b.barrel)) assertEquals(x.toRawBits(), y.toRawBits(), "sample $i")
            assertEquals(a.tool, b.tool)
        }
    }

    @Test fun explicitModeCodesAndElevenBytePayloadMatchIndependentWriter() {
        assertEquals(6, StrokeCodec.VERSION)
        assertEquals(listOf(0, 1, 2, 3, 4), StabilizerMode.entries.map { it.code })
        for (mode in StabilizerMode.entries) for (source in listOf(false, true)) {
            val spec = StabilizerSpec(mode, .65f, 17f, 9)
            val r = (if (source) captured(base()) else base()).copy(stabilizer = spec)
            val authored = wire(6, r, payload(mode.code))
            val encoded = StrokeCodec.encode(r)
            assertContentEquals(authored.bytes, encoded)
            assertEquals(11, payload(mode.code).size)
            assertContentEquals(payload(mode.code), encoded.copyOfRange(authored.payloadOffset, authored.payloadOffset + 11))
            val back = StrokeCodec.decode(encoded)
            assertEquals(spec, back.stabilizer); assertSamples(r, back)
            assertContentEquals(encoded, StrokeCodec.encode(back))
        }
    }

    @Test fun absentSettingsInEveryHistoricalVersionRemainAbsentAndKeepRawSamples() {
        for (version in 1..6) {
            val before = base()
            val decoded = StrokeCodec.decode(wire(version, before).bytes)
            assertNull(decoded.stabilizer)
            assertSamples(before, decoded)
            assertEquals(before.smoothing.toRawBits(), decoded.smoothing.toRawBits())
            assertEquals(if (version >= 6) before.secondaryColorArgb else null, decoded.secondaryColorArgb)
            assertEquals(if (version >= 3) before.seq else 0L, decoded.seq)
            assertNull(StrokeCodec.decode(StrokeCodec.encode(decoded)).stabilizer)
        }
    }

    @Test fun flagsSevenAndEightBelongOnlyToV6AndUnknownFlagsAlwaysRefuse() {
        for (version in 1..5) assertFailsWith<StrokeCodecException>("v$version bit7") {
            StrokeCodec.decode(wire(version, extraFlags = 128).bytes)
        }
        for (version in 1..6) for (unknown in listOf(0x0200, 0x8000))
            assertFailsWith<StrokeCodecException>("v$version flags $unknown") {
                StrokeCodec.decode(wire(version, extraFlags = unknown).bytes)
            }
        assertFailsWith<StrokeCodecException> { StrokeCodec.decode(wire(0).bytes) }
        assertFailsWith<StrokeCodecException> { StrokeCodec.decode(wire(7).bytes) }
    }

    @Test fun strictModeSettingsRejectRatherThanClampOrGuess() {
        val bad = listOf(
            payload(code = 5), payload(code = 255),
            payload(strength = Float.NaN), payload(strength = Float.POSITIVE_INFINITY), payload(strength = -.01f), payload(strength = 1.01f),
            payload(radius = Float.NaN), payload(radius = Float.NEGATIVE_INFINITY), payload(radius = -.01f), payload(radius = 512.01f),
            payload(window = 0), payload(window = 257), payload(window = 65535),
        )
        for (bytes in bad) assertFailsWith<StrokeCodecException> { StrokeCodec.decode(wire(6, actual = bytes).bytes) }
        for (spec in listOf(StabilizerSpec(strength = Float.NaN), StabilizerSpec(radiusPx = 513f), StabilizerSpec(windowSize = 0)))
            assertFailsWith<StrokeCodecException> { StrokeCodec.encode(base().copy(stabilizer = spec)) }
        val signed = StabilizerSpec(StabilizerMode.off, -0f, -0f, 1)
        val record = base().copy(stabilizer = signed)
        val back = StrokeCodec.decode(StrokeCodec.encode(record)).stabilizer!!
        assertEquals(signed.strength.toRawBits(), back.strength.toRawBits())
        assertEquals(signed.radiusPx.toRawBits(), back.radiusPx.toRawBits())
    }

    @Test fun eachTruncatedPayloadAndV6RecordOrListTrailerRefuses() {
        val authored = wire(6, captured(base()), payload())
        for (available in 0 until 11) assertFailsWith<StrokeCodecException>("$available actual bytes") {
            StrokeCodec.decode(authored.bytes.copyOf(authored.payloadOffset + available))
        }
        assertFailsWith<StrokeCodecException> { StrokeCodec.decode(authored.bytes + byteArrayOf(0)) }
        assertFailsWith<StrokeCodecException> { StrokeCodec.decodeAll(list(wire(1).bytes, authored.bytes) + byteArrayOf(0)) }
        for (version in 1..3) {
            val old = wire(version).bytes
            assertSamples(base(), StrokeCodec.decode(old + byteArrayOf(1, 2)))
            assertEquals(1, StrokeCodec.decodeAll(list(old) + byteArrayOf(1)).size)
        }
        for (version in 4..6) {
            val strict = wire(version).bytes
            assertFailsWith<StrokeCodecException> { StrokeCodec.decode(strict + byteArrayOf(0)) }
            assertFailsWith<StrokeCodecException> { StrokeCodec.decodeAll(list(strict) + byteArrayOf(0)) }
        }
    }

    @Test fun capturedAllMissingSensorPayloadBitsAndSourceMetadataStayExact() {
        val raw = base().samples.first()
        val before = captured(base().copy(samples = listOf(raw))).copy(stabilizer = StabilizerSpec(StabilizerMode.off))
        val back = StrokeCodec.decode(StrokeCodec.encode(before))
        assertSamples(before, back)
        assertEquals(before.capturedPhysicalSource, back.capturedPhysicalSource)
        assertEquals(before.secondaryColorArgb, back.secondaryColorArgb)
        assertEquals(before.stabilizer, back.stabilizer)
        assertTrue(CapturedStrokeRules.sameRecord(before, back))
    }
}

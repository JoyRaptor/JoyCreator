package cc.joycreator.joybrush.core.stroke

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.io.ByteReader
import cc.joycreator.joybrush.core.io.ByteWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Authored wire/source tests only; no renderer, archive, document-version or runtime admission. */
class CapturedStrokeCodecTest {
    private fun ordinary() = StrokeRecord("line", "ink", Long.MIN_VALUE, 0.5f, 2f,
        listOf(PenSample(-12f, 300f, -0.25, 0.7f, 0f, 0f, 0f)),
        colorArgb = 0x80442211.toInt(), widthScale = 2f, seq = 7L)

    private fun captured() = ordinary().copy(capturedPhysicalSource = CapturedPhysicalSource(
        CaptureDomain(CaptureRect(-100, -200, 500, 700), listOf(
            CaptureRect(10, 20, 30, 40), CaptureRect(-20, -30, 40, 80))), SourcePlacement(-257, 513)))

    private fun int(w: ByteWriter, n: Int) = w.u32(n.toLong() and 0xFFFF_FFFFL)
    private fun rect(w: ByteWriter, r: CaptureRect) {
        int(w, r.x); int(w, r.y); int(w, r.w); int(w, r.h)
    }

    private fun block(version: Int = 1, flags: Int = 0, window: CaptureRect? = null,
                      exclusions: List<CaptureRect> = emptyList(), count: Int = exclusions.size,
                      dx: Int = 0, dy: Int = 0): ByteArray {
        val w = ByteWriter()
        w.u8(version); w.u8(flags)
        window?.let { rect(w, it) }
        w.u16(count)
        for (r in exclusions) rect(w, r)
        int(w, dx); int(w, dy)
        return w.toByteArray()
    }

    private fun countOffset(bytes: ByteArray): Int {
        val r = ByteReader(bytes)
        r.bytes(6)
        val flags = r.u16()
        r.utf8(); r.utf8(); r.i64(); r.f32(); r.f32(); r.u32(); r.f32(); r.i64()
        if (flags and 16 != 0) r.utf8()
        return r.position
    }

    private fun wire(block: ByteArray = block(), record: StrokeRecord = ordinary()): ByteArray {
        val base = StrokeCodec.encode(record.copy(capturedPhysicalSource = null))
        val at = countOffset(base)
        base[6] = (base[6].toInt() or 32).toByte()
        return base.copyOfRange(0, at) + block + base.copyOfRange(at, base.size)
    }

    private fun listWire(vararg records: ByteArray): ByteArray {
        val w = ByteWriter()
        w.u32(records.size.toLong())
        for (r in records) { w.u32(r.size.toLong()); w.bytes(r) }
        return w.toByteArray()
    }

    private fun legacy(version: Int): ByteArray {
        val w = ByteWriter()
        w.bytes("JBS1".encodeToByteArray()); w.u16(version); w.u16(0)
        w.utf8("old"); w.utf8("ink"); w.i64(8); w.f32(0.5f); w.f32(1f)
        if (version >= 2) { w.u32(0xFF112233L); w.f32(Float.NaN) }
        if (version >= 3) w.i64(19)
        w.u32(1); w.f64(1.0); w.f32(1f); w.f32(2f); w.f32(1f); w.u8(0)
        return w.toByteArray()
    }

    /** A captured v4 record has the established v4 header/metadata followed by its capture block. */
    private fun capturedV4(): ByteArray = StrokeCodec.encode(captured()).also {
        // A null wash frame means the v5 writer emits the identical record body; only the version differs.
        it[4] = 4
        it[5] = 0
    }

    private fun refuses(bytes: ByteArray, contains: String) {
        val e = assertFailsWith<StrokeCodecException> { StrokeCodec.decode(bytes) }
        assertTrue(e.message.orEmpty().contains(contains), e.message)
    }

    @Test fun ordinaryWriterIsV5WithCapturedAndWashFrameFlagsClear() {
        val bytes = StrokeCodec.encode(ordinary())
        assertEquals(5, StrokeCodec.VERSION)
        assertEquals(5, bytes[4].toInt())
        assertEquals(0, bytes[6].toInt() and 32)
        assertEquals(0, bytes[6].toInt() and 64)
        assertEquals(ordinary(), StrokeCodec.decode(bytes))
    }

    @Test fun boundedCapturedRecipeAndPlacementRoundTripExactly() {
        val record = captured().copy(locked = true, groupId = "group")
        val bytes = StrokeCodec.encode(record)
        assertEquals(record, StrokeCodec.decode(bytes))
        assertContentEquals(bytes, StrokeCodec.encode(StrokeCodec.decode(bytes)))
    }

    @Test fun independentlyWrittenBlockMatchesWriterByteLayout() {
        val window = CaptureRect(-512, -256, 1024, 768)
        val exclusions = listOf(CaptureRect(-3, -4, 5, 6), CaptureRect(0, 0, 3, 4))
        val base = ordinary().copy(locked = true, groupId = "g")
        val source = CapturedPhysicalSource(CaptureDomain(window, exclusions), SourcePlacement(-300, 600))
        val bytes = wire(block(flags = 1, window = window, exclusions = exclusions, dx = -300, dy = 600), base)
        assertEquals(base.copy(capturedPhysicalSource = source), StrokeCodec.decode(bytes))
        assertContentEquals(bytes, StrokeCodec.encode(base.copy(capturedPhysicalSource = source)))
    }

    @Test fun unboundedComplementAndOverlappingExclusionsRoundTrip() {
        val a = CaptureRect(-3, -4, 20, 20)
        val b = CaptureRect(0, 0, 40, 40)
        val source = CapturedPhysicalSource(CaptureDomain(null, listOf(b, a, a)))
        val record = ordinary().copy(capturedPhysicalSource = source)
        assertEquals(source, StrokeCodec.decode(StrokeCodec.encode(record)).capturedPhysicalSource)
        assertEquals(listOf(a, b), source.domain.excluded)
    }

    @Test fun exclusiveMaxEdgeAndExtremePlacementRoundTrip() {
        val source = CapturedPhysicalSource(CaptureDomain(CaptureRect(Int.MAX_VALUE, Int.MIN_VALUE, 1, 1)),
            SourcePlacement(Int.MIN_VALUE, Int.MAX_VALUE))
        val record = ordinary().copy(capturedPhysicalSource = source)
        assertEquals(source, StrokeCodec.decode(StrokeCodec.encode(record)).capturedPhysicalSource)
        // Placement validity depends on the bounded query; serialization never silently clamps it.
        assertFailsWith<IllegalArgumentException> { source.placement.sourceToDestination(0, 1) }
    }

    @Test fun allMissingSensorsPreserveNoncanonicalNaNPayloadBits() {
        val s = ordinary().samples.single().copy(tilt = Float.fromBits(0x7FC00001),
            azimuth = Float.fromBits(0xFFC00002.toInt()), barrel = Float.fromBits(0x7FC12345))
        val r = captured().copy(samples = listOf(s))
        val bytes = StrokeCodec.encode(r)
        assertEquals(7, bytes[6].toInt() and 7)
        val back = StrokeCodec.decode(bytes).samples.single()
        assertEquals(s.tilt.toRawBits(), back.tilt.toRawBits())
        assertEquals(s.azimuth.toRawBits(), back.azimuth.toRawBits())
        assertEquals(s.barrel.toRawBits(), back.barrel.toRawBits())
        assertContentEquals(bytes, StrokeCodec.encode(StrokeCodec.decode(bytes)))
    }

    @Test fun validSignedZerosAndBoundaryPressureRemainRaw() {
        val a = PenSample(-0.0f, 0.0f, -0.0, 0f, -0.0f, Float.NaN, Float.MIN_VALUE)
        val b = a.copy(pressure = 1f)
        val back = StrokeCodec.decode(StrokeCodec.encode(captured().copy(samples = listOf(a, b)))).samples
        assertEquals(a.x.toRawBits(), back[0].x.toRawBits())
        assertEquals(a.y.toRawBits(), back[0].y.toRawBits())
        assertEquals(a.timeMs.toRawBits(), back[0].timeMs.toRawBits())
        assertEquals(a.tilt.toRawBits(), back[0].tilt.toRawBits())
        assertEquals(a.barrel.toRawBits(), back[0].barrel.toRawBits())
        assertEquals(listOf(0f, 1f), back.map { it.pressure })
    }

    @Test fun samplesOutsideDomainAreRetainedWithoutTrimming() {
        val record = captured().copy(samples = listOf(PenSample(-9999f, 9999f, 0.0)))
        assertEquals(record.samples, StrokeCodec.decode(StrokeCodec.encode(record)).samples)
    }

    @Test fun legacyLayoutsDefaultNullAndRetainWidthRepair() {
        for (version in 1..4) {
            val back = StrokeCodec.decode(legacy(version))
            assertNull(back.capturedPhysicalSource)
            assertNull(back.washFrame)
            assertEquals(1f, back.widthScale)
            assertEquals(if (version >= 3) 19L else 0L, back.seq)
        }
    }

    @Test fun legacyRecordsAndLegacyOnlyListsKeepTrailerPolicyAndFileOrder() {
        for (version in 1..3) assertEquals(StrokeCodec.decode(legacy(version)),
            StrokeCodec.decode(legacy(version) + byteArrayOf(99)))
        val back = StrokeCodec.decodeAll(listWire(legacy(1), legacy(2), legacy(3)) + byteArrayOf(99))
        assertEquals(listOf(1L, 2L, 19L), back.map { it.seq })
        assertEquals(emptyList(), StrokeCodec.decodeAll(byteArrayOf(0, 0, 0, 0, 99)))
    }

    @Test fun v4AndV5OrdinaryAndCapturedRecordTrailersRefused() {
        refuses(legacy(4) + byteArrayOf(99), "trailing")
        refuses(capturedV4() + byteArrayOf(99), "trailing")
        refuses(StrokeCodec.encode(ordinary()) + byteArrayOf(99), "trailing")
        refuses(StrokeCodec.encode(captured()) + byteArrayOf(99), "trailing")
    }

    @Test fun capturedV4FixtureRetainsCaptureAndDefaultsWashFrameToNull() {
        val bytes = capturedV4()
        assertEquals(4, bytes[4].toInt())
        assertEquals(captured(), StrokeCodec.decode(bytes))
        assertNull(StrokeCodec.decode(bytes).washFrame)
    }

    @Test fun mixedListOuterTrailerRefusedInEitherOrder() {
        val v4 = legacy(4)
        for (bytes in listOf(listWire(legacy(3), v4), listWire(v4, legacy(1)))) {
            assertEquals(2, StrokeCodec.decodeAll(bytes).size)
            assertFailsWith<StrokeCodecException> { StrokeCodec.decodeAll(bytes + byteArrayOf(99)) }
        }
    }

    @Test fun v4AndV5ListsDoNotPermitInnerRecordTrailer() {
        assertFailsWith<StrokeCodecException> {
            StrokeCodec.decodeAll(listWire(legacy(4) + byteArrayOf(0)))
        }
        assertFailsWith<StrokeCodecException> {
            StrokeCodec.decodeAll(listWire(capturedV4() + byteArrayOf(0)))
        }
        assertFailsWith<StrokeCodecException> {
            StrokeCodec.decodeAll(listWire(StrokeCodec.encode(captured()) + byteArrayOf(0)))
        }
    }

    @Test fun mixedListRetainsLegacyInnerTrailerPolicy() {
        val back = StrokeCodec.decodeAll(listWire(legacy(2) + byteArrayOf(99), capturedV4()))
        assertEquals(1L, back[0].seq)
        assertEquals(captured(), back[1])
    }

    @Test fun capturedFlagIsUnknownInEveryLegacyVersion() {
        for (version in 1..3) refuses(legacy(version).also { it[6] = 32 }, "flags")
    }

    @Test fun washFrameFlagIsUnknownInV4() {
        refuses(legacy(4).also { it[6] = (it[6].toInt() or 64).toByte() }, "flags")
    }

    @Test fun unknownCapturedSourceVersionRefused() { refuses(wire(block(version = 2)), "source version") }
    @Test fun unknownDomainFlagRefused() { refuses(wire(block(flags = 2)), "domain flags") }

    @Test fun exclusionBudgetCheckedBeforePayloadAllocation() {
        refuses(wire(block(count = CAPTURE_DOMAIN_MAX_EXCLUSIONS + 1)), "too many exclusions")
        refuses(wire(block(count = 65535)), "too many exclusions")
    }

    @Test fun exactlyMaximumExclusionsAccepted() {
        val exclusions = List(CAPTURE_DOMAIN_MAX_EXCLUSIONS) { CaptureRect(it * 2, 0, 1, 1) }
        val back = StrokeCodec.decode(wire(block(exclusions = exclusions)))
        assertEquals(exclusions, back.capturedPhysicalSource!!.domain.excluded)
    }

    @Test fun duplicateAndOutOfOrderWireExclusionsRefused() {
        val a = CaptureRect(0, 0, 2, 2)
        val b = CaptureRect(1, 0, 2, 2)
        refuses(wire(block(exclusions = listOf(a, a))), "canonical order")
        refuses(wire(block(exclusions = listOf(b, a))), "canonical order")
        assertEquals(listOf(a, b), StrokeCodec.decode(wire(block(exclusions = listOf(a, b))))
            .capturedPhysicalSource!!.domain.excluded)
    }

    @Test fun canonicalTieBreakUsesHeightThenWidth() {
        val a = CaptureRect(-1, -1, 2, 1)
        val b = CaptureRect(-1, -1, 1, 2)
        refuses(wire(block(exclusions = listOf(b, a))), "canonical order")
        assertEquals(listOf(a, b), StrokeCodec.decode(wire(block(exclusions = listOf(a, b))))
            .capturedPhysicalSource!!.domain.excluded)
    }

    @Test fun truncatedCapturedBlockEveryPrefixRefused() {
        val full = wire(block(flags = 1, window = CaptureRect(0, 0, 2, 2),
            exclusions = listOf(CaptureRect(-1, -1, 1, 1))))
        for (length in 0 until full.size) assertFailsWith<StrokeCodecException>("prefix $length") {
            StrokeCodec.decode(full.copyOf(length))
        }
    }

    @Test fun nonpositiveExtentAndEndpointOverflowRefused() {
        fun malformedRect(x: Int, y: Int, width: Int, height: Int): ByteArray {
            val w = ByteWriter()
            w.u8(1); w.u8(1); int(w, x); int(w, y); int(w, width); int(w, height)
            w.u16(0); int(w, 0); int(w, 0)
            return w.toByteArray()
        }
        for (r in listOf(intArrayOf(0, 0, 0, 1), intArrayOf(0, 0, 1, -1),
            intArrayOf(Int.MAX_VALUE, 0, 2, 1), intArrayOf(0, Int.MAX_VALUE, 1, 2))) {
            refuses(wire(malformedRect(r[0], r[1], r[2], r[3])), "rectangle")
        }
    }

    @Test fun malformedExclusionBoundsUseCodecException() {
        for (width in listOf(0, -1, 2)) {
            val w = ByteWriter()
            w.u8(1); w.u8(0); w.u16(1)
            int(w, Int.MAX_VALUE); int(w, 0); int(w, width); int(w, 1)
            int(w, 0); int(w, 0)
            refuses(wire(w.toByteArray()), "rectangle")
        }
    }

    @Test fun sampleCountUsesActualFlaggedStrideBeforeAllocation() {
        // A one-sample fixture carries 33 bytes. Two 21-byte MIN_SAMPLE_BYTES samples cannot
        // fit either; append 9 bytes to expose a count that passes only the old minimum check.
        val source = block()
        val bytes = wire(source) + ByteArray(9)
        val countAt = countOffset(StrokeCodec.encode(ordinary())) + source.size
        bytes[countAt] = 2
        refuses(bytes, "truncated")
    }

    @Test fun capturedWidthRefusesRatherThanRepairingAtBothBoundaries() {
        for (width in listOf(0f, 0.049f, 20.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<StrokeCodecException> { StrokeCodec.encode(captured().copy(widthScale = width)) }
            refuses(wire(record = ordinary().copy(widthScale = width)), "widthScale")
        }
        for (width in listOf(StrokeEdit.MIN_WIDTH_SCALE, StrokeEdit.MAX_WIDTH_SCALE)) {
            assertEquals(width, StrokeCodec.decode(StrokeCodec.encode(captured().copy(widthScale = width))).widthScale)
        }
    }

    @Test fun ordinaryV4WidthRepairRemainsCompatible() {
        assertEquals(1f, StrokeCodec.decode(StrokeCodec.encode(ordinary().copy(widthScale = Float.NaN))).widthScale)
        assertEquals(StrokeEdit.MAX_WIDTH_SCALE,
            StrokeCodec.decode(StrokeCodec.encode(ordinary().copy(widthScale = Float.MAX_VALUE))).widthScale)
    }

    @Test fun capturedHeaderNumericsRefusedOnWriteAndRead() {
        val invalid = listOf(ordinary().copy(smoothing = Float.NaN), ordinary().copy(smoothing = -0.01f),
            ordinary().copy(smoothing = 1.01f), ordinary().copy(screenPerDoc = 0f),
            ordinary().copy(screenPerDoc = Float.POSITIVE_INFINITY))
        for (r in invalid) {
            assertFailsWith<StrokeCodecException> { StrokeCodec.encode(r.copy(capturedPhysicalSource = captured().capturedPhysicalSource)) }
            refuses(wire(record = r), "captured stroke")
        }
    }

    @Test fun capturedIdentityAndNegativeSequenceRefused() {
        for (r in listOf(captured().copy(id = " "), captured().copy(brushId = ""),
            captured().copy(groupId = " "), captured().copy(seq = -1))) {
            assertFailsWith<StrokeCodecException> { StrokeCodec.encode(r) }
        }
        refuses(wire(record = ordinary().copy(id = "")), "id")
        assertTrue(CapturedStrokeRules.problems(captured().copy(seq = 0)).isEmpty())
    }

    @Test fun invalidCapturedSampleNumericsRefusedWithoutFiltering() {
        val sample = ordinary().samples.single()
        val invalid = listOf(sample.copy(x = Float.NaN), sample.copy(y = Float.NEGATIVE_INFINITY),
            sample.copy(timeMs = Double.NaN), sample.copy(pressure = Float.NaN),
            sample.copy(pressure = -0.1f), sample.copy(pressure = 1.1f),
            sample.copy(tilt = Float.POSITIVE_INFINITY), sample.copy(azimuth = Float.NEGATIVE_INFINITY),
            sample.copy(barrel = Float.POSITIVE_INFINITY))
        for (s in invalid) {
            assertFailsWith<StrokeCodecException> { StrokeCodec.encode(captured().copy(samples = listOf(s))) }
            refuses(wire(record = ordinary().copy(samples = listOf(s))), "sample 0")
        }
    }

    @Test fun predictedSampleInsertedThroughCallerAliasRefusedAtEncoding() {
        val samples = mutableListOf(ordinary().samples.single())
        val record = captured().copy(samples = samples)
        samples[0] = samples[0].copy(predicted = true)
        assertTrue(CapturedStrokeRules.problems(record).any { it.contains("placeable") })
        assertFailsWith<StrokeCodecException> { StrokeCodec.encode(record) }
    }

    @Test fun sourceDomainIsImmutableAgainstCallerListChanges() {
        val exclusions = mutableListOf(CaptureRect(0, 0, 1, 1))
        val record = captured().copy(capturedPhysicalSource = CapturedPhysicalSource(CaptureDomain(null, exclusions)))
        val bytes = StrokeCodec.encode(record)
        exclusions.clear()
        assertContentEquals(bytes, StrokeCodec.encode(record))
        val back = StrokeCodec.decode(bytes).capturedPhysicalSource!!.domain.excluded
        assertTrue(back !is MutableList<*>)
        assertEquals(listOf(CaptureRect(0, 0, 1, 1)), back)
    }

    @Test fun ordinaryRulesRemainUnchangedAndEmptyCapturedRecipeIsRepresentable() {
        assertTrue(CapturedStrokeRules.problems(ordinary().copy(smoothing = Float.NaN)).isEmpty())
        val empty = captured().copy(samples = emptyList())
        assertEquals(empty, StrokeCodec.decode(StrokeCodec.encode(empty)))
        // Cel membership/nonempty contact admission remains the consumer's responsibility.
    }
}

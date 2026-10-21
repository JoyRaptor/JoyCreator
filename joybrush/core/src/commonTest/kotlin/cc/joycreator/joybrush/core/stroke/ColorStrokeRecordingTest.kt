package cc.joycreator.joybrush.core.stroke

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ColorDynamicsSpec
import cc.joycreator.joybrush.core.brush.ColorJitter
import cc.joycreator.joybrush.core.brush.ColorMode
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.Tool
import cc.joycreator.joybrush.core.io.ByteReader
import cc.joycreator.joybrush.core.io.ByteWriter
import cc.joycreator.joybrush.core.vector.InkReplay
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ColorStrokeRecordingTest {
    private fun record() = StrokeRecord("color-stroke", "color-brush", Long.MIN_VALUE, .25f, 2f,
        listOf(PenSample(-0f, 0f, .125, .2f, 0f, 0f, 0f),
            PenSample(32f, 4f, 100.375, .8f, Float.fromBits(0x7FC00123),
                Float.fromBits(0x7FC00456), Float.fromBits(0x7FC00789))),
        colorArgb = 0xFFFF0000.toInt(), seq = 7L, locked = true, groupId = "color-group")

    private fun source() = CapturedPhysicalSource(
        CaptureDomain(CaptureRect(-10, -20, 50, 60), listOf(CaptureRect(1, 2, 3, 4))), SourcePlacement(-5, 8))

    private fun flags(bytes: ByteArray) = ByteReader(bytes, 6).u16()

    /** Independent walk of the documented fixed header and optional captured block. */
    private fun paletteOffset(bytes: ByteArray): Int {
        val r = ByteReader(bytes)
        assertContentEquals("JBS1".encodeToByteArray(), r.bytes(4))
        assertEquals(6, r.u16())
        val flags = r.u16()
        r.utf8(); r.utf8(); r.i64(); r.f32(); r.f32(); r.u32(); r.f32(); r.i64()
        if (flags and 16 != 0) r.utf8()
        if (flags and 32 != 0) {
            assertEquals(1, r.u8())
            val domainFlags = r.u8()
            if (domainFlags and 1 != 0) repeat(4) { r.u32() }
            repeat(r.u16()) { repeat(4) { r.u32() } }
            r.u32(); r.u32()
        }
        return r.position
    }

    @Test fun optionalSecondaryIsFourRawBytesAfterCaptureBeforeSampleCount() {
        for (captured in listOf(false, true)) for (secondary in listOf(0, -1, Int.MIN_VALUE, Int.MAX_VALUE, 0x12345678)) {
            val base = record().copy(capturedPhysicalSource = if (captured) source() else null)
            val old = StrokeCodec.encode(base)
            val withPalette = StrokeCodec.encode(base.copy(secondaryColorArgb = secondary))
            assertEquals(old.size + 4, withPalette.size)
            assertEquals(flags(old) or 64, flags(withPalette))
            val at = paletteOffset(withPalette)
            val payload = ByteReader(withPalette, at)
            assertEquals(secondary.toLong() and 0xFFFF_FFFFL, payload.u32())
            assertEquals(base.samples.size.toLong(), payload.u32())
            val removed = withPalette.copyOfRange(0, at) + withPalette.copyOfRange(at + 4, withPalette.size)
            removed[6] = (removed[6].toInt() and 64.inv()).toByte()
            assertContentEquals(old, removed, "an absent palette has exactly the old header/body layout")
            val back = StrokeCodec.decode(withPalette)
            assertEquals(secondary, back.secondaryColorArgb)
            assertEquals(base.colorArgb, back.colorArgb)
            assertEquals(base.capturedPhysicalSource, back.capturedPhysicalSource)
            assertRawSamples(base, back)
            assertContentEquals(withPalette, StrokeCodec.encode(back))
        }
    }

    @Test fun legacyVersionsReadWithoutPaletteAndRefuseTheNewFlag() {
        for (version in 1..4) {
            val bytes = legacy(version)
            val back = StrokeCodec.decode(bytes)
            assertNull(back.secondaryColorArgb)
            assertEquals(if (version == 1) StrokeCodec.DEFAULT_COLOR_ARGB else 0x80123456.toInt(), back.colorArgb)
            assertEquals(if (version < 3) 0L else 12L, back.seq)
            assertFailsWith<StrokeCodecException> {
                StrokeCodec.decode(bytes.copyOf().also { it[6] = (it[6].toInt() or 64).toByte() })
            }
        }
        val v4Captured = StrokeCodec.encode(record().copy(capturedPhysicalSource = source()))
            .also { it[4] = 4 }
        assertEquals(source(), StrokeCodec.decode(v4Captured).capturedPhysicalSource)
        assertNull(StrokeCodec.decode(v4Captured).secondaryColorArgb)
        for (v4 in listOf(legacy(4), v4Captured)) assertFailsWith<StrokeCodecException> {
            StrokeCodec.decode(v4 + byteArrayOf(0))
        }
        assertFailsWith<StrokeCodecException> {
            StrokeCodec.decode(v4Captured.copyOf().also { it[6] = (it[6].toInt() or 64).toByte() })
        }
    }

    @Test fun secondaryHeaderCannotNormalizeOrdinaryRawSampleBits() {
        val base = record()
        val r = base.copy(secondaryColorArgb = Int.MIN_VALUE, samples = listOf(
            base.samples[0].copy(y = Float.fromBits(0x7FC00ABC),
                timeMs = Double.fromBits(0x7FF8000000000ABCL), pressure = Float.fromBits(0x7FC00DEF)),
            base.samples[1]))
        val bytes = StrokeCodec.encode(r)
        val back = StrokeCodec.decode(bytes)
        assertEquals(Int.MIN_VALUE, back.secondaryColorArgb)
        assertRawSamples(r, back)
        assertContentEquals(bytes, StrokeCodec.encode(back))
    }

    @Test fun paletteTruncationUnknownBitsAndStrictTrailersRefuse() {
        val r = record().copy(secondaryColorArgb = 0xABCDEF01.toInt(), capturedPhysicalSource = source())
        val bytes = StrokeCodec.encode(r)
        val at = paletteOffset(bytes)
        for (available in 0..3) assertFailsWith<StrokeCodecException>("$available secondary bytes") {
            StrokeCodec.decode(bytes.copyOf(at + available))
        }
        assertFailsWith<StrokeCodecException> {
            StrokeCodec.decode(bytes.copyOf().also { it[7] = (it[7].toInt() or 1).toByte() })
        }
        assertFailsWith<StrokeCodecException> { StrokeCodec.decode(bytes + byteArrayOf(0)) }
        assertFailsWith<StrokeCodecException> { StrokeCodec.decodeAll(StrokeCodec.encodeAll(listOf(r)) + byteArrayOf(0)) }
        val modernNull = StrokeCodec.decode(StrokeCodec.encode(record()))
        assertNull(modernNull.secondaryColorArgb)
        assertEquals(listOf(record(), r), StrokeCodec.decodeAll(StrokeCodec.encodeAll(listOf(record(), r))))
    }

    @Test fun capturedSourceRecipeIncludesSecondaryAndCannotChangeItThroughMetadataEdits() {
        val captured = record().copy(capturedPhysicalSource = source(), secondaryColorArgb = 0xAA112233.toInt())
        assertTrue(CapturedStrokeRules.sameRecord(captured, CapturedStrokeRules.snapshot(captured)))
        assertFalse(CapturedStrokeRules.sameRecord(captured, captured.copy(secondaryColorArgb = null)))
        assertFalse(CapturedStrokeRules.sameRecord(captured, captured.copy(secondaryColorArgb = -1)))
        assertFailsWith<IllegalArgumentException> {
            CapturedStrokeRules.requireSupportedReplacement(captured, captured.copy(secondaryColorArgb = -1))
        }
        val moved = captured.copy(locked = false, groupId = "new-group",
            capturedPhysicalSource = captured.capturedPhysicalSource!!.copy(placement = SourcePlacement(10, 20)))
        CapturedStrokeRules.requireSupportedReplacement(captured, moved)
        assertEquals(captured.secondaryColorArgb, moved.secondaryColorArgb)
    }

    @Test fun replayAndAdmittedReplayUseFrozenPaletteIncludingLegacyWhiteFallback() {
        val brush = brush()
        val r = record().copy(secondaryColorArgb = 0xFF0000FF.toInt())
        val dabs = InkReplay.dabs(r, brush)
        assertTrue(dabs.isNotEmpty())
        assertTrue(dabs.all { it.color != null })
        assertEquals(dabs, InkReplay.dabs(StrokeCodec.decode(StrokeCodec.encode(r)), brush))
        val admitted = ArrayList<cc.joycreator.joybrush.core.paint.Dab>()
        assertEquals(dabs, InkReplay.replayAdmitted(r, brush, admitted::add).dabs)
        assertEquals(dabs, admitted)
        val fallback = InkReplay.dabs(r.copy(secondaryColorArgb = null), brush)
        assertEquals(fallback, InkReplay.dabs(r.copy(secondaryColorArgb = -1), brush))
        assertNotEquals(dabs.map { it.color }, fallback.map { it.color })
    }

    @Test fun recolorReweightAndRebrushKeepPaletteSeedAndRawRecipe() {
        val r = record().copy(secondaryColorArgb = 0xFF0000FF.toInt())
        val brush = brush()
        val original = InkReplay.dabs(r, brush)
        val recolored = StrokeEdit.recolor(r, 0xFF00FF00.toInt())
        assertEquals(r.copy(colorArgb = 0xFF00FF00.toInt()), recolored)
        val changed = InkReplay.dabs(recolored, brush)
        assertEquals(original.map { it.copy(color = null) }, changed.map { it.copy(color = null) })
        for (i in original.indices) {
            val a = assertNotNull(original[i].color); val b = assertNotNull(changed[i].color)
            assertEquals(a.r, b.g)
            assertEquals(a.b, b.b)
            assertEquals(0f, a.g)
            assertEquals(0f, b.r)
        }
        val heavy = StrokeEdit.reweight(r, 2f)
        assertEquals(r.secondaryColorArgb, heavy.secondaryColorArgb)
        assertEquals(original.map { it.color }, InkReplay.dabs(heavy, brush).map { it.color })
        val rebrushed = StrokeEdit.rebrush(r, brush.id)
        assertEquals(r.seed, rebrushed.seed)
        assertEquals(r.secondaryColorArgb, rebrushed.secondaryColorArgb)
        assertRawSamples(r, rebrushed)
    }

    private fun brush() = BrushPreset(id = "color-brush", name = "Color", size = Param(12f),
        color = ColorJitter(dynamics = ColorDynamicsSpec(mode = ColorMode.perDab, secondaryMix = .8f)))

    private fun assertRawSamples(expected: StrokeRecord, actual: StrokeRecord) {
        assertEquals(expected.samples.size, actual.samples.size)
        for (i in expected.samples.indices) {
            val a = expected.samples[i]; val b = actual.samples[i]
            assertEquals(a.x.toRawBits(), b.x.toRawBits())
            assertEquals(a.y.toRawBits(), b.y.toRawBits())
            assertEquals(a.timeMs.toRawBits(), b.timeMs.toRawBits())
            assertEquals(a.pressure.toRawBits(), b.pressure.toRawBits())
            assertEquals(a.tilt.toRawBits(), b.tilt.toRawBits())
            assertEquals(a.azimuth.toRawBits(), b.azimuth.toRawBits())
            assertEquals(a.barrel.toRawBits(), b.barrel.toRawBits())
            assertEquals(a.tool, b.tool)
        }
    }

    private fun legacy(version: Int): ByteArray = ByteWriter().let { w ->
        w.bytes("JBS1".encodeToByteArray()); w.u16(version); w.u16(0)
        w.utf8("legacy"); w.utf8("color-brush"); w.i64(-7L); w.f32(.5f); w.f32(2f)
        if (version >= 2) { w.u32(0x80123456); w.f32(1.5f) }
        if (version >= 3) w.i64(12L)
        w.u32(1); w.f64(.125); w.f32(2f); w.f32(3f); w.f32(.75f); w.u8(Tool.STYLUS.ordinal)
        w.toByteArray()
    }
}

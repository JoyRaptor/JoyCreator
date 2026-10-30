package cc.joycreator.joybrush.core.stroke

import cc.joycreator.joybrush.core.input.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DRAFT — not compiled/run. Actual contact settings and source recipe identity. */
class StabilizerCaptureIdentityTest {
    private val raw = (0..12).map { i -> PenSample(i * 3f + 7f, if (i % 2 == 0) 12f else 18f,
        i * 7.0, .2f + i * .03f, Float.fromBits(0x7FC00ABC), -.3f, Float.NaN) }
    private fun sampleBits(s: PenSample) = listOf(s.x.toRawBits().toLong(), s.y.toRawBits().toLong(),
        s.timeMs.toRawBits(), s.pressure.toRawBits().toLong(), s.tilt.toRawBits().toLong(),
        s.azimuth.toRawBits().toLong(), s.barrel.toRawBits().toLong(), s.tool.ordinal.toLong())

    @Test fun captureKeepsPostGuidePreResponsePreFilterRawAndResolvedSettings() {
        val suggestion = StabilizerSpec(StabilizerMode.pulledString, .8f, 17f, 5)
        var preference = StabilizerPreference(StabilizerPolicy.brushSuggestion,
            StabilizerSpec(StabilizerMode.movingAverage, .5f, 9f, 4))
        val actual = preference.resolve(suggestion, .3f)
        val capture = StrokeCapture("contact", "frozen", 414L, .3f, 2f, -0x123456,
            secondaryColorArgb = 0x12345678, stabilizer = actual)
        val filter = StrokeStabilizer(actual, .3f, 2f)
        val heard = ArrayList<PenSample>()
        raw.forEachIndexed { i, sample ->
            capture.add(sample)
            heard += filter.add(sample)
            if (i == 4) preference = preference.copy(policy = StabilizerPolicy.alwaysMine,
                mine = StabilizerSpec(StabilizerMode.off))
        }
        heard += filter.finish()
        val saved = assertNotNull(capture.finish())
        assertEquals(raw.map(::sampleBits), saved.samples.map(::sampleBits))
        assertNotEquals(raw.map(::sampleBits), heard.map(::sampleBits))
        assertEquals(actual, saved.stabilizer)
        assertNotEquals(actual, preference.resolve(suggestion, .3f))
        assertEquals(414L, saved.seed)
        assertEquals(2f, saved.screenPerDoc)
        assertEquals(0x12345678, saved.secondaryColorArgb)
    }

    @Test fun legacyConstructorLeavesActualModeAbsentAndInvalidNewContactSettingsRefuse() {
        val legacy = StrokeCapture("old", "frozen", 1, .3f, 1f, -1)
        legacy.add(raw.first())
        assertNull(assertNotNull(legacy.finish()).stabilizer)
        for (bad in listOf(StabilizerSpec(strength = Float.NaN), StabilizerSpec(radiusPx = -1f),
            StabilizerSpec(windowSize = Int.MAX_VALUE))) assertFailsWith<IllegalArgumentException> {
            StrokeCapture("bad", "frozen", 1, .3f, 1f, -1, stabilizer = bad)
        }
    }

    @Test fun recolorReweightAndRebrushKeepActualSettingsAndRawSamples() {
        val actual = StabilizerSpec(StabilizerMode.movingAverage, .8f, 12f, 7)
        val r = StrokeRecord("s", "frozen", 22, .2f, 1.5f, raw, secondaryColorArgb = 0x12345678, stabilizer = actual)
        val edited = StrokeEdit.rebrush(StrokeEdit.reweight(StrokeEdit.recolor(r, 0xFFABCDEF.toInt()), 2f), "other")
        assertEquals(actual, edited.stabilizer)
        assertEquals(r.seed, edited.seed)
        assertEquals(r.samples.map(::sampleBits), edited.samples.map(::sampleBits))
        assertEquals(r.secondaryColorArgb, edited.secondaryColorArgb)
        assertEquals(actual, StrokeCodec.decode(StrokeCodec.encode(edited)).stabilizer)
    }

    @Test fun capturedOriginalRecipeIncludesEveryActualStabilizerFieldAndSignedZero() {
        val actual = StabilizerSpec(StabilizerMode.movingAverage, .7f, 11f, 6)
        val source = CapturedPhysicalSource(CaptureDomain(CaptureRect(-100, -100, 300, 300)), SourcePlacement(7, -9))
        val captured = StrokeRecord("s", "frozen", 22, .2f, 1.5f, raw, seq = 4,
            capturedPhysicalSource = source, secondaryColorArgb = -1, stabilizer = actual)
        assertTrue(CapturedStrokeRules.sameRecord(captured, CapturedStrokeRules.snapshot(captured)))
        for (changed in listOf<StabilizerSpec?>(null, actual.copy(mode = StabilizerMode.off),
            actual.copy(strength = .8f), actual.copy(radiusPx = 12f), actual.copy(windowSize = 7))) {
            val after = captured.copy(stabilizer = changed)
            assertFalse(CapturedStrokeRules.sameRecord(captured, after))
            assertFailsWith<IllegalArgumentException> { CapturedStrokeRules.requireSupportedReplacement(captured, after) }
        }
        // Legal captured edits change placement or owner metadata, never the source recipe.
        CapturedStrokeRules.requireSupportedReplacement(captured,
            captured.copy(capturedPhysicalSource = source.copy(placement = SourcePlacement(-8, 14)), locked = true, groupId = "g"))
        assertTrue(CapturedStrokeRules.problems(captured.copy(stabilizer = actual.copy(strength = Float.NaN))).isNotEmpty())
        val minusZero = captured.copy(stabilizer = StabilizerSpec(StabilizerMode.off, -0f, -0f, 1))
        val plusZero = minusZero.copy(stabilizer = minusZero.stabilizer!!.copy(strength = 0f))
        assertFalse(CapturedStrokeRules.sameRecord(minusZero, plusZero))
    }
}

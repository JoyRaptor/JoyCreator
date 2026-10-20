package cc.joycreator.joybrush.core.brush

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ColorDynamicsTest {
    private val colors = StrokeColors(0xFF986438.toInt(), 0xFF2898C4.toInt())
    private val lively = ColorDynamicsSpec(hueRangeDegrees = 60f, saturationRange = .25f,
        valueRange = .15f, secondaryMix = .6f, pressureHueDegrees = 30f)

    @Test fun activeSwitchDependsOnEffectsRatherThanModeOrDriftPitch() {
        for (mode in ColorMode.entries) assertFalse(ColorDynamicsSpec(mode, driftDistancePx = 1f).isActive)
        for (spec in listOf(ColorDynamicsSpec(hueRangeDegrees = 1f),
            ColorDynamicsSpec(saturationRange = .1f), ColorDynamicsSpec(valueRange = .1f),
            ColorDynamicsSpec(secondaryMix = .1f), ColorDynamicsSpec(pressureHueDegrees = -1f))) {
            assertTrue(spec.isActive)
        }
    }

    @Test fun sameSeedAndCallsRepeatExactlyAndOtherSeedsChangeColor() {
        for (mode in ColorMode.entries) {
            val spec = lively.copy(mode = mode)
            val a = ColorDynamics(spec, 41L, colors)
            val b = ColorDynamics(spec, 41L, colors)
            val other = ColorDynamics(spec, 42L, colors)
            val first = ArrayList<ColorDab>()
            val control = ArrayList<ColorDab>()
            for (i in 0..40) {
                val x = i * 7.25f
                val p = (i % 9) / 8f
                val ca = a.at(x, p)
                assertEquals(ca, b.at(x, p))
                first.add(ca); control.add(other.at(x, p))
            }
            assertNotEquals(first, control)
        }
    }

    @Test fun disabledDynamicsReturnPrimaryChannelBitsInEveryMode() {
        val expected = ColorDab(152f / 255f, 100f / 255f, 56f / 255f)
        for (mode in ColorMode.entries) {
            val generator = ColorDynamics(ColorDynamicsSpec(mode), 99L, colors)
            for (i in 0..20) assertEquals(expected, generator.at(i * 3f, i / 20f))
        }
        // Alpha is not secretly another saturation, value or mixing control.
        assertEquals(expected, ColorDynamics(ColorDynamicsSpec(), 99L,
            StrokeColors(0x00986438, 0x002898C4)).at(0f, 1f))
    }

    @Test fun pressureHueHasKnownPrimaryColorAnswersAndWrapsBelowZero() {
        val red = StrokeColors(0xFFFF0000.toInt(), 0xFF000000.toInt())
        val positive = ColorDynamics(ColorDynamicsSpec(pressureHueDegrees = 120f), 1L, red)
        assertColor(ColorDab(1f, 0f, 0f), positive.at(0f, 0f))
        assertColor(ColorDab(1f, 1f, 0f), positive.at(0f, .5f))
        assertColor(ColorDab(0f, 1f, 0f), positive.at(0f, 1f))
        val negative = ColorDynamics(ColorDynamicsSpec(pressureHueDegrees = -120f), 1L, red)
        assertColor(ColorDab(0f, 0f, 1f), negative.at(0f, 1f))
        assertColor(ColorDab(1f, 0f, 0f), negative.at(0f, Float.NaN))
    }

    @Test fun perStrokeKeepsRandomColorButPressureStillReacts() {
        val spec = ColorDynamicsSpec(mode = ColorMode.perStroke, hueRangeDegrees = 30f, pressureHueDegrees = 60f)
        val generator = ColorDynamics(spec, 714L, colors)
        val start = generator.at(0f, 0f)
        for (distance in listOf(0f, 2f, 64f, 1024f, Float.MAX_VALUE))
            assertEquals(start, generator.at(distance, 0f))
        assertNotEquals(start, generator.at(100f, 1f))
        assertEquals(start, generator.at(200f, 0f))
    }

    @Test fun activeMixAndHsvIgnorePaletteAlphaBytes() {
        val opaque = ColorDynamics(lively, 63L, colors)
        val transparent = ColorDynamics(lively, 63L,
            StrokeColors(colors.primaryArgb and 0x00FFFFFF, colors.secondaryArgb and 0x00FFFFFF))
        for (i in 0..20) assertEquals(opaque.at(i * 5f, i / 20f), transparent.at(i * 5f, i / 20f))
    }

    @Test fun perDabColorChangesEvenWhenDistanceIsUnchanged() {
        val generator = ColorDynamics(lively.copy(pressureHueDegrees = 0f), 741L, colors)
        assertNotEquals(generator.at(0f, .5f), generator.at(0f, .5f))
    }

    @Test fun paletteRecolorPreservesSeededMixOperands() {
        // Mixing pure endpoint channels reveals the same draw without reproducing the RNG math.
        val spec = ColorDynamicsSpec(secondaryMix = .8f)
        for (mode in ColorMode.entries) {
            val actualSpec = spec.copy(mode = mode)
            val redBlue = ColorDynamics(actualSpec, 55L,
                StrokeColors(0xFFFF0000.toInt(), 0xFF0000FF.toInt()))
            val greenRed = ColorDynamics(actualSpec, 55L,
                StrokeColors(0xFF00FF00.toInt(), 0xFFFF0000.toInt()))
            for (i in 0..30) {
                val a = redBlue.at(i * 12f, .5f)
                val b = greenRed.at(i * 12f, .5f)
                assertEquals(a.b, b.r)
                assertEquals(a.r, b.g)
                assertEquals(0f, a.g)
                assertEquals(0f, b.b)
                assertTrue(a.b in 0f..spec.secondaryMix)
                assertEquals(1f, a.r + a.b, .000001f)
            }
        }
    }

    @Test fun driftIsContinuousAtKnotsAndIndependentOfInterveningCalls() {
        val spec = lively.copy(mode = ColorMode.perStrokeDrift, pressureHueDegrees = 0f)
        val generator = ColorDynamics(spec, 77L, colors)
        val pristine = ColorDynamics(spec, 77L, colors)
        for (knot in listOf(64f, 128f, 640f, 4096f)) {
            val center = generator.at(knot, .5f)
            assertColor(center, generator.at(knot - .01f, .5f), .00001f)
            assertColor(center, generator.at(knot + .01f, .5f), .00001f)
        }
        generator.at(1000000000f, 1f)
        generator.at(3f, 0f)
        for (distance in listOf(1f, 19f, 63f, 64f, 68f, 193f, 1000000f))
            assertEquals(pristine.at(distance, .4f), generator.at(distance, .4f))
        assertNotEquals(generator.at(0f, .5f), generator.at(64f, .5f))
    }

    @Test fun extremeDistancesAndUnvalidatedNumbersNeverProduceInvalidRgb() {
        val strange = lively.copy(mode = ColorMode.perStrokeDrift,
            hueRangeDegrees = Float.NaN, saturationRange = Float.POSITIVE_INFINITY,
            valueRange = 100f, secondaryMix = -2f, pressureHueDegrees = Float.NEGATIVE_INFINITY)
        val distances = listOf(0f, -1f, Float.MIN_VALUE, Float.MAX_VALUE,
            Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        for (pitch in listOf(Float.MIN_VALUE, 64f, Float.MAX_VALUE, 0f, -1f, Float.NaN)) {
            val generator = ColorDynamics(strange.copy(driftDistancePx = pitch), 0L, colors)
            for (d in distances) for (p in distances) bounded(generator.at(d, p))
        }
    }

    @Test fun maximumRangesClampSaturationAndValueAndKeepHueWrappingFinite() {
        for (mode in ColorMode.entries) for (palette in listOf(colors,
            StrokeColors(0xFFFFFFFF.toInt(), 0xFF000000.toInt()),
            StrokeColors(0xFF808080.toInt(), 0xFFFF00FF.toInt()))) {
            val generator = ColorDynamics(ColorDynamicsSpec(mode, 180f, 1f, 1f, 1f, -180f), -10L, palette)
            for (i in 0..300) {
                val c = generator.at(i * 3f, (i % 11) / 10f)
                bounded(c)
            }
        }
    }

    private fun bounded(c: ColorDab) {
        assertTrue(c.r.isFinite() && c.r in 0f..1f)
        assertTrue(c.g.isFinite() && c.g in 0f..1f)
        assertTrue(c.b.isFinite() && c.b in 0f..1f)
    }

    private fun assertColor(expected: ColorDab, actual: ColorDab, tolerance: Float = .000001f) {
        assertTrue(abs(expected.r - actual.r) <= tolerance, "red: $expected != $actual")
        assertTrue(abs(expected.g - actual.g) <= tolerance, "green: $expected != $actual")
        assertTrue(abs(expected.b - actual.b) <= tolerance, "blue: $expected != $actual")
    }
}

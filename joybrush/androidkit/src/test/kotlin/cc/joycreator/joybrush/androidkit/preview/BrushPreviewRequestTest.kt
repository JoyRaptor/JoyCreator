package cc.joycreator.joybrush.androidkit.preview

import cc.joycreator.joybrush.core.brush.*
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.StabilizerSpec
import cc.joycreator.joybrush.core.input.StabilizerPreference
import cc.joycreator.joybrush.core.input.StabilizerPolicy
import cc.joycreator.joybrush.core.input.Tool
import org.junit.Assert.*
import org.junit.Test

class BrushPreviewRequestTest {
    @Test fun overrideChangesOnlyMeasuredPressureAndTiltBeforeResponse() {
        val sample = PenSample(7f, 9f, 13.25, .2f, .1f, .6f, .7f, Tool.ERASER, true)
        val changed = PreviewPenOverride(.75f, 1f).apply(listOf(sample)).single()
        assertEquals(sample.copy(pressure = .75f, tilt = 1f), changed)
        assertEquals(.2f, sample.pressure, 0f)
        assertEquals(.1f, sample.tilt, 0f)
    }

    @Test fun overrideRejectsNaNInfinityAndOutOfRangeReadings() {
        for ((pressure, tilt) in listOf(Float.NaN to 0f, 1f to Float.POSITIVE_INFINITY,
            -.1f to 0f, 1.1f to 0f, .5f to -.1f, .5f to 2f)) {
            try { PreviewPenOverride(pressure, tilt); fail("Invalid sensor override accepted") }
            catch (expected: IllegalArgumentException) { }
        }
        PreviewPenOverride(0f, 0f)
        PreviewPenOverride(1f, kotlin.math.PI.toFloat() / 2f)
    }

    @Test fun actualSettingsOnUnsupportedEnginesAreExplicitlyRefused() {
        val brush = BrushPreset(id = "fill", name = "Fill", size = Param(24f), engine = ENGINE_FILL)
        try { BrushPreviewRequest.snapshot(brush, 64, 32, actualStabilizer = StabilizerSpec()); fail("Unsupported actual settings accepted") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("stamp preview")) }
        try { BrushPreviewRequest.snapshot(brush, 64, 32, penOverride = PreviewPenOverride(.5f, 0f)); fail("Unsupported override accepted") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("stamp or smudge")) }
    }

    @Test fun defaultPolicyChangesOnlyFamilyMarkedStampRecipesAndExplicitWireActualIsRefused() {
        val mine = StabilizerSpec(windowSize = 29)
        val preference = StabilizerPreference(StabilizerPolicy.alwaysMine, mine)
        val legacy = BrushPreset(id = "legacy", name = "Legacy", size = Param(24f), line = LineStyleSpec(screenWidthPx = 2f))
        assertNull(BrushPreviewRequest.defaultActualStabilizer(legacy, preference))
        assertNull(BrushPreviewRequest.snapshot(legacy, 64, 32).actualStabilizer)
        val plain = legacy.copy(line = null)
        for (family in listOf("builtin", "user")) {
            val modern = plain.copy(extensions = mapOf("unibrush.family" to family))
            assertEquals(mine, BrushPreviewRequest.defaultActualStabilizer(modern, preference))
            assertNull(BrushPreviewRequest.defaultActualStabilizer(modern.copy(engine = ENGINE_SMUDGE), preference))
        }
        assertNull(BrushPreviewRequest.defaultActualStabilizer(plain.copy(extensions = mapOf("unibrush.family" to "other")), preference))
        try { BrushPreviewRequest.snapshot(legacy, 64, 32, actualStabilizer = mine); fail("Wire actual accepted") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("wire previews")) }
        try { BrushPreviewRequest.snapshot(plain.copy(engine = ENGINE_SMUDGE), 64, 32, actualStabilizer = mine); fail("Smudge actual accepted") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("stamp preview")) }
        // Pen-only smudge previews stay on the existing stamp dab path and are supported.
        assertNotNull(BrushPreviewRequest.snapshot(plain.copy(engine = ENGINE_SMUDGE), 64, 32,
            penOverride = PreviewPenOverride(.5f, .2f)).penOverride)
    }

    @Test fun sampledPreviewCannotFallBackToInstalledLibraryOrAcceptUnreferencedPayloads() {
        val brush = BrushPreset(id = "sample", name = "Sample", size = Param(24f), tip = TipSpec(sampled = SampledTipSpec(listOf("tip.png"))))
        try { BrushPreviewRequest.snapshot(brush, 64, 32); fail("Missing images accepted") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("frozen PNG")) }
        try {
            BrushPreviewRequest.snapshot(brush, 64, 32, pngs = FrozenBrushPngs.snapshot(
                mapOf("tip.png" to byteArrayOf(1), "extra.png" to byteArrayOf(2))))
            fail("Unreferenced image accepted")
        } catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("unreferenced")) }
    }
}

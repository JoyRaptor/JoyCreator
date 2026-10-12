package cc.joycreator.joybrush.core.select

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.TipSpec
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.InkReplay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JB-5.20e core item 2 — a line through the Select box keeps "all the pressure, velocity, and tilt" (owner).
 * Expected values are exact: a translate adds, a ×2 doubles, a quarter turn adds PI/2 to the lean, a flip mirrors it.
 */
class LineTransformTest {

    private fun record(lean: Float = 0.3f, barrel: Float = Float.NaN) = StrokeRecord(
        id = "a", brushId = "joybrush.ink", seed = 7L, smoothing = 0f, screenPerDoc = 1.5f,
        samples = (0 until 30).map { PenSample(x = 10f + it * 4f, y = 20f, timeMs = it * 8.0, pressure = 0.2f + it * 0.02f, tilt = 0.6f, azimuth = lean, barrel = barrel) },
        colorArgb = 0xFF336699.toInt(), widthScale = 1.25f,
    )

    private fun close(a: Float, b: Float, eps: Float = 1e-4f) = abs(a - b) <= eps
    private fun angleClose(a: Float, b: Double) = abs(((a - b + 3 * PI) % (2 * PI)) - PI) < 1e-4

    @Test
    fun aMoveMovesThePointsAndNothingElse() {
        val r = record()
        val out = assertNotNull(LineTransform.apply(r, Homography.translate(15.0, -4.0), LineTransform.Weight.SCALE))
        for (i in r.samples.indices) {
            val a = r.samples[i]; val b = out.samples[i]
            assertTrue(close(a.x + 15f, b.x) && close(a.y - 4f, b.y), "sample $i moved by the move")
            assertEquals(a.copy(x = b.x, y = b.y), b, "sample $i: pressure, tilt, lean, barrel, time kept")
        }
        assertEquals(r.copy(samples = out.samples), out, "id, seed, brush, colour, smoothing, zoom and weight kept")
    }

    @Test
    fun doublingScalesWeightAndHalvesTheZoomItWasDrawnAt() {
        val r = record()
        val h = Homography.scale(2.0, 2.0, Pt(0.0, 0.0))
        val scaled = assertNotNull(LineTransform.apply(r, h, LineTransform.Weight.SCALE))
        assertTrue(close(r.widthScale * 2f, scaled.widthScale))
        assertTrue(close(r.screenPerDoc / 2f, scaled.screenPerDoc), "smoothing is screen-relative: it must be heard as drawn")
        val kept = assertNotNull(LineTransform.apply(r, h, LineTransform.Weight.KEEP))
        assertEquals(r.widthScale, kept.widthScale, "Keep weights")
        assertTrue(close(r.screenPerDoc / 2f, kept.screenPerDoc), "the zoom rule holds either way")
    }

    /**
     * What a doubled line replays as, pinned as it is today. Dab SPACING comes from the brush's own size, before the
     * record's widthScale is applied (InkReplayTest.widthScaleThickensTheReplay: "re-weighting does not re-space the
     * stroke"). So a line scaled x2 with its weight lays twice as many dabs along twice the length, each twice as big:
     * the same line for an opaque ink, a denser one for a translucent stamp that builds up. Spacing by size x widthScale
     * would be a replay change (the lead's / vector lane's call), flagged 2026-10-11; if it lands, this test tightens to
     * "the same dabs, doubled".
     */
    @Test
    fun aDoubledLineReplaysAlongTheDoubledPathAtTwiceTheSize() {
        val brush = BrushPreset(id = "joybrush.ink", name = "Ink", engine = "stamp", size = Param(6f),
            tip = TipSpec(corner = 2f, hardness = Param(1f)), spacing = 0.04f, accumulate = "wash")
        val r = record()
        val big = assertNotNull(LineTransform.apply(r, Homography.scale(2.0, 2.0, Pt(0.0, 0.0)), LineTransform.Weight.SCALE))
        val a = InkReplay.dabs(r, brush)
        val b = InkReplay.dabs(big, brush)
        assertTrue(a.size > 10)
        val ratio = b.size.toDouble() / a.size
        assertTrue(ratio in 1.9..2.1, "twice the length at the brush's own spacing: about twice the dabs, got $ratio")
        assertTrue(close(a.first().x * 2f, b.first().x, 1e-2f) && close(a.first().y * 2f, b.first().y, 1e-2f), "starts where it should")
        assertTrue(close(a.last().x * 2f, b.last().x, 0.5f) && close(a.last().y * 2f, b.last().y, 1e-2f), "ends where it should")
        assertTrue(close(a.first().radius * 2f, b.first().radius, 1e-3f), "twice as thick: ${a.first().radius} x 2 vs ${b.first().radius}")
        assertTrue(close(a.last().radius * 2f, b.last().radius, 6e-2f), "and at the far end too")
    }

    @Test
    fun aQuarterTurnTurnsTheLeanAndTheBarrel() {
        val r = record(lean = 0.3f, barrel = 0.5f)
        val out = assertNotNull(LineTransform.apply(r, Homography.rotate(PI / 2, Pt(50.0, 50.0)), LineTransform.Weight.SCALE))
        for (s in out.samples) {
            assertTrue(angleClose(s.azimuth, 0.3 + PI / 2), "lean ${s.azimuth}")
            assertTrue(angleClose(s.barrel, 0.5 + PI / 2), "barrel ${s.barrel}")
            assertEquals(0.6f, s.tilt, "how far it leans is not a direction")
        }
        assertTrue(close(r.widthScale, out.widthScale) && close(r.screenPerDoc, out.screenPerDoc), "a turn is not a scale")
    }

    @Test
    fun aFlipMirrorsTheLean() {
        val r = record(lean = 0.3f, barrel = 0.5f)
        val flipH = Homography.scale(-1.0, 1.0, Pt(50.0, 0.0))
        val out = assertNotNull(LineTransform.apply(r, flipH, LineTransform.Weight.SCALE))
        for (s in out.samples) {
            assertTrue(angleClose(s.azimuth, PI - 0.3), "leaning right becomes leaning left: ${s.azimuth}")
            assertTrue(angleClose(s.barrel, PI - 0.5), "the barrel mirrors too: ${s.barrel}")
        }
        val flipV = Homography.scale(1.0, -1.0, Pt(0.0, 50.0))
        for (s in assertNotNull(LineTransform.apply(r, flipV, LineTransform.Weight.SCALE)).samples) {
            assertTrue(angleClose(s.azimuth, -0.3), "${s.azimuth}")
        }
    }

    @Test
    fun noLeanStaysNoLeanAndANowhereCornerIsRefused() {
        val r = record(lean = Float.NaN)
        val out = assertNotNull(LineTransform.apply(r, Homography.rotate(1.0, Pt(0.0, 0.0)), LineTransform.Weight.SCALE))
        assertTrue(out.samples.all { it.azimuth.isNaN() && it.barrel.isNaN() }, "a pen with no lean sensor stays unknown")
        // w = x - 50 is zero at x = 50, which the line crosses (x 10..126): that sample lands nowhere.
        val vanishing = Homography(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0, 0.0, -50.0))
        assertNull(LineTransform.apply(r.copy(samples = r.samples.map { it.copy(x = 50f) }), vanishing, LineTransform.Weight.SCALE))
    }
}

package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.MediaPresets
import cc.joycreator.joybrush.core.input.PenSample
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HoverFootprintTest {
    private fun brush(id: String): BrushPreset = MediaPresets.ALL.first { it.id == id }
    private fun pen(pressure: Float, tilt: Float, azimuth: Float) = PenSample(0f, 0f, 0.0, pressure = pressure, tilt = tilt, azimuth = azimuth)

    /** The corner farthest from the pen point: where the mark reaches. */
    private fun reach(o: List<DoubleArray>) = o.maxByOrNull { hypot(it[0], it[1]) }!!
    private fun length(o: List<DoubleArray>) = hypot(reach(o)[0], reach(o)[1])
    /** The widest the outline is across the line from the pen point to its far end. */
    private fun width(o: List<DoubleArray>): Double {
        val r = reach(o); val l = hypot(r[0], r[1]); val nx = -r[1] / l; val ny = r[0] / l
        val across = o.map { it[0] * nx + it[1] * ny }
        return across.max() - across.min()
    }

    @Test fun aKnifeHeldLikeARealOnePointsWhereThePenLeansAndGrowsWithTheLean() {
        val knife = brush("media:paste:Palette knife 3")
        val pb = PasteBrushes.ALL.getValue("Palette knife 3")
        val upright = HoverFootprint.outline(knife, pen(1f, 0.02f, 0.3f))
        val leaned = HoverFootprint.outline(knife, pen(1f, 1.1f, 0.3f))
        assertEquals(4, leaned.size, "a blade is a slab")
        assertTrue(length(leaned) > 2.5 * length(upright), "more lean, more blade: ${length(upright)} then ${length(leaned)}")
        assertTrue(length(leaned) <= (pb.bladeLenMm + 1.0) * DOC_PX_PER_MM, "never longer than the blade")
        // The engine's own lean for this pen (MediaInput turns the pen's azimuth by half a turn): the blade lies along it.
        val az = MediaInput.sample(pen(1f, 1.1f, 0.3f)).az
        val far = reach(leaned)
        val off = abs(atan2(sin(atan2(far[1], far[0]) - az), cos(atan2(far[1], far[0]) - az)))
        assertTrue(off < 0.08, "the blade lies along the pen's lean, $off rad off")
        // Pressure is how hard it scrapes, not how big it is (the owner's ruling, 2026-11-11).
        assertEquals(length(leaned), length(HoverFootprint.outline(knife, pen(0.2f, 1.1f, 0.3f))), 0.5)
    }

    @Test fun turningThePenTurnsTheOutlineWithIt() {
        for (id in listOf("media:paste:Oil flat", "media:paste:Oil round", "media:paste:Palette knife 2", "media:paste:Palette knife 3")) {
            val a = HoverFootprint.outline(brush(id), pen(0.8f, 0.7f, 0.2f))
            val b = HoverFootprint.outline(brush(id), pen(0.8f, 0.7f, 0.2f + (PI / 2).toFloat()))
            assertTrue(a.isNotEmpty() && a.size == b.size, id)
            // A quarter turn: (x, y) goes to (-y, x), corner for corner.
            for (i in a.indices) {
                assertEquals(-a[i][1], b[i][0], 1.5, "$id corner $i x")
                assertEquals(a[i][0], b[i][1], 1.5, "$id corner $i y")
            }
        }
    }

    @Test fun aRoundHairBrushIsWiderPressedThanBarelyTouching() {
        val round = brush("media:paste:Oil round")
        val light = HoverFootprint.outline(round, pen(0.1f, 0.5f, 0f))
        val firm = HoverFootprint.outline(round, pen(1f, 0.5f, 0f))
        assertTrue(light.size > 8 && firm.size > 8, "a round is an oval")
        assertTrue(width(firm) > 1.2 * width(light), "pressed wider: ${width(light)} then ${width(firm)}")
        // No wider than the brush says it is, at its own size.
        val pb = PasteBrushes.ALL.getValue("Oil round")
        assertTrue(width(firm) <= pb.widthMm * 1.3 * DOC_PX_PER_MM, "${width(firm)} px for a ${pb.widthMm} mm brush")
    }

    @Test fun theOutlineStartsAtThePenPoint() {
        for (id in listOf("media:paste:Oil flat", "media:paste:Palette knife 2", "media:paste:Palette knife 3")) {
            val o = HoverFootprint.outline(brush(id), pen(1f, 0.9f, 1.0f))
            val nearest = o.minOf { hypot(it[0], it[1]) }
            assertTrue(nearest < 0.6 * length(o), "$id: one end of the mark is at the pen ($nearest of ${length(o)})")
        }
    }

    @Test fun brushesWithNoEngineFootprintYetGiveNothing() {
        assertTrue(HoverFootprint.outline(brush("media:dry:6B"), pen(1f, 0.5f, 0f)).isEmpty())
        assertTrue(HoverFootprint.outline(brush("media:wet:Round"), pen(1f, 0.5f, 0f)).isEmpty())
        assertTrue(HoverFootprint.outline(brush("media:paste:Oil round").copy(media = null), pen(1f, 0.5f, 0f)).isEmpty(), "not a realistic-paint brush")
    }
}

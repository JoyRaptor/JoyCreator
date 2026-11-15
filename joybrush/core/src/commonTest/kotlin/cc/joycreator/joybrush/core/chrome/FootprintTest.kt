package cc.joycreator.joybrush.core.chrome

import cc.joycreator.joybrush.core.brush.BrushInput
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.InputCurve
import cc.joycreator.joybrush.core.brush.MediaPresets
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.QuadShapeSpec
import cc.joycreator.joybrush.core.brush.TipQuadSpec
import cc.joycreator.joybrush.core.brush.TipSpec
import cc.joycreator.joybrush.core.brush.UnibrushModes
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** BRUSH-UI section K.4: the footprint is the brush's own mark seen from above, for every kind of brush. */
class FootprintTest {
    private fun stamp(tip: TipSpec = TipSpec()) = BrushPreset(id = "t", name = "T", size = Param(20f), tip = tip)
    private fun radii(o: List<DoubleArray>): List<Double> {
        val cx = o.sumOf { it[0] } / o.size; val cy = o.sumOf { it[1] } / o.size
        return o.map { hypot(it[0] - cx, it[1] - cy) }
    }
    private val upright get() = Footprint.held(stamp(), 0f)
    private val laidOver get() = Footprint.held(stamp(), (70.0 * PI / 180).toFloat())

    @Test fun aRoundTipIsARound() {
        val r = radii(Footprint.outline(stamp(), upright))
        assertTrue(r.max() - r.min() < r.max() * 0.08, "round within 8%: ${r.min()}..${r.max()}")
    }

    @Test fun aSquareTipHasCorners() {
        val square = stamp(TipSpec(quad = TipQuadSpec(QuadShapeSpec(roundness = 0f))))
        val r = radii(Footprint.outline(square, upright))
        assertTrue(r.max() / r.min() > 1.3, "corners reach further than the sides: ${r.min()}..${r.max()}")
    }

    @Test fun aTipThatFlattensWithTiltChangesItsFootprint() {
        val flattens = stamp(TipSpec(aspectDynamics = Param(0f, listOf(InputCurve(BrushInput.tilt, listOf(listOf(0f, 0f), listOf(1f, .8f)))), "add")))
        assertTrue(Footprint.followsTilt(flattens))
        assertFalse(Footprint.followsTilt(stamp()))
        val up = radii(Footprint.outline(flattens, upright)); val over = radii(Footprint.outline(flattens, laidOver))
        assertTrue(over.min() < up.min() * 0.7, "laid over it is thinner: ${up.min()} -> ${over.min()}")
    }

    @Test fun everyShippedBrushHasAFootprint() {
        for (p in UnibrushModes.presets() + MediaPresets.ALL) {
            val o = Footprint.outline(p, Footprint.held(p, .5f))
            assertTrue(o.size >= 3, "${p.id}: ${o.size} points")
            assertTrue(o.all { it[0].isFinite() && it[1].isFinite() }, p.id)
        }
    }

    @Test fun anOilKnifeIsItsOwnShapeAndAPencilARound() {
        val knife = MediaPresets.ALL.first { it.id == "media:paste:Palette knife 3" }
        assertEquals(cc.joycreator.joybrush.core.media.HoverFootprint.outline(knife, laidOver).size, Footprint.outline(knife, laidOver).size)
        val pencil = MediaPresets.ALL.first { it.id == "media:dry:6B" }
        val r = radii(Footprint.outline(pencil, laidOver))
        assertTrue(r.max() - r.min() < r.max() * 0.02)
    }
}

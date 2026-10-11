package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.BLEND_MULTIPLY
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.LineStyleSpec
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.TipSpec
import cc.joycreator.joybrush.core.doc.BlendMode
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.Accumulate
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.RefCanvas
import cc.joycreator.joybrush.core.paint.StrokeBlend
import cc.joycreator.joybrush.core.paint.TipShape
import cc.joycreator.joybrush.core.render.Blend
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Brush version 9 reaching the two places that draw: the CPU stroke commit ([RefCanvas], the reference `jb_commit.frag`
 * is checked against) and the line replay ([InkReplay]).
 */
class LineBrushReplayTest {

    /** One stroke of [rgb] at opacity [a] over the whole centre of a 16 px tile. */
    private fun RefCanvas.stroke(rgb: FloatArray, a: Float, blend: StrokeBlend) {
        beginStroke("L", rgb[0], rgb[1], rgb[2], a, Accumulate.WASH, blend, TipShape(hardness = 1f))
        addDabs(listOf(Dab(8f, 8f, radius = 7f, cap = capForStroke())))
        endStroke()
    }

    @Test
    fun theGlazeCommitsAreTheCompositorsOwnModes() {
        // Under paint at 70% (a blue-green), then a glaze stroke at 60%. The centre pixel is fully covered, so the
        // stroke's alpha there is exactly 0.6 and the expected pixel is render/Blend's answer for that source over that
        // backdrop. A commit that used normal "over", or swapped source and backdrop, misses by tenths, not by rounding.
        for ((blend, mode) in listOf(StrokeBlend.MULTIPLY to BlendMode.MULTIPLY, StrokeBlend.SCREEN to BlendMode.SCREEN)) {
            val c = RefCanvas(tileSize = 16)
            c.stroke(floatArrayOf(0.1f, 0.6f, 0.8f), 0.7f, StrokeBlend.NORMAL)
            val under = c.pixel("L", 8, 8)
            val glaze = floatArrayOf(0.9f, 0.5f, 0.2f)
            c.stroke(glaze, 0.6f, blend)
            val expected = FloatArray(4)
            Blend.apply(mode, floatArrayOf(glaze[0] * 0.6f, glaze[1] * 0.6f, glaze[2] * 0.6f, 0.6f), under, expected)
            val got = c.pixel("L", 8, 8)
            for (ch in 0..3) assertTrue(abs(expected[ch] - got[ch]) < 1e-5f, "$blend channel $ch: ${expected[ch]} vs ${got[ch]}")

            val normal = RefCanvas(tileSize = 16).apply {
                stroke(floatArrayOf(0.1f, 0.6f, 0.8f), 0.7f, StrokeBlend.NORMAL); stroke(glaze, 0.6f, StrokeBlend.NORMAL)
            }.pixel("L", 8, 8)
            assertTrue((0..2).any { abs(normal[it] - got[it]) > 0.05f }, "$blend is not just normal paint")
        }
    }

    private val pen = BrushPreset(id = "pen", name = "Pen", size = Param(10f), tip = TipSpec(hardness = Param(1f)), spacing = 0.05f)

    /** A level stroke from x = 0 to x = 200, constant pressure, no smoothing. */
    private val straight = StrokeRecord("s", "pen", 7L, 0f, 1f, (0..40).map { PenSample(it * 5f, 50f, it * 10.0) })

    @Test
    fun replayAppliesTheBrushesTaperBeforeTheScatter() {
        val style = LineStyleSpec(taperIn = 3f, taperOut = 5f, taperTip = 0.2f)
        val plain = InkReplay.dabs(straight, pen)
        val tapered = InkReplay.dabs(straight, pen.copy(blend = BLEND_MULTIPLY, line = style))
        // Exactly the line maths on the brush-less replay: same dabs, same positions, radii shaped by LineShaper.
        assertEquals(LineShaper.taper(plain, LineStyle.of(style), 10f), tapered)
        assertTrue(abs(tapered.first().radius - 5f * 0.2f) < 1e-4f, "starts at the tip width")
        assertTrue(abs(tapered[tapered.size / 2].radius - 5f) < 1e-4f, "full width in the middle")
    }

    @Test
    fun replayDashesAlongTheStroke() {
        // Diameter 10, dashes 4 on 2 off: period 60 px over a 200 px stroke, so the dab list has gaps of more than one
        // spacing step at each of the three period boundaries a solid line does not have.
        val dashed = InkReplay.dabs(straight, pen.copy(line = LineStyleSpec(dashOn = 4f, dashOff = 2f)))
        val gaps = dashed.zipWithNext().count { (a, b) -> b.x - a.x > 5f }
        assertEquals(3, gaps, "three gaps between four dashes")
        assertTrue(dashed.size < InkReplay.dabs(straight, pen).size)
    }

    @Test
    fun reweightingALineKeepsItsProportions() {
        // Taper-in of 3 diameters. At widthScale 1 the 10 px pen is full width 30 px along; re-weighted to 2 it is a 20 px
        // line and must be full width 60 px along, the same shape twice the size. Measured in the brush's own size (the
        // bug the review found), both would reach full width at 30 px and the heavy line would look stubby.
        val style = LineStyleSpec(taperIn = 3f)
        val brush = pen.copy(line = style)
        fun fullAt(scale: Float): Float {
            val dabs = InkReplay.dabs(straight.copy(widthScale = scale), brush)
            val full = dabs.maxOf { it.radius }
            val s = LineShaper.arcLengths(dabs)
            return s[dabs.indexOfFirst { it.radius >= full - 1e-4f }]
        }
        val light = fullAt(1f)
        val heavy = fullAt(2f)
        assertTrue(abs(light - 30f) <= 0.5f, "light line full width at $light")
        assertTrue(abs(heavy - 60f) <= 0.5f, "heavy line full width at $heavy")
    }
}

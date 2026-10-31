package cc.joycreator.joybrush.core.media

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue

/** The owner's cul-de-sac: past a sharp turn a dragged brush folds under the pen, and its body never swings out past the corner. */
class PasteTurnTest {
    private val ppm = DOC_PX_PER_MM

    /** Up [up] mm, then right [right] mm, or straight back with [back]; every step made. */
    private fun corner(brush: PasteBrush, up: Double = 30.0, right: Double = 30.0, back: Boolean = false): List<PasteStep> {
        val s = PasteStroke(brush, ppm)
        val out = ArrayList<PasteStep>()
        var t = 0.0
        var y = 0.0
        while (y <= up) { s.add(MediaSample(1000.0, 1000 - y * ppm, 0.6, 0.3, 0.0, t.also { t += 8.0 })); out += s.take(); y += 0.5 }
        var x = 0.5
        while (x <= right) {
            s.add(if (back) MediaSample(1000.0, 1000 - (up - x) * ppm, 0.6, 0.3, 0.0, t.also { t += 8.0 })
            else MediaSample(1000 + x * ppm, 1000 - up * ppm, 0.6, 0.3, 0.0, t.also { t += 8.0 }))
            out += s.take(); x += 0.5
        }
        return out
    }

    /** The farthest any step's tail reaches past the old line, on the side away from the turn (mm): the scoop. */
    private fun scoop(steps: List<PasteStep>): Double =
        steps.maxOf { max(0.0, 1000 - (it.x + it.lDirX * it.len * ppm)) } / ppm

    @Test fun theBodyNeverSwingsOutPastTheCorner() {
        // Not the fan: wider than it is long, it has no body to swing, and it is no worse for being dragged.
        val fan = PasteBrushes.ALL.getValue("Fan blender")
        assertTrue(scoop(corner(fan)) < scoop(corner(fan.copy(turns = TurnMode.SWING))) + 0.5)
        for (name in listOf("Oil round", "Oil flat", "All-round", "Palette knife 1")) {
            val b = PasteBrushes.ALL.getValue(name)
            val before = scoop(corner(b.copy(turns = TurnMode.SWING)))
            val now = scoop(corner(b))
            assertTrue(before > 1.0, "$name: the old way swings out ($before mm)")
            assertTrue(now < 0.2 * before, "$name: dragged, the tail stays by the path ($now mm, $before before)")
        }
    }

    @Test fun doublingBackTheBrushShrinksThroughItsMiddleInsteadOfSweeping() {
        val b = PasteBrushes.ALL.getValue("Palette knife 1")
        val steps = corner(b, right = 25.0, back = true)
        val shortest = steps.drop(steps.size / 3).minOf { it.len }
        val full = steps[steps.size / 3].len
        assertTrue(shortest < 0.5 * full, "the ends meet: $shortest against $full mm")
        assertTrue(steps.all { abs(it.x + it.lDirX * it.len * ppm - 1000) < 0.5 * ppm }, "and it never leaves the line")
    }

    @Test fun aStraightStrokeLiesAsItDidAndAWobbleDoesNotShakeIt() {
        val b = PasteBrushes.ALL.getValue("Oil round")
        val s = PasteStroke(b, ppm)
        var t = 0.0
        val out = ArrayList<PasteStep>()
        for (i in 0..200) {
            s.add(MediaSample(1000 + i * 0.5 * ppm, 1000 + (if (i % 2 == 0) 0.4 else -0.4), 0.6, 0.3, 0.0, t.also { t += 8.0 }))
            out += s.take()
        }
        val settled = out.drop(out.size / 2)
        assertTrue(settled.all { it.lDirX < -0.999 }, "along the line: ${settled.minOf { -it.lDirX }}")
        val swing = ArrayList<PasteStep>()
        val o = PasteStroke(b.copy(turns = TurnMode.SWING), ppm)
        t = 0.0
        for (i in 0..200) { o.add(MediaSample(1000 + i * 0.5 * ppm, 1000.0, 0.6, 0.3, 0.0, t.also { t += 8.0 })); swing += o.take() }
        assertTrue(abs(settled.last().len - swing.last().len) < 1e-6, "as long as it was, wobble and all")
    }
}

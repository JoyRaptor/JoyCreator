package cc.joycreator.joybrush.core.media

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/** Uneven pressure across a hair brush (PasteBrush.force, owner 10-27): which side leans, which side is outside a curve. */
class PasteForceTest {
    private val ppm = DOC_PX_PER_MM

    private fun brush(force: Double) = PasteBrushes.ALL.getValue("Oil round").copy(force = force)

    /** A straight stroke along +x, the pen tilted [tilt] with its handle toward [az]. Its w side is -y (doc y grows down). */
    private fun straight(force: Double, az: Double, tilt: Double = 0.8): List<PasteStep> {
        val s = PasteStroke(brush(force), ppm)
        var t = 0.0
        for (i in 0..60) s.add(MediaSample(400 + i * 0.5 * ppm, 400.0, 0.6, tilt, az, t.also { t += 16.0 }))
        return s.take()
    }

    @Test fun offItIsTheStrokeAsBefore() {
        val steps = straight(0.0, -PI / 2)
        assertTrue(steps.isNotEmpty())
        assertTrue(steps.all { it.lean == 0.0 && it.curve == 0.0 })
    }

    @Test fun thePressedSideIsTheSideTheHandleLeansToward() {
        val toward = straight(1.0, -PI / 2).drop(5)
        val away = straight(1.0, PI / 2).drop(5)
        assertTrue(toward.all { it.wDirY < 0 }, "w is -y for a stroke along +x")
        assertTrue(toward.all { it.lean > 0.5 }, "handle toward w: that side presses")
        assertTrue(away.all { it.lean < -0.5 })
        val upright = straight(1.0, -PI / 2, tilt = 0.0).drop(5)
        assertTrue(upright.all { it.lean == 0.0 }, "an upright pen presses evenly")
    }

    @Test fun theOutsideOfACurveIsWherePaintPilesUp() {
        // Along +x, then bending toward w (-y) round a 15 mm circle: the outside is -w.
        val s = PasteStroke(brush(1.0), ppm)
        val r = 15 * ppm
        var t = 0.0
        for (i in 0..120) {
            val th = i / 120.0 * PI
            s.add(MediaSample(400 + r * sin(th), 400 - r * (1 - cos(th)), 0.6, 0.0, 0.0, t.also { t += 16.0 }))
        }
        val steps = s.take().drop(30)
        assertTrue(steps.isNotEmpty())
        assertTrue(steps.all { it.curve < -0.05 }, "curving toward w: the outside (and the pile) is -w")
        assertTrue(steps.all { it.lean == 0.0 }, "upright: no lean")
    }
}

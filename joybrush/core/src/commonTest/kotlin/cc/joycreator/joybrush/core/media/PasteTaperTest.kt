package cc.joycreator.joybrush.core.media

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An oil hair brush's Taper (PasteBrush.taper, the owner 2026-11-14): off is today's stroke, on narrows the ends only. */
class PasteTaperTest {
    private val ppm = DOC_PX_PER_MM
    private val round = PasteBrushes.ALL.getValue("Oil round")

    /** A straight stroke along +x, [mm] long, at [pressure] (a function of the share travelled), steady tilt. */
    private fun straight(b: PasteBrush, mm: Double, pressure: (Double) -> Double): List<PasteStep> {
        val s = PasteStroke(b, ppm)
        val n = (mm * 4).toInt()
        var t = 0.0
        for (i in 0..n) s.add(MediaSample(400 + i * 0.25 * ppm, 400.0, pressure(i / n.toDouble()), 0.5, 1.0, t.also { t += 8.0 }))
        return s.take()
    }

    @Test fun offItIsTheStrokeAsBefore() {
        val a = straight(round, 30.0) { 0.6 }
        val b = straight(round.copy(taper = 0.0), 30.0) { 0.6 }
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertEquals(a[i].halfW, b[i].halfW)
            assertEquals(a[i].thick, b[i].thick)
            assertEquals(a[i].len, b[i].len)
        }
    }

    @Test fun fullTaperStartsAtAbout15PercentAndIsFullWidthAfterTwoAndAHalfWidths() {
        val plain = straight(round, 30.0) { 0.6 }
        val tapered = straight(round.copy(taper = 1.0), 30.0) { 0.6 }
        assertEquals(plain.size, tapered.size)
        val first = tapered.first().halfW / plain.first().halfW
        assertTrue(abs(first - 0.15) < 0.05, "first step ${first * 100} % wide")
        val reach = 2.5 * round.widthMm * ppm
        val later = tapered.indices.filter { tapered[it].x - tapered.first().x > reach + ppm }
        assertTrue(later.isNotEmpty())
        assertTrue(later.all { abs(tapered[it].halfW - plain[it].halfW) < 1e-9 }, "full width once it has travelled")
        assertTrue(tapered.zip(plain).all { (t, p) -> t.halfW <= p.halfW + 1e-12 }, "never wider")
    }

    @Test fun aStrokeThatStopsPressedKeepsItsWidthAndOneThatLiftsTapers() {
        val pressed = straight(round.copy(taper = 1.0), 30.0) { 0.7 }
        val pressedPlain = straight(round, 30.0) { 0.7 }
        assertTrue(abs(pressed.last().halfW - pressedPlain.last().halfW) < 1e-9, "stops pressed: full width")
        val lift: (Double) -> Double = { u -> if (u < 0.85) 0.7 else 0.7 * (1 - (u - 0.85) / 0.15 * 0.95) }
        val lifted = straight(round.copy(taper = 1.0), 30.0, lift)
        val liftedPlain = straight(round, 30.0, lift)
        assertTrue(lifted.last().halfW < 0.6 * liftedPlain.last().halfW, "lifting off narrows the end")
    }

    @Test fun bladesAndTheTrowelAreUntouched() {
        for (name in listOf("Palette knife 1", "Palette knife 2", "Scraper")) {
            val b = PasteBrushes.ALL.getValue(name)
            val a = straight(b, 20.0) { 0.6 }
            val t = straight(b.copy(taper = 1.0), 20.0) { 0.6 }
            assertEquals(a.size, t.size, name)
            assertTrue(a.zip(t).all { (x, y) -> x.halfW == y.halfW && x.thick == y.thick }, name)
        }
    }
}

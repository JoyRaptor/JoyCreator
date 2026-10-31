package cc.joycreator.joybrush.core.media

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A paste stroke behind the pen takes fewer, longer steps (PasteSpacing), trading the same paint per millimetre. */
class PasteSpacingTest {
    private fun stroke(name: String, spacing: Double): List<PasteStep> {
        val ppm = DOC_PX_PER_MM
        val s = PasteStroke(PasteBrushes.ALL.getValue(name), ppm)
        s.spacing = spacing
        var t = 0.0
        val out = ArrayList<PasteStep>()
        // A frame after every sample, so the backlog rule (PasteSpacing.behind) stays out of it.
        for (i in 0..60) { s.add(MediaSample(400 + i * 0.5 * ppm, 400.0, 0.6, 0.3, 0.0, t.also { t += 16.0 })); out += s.take() }
        return out
    }

    @Test fun aPhoneThatKeepsUpNeverLeavesTheShippedHops() {
        var s = 1.0
        repeat(50) { s = PasteSpacing.next(s, 16) }
        assertEquals(1.0, s)
        repeat(50) { s = PasteSpacing.next(s, 200) }
        assertEquals(PasteSpacing.MAX, s, "a slow phone widens to the cap, no further")
        repeat(50) { s = PasteSpacing.next(s, 16) }
        assertEquals(1.0, s, "and comes back once it keeps up")
        assertEquals(1.3, PasteSpacing.next(1.3, 35), "in between, it holds")
    }

    @Test fun widerHopsSlideAsFarInFewerSteps() {
        for (name in listOf("Oil round", "Oil flat", "Palette knife 1")) {
            val fine = stroke(name, 1.0)
            val wide = stroke(name, 2.0)
            val slideFine = fine.sumOf { it.slideMm }
            val slideWide = wide.sumOf { it.slideMm }
            assertTrue(wide.size < fine.size * 0.8, "$name: ${wide.size} steps against ${fine.size}")
            assertTrue(abs(slideWide - slideFine) < 0.05 * slideFine + 1.0, "$name: the same travel ($slideWide against $slideFine mm)")
        }
    }

    @Test fun inATurnTheHopsStayFine() {
        val ppm = DOC_PX_PER_MM
        val s = PasteStroke(PasteBrushes.ALL.getValue("Oil round"), ppm)
        s.spacing = 2.0
        var t = 0.0
        // Right 10 mm, then straight back: the brush still lies along the way it came for the first millimetres back.
        for (i in 0..20) s.add(MediaSample(400 + i * 0.5 * ppm, 400.0, 0.6, 0.3, 0.0, t.also { t += 16.0 }))
        val out = s.take().size
        val back = ArrayList<PasteStep>()
        for (i in 1..6) { s.add(MediaSample(400 + (10 - i * 0.5) * ppm, 400.0, 0.6, 0.3, 0.0, t.also { t += 16.0 })); back += s.take() }
        val gaps = back.zipWithNext { a, b -> kotlin.math.abs(b.x - a.x) / ppm }
        assertTrue(gaps.isNotEmpty() && gaps.all { it < 0.31 }, "fine hops in the turn: $gaps")
        assertTrue(out > 0)
    }

    @Test fun aBurstThinsOutButKeepsItsPaintPerMillimetre() {
        assertEquals(1.0, PasteSpacing.behind(PasteSpacing.FRAME_STEPS, 6.0))
        assertEquals(4.0, PasteSpacing.behind(PasteSpacing.FRAME_STEPS * 2, 6.0))
        assertEquals(6.0, PasteSpacing.behind(PasteSpacing.FRAME_STEPS * 4, 6.0))
        assertEquals(1.0, PasteSpacing.behind(100, 0.2))
    }
}

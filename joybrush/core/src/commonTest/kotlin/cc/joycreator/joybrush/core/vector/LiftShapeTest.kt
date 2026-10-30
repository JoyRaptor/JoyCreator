package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.*
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.test.*

/** Unibrush U6: the lift's redraw is the shaped replay, and a stroke past the allowances is kept plain (null), never dropped. */
class LiftShapeTest {
    private val raw = (0..40).map { PenSample(it * 3.1f, (it % 7) * 1.3f, it * 8.0, (it % 9) / 8f, .3f, .6f) }
    private fun brush(line: LineStyleSpec?) = BrushPreset(id = "t", name = "Taper", size = Param(12f), line = line)
    private fun record(p: BrushPreset) = StrokeRecord("s", p.id, 7, .3f, 1f, raw, colorArgb = 0xff202020.toInt())

    @Test fun onlyAStampThatTapersOrDashesIsShapedAtTheLift() {
        assertTrue(LiftShape.wanted(brush(LineStyleSpec(taperIn = 1f, taperOut = 2f))))
        assertTrue(LiftShape.wanted(brush(LineStyleSpec(dashOn = 2f, dashOff = 1f))))
        assertFalse(LiftShape.wanted(brush(null)), "no line section: nothing to redraw")
        assertFalse(LiftShape.wanted(brush(LineStyleSpec(taperOut = 2f, screenWidthPx = 1.5f))), "a wire belongs to its renderer")
        assertFalse(LiftShape.wanted(brush(LineStyleSpec(taperOut = 2f)).copy(engine = "smudge")))
    }

    @Test fun theLiftRedrawIsExactlyTheWholeStrokeReplay() {
        val p = brush(LineStyleSpec(taperIn = 1.5f, taperOut = 3f, taperTip = .05f))
        val lifted = assertNotNull(LiftShape.shaped(record(p), p, null, null))
        val replay = InkReplay.replay(record(p), p)
        assertEquals(replay.dabs, lifted.dabs)
        assertTrue(lifted.dabs.first().radius < lifted.dabs[lifted.dabs.size / 2].radius, "the start tapers in")
        assertTrue(lifted.dabs.last().radius < lifted.dabs[lifted.dabs.size / 2].radius, "the end tapers out")
    }

    @Test fun aStrokePastTheAllowancesIsKeptPlainNotDropped() {
        val p = brush(LineStyleSpec(taperOut = 3f))
        assertNull(LiftShape.shaped(record(p), p, null, null, ShapedReplayLimits(10, 10_000, 10_000, 10_000)))
    }
}

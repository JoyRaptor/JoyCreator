package cc.joycreator.joybrush.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A press that never moves still reaches the stroke builders ([MediaSpline.finish]; second review E6). */
class MediaSplineTapTest {
    private fun at(x: Double, y: Double, t: Double, p: Double = 0.6) = MediaSample(x, y, p, 0.4, 0.2, t)

    @Test fun aStillPressIsHeardTwiceTheTouchAndHowItEnded() {
        val heard = ArrayList<MediaSample>()
        val spline = MediaSpline(sink = heard::add)
        spline.add(at(100.0, 100.0, 0.0))
        for (k in 1..30) spline.add(at(100.0, 100.0, k * 8.0, p = 0.9))
        assertEquals(1, heard.size, "while it is held the curve waits, as before")
        spline.finish()
        assertEquals(2, heard.size)
        assertEquals(240.0, heard[1].t); assertEquals(0.9, heard[1].p); assertEquals(100.0, heard[1].x)
    }

    @Test fun aSingleReportAloneIsNotRepeated() {
        val heard = ArrayList<MediaSample>()
        val spline = MediaSpline(sink = heard::add)
        spline.add(at(5.0, 5.0, 0.0)); spline.finish()
        assertEquals(1, heard.size, "no second report came: nothing to add")
    }

    @Test fun aMovingStrokeIsUnchangedAndTheNextStrokeStartsClean() {
        val heard = ArrayList<MediaSample>()
        val spline = MediaSpline(sink = heard::add)
        spline.add(at(0.0, 0.0, 0.0)); spline.add(at(0.0, 0.0, 8.0)); spline.add(at(6.0, 0.0, 16.0)); spline.finish()
        val moving = heard.size
        assertTrue(moving > 2 && heard.last().x == 6.0, "the stretch to the last point is drawn, and nothing after it")
        heard.clear()
        spline.add(at(50.0, 50.0, 0.0)); spline.finish()
        assertEquals(1, heard.size, "a still press in the last stroke does not leak into this one")
    }

    @Test fun anOilRoundTapLaysSomething() {
        val brush = PasteBrushes.ALL.getValue("Oil round")
        val stroke = PasteStroke(brush, DOC_PX_PER_MM, 1.0)
        val spline = MediaSpline(sink = stroke::add)
        spline.add(at(100.0, 100.0, 0.0))
        for (k in 1..30) spline.add(at(100.0, 100.0, k * 8.0))
        spline.finish()
        assertTrue(stroke.take().isNotEmpty(), "a tap with the default oil brush lays a step")
    }
}

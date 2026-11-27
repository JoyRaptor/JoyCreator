package cc.joycreator.joybrush.android.chrome

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** BRUSH-UI §L (owner's mockup, 11-27): the pen row every brush editor shares, and its curve boxes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PenHearingRowTest {
    private val kit = ChromeKit(RuntimeEnvironment.getApplication())
    private fun curve(name: String) = CurveEditorView(kit, name, listOf(.25f, .25f, .75f, .75f)) { _, _ -> }

    @Test fun aCurveFillsWhileThePenPressesAndKeepsAGreyMarkerWhereTheStrokeEnded() {
        val c = curve("Pressure")
        assertFalse(c.isFilling())
        c.showReading(.3f, true)
        c.showReading(.62f, true)
        assertTrue("pressing fills under the curve", c.isFilling())
        assertTrue("no last-stroke marker while still pressing", c.lastStrokeAt().isNaN())
        c.showReading(Float.NaN, false)
        assertFalse(c.isFilling())
        assertEquals("the marker stays where the stroke ended", .62f, c.lastStrokeAt(), 0f)
        c.showReading(.1f, false)   // hovering: no fill, and the marker of the last stroke stays
        assertFalse(c.isFilling())
        assertEquals(.62f, c.lastStrokeAt(), 0f)
        c.showReading(.4f, true); c.showReading(Float.NaN, false)
        assertEquals("the next stroke moves it", .4f, c.lastStrokeAt(), 0f)
    }

    @Test fun theMonitorGrowsOverTheCurvesAndBack() {
        val pressure = curve("Pressure"); val tilt = curve("Tilt")
        val row = PenHearingRow(kit, PenReadingView(kit), FootprintView(kit), pressure, tilt)
        assertTrue("the footprints stand in a column beside the monitor", row.footprint.vertical)
        fun expand() = (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<android.widget.FrameLayout>().single()
            .let { box -> (0 until box.childCount).map { box.getChildAt(it) }.first { it.contentDescription?.startsWith("Make the pen monitor") == true } }
        assertTrue(expand().contentDescription.toString().contains("large"))
        expand().performClick()
        assertTrue(row.expanded)
        assertEquals(View.GONE, pressure.visibility); assertEquals(View.GONE, tilt.visibility)
        assertTrue(expand().contentDescription.toString().contains("small again"))
        expand().performClick()
        assertFalse(row.expanded)
        assertEquals(View.VISIBLE, pressure.visibility); assertEquals(View.VISIBLE, tilt.visibility)
    }
}

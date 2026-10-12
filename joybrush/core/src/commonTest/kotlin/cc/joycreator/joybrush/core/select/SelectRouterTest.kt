package cc.joycreator.joybrush.core.select

import cc.joycreator.joybrush.core.select.SelectRouter.BoxHit
import cc.joycreator.joybrush.core.select.SelectRouter.Kind
import cc.joycreator.joybrush.core.select.SelectRouter.Selection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** JB-5.20e D3–D8, D12, §K K11–K19: who a contact belongs to and what it means. Density 1, so dp are px. */
class SelectRouterTest {

    private class FakeEnv : SelectRouter.Env {
        override val density = 1f
        override var selectToolInHand = true
        override var selection = Selection.NONE
        override var nudging = false
        override var penSeen = true
        override var fingerSelects = false
        override var crosshair = true
        override var addArmed = false
        var tipAt: Pair<Float, Float>? = null
        override fun onTip(x: Float, y: Float) = tipAt?.let { it.first == x && it.second == y } ?: false
    }

    private class Log : SelectRouter.Sink {
        val events = ArrayList<String>()
        override fun tap(x: Float, y: Float, adding: Boolean) { events += "tap ${x.toInt()},${y.toInt()}${if (adding) " +" else ""}" }
        override fun lassoMoved(points: FloatArray) { if (events.lastOrNull() != "loop…") events += "loop…" }
        override fun lassoEnded(points: FloatArray, adding: Boolean) { events += "loop ${points.size / 2}${if (adding) " +" else ""}" }
        override fun crosshairMoved(x: Float, y: Float) { events += "cross ${x.toInt()},${y.toInt()}" }
        override fun crosshairEnded(x: Float, y: Float, adding: Boolean) { events += "cross-pick ${x.toInt()},${y.toInt()}${if (adding) " +" else ""}" }
        override fun penButtonTap(x: Float, y: Float) { events += "button-tap" }
        override fun endSelection() { events += "end" }
        override fun nudge(phase: SelectRouter.Phase, x: Float, y: Float) { events += "nudge $phase" }
        override fun tip(phase: SelectRouter.Phase, x: Float, y: Float) { events += "tip $phase" }
        override fun cancelled() { events += "cancelled" }
    }

    private var now = 0L
    private val env = FakeEnv()
    private val log = Log()
    private val r = SelectRouter(env, log) { now }

    private fun drag(id: Int, from: Pair<Float, Float>, to: Pair<Float, Float>, steps: Int = 10) {
        for (i in 1..steps) r.move(id, from.first + (to.first - from.first) * i / steps, from.second + (to.second - from.second) * i / steps)
    }

    @Test
    fun aTapPicksAndADragDrawsALoop() {
        assertTrue(r.down(1, 100f, 100f, Kind.PEN, false))
        r.move(1, 103f, 102f) // inside the 8 dp slop
        r.up(1, 103f, 102f)
        assertEquals(listOf("tap 100,100"), log.events, "a tap is reported where it went down")
        log.events.clear()
        r.down(2, 100f, 100f, Kind.PEN, false)
        drag(2, 100f to 100f, 160f to 140f)
        drag(2, 160f to 140f, 100f to 160f)
        r.up(2, 100f, 160f)
        assertEquals("loop…", log.events.first())
        assertTrue(log.events.last().startsWith("loop "), "${log.events}")
    }

    @Test
    fun aLoopTooShortIsATap() {
        r.down(1, 100f, 100f, Kind.PEN, false)
        drag(1, 100f to 100f, 112f to 100f, steps = 3) // 12 px: past the slop, short of the 24 px loop
        r.up(1, 112f, 100f)
        assertEquals("tap 100,100", log.events.last())
    }

    @Test
    fun aStillFingerLiftsTheCrosshairAndItAddsWithPlus() {
        r.down(1, 200f, 300f, Kind.FINGER, false)
        now = 299; r.tick()
        assertTrue(log.events.isEmpty(), "not before 300 ms")
        now = 300; r.tick()
        assertEquals("cross 200,252", log.events.last(), "48 dp above the fingertip")
        r.move(1, 210f, 310f)
        env.addArmed = true
        r.up(1, 210f, 310f)
        assertEquals("cross-pick 210,262 +", log.events.last(), "K12: the crosshair adds when ＋ is on")
    }

    @Test
    fun aRestingThumbMakesThePenAdd() {
        r.down(1, 100f, 100f, Kind.PEN, false)
        assertTrue(r.otherDown(9, Kind.FINGER))
        r.up(1, 100f, 100f)
        assertEquals("tap 100,100 +", log.events.last())
    }

    @Test
    fun theSPenButtonTapsForTheEyedropperAndLoopsFromAnyTool() {
        env.selectToolInHand = false
        assertTrue(r.down(1, 50f, 50f, Kind.PEN, penButton = true))
        r.up(1, 50f, 50f)
        assertEquals("button-tap", log.events.last())
        assertTrue(r.down(2, 50f, 50f, Kind.PEN, penButton = true))
        drag(2, 50f to 50f, 120f to 90f); drag(2, 120f to 90f, 50f to 120f)
        r.up(2, 50f, 120f)
        assertTrue(log.events.last().startsWith("loop "), "R42: held and looped is a lasso, with a brush in hand")
    }

    @Test
    fun aPenWithABrushPaintsAndEndsOnlyALineSelection() {
        env.selectToolInHand = false
        env.selection = Selection.PIXELS
        assertFalse(r.down(1, 10f, 10f, Kind.PEN, false), "the pen keeps its tool")
        assertTrue(log.events.isEmpty(), "a paint selection lives on: the stroke is clipped to it")
        env.selection = Selection.LINES
        assertFalse(r.down(2, 10f, 10f, Kind.PEN, false))
        assertEquals(listOf("end"), log.events, "a stroke ends a line selection, as in Concepts")
    }

    @Test
    fun beforeAnyPenAFingerDraws() {
        env.selectToolInHand = false; env.penSeen = false
        assertFalse(r.down(1, 10f, 10f, Kind.FINGER, false))
    }

    @Test
    fun afterThePenOneFingerPansByDefaultButATapStillEnds() {
        env.selectToolInHand = false
        assertFalse(r.down(1, 10f, 10f, Kind.FINGER, false), "no selection: a finger is pure navigation, never delayed")
        env.selection = Selection.LINES
        assertTrue(r.down(2, 10f, 10f, Kind.FINGER, false))
        r.up(2, 10f, 10f)
        assertEquals("end", log.events.last(), "tap outside commits, even with a panning finger")
        assertTrue(r.down(3, 10f, 10f, Kind.FINGER, false))
        assertFalse(r.move(3, 40f, 10f), "a drag hands the rest to navigation: it pans")
        env.fingerSelects = true
        assertTrue(r.down(4, 10f, 10f, Kind.FINGER, false))
        r.up(4, 10f, 10f)
        assertEquals("tap 10,10", log.events.last(), "K18 'Selects': the finger picks")
    }

    @Test
    fun aSecondFingerBesideAFingerHandsBackToTheCanvas() {
        r.down(1, 10f, 10f, Kind.FINGER, false)
        assertFalse(r.otherDown(2, Kind.FINGER))
        assertEquals("cancelled", log.events.last())
        assertFalse(r.busy)
    }

    @Test
    fun nudgeAndTipDragsReportTheirPhasesAndANudgeTapIsATap() {
        env.selection = Selection.LINES; env.nudging = true
        r.down(1, 10f, 10f, Kind.PEN, false); drag(1, 10f to 10f, 40f to 10f); r.up(1, 40f, 10f)
        assertEquals(listOf("nudge START", "nudge MOVE"), log.events.take(2))
        assertEquals("nudge END", log.events.last())
        log.events.clear()
        r.down(2, 300f, 300f, Kind.PEN, false); r.up(2, 300f, 300f)
        assertEquals(listOf("tap 300,300"), log.events, "the Sink ends the selection when the tap is outside it")
        env.nudging = false; env.tipAt = 50f to 60f; log.events.clear()
        r.down(3, 50f, 60f, Kind.FINGER, false); r.move(3, 55f, 60f); r.up(3, 55f, 60f)
        assertEquals(listOf("tip START", "tip MOVE", "tip END"), log.events)
    }

    @Test
    fun theBoxGate() {
        assertTrue(SelectRouter.boxTakes(BoxHit.HANDLE, Kind.PEN, false, selectToolInHand = false), "handles are the box's")
        assertFalse(SelectRouter.boxTakes(BoxHit.BODY, Kind.PEN, false, selectToolInHand = false), "a pen with a brush paints")
        assertTrue(SelectRouter.boxTakes(BoxHit.BODY, Kind.PEN, true, selectToolInHand = false), "the S Pen button moves it")
        assertTrue(SelectRouter.boxTakes(BoxHit.BODY, Kind.PEN, false, selectToolInHand = true), "the Select tool moves it")
        assertTrue(SelectRouter.boxTakes(BoxHit.BODY, Kind.FINGER, false, selectToolInHand = false), "a finger moves it")
        assertFalse(SelectRouter.boxTakes(BoxHit.TIP, Kind.FINGER, false, true), "tips are the router's")
        assertTrue(SelectRouter.boxTakes(BoxHit.OFF, Kind.FINGER, false, false), "P8 B: a finger anywhere may start a two-finger move")
        assertFalse(SelectRouter.boxTakes(BoxHit.OFF, Kind.PEN, false, true), "a pen off the box is never held back")
    }
}

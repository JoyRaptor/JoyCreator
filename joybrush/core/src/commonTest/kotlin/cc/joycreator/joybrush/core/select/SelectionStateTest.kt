package cc.joycreator.joybrush.core.select

import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.vector.InkLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** JB-5.20e D3, D6, D9 and P6 — what a tap and a loop select. Zoom 1, so screen px are doc px. */
class SelectionStateTest {

    /** Horizontal lines, drawn in list order (later = on top). */
    private fun hline(id: String, x0: Double, x1: Double, y: Double) =
        InkLine(id, DoubleArray(11) { x0 + (x1 - x0) * it / 10 }, DoubleArray(11) { y }, DoubleArray(11) { 1.0 })

    // Three lines 3 px apart (dense hatching), and one far away.
    private val a = hline("a", 0.0, 100.0, 50.0)
    private val b = hline("b", 0.0, 100.0, 53.0)
    private val c = hline("c", 0.0, 100.0, 56.0)
    private val far = hline("far", 0.0, 100.0, 300.0)
    private val lines = listOf(a, b, c, far)
    private val noBox: (Double, Double) -> Boolean = { _, _ -> false }

    private fun square(x0: Double, y0: Double, x1: Double, y1: Double) = listOf(Pt(x0, y0), Pt(x1, y0), Pt(x1, y1), Pt(x0, y1))

    private fun ids(s: SelectionState) = (s.current as SelectionState.Lines).ids

    @Test
    fun tappingTheSameSpotCyclesWithNoTimeWindow() {
        val s = SelectionState()
        assertTrue(s.tap(40.0, 53.0, 1.0, lines, adding = false, insideBox = noBox))
        val first = ids(s).single()
        val seen = mutableListOf(first)
        repeat(3) { assertTrue(s.tap(41.0, 53.5, 1.0, lines, false, noBox)); seen += ids(s).single() }
        // Every candidate under the tap once per round, then back to the first: no clock involved at all.
        assertEquals(setOf("a", "b", "c"), seen.take(3).toSet())
        assertEquals(first, seen[3], "the fourth tap wraps round")
    }

    @Test
    fun aTapElsewhereStartsAgainAndATapOnNothingOutsideEnds() {
        val s = SelectionState()
        s.tap(40.0, 53.0, 1.0, lines, false, noBox)
        s.tap(41.0, 53.0, 1.0, lines, false, noBox)
        s.tap(40.0, 300.0, 1.0, lines, false, noBox)
        assertEquals(setOf("far"), ids(s), "a tap on another line replaces the selection")
        assertTrue(s.tap(40.0, 200.0, 1.0, lines, false, noBox))
        assertNull(s.current, "tapping outside commits")
    }

    @Test
    fun aTapOnEmptySpaceInsideTheBoxChangesNothing() {
        val s = SelectionState()
        s.tap(40.0, 300.0, 1.0, lines, false, noBox)
        assertFalse(s.tap(40.0, 200.0, 1.0, lines, false) { _, _ -> true })
        assertEquals(setOf("far"), ids(s))
    }

    @Test
    fun addingTogglesLinesInAndOut() {
        val s = SelectionState()
        s.tap(40.0, 300.0, 1.0, lines, false, noBox)
        s.tap(40.0, 50.0, 1.0, lines, adding = true, insideBox = noBox)
        assertEquals(setOf("far", "a"), ids(s))
        s.tap(40.0, 300.0, 1.0, lines, adding = true, insideBox = noBox)
        assertEquals(setOf("a"), ids(s), "a tap on a selected line takes it back out")
    }

    @Test
    fun aLoopWithNoWholeLineIsPaintAndTheChipsReReadTheSameLoop() {
        val s = SelectionState()
        val loop = square(-10.0, 45.0, 60.0, 60.0) // crosses a, b and c at x 60: none whole
        s.loop(loop, lines, lines, SelectionState.Reading(), adding = false)
        val px = assertIs<SelectionState.Pixels>(s.current)
        assertEquals(0, px.linesInLoop)
        assertNull(s.reread(SelectionState.Reading(reach = LineLasso.Reach.TOUCHING), lines, lines))
        assertEquals(setOf("a", "b", "c"), ids(s), "Touching takes the three it crosses")
        assertNull(s.reread(SelectionState.Reading(take = SelectionState.Take.PIXELS, reach = LineLasso.Reach.TOUCHING), lines, lines))
        assertEquals(3, assertIs<SelectionState.Pixels>(s.current).linesInLoop, "the Lines chip still knows its count")
    }

    @Test
    fun askingForLinesFallsBackToTouchingInsteadOfDoingNothing() {
        // Owner's K13: the loop crossed lines without enclosing any, so it took paint. Tapping Lines must take the lines it
        // touches (and say Touching), not grey out.
        val s = SelectionState()
        s.loop(square(-10.0, 45.0, 60.0, 60.0), lines, lines, SelectionState.Reading(), adding = false)
        assertIs<SelectionState.Pixels>(s.current)
        assertNull(s.reread(SelectionState.Reading(take = SelectionState.Take.LINES), lines, lines))
        val got = assertIs<SelectionState.Lines>(s.current)
        assertEquals(setOf("a", "b", "c"), got.ids)
        assertEquals(LineLasso.Reach.TOUCHING, got.reading?.reach, "the chip follows the fallback")
    }

    @Test
    fun aLoopTouchingNoLineStaysPaintAndSaysSo() {
        val s = SelectionState()
        s.loop(square(-10.0, 100.0, 50.0, 200.0), lines, lines, SelectionState.Reading(), adding = false)
        val before = s.current
        assertEquals(SelectionState.NO_LINES, s.reread(SelectionState.Reading(take = SelectionState.Take.LINES), lines, lines))
        assertEquals(before, s.current)
    }

    @Test
    fun chosenLinesAddOnlyInAddMode() {
        // Owner's K12: the crosshair, the fan and the count scrubber add when ＋ is on, and replace when it is off.
        val s = SelectionState()
        s.choose("a", adding = false, at = null)
        s.choose("c", adding = true, at = null)
        assertEquals(setOf("a", "c"), ids(s))
        s.choose("a", adding = true, at = null)
        assertEquals(setOf("c"), ids(s), "choosing a selected line in add mode takes it back out")
        s.choose("b", adding = false, at = SelectionState.Pick(40.0, 53.0))
        assertEquals(setOf("b"), ids(s))
    }

    @Test
    fun lockedLinesAreSkippedUntilPickLockedIsOn() {
        val items = object : SelectionState.Items { override fun locked(id: String) = id == "b" }
        val s = SelectionState(items = items)
        s.tap(40.0, 53.0, 1.0, lines, false, noBox)
        assertTrue("b" !in ids(s), "a tap right on the locked line takes the next one instead")
        s.loop(square(-10.0, 45.0, 110.0, 60.0), lines, lines, SelectionState.Reading(), adding = false)
        assertEquals(setOf("a", "c"), ids(s), "a loop skips it too")
        s.choose("b", adding = false, at = null, lines = lines)
        assertEquals(setOf("a", "c"), ids(s), "and so does a crosshair or the fan")
        s.pickLocked = true
        s.tap(40.0, 53.0, 1.0, lines, false, noBox)
        assertEquals(setOf("b"), ids(s), "with Pick locked lines too, it can be picked (to unlock it)")
    }

    @Test
    fun aGroupComesTogether() {
        val items = object : SelectionState.Items { override fun group(id: String) = if (id == "a" || id == "far") "g1" else null }
        val s = SelectionState(items = items)
        s.tap(40.0, 300.0, 1.0, lines, false, noBox)
        assertEquals(setOf("far", "a"), ids(s), "touching one member takes the whole group")
        s.tap(40.0, 56.0, 1.0, lines, adding = true, insideBox = noBox)
        assertEquals(setOf("far", "a", "c"), ids(s))
        s.tap(40.0, 300.0, 1.0, lines, adding = true, insideBox = noBox)
        assertEquals(setOf("c"), ids(s), "and taking one back out takes the group out")
        s.loop(square(-10.0, 290.0, 110.0, 310.0), lines, lines, SelectionState.Reading(), adding = false)
        assertEquals(setOf("far", "a"), ids(s), "a loop around one member takes the group")
    }

    @Test
    fun allLayersCannotApplyToPaint() {
        val s = SelectionState()
        s.loop(square(-10.0, 100.0, 50.0, 200.0), lines, lines, SelectionState.Reading(), adding = false)
        val before = s.current
        assertEquals(SelectionState.PAINT_ONE_LAYER, s.reread(SelectionState.Reading(allLayers = true), lines, lines))
        assertEquals(before, s.current, "refused in words, nothing changed")
    }

    @Test
    fun allLayersFindsLinesOnOtherLayers() {
        val other = hline("sketch", 10.0, 40.0, 150.0)
        val s = SelectionState()
        val loop = square(0.0, 100.0, 50.0, 200.0)
        s.loop(loop, lines, lines + other, SelectionState.Reading(), adding = false)
        assertIs<SelectionState.Pixels>(s.current)
        assertNull(s.reread(SelectionState.Reading(allLayers = true), lines, lines + other))
        assertEquals(setOf("sketch"), ids(s))
    }

    @Test
    fun loopingAgainAddsAndLoopingSelectedLinesRemovesThem() {
        val s = SelectionState()
        s.tap(40.0, 300.0, 1.0, lines, false, noBox)
        val aOnly = square(-10.0, 48.0, 110.0, 51.5)
        s.loop(aOnly, lines, lines, SelectionState.Reading(), adding = true)
        assertEquals(setOf("far", "a"), ids(s))
        s.loop(aOnly, lines, lines, SelectionState.Reading(), adding = true)
        assertEquals(setOf("far"), ids(s), "every line the loop takes was selected, so it subtracts")
    }

    @Test
    fun theKeptLoopMovesWithTheSelection() {
        val s = SelectionState()
        s.loop(square(-10.0, 290.0, 110.0, 310.0), lines, lines, SelectionState.Reading(), adding = false)
        s.moved(Homography.translate(0.0, -250.0))
        assertNull(s.reread(SelectionState.Reading(reach = LineLasso.Reach.TOUCHING), lines, lines))
        assertEquals(setOf("a", "b", "c"), ids(s), "the loop now sits over the hatching, so a re-read reads there")
    }

    @Test
    fun garbageChangesNothing() {
        val s = SelectionState()
        assertFalse(s.tap(Double.NaN, 1.0, 1.0, lines, false, noBox))
        assertFalse(s.loop(listOf(Pt(0.0, 0.0), Pt(1.0, 1.0)), lines, lines, SelectionState.Reading(), false))
        assertNull(s.current)
    }
}

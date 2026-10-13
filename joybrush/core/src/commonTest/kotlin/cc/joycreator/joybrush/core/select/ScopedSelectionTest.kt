package cc.joycreator.joybrush.core.select

import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.vector.InkLine
import cc.joycreator.joybrush.core.vector.StrokePicker
import kotlin.test.*

class ScopedSelectionTest {
    private val shared = LineAddress("layer", null, "same")
    private val frame = LineAddress("layer", "frame", "same")
    private val otherLayer = LineAddress("other", "frame", "same")
    private fun line(address: LineAddress) = SelectionLine(address,
        InkLine(address.recordId, doubleArrayOf(10.0, 90.0), doubleArrayOf(50.0, 50.0), doubleArrayOf(2.0, 2.0)))
    private val lines = listOf(shared, frame, otherLayer).map(::line)
    private val loop = listOf(Pt(0.0, 40.0), Pt(100.0, 40.0), Pt(100.0, 60.0), Pt(0.0, 60.0))
    private fun ids(state: SelectionState) = assertIs<SelectionState.Lines>(state.current).ids

    @Test fun identicalRecordIdsCycleThroughThreeDistinctOwners() {
        val state = SelectionState()
        val seen = mutableListOf<LineAddress>()
        repeat(4) {
            assertTrue(state.tap(50.0, 50.0, 1.0, lines, false) { _, _ -> false })
            seen += ids(state).single()
        }
        assertEquals(listOf(otherLayer, frame, shared, otherLayer), seen)
    }

    @Test fun lassoKeepsEveryOwnerAndAllLayersRereadExpandsScope() {
        val state = SelectionState()
        state.loop(loop, lines.take(1), lines, SelectionState.Reading(), false)
        assertEquals(setOf(shared), ids(state))
        assertNull(state.reread(SelectionState.Reading(allLayers = true), lines.take(1), lines))
        assertEquals(setOf(shared, frame, otherLayer), ids(state))
    }

    @Test fun flagsResolveTheWholeAddressAndCountKeepsItsFixedBase() {
        val state = SelectionState(items = object : SelectionState.Items {
            override fun locked(id: LineAddress) = id == otherLayer
            override fun group(id: LineAddress) = if (id == shared) "group" else null
        })
        state.loop(loop, lines, lines, SelectionState.Reading(), false)
        assertEquals(setOf(shared, frame), ids(state))
        assertEquals(setOf(frame), state.countBaseWithout(shared, lines))
        assertTrue(state.chooseFromBase(shared, setOf(frame), SelectionState.Pick(50.0, 50.0), lines))
        assertEquals(setOf(shared, frame), ids(state))
        assertFalse(state.chooseFromBase(otherLayer, emptySet(), SelectionState.Pick(50.0, 50.0), lines))
        assertEquals(setOf(shared, frame), ids(state))
        state.pickLocked = true
        assertTrue(state.chooseFromBase(otherLayer, emptySet(), SelectionState.Pick(50.0, 50.0), lines))
        assertEquals(setOf(otherLayer), ids(state))
    }

    @Test fun geometryOrdinalsPreserveInputOrderWithoutChangingLegacyIds() {
        val geometry = lines.map { it.geometry }
        val picker = StrokePicker()
        assertEquals(listOf(2, 1, 0), picker.rankIndices(geometry, 50.0, 50.0, 1.0))
        assertEquals(listOf("same", "same", "same"), picker.rank(geometry, 50.0, 50.0, 1.0))
        assertEquals(listOf(0, 1, 2), LineLasso.takeIndices(geometry, loop, LineLasso.Reach.WHOLE))
        assertEquals(listOf("same", "same", "same"), LineLasso.take(geometry, loop, LineLasso.Reach.WHOLE))
    }

    @Test fun geometryAddressMismatchIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            SelectionLine(shared, lines.first().geometry.copy(id = "different"))
        }
    }
}

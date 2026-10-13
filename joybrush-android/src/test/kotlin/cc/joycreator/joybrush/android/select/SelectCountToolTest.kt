package cc.joycreator.joybrush.android.select

import android.view.InputDevice
import android.view.MotionEvent
import cc.joycreator.joybrush.androidkit.select.LiftedLines
import cc.joycreator.joybrush.androidkit.select.LiftedPixels
import cc.joycreator.joybrush.androidkit.select.SelectTool
import cc.joycreator.joybrush.androidkit.select.SelectionHost
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.select.Homography
import cc.joycreator.joybrush.core.select.SelectionMask
import cc.joycreator.joybrush.core.select.SelectionState
import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.select.SelectionLine
import cc.joycreator.joybrush.core.vector.InkLine
import cc.joycreator.joybrush.core.view.ViewTransform
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SelectCountToolTest {
    private class Host(initial: List<StrokeRecord>) : SelectionHost {
        var records = initial.toMutableList()
        override var paintClip: SelectionMask? = null
        var liftCount = 0
        var lineCommits = 0
        var replacements = 0
        var additions = 0
        var deletions = 0
        var bakes = 0
        val messages = mutableListOf<String>()

        private fun lines() = records.map { record ->
            val xs = record.samples.map { it.x.toDouble() }.toDoubleArray()
            val ys = record.samples.map { it.y.toDouble() }.toDoubleArray()
            SelectionHost.Line(LineAddress("layer", null, record.id), InkLine(record.id, xs, ys, DoubleArray(xs.size) { 2.0 }),
                record, record.locked, record.groupId)
        }
        override fun activeLines() = lines()
        override fun visibleLines() = lines()
        override fun liftLines(ids: Set<LineAddress>): LiftedLines {
            liftCount++
            return object : LiftedLines {
                override fun setTransform(h: Homography) {}
                override fun preview(records: List<AddressedStroke>) {}
                override fun commit(after: List<AddressedStroke>): Boolean { lineCommits++; return false }
                override fun release() {}
            }
        }
        override fun replaceLines(after: List<AddressedStroke>): Boolean { replacements++; return false }
        override fun addLines(records: List<AddressedStroke>): Boolean { additions++; return false }
        override fun deleteLines(ids: Set<LineAddress>): Boolean { deletions++; return false }
        override fun bakeLines(ids: Set<LineAddress>): Boolean { bakes++; return false }
        override fun liftPixels(mask: SelectionMask, keepSource: Boolean): LiftedPixels? = null
        override fun dimRest(alpha: Float) {}
        override fun adjustColour(hueDeg: Float, saturation: Float, brightness: Float, done: Boolean) {}
        override fun penButtonTap(screenX: Float, screenY: Float) {}
        override fun toast(text: String, action: String?, onAction: (() -> Unit)?) { messages += text }
        override fun invalidate() {}

        fun assertNoDrawingWrites() {
            assertEquals(0, lineCommits)
            assertEquals(0, replacements)
            assertEquals(0, additions)
            assertEquals(0, deletions)
            assertEquals(0, bakes)
        }
    }

    private fun record(id: String, y: Float = 50f, locked: Boolean = false, group: String? = null) = StrokeRecord(
        id = id, brushId = "brush", seed = id.hashCode().toLong(), smoothing = 0f, screenPerDoc = 1f,
        // Keep midpoint taps clear of the existing 16dp endpoint bend target.
        samples = listOf(PenSample(10f, y, 0.0), PenSample(90f, y, 1.0)), locked = locked, groupId = group,
    )

    private fun fixture(vararg records: StrokeRecord): Pair<SelectTool, Host> {
        val host = Host(records.toList())
        return SelectTool(host, 1f) { true } to host
    }

    private fun tap(tool: SelectTool, x: Float = 50f, y: Float = 50f, time: Long = 10L) {
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        fun event(action: Int, at: Long) = MotionEvent.obtain(0L, at, action, 1, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
        val down = event(MotionEvent.ACTION_DOWN, time)
        try { assertTrue(tool.down(down, 0, ViewTransform())) } finally { down.recycle() }
        val up = event(MotionEvent.ACTION_UP, time + 1L)
        try { assertTrue(tool.event(up, ViewTransform())) } finally { up.recycle() }
    }

    private fun choose(tool: SelectTool, order: List<String>, index: Int = 0, adding: Boolean = false,
                       at: SelectionState.Pick = SelectionState.Pick(50.0, 50.0)) {
        tool.chooseFromOrder(order.map { LineAddress("layer", null, it) }, index, adding, at)
    }

    private fun addresses(vararg ids: String) = ids.map { LineAddress("layer", null, it) }.toSet()

    private fun ids(tool: SelectTool): Set<LineAddress> = (tool.selection as SelectionState.Lines).ids

    @Test fun repeatedRealTapsKeepTheRankOrdinalAnchorAndNeverWriteDrawingHistory() {
        val (tool, host) = fixture(record("a"), record("b"), record("c"))
        tap(tool)
        assertEquals(addresses("c"), ids(tool))
        assertEquals(SelectTool.CountPick(SelectionState.Pick(50.0, 50.0), 0, 3), tool.countPick)
        tap(tool, time = 30L)
        assertEquals(addresses("b"), ids(tool))
        assertEquals(SelectTool.CountPick(SelectionState.Pick(50.0, 50.0), 1, 3), tool.countPick)
        tap(tool, time = 50L)
        assertEquals(addresses("a"), ids(tool))
        assertEquals(SelectTool.CountPick(SelectionState.Pick(50.0, 50.0), 2, 3), tool.countPick)
        host.assertNoDrawingWrites()
    }

    @Test fun additiveScrubUsesOneFixedBaseAndReversalDoesNotAccumulate() {
        val (tool, host) = fixture(record("a", group = "pair"), record("b", group = "pair"), record("c"), record("other", 150f))
        choose(tool, listOf("other"))
        choose(tool, listOf("c", "b", "a"), index = 2, adding = true)
        assertEquals(addresses("other", "a", "b"), ids(tool))
        val session = tool.beginCountScrub(adding = true)!!
        assertTrue(tool.scrubCount(session, 0))
        assertEquals(addresses("other", "c"), ids(tool))
        assertTrue(tool.scrubCount(session, 2))
        assertEquals(addresses("other", "a", "b"), ids(tool))
        assertTrue(tool.scrubCount(session, 0))
        assertEquals(addresses("other", "c"), ids(tool))
        host.assertNoDrawingWrites()
    }

    @Test fun groupCandidateIsRemovedAndRestoredAsItsWholeUnit() {
        val (tool, host) = fixture(record("a", group = "pair"), record("b", group = "pair"), record("c"))
        choose(tool, listOf("c", "b", "a"), index = 2)
        assertEquals(addresses("a", "b"), ids(tool))
        val session = tool.beginCountScrub(adding = false)!!
        assertTrue(tool.scrubCount(session, 0))
        assertEquals(addresses("c"), ids(tool))
        assertTrue(tool.scrubCount(session, 2))
        assertEquals(addresses("a", "b"), ids(tool))
        host.assertNoDrawingWrites()
    }

    @Test fun emptySingleAndPixelSelectionsHaveNoCountContext() {
        val (tool, _) = fixture(record("a"))
        tap(tool, x = 150f, y = 150f)
        assertNull(tool.selection)
        assertNull(tool.countPick)
        tap(tool)
        assertTrue(tool.selection is SelectionState.Lines)
        assertNull(tool.countPick)
        assertNull(tool.beginCountScrub(adding = false))

        tool.end()
        tool.state.loop(listOf(Pt(0.0, 0.0), Pt(20.0, 0.0), Pt(20.0, 20.0), Pt(0.0, 20.0)),
            emptyList(), emptyList(), SelectionState.Reading(take = SelectionState.Take.PIXELS), adding = false)
        tool.reread(SelectionState.Reading(take = SelectionState.Take.PIXELS))
        assertTrue(tool.selection is SelectionState.Pixels)
        assertNull(tool.countPick)
        assertNull(tool.beginCountScrub(adding = false))
    }

    @Test fun selectionChangeAndEndInvalidateHeldCountSessions() {
        val (tool, host) = fixture(record("a"), record("b"), record("c"))
        choose(tool, listOf("c", "b", "a"))
        val changedSession = tool.beginCountScrub(adding = false)!!
        choose(tool, listOf("c", "b", "a"), index = 1)
        assertFalse(tool.scrubCount(changedSession, 2))
        val endedSession = tool.beginCountScrub(adding = false)!!
        tool.end()
        assertFalse(tool.scrubCount(endedSession, 0))
        assertNull(tool.countPick)
        host.assertNoDrawingWrites()
    }

    @Test fun deletedCandidateInvalidatesScrubWithoutWriting() {
        val (tool, host) = fixture(record("a"), record("b"), record("c"))
        choose(tool, listOf("c", "b", "a"))
        val session = tool.beginCountScrub(adding = false)!!
        host.records.removeAll { it.id == "b" }
        assertFalse(tool.scrubCount(session, 1))
        assertNull(tool.countPick)
        assertTrue(host.messages.isNotEmpty())
        host.assertNoDrawingWrites()
    }

    @Test fun lockedCandidateInvalidatesScrubWithoutWriting() {
        val (tool, host) = fixture(record("a"), record("b"), record("c"))
        choose(tool, listOf("c", "b", "a"))
        val session = tool.beginCountScrub(adding = false)!!
        val b = host.records.first { it.id == "b" }
        host.records[host.records.indexOf(b)] = b.copy(locked = true)
        assertFalse(tool.scrubCount(session, 1))
        assertNull(tool.countPick)
        host.assertNoDrawingWrites()
    }

    @Test fun changedGroupMembershipInvalidatesScrubEvenWhenRankIsUnchanged() {
        val (tool, host) = fixture(record("a", group = "pair"), record("b", group = "pair"), record("c"))
        choose(tool, listOf("c", "b", "a"), index = 2, adding = true)
        assertEquals(addresses("a", "b"), ids(tool))
        val session = tool.beginCountScrub(adding = true)!!
        val bIndex = host.records.indexOfFirst { it.id == "b" }
        host.records[bIndex] = host.records[bIndex].copy(groupId = null)

        assertFalse(tool.scrubCount(session, 0))
        assertNull(tool.countPick)
        assertEquals("stale group data must not alter the live selection", addresses("a", "b"), ids(tool))
        host.assertNoDrawingWrites()
    }

    @Test fun changedRankInvalidatesScrubWithoutWriting() {
        val (tool, host) = fixture(record("a"), record("b"), record("c"))
        choose(tool, listOf("c", "b", "a"))
        val session = tool.beginCountScrub(adding = false)!!
        host.records[host.records.indexOfFirst { it.id == "c" }] = record("c", y = 120f)
        assertFalse(tool.scrubCount(session, 1))
        assertNull(tool.countPick)
        host.assertNoDrawingWrites()
    }

    @Test fun endingScrubKeepsLastChoiceAndSameIndexDoesNotRelift() {
        val (tool, host) = fixture(record("a"), record("b"), record("c"))
        choose(tool, listOf("c", "b", "a"))
        val session = tool.beginCountScrub(adding = false)!!
        val liftBefore = host.liftCount
        assertTrue(tool.scrubCount(session, 0))
        assertEquals(liftBefore, host.liftCount)
        assertTrue(tool.scrubCount(session, 1))
        val afterChoice = host.liftCount
        tool.endCountScrub(session)
        assertEquals(addresses("b"), ids(tool))
        assertEquals(SelectTool.CountPick(SelectionState.Pick(50.0, 50.0), 1, 3), tool.countPick)
        assertEquals(afterChoice, host.liftCount)
        assertFalse(tool.scrubCount(session, 2))
        host.assertNoDrawingWrites()
    }
}

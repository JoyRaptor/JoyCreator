package cc.joycreator.joybrush.android.select

import android.view.InputDevice
import android.view.MotionEvent
import cc.joycreator.joybrush.androidkit.select.*
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.select.*
import cc.joycreator.joybrush.core.stroke.*
import cc.joycreator.joybrush.core.vector.InkLine
import cc.joycreator.joybrush.core.view.ViewTransform
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScopedSelectEditTest {
    private val a = LineAddress("layer", null, "same")
    private val b = LineAddress("layer", "frame", "same")
    private val c = LineAddress("other", "frame", "same")
    private fun record(seq: Long) = StrokeRecord("same", "brush", 7L, 0f, 1f,
        listOf(PenSample(10f, 50f, 0.0), PenSample(90f, 50f, 1.0)), seq = seq)
    private fun capturedRecord(seq: Long) = record(seq).copy(
        capturedPhysicalSource = CapturedPhysicalSource(CaptureDomain(CaptureRect(0, 0, 100, 100)))
    )

    private class Host(initial: List<AddressedStroke>, private val live: Boolean) : SelectionHost {
        // Deliberately not seq-sorted: changing order must never re-pair ownership and records.
        var stored = initial.associate { it.address to it.record }.toMutableMap()
        var lastEdit: List<AddressedStroke> = emptyList()
        var preview: List<AddressedStroke> = emptyList()
        var lifted: Set<LineAddress> = emptySet()
        var deleted: Set<LineAddress> = emptySet()
        var baked: Set<LineAddress> = emptySet()
        override var paintClip: SelectionMask? = null
        override fun activeLines() = stored.map { (address, record) ->
            SelectionHost.Line(address, InkLine(record.id,
                record.samples.map { it.x.toDouble() }.toDoubleArray(),
                record.samples.map { it.y.toDouble() }.toDoubleArray(), DoubleArray(record.samples.size) { 2.0 }),
                record, record.locked, record.groupId)
        }
        override fun visibleLines() = activeLines()
        override fun liftLines(ids: Set<LineAddress>): LiftedLines? {
            lifted = ids
            if (!live) return null
            return object : LiftedLines {
                override fun setTransform(h: Homography) {}
                override fun preview(records: List<AddressedStroke>) { preview = records }
                override fun commit(after: List<AddressedStroke>) = replaceLines(after)
                override fun release() {}
            }
        }
        override fun replaceLines(after: List<AddressedStroke>): Boolean {
            require(after.all { it.address in stored })
            lastEdit = after
            val changed = after.any { stored[it.address] != it.record }
            after.forEach { stored[it.address] = it.record }
            return changed
        }
        override fun addLines(records: List<AddressedStroke>): Boolean {
            require(records.all { it.address !in stored })
            lastEdit = records
            records.forEach { stored[it.address] = it.record.copy(seq = 99L) }
            return records.isNotEmpty()
        }
        override fun deleteLines(ids: Set<LineAddress>): Boolean { deleted = ids; ids.forEach(stored::remove); return ids.isNotEmpty() }
        override fun bakeLines(ids: Set<LineAddress>): Boolean { baked = ids; return ids.isNotEmpty() }
        override fun liftPixels(mask: SelectionMask, keepSource: Boolean): LiftedPixels? = null
        override fun dimRest(alpha: Float) {}
        override fun adjustColour(hueDeg: Float, saturation: Float, brightness: Float, done: Boolean) {}
        override fun penButtonTap(screenX: Float, screenY: Float) {}
        override fun toast(text: String, action: String?, onAction: (() -> Unit)?) {}
        override fun invalidate() {}
    }

    private fun fixture(live: Boolean = true): Pair<SelectTool, Host> {
        val host = Host(listOf(AddressedStroke(a, record(80)), AddressedStroke(b, record(2)), AddressedStroke(c, record(40))), live)
        return SelectTool(host, 1f) { true } to host
    }
    private fun choose(tool: SelectTool, address: LineAddress, adding: Boolean = false) =
        tool.chooseFromOrder(listOf(address), 0, adding, SelectionState.Pick(50.0, 50.0))
    private fun move(tool: SelectTool) {
        tool.beginBox()
        val q = tool.boxDoc!!.copyOf().also { for (i in 0..3) it[i * 2] += 30f }
        assertTrue(tool.boxTo(q))
        assertTrue(tool.commitBox())
    }

    private fun drag(tool: SelectTool, x: Float, y: Float, dx: Float, dy: Float) {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        fun event(action: Int, at: Long, px: Float, py: Float) = MotionEvent.obtain(0L, at, action, 1, properties,
            arrayOf(MotionEvent.PointerCoords().apply { this.x = px; this.y = py; pressure = 1f; size = 1f }),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
        val down = event(MotionEvent.ACTION_DOWN, 1L, x, y)
        try { assertTrue(tool.down(down, 0, ViewTransform())) } finally { down.recycle() }
        val move = event(MotionEvent.ACTION_MOVE, 2L, x + dx, y + dy)
        try { assertTrue(tool.event(move, ViewTransform())) } finally { move.recycle() }
        val up = event(MotionEvent.ACTION_UP, 3L, x + dx, y + dy)
        try { assertTrue(tool.event(up, ViewTransform())) } finally { up.recycle() }
    }

    @Test fun tipBendsOnlyItsAddressEvenWithSameIdSiblingSelected() {
        val (tool, host) = fixture()
        val originals = host.stored.toMap()
        choose(tool, a)
        choose(tool, b, adding = true)
        drag(tool, 10f, 50f, 10f, 10f)
        assertNotEquals(originals[a], host.stored[a])
        assertEquals(originals[b], host.stored[b])
        assertEquals(originals[c], host.stored[c])
        assertEquals(80L, host.stored[a]!!.seq)
    }

    @Test fun nudgeKeepsOtherCelsUntouched() {
        val (tool, host) = fixture()
        val originals = host.stored.toMap()
        choose(tool, b)
        tool.nudging = true
        drag(tool, 50f, 50f, 20f, 10f)
        assertNotEquals(originals[b], host.stored[b])
        assertEquals(originals[a], host.stored[a])
        assertEquals(originals[c], host.stored[c])
        assertEquals(2L, host.stored[b]!!.seq)
    }

    @Test fun transformWeightResetAndColourKeepOneExactOwnerWithLiveLift() {
        val (tool, host) = fixture()
        val originals = host.stored.toMap()
        choose(tool, b)
        assertEquals(setOf(b), host.lifted)
        move(tool)
        assertEquals(listOf(b), host.lastEdit.map { it.address })
        assertEquals(2L, host.lastEdit.single().record.seq)
        assertEquals(40f, host.stored[b]!!.samples.first().x, 0.001f)
        assertTrue(tool.reweight(2f, false))
        assertEquals(listOf(b), host.preview.map { it.address })
        assertTrue(tool.reweight(2f, true))
        assertTrue(tool.recolour(0xFF123456.toInt()))
        assertTrue(tool.resetBox())
        assertEquals(originals[b], host.stored[b])
        assertEquals(originals[a], host.stored[a])
        assertEquals(originals[c], host.stored[c])
    }

    @Test fun directFallbackAndUnsortedMultiRecordEditsKeepTheirPairingAndSeqs() {
        val (tool, host) = fixture(live = false)
        choose(tool, a)
        choose(tool, b, adding = true)
        move(tool)
        assertEquals(listOf(a, b), host.lastEdit.map { it.address })
        assertEquals(listOf(80L, 2L), host.lastEdit.map { it.record.seq })
        assertEquals(10f, host.stored[c]!!.samples.first().x, 0.001f)
        assertTrue(tool.rebrush("new-brush"))
        assertEquals("new-brush", host.stored[a]!!.brushId)
        assertEquals("new-brush", host.stored[b]!!.brushId)
        assertEquals("brush", host.stored[c]!!.brushId)
    }

    @Test fun duplicateUsesFreshIdInSourceCelAndHostAssignsNewSequence() {
        val (tool, host) = fixture()
        choose(tool, b)
        assertTrue(tool.duplicate { "copy" })
        val copy = b.copy(recordId = "copy")
        assertEquals(listOf(copy), host.lastEdit.map { it.address })
        assertEquals(0L, host.lastEdit.single().record.seq)
        assertEquals(99L, host.stored[copy]!!.seq)
        assertEquals(setOf(copy), (tool.selection as SelectionState.Lines).ids)
        assertEquals(4, host.stored.size)
        assertEquals(10f, host.stored[b]!!.samples.first().x, 0.001f)
    }

    @Test fun deleteAndBakeReceiveOnlyTheChosenCel() {
        val (tool, host) = fixture()
        choose(tool, b)
        assertTrue(tool.deleteSelection())
        assertEquals(setOf(b), host.deleted)
        assertEquals(setOf(a, c), host.stored.keys)
        choose(tool, c)
        assertTrue(tool.rasterizeDown())
        assertEquals(setOf(c), host.baked)
    }

    @Test fun externalOrderCannotSelectCapturedLineForLiftDeleteOrBake() {
        val host = Host(listOf(AddressedStroke(b, capturedRecord(2))), live = true)
        val tool = SelectTool(host, 1f) { true }

        choose(tool, b)
        assertNull(tool.selection)
        assertTrue(host.lifted.isEmpty())
        assertFalse(tool.deleteSelection())
        assertTrue(host.deleted.isEmpty())
        assertFalse(tool.rasterizeDown())
        assertTrue(host.baked.isEmpty())

        val (ordinaryTool, ordinaryHost) = fixture()
        choose(ordinaryTool, b)
        assertEquals(setOf(b), ordinaryHost.lifted)
        assertTrue(ordinaryTool.deleteSelection())
        assertEquals(setOf(b), ordinaryHost.deleted)
    }

    @Test fun deleteAndBakeRefuseWhenSelectedOrdinaryAddressNowNamesCapturedRecord() {
        val (deleteTool, deleteHost) = fixture()
        choose(deleteTool, b)
        deleteHost.stored[b] = capturedRecord(2)
        assertFalse(deleteTool.deleteSelection())
        assertTrue(deleteHost.deleted.isEmpty())

        val (bakeTool, bakeHost) = fixture()
        choose(bakeTool, b)
        bakeHost.stored[b] = capturedRecord(2)
        assertFalse(bakeTool.rasterizeDown())
        assertTrue(bakeHost.baked.isEmpty())
    }

    @Test fun countScrubberDistinguishesSameIdCelsAndRejectsChangedOwnership() {
        val (tool, host) = fixture()
        tool.chooseFromOrder(listOf(c, b, a), 0, false, SelectionState.Pick(50.0, 50.0))
        val session = tool.beginCountScrub(false)!!
        assertTrue(tool.scrubCount(session, 1))
        assertEquals(setOf(b), (tool.selection as SelectionState.Lines).ids)
        host.stored[a.copy(celId = "new-frame")] = host.stored.remove(a)!!
        assertFalse(tool.scrubCount(session, 2))
        assertEquals(setOf(b), (tool.selection as SelectionState.Lines).ids)
        assertNull(tool.countPick)
    }
}

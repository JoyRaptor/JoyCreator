package cc.joycreator.joybrush.android.select

import cc.joycreator.joybrush.androidkit.select.*
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.select.*
import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.select.SelectionLine
import cc.joycreator.joybrush.core.vector.InkLine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SelectToolEditTest {
    @Test fun oversizedPaintCoverageIsRefusedBeforeRasterisationOrHostLift() {
        val host = Host()
        val tool = SelectTool(host, 1f) { true }
        tool.state.loop(listOf(Pt(0.0, 0.0), Pt(2304.0, 0.0), Pt(2304.0, 2048.0), Pt(0.0, 2048.0)),
            emptyList(), emptyList(), SelectionState.Reading(take = SelectionState.Take.PIXELS), adding = false)
        tool.reread(SelectionState.Reading(take = SelectionState.Take.PIXELS))
        assertNull(tool.selection)
        assertNull(host.paintClip)
        assertEquals(0, host.pixelLifts)
        assertTrue(host.notes.single().contains("too many tiles"))
    }

    @Test fun oversizedPixelTransformIsRefusedBeforePreviewOrCommit() {
        val pixels = Pixels()
        val host = Host(pixels = pixels)
        val tool = SelectTool(host, 1f) { true }
        selectPaint(tool)
        val clip = host.paintClip
        tool.beginBox()
        val oversized = tool.boxDoc!!.map { it * 100f }.toFloatArray()
        assertFalse(tool.boxTo(oversized))
        assertEquals(Homography.IDENTITY, pixels.previewTransform)
        assertEquals(0, pixels.commits)
        assertSame(clip, host.paintClip)
        tool.cancelBox()
    }

    @Test fun pixelCommitRechecksCoverageBeforeTheHostCanPublish() {
        val pixels = Pixels()
        val host = Host(pixels = pixels)
        val tool = SelectTool(host, 1f) { true }
        selectPaint(tool)
        val clip = host.paintClip
        val original = tool.boxDoc!!.copyOf()
        tool.beginBox()
        // A stale or external overlay can mutate this public array after boxTo's admission.
        val changed = tool.boxDoc!!
        for (i in changed.indices) changed[i] *= 100f
        assertFalse(tool.commitBox())
        assertEquals(0, pixels.commits)
        assertSame(clip, host.paintClip)
        assertArrayEquals(original, tool.boxDoc!!, 0f)
    }

    @Test fun nudgeHoldsTheBeforeShapeOnlyUntilTheGestureEnds() {
        val host = Host(mutableListOf(record("a")))
        val tool = SelectTool(host, 1f) { true }
        selectLines(tool, host)
        tool.nudging = true
        val view = cc.joycreator.joybrush.core.view.ViewTransform()
        val down = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 20f, 10f, 0)
        assertTrue(tool.down(down, 0, view)); down.recycle()
        val move = android.view.MotionEvent.obtain(0, 10, android.view.MotionEvent.ACTION_MOVE, 20f, 30f, 0)
        tool.event(move, view); move.recycle()
        val ghost = tool.nudgeBeforeScreen().single()
        assertEquals(10f, ghost.points[1], 0f)
        val up = android.view.MotionEvent.obtain(0, 20, android.view.MotionEvent.ACTION_UP, 20f, 30f, 0)
        tool.event(up, view); up.recycle()
        assertTrue(tool.nudgeBeforeScreen().isEmpty())
    }
    private class Pixels : LiftedPixels {
        var commits = 0
        var commitResult = true
        var releases = 0
        var previewTransform: Homography = Homography.IDENTITY
        var coverageLoop: List<Pt> = emptyList()
        var coverage: SelectionMask? = null
        val fills = mutableListOf<SelectionMask>()
        override fun setTransform(h: Homography) { previewTransform = h }
        override fun commit(): Boolean {
            commits++
            if (commitResult) {
                coverageLoop = coverageLoop.map(previewTransform::apply)
                coverage = SelectionMask.polygon(coverageLoop)
                previewTransform = Homography.IDENTITY
            }
            return commitResult
        }
        override fun clear() = false
        override fun fill(argb: Int): Boolean {
            val current = coverage ?: return false
            fills += current
            return true
        }
        override fun release() { releases++ }
    }

    private class Lines(private val host: Host) : LiftedLines {
        var commits = 0
        var previews = 0
        var releases = 0
        var commitResult = true
        override fun setTransform(h: Homography) {}
        override fun preview(records: List<AddressedStroke>) { previews++ }
        override fun commit(after: List<AddressedStroke>): Boolean {
            commits++
            return commitResult && host.replaceLines(after)
        }
        override fun release() { releases++ }
    }

    private class Host(
        var records: MutableList<StrokeRecord> = mutableListOf(),
        val pixels: Pixels? = null,
    ) : SelectionHost {
        override var paintClip: SelectionMask? = null
        var pixelLifts = 0
        var lineLift: Lines? = null
        var rejectReplacements = false
        var provideLineLift = true
        val notes = mutableListOf<String>()
        private fun lines() = records.map { r ->
            val xs = r.samples.map { it.x.toDouble() }.toDoubleArray()
            val ys = r.samples.map { it.y.toDouble() }.toDoubleArray()
            SelectionHost.Line(LineAddress("layer", null, r.id), InkLine(r.id, xs, ys, DoubleArray(xs.size) { 1.0 }), r, false, null)
        }
        override fun activeLines() = lines()
        override fun visibleLines() = lines()
        override fun liftLines(ids: Set<LineAddress>): LiftedLines? {
            if (!provideLineLift) return null
            return Lines(this).also { lineLift = it }
        }
        override fun replaceLines(after: List<AddressedStroke>): Boolean {
            if (after.isEmpty() || rejectReplacements) return false
            val changed = after.any { next -> records.firstOrNull { LineAddress("layer", null, it.id) == next.address } != next.record }
            records = records.map { old -> after.firstOrNull { it.address == LineAddress("layer", null, old.id) }?.record ?: old }.toMutableList()
            return changed
        }
        override fun addLines(records: List<AddressedStroke>): Boolean { this.records.addAll(records.map { it.record }); return records.isNotEmpty() }
        override fun deleteLines(ids: Set<LineAddress>): Boolean { val before = records.size; records.removeAll { LineAddress("layer", null, it.id) in ids }; return before != records.size }
        override fun bakeLines(ids: Set<LineAddress>) = false
        override fun liftPixels(mask: SelectionMask, keepSource: Boolean): LiftedPixels? {
            pixelLifts++
            pixels?.let { lifted ->
                val b = mask.bounds()!!
                lifted.coverageLoop = listOf(
                    Pt(b.x.toDouble(), b.y.toDouble()), Pt((b.x + b.w).toDouble(), b.y.toDouble()),
                    Pt((b.x + b.w).toDouble(), (b.y + b.h).toDouble()), Pt(b.x.toDouble(), (b.y + b.h).toDouble()),
                )
                lifted.coverage = mask
            }
            return pixels
        }
        override fun dimRest(alpha: Float) {}
        override fun adjustColour(hueDeg: Float, saturation: Float, brightness: Float, done: Boolean) {}
        override fun penButtonTap(screenX: Float, screenY: Float) {}
        override fun toast(text: String, action: String?, onAction: (() -> Unit)?) { notes += text }
        override fun invalidate() {}
    }

    private fun record(id: String, offset: Float = 0f) = StrokeRecord(
        id, "brush", 7L, 0f, 1f,
        listOf(PenSample(offset + 10f, 10f, 0.0), PenSample(offset + 20f, 20f, 1.0)),
    )

    private fun selectLines(tool: SelectTool, host: Host, left: Double = 0.0, right: Double = 50.0) {
        val lines = host.activeLines().map { SelectionLine(it.address, it.geometry) }
        tool.state.loop(
            listOf(Pt(left, 0.0), Pt(right, 0.0), Pt(right, 40.0), Pt(left, 40.0)),
            lines, lines, SelectionState.Reading(take = SelectionState.Take.LINES), adding = false,
        )
        tool.reread(SelectionState.Reading(take = SelectionState.Take.LINES))
    }

    private fun selectPaint(tool: SelectTool) {
        tool.state.loop(listOf(Pt(0.0, 0.0), Pt(40.0, 0.0), Pt(40.0, 40.0), Pt(0.0, 40.0)),
            emptyList(), emptyList(), SelectionState.Reading(), adding = false)
        tool.reread(SelectionState.Reading(take = SelectionState.Take.PIXELS))
    }

    private fun movedBox(tool: SelectTool, dx: Float) {
        val q = tool.boxDoc!!.copyOf()
        for (i in 0 until 4) q[i * 2] += dx
        tool.beginBox()
        assertTrue(tool.boxTo(q))
    }

    private fun cross(): Host = Host(mutableListOf(
        StrokeRecord("a", "brush", 7L, 0f, 1f, listOf(PenSample(0f, 50f, 0.0), PenSample(100f, 50f, 1.0))),
        StrokeRecord("b", "brush", 7L, 0f, 1f, listOf(PenSample(50f, 0f, 0.0), PenSample(50f, 100f, 1.0)))))

    private fun boxOf(tool: SelectTool) = SelectBoxHost(tool, { cc.joycreator.joybrush.core.view.ViewTransform() },
        { android.graphics.RectF(0f, 0f, 200f, 200f) })

    /** D3 on the phone (Select bench, 10-24): tapping a selected line again lands on the box's body; it must still cycle. */
    @Test fun anUnmovedTapTheBoxTookPicksTheLineUnderneath() {
        val tool = SelectTool(cross(), 1f) { true }
        val box = boxOf(tool)
        tool.tapFromBox(50f, 50f)
        val first = (tool.selection as SelectionState.Lines).ids
        box.noteBodyContact(50f, 50f, single = true)
        box.cancelGesture()
        val second = (tool.selection as SelectionState.Lines).ids
        assertEquals(1, first.size)
        assertEquals(1, second.size)
        assertNotEquals(first, second)
    }

    @Test fun aBodyContactThatWasOneOfTwoNeverPicks() {
        val tool = SelectTool(cross(), 1f) { true }
        val box = boxOf(tool)
        tool.tapFromBox(50f, 50f)
        val first = (tool.selection as SelectionState.Lines).ids
        box.noteBodyContact(50f, 50f, single = false)
        box.cancelGesture()
        assertEquals(first, (tool.selection as SelectionState.Lines).ids)
    }

    @Test fun committedMoveThenReweightUsesMovedSamples() {
        val original = record("a")
        val host = Host(mutableListOf(original))
        val tool = SelectTool(host, 1f) { true }
        selectLines(tool, host)
        movedBox(tool, 30f)
        assertTrue(tool.commitBox())
        val moved = host.records.single()
        assertEquals(40f, moved.samples[0].x, 0.001f)
        assertTrue(tool.reweight(2f, done = true))
        val result = host.records.single()
        assertEquals(40f, result.samples[0].x, 0.001f)
        assertEquals(50f, result.samples[1].x, 0.001f)
        assertEquals(moved.widthScale * 2f, result.widthScale, 0.001f)
    }

    @Test fun canceledBoxThenReweightUsesCurrentRecords() {
        val host = Host(mutableListOf(record("a")))
        val tool = SelectTool(host, 1f) { true }
        selectLines(tool, host)
        movedBox(tool, 30f)
        assertTrue(tool.commitBox())
        val before = tool.boxDoc!!.copyOf()
        movedBox(tool, 20f)
        tool.cancelBox()
        assertArrayEquals(before, tool.boxDoc!!, 0.001f)
        assertTrue(tool.reweight(2f, done = true))
        assertEquals(40f, host.records.single().samples[0].x, 0.001f)
        assertEquals(50f, host.records.single().samples[1].x, 0.001f)
        assertEquals(2f, host.records.single().widthScale, 0.001f)
    }

    @Test fun failedLineTransformRestoresBoxAndRecords() {
        val host = Host(mutableListOf(record("a")))
        val tool = SelectTool(host, 1f) { true }
        selectLines(tool, host)
        val beforeBox = tool.boxDoc!!.copyOf()
        val beforeRecord = host.records.single()
        movedBox(tool, 30f)
        host.rejectReplacements = true
        assertFalse(tool.commitBox())
        assertArrayEquals(beforeBox, tool.boxDoc!!, 0.001f)
        assertEquals(beforeRecord, host.records.single())
    }

    @Test fun lineTransformAndReweightWorkWithoutLiveLiftPreview() {
        val host = Host(mutableListOf(record("a"))).apply { provideLineLift = false }
        val tool = SelectTool(host, 1f) { true }
        selectLines(tool, host)
        movedBox(tool, 30f)
        assertTrue(tool.commitBox())
        assertEquals(40f, host.records.single().samples[0].x, 0.001f)
        assertTrue(tool.reweight(2f, done = true))
        assertEquals(40f, host.records.single().samples[0].x, 0.001f)
        assertEquals(2f, host.records.single().widthScale, 0.001f)
    }

    @Test fun selectionChangeAndEndClearWeightDragSnapshot() {
        val host = Host(mutableListOf(record("a"), record("b", 100f)))
        val tool = SelectTool(host, 1f) { true }
        selectLines(tool, host)
        assertTrue(tool.reweight(1.5f, done = false))
        tool.chooseFromOrder(listOf(LineAddress("layer", null, "b")), 0, adding = false, at = SelectionState.Pick(110.0, 10.0))
        assertTrue(tool.reweight(2f, done = true))
        assertEquals(1f, host.records.first { it.id == "a" }.widthScale, 0.001f)
        assertEquals(2f, host.records.first { it.id == "b" }.widthScale, 0.001f)

        assertTrue(tool.reweight(1.5f, done = false))
        tool.end()
        assertFalse(tool.reweight(2f, done = true))
        assertEquals(2f, host.records.first { it.id == "b" }.widthScale, 0.001f)
    }

    @Test fun failedPixelTransformRestoresBoxAndKeepsClip() {
        val pixels = Pixels().apply { commitResult = false }
        val host = Host(pixels = pixels)
        val tool = SelectTool(host, 1f) { true }
        selectPaint(tool)
        val before = tool.boxDoc!!.copyOf()
        val clip = host.paintClip
        movedBox(tool, 30f)
        assertFalse(tool.commitBox())
        assertArrayEquals(before, tool.boxDoc!!, 0.001f)
        assertSame(clip, host.paintClip)
        assertNotNull(host.paintClip)
        assertEquals(1, pixels.commits)
        assertEquals(5.0, pixels.previewTransform.apply(Pt(5.0, 5.0)).x, 0.001)
        assertEquals(5.0, pixels.previewTransform.apply(Pt(5.0, 5.0)).y, 0.001)
        assertEquals(listOf(Pt(0.0, 0.0), Pt(40.0, 0.0), Pt(40.0, 40.0), Pt(0.0, 40.0)),
            (tool.selection as SelectionState.Pixels).loop)
    }

    @Test fun successfulPixelTransformMovesClipCoverageWithSingleCommitAndNoRelift() {
        val pixels = Pixels()
        val host = Host(pixels = pixels)
        val tool = SelectTool(host, 1f) { true }
        selectPaint(tool)
        val selection = tool.selection
        assertEquals(1, host.pixelLifts)
        movedBox(tool, 100f)
        assertTrue(tool.commitBox())
        assertEquals(1, pixels.commits)
        assertEquals(1, host.pixelLifts)
        assertNotSame(selection, tool.selection)
        assertEquals(100.0, (tool.selection as SelectionState.Pixels).loop.first().x, 0.001)
        val clip = host.paintClip!!
        assertEquals(0, clip.coverage(5, 5))
        assertEquals(255, clip.coverage(105, 5))
    }

    @Test fun refusedPixelLiftCannotTransformOrDeleteAndRetainsSelection() {
        val host = Host(pixels = null)
        val tool = SelectTool(host, 1f) { true }
        selectPaint(tool)
        val selection = tool.selection
        val box = tool.boxDoc!!.copyOf()
        assertFalse(tool.canTransform)
        tool.beginBox()
        val moved = box.copyOf().also { for (i in 0 until 4) it[i * 2] += 30f }
        assertFalse(tool.boxTo(moved))
        assertFalse(tool.deleteSelection())
        assertSame(selection, tool.selection)
        assertArrayEquals(box, tool.boxDoc!!, 0.001f)
    }

    @Test fun successivePixelMovesRebaseCoverageAndFillAtTheCurrentPosition() {
        val pixels = Pixels()
        val host = Host(pixels = pixels)
        val tool = SelectTool(host, 1f) { true }
        selectPaint(tool)

        movedBox(tool, 100f)
        assertTrue(tool.commitBox())
        movedBox(tool, 30f)
        assertTrue(tool.commitBox())

        assertEquals(2, pixels.commits)
        assertEquals(1, host.pixelLifts)
        val selection = tool.selection as SelectionState.Pixels
        assertEquals(130.0, selection.loop.first().x, 0.001)
        assertEquals(0, host.paintClip!!.coverage(5, 5))
        assertEquals(0, host.paintClip!!.coverage(105, 5))
        assertEquals(255, host.paintClip!!.coverage(135, 5))
        assertTrue(tool.fill(0xFF234567.toInt()))
        assertEquals(1, pixels.fills.size)
        assertEquals(0, pixels.fills.single().coverage(5, 5))
        assertEquals(255, pixels.fills.single().coverage(135, 5))
    }
}

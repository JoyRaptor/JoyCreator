package cc.joycreator.joybrush.android.select

import cc.joycreator.joybrush.androidkit.lines.LineEdit
import cc.joycreator.joybrush.androidkit.lines.LineEditResult
import cc.joycreator.joybrush.androidkit.lines.LineSnapshot
import cc.joycreator.joybrush.core.brush.BrushValidate
import cc.joycreator.joybrush.core.select.SelectionMask
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.vector.InkEditSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Select host over a stand-in canvas: what it reads, what it checks before sending, and what it shows meanwhile. */
class CanvasSelectionHostTest {

    @Test fun theFixtureBrushIsAValidBrush() {
        assertEquals(emptyList<String>(), BrushValidate.validate(frozenInk))
    }

    // ── reading ──

    @Test fun readsTheActiveAndTheVisibleLinesInDrawingOrderWithTheirAddresses() {
        val rig = Rig()
        assertEquals(listOf("a", "b"), rig.host.activeLines().map { it.id })
        assertEquals(listOf("a", "b", "c"), rig.host.visibleLines().map { it.id })
        val c = rig.host.visibleLines().last()
        assertEquals(planeQ.address("c"), c.address)
        assertEquals("L", c.layerId)
        assertEquals("cel-2", c.celId)
        assertEquals(planeP.address("a"), rig.host.activeLines().first().address)
    }

    @Test fun geometryIsTheOneTheSessionBuildsFromTheReplayedDabs() {
        val rig = Rig()
        val a = lineRecord("a", 1, y = 10f)
        val expected = InkEditSession(listOf(a), "x").lines { frozenInk }.single()
        val line = rig.host.activeLines().first { it.id == "a" }
        assertEquals(expected, line.geometry)
        assertEquals(a, line.record)
        assertTrue("a flat line replays to many dabs", line.geometry.xs.size > 20)
    }

    @Test fun lockedAndGroupedTravelWithTheLine() {
        val locked = lineRecord("a", 1).copy(locked = true, groupId = "g")
        val rig = Rig(mapOf(planeP to listOf(locked, lineRecord("b", 2))))
        val a = rig.host.activeLines().first { it.id == "a" }
        assertTrue(a.locked)
        assertEquals("g", a.groupId)
        val b = rig.host.activeLines().first { it.id == "b" }
        assertFalse(b.locked)
        assertNull(b.groupId)
    }

    @Test fun aLineWhoseBrushIsMissingIsSkippedAndLoggedOnce() {
        val rig = Rig(mapOf(planeP to listOf(lineRecord("a", 1), lineRecord("lost", 2, brushId = "nowhere"))))
        assertEquals(listOf("a"), rig.host.activeLines().map { it.id })
        assertEquals(1, rig.ui.logs.size)
        assertTrue(rig.ui.logs[0].contains("lost"))
        rig.host.activeLines()
        rig.host.visibleLines()
        rig.publish(mapOf(planeP to listOf(lineRecord("a", 1), lineRecord("lost", 2, brushId = "nowhere"))))
        rig.host.activeLines()
        assertEquals("the same refusal is cached by value, not logged again", 1, rig.ui.logs.size)
    }

    @Test fun repeatedReadsOfTheSameSnapshotAreTheSameList() {
        val rig = Rig()
        assertSame(rig.host.activeLines(), rig.host.activeLines())
        assertSame(rig.host.visibleLines(), rig.host.visibleLines())
    }

    @Test fun geometryIsCachedByRecordValueAcrossSnapshots() {
        val rig = Rig()
        val first = rig.host.activeLines().first { it.id == "a" }
        // A new snapshot holding equal records (new objects), as the engine publishes after any change.
        rig.publish(mapOf(
            planeP to listOf(lineRecord("a", 1, y = 10f), lineRecord("b", 2, y = 30f)),
            planeQ to listOf(lineRecord("c", 1, y = 50f)),
        ))
        val second = rig.host.activeLines().first { it.id == "a" }
        assertSame("no second replay for a record that did not change", first.geometry, second.geometry)
    }

    @Test fun aChangedRecordIsReplayedAgain() {
        val rig = Rig()
        val first = rig.host.activeLines().first { it.id == "a" }
        rig.publish(mapOf(planeP to listOf(shifted(lineRecord("a", 1, y = 10f), 5f), lineRecord("b", 2, y = 30f))))
        val second = rig.host.activeLines().first { it.id == "a" }
        assertEquals(first.geometry.xs[0] + 5.0, second.geometry.xs[0], 0.01)
    }

    @Test fun thePlanesTheCanvasNamesDecideWhichLinesAreActive() {
        val rig = Rig()
        rig.canvas.active = listOf(planeQ)
        assertEquals(listOf("c"), rig.host.activeLines().map { it.id })
        rig.canvas.active = listOf(planeP, planeP)
        assertEquals("a plane named twice is read once", listOf("a", "b"), rig.host.activeLines().map { it.id })
    }

    // ── checking before sending ──

    @Test fun replaceChecksEveryAddressBeforeSending() {
        val rig = Rig()
        val ghost = AddressedStroke(planeP.address("ghost"), lineRecord("ghost", 3))
        val a = AddressedStroke(planeP.address("a"), shifted(lineRecord("a", 1), 3f))
        assertFalse(rig.host.replaceLines(listOf(a, ghost)))
        assertTrue("nothing was sent, not even the good one", rig.canvas.submitted.isEmpty())
        assertEquals(listOf(CanvasSelectionHost.GONE), rig.ui.notes)
    }

    @Test fun replaceRefusesNothingToDoAndTheSameLineTwice() {
        val rig = Rig()
        assertFalse(rig.host.replaceLines(emptyList()))
        val a = AddressedStroke(planeP.address("a"), shifted(lineRecord("a", 1), 3f))
        assertFalse(rig.host.replaceLines(listOf(a, a)))
        assertTrue(rig.canvas.submitted.isEmpty())
    }

    @Test fun replaceRefusesALineWhoseBrushIsNotInTheDrawing() {
        val rig = Rig()
        val odd = AddressedStroke(planeP.address("a"), lineRecord("a", 1).copy(brushId = "nowhere"))
        assertFalse(rig.host.replaceLines(listOf(odd)))
        assertTrue(rig.canvas.submitted.isEmpty())
        assertEquals(1, rig.ui.notes.size)
    }

    @Test fun replaceSendsOneEditWithTheSameAddresses() {
        val rig = Rig()
        val a = AddressedStroke(planeP.address("a"), shifted(lineRecord("a", 1), 3f))
        val c = AddressedStroke(planeQ.address("c"), shifted(lineRecord("c", 1, y = 50f), 3f))
        assertTrue(rig.host.replaceLines(listOf(a, c)))
        assertEquals(1, rig.canvas.submitted.size)
        assertEquals(LineEdit.Replace(listOf(a, c), emptyMap()), rig.canvas.submitted[0].first)
        assertTrue(rig.host.editing)
    }

    @Test fun addNeedsVacantAddressesOnAPlaneTheCanvasKnows() {
        val rig = Rig()
        val taken = AddressedStroke(planeP.address("a"), lineRecord("a", 0))
        assertFalse("a is there already", rig.host.addLines(listOf(taken)))
        val elsewhere = AddressedStroke(cc.joycreator.joybrush.androidkit.lines.LinePlane("other", null).address("n"), lineRecord("n", 0))
        assertFalse("no such layer in view", rig.host.addLines(listOf(elsewhere)))
        assertFalse(rig.host.addLines(emptyList()))
        assertTrue(rig.canvas.submitted.isEmpty())
        val fresh = AddressedStroke(planeP.address("a2"), lineRecord("a2", 0, y = 70f))
        assertTrue(rig.host.addLines(listOf(fresh)))
        assertEquals(LineEdit.Add(listOf(fresh), emptyMap()), rig.canvas.submitted[0].first)
    }

    @Test fun deleteNeedsEveryLineToBeThere() {
        val rig = Rig()
        assertFalse(rig.host.deleteLines(emptySet()))
        assertFalse(rig.host.deleteLines(setOf(planeP.address("a"), planeP.address("ghost"))))
        assertTrue(rig.canvas.submitted.isEmpty())
        assertTrue(rig.host.deleteLines(setOf(planeP.address("a"), planeQ.address("c"))))
        assertEquals(LineEdit.Delete(setOf(planeP.address("a"), planeQ.address("c"))), rig.canvas.submitted[0].first)
    }

    // ── showing an edit before the engine answers ──

    @Test fun anEditShowsAtOnceAndDropsOffWhenTheEngineAnswers() {
        val rig = Rig()
        val before = rig.host.activeLines().first { it.id == "a" }
        val moved = shifted(before.record, 50f)
        assertTrue(rig.host.replaceLines(listOf(AddressedStroke(planeP.address("a"), moved))))

        val during = rig.host.activeLines().first { it.id == "a" }
        assertEquals("the line is already where it was sent", before.geometry.xs[0] + 50.0, during.geometry.xs[0], 0.01)
        assertEquals("its place in the drawing order is kept", 1L, during.record.seq)

        // The engine publishes its snapshot first, then answers (the canvas posts them in that order).
        rig.publish(mapOf(
            planeP to listOf(moved, lineRecord("b", 2, y = 30f)),
            planeQ to listOf(lineRecord("c", 1, y = 50f)),
        ))
        rig.canvas.answer(0, LineEditResult(true))
        assertFalse(rig.host.editing)
        val after = rig.host.activeLines().first { it.id == "a" }
        assertEquals(during.geometry, after.geometry)
        assertTrue(rig.ui.refusals.isEmpty())
    }

    @Test fun aRefusedEditDropsOffSaysWhyAndTellsTheScreen() {
        val rig = Rig()
        val before = rig.host.activeLines().first { it.id == "a" }
        assertTrue(rig.host.replaceLines(listOf(AddressedStroke(planeP.address("a"), shifted(before.record, 50f)))))
        rig.canvas.answer(0, LineEditResult(false, "That layer is locked."))
        assertEquals(listOf("That layer is locked."), rig.ui.refusals)
        assertTrue(rig.ui.notes.contains("That layer is locked."))
        val after = rig.host.activeLines().first { it.id == "a" }
        assertEquals("what the engine holds is what is shown", before.geometry, after.geometry)
    }

    @Test fun twoEditsInARowEachStartFromTheLastOne() {
        val rig = Rig()
        val a = rig.host.activeLines().first { it.id == "a" }.record
        val once = shifted(a, 10f)
        assertTrue(rig.host.replaceLines(listOf(AddressedStroke(planeP.address("a"), once))))
        val seen = rig.host.activeLines().first { it.id == "a" }.record
        assertEquals(once, seen.copy(seq = once.seq))
        val twice = shifted(seen, 10f)
        assertTrue(rig.host.replaceLines(listOf(AddressedStroke(planeP.address("a"), twice))))
        assertEquals(2, rig.canvas.submitted.size)
        assertEquals(a.samples[0].x + 20f, rig.host.activeLines().first { it.id == "a" }.record.samples[0].x, 0.001f)
        // The first answer arrives: the second edit is still shown on top.
        rig.publish(mapOf(planeP to listOf(once, lineRecord("b", 2, y = 30f)), planeQ to listOf(lineRecord("c", 1, y = 50f))))
        rig.canvas.answer(0, LineEditResult(true))
        assertTrue(rig.host.editing)
        assertEquals(a.samples[0].x + 20f, rig.host.activeLines().first { it.id == "a" }.record.samples[0].x, 0.001f)
    }

    @Test fun anAddedLineIsShownOnTopWithTheNextSeqAndGoesIfRefused() {
        val rig = Rig()
        val copy = AddressedStroke(planeP.address("a2"), lineRecord("a2", 0, y = 70f))
        assertTrue(rig.host.addLines(listOf(copy)))
        val shown = rig.host.activeLines()
        assertEquals(listOf("a", "b", "a2"), shown.map { it.id })
        assertEquals("one past the highest seq in its plane, as the engine assigns", 3L, shown.last().record.seq)
        assertEquals(planeP.address("a2"), shown.last().address)
        rig.canvas.answer(0, LineEditResult(false, "no room"))
        assertEquals(listOf("a", "b"), rig.host.activeLines().map { it.id })
    }

    @Test fun aDeletedLineIsGoneAtOnce() {
        val rig = Rig()
        assertTrue(rig.host.deleteLines(setOf(planeP.address("b"))))
        assertEquals(listOf("a"), rig.host.activeLines().map { it.id })
        assertEquals(listOf("a", "c"), rig.host.visibleLines().map { it.id })
    }

    @Test fun anAnswerThatComesBeforeTheCallReturnsIsAPlainRefusal() {
        val rig = Rig()
        rig.canvas.answerAtOnce = LineEditResult(false, "Lift the pen before editing lines")
        val a = AddressedStroke(planeP.address("a"), shifted(lineRecord("a", 1), 3f))
        assertFalse("the tool must see no, so it puts its box back itself", rig.host.replaceLines(listOf(a)))
        assertEquals(listOf("Lift the pen before editing lines"), rig.ui.notes)
        assertTrue("and the screen is not asked to re-read twice", rig.ui.refusals.isEmpty())
        assertFalse(rig.host.editing)
        assertEquals(10f, rig.host.activeLines().first { it.id == "a" }.record.samples[0].x, 0.001f)
    }

    @Test fun aQuickYesIsAYes() {
        val rig = Rig()
        rig.canvas.answerAtOnce = LineEditResult(true)
        assertTrue(rig.host.deleteLines(setOf(planeP.address("a"))))
        assertFalse(rig.host.editing)
    }

    // ── a re-brush's frozen copy ──

    @Test fun aNewBrushIsFrozenOnceAndRidesOnTheFirstEditThatNamesIt() {
        val rig = Rig()
        val marker = testBrush("library.marker", "Marker")
        val id = rig.host.freezeBrush(marker)
        assertEquals("lb-new", id)
        assertEquals("the same brush is the same copy", id, rig.host.freezeBrush(marker))
        assertNotNull(rig.host.brushOf(id))

        val a = rig.host.activeLines().first { it.id == "a" }.record
        val rebrushed = a.copy(brushId = id)
        assertTrue(rig.host.replaceLines(listOf(AddressedStroke(planeP.address("a"), rebrushed))))
        val edit = rig.canvas.submitted[0].first as LineEdit.Replace
        assertEquals(setOf("lb-new"), edit.brushes.keys)
        assertEquals("lb-new", edit.brushes.getValue("lb-new").id)

        // Once the snapshot holds the copy, edits no longer carry it.
        rig.publish(
            mapOf(planeP to listOf(rebrushed, lineRecord("b", 2, y = 30f)), planeQ to listOf(lineRecord("c", 1, y = 50f))),
            mapOf("lb-1" to frozenInk, "lb-new" to edit.brushes.getValue("lb-new")),
        )
        rig.canvas.answer(0, LineEditResult(true))
        val again = AddressedStroke(planeP.address("a"), shifted(rebrushed, 2f))
        assertTrue(rig.host.replaceLines(listOf(again)))
        assertEquals(emptyMap<String, Any>(), (rig.canvas.submitted[1].first as LineEdit.Replace).brushes)
    }

    @Test fun aBrushAlreadyInTheDrawingIsReusedNotCopied() {
        val rig = Rig()
        assertEquals("lb-1", rig.host.freezeBrush(testBrush("library.ink")))
        assertNull("a reused copy needs no staging", rig.host.brushOf("lb-new"))
    }

    // ── v1: nothing live ──

    @Test fun theLiveLiftsAndTheRestAreNotBackedYet() {
        val rig = Rig()
        assertNull(rig.host.liftLines(setOf(planeP.address("a"))))
        assertNull(rig.host.liftPixels(SelectionMask.polygon(listOf(
            cc.joycreator.joybrush.core.shape.Pt(0.0, 0.0), cc.joycreator.joybrush.core.shape.Pt(9.0, 0.0), cc.joycreator.joybrush.core.shape.Pt(9.0, 9.0))), false))
        assertFalse(rig.host.canAdjustColour)
        rig.host.dimRest(0.5f)
        rig.host.adjustColour(10f, 1f, 1f, true)
        assertTrue(rig.canvas.submitted.isEmpty())
        assertFalse(rig.host.bakeLines(setOf(planeP.address("a"))))
        assertEquals(listOf("Rasterize Down is coming next"), rig.ui.notes)
    }

    @Test fun thePaintClipIsKept() {
        val rig = Rig()
        val mask = SelectionMask.polygon(listOf(
            cc.joycreator.joybrush.core.shape.Pt(0.0, 0.0), cc.joycreator.joybrush.core.shape.Pt(9.0, 0.0), cc.joycreator.joybrush.core.shape.Pt(9.0, 9.0)))
        rig.host.paintClip = mask
        assertSame(mask, rig.host.paintClip)
        rig.host.paintClip = null
        assertNull(rig.host.paintClip)
    }

    @Test fun penButtonToastAndInvalidateGoToTheScreen() {
        val rig = Rig()
        rig.host.penButtonTap(12f, 34f)
        assertEquals(listOf(12f to 34f), rig.ui.penTaps)
        rig.host.toast("hello")
        assertEquals(listOf("hello"), rig.ui.notes)
        val redraws = rig.canvas.redraws
        val overlays = rig.ui.overlays
        rig.host.invalidate()
        assertEquals(redraws + 1, rig.canvas.redraws)
        assertEquals(overlays + 1, rig.ui.overlays)
    }

    @Test fun anEmptySnapshotHasNoLines() {
        val rig = Rig(emptyMap(), emptyMap())
        rig.canvas.snapshot = LineSnapshot.EMPTY
        assertEquals(emptyList<Any>(), rig.host.activeLines())
        assertEquals(emptyList<Any>(), rig.host.visibleLines())
    }
}

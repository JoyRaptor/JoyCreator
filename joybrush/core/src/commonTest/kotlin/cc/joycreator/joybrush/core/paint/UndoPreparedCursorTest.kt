package cc.joycreator.joybrush.core.paint

import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.CelComposer
import kotlin.test.*

/** Source-level cursor/publication contract; no GL renderer or engine activation. */
class UndoPreparedCursorTest {
    private fun step(before: Int?, after: Int?, key: Long = 0) =
        UndoLog.Step(listOf(UndoLog.TileChange("paint", key, before, after, "physical")))

    private fun mixed(before: Int, after: Int, slabBefore: Int, slabAfter: Int, key: Long = 0) =
        UndoLog.Step(listOf(UndoLog.TileChange("paint", key, before, after, "physical")), slabs = listOf(
            UndoLog.SlabChange("recorded", "physical", key, 7,
                UndoLog.SlabValue(slabBefore, "multiply"), UndoLog.SlabValue(slabAfter, "erase"))))

    private data class Root(val pixels: Int, val cursor: UndoLog.Cursor<Int>, val epoch: Int = 0)

    @Test fun newUndoRedoProposalsAreBorrowedValuesAndDoNotPublishOrRelease() {
        val released = mutableListOf<Int>()
        val log = UndoLog<Int>(100, { 1 }, released::add)
        val initial = log.snapshotCursor()
        val edit = mixed(1, 2, 3, 4)
        val made = log.prepareNew(edit)
        assertSame(initial, made.expectedCursor)
        assertSame(edit, made.step)
        assertSame(edit, made.cursor.peekUndo())
        assertEquals(UndoLog.Direction.NEW, made.direction)
        assertEquals(setOf(1, 3), made.cursor.heldTextures())
        assertSame(initial, log.snapshotCursor())
        assertFalse(log.canUndo)
        assertTrue(log.isCurrent(made))
        assertTrue(made.retirements.isEmpty())

        // Preparation may chain private values before an engine root is published.
        val undone = assertNotNull(log.prepareUndo(made.cursor))
        assertSame(edit, undone.step)
        assertEquals(UndoLog.Direction.UNDO, undone.direction)
        assertEquals(setOf(2, 4), undone.cursor.heldTextures())
        assertSame(edit, undone.cursor.peekRedo())
        assertFalse(log.isCurrent(undone))
        val redone = assertNotNull(log.prepareRedo(undone.cursor))
        assertSame(edit, redone.step)
        assertEquals(UndoLog.Direction.REDO, redone.direction)
        assertEquals(setOf(1, 3), redone.cursor.heldTextures())
        assertTrue(undone.retirements.isEmpty())
        assertTrue(redone.retirements.isEmpty())
        assertTrue(released.isEmpty()) // Discarding all three proposals changes no ownership.
    }

    @Test fun preparedStepPreservesPhysicalAddressesSequenceOperatorsAndMetadata() {
        var id = 0
        val beforeDoc = DocOps.newDocument("d", "before", 20, 20) { "id-${++id}" }
        val afterDoc = beforeDoc.copy(name = "after")
        val record = StrokeRecord("line", "frozen", 17, 0f, 1f,
            listOf(PenSample(1f, 2f, 0.0)), seq = 9)
        val edit = UndoLog.Step(listOf(UndoLog.TileChange("plain", 5, 1, 2, "")),
            documentBefore = beforeDoc, documentAfter = afterDoc,
            lines = listOf(UndoLog.LineChange("recorded", "cel", "line", null, CelComposer.Line(9, record))),
            slabs = listOf(UndoLog.SlabChange("recorded", null, 5, 7,
                UndoLog.SlabValue(3, "normal"), UndoLog.SlabValue(4, "behind"))))
        val log = UndoLog<Int>(1000, { 1 }, {})
        val made = log.prepareNew(edit)
        assertSame(edit, made.step)
        assertSame(edit, made.cursor.peekUndo())
        assertSame(beforeDoc, made.step.documentBefore)
        assertSame(afterDoc, made.step.documentAfter)
        assertEquals("", made.step.changes.single().celId)
        assertNull(made.step.slabs.single().celId)
        assertEquals(7L, made.step.slabs.single().seq)
        assertEquals("behind", made.step.slabs.single().after!!.op)
        assertEquals(9L, made.step.lines.single().after!!.seq)
        assertEquals(record, made.step.lines.single().after!!.record)
    }

    @Test fun branchRetirementIsRedoPopOrderThenOldestBudgetDropsAndTileBeforeSlab() {
        val released = mutableListOf<Int>()
        var size = 1L
        val log = UndoLog<Int>(100, { size }, released::add)
        val first = mixed(1, 2, 11, 12, 1)
        val second = mixed(3, 4, 13, 14, 2)
        val third = mixed(5, 6, 15, 16, 3)
        val fourth = mixed(7, 8, 17, 18, 4)
        listOf(first, second, third, fourth).forEach(log::push)
        log.undo(); log.undo()
        val old = log.snapshotCursor()
        size = 100 // Newest must survive alone, even over budget.
        val newest = mixed(9, 10, 19, 20, 5)
        val made = log.prepareNew(newest)
        assertEquals(listOf(6, 16, 8, 18, 1, 11, 3, 13), made.retirements)
        assertEquals(listOf(newest), made.cursor.undoSteps)
        assertFalse(made.cursor.canRedo)
        assertSame(old, log.snapshotCursor())
        assertEquals(setOf(1, 11, 3, 13, 6, 16, 8, 18), log.heldTextures())
        assertTrue(released.isEmpty())
    }

    @Test fun redoPreparationTrimsOldestBeforeAndKeepsNewestOverBudget() {
        val released = mutableListOf<Int>()
        var size = 1L
        val log = UndoLog<Int>(10, { size }, released::add)
        val first = mixed(1, 2, 11, 12, 1)
        val second = mixed(3, 4, 13, 14, 2)
        log.push(first); log.push(second); log.undo()
        val old = log.snapshotCursor()
        size = 10
        val made = assertNotNull(log.prepareRedo())
        assertEquals(listOf(1, 11), made.retirements)
        assertEquals(listOf(second), made.cursor.undoSteps)
        assertSame(second, made.step)
        assertSame(old, log.snapshotCursor())
        assertTrue(released.isEmpty())
    }

    @Test fun boundReadersObserveOneArtworkRootAssignmentAcrossNewUndoRedo() {
        val released = mutableListOf<Int>()
        val log = UndoLog<Int>(10, { 1 }, released::add)
        var root = Root(1, log.snapshotCursor())
        var assignments = 0
        log.bindCursor { root.cursor }
        fun publish(expected: Root, prepared: UndoLog.PreparedCursor<Int>, pixels: Int) {
            check(root === expected && log.isCurrent(prepared))
            root = Root(pixels, prepared.cursor, expected.epoch)
            assignments++
            prepared.retirements.forEach(released::add)
        }
        val edit = step(1, 2)
        publish(root, log.prepareNew(edit), 2)
        assertEquals(2, root.pixels)
        assertSame(root.cursor, log.snapshotCursor())
        assertSame(edit, log.peekUndo())
        assertEquals(setOf(1), log.heldTextures())
        assertEquals(1L, log.heldBytes)
        publish(root, assertNotNull(log.prepareUndo()), 1)
        assertEquals(1, root.pixels)
        assertFalse(log.canUndo)
        assertTrue(log.canRedo)
        assertSame(edit, log.peekRedo())
        assertEquals(setOf(2), log.heldTextures())
        assertNull(log.newestExtendable())
        publish(root, assertNotNull(log.prepareRedo()), 2)
        assertSame(edit, log.newestExtendable())
        assertEquals(3, assignments)
        assertTrue(released.isEmpty())
    }

    @Test fun staleCursorRejectsEvenWhenNewestStepIdentityMatchesAgain() {
        val log = UndoLog<Int>(10, { 1 }, {})
        val edit = step(1, 2)
        log.push(edit)
        val pending = assertNotNull(log.prepareUndo())
        log.undo(); log.redo()
        assertSame(edit, log.peekUndo())
        assertFalse(log.isCurrent(pending))
        assertNotSame(pending.expectedCursor, log.snapshotCursor())
    }

    @Test fun rootAndEpochChecksRemainNecessaryEvenWhenHistoryCursorIsCurrent() {
        val log = UndoLog<Int>(10, { 1 }, {})
        var root = Root(1, log.snapshotCursor())
        log.bindCursor { root.cursor }
        val expected = root
        val pending = log.prepareNew(step(1, 2))
        root = root.copy(pixels = 99, epoch = 1)
        assertTrue(log.isCurrent(pending))
        assertNotSame(expected, root)
        assertNotEquals(expected.epoch, root.epoch)
        assertFalse(root === expected && log.isCurrent(pending))
        assertFalse(log.canUndo)
    }

    @Test fun allLegacyMutatorsRefuseWhileBoundBeforeEvenNoOpCallbacksOrBudget() {
        val released = mutableListOf<Int>()
        var budgetCalls = 0
        var callbackCalls = 0
        val log = UndoLog<Int>(10, { budgetCalls++; 1 }, released::add)
        val edit = step(1, 2)
        log.push(edit)
        val original = log.snapshotCursor()
        log.bindCursor { original }
        val attempts: List<() -> Unit> = listOf(
            { log.push(step(2, 3)) },
            { log.pushAndPublish(step(2, 3)) { callbackCalls++ } },
            { log.undo() },
            { log.undoAndPublish(edit) { callbackCalls++ } },
            { log.redo() },
            { log.redoAndPublish(edit) { callbackCalls++ } },
            { log.mergeNewest(0) },
            { log.extendNewest(emptyList()) { callbackCalls++; true } },
            { log.replaceInNewest(emptyList()) },
            { log.clear() },
        )
        val beforeBudget = budgetCalls
        attempts.forEach { assertFailsWith<IllegalStateException>(block = it) }
        assertEquals(beforeBudget, budgetCalls)
        assertEquals(0, callbackCalls)
        assertTrue(released.isEmpty())
        assertSame(original, log.snapshotCursor())
        assertSame(edit, log.peekUndo())
    }

    @Test fun bindingChecksLocalIdentityAgainAfterAnExternalCallbackMutatesHistory() {
        val log = UndoLog<Int>(10, { 1 }, {})
        val original = log.snapshotCursor()
        val edit = step(1, 2)
        assertFailsWith<IllegalStateException> {
            log.bindCursor {
                log.push(edit)
                original
            }
        }
        assertFalse(log.isCursorBound)
        assertSame(edit, log.peekUndo())
        assertNotSame(original, log.snapshotCursor())
        val current = log.snapshotCursor()
        log.bindCursor { current }
        assertTrue(log.isCursorBound)
    }

    @Test fun bindingRejectsAStagedButNotCurrentCursorAndDoesNotTakeItsHandles() {
        val released = mutableListOf<Int>()
        val log = UndoLog<Int>(10, { 1 }, released::add)
        val original = log.snapshotCursor()
        val staged = log.prepareNew(step(1, 2))
        assertFailsWith<IllegalStateException> { log.bindCursor { staged.cursor } }
        assertFalse(log.isCursorBound)
        assertSame(original, log.snapshotCursor())
        assertTrue(released.isEmpty())
    }

    @Test fun sourceLogIdentityIsStrictForBindingPreparationAndCurrentChecks() {
        val released = mutableListOf<Int>()
        val a = UndoLog<Int>(10, { 1 }, released::add)
        val b = UndoLog<Int>(10, { 1 }, released::add)
        val foreign = b.snapshotCursor()
        assertFailsWith<IllegalArgumentException> { a.bindCursor { foreign } }
        assertFailsWith<IllegalArgumentException> { a.prepareNew(step(1, 2), foreign) }
        assertFailsWith<IllegalArgumentException> { a.prepareUndo(foreign) }
        assertFailsWith<IllegalArgumentException> { a.prepareRedo(foreign) }
        assertFalse(a.isCurrent(b.prepareNew(step(1, 2))))
        assertFalse(a.isCursorBound)
        assertTrue(released.isEmpty())
    }

    @Test fun secondBindRefusesBeforeInvokingTheReplacementAccessor() {
        val log = UndoLog<Int>(10, { 1 }, {})
        var root = Root(1, log.snapshotCursor())
        log.bindCursor { root.cursor }
        var called = false
        assertFailsWith<IllegalStateException> { log.bindCursor { called = true; root.cursor } }
        assertFalse(called)
        root = Root(2, log.prepareNew(step(1, 2)).cursor)
        assertEquals(1, log.undoDepth)
    }

    @Test fun boundAccessorReturningForeignCursorRefusesWithoutRelease() {
        val released = mutableListOf<Int>()
        val a = UndoLog<Int>(10, { 1 }, released::add)
        val b = UndoLog<Int>(10, { 1 }, released::add)
        var exposed = a.snapshotCursor()
        a.bindCursor { exposed }
        exposed = b.snapshotCursor()
        assertFailsWith<IllegalArgumentException> { a.snapshotCursor() }
        assertFailsWith<IllegalArgumentException> { a.prepareNew(step(1, 2)) }
        assertFailsWith<IllegalArgumentException> { a.heldTextures() }
        assertTrue(released.isEmpty())
    }

    @Test fun emptyCursorIsAStagedResetValueAndDoesNotClearOrRetireAnything() {
        val released = mutableListOf<Int>()
        val log = UndoLog<Int>(10, { 1 }, released::add)
        log.push(step(1, 2))
        val original = log.snapshotCursor()
        val empty = log.emptyCursor()
        assertFalse(empty.canUndo)
        assertFalse(empty.canRedo)
        assertTrue(empty.heldTextures().isEmpty())
        assertSame(original, log.snapshotCursor())
        assertEquals(setOf(1), log.heldTextures())
        assertTrue(released.isEmpty())
    }

    @Test fun returnedStackAndRetirementListsCannotMutateCursorBacking() {
        val log = UndoLog<Int>(10, { 1 }, {})
        val first = step(1, 2, 1)
        val second = step(3, 4, 2)
        log.push(first); log.push(second)
        val original = log.snapshotCursor()
        (original.undoSteps as MutableList<UndoLog.Step<Int>>).clear()
        assertEquals(listOf(first, second), original.undoSteps)
        log.undo(); log.undo()
        val redo = log.snapshotCursor()
        (redo.redoSteps as MutableList<UndoLog.Step<Int>>).clear()
        assertEquals(listOf(second, first), redo.redoSteps)
        val made = log.prepareNew(step(null, 5))
        (made.retirements as MutableList<Int>).clear()
        assertEquals(listOf(2, 4), made.retirements)
    }

    @Test fun equivalentStepRefusesInPureUndoAndRedoPreparation() {
        val log = UndoLog<Int>(10, { 1 }, {})
        val edit = step(1, 2)
        log.push(edit)
        val original = log.snapshotCursor()
        assertNull(log.prepareUndo(expectedStep = step(1, 2)))
        assertSame(original, log.snapshotCursor())
        log.undo()
        val undone = log.snapshotCursor()
        assertNull(log.prepareRedo(expectedStep = step(1, 2)))
        assertSame(undone, log.snapshotCursor())
    }

    @Test fun budgetFailureInBoundPreparationPreservesRootRedoAndOwnership() {
        val released = mutableListOf<Int>()
        var fail = false
        val log = UndoLog<Int>(10, { if (fail) error("budget") else 1 }, released::add)
        log.push(step(1, 2, 1)); log.push(step(3, 4, 2)); log.undo()
        var root = Root(1, log.snapshotCursor())
        val original = root
        log.bindCursor { root.cursor }
        fail = true
        assertFailsWith<IllegalStateException> { log.prepareNew(step(5, 6, 3)) }
        assertFailsWith<IllegalStateException> { log.prepareRedo() }
        assertSame(original, root)
        assertSame(original.cursor, log.snapshotCursor())
        assertTrue(log.canRedo)
        assertTrue(released.isEmpty())
    }

    @Test fun budgetCallbackReentryMakesUnboundPushStaleBeforePublicationOrRetirement() {
        val released = mutableListOf<Int>()
        var reenter = false
        lateinit var log: UndoLog<Int>
        log = UndoLog(10, {
            if (reenter) {
                reenter = false
                log.undo()
            }
            1
        }, released::add)
        val first = step(1, 2)
        log.push(first)
        reenter = true
        var published = false
        assertFailsWith<IllegalStateException> { log.pushAndPublish(step(2, 3)) { published = true } }
        assertFalse(published)
        assertSame(first, log.peekRedo())
        assertNull(log.peekUndo())
        assertTrue(released.isEmpty())
    }

    @Test fun publicationAndReleaseCallbacksCannotReenterUnboundMutators() {
        val rejected = mutableListOf<String>()
        lateinit var log: UndoLog<Int>
        log = UndoLog(1, { 1 }, {
            assertFailsWith<IllegalStateException> { log.clear() }
            rejected += "release:$it"
        })
        log.push(step(1, 2))
        log.pushAndPublish(step(2, 3)) {
            assertFailsWith<IllegalStateException> { log.undo() }
            rejected += "publish"
        }
        assertEquals(listOf("publish", "release:1"), rejected)
        assertEquals(1, log.undoDepth)
        assertEquals(2, log.peekUndo()!!.changes.single().before)
    }

    @Test fun failedUnboundMergeDoesNotReleaseIntermediatesOrRemoveOldSteps() {
        val released = mutableListOf<Int>()
        val log = UndoLog<Int>(100, { 1 }, released::add)
        log.push(step(1, 2, 0))
        // Valid per-step endpoints, but folding would duplicate the oldest before owner.
        log.push(UndoLog.Step(listOf(UndoLog.TileChange("paint", 0, 2, 3, "physical"),
            UndoLog.TileChange("paint", 1, 1, 4, "physical"))))
        val original = log.snapshotCursor()
        assertFailsWith<IllegalArgumentException> { log.mergeNewest(2) }
        assertSame(original, log.snapshotCursor())
        assertEquals(2, log.undoDepth)
        assertTrue(released.isEmpty())
    }

    @Test fun mergeAndExtensionBudgetFailuresKeepOldCursorAndItsOwnership() {
        val released = mutableListOf<Int>()
        var fail = false
        val log = UndoLog<Int>(100, { if (fail) error("budget") else 1 }, released::add)
        log.push(step(1, 2, 0)); log.push(step(3, 4, 1)); log.push(step(4, 5, 1))
        val original = log.snapshotCursor()
        fail = true
        assertFailsWith<IllegalStateException> { log.mergeNewest(2) }
        assertSame(original, log.snapshotCursor())
        assertFailsWith<IllegalStateException> {
            log.extendNewest(listOf(UndoLog.TileChange("paint", 2, 6, 7, "physical"))) { true }
        }
        assertSame(original, log.snapshotCursor())
        assertTrue(released.isEmpty())
    }

    @Test fun successfulMergeExtensionReplacementPreserveOldSnapshotsAndReleaseOrder() {
        val released = mutableListOf<Int>()
        val log = UndoLog<Int>(100, { 1 }, released::add)
        val first = mixed(1, 2, 11, 12)
        val second = mixed(2, 3, 12, 13)
        log.push(first); log.push(second)
        val old = log.snapshotCursor()
        log.mergeNewest(2)
        assertEquals(listOf(first, second), old.undoSteps)
        assertEquals(listOf(2, 12), released)
        val merged = log.snapshotCursor()
        assertTrue(log.extendNewest(listOf(UndoLog.TileChange("paint", 1, null, 20, "physical"))) { true })
        assertEquals(1, merged.peekUndo()!!.changes.size)
        val extended = log.snapshotCursor()
        assertTrue(log.replaceInNewest(listOf(UndoLog.TileChange("paint", 1, null, null, "physical"))))
        assertEquals(2, extended.peekUndo()!!.changes.size)
        assertEquals(1, log.peekUndo()!!.changes.size)
        assertEquals("multiply", log.peekUndo()!!.slabs.single().before!!.op)
        assertEquals("erase", log.peekUndo()!!.slabs.single().after!!.op)
        // Replacement's after is caller-owned: history must not release 20 here.
        assertEquals(listOf(2, 12), released)
        log.clear()
        assertEquals(listOf(2, 12, 1, 11), released)
    }

    @Test fun boundRetirementsCanBeEpochCheckedIndividuallyAfterJointPublication() {
        val released = mutableListOf<Int>()
        val log = UndoLog<Int>(100, { 1 }, { error("bound log must never release") })
        log.push(mixed(1, 2, 3, 4)); log.undo()
        var root = Root(1, log.snapshotCursor(), epoch = 7)
        log.bindCursor { root.cursor }
        val expected = root
        val prepared = log.prepareNew(step(1, 5))
        check(root === expected && log.isCurrent(prepared))
        root = Root(5, prepared.cursor, expected.epoch)
        for (texture in prepared.retirements) {
            if (root.epoch != expected.epoch) break
            released += texture
            root = root.copy(epoch = 8) // Reset during first actual renderer release.
        }
        assertEquals(listOf(2), released)
        assertEquals(listOf(2, 4), prepared.retirements)
        assertSame(prepared.cursor, log.snapshotCursor())
        assertEquals(setOf(1), log.heldTextures())
    }
}

package cc.joycreator.joybrush.core.paint

import kotlin.test.*

class UndoResetHandoffTest {
    private data class Root(override val cursor: UndoLog.Cursor<Int>, override val graphicsEpoch: Long = 7,
                            val marker: Int = 0) : UndoLog.CursorPublication<Int>
    private fun mixed(before: Int, after: Int, slabBefore: Int, slabAfter: Int, key: Long) =
        UndoLog.Step(listOf(UndoLog.TileChange("native", key, before, after)), slabs = listOf(
            UndoLog.SlabChange("recorded", "cel", key, 2, UndoLog.SlabValue(slabBefore, "normal"),
                UndoLog.SlabValue(slabAfter, "erase"))))

    @Test fun purePreflightExportsOldClearOwnershipAndExactPublicationHandsOffWithoutCallbacks() {
        val released = mutableListOf<Int>()
        var sizeCalls = 0
        val log = UndoLog<Int>(100, { sizeCalls++; 1 }, released::add)
        log.push(mixed(1, 2, 3, 4, 0)); log.push(mixed(5, 6, 7, 8, 1)); log.undo()
        var root = Root(log.snapshotCursor())
        var reads = 0
        var rejectReads = false
        log.bindCursor { reads++; check(!rejectReads); root.cursor }
        val old = root
        val next = Root(log.emptyCursor(), 8)
        val pendingNew = log.prepareNew(UndoLog.Step(listOf(UndoLog.TileChange("new", 0, null, 9))))
        val ticket = log.prepareResetHandoff(old, next) { reads++; check(!rejectReads); root }
        assertSame(old.cursor, log.snapshotCursor())
        assertEquals(listOf(1, 3, 6, 8), ticket.retiredHistoryTextures)
        assertTrue(ticket.isCurrent())
        val beforeSize = sizeCalls
        val beforeReads = reads
        root = next // Actual owner publication occurs exactly once outside the log.
        rejectReads = true
        ticket.acknowledgeAfterResetPublication(root)
        assertEquals(beforeReads, reads)
        assertEquals(beforeSize, sizeCalls)
        assertTrue(released.isEmpty())
        assertFalse(log.isCursorBound)
        assertSame(next.cursor, log.snapshotCursor())
        assertFalse(log.canUndo); assertFalse(log.canRedo)
        assertTrue(log.heldTextures().isEmpty())
        assertFalse(log.isCurrent(pendingNew))
        // Old ownership belongs to caller now; the local empty log cannot release it twice.
        log.clear()
        assertTrue(released.isEmpty())
        ticket.retiredHistoryTextures.forEach(released::add)
        log.push(UndoLog.Step(listOf(UndoLog.TileChange("new", 0, 10, 11))))
        log.clear()
        assertEquals(listOf(1, 3, 6, 8, 10), released)
    }

    @Test fun foreignExpectedOrResetCursorsRefuseBeforeReadingTheRoot() {
        val a = UndoLog<Int>(100, { 1 }, {})
        val b = UndoLog<Int>(100, { 1 }, {})
        val old = Root(a.snapshotCursor())
        a.bindCursor { old.cursor }
        var reads = 0
        assertFailsWith<IllegalArgumentException> {
            a.prepareResetHandoff(old, Root(b.emptyCursor(), 8)) { reads++; old }
        }
        assertFailsWith<IllegalArgumentException> {
            a.prepareResetHandoff(Root(b.snapshotCursor()), Root(a.emptyCursor(), 8)) { reads++; old }
        }
        assertEquals(0, reads)
        assertTrue(a.isCursorBound)
    }

    @Test fun resourceFreeButNonemptyHistoryIsNotAnEmptyResetCursor() {
        val log = UndoLog<Int>(100, { 1 }, {})
        val old = Root(log.snapshotCursor())
        log.bindCursor { old.cursor }
        val nonempty = log.prepareNew(UndoLog.Step<Int>(emptyList())).cursor
        assertTrue(nonempty.heldTextures().isEmpty())
        var reads = 0
        assertFailsWith<IllegalArgumentException> {
            log.prepareResetHandoff(old, Root(nonempty, 8)) { reads++; old }
        }
        assertEquals(0, reads)
        assertSame(old.cursor, log.snapshotCursor())
    }

    @Test fun resetNeedsDistinctCursorAndRootAndStrictlyAdvancingEpochWithoutWrap() {
        val log = UndoLog<Int>(100, { 1 }, {})
        val old = Root(log.snapshotCursor(), Long.MAX_VALUE)
        log.bindCursor { old.cursor }
        var reads = 0
        for (next in listOf(old, Root(old.cursor, Long.MAX_VALUE), Root(log.emptyCursor(), Long.MAX_VALUE),
            Root(log.emptyCursor(), Long.MIN_VALUE))) {
            assertFailsWith<IllegalArgumentException> { log.prepareResetHandoff(old, next) { reads++; old } }
        }
        assertEquals(0, reads)
    }

    @Test fun wrongActualRootOrCursorDuringPreflightRefusesAndReleasesNothing() {
        val released = mutableListOf<Int>()
        val log = UndoLog<Int>(100, { 1 }, released::add)
        log.push(UndoLog.Step(listOf(UndoLog.TileChange("n", 0, 1, 2))))
        var root = Root(log.snapshotCursor())
        log.bindCursor { root.cursor }
        val old = root
        root = root.copy(marker = 1)
        assertFailsWith<IllegalStateException> {
            log.prepareResetHandoff(old, Root(log.emptyCursor(), 8)) { root }
        }
        assertTrue(released.isEmpty())
        assertSame(root.cursor, log.snapshotCursor())
    }

    @Test fun preflightReaderFailureUnwindsAndRetryCanAcknowledge() {
        val log = UndoLog<Int>(100, { 1 }, {})
        var root = Root(log.snapshotCursor())
        log.bindCursor { root.cursor }
        val next = Root(log.emptyCursor(), 8)
        assertFailsWith<IllegalStateException> { log.prepareResetHandoff(root, next) { error("reader failed") } }
        val ticket = log.prepareResetHandoff(root, next) { root }
        assertTrue(ticket.isCurrent())
        root = next
        ticket.acknowledgeAfterResetPublication(root)
        log.checkCursorBindingAllowed()
    }

    @Test fun externalReaderRootChangeAfterAnswerMakesTicketStaleWithoutHandoff() {
        val log = UndoLog<Int>(100, { 1 }, {})
        var root = Root(log.snapshotCursor())
        log.bindCursor { root.cursor }
        val next = Root(log.emptyCursor(), 8)
        var mutate = false
        val ticket = log.prepareResetHandoff(root, next) {
            val answered = root
            if (mutate) { mutate = false; root = root.copy(marker = 1) }
            answered
        }
        mutate = true
        assertFalse(ticket.isCurrent())
        assertTrue(log.isCursorBound)
        assertSame(root.cursor, log.snapshotCursor())
    }

    @Test fun acknowledgementRequiresTheExactPrevalidatedObjectAndIsOneShot() {
        val log = UndoLog<Int>(100, { 1 }, {})
        var root = Root(log.snapshotCursor())
        log.bindCursor { root.cursor }
        val next = Root(log.emptyCursor(), 8)
        val ticket = log.prepareResetHandoff(root, next) { root }
        assertFailsWith<IllegalStateException> { ticket.acknowledgeAfterResetPublication(next.copy()) }
        assertTrue(log.isCursorBound)
        root = next
        ticket.acknowledgeAfterResetPublication(root)
        assertFailsWith<IllegalStateException> { ticket.acknowledgeAfterResetPublication(root) }
        assertFalse(ticket.isCurrent())
        assertSame(next.cursor, log.snapshotCursor())
    }

    @Test fun unboundBindingAndPublishingEntrancesRefuseBeforeAnyRootRead() {
        val log = UndoLog<Int>(100, { 1 }, {})
        val old = Root(log.snapshotCursor())
        val next = Root(log.emptyCursor(), 8)
        var reads = 0
        assertFailsWith<IllegalStateException> { log.prepareResetHandoff(old, next) { reads++; old } }
        log.pushAndPublish(UndoLog.Step<Int>(emptyList())) {
            assertFailsWith<IllegalStateException> { log.prepareResetHandoff(old, next) { reads++; old } }
        }
        val current = Root(log.snapshotCursor())
        log.bindCursor {
            assertFailsWith<IllegalStateException> { log.prepareResetHandoff(current, next) { reads++; current } }
            current.cursor
        }
        assertEquals(0, reads)
    }

    @Test fun returnedRetirementBackingDoesNotChangeThePreparedOwnershipLedger() {
        val log = UndoLog<Int>(100, { 1 }, {})
        log.push(mixed(1, 2, 3, 4, 0))
        val root = Root(log.snapshotCursor())
        log.bindCursor { root.cursor }
        val ticket = log.prepareResetHandoff(root, Root(log.emptyCursor(), 8)) { root }
        (ticket.retiredHistoryTextures as MutableList<Int>).clear()
        assertEquals(listOf(1, 3), ticket.retiredHistoryTextures)
        assertTrue(ticket.isCurrent())
    }
}

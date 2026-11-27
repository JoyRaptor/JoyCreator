package cc.joycreator.joybrush.androidkit.gl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordedWorkingTileLedgerTest {
    @Test fun unsignedGlNamesRetainTheirIntBitPatterns() {
        val ledger = RecordedWorkingTileLedger(9)
        ledger.register(9, listOf(Int.MIN_VALUE, -1), 16)
        assertEquals(40L, ledger.admit(9, mapOf(Int.MIN_VALUE to 24L), 0, 40))
        assertTrue(ledger.forget(9, -1))
        assertEquals(mapOf(Int.MIN_VALUE to 16L), ledger.snapshot(9))
    }

    @Test fun staleEpochCannotForgetAReusedNameAndCannotReadOrAdmit() {
        val ledger = RecordedWorkingTileLedger(10)
        ledger.register(10, listOf(7), 40)
        ledger.reset(11)
        ledger.register(11, listOf(7), 80)

        assertFalse(ledger.forget(10, 7))
        assertEquals(mapOf(7 to 80L), ledger.snapshot(11))
        assertTrue(ledger.forget(11, 7))
        assertFalse(ledger.forget(11, 7))
        ledger.register(11, listOf(7), 80)
        assertFailsWith<IllegalStateException> { ledger.snapshot(10) }
        assertFailsWith<IllegalStateException> { ledger.register(10, listOf(8), 1) }
        assertFailsWith<IllegalStateException> { ledger.admit(10, emptyMap(), 0, 100) }
    }

    @Test fun registerIsAtomicForInvalidOrAlreadyTrackedBatches() {
        val ledger = RecordedWorkingTileLedger(1)
        ledger.register(1, listOf(2), 16)

        assertFailsWith<IllegalArgumentException> { ledger.register(1, listOf(3, 0, 4), 8) }
        assertFailsWith<IllegalArgumentException> { ledger.register(1, listOf(3, 3), 8) }
        assertFailsWith<IllegalArgumentException> { ledger.register(1, listOf(3, 2), 8) }
        assertFailsWith<IllegalArgumentException> { ledger.register(1, listOf(5), 0) }
        assertEquals(mapOf(2 to 16L), ledger.snapshot(1))
    }

    @Test fun snapshotIsDetachedAndUnmodifiable() {
        val ledger = RecordedWorkingTileLedger(4)
        ledger.register(4, listOf(9), 32)
        val snapshot = ledger.snapshot(4)

        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (snapshot as MutableMap<Int, Long>)[10] = 64
        }
        ledger.register(4, listOf(11), 16)
        assertEquals(mapOf(9 to 32L), snapshot)
        assertEquals(mapOf(9 to 32L, 11 to 16L), ledger.snapshot(4))
    }

    @Test fun admissionDeduplicatesByNameUsingConservativeMaximum() {
        val ledger = RecordedWorkingTileLedger(2)
        ledger.register(2, listOf(5, 6), 40)
        val owners = mapOf(5 to 80L, 7 to 24L)

        // 5 is charged max(80,40), 6 is 40, 7 is 24, then 10 additional.
        assertEquals(154L, ledger.admit(2, owners, additionalBytes = 10, budgetBytes = 154))
        // The staged size wins when the actual owner reports a smaller format estimate.
        assertEquals(114L, ledger.admit(2, mapOf(5 to 20L, 7 to 24L), 10, 114))
        assertFailsWith<IllegalArgumentException> {
            ledger.admit(2, owners, additionalBytes = 10, budgetBytes = 153)
        }
        assertEquals(owners, mapOf(5 to 80L, 7 to 24L))
        assertEquals(mapOf(5 to 40L, 6 to 40L), ledger.snapshot(2))
    }

    @Test fun admissionRejectsInvalidInputsAndCheckedOverflow() {
        val ledger = RecordedWorkingTileLedger(3)
        assertFailsWith<IllegalArgumentException> { ledger.admit(3, mapOf(0 to 1), 0, 10) }
        assertFailsWith<IllegalArgumentException> { ledger.admit(3, mapOf(1 to 0), 0, 10) }
        assertFailsWith<IllegalArgumentException> { ledger.admit(3, emptyMap(), -1, 10) }
        assertFailsWith<IllegalArgumentException> { ledger.admit(3, emptyMap(), 0, -1) }
        assertFailsWith<IllegalStateException> {
            ledger.admit(3, mapOf(1 to Long.MAX_VALUE, 2 to 1L), 0, Long.MAX_VALUE)
        }
        assertFailsWith<IllegalStateException> {
            ledger.admit(3, mapOf(1 to Long.MAX_VALUE), 1, Long.MAX_VALUE)
        }
        assertTrue(ledger.snapshot(3).isEmpty())
    }
}

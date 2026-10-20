package cc.joycreator.joybrush.androidkit.gl

import kotlin.test.*

class StampColorTargetsTest {
    @Test fun firstNonemptyBatchLatchesFormatUntilEndOrContextLoss() {
        val deleted = ArrayList<List<Int>>()
        val targets = StampColorTargets(16) { deleted.add(it.toList()) }
        targets.latch(false); targets.latch(false)
        assertFailsWith<IllegalStateException> { targets.latch(true) }
        targets.prepare(listOf(1L), 4, 0, { error("disabled must not allocate") }, {})
        assertNull(targets.textureFor(1L))
        targets.clear(); targets.latch(true)
        targets.prepare(listOf(1L, 1L, 2L), 4, 2048, generateSequence(11) { it + 1 }.iterator()::next, {})
        assertEquals(setOf(11, 12), targets.heldTextureHandles())
        assertEquals(mapOf(11 to 1024L, 12 to 1024L), targets.heldWorkingTileBytes())
        assertEquals(11, targets.textureFor(1L))
        assertFailsWith<IllegalStateException> { targets.latch(false) }
        targets.clear(); targets.clear()
        assertEquals(listOf(listOf(11,12)), deleted)
        targets.latch(false)
    }

    @Test fun batchAllocationIsTransactionalAndKeepsPreviousTilesOnFailure() {
        val deleted = ArrayList<List<Int>>()
        val targets = StampColorTargets(8) { deleted.add(it.toList()) }
        targets.latch(true)
        targets.prepare(listOf(1L), 8, 512, { 7 }, {})
        var next = 20
        assertFailsWith<IllegalArgumentException> {
            targets.prepare(listOf(2L,3L), 8, 1536, { next++ }) { if (it == 21) throw IllegalArgumentException("init") }
        }
        assertEquals(setOf(7), targets.heldTextureHandles())
        assertEquals(listOf(listOf(20,21)), deleted)
        next = 30
        assertFailsWith<IllegalStateException> {
            targets.prepare(listOf(2L,3L), 8, 1536, { if (next == 30) next++ else error("allocation") }, {})
        }
        assertEquals(listOf(30), deleted.last())
        assertEquals(mapOf(7 to 512L), targets.heldWorkingTileBytes())
    }

    @Test fun budgetAndPrecisionRefusalsPrecedeAllocationAndNeverTruncate() {
        val targets = StampColorTargets(16) { error("no deletion expected") }
        targets.latch(true)
        var allocations = 0
        val allocate = { ++allocations }
        assertFailsWith<IllegalArgumentException> { targets.prepare(listOf(1L,2L),4,2047,allocate,{}) }
        assertEquals(0, allocations)
        targets.prepare(listOf(1L,2L),4,2048,allocate,{})
        assertEquals(2, allocations)
        targets.prepare(listOf(1L,1L),4,2048,allocate,{})
        assertEquals(2, allocations)
        assertFailsWith<IllegalStateException> { targets.prepare(listOf(1L),8,4096,allocate,{}) }
        assertFailsWith<IllegalArgumentException> { targets.prepare(listOf(3L),4,-1,allocate,{}) }
        assertEquals(2, allocations)
        val huge = StampColorTargets(Int.MAX_VALUE) {}
        huge.latch(true)
        assertFailsWith<ArithmeticException> { huge.prepare(listOf(1L),8,Long.MAX_VALUE,allocate,{}) }
    }

    @Test fun aliasedOrZeroNamesAreRejectedAndDeadContextNamesAreAbandoned() {
        val deleted = ArrayList<List<Int>>()
        val targets = StampColorTargets(8) { deleted.add(it.toList()) }
        targets.latch(true)
        assertFailsWith<IllegalStateException> { targets.prepare(listOf(1L),4,256,{0},{}) }
        assertTrue(deleted.isEmpty())
        assertFailsWith<IllegalStateException> { targets.prepare(listOf(1L,2L),4,512,{4},{}) }
        assertEquals(listOf(listOf(4)), deleted)
        targets.prepare(listOf(1L),4,256,{9},{})
        assertFailsWith<IllegalStateException> { targets.prepare(listOf(2L),4,512,{9},{}) }
        assertEquals(setOf(9), targets.heldTextureHandles())
        targets.abandonContext(); targets.clear()
        assertEquals(listOf(listOf(4)), deleted)
        assertEquals(emptyMap(), targets.heldWorkingTileBytes())
        targets.latch(false)
    }
}

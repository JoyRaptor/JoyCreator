package cc.joycreator.joybrush.core.media

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Media Atlas v2 B3 step 6: a frame's dabs split in time, in order, each part with its own box. */
class DabBatchSliceTest {
    @Test fun aSliceKeepsTheDabsInOrderWithTheirOwnBox() {
        val c = DabCollector()
        for (i in 0 until 5) c.push(DoubleArray(DabBatch.FLOATS) { k -> i * 100.0 + k }, doubleArrayOf(i * 10.0, 0.0, i * 10.0 + 5, 5.0 + i))
        val all = c.take()
        assertContentEquals(doubleArrayOf(0.0, 0.0, 45.0, 9.0), all.dirty)
        val a = all.slice(0, 2)!!; val b = all.slice(2, 5)!!
        assertEquals(2, a.count); assertEquals(3, b.count)
        assertContentEquals(doubleArrayOf(0.0, 0.0, 15.0, 6.0), a.dirty)
        assertContentEquals(doubleArrayOf(20.0, 0.0, 45.0, 9.0), b.dirty)
        assertContentEquals(all.dabs.copyOfRange(2 * DabBatch.FLOATS, 5 * DabBatch.FLOATS), b.dabs)
        assertEquals(200f, b.dabs[0])
    }

    @Test fun withoutBoxesThereIsNoSlice() {
        assertNull(DabBatch(FloatArray(DabBatch.FLOATS), 1, doubleArrayOf(0.0, 0.0, 1.0, 1.0)).slice(0, 1))
    }
}

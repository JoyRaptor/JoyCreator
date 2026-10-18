package cc.joycreator.joybrush.android.chrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The memory the drawer keeps its pictures in: a byte budget, the least recently used going first. */
class PreviewLruTest {

    private fun lru(max: Long) = PreviewLru<String, String>(max) { it.length.toLong() }

    @Test fun valuesAreKeptWhileTheyFit() {
        val c = lru(10)
        c.put("a", "1234")
        c.put("b", "1234")
        assertEquals(8L, c.totalBytes)
        assertEquals("1234", c["a"])
        assertEquals(2, c.size)
    }

    @Test fun theOldestGoesFirstWhenTheBudgetIsPassed() {
        val c = lru(10)
        c.put("a", "1234")
        c.put("b", "1234")
        c.put("c", "1234")
        assertNull(c["a"])
        assertEquals(listOf("b", "c"), c.keys())
        assertEquals(8L, c.totalBytes)
    }

    @Test fun readingAValueMakesItTheNewest() {
        val c = lru(10)
        c.put("a", "1234")
        c.put("b", "1234")
        c["a"]
        c.put("c", "1234")
        assertNull(c["b"])
        assertEquals(listOf("a", "c"), c.keys())
    }

    @Test fun replacingAValueCountsItsNewSizeNotBoth() {
        val c = lru(10)
        c.put("a", "1234")
        c.put("a", "123456")
        assertEquals(6L, c.totalBytes)
        assertEquals(1, c.size)
    }

    @Test fun aValueBiggerThanTheWholeBudgetIsKeptAloneRatherThanDropped() {
        val c = lru(10)
        c.put("a", "123")
        c.put("big", "x".repeat(40))
        assertEquals(listOf("big"), c.keys())
        assertEquals(40L, c.totalBytes)
        c.put("b", "12")
        assertEquals("the next one pushes the oversize one out", listOf("b"), c.keys())
    }

    @Test fun removeAndClearGiveTheBytesBack() {
        val c = lru(100)
        c.put("a", "1234")
        c.put("b", "12")
        c.remove("a")
        assertEquals(2L, c.totalBytes)
        c.remove("missing")
        assertEquals(2L, c.totalBytes)
        c.clear()
        assertEquals(0L, c.totalBytes)
        assertEquals(0, c.size)
    }
}

package cc.joycreator.joybrush.androidkit.lines

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import kotlin.test.*

class FrozenBrushCacheTest {
    private fun pen(size: Float = 8f) = BrushPreset(id = "lib-pen", name = "Pen", size = Param(size))
    private var ids = 0
    private fun newId() = "lb-${++ids}"

    @Test fun theSameBrushAtTheSameRevisionIsFrozenOnce() {
        val cache = FrozenBrushCache()
        val a = cache.freeze(5, emptyMap(), pen(), ::newId)
        val b = cache.freeze(5, emptyMap(), pen(), ::newId)
        assertSame(a, b)
        assertEquals(1, cache.computed)
        assertEquals(1, ids, "no second id was asked for")
    }

    @Test fun aNewRevisionOrAnotherBrushIsFrozenAgain() {
        val cache = FrozenBrushCache()
        cache.freeze(5, emptyMap(), pen(), ::newId)
        cache.freeze(6, emptyMap(), pen(), ::newId)
        cache.freeze(6, emptyMap(), pen(12f), ::newId)
        assertEquals(3, cache.computed)
    }

    @Test fun anAnswerThatReusesAKeptCopyStaysValidWhileTheRevisionStands() {
        val cache = FrozenBrushCache()
        val first = cache.freeze(1, emptyMap(), pen(), ::newId)
        val kept = mapOf(first.id to first.preset)
        val second = cache.freeze(2, kept, pen(), ::newId)
        assertEquals(first.id, second.id, "the kept copy is reused, not a new brush")
        assertTrue(second.newEntries.isEmpty())
        assertSame(second, cache.freeze(2, kept, pen(), ::newId))
        assertEquals(2, cache.computed)
    }

    @Test fun theOldestAnswerIsForgottenPastTheCapacity() {
        val cache = FrozenBrushCache(capacity = 2)
        cache.freeze(1, emptyMap(), pen(1f), ::newId)
        cache.freeze(1, emptyMap(), pen(2f), ::newId)
        cache.freeze(1, emptyMap(), pen(3f), ::newId)
        cache.freeze(1, emptyMap(), pen(1f), ::newId)
        assertEquals(4, cache.computed, "the first brush had been pushed out")
        cache.freeze(1, emptyMap(), pen(3f), ::newId)
        assertEquals(4, cache.computed, "the newest ones are still there")
    }

    @Test fun anInvalidBrushStillRaisesAndIsNotRemembered() {
        val cache = FrozenBrushCache()
        val bad = pen().copy(size = Param(Float.NaN))
        assertFailsWith<Exception> { cache.freeze(1, emptyMap(), bad, ::newId) }
        assertFailsWith<Exception> { cache.freeze(1, emptyMap(), bad, ::newId) }
        assertEquals(0, cache.computed)
    }

    @Test fun clearForgetsEverything() {
        val cache = FrozenBrushCache()
        cache.freeze(1, emptyMap(), pen(), ::newId)
        cache.clear()
        cache.freeze(1, emptyMap(), pen(), ::newId)
        assertEquals(2, cache.computed)
    }
}

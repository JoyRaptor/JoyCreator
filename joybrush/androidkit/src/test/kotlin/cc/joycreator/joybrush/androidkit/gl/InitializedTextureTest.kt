package cc.joycreator.joybrush.androidkit.gl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InitializedTextureTest {
    @Test fun initializedHandleBelongsToCallerAndIsNotReleased() {
        val live = mutableSetOf(17)
        var initialized = 0
        val texture = allocateInitializedTexture(
            allocate = { 73.also { live += it } },
            initialize = { initialized = it },
            release = { error("successful initialization must transfer ownership") },
        )
        assertEquals(73, texture)
        assertEquals(73, initialized)
        assertEquals(setOf(17, 73), live)
    }

    @Test fun failedInitializationReclaimsUnreturnedHandleWithoutReleasingSource() {
        val source = 17
        val live = mutableSetOf(source)
        val released = ArrayList<Int>()
        val original = IllegalStateException("initialization failed")
        val thrown = assertFailsWith<IllegalStateException> {
            allocateInitializedTexture(
                allocate = { 73.also { live += it } },
                initialize = { assertEquals(73, it); throw original },
                release = { released += it; assertTrue(live.remove(it)) },
            )
        }
        assertSame(original, thrown)
        assertEquals(listOf(73), released)
        assertEquals(setOf(source), live)
    }

    @Test fun failedAllocationHasNoHandleToInitializeOrRelease() {
        val original = IllegalStateException("allocation failed")
        val thrown = assertFailsWith<IllegalStateException> {
            allocateInitializedTexture(
                allocate = { throw original },
                initialize = { error("no allocation was returned") },
                release = { error("no handle is owned") },
            )
        }
        assertSame(original, thrown)
    }

    @Test fun cleanupFailurePreservesOriginalInitializationFailure() {
        val original = IllegalStateException("initialization failed")
        val cleanup = IllegalArgumentException("release failed")
        val thrown = assertFailsWith<IllegalStateException> {
            allocateInitializedTexture(
                allocate = { 73 },
                initialize = { throw original },
                release = { throw cleanup },
            )
        }
        assertSame(original, thrown)
        assertEquals(listOf(cleanup), thrown.suppressed.toList())
    }
}

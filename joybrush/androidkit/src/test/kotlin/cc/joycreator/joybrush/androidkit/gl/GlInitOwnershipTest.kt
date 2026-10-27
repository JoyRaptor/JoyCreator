package cc.joycreator.joybrush.androidkit.gl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GlInitOwnershipTest {
    @Test
    fun rollbackDeletesPartialAllocationsAcrossEveryKindAndClearsLedgers() {
        val deleted = mutableListOf<Pair<GlInitOwnership.Kind, Int>>()
        val ownership = GlInitOwnership(
            current = { true },
            delete = { kind, name -> deleted += kind to name },
            programs = 3,
            shaders = 2,
            buffers = 2,
            vertexArrays = 1,
            framebuffers = 2,
            textures = 2,
        )
        ownership.programNames[0] = 7
        ownership.programNames[2] = 7 // Duplicate within a kind is deleted once.
        ownership.shaderNames[0] = 7 // The same numeric name in another kind is independent.
        ownership.shaderNames[1] = 8
        ownership.bufferNames[1] = 9
        ownership.vertexArrayNames[0] = 10
        ownership.framebufferNames[0] = 11
        ownership.textureNames[1] = 12

        ownership.rollback(IllegalStateException("partial initialization failed"))

        assertEquals(
            setOf(
                GlInitOwnership.Kind.PROGRAM to 7,
                GlInitOwnership.Kind.SHADER to 7,
                GlInitOwnership.Kind.SHADER to 8,
                GlInitOwnership.Kind.BUFFER to 9,
                GlInitOwnership.Kind.VERTEX_ARRAY to 10,
                GlInitOwnership.Kind.FRAMEBUFFER to 11,
                GlInitOwnership.Kind.TEXTURE to 12,
            ),
            deleted.toSet(),
        )
        assertEquals(7, deleted.size)
        assertAllLedgersZero(ownership)
    }

    @Test
    fun staleContextSkipsDeletesEvenWhenNamesHaveBeenReused() {
        val deleted = mutableListOf<Pair<GlInitOwnership.Kind, Int>>()
        val ownership = GlInitOwnership(
            current = { false },
            delete = { kind, name -> deleted += kind to name },
            programs = 1,
            shaders = 1,
            buffers = 1,
            vertexArrays = 1,
            framebuffers = 1,
            textures = 1,
        )
        ownership.programNames[0] = 5
        ownership.shaderNames[0] = 5
        ownership.bufferNames[0] = 5
        ownership.vertexArrayNames[0] = 5
        ownership.framebufferNames[0] = 5
        ownership.textureNames[0] = 5

        ownership.rollback(IllegalStateException("context epoch changed"))

        assertTrue(deleted.isEmpty())
        assertAllLedgersZero(ownership)
    }

    @Test
    fun contextChangeDuringCleanupStopsFurtherDeletesButClearsEverySlot() {
        var isCurrent = true
        val deleted = mutableListOf<Pair<GlInitOwnership.Kind, Int>>()
        val ownership = GlInitOwnership(
            current = { isCurrent },
            delete = { kind, name ->
                deleted += kind to name
                isCurrent = false
            },
            programs = 1,
            shaders = 1,
            buffers = 1,
            vertexArrays = 1,
            framebuffers = 1,
            textures = 1,
        )
        ownership.programNames[0] = 1
        ownership.shaderNames[0] = 2
        ownership.bufferNames[0] = 3
        ownership.vertexArrayNames[0] = 4
        ownership.framebufferNames[0] = 5
        ownership.textureNames[0] = 6

        ownership.rollback(IllegalStateException("initialization failed"))

        assertEquals(1, deleted.size, "the current-context check must gate each handle")
        assertAllLedgersZero(ownership)
    }

    @Test
    fun deletionFailureIsSuppressedAndDoesNotPreventOtherCleanup() {
        val cleanupFailure = IllegalArgumentException("delete failed")
        val deleted = mutableListOf<Pair<GlInitOwnership.Kind, Int>>()
        val ownership = GlInitOwnership(
            current = { true },
            delete = { kind, name ->
                deleted += kind to name
                if (kind == GlInitOwnership.Kind.BUFFER) throw cleanupFailure
            },
            programs = 1,
            shaders = 1,
            buffers = 1,
            vertexArrays = 1,
            framebuffers = 1,
            textures = 1,
        )
        ownership.programNames[0] = 1
        ownership.shaderNames[0] = 2
        ownership.bufferNames[0] = 3
        ownership.vertexArrayNames[0] = 4
        ownership.framebufferNames[0] = 5
        ownership.textureNames[0] = 6
        val failure = IllegalStateException("initialization failed")

        ownership.rollback(failure)

        assertEquals(6, deleted.size)
        assertTrue(deleted.any { it.first == GlInitOwnership.Kind.TEXTURE })
        assertEquals(listOf(cleanupFailure), failure.suppressed.toList())
        assertSame(cleanupFailure, failure.suppressed.single())
        assertAllLedgersZero(ownership)
    }

    @Test
    fun repeatedRollbackDoesNotDeleteTheSameNamesTwice() {
        val deleted = mutableListOf<Pair<GlInitOwnership.Kind, Int>>()
        val ownership = GlInitOwnership(
            current = { true },
            delete = { kind, name -> deleted += kind to name },
            programs = 1,
            shaders = 1,
            buffers = 1,
            vertexArrays = 1,
            framebuffers = 1,
            textures = 1,
        )
        ownership.programNames[0] = 1
        ownership.shaderNames[0] = 2
        ownership.bufferNames[0] = 3
        ownership.vertexArrayNames[0] = 4
        ownership.framebufferNames[0] = 5
        ownership.textureNames[0] = 6

        ownership.rollback(IllegalStateException("first failure"))
        ownership.rollback(IllegalStateException("same failure, retried"))

        assertEquals(6, deleted.size)
        assertEquals(6, deleted.toSet().size)
        assertAllLedgersZero(ownership)
    }

    private fun assertAllLedgersZero(ownership: GlInitOwnership) {
        assertTrue(ownership.programNames.all { it == 0 })
        assertTrue(ownership.shaderNames.all { it == 0 })
        assertTrue(ownership.bufferNames.all { it == 0 })
        assertTrue(ownership.vertexArrayNames.all { it == 0 })
        assertTrue(ownership.framebufferNames.all { it == 0 })
        assertTrue(ownership.textureNames.all { it == 0 })
    }
}

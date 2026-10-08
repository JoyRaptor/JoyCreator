package cc.joycreator.joybrush.androidkit.gl.media

import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FloatUploadBufferTest {
    @Test fun uploadIsDirectAndNativeOrder() {
        val b = FloatUploadBuffer().upload(floatArrayOf(1f, 2f, 3f))
        assertTrue(b.isDirect, "staging must be direct")
        assertEquals(ByteOrder.nativeOrder(), b.order())
    }

    @Test fun uploadSetsPositionLimitAndCapacityExactly() {
        val data = floatArrayOf(1f, -2f, 3.5f, 0f)
        val b = FloatUploadBuffer().upload(data)
        assertEquals(0, b.position())
        assertEquals(data.size, b.limit())
        assertEquals(data.size, b.remaining())
        assertTrue(b.capacity() >= data.size)
    }

    @Test fun uploadReusesSameInstanceWhenCapacityFits() {
        val u = FloatUploadBuffer()
        val first = u.upload(floatArrayOf(1f, 2f, 3f, 4f))
        val second = u.upload(floatArrayOf(5f, 6f))
        assertSame(first, second, "fitting upload must reuse held buffer")
        assertEquals(2, second.limit())
        assertEquals(0, second.position())
        assertEquals(5f.toRawBits(), second.get(0).toRawBits())
        assertEquals(6f.toRawBits(), second.get(1).toRawBits())
    }

    @Test fun uploadPreservesEveryFloatRawBitsIncludingNaNPayloadsAndSignedZero() {
        val inputs = floatArrayOf(
            Float.fromBits(0x3F800000),
            Float.fromBits(0xBF800000.toInt()),
            Float.fromBits(0x00000000),
            Float.fromBits(0x80000000.toInt()),
            Float.fromBits(0x7FC00001),
            Float.fromBits(0x7FC0BEEF),
            Float.fromBits(0xFFC00002.toInt()),
            Float.fromBits(0x7F800000),
            Float.fromBits(0xFF800000.toInt())
        )
        val expectedRaw = intArrayOf(
            0x3F800000,
            0xBF800000.toInt(),
            0x00000000,
            0x80000000.toInt(),
            0x7FC00001,
            0x7FC0BEEF,
            0xFFC00002.toInt(),
            0x7F800000,
            0xFF800000.toInt()
        )
        val b = FloatUploadBuffer().upload(inputs)
        assertEquals(expectedRaw.size, b.limit())
        for (i in expectedRaw.indices) {
            assertEquals(expectedRaw[i], b.get(i).toRawBits(), "raw bits differ at $i")
        }
        assertEquals(0x00000000, b.get(2).toRawBits())
        assertEquals(0x80000000.toInt(), b.get(3).toRawBits())
        assertEquals(0x7FC00001, b.get(4).toRawBits())
        assertEquals(0x7FC0BEEF, b.get(5).toRawBits())
    }

    @Test fun emptyUploadHasNoActiveContent() {
        val u = FloatUploadBuffer()
        val emptyFirst = u.upload(floatArrayOf())
        assertEquals(0, emptyFirst.position())
        assertEquals(0, emptyFirst.limit())
        assertEquals(0, emptyFirst.remaining())
        assertTrue(emptyFirst.isDirect)
        assertEquals(ByteOrder.nativeOrder(), emptyFirst.order())
    }

    @Test fun emptyAfterNonEmptyLeavesNoStaleActiveContent() {
        val u = FloatUploadBuffer()
        u.upload(floatArrayOf(1f, 2f, 3f))
        val empty = u.upload(floatArrayOf())
        assertEquals(0, empty.position())
        assertEquals(0, empty.limit())
        assertEquals(0, empty.remaining())
    }

    @Test fun growthReallocatesAndKeepsExactContent() {
        val u = FloatUploadBuffer()
        val small = u.upload(floatArrayOf(1f, 2f))
        val grown = u.upload(floatArrayOf(10f, 20f, 30f, 40f, 50f))
        assertNotSame(small, grown, "growth past capacity must allocate a new buffer")
        assertTrue(grown.capacity() >= 5)
        assertEquals(0, grown.position())
        assertEquals(5, grown.limit())
        assertEquals(10f.toRawBits(), grown.get(0).toRawBits())
        assertEquals(50f.toRawBits(), grown.get(4).toRawBits())
        assertTrue(grown.isDirect)
        assertEquals(ByteOrder.nativeOrder(), grown.order())
    }

    @Test fun shrinkReusesBufferWithExactSmallerLimitAndNoStaleContent() {
        val u = FloatUploadBuffer()
        val large = u.upload(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f))
        val small = u.upload(floatArrayOf(9f, 8f))
        assertSame(large, small, "shrink must reuse held buffer")
        assertEquals(0, small.position())
        assertEquals(2, small.limit())
        assertEquals(2, small.remaining())
        assertEquals(9f.toRawBits(), small.get(0).toRawBits())
        assertEquals(8f.toRawBits(), small.get(1).toRawBits())
    }

    @Test fun alternationKeepsOnlyCurrentInputActive() {
        val u = FloatUploadBuffer()
        val sizes = intArrayOf(3, 1, 4, 2)
        for ((step, n) in sizes.withIndex()) {
            val data = FloatArray(n) { (step * 10 + it).toFloat() }
            val b = u.upload(data)
            assertEquals(0, b.position(), "step $step position")
            assertEquals(n, b.limit(), "step $step limit")
            assertEquals(n, b.remaining(), "step $step remaining")
            for (i in data.indices) {
                assertEquals(data[i].toRawBits(), b.get(i).toRawBits(), "step $step index $i")
            }
        }
    }

    @Test fun resetDropsReferenceAndNextUploadIsFresh() {
        val u = FloatUploadBuffer()
        val first = u.upload(floatArrayOf(1f, 2f, 3f))
        u.reset()
        assertEquals(0, u.capacity, "reset must drop held capacity")
        val next = u.upload(floatArrayOf(4f, 5f))
        assertNotSame(first, next, "post-reset upload must not reuse dropped buffer")
        assertEquals(0, next.position())
        assertEquals(2, next.limit())
        assertEquals(4f.toRawBits(), next.get(0).toRawBits())
        assertEquals(5f.toRawBits(), next.get(1).toRawBits())
        assertTrue(next.isDirect)
        assertEquals(ByteOrder.nativeOrder(), next.order())
    }

    @Test fun resetOnEmptyStaysEmpty() {
        val u = FloatUploadBuffer()
        u.reset()
        assertEquals(0, u.capacity)
        val b = u.upload(floatArrayOf())
        assertEquals(0, b.limit())
        assertEquals(0, b.position())
    }

    @Test fun overflowGuardRejectsWithoutHugeAllocation() {
        assertEquals(0, FloatUploadBuffer.byteSizeOrThrow(0))
        assertEquals(4, FloatUploadBuffer.byteSizeOrThrow(1))
        assertEquals(Int.MAX_VALUE / 4 * 4, FloatUploadBuffer.byteSizeOrThrow(Int.MAX_VALUE / 4))
        assertFailsWith<IllegalArgumentException> { FloatUploadBuffer.byteSizeOrThrow(Int.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { FloatUploadBuffer.byteSizeOrThrow(Int.MAX_VALUE / 4 + 1) }
        assertFailsWith<IllegalArgumentException> { FloatUploadBuffer.byteSizeOrThrow(-1) }
    }
}

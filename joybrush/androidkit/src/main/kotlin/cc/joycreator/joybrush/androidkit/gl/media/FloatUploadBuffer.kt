package cc.joycreator.joybrush.androidkit.gl.media

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Reusable direct native-order staging buffer for per-frame dab uploads.
 *
 * GL thread only: not synchronized. The owning [MediaLayerEngine] calls [upload] from its
 * per-frame dab-upload sites and [reset] from sleep/release.
 *
 * [upload] reuses the held buffer when its capacity fits, otherwise allocates a direct
 * native-order buffer sized exactly to the input; it then clear/put/flip so the returned
 * buffer has position 0 and limit exactly the input size. Bulk put copies every float's
 * raw bits, including NaN payloads and signed zero. A smaller input after a larger one
 * leaves no stale active content because the limit is exactly the smaller size.
 *
 * [reset] drops the held reference; it never calls System.gc nor any private cleaner.
 */
internal class FloatUploadBuffer {
    private var buffer: FloatBuffer? = null

    /** Held capacity in floats, 0 when nothing is held. */
    val capacity: Int get() = buffer?.capacity() ?: 0

    /**
     * Stage [data] for upload. Returns a direct native-order FloatBuffer with position 0
     * and limit exactly [data].size.
     */
    fun upload(data: FloatArray): FloatBuffer {
        var b = buffer
        if (b == null || b.capacity() < data.size) {
            b = ByteBuffer.allocateDirect(byteSizeOrThrow(data.size))
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            buffer = b
        }
        b.clear()
        b.put(data)
        b.flip()
        return b
    }

    /** Drop the held buffer, if any. */
    fun reset() {
        buffer = null
    }

    companion object {
        /**
         * Exact byte size for [floatCount] floats; rejects negative counts and
         * Int-byte-overflow without allocating.
         */
        fun byteSizeOrThrow(floatCount: Int): Int {
            require(floatCount >= 0) { "floatCount must be >= 0: $floatCount" }
            if (floatCount > Int.MAX_VALUE / 4) {
                throw IllegalArgumentException("floatCount $floatCount exceeds max direct bytes")
            }
            return floatCount * 4
        }
    }
}

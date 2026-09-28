package cc.joycreator.joybrush.core.io

import cc.joycreator.joybrush.core.stroke.StrokeCodecException

/**
 * The mirror of [ByteWriter]: reads little-endian back out of a byte array and refuses to invent
 * bytes that are not there.
 *
 * A short read is ALWAYS [StrokeCodecException], never a zero and never a partial number. Half a
 * stroke is worse than no stroke, so a truncated file must fail loudly and say so.
 */
class ByteReader(private val buf: ByteArray, private var pos: Int = 0) {

    val position: Int get() = pos
    val remaining: Int get() = buf.size - pos

    fun u8(): Int {
        need(1)
        return buf[pos++].toInt() and 0xFF
    }

    fun u16(): Int {
        need(2)
        val v = (buf[pos].toInt() and 0xFF) or ((buf[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        return v
    }

    fun u32(): Long {
        need(4)
        var v = 0L
        for (i in 0..3) v = v or ((buf[pos + i].toLong() and 0xFF) shl (i * 8))
        pos += 4
        return v
    }

    fun i64(): Long {
        need(8)
        var v = 0L
        for (i in 0..7) v = v or ((buf[pos + i].toLong() and 0xFF) shl (i * 8))
        pos += 8
        return v
    }

    fun f32(): Float = Float.fromBits(i32())

    fun f64(): Double = Double.fromBits(i64())

    /** A u16 byte count followed by that many UTF-8 bytes. */
    fun utf8(): String {
        val n = u16()
        val b = bytes(n)
        return try {
            b.decodeToString()
        } catch (e: Exception) {
            throw StrokeCodecException("bad utf-8 in stroke data: ${e.message ?: "invalid"}")
        }
    }

    fun bytes(n: Int): ByteArray {
        if (n < 0) throw StrokeCodecException("truncated: negative length $n")
        need(n)
        val out = buf.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    private fun i32(): Int {
        need(4)
        var v = 0
        for (i in 0..3) v = v or ((buf[pos + i].toInt() and 0xFF) shl (i * 8))
        pos += 4
        return v
    }

    private fun need(n: Int) {
        if (n < 0 || remaining < n) throw StrokeCodecException("truncated")
    }
}

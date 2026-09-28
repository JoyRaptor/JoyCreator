package cc.joycreator.joybrush.core.io

/**
 * A growable little-endian byte sink. Every number Joy Brush writes goes through here, so the file
 * formats stay byte-for-byte the same on the phone, on the PC and in a test — no `java.nio`, no
 * platform type, nothing that could differ.
 *
 * The buffer doubles when it fills. That is the whole allocation policy: a 1,000-sample stroke is
 * ~33 KB, written in a few dozen doublings.
 */
class ByteWriter {
    private var buf = ByteArray(256)
    private var len = 0

    val size: Int get() = len

    fun u8(v: Int) {
        require(v in 0..0xFF) { "u8 out of range: $v" }
        reserve(1)
        buf[len++] = v.toByte()
    }

    fun u16(v: Int) {
        require(v in 0..0xFFFF) { "u16 out of range: $v" }
        reserve(2)
        buf[len++] = v.toByte()
        buf[len++] = (v ushr 8).toByte()
    }

    fun u32(v: Long) {
        require(v in 0..0xFFFF_FFFFL) { "u32 out of range: $v" }
        reserve(4)
        for (i in 0..3) buf[len++] = (v ushr (i * 8)).toByte()
    }

    fun i64(v: Long) {
        reserve(8)
        for (i in 0..7) buf[len++] = (v ushr (i * 8)).toByte()
    }

    /** Raw bits, so NaN and -0.0f come back out of a round trip as the exact same float. */
    fun f32(v: Float) = i32(v.toRawBits())

    fun f64(v: Double) = i64(v.toRawBits())

    fun bytes(b: ByteArray) {
        reserve(b.size)
        b.copyInto(buf, len)
        len += b.size
    }

    /** A u16 BYTE count followed by the UTF-8 bytes — never a char count. */
    fun utf8(s: String) {
        val b = s.encodeToByteArray()
        require(b.size <= 0xFFFF) { "string is too long for a u16 length: ${b.size} bytes" }
        u16(b.size)
        bytes(b)
    }

    fun toByteArray(): ByteArray = buf.copyOf(len)

    private fun i32(v: Int) {
        reserve(4)
        for (i in 0..3) buf[len++] = (v ushr (i * 8)).toByte()
    }

    private fun reserve(n: Int) {
        if (len + n <= buf.size) return
        var cap = if (buf.isEmpty()) 1 else buf.size
        while (cap < len + n) cap *= 2
        buf = buf.copyOf(cap)
    }
}

package cc.joycreator.joybrush.core.media

/**
 * IEEE 754 half floats, for media state at rest (the Lead's ruling, 2026-10-07: RGBA16F stores, `.f16` in the archive).
 * The window simulates in full float; a tile is rounded to half once per write-back, round-to-nearest-even, which is what
 * the GPU does too. Bytes are little-endian, two per value.
 */
object HalfFloat {

    /** The half nearest [f] (ties to even); overflow is ±Infinity, NaN stays NaN. As the low 16 bits of an Int. */
    fun toHalf(f: Float): Int {
        val bits = f.toRawBits()
        val sign = (bits ushr 16) and 0x8000
        val exp = (bits ushr 23) and 0xFF
        var mant = bits and 0x7FFFFF
        if (exp == 0xFF) return sign or 0x7C00 or (if (mant != 0) 0x200 else 0)
        val e = exp - 127 + 15
        if (e >= 0x1F) return sign or 0x7C00
        if (e <= 0) {
            // Subnormal half: the value in units of 2^-24.
            if (e < -10) return sign
            mant = mant or 0x800000
            val shift = 14 - e
            var half = mant ushr shift
            val rem = mant and ((1 shl shift) - 1)
            val halfway = 1 shl (shift - 1)
            if (rem > halfway || (rem == halfway && (half and 1) != 0)) half++
            return sign or half
        }
        var half = (e shl 10) or (mant ushr 13)
        val rem = mant and 0x1FFF
        // A carry out of the mantissa rolls into the exponent, which is the correct next value (up to Infinity).
        if (rem > 0x1000 || (rem == 0x1000 && (half and 1) != 0)) half++
        return sign or half
    }

    /** The float a half (low 16 bits of [h]) stands for, exactly. */
    fun toFloat(h: Int): Float {
        val sign = (h and 0x8000) shl 16
        val exp = (h ushr 10) and 0x1F
        val mant = h and 0x3FF
        val bits = when {
            exp == 0 && mant == 0 -> sign
            exp == 0 -> {
                var e = -1
                var m = mant
                do { e++; m = m shl 1 } while (m and 0x400 == 0)
                sign or ((127 - 15 - e) shl 23) or ((m and 0x3FF) shl 13)
            }
            exp == 0x1F -> sign or 0x7F800000 or (mant shl 13)
            else -> sign or ((exp - 15 + 127) shl 23) or (mant shl 13)
        }
        return Float.fromBits(bits)
    }

    fun encode(values: FloatArray): ByteArray {
        val out = ByteArray(values.size * 2)
        for (i in values.indices) {
            val h = toHalf(values[i])
            out[2 * i] = h.toByte()
            out[2 * i + 1] = (h ushr 8).toByte()
        }
        return out
    }

    fun decode(bytes: ByteArray): FloatArray {
        require(bytes.size % 2 == 0) { "half floats come in pairs of bytes, got ${bytes.size}" }
        return FloatArray(bytes.size / 2) { toFloat((bytes[2 * it].toInt() and 0xFF) or ((bytes[2 * it + 1].toInt() and 0xFF) shl 8)) }
    }
}

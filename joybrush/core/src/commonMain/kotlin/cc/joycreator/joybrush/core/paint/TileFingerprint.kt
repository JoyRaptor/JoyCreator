package cc.joycreator.joybrush.core.paint

import cc.joycreator.joybrush.core.media.HalfFloat

/**
 * A 128-bit fingerprint of one tile's texels, the same arithmetic the graphics chip runs on the tile where it lies
 * (androidkit `gl/TileHasher`, JBX-36 "rereading only what changed", 2026-11-15). A save asks the chip for every tile's
 * fingerprint, which costs almost nothing, and reads back only the tiles whose fingerprint is not the one they had when
 * they were last read. The fingerprint is of the CONTENT, so it needs no record of who wrote a tile and when: a write
 * nobody remembered to note cannot be missed.
 *
 * Four lanes of 32 bits, each its own seed and odd multiplier. Per value: `h = (h xor v) * M; h = h xor (h ushr 15)`.
 * Both steps are one-to-one on `h` for a given `v`, and one-to-one on `v` for a given `h`, so two tiles that differ in
 * exactly one value never share a fingerprint. The shift matters: a multiply alone only carries a difference upward, and
 * two flipped top bits (two changed signs in a paint store) would cancel in every lane.
 *
 * The tile is taken as 16 x 16 blocks of 16 x 16 texels (the chip does a block per output pixel), each block's texels
 * row by row, red green blue alpha; then the blocks' results row by row, lane by lane.
 *
 * A texel's value is its byte (0..255) for a picture tile, and the bits of the 32-bit float a half float widens to for a
 * paint store tile (what a shader's `floatBitsToUint` sees).
 */
object TileFingerprint {
    const val SIDE = 256
    private const val BLOCK = 16
    private val SEED = intArrayOf(0x811C9DC5.toInt(), 0x9E3779B9.toInt(), 0x85EBCA6B.toInt(), 0xC2B2AE35.toInt())
    private val MUL = intArrayOf(16777619, 0x9E3779B1.toInt(), 0x85EBCA77.toInt(), 0xC2B2AE3D.toInt())

    /** The seeds and multipliers as GLSL `uvec4(...)` bodies, so the shader cannot drift from this file. */
    val glslSeed: String get() = SEED.joinToString(", ") { "${it.toUInt()}u" }
    val glslMul: String get() = MUL.joinToString(", ") { "${it.toUInt()}u" }

    class Print(val a: Long, val b: Long) {
        override fun equals(other: Any?) = other is Print && other.a == a && other.b == b
        override fun hashCode() = (a xor b).toInt()
        override fun toString() = a.toULong().toString(16).padStart(16, '0') + b.toULong().toString(16).padStart(16, '0')
    }

    /** From the four lanes as the chip hands them back (red, green, blue, alpha of one RGBA32UI pixel). */
    fun of(l0: Int, l1: Int, l2: Int, l3: Int): Print =
        Print((l0.toLong() shl 32) or (l1.toLong() and 0xFFFFFFFFL), (l2.toLong() shl 32) or (l3.toLong() and 0xFFFFFFFFL))

    /** A picture tile: [SIDE]² RGBA8 texels, rows from the texture's first row, as a readback gives them. */
    fun ofBytes(rgba: ByteArray): Print {
        require(rgba.size == SIDE * SIDE * 4) { "a picture tile is ${SIDE * SIDE * 4} bytes" }
        return fold { i -> rgba[i].toInt() and 0xFF }
    }

    /** A paint store tile: [SIDE]² RGBA half floats, two bytes each in the order a readback on this machine gives them. */
    fun ofHalves(halves: ByteArray, littleEndian: Boolean = true): Print {
        require(halves.size == SIDE * SIDE * 8) { "a paint store tile is ${SIDE * SIDE * 8} bytes" }
        return fold { i ->
            val lo = halves[i * 2].toInt() and 0xFF; val hi = halves[i * 2 + 1].toInt() and 0xFF
            HalfFloat.toFloat(if (littleEndian) (hi shl 8) or lo else (lo shl 8) or hi).toRawBits()
        }
    }

    /** [value]: the value at channel index `(y * SIDE + x) * 4 + channel`. */
    private inline fun fold(value: (Int) -> Int): Print {
        val out = SEED.copyOf()
        val h = IntArray(4)
        for (by in 0 until SIDE / BLOCK) for (bx in 0 until SIDE / BLOCK) {
            SEED.copyInto(h)
            for (y in 0 until BLOCK) {
                var i = ((by * BLOCK + y) * SIDE + bx * BLOCK) * 4
                for (n in 0 until BLOCK * 4) {
                    val v = value(i++)
                    for (l in 0 until 4) { val m = (h[l] xor v) * MUL[l]; h[l] = m xor (m ushr 15) }
                }
            }
            for (l in 0 until 4) { val m = (out[l] xor h[l]) * MUL[l]; out[l] = m xor (m ushr 15) }
        }
        return of(out[0], out[1], out[2], out[3])
    }
}

package cc.joycreator.joybrush.core.grain

import kotlin.math.floor

/**
 * Fractal value noise on a periodic lattice — the "clouds" height field the grain shader
 * thresholds (joybrush/shaders/jb_grain.glsl). Tileable by construction, because every octave's
 * lattice is indexed `mod cells`; identical on every platform, because every lattice value comes
 * from a hash of the seed and never from kotlin.random.
 */
object CloudNoise {

    /**
     * size×size heights in 0..1, row-major, TILEABLE (wraps seamlessly at both edges).
     * Fractal value noise: [octaves] layers, base lattice = [baseCells] cells across, each octave
     * doubles the cells and multiplies amplitude by [persistence]. Result normalised so min = 0
     * and max = 1.
     *
     * The noise is a function of position only, so sampling it finer (a larger [size]) gives the
     * same field with more detail — the 512 PNG is the 256 PNG, not a different cloud.
     */
    fun generate(
        size: Int,
        seed: Long,
        octaves: Int = 5,
        baseCells: Int = 4,
        persistence: Float = 0.5f,
    ): FloatArray {
        require(size >= 1) { "size must be positive, was $size" }
        require(baseCells >= 1) { "baseCells must be positive, was $baseCells" }
        require(octaves in 1..30) { "octaves must be 1..30, was $octaves" }
        val finest = baseCells.toLong() shl (octaves - 1)
        require(size % finest == 0L) {
            "size $size must be a multiple of baseCells * 2^(octaves-1) = $finest"
        }

        val out = FloatArray(size * size)
        var amplitude = 1f
        for (octave in 0 until octaves) {
            val cells = (baseCells.toLong() shl octave).toInt()
            val lattice = lattice(cells, seed, octave)
            // Cell coordinates along one axis: which lattice column/row, and how far into it.
            val i0 = IntArray(size)
            val i1 = IntArray(size)
            val fx = FloatArray(size)
            val cellWidth = cells.toFloat() / size.toFloat()
            for (x in 0 until size) {
                val t = x * cellWidth
                val i = floor(t).toInt()
                i0[x] = i
                i1[x] = (i + 1) % cells
                fx[x] = smoothstep(t - i)
            }
            for (y in 0 until size) {
                val t = y * cellWidth
                val j = floor(t).toInt()
                val topRow = (j % cells) * cells
                val bottomRow = ((j + 1) % cells) * cells
                val fy = smoothstep(t - j)
                val outRow = y * size
                for (x in 0 until size) {
                    val col = i0[x]
                    val nextCol = i1[x]
                    val near = lattice[topRow + col] + (lattice[topRow + nextCol] - lattice[topRow + col]) * fx[x]
                    val far = lattice[bottomRow + col] + (lattice[bottomRow + nextCol] - lattice[bottomRow + col]) * fx[x]
                    out[outRow + x] += amplitude * (near + (far - near) * fy)
                }
            }
            amplitude *= persistence
        }

        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in out) {
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        val range = hi - lo
        if (range > 0f) {
            val k = 1f / range
            for (i in out.indices) out[i] = (out[i] - lo) * k
        } else {
            // A field with no variation at all (one lattice cell, say) is flat, not NaN.
            out.fill(0f)
        }
        return out
    }

    /** The periodic lattice for one octave: [cells]×[cells] values in 0..1. */
    private fun lattice(cells: Int, seed: Long, octave: Int): FloatArray {
        val out = FloatArray(cells * cells)
        for (j in 0 until cells) for (i in 0 until cells) out[j * cells + i] = latticeValue(seed, octave, i, j)
        return out
    }

    private fun latticeValue(seed: Long, octave: Int, i: Int, j: Int): Float {
        val mixed = seed.toULong() xor
            (octave.toULong() * 0x9E3779B97F4A7C15uL) xor
            (i.toULong() * 0xBF58476D1CE4E5B9uL) xor
            (j.toULong() * 0x94D049BB133111EBuL)
        return (splitMix64(mixed) shr 40).toFloat() / 16777216f
    }

    /** SplitMix64's finalising mix, on unsigned 64-bit so the shifts are logical. */
    private fun splitMix64(v: ULong): ULong {
        var z = v
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        return z xor (z shr 31)
    }

    private fun smoothstep(t: Float): Float = t * t * (3f - 2f * t)
}

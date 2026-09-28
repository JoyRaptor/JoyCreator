package cc.joycreator.joybrush.core.brush

/**
 * SplitMix64 — the deterministic 64-bit generator behind every bit of brush randomness.
 *
 * Why not `kotlin.random`: its algorithm is not promised across versions or platforms, so a stroke
 * drawn yesterday might replay differently today, and a brush's size jitter would move every time
 * the app updated. Joy Brush strokes are recordings — they are re-rendered at another zoom, replayed
 * for a timelapse, and re-drawn with a different brush, sometimes on another device. The numbers a
 * stroke was drawn with have to be the numbers it is re-rendered with, forever.
 *
 * SplitMix64 is a handful of shifts, multiplies and xors over 64-bit two's-complement arithmetic,
 * which Kotlin defines exactly on every platform (overflow wraps, there is no undefined behaviour).
 * So "the same seed gives the same numbers" is a property of the code, not of a library version.
 *
 * It is not a cryptographic generator and does not need to be: the seed for a stroke comes from the
 * document, and the job is a scatter of dabs, not a secret.
 *
 * [nextFloat] takes the TOP 24 bits of a draw and divides by 2^24. A float has 24 bits of
 * significand, so that is the whole range it can hold anyway — the result is in 0..1 (0 included,
 * 1 excluded) and needs no rounding of its own. The bits it drops are still mixed into the state by
 * [nextLong], so the stream does not go soft.
 */
class SplitMix(seed: Long) {

    private var state: Long = seed

    /** The next 64-bit draw. Same seed, same sequence, on every platform, forever. */
    fun nextLong(): Long {
        state += GAMMA
        var z = state
        z = (z xor (z ushr 30)) * MIX_1
        z = (z xor (z ushr 27)) * MIX_2
        return z xor (z ushr 31)
    }

    /** The next draw as a float in 0..1, from its top 24 bits. */
    fun nextFloat(): Float = (nextLong() ushr 40).toFloat() / TWO_POW_24

    companion object {
        /** 0x9E3779B97F4A7C15 — the golden-ratio step that walks the state through the whole range. */
        private const val GAMMA = -0x61c8864680b583ebL

        /** 0xBF58476D1CE4E5B9. */
        private const val MIX_1 = -0x40a7b892e31b1a47L

        /** 0x94D049BB133111EB. */
        private const val MIX_2 = -0x6bf2b644eceeee15L

        /** 2^24, exact as a float, and the divisor that turns 24 bits into 0..1. */
        private const val TWO_POW_24 = 16777216f
    }
}

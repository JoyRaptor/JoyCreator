package cc.joycreator.joybrush.core.export

import kotlin.math.floor

/**
 * A board animation's play order turned into GIF frames whose delays add up to the board's own length
 * (Codex audit JB-ANIM-01 and JB-ANIM-02, 11-15).
 *
 * - **No drift.** Each frame's hundredths of a second are the difference of two rounded running totals,
 *   never each frame rounded on its own: 30 one-tick frames at 30 fps last 100 cs, not 90.
 * - **At most 50 drawings a second.** A GIF delay under 2 cs is not read as a duration by browsers. A
 *   drawing that would start less than 2 cs after the one before is left out and the one before is shown
 *   for its time, so the length stays right; [Result.dropped] says how many, for a warning in words.
 * - **Long holds kept whole.** A GIF delay is 16 bits (655.35 s). A longer hold is written as the same
 *   picture several times, never shortened; no piece is shorter than 2 cs.
 *
 * Pure, so the arithmetic is tested off the phone.
 */
object GifTiming {
    const val MIN_CS = 2
    const val MAX_CS = 0xFFFF

    /** One GIF frame: which of the caller's pictures it shows, for how many hundredths of a second. */
    data class Piece(val picture: Int, val centiseconds: Int)

    data class Result(val pieces: List<Piece>, val dropped: Int)

    /**
     * [pictures] and [delaysMs] are the same length, in play order: the picture each step shows and how
     * long it is shown in milliseconds.
     */
    fun of(pictures: List<Int>, delaysMs: List<Int>): Result {
        require(pictures.size == delaysMs.size) { "one delay per picture" }
        if (pictures.isEmpty()) return Result(emptyList(), 0)
        // Running totals in ms, each rounded half up to a hundredth once.
        val bounds = LongArray(pictures.size + 1)
        var elapsedMs = 0L
        for (i in delaysMs.indices) {
            elapsedMs += delaysMs[i].coerceAtLeast(0)
            bounds[i + 1] = floor(elapsedMs / 10.0 + 0.5).toLong()
        }
        val end = bounds[pictures.size]
        val keptPicture = ArrayList<Int>(pictures.size)
        val keptStart = ArrayList<Long>(pictures.size)
        var dropped = 0
        for (i in pictures.indices) {
            if (keptStart.isEmpty() || bounds[i] - keptStart.last() >= MIN_CS) {
                keptPicture.add(pictures[i]); keptStart.add(bounds[i])
            } else dropped++
        }
        // The last drawing kept must itself last 2 cs; if it cannot, the one before runs to the end.
        if (keptStart.size > 1 && end - keptStart.last() < MIN_CS) {
            keptPicture.removeAt(keptPicture.lastIndex); keptStart.removeAt(keptStart.lastIndex); dropped++
        }
        val pieces = ArrayList<Piece>(keptPicture.size)
        for (k in keptPicture.indices) {
            val until = if (k < keptStart.lastIndex) keptStart[k + 1] else end
            var left = maxOf(until - keptStart[k], MIN_CS.toLong())
            while (left > 0) {
                // A piece of 65 535 cs would leave 1 cs behind: take 65 534 then, so the tail is 2.
                val piece = if (left == MAX_CS + 1L) MAX_CS - 1L else minOf(left, MAX_CS.toLong())
                pieces.add(Piece(keptPicture[k], piece.toInt()))
                left -= piece
            }
        }
        return Result(pieces, dropped)
    }
}

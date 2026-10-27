package cc.joycreator.joybrush.core.doc

import kotlin.math.floor

/** Clip timing at a common fps. Each drawing's hold is rounded to the nearest whole tick. */
class ClipTimeline(private val board: Board, fps: Float) {
    private val holds = board.frames.map { floor(fps.toDouble() / board.fps * it.holdFrames + 0.5).toInt().coerceAtLeast(1) }
    val length: Int
    private val cycle: Int
    init {
        val n = holds.sumOf { it.toLong() }
        if (n == 0L || n > Int.MAX_VALUE / 2) throw DocException("This clip's timeline is too long")
        length = n.toInt()
        cycle = if (holds.size > 2) length + holds.subList(1, holds.lastIndex).sum() else length
    }
    val cycleLength: Int get() = if (BoardEnd.of(board.end) == BoardEnd.PING_PONG) cycle else length

    fun frameAt(tick: Int): String? {
        var t = tick.coerceAtLeast(0)
        if (t >= length) when (BoardEnd.of(board.end)) {
            BoardEnd.BLANK -> return null
            BoardEnd.HOLD -> return board.frames.last().id
            BoardEnd.PING_PONG -> {
                t %= cycle
                if (t >= length) {
                    t -= length
                    for (i in holds.lastIndex - 1 downTo 1) {
                        if (t < holds[i]) return board.frames[i].id
                        t -= holds[i]
                    }
                }
            }
            else -> t %= length
        }
        for (i in holds.indices) {
            if (t < holds[i]) return board.frames[i].id
            t -= holds[i]
        }
        return board.frames.last().id
    }
}

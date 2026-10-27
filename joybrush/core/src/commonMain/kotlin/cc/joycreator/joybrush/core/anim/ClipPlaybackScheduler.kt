package cc.joycreator.joybrush.core.anim

import cc.joycreator.joybrush.core.doc.*

/** One origin for every clip; each immutable clock retains its own fps, holds and saved end. */
class ClipPlaybackScheduler(boards: List<Board>) {
    private val clips = boards.filter { it.kind == BoardKind.ANIMATION && it.frames.isNotEmpty() }
        .map { it to PlaybackClock(it, PlayMode.of(it.end)) }

    fun framesAt(elapsedMs: Double): Map<String, String> = clips.associate { (b, c) ->
        b.id to if (c.isBlank(elapsedMs)) RegionDocumentOps.BLANK_PREVIEW else b.frames[c.frameIndexAt(elapsedMs)].id
    }

    /** Infinity means every clip has reached a stable final picture. Keep that preview until Stop. */
    fun nextChangeMs(elapsedMs: Double): Double = clips.minOfOrNull { it.second.nextChangeMs(elapsedMs) }
        ?: Double.POSITIVE_INFINITY
}

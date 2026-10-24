package cc.joycreator.joybrush.core.anim

import cc.joycreator.joybrush.core.doc.DocException
import cc.joycreator.joybrush.core.doc.Frame

/**
 * The tween exposure at one board tick. The source frame is the frame shown at this tick;
 * [nextFrameId] is present only when that source frame is actively tweening toward its next
 * play-order frame. Fractions are tick-quantized, so repeated polls within one tick are identical.
 */
data class TweenFrameSample(
    val frameId: String,
    val frameIndex: Int,
    val nextFrameId: String?,
    val nextFrameIndex: Int?,
    /** Zero-based tick offset within the source frame's hold. */
    val tickOffset: Long,
    /** `tickOffset / holdFrames` when interpolating, otherwise zero. */
    val fraction: Double,
) {
    companion object {
        /**
         * Samples an integer tick on the board timeline. With [loop] enabled, nonnegative ticks wrap
         * over the whole board using Euclidean modulo. Negative ticks deliberately clamp to the
         * first frame, matching [cc.joycreator.joybrush.core.doc.AnimOps.frameAt]. Without looping,
         * ticks before/after the board clamp to its first/last occupied tick.
         */
        fun atTick(frames: List<Frame>, tick: Long, loop: Boolean = false): TweenFrameSample {
            val totalTicks = validateAndCountTicks(frames)
            val effectiveTick = when {
                tick < 0L -> 0L
                loop -> tick % totalTicks
                tick >= totalTicks -> totalTicks - 1L
                else -> tick
            }

            var frameStart = 0L
            var index = 0
            while (index < frames.lastIndex && effectiveTick >= frameStart + frames[index].holdFrames) {
                frameStart += frames[index].holdFrames.toLong()
                index++
            }

            val frame = frames[index]
            val offset = effectiveTick - frameStart
            val targetIndex = when {
                !frame.tween || frame.holdFrames <= 1 -> null
                index < frames.lastIndex -> index + 1
                loop -> 0
                else -> null
            }
            return TweenFrameSample(
                frameId = frame.id,
                frameIndex = index,
                nextFrameId = targetIndex?.let(frames::get)?.id,
                nextFrameIndex = targetIndex,
                tickOffset = offset,
                fraction = if (targetIndex == null) 0.0 else offset.toDouble() / frame.holdFrames,
            )
        }

        /**
         * Samples a board-timeline millisecond position without summing rounded per-frame
         * durations. Each tick boundary is derived from its cumulative integer tick count as
         * `cumulativeTicks * 1000 / fps`, which keeps the same boundary arithmetic at every fps.
         *
         * Negative times and NaN resolve to the first frame. Positive infinity resolves to the
         * final frame when not looping; non-finite times with looping resolve to the first frame
         * because no modulo position is defined. Positive finite times wrap only when looping.
         */
        fun atTimeMs(
            frames: List<Frame>,
            fps: Float,
            timeMs: Double,
            loop: Boolean = false,
        ): TweenFrameSample {
            val totalTicks = validateAndCountTicks(frames)
            if (!fps.isFinite() || fps !in 1f..60f) {
                throw DocException("fps $fps is not playable (must be 1 to 60)")
            }
            if (timeMs.isNaN() || timeMs < 0.0 || (loop && !timeMs.isFinite())) {
                return atTick(frames, 0L, loop)
            }
            val rate = fps.toDouble()
            if (!timeMs.isFinite()) return atTick(frames, totalTicks - 1L, loop)

            val durationMs = boundaryMs(totalTicks, rate)
            val effectiveTimeMs = if (loop) timeMs % durationMs else timeMs
            if (effectiveTimeMs >= durationMs) return atTick(frames, totalTicks - 1L, loop)

            // Binary-search the greatest integer tick boundary no later than the requested time.
            // Comparing the same cumulative-boundary expression that defines the schedule avoids
            // losing an exact boundary to `(timeMs * fps) / 1000` rounding just below its tick.
            var low = 0L
            var high = totalTicks
            while (low + 1L < high) {
                val mid = low + (high - low) / 2L
                if (boundaryMs(mid, rate) <= effectiveTimeMs) low = mid else high = mid
            }
            return atTick(frames, low, loop)
        }

        /**
         * Absolute board-timeline time of the next scheduled exposure. Tween holds wake on each
         * integer tick; ordinary holds sleep until the next frame. Uses the same cumulative tick
         * boundaries as [atTimeMs], including fractional fps and loop seams. This is a forward
         * timeline primitive, not a replacement for PlaybackClock's speed/range/end policy.
         *
         * A non-looping final frame has no further changes. Positive infinity and times so large
         * that Double can no longer represent the next boundary also return positive infinity.
         */
        fun nextChangeTimeMs(
            frames: List<Frame>,
            fps: Float,
            timeMs: Double,
            loop: Boolean = false,
        ): Double {
            val sample = atTimeMs(frames, fps, timeMs, loop)
            if (timeMs == Double.POSITIVE_INFINITY) return Double.POSITIVE_INFINITY
            if (!loop && sample.frameIndex == frames.lastIndex && sample.nextFrameId == null) {
                return Double.POSITIVE_INFINITY
            }
            val cleanTime = if (!timeMs.isFinite() || timeMs < 0.0) 0.0 else timeMs
            val duration = boundaryMs(validateAndCountTicks(frames), fps.toDouble())
            val position = if (loop) cleanTime % duration else cleanTime
            var frameStart = 0L
            for (index in 0 until sample.frameIndex) frameStart += frames[index].holdFrames.toLong()
            val nextTick = frameStart + if (sample.nextFrameId != null) sample.tickOffset + 1L
                else frames[sample.frameIndex].holdFrames.toLong()
            val next = (cleanTime - position) + boundaryMs(nextTick, fps.toDouble())
            return if (next > cleanTime) next else Double.POSITIVE_INFINITY
        }

        private fun validateAndCountTicks(frames: List<Frame>): Long {
            if (frames.isEmpty()) throw DocException("board has no frames, so nothing is showing")
            val ids = HashSet<String>()
            var total = 0L
            for (frame in frames) {
                if (!ids.add(frame.id)) throw DocException("board has two frames called \"${frame.id}\"")
                if (frame.holdFrames < 1) {
                    throw DocException("frame \"${frame.id}\" must be held for at least one tick")
                }
                total += frame.holdFrames.toLong()
            }
            return total
        }

        private fun boundaryMs(cumulativeTicks: Long, fps: Double): Double =
            cumulativeTicks.toDouble() * 1000.0 / fps
    }
}

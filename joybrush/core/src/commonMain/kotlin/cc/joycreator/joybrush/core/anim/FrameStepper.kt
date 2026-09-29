package cc.joycreator.joybrush.core.anim

import kotlin.math.min

/**
 * What the screen must DO, at one instant, given what is currently on it. Pure: no clock, no
 * thread, no Android, no sound. A player that owns one of these and a Handler is a player; a
 * player that owns arithmetic is a bug waiting for a review.
 */
sealed class Step {
    /**
     * Put frame [index] on screen. Emitted ONLY when it differs from the frame the caller said was
     * up — that comparison is the reason this file exists.
     */
    data class ShowFrame(val index: Int) : Step()

    /**
     * The sound must be at [boardMs] of the BOARD's timeline, or SILENT when null.
     *
     * This is `PlaybackClock.audioPositionMs` verbatim, with **no tolerance, no smoothing and no
     * interpolation** — see Decision 6. The device side decides whether a change of 0.4 ms is worth
     * a `seekTo`; this file does not know what a `seekTo` costs.
     *
     * Where the BYTES come from is JB-0.02c's business (`Board.audio`, the archive entry, the size
     * bound). Nothing in this file opens a file, a stream or a player.
     */
    data class Audio(val boardMs: Double?) : Step()

    /**
     * ONCE has reached the end of its range. The last frame stays up.
     *
     * LEVEL-TRIGGERED, not once: `step` is a pure function of its three arguments, so it cannot
     * know it has already said this. A player must therefore treat it as a standing fact and stop.
     * See Decision 4.
     */
    object Finish : Step()
}

/**
 * Playback as a PURE FUNCTION of elapsed time.
 *
 * [step] takes the frame that is CURRENTLY on screen and never emits [Step.ShowFrame] for it. That
 * one parameter is the whole of the fix for the backward-boundary spin: a caller cannot ask "what
 * should I draw?" without also saying "here is what is drawn", so there is no code path in which
 * "something changed" is decided without comparing it to something.
 *
 * IMMUTABLE, like the clock, and for the clock's own reason: changing the board, the range, the
 * mode or the speed builds a new clock and a new stepper, which restarts playback at 0. This class
 * does not work around that, and it holds no state of its own — see Decision 2.
 */
class FrameStepper(val clock: PlaybackClock) {

    /**
     * What to do at [elapsedMs], given that [frameIndex] is on screen and the sound is at
     * [audioAtMs] on the board's timeline (`null` = silent).
     *
     * PURE: the same three arguments always give the same list. No "last frame" field, no
     * "already finished" field, no `var` of any kind.
     *
     * ORDER: the picture first, then the sound, then [Step.Finish] — so a caller that draws before
     * it sounds never shows a picture a frame ahead of its sound. An unchanged moment is an EMPTY
     * LIST, not a singleton: a list is what the caller has to handle anyway, and one less type in
     * the contract is one less thing to get wrong.
     *
     * [frameIndex] is what the CALLER believes, and is not checked against the clock's range —
     * [PlaybackClock] does not expose the range, and a caller that lies here gets the clock's
     * answer back rather than an exception. See Decision 8.
     */
    fun step(elapsedMs: Double, frameIndex: Int, audioAtMs: Double?): List<Step> {
        val show = clock.frameIndexAt(elapsedMs)
        val audio = clock.audioPositionMs(elapsedMs)
        val finish = clock.isFinished(elapsedMs)
        // The overwhelmingly common answer, and it is not an error. Computed before anything is
        // allocated so that a player polling on every Handler tick pays nothing for a frame that has
        // not changed — which, on a board of long holds, is nearly every tick.
        if (show == frameIndex && audio == audioAtMs && !finish) return emptyList()
        val out = ArrayList<Step>(3)
        // Both comparisons are `==` on the clock's OWN number: no epsilon, because a tolerance
        // belongs to whoever knows what a `seekTo` costs, and this class is the wrong place to
        // guess (Decision 6). For a `Double?` that is a bit-for-bit comparison, which is the only
        // kind that can be asked the question.
        if (show != frameIndex) out.add(Step.ShowFrame(show))
        if (audio != audioAtMs) out.add(Step.Audio(audio))
        if (finish) out.add(Step.Finish)
        return out
    }

    /**
     * The frame the clock says is up at [elapsedMs], whatever is on screen. For the film strip's
     * playhead marker, which is a READING and not a change, so it does not go through [step].
     */
    fun frameOnScreen(elapsedMs: Double): Int = clock.frameIndexAt(elapsedMs)

    /**
     * How long until the player should look again, wall ms — a FLOOR for the handler's delay, not a
     * schedule and never [PlaybackClock.nextChangeMs] itself.
     *
     * **ALWAYS STRICTLY GREATER THAN THE ELAPSED, once the elapsed has been placed on a timeline**
     * (a `NaN`, a negative or an infinite `t` means the beginning, exactly as
     * [PlaybackClock] decides it — see Decision 7). That is the whole contract, and Decision 7 is
     * why it takes work to keep: at a backward boundary the clock answers `nextChangeMs(t) == t`
     * bit-for-bit and the frame has not changed yet, so a naive `min(nextChangeMs, t + MAX)` hands
     * a player back its own timestamp and a millisecond clock loops on it.
     *
     * A caller that sleeps until this and then calls [step] again makes progress. A caller that
     * treats it as "the frame changes here" is wrong, and at a backward boundary the difference is
     * the spin.
     */
    fun nextWakeMs(elapsedMs: Double): Double {
        // The clock's OWN rule, restated: `cleanElapsed` is private to it, and a `t` that cannot be
        // placed on a timeline means "the beginning". Restating it here rather than importing it is
        // not duplication — the two are one sentence, and this one has to run BEFORE the comparison
        // below, because every comparison against NaN is false.
        val t = if (elapsedMs.isFinite() && elapsedMs >= 0.0) elapsedMs else 0.0
        val next = clock.nextChangeMs(t)
        return when {
            next > t -> min(next, t + MAX_SLEEP_MS)
            else -> t + MIN_WAKE_MS       // next == t, bit-for-bit: a backward boundary
        }
    }

    companion object {
        /** The longest a player may sleep before looking again, wall ms. 250 = the app's own tap
         *  threshold (`CanvasGestures.TAP_MS = 250L`), chosen so a Handler can never be parked past
         *  the point where a person would tap Stop. */
        const val MAX_SLEEP_MS: Double = 250.0

        /**
         * The smallest move this class will ask a player to make, wall ms. Used ONLY when the
         * clock says the next change is at or before now — which, by the clock's own pinned
         * contract, is exactly a backward boundary asked at exactly itself. See Decision 7 for why
         * 1 ms and not something derived from a frame.
         */
        const val MIN_WAKE_MS: Double = 1.0
    }
}

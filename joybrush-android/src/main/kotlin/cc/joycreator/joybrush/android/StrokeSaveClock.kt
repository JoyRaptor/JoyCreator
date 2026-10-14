package cc.joycreator.joybrush.android

/** Monotonic deadline that continuous painting cannot postpone by restarting the idle timer. */
internal class StrokeSaveClock(now: Long, private val intervalMs: Long = 60_000L) {
    private var anchor = now
    init { require(now >= 0 && intervalMs > 0) }

    fun due(now: Long, dirty: Boolean, ready: Boolean, queueBusy: Boolean): Boolean =
        dirty && ready && !queueBusy && now >= anchor && now - anchor >= intervalMs

    /** Failed attempts also wait their interval; the existing 30-second idle retry remains. */
    fun attempted(now: Long) { anchor = now }
    fun saved(now: Long) { anchor = now }
    fun reset(now: Long) { anchor = now }
}

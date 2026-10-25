package cc.joycreator.joybrush.androidkit.lines

import java.util.concurrent.atomic.AtomicBoolean

/** Delivery is scheduled on the UI thread by the view; competing terminal paths can win only once. */
internal class LineCopyReply(
    private val isCurrent: () -> Boolean,
    private val deliver: (LineCopyResult) -> Unit,
) {
    private val answered = AtomicBoolean(false)
    val isFinished: Boolean get() = answered.get()

    fun finish(result: LineCopyResult): Boolean {
        if (!answered.compareAndSet(false, true)) return false
        deliver(if (isCurrent()) result else LineCopyResult.Refused("That drawing changed before Copy finished. Tap Copy again."))
        return true
    }
}

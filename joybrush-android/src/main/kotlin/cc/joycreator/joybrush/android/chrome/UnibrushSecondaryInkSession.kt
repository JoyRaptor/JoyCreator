package cc.joycreator.joybrush.android.chrome

/** One picker owns temporary secondary ink. Only an accepted current choice persists. UI thread. */
internal class UnibrushSecondaryInkSession(
    private val read: () -> Int?, private val write: (Int?) -> Unit, private val persist: (Int) -> Unit,
) {
    private var current: Choice? = null
    fun begin(isCurrent: () -> Boolean): Choice {
        cancel()
        return Choice(read(), isCurrent).also { current = it }
    }
    fun cancel() { current?.cancel() }
    inner class Choice internal constructor(private val before: Int?, private val isCurrent: () -> Boolean) {
        val initialArgb: Int get() = before ?: 0xFFFFFFFF.toInt()
        private var last = before
        private var committed = false
        private fun admitted(): Boolean {
            if (current !== this || committed) return false
            if (!isCurrent()) { cancel(); return false }
            return true
        }
        fun preview(argb: Int) {
            if (!admitted()) return
            write(argb); last = argb
        }
        fun confirm(argb: Int): Boolean {
            if (!admitted()) return false
            persist(argb) // A failure leaves this choice cancellable; never report a saved value.
            committed = true
            write(argb); last = argb
            return true
        }
        fun cancel() {
            if (current !== this) return
            current = null
            // An independent later writer keeps its value.
            if (!committed && read() == last && read() != before) write(before)
        }
    }
}

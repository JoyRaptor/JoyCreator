package cc.joycreator.joybrush.androidkit

/**
 * A drawing's load as a chain of short graphics tasks instead of one (ATLAS-SOLID item 2, the load split): the work units
 * run in order, as many to a task as fit [budgetNanos] (always at least one), and the next task is handed to [schedule]
 * only when this one is done. One task that inflated and uploaded a whole drawing grew with the drawing; now each task is
 * short, and [onProgress] says after every task that the load is still moving, so a watchdog can wait on progress rather
 * than on the whole load.
 *
 * [alive] is asked before every unit: false (another load began, the screen is going) ends the chain where it is, with
 * neither [onFinish] nor [onFailed]. A unit that throws ends it with [onFailed]; no unit after it runs. [onFinish] runs once,
 * in the task that ran the last unit. One thread (the GL thread): no locking.
 */
internal class LoadSteps(
    private val units: List<() -> Unit>,
    private val budgetNanos: Long,
    private val schedule: (task: () -> Unit) -> Unit,
    private val alive: () -> Boolean,
    private val onProgress: (done: Int, of: Int) -> Unit,
    private val onFinish: () -> Unit,
    private val onFailed: (Exception) -> Unit,
    private val clock: () -> Long = System::nanoTime,
) {
    private var next = 0
    var tasks = 0; private set

    /** Hands the first task to [schedule]. */
    fun start() = schedule(::runTask)

    private fun runTask() {
        tasks++
        val began = clock()
        while (next < units.size) {
            if (!alive()) return
            val unit = units[next]
            // Exceptions only: an Error (out of memory) goes on up as it did from the one-task load.
            try { unit() } catch (e: Exception) { next = units.size; onFailed(e); return }
            next++
            // FAIL-FIRST PROBE (never land): every unit in one task, as the load was
        }
        if (!alive()) return
        if (next < units.size) {
            onProgress(next, units.size)
            schedule(::runTask)
        } else {
            onFinish()
        }
    }

    companion object {
        /** About a quarter second of uploads to a task: short enough to keep the graphics thread answering, few enough
         * tasks that waiting a frame between them costs little (a full oil page is about 7 s of work on the Note 9). */
        const val BUDGET_NANOS = 250_000_000L
    }
}

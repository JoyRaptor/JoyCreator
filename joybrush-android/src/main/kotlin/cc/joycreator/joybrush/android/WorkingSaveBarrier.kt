package cc.joycreator.joybrush.android

/**
 * Process-wide, per-working-path handoff between drawing screens. UI THREAD ONLY.
 * Register before requesting a snapshot (or queuing the leaving-screen save), not
 * when its disk write is finally submitted. Waiting never occupies the disk worker.
 */
internal class WorkingSaveBarrier {
    private class State {
        val pending = LinkedHashSet<Any>()
        val waiters = LinkedHashMap<Any, (String?) -> Unit>()
        var problem: String? = null
    }
    private val paths = HashMap<String, State>()

    inner class Ticket internal constructor(private val finish: (String?) -> Unit) {
        private var complete = false
        fun complete(problem: String?) {
            if (complete) return
            complete = true
            finish(problem)
        }
    }

    fun begin(path: String): Ticket {
        val state = paths.getOrPut(path) { State() }
        // A later explicit save/retry can establish a new durable handoff after failure.
        if (state.pending.isEmpty()) state.problem = null
        val token = Any()
        state.pending.add(token)
        return Ticket { problem ->
            state.pending.remove(token)
            if (problem != null && state.problem == null) state.problem = problem
            if (state.pending.isEmpty()) {
                val callbacks = state.waiters.values.toList()
                state.waiters.clear()
                val outcome = state.problem
                if (outcome == null && paths[path] === state) paths.remove(path)
                callbacks.forEach { it(outcome) }
            }
        }
    }

    /** Callback and completion run on the UI thread; callback should only enqueue work. */
    fun whenSettled(path: String, ready: (String?) -> Unit): AutoCloseable {
        val state = paths[path]
        if (state == null || state.pending.isEmpty()) {
            ready(state?.problem)
            return AutoCloseable { }
        }
        val token = Any()
        state.waiters[token] = ready
        return AutoCloseable { state.waiters.remove(token); Unit }
    }
}

/** A leaving-screen request can merge with an idle entry or sit behind earlier working saves.
 * Keep its extra registration until the entire serial queue drains, not the first save to start. */
internal class WorkingSaveHandoff(private val barrier: WorkingSaveBarrier) {
    private var ticket: WorkingSaveBarrier.Ticket? = null
    private var problem: String? = null

    fun requested(path: String) {
        if (ticket == null) {
            problem = null
            ticket = barrier.begin(path)
        }
    }

    /** Called AFTER the queue's finished callback has advanced/drained it. */
    fun queueAdvanced(workingProblem: String?, drained: Boolean) {
        if (ticket == null) return
        if (workingProblem != null && problem == null) problem = workingProblem
        if (drained) {
            val completed = ticket
            val outcome = problem
            ticket = null; problem = null
            completed?.complete(outcome)
        }
    }
}

package cc.joycreator.joybrush.core.io

/**
 * Why a save was asked for. The only difference between two saves is what the person meant by it.
 */
enum class SaveReason {
    /** The screen leaving, or 30 s of quiet. Nobody asked, so two of these in a row are one. */
    IDLE,

    /**
     * The person asked ("Save a copy…"). NEVER merged with anything, NEVER dropped, and it says how
     * it went in its own words.
     */
    EXPLICIT,
}

/**
 * What [SaveQueue] asks the screen to do, one request at a time.
 *
 * Everything here runs on ONE thread — the screen's UI thread — because the queue itself is not
 * thread-safe by design: the screen owns the executors and marshals back before it calls [finished].
 * The queue is a sequence, not a scheduler.
 */
interface SaveTarget<D> {
    /**
     * True while the pen is down. A snapshot taken then holds the drawing as it was BEFORE the
     * stroke, so the person would be told "saved" about a drawing that lacks the mark on their
     * screen (LEAD_RULINGS R11). Read fresh every time, never cached.
     */
    val strokeInProgress: Boolean

    /**
     * Begin one save of [destination]. Called only when no other save is in flight and no stroke is.
     * The work may be asynchronous; [finished] must be called EXACTLY once when it is over, with the
     * words for a failure or null for success. A second call is ignored, so a watchdog and a late
     * answer cannot both count.
     */
    fun start(reason: SaveReason, destination: D, finished: (problem: String?) -> Unit)
}

/**
 * Save requests are queued, never dropped (JB-2.15, JB-0.08b review findings 1–3).
 *
 * The three work-loss bugs in the first save wiring were one bug: a save request was a *moment* — a
 * `compareAndSet` that returned, a flag that any history event cleared — instead of a *thing that
 * waits its turn*. Here a request is an entry, and an entry leaves the queue only by being started:
 *
 *  1. **One at a time.** Nothing starts while another save is in flight, so two writers can never
 *     fight over one temporary file, and there is one readback on the GL thread at a time.
 *  2. **Explicit requests are never merged or discarded.** A "Save a copy…" tapped in the middle of
 *     an autosave waits for it and then runs, with its own destination and its own words.
 *  3. **IDLE requests merge** only with an IDLE that is still WAITING and is the LAST entry, so an
 *     autosave never jumps ahead of, or hides behind, a copy the person asked for.
 *  4. **A stroke in flight holds the whole queue, and only the stroke ending releases it.** The
 *     check is level-triggered — [drain] looks at [SaveTarget.strokeInProgress] every time — so an
 *     undo, a redo or a timer that calls [drain] mid-stroke cannot pay the debt early, and nothing
 *     can clear it either. [strokeFinished] is called for an ended stroke AND a cancelled one.
 *
 * Retrying a failed save is the screen's job (its idle timer asks again): a queue that retried in
 * a loop would be a battery bug, and one that retried once would hide the failure.
 */
class SaveQueue<D>(private val target: SaveTarget<D>) {

    private class Entry<D>(val id: Int, val reason: SaveReason, val destination: D)

    private val waiting = ArrayList<Entry<D>>()
    private var inFlight: Entry<D>? = null
    private var draining = false
    private var nextId = 1

    /** Requests that have not started yet. Not counting the one in flight. */
    val pending: Int get() = waiting.size

    /** True while a save has started and not finished. */
    val busy: Boolean get() = inFlight != null

    /**
     * Ask for a save and try to run it. Returns the id of the entry that will carry it — an
     * existing one when an IDLE request merged into a waiting IDLE (the newest destination wins).
     */
    fun request(reason: SaveReason, destination: D): Int {
        val last = waiting.lastOrNull()
        val id: Int
        if (reason == SaveReason.IDLE && last != null && last.reason == SaveReason.IDLE) {
            id = last.id
            waiting[waiting.size - 1] = Entry(id, reason, destination)
        } else {
            id = nextId++
            waiting.add(Entry(id, reason, destination))
        }
        drain()
        return id
    }

    /** The stroke ended, or was cancelled. Runs whatever was waiting on it. */
    fun strokeFinished() = drain()

    /** Runs the queue as far as it can. Safe from anywhere, including twice and re-entrantly. */
    fun drain() {
        if (draining) return
        draining = true
        try {
            while (inFlight == null && waiting.isNotEmpty() && !target.strokeInProgress) {
                val entry = waiting.removeAt(0)
                inFlight = entry
                var answered = false
                try {
                    target.start(entry.reason, entry.destination) { _ ->
                        if (answered) return@start
                        answered = true
                        inFlight = null
                        // A synchronous answer arrives while the loop above is still running and
                        // simply lets it go round again; an asynchronous one has to restart it.
                        if (!draining) drain()
                    }
                } catch (e: Throwable) {
                    // A start that throws must not leave the queue stuck "busy" forever: the
                    // saves behind it would then silently never happen.
                    inFlight = null
                    throw e
                }
            }
        } finally {
            draining = false
        }
    }
}

package cc.joycreator.joybrush.android.bench

/** UI-thread lifetime for ONE probe instance. Wrap every delayed/read/edit callback, not only finish. */
internal class UnibrushProbeLifetime {
    var closed: Boolean = false
        private set

    fun runIfActive(action: () -> Unit): Boolean {
        if (closed) return false
        action()
        return true
    }

    fun guard(action: () -> Unit): () -> Unit = { runIfActive(action); Unit }
    fun close() { closed = true }
}

/** Scoped notice hook. Closing never replaces a newer hook; lost ownership invalidates evidence. */
internal class UnibrushProbeNoticeLease(
    private val lifetime: UnibrushProbeLifetime,
    private val get: () -> ((String) -> Unit)?,
    private val set: (((String) -> Unit)?) -> Unit,
    private val receive: (String) -> Unit,
) : AutoCloseable {
    private val previous = get()
    private var closed = false
    var ownerChanged: Boolean = false
        private set
    private val installed: (String) -> Unit = { value -> deliver(value) }

    private fun deliver(value: String) {
        lifetime.runIfActive {
            if (!closed && get() === installed) {
                receive(value)
                if (!closed && !lifetime.closed && get() === installed) previous?.invoke(value)
            }
        }
    }

    init {
        require(!lifetime.closed) { "A closed probe cannot install a notice hook" }
        set(installed)
    }

    override fun close() {
        if (closed) return
        closed = true
        if (get() === installed) set(previous) else ownerChanged = true
    }
}

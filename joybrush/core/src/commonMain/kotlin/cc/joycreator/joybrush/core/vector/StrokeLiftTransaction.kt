package cc.joycreator.joybrush.core.vector

/**
 * The application supplies this port from the CURRENT artwork publication
 * owner. A local-native endStroke callback must not masquerade as a recorded-scene publisher.
 */
interface StrokeLiftPort<Staged : Any, Receipt : Any> {
    /** Checks contact/document/physical-context/cel/owner identities captured at pen-down. */
    fun isCurrent(): Boolean
    /** Builds private final resources without changing preview/artwork/history; cleans partial allocations on throw. */
    fun prepare(): Staged
    /**
     * Atomically consumes staged ownership only when Published is returned. Refused or a thrown
     * exception guarantees NO publication and leaves staged owned by this transaction. There
     * must be no exception after publication; post-publication cleanup is reported separately.
     */
    fun publish(staged: Staged): LiftPublication<Receipt>
    /** Releases unconsumed staging via its actual physical-context owner; safe to retry after partial cleanup. */
    fun discard(staged: Staged)
    /** Drops only the private preview. No artwork/history write, including on context change. */
    fun discardPreview()
}

sealed class LiftPublication<out Receipt : Any> {
    data class Published<Receipt : Any>(val receipt: Receipt) : LiftPublication<Receipt>()
    data class Refused(val reason: String) : LiftPublication<Nothing>()
}

sealed class LiftResult<out Receipt : Any> {
    data class Published<Receipt : Any>(val receipt: Receipt, val previewCleanupFailure: Throwable? = null) : LiftResult<Receipt>()
    data class Refused(val reason: String) : LiftResult<Nothing>()
    data object Cancelled : LiftResult<Nothing>()
}

/**
 * One serial-thread lift attempt. Never commits a provisional preview, retries publication or
 * drops a valid preview because staging/admission refused. Host cancels a refused contact after
 * displaying its reason. Cancellation during synchronous prepare is honored before publish.
 */
class StrokeLiftTransaction<Staged : Any, Receipt : Any>(private val port: StrokeLiftPort<Staged, Receipt>) {
    private enum class State { OPEN, PREPARING, PUBLISHING, PUBLISHED, REFUSED, CANCELLED }
    private var state = State.OPEN
    private var cancelRequested = false
    private var previewDiscarded = false
    private var result: LiftResult<Receipt>? = null
    private var retainedStaging: Staged? = null
    private var stagingRetiring = false
    private var previewRetiring = false

    fun finish(): LiftResult<Receipt> {
        result?.let { return it }
        check(state == State.OPEN) { "A lift transaction cannot reenter preparation/publication" }
        state = State.PREPARING
        var staged: Staged? = null
        var consumed = false
        var failure: Throwable? = null
        try {
            val current = port.isCurrent()
            if (cancelRequested) return completeCancellation()
            if (!current) return refuse("The stroke owner changed before finalization")
            staged = port.prepare()
            retainedStaging = staged
            if (cancelRequested) {
                return completeCancellation()
            }
            val stillCurrent = port.isCurrent()
            if (cancelRequested) return completeCancellation()
            if (!stillCurrent) return refuse("The stroke owner changed during finalization")
            state = State.PUBLISHING
            when (val publication = port.publish(staged)) {
                is LiftPublication.Refused -> return refuse(publication.reason)
                is LiftPublication.Published -> {
                    // Ownership transferred BEFORE any preview cleanup that could fail.
                    consumed = true; state = State.PUBLISHED
                    retainedStaging = null
                    // A cleanup failure must never be reported as a failed artwork publication.
                    val cleanupFailure = try { discardPreviewOnce(); null } catch (cleanup: Throwable) { cleanup }
                    val published = LiftResult.Published(publication.receipt, cleanupFailure)
                    result = published
                    return published
                }
            }
        } catch (error: Throwable) {
            failure = error
            if (!consumed && state != State.CANCELLED) state = State.REFUSED
            throw error
        } finally {
            if (staged != null && !consumed) try { discardRetainedStaging() } catch (cleanup: Throwable) {
                if (failure == null) throw cleanup
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
        }
    }

    fun cancel() {
        when (state) {
            State.PREPARING -> cancelRequested = true
            State.PUBLISHING -> error("Publication is atomic; do not reenter it with cancellation")
            State.PUBLISHED, State.CANCELLED -> Unit
            State.OPEN, State.REFUSED -> {
                state = State.CANCELLED; result = LiftResult.Cancelled
                discardPreviewOnce()
            }
        }
    }

    /** Explicit cleanup retry, without another preparation, publication, or history entry. */
    fun retryPreviewCleanup() {
        check(state == State.PUBLISHED || state == State.CANCELLED)
        discardPreviewOnce()
    }

    /** A failed deletion stays owned here until its actual context owner accepts retirement. */
    fun retryStagingCleanup() {
        check(state == State.REFUSED || state == State.CANCELLED)
        discardRetainedStaging()
    }

    private fun discardRetainedStaging() {
        val staged = retainedStaging ?: return
        check(!stagingRetiring) { "Staging retirement cannot reenter its owner" }
        stagingRetiring = true
        try {
            port.discard(staged)
            retainedStaging = null
        } finally { stagingRetiring = false }
    }

    private fun completeCancellation(): LiftResult<Receipt> {
        state = State.CANCELLED; result = LiftResult.Cancelled
        discardPreviewOnce()
        return LiftResult.Cancelled
    }

    private fun refuse(reason: String): LiftResult<Receipt> {
        state = State.REFUSED
        return LiftResult.Refused(reason).also { result = it }
    }

    private fun discardPreviewOnce() {
        if (previewDiscarded) return
        check(!previewRetiring) { "Preview retirement cannot reenter its owner" }
        previewRetiring = true
        try {
            port.discardPreview()
            previewDiscarded = true
        } finally { previewRetiring = false }
    }
}

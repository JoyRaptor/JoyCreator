package cc.joycreator.joybrush.android.select

import android.content.SharedPreferences
import androidx.annotation.MainThread

/** Runtime-only facts from a future trusted publication adapter. This class has no copy factory. */
internal class SelectNoticeReceipt(
    val kind: Kind,
    val documentGeneration: Any,
    val operationIdentity: Any,
    val layerId: String,
    val historyHeadIdentity: Any,
) {
    init { require(layerId.isNotBlank()) }

    enum class Kind(val message: String, val preferenceKey: String) {
        PIXELS_PUBLISHED_ON_LINE_LAYER(
            "Brushes that blend with what's underneath become pixels. Everything else stays editable.",
            "joybrush.select.notice.pixelBrushOnLines.seen"),
        LINE_SECTION_CONSUMED_BY_SMUDGE(
            "That line became pixels.", "joybrush.select.notice.smudgeConsumedLine.seen"),
    }
}

/** Only these two keys belong to D23; no global preference snapshot/reset. */
internal class SelectNoticePreferences(private val preferences: SharedPreferences) : SelectFirstUseNotices.SeenStore {
    override fun seen(kind: SelectNoticeReceipt.Kind) = preferences.getBoolean(kind.preferenceKey, false)
    override fun markSeen(kind: SelectNoticeReceipt.Kind) =
        preferences.edit().putBoolean(kind.preferenceKey, true).commit()
}

/**
 * Pure UI coordinator; NOT connected to Activity, View, engine or SelectToast yet.
 * All entry points and renderer callbacks belong on the UI thread. A future SelectToast
 * adapter must call onShown only after the surface is actually presented, and onFinished
 * when it is gone. Merely enqueueing a toast is not presentation.
 */
@MainThread
internal class SelectFirstUseNotices(
    private val store: SeenStore,
    private val renderer: Renderer,
    private val contextIsCurrent: (SelectNoticeReceipt) -> Boolean,
    /** Must atomically validate generation/layer/EXACT history head and undo that step, or refuse. */
    private val guardedUndo: (SelectNoticeReceipt) -> Boolean,
    private val onStoreFailure: (SelectNoticeReceipt.Kind) -> Unit = {},
) {
    interface SeenStore {
        fun seen(kind: SelectNoticeReceipt.Kind): Boolean
        fun markSeen(kind: SelectNoticeReceipt.Kind): Boolean
    }

    fun interface Handle { fun cancel() }

    interface Renderer {
        /** Null/throw before onShown means not presented. Callbacks must obey this contract. */
        fun show(text: String, action: String?, onAction: (() -> Unit)?,
                 onShown: () -> Unit, onFinished: () -> Unit): Handle?
    }

    private class Pending(val receipt: SelectNoticeReceipt) {
        var handle: Handle? = null
        var shown = false
        var undoAttempted = false
    }

    private val queue = ArrayDeque<Pending>()
    private val pendingKinds = mutableSetOf<SelectNoticeReceipt.Kind>()
    private val presented = mutableSetOf<SelectNoticeReceipt.Kind>()
    private var current: Pending? = null
    private var alive = true

    /** Suppress repeated/in-flight offers (including the same operation) without consuming a preference. */
    fun offer(receipt: SelectNoticeReceipt): Boolean {
        val kind = receipt.kind
        if (!alive || !contextIsCurrent(receipt) || kind in presented || kind in pendingKinds) return false
        val seen = try { store.seen(kind) } catch (_: Exception) { onStoreFailure(kind); return false }
        if (seen) return false
        pendingKinds += kind
        queue.addLast(Pending(receipt)) // At most one per kind; bounded to two notices.
        pump()
        return true
    }

    /** Invalidate pending callbacks/actions BEFORE cancelling surfaces at a document/lifecycle boundary. */
    fun invalidateContext() {
        val prior = current
        current = null
        queue.clear()
        pendingKinds.clear()
        prior?.handle?.cancel()
    }

    fun dispose() {
        alive = false
        invalidateContext()
    }

    private fun pump() {
        if (!alive || current != null) return
        while (queue.isNotEmpty()) {
            val pending = queue.removeFirst()
            if (!contextIsCurrent(pending.receipt)) {
                pendingKinds -= pending.receipt.kind
                continue
            }
            current = pending
            val smudge = pending.receipt.kind == SelectNoticeReceipt.Kind.LINE_SECTION_CONSUMED_BY_SMUDGE
            val action: (() -> Unit)? = if (!smudge) null else ({
                if (current === pending && alive && pending.shown && !pending.undoAttempted &&
                    contextIsCurrent(pending.receipt)) {
                    pending.undoAttempted = true
                    // This is the atomic seam, never a separate check followed by plain undo().
                    guardedUndo(pending.receipt)
                }
            })
            val handle = try {
                renderer.show(pending.receipt.kind.message, if (smudge) "Undo" else null, action,
                    onShown = { shown(pending) }, onFinished = { finish(pending, cancel = false) })
            } catch (_: Exception) { null }
            // A renderer may synchronously finish/cancel while show() is still returning.
            if (current !== pending) handle?.cancel()
            else if (handle == null) finish(pending, cancel = false)
            else pending.handle = handle
            return
        }
    }

    private fun shown(pending: Pending) {
        if (current !== pending || !alive || pending.shown) return
        if (!contextIsCurrent(pending.receipt)) { finish(pending, cancel = true); return }
        pending.shown = true
        presented += pending.receipt.kind
        val persisted = try { store.markSeen(pending.receipt.kind) } catch (_: Exception) { false }
        // Suppress repeat display in this presenter even if disk persistence fails; report that
        // durability is unproved. No queued/failed/cancelled/stale show ever writes a seen flag.
        if (!persisted) onStoreFailure(pending.receipt.kind)
    }

    private fun finish(pending: Pending, cancel: Boolean) {
        if (current !== pending) return
        current = null
        pendingKinds -= pending.receipt.kind
        if (cancel) pending.handle?.cancel()
        pump()
    }
}

package cc.joycreator.joybrush.android.select

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Pure coordinator/renderer-contract tests; no actual SelectToast or engine publication evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SelectFirstUseNoticesTest {
    private val pixels = SelectNoticeReceipt.Kind.PIXELS_PUBLISHED_ON_LINE_LAYER
    private val smudge = SelectNoticeReceipt.Kind.LINE_SECTION_CONSUMED_BY_SMUDGE

    private class Store : SelectFirstUseNotices.SeenStore {
        val seen = mutableSetOf<SelectNoticeReceipt.Kind>()
        var failWrite = false
        var writes = 0
        override fun seen(kind: SelectNoticeReceipt.Kind) = kind in seen
        override fun markSeen(kind: SelectNoticeReceipt.Kind): Boolean {
            writes++
            if (failWrite) return false
            seen += kind
            return true
        }
    }

    private class Renderer : SelectFirstUseNotices.Renderer {
        class Call(val text: String, val action: String?, val onAction: (() -> Unit)?,
                   val shown: () -> Unit, val finished: () -> Unit) { var cancels = 0 }
        val calls = ArrayList<Call>()
        var fail = false
        var synchronousFinish = false
        override fun show(text: String, action: String?, onAction: (() -> Unit)?, onShown: () -> Unit,
                          onFinished: () -> Unit): SelectFirstUseNotices.Handle? {
            if (fail) throw IllegalStateException("Unavailable surface")
            val call = Call(text, action, onAction, onShown, onFinished).also { calls += it }
            if (synchronousFinish) onFinished()
            return SelectFirstUseNotices.Handle { call.cancels++; onFinished() }
        }
    }

    private inner class Fixture {
        var document: Any = Any()
        var head: Any = Any()
        val store = Store()
        val renderer = Renderer()
        var undoRequests = 0
        var actualUndos = 0
        val persistenceFailures = ArrayList<SelectNoticeReceipt.Kind>()
        val notices = SelectFirstUseNotices(store, renderer,
            contextIsCurrent = { it.documentGeneration === document && it.layerId == "layer" },
            guardedUndo = {
                undoRequests++
                // One atomic fake transaction: adapter must implement this validation and undo together.
                if (it.documentGeneration === document && it.layerId == "layer" && it.historyHeadIdentity === head) {
                    actualUndos++; head = Any(); true
                } else false
            }, onStoreFailure = { persistenceFailures += it })
        fun receipt(kind: SelectNoticeReceipt.Kind, operation: Any = Any()) =
            SelectNoticeReceipt(kind, document, operation, "layer", head)
    }

    @Test fun enqueueAndRepeatedOperationDoNotConsumePrefUntilActuallyShown() {
        val f = Fixture()
        val receipt = f.receipt(pixels)
        assertTrue(f.notices.offer(receipt))
        assertFalse(f.notices.offer(receipt))
        assertFalse(f.notices.offer(f.receipt(pixels)))
        assertEquals(1, f.renderer.calls.size)
        assertEquals(0, f.store.writes)
        val call = f.renderer.calls.single()
        assertEquals("Brushes that blend with what's underneath become pixels. Everything else stays editable.", call.text)
        assertNull(call.action)
        assertNull(call.onAction)
        call.shown(); call.shown()
        assertEquals(1, f.store.writes)
        call.finished()
        assertFalse(f.notices.offer(f.receipt(pixels)))
    }

    @Test fun independentKindsFromSameOperationQueueInsteadOfReplacingEachOther() {
        val f = Fixture()
        val operation = Any()
        assertTrue(f.notices.offer(f.receipt(pixels, operation)))
        assertTrue(f.notices.offer(f.receipt(smudge, operation)))
        assertEquals(1, f.renderer.calls.size)
        assertTrue(f.store.seen.isEmpty())
        f.renderer.calls[0].shown()
        f.renderer.calls[0].finished()
        assertEquals(2, f.renderer.calls.size)
        assertEquals(setOf(pixels), f.store.seen)
        assertEquals("That line became pixels.", f.renderer.calls[1].text)
        assertEquals("Undo", f.renderer.calls[1].action)
        f.renderer.calls[1].shown()
        assertEquals(setOf(pixels, smudge), f.store.seen)
    }

    @Test fun cancellationAndLateCallbacksCannotConsumeEitherQueuedNotice() {
        val f = Fixture()
        f.notices.offer(f.receipt(pixels)); f.notices.offer(f.receipt(smudge))
        val old = f.renderer.calls.single()
        f.notices.invalidateContext()
        old.shown(); old.finished()
        assertEquals(1, old.cancels)
        assertEquals(1, f.renderer.calls.size)
        assertTrue(f.store.seen.isEmpty())
        assertTrue(f.notices.offer(f.receipt(pixels)))
        assertEquals(2, f.renderer.calls.size)
    }

    @Test fun documentSwitchWithoutExplicitCancelStillRejectsDelayedShownAndQueuedWork() {
        val f = Fixture()
        val oldReceipt = f.receipt(pixels)
        f.notices.offer(oldReceipt); f.notices.offer(f.receipt(smudge))
        val old = f.renderer.calls.single()
        f.document = Any()
        old.shown()
        assertEquals(1, old.cancels)
        assertTrue(f.store.seen.isEmpty())
        assertEquals(1, f.renderer.calls.size)
        assertFalse(f.notices.offer(oldReceipt))
        assertTrue(f.notices.offer(f.receipt(pixels)))
    }

    @Test fun failedShowDoesNotConsumeSeenOrLeaveKindPermanentlyInFlight() {
        val f = Fixture()
        val receipt = f.receipt(pixels)
        f.renderer.fail = true
        assertTrue(f.notices.offer(receipt))
        assertEquals(0, f.store.writes)
        f.renderer.fail = false
        assertTrue(f.notices.offer(receipt))
        f.renderer.calls.single().shown()
        assertEquals(setOf(pixels), f.store.seen)
    }

    @Test fun newDocumentOfferCannotBeHiddenByAnOldRendererThatNeverCallsBack() {
        val f = Fixture()
        f.notices.offer(f.receipt(pixels))
        val old = f.renderer.calls.single()
        f.document = Any()
        assertTrue(f.notices.offer(f.receipt(pixels)))
        assertEquals(1, old.cancels)
        assertEquals(2, f.renderer.calls.size)
        old.shown()
        assertTrue(f.store.seen.isEmpty())
        f.renderer.calls.last().shown()
        assertEquals(setOf(pixels), f.store.seen)
    }

    @Test fun synchronousFinishedBeforeShowReturnsDoesNotAcceptLateShown() {
        val f = Fixture()
        f.renderer.synchronousFinish = true
        f.notices.offer(f.receipt(pixels))
        val old = f.renderer.calls.single()
        assertEquals(1, old.cancels)
        old.shown()
        assertEquals(0, f.store.writes)
    }

    @Test fun persistenceFailureIsReportedAndDoesNotSpamAgainInThisPresenter() {
        val f = Fixture()
        f.store.failWrite = true
        f.notices.offer(f.receipt(pixels))
        f.renderer.calls.single().shown()
        f.renderer.calls.single().finished()
        assertEquals(listOf(pixels), f.persistenceFailures)
        assertTrue(f.store.seen.isEmpty())
        assertFalse(f.notices.offer(f.receipt(pixels)))
        assertEquals(1, f.store.writes)
    }

    @Test fun undoIsOneAtomicReceiptGuardedAttemptAndNeverTargetsANewerStep() {
        val f = Fixture()
        f.notices.offer(f.receipt(smudge))
        val call = f.renderer.calls.single()
        val action = requireNotNull(call.onAction)
        action()
        assertEquals("an unpresented action is unavailable", 0, f.undoRequests)
        call.shown()
        f.head = Any() // Another edit wins before the person taps Undo.
        action(); action()
        assertEquals(1, f.undoRequests)
        assertEquals(0, f.actualUndos)
    }

    @Test fun validUndoUsesExactReceiptAndLateDisposedActionIsIgnored() {
        val f = Fixture()
        f.notices.offer(f.receipt(smudge))
        val call = f.renderer.calls.single()
        val action = requireNotNull(call.onAction)
        call.shown(); action()
        assertEquals(1, f.actualUndos)
        f.notices.dispose()
        action(); call.shown()
        assertEquals(1, f.undoRequests)
        assertFalse(f.notices.offer(f.receipt(pixels)))
    }

    @Test fun seenPreferencesAreIndependentAndUnrelatedKeysSurvive() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("d23-${System.nanoTime()}", Context.MODE_PRIVATE)
        prefs.edit().putString("tools", "keep-tools").putBoolean("unrelated", false).commit()
        val store = SelectNoticePreferences(prefs)
        assertFalse(store.seen(pixels)); assertFalse(store.seen(smudge))
        assertTrue(store.markSeen(pixels))
        assertTrue(store.seen(pixels)); assertFalse(store.seen(smudge))
        assertTrue(store.markSeen(smudge))
        assertEquals("keep-tools", prefs.getString("tools", null))
        assertFalse(prefs.getBoolean("unrelated", true))
        assertEquals(setOf("tools", "unrelated", pixels.preferenceKey, smudge.preferenceKey), prefs.all.keys)
    }
}

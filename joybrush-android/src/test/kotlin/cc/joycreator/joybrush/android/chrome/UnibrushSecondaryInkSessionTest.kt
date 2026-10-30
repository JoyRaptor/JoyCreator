package cc.joycreator.joybrush.android.chrome

import org.junit.Assert.*
import org.junit.Test

class UnibrushSecondaryInkSessionTest {
    private class Palette(initial: Int? = null) {
        var live = initial
        val saved = mutableListOf<Int>()
        var fail = false
        val session = UnibrushSecondaryInkSession({live}, {live=it}, {
            if (fail) error("Disk refused")
            saved += it
        })
    }
    @Test fun previewAndDismissRestoreTheNullableOriginalWithoutSaving() {
        val p=Palette(); val c=p.session.begin{true}
        assertEquals(-1,c.initialArgb)
        c.preview(123); assertEquals(123,p.live); assertTrue(p.saved.isEmpty())
        c.cancel(); assertNull(p.live); assertTrue(p.saved.isEmpty())
    }
    @Test fun acceptedChoicePersistsExactlyOnceAndSurvivesDismissal() {
        val p=Palette(12); val c=p.session.begin{true}
        c.preview(23); assertTrue(c.confirm(34)); assertFalse(c.confirm(45))
        c.cancel(); assertEquals(34,p.live); assertEquals(listOf(34),p.saved)
    }
    @Test fun replacementPickerRejectsDelayedCallbacksFromItsPredecessor() {
        val p=Palette(12); val old=p.session.begin{true}; old.preview(23)
        val fresh=p.session.begin{true}; assertEquals(12,fresh.initialArgb)
        old.preview(34); assertFalse(old.confirm(45)); old.cancel()
        fresh.preview(56); assertEquals(56,p.live); assertTrue(p.saved.isEmpty())
    }
    @Test fun changedSelectionRejectsConfirmationAndRestoresItsPreview() {
        val p=Palette(12); var current=true; val c=p.session.begin{current}
        c.preview(23); current=false
        assertFalse(c.confirm(34)); assertEquals(12,p.live); assertTrue(p.saved.isEmpty())
    }
    @Test fun cancellationPreservesAnIndependentLaterWriter() {
        val p=Palette(12); val c=p.session.begin{true}; c.preview(23)
        p.live=34; c.cancel(); assertEquals(34,p.live); assertTrue(p.saved.isEmpty())
    }
    @Test fun persistenceFailureLeavesThePreviewCancellable() {
        val p=Palette(12); val c=p.session.begin{true}; c.preview(23); p.fail=true
        try { c.confirm(34); fail("Expected persistence refusal") } catch (_: IllegalStateException) {}
        c.cancel(); assertEquals(12,p.live); assertTrue(p.saved.isEmpty())
    }
}

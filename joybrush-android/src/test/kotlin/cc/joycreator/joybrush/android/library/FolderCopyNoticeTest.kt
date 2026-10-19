package cc.joycreator.joybrush.android.library

import org.junit.Assert.*
import org.junit.Test

/** Just-works sweep: a drawing that could not be copied to the Joy Creator folder is told once, never nagged. */
class FolderCopyNoticeTest {
    @Test fun eachDrawingIsToldOnceNoMatterHowManySavesFail() {
        val notice = FolderCopyNotice()
        assertTrue(notice.firstFailure("drawing-1.joybrush"))
        repeat(5) { assertFalse(notice.firstFailure("drawing-1.joybrush")) }
        assertTrue("another drawing has its own word", notice.firstFailure("drawing-2.joybrush"))
        assertFalse(notice.firstFailure("drawing-2.joybrush"))
    }

    @Test fun aNewSessionStartsFresh() {
        assertTrue(FolderCopyNotice().firstFailure("current.joybrush"))
        assertTrue(FolderCopyNotice().firstFailure("current.joybrush"))
    }

    @Test fun theSentenceSaysWhatFailedThatTheDrawingIsSafeAndWhatHappensNext() {
        val s = FolderCopyNotice.SENTENCE
        assertTrue(s, s.contains("Joy Creator folder") && s.contains("safe") && s.contains("next save"))
        assertTrue("one sentence of plain words, no code", !s.contains("Exception") && s.count { it == '.' } <= 2)
    }
}

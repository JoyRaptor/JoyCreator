package cc.joycreator.joybrush.androidkit.gl.media

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The write-back guard's rule: live paint belongs to the drawing it was loaded from, and only to that one. */
class DocumentStampTest {
    @Test fun anotherDrawingMakesTheLoadedPaintStaleUntilItIsLoadedAgain() {
        var generation = 0L
        val s = DocumentStamp { generation }
        assertFalse(s.stale, "nothing loaded yet: nothing to refuse")
        s.stamp()
        assertFalse(s.stale, "the drawing it came from is still open")
        generation++   // resetDocument: another drawing opens
        assertTrue(s.stale, "written back now it would land in the next drawing")
        assertTrue(s.stale, "and it stays refused until the place loads again")
        s.stamp()
        assertFalse(s.stale, "loaded from the drawing open now")
    }
}

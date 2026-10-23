package cc.joycreator.joybrush.android.library

import cc.joycreator.joybrush.androidkit.JbCanvasView.Companion.LEGACY_SHARED_DOC_ID
import org.junit.Assert.*
import org.junit.Test

/** Audit 2026-10-18 P1: one drawing's folder copy never takes over another drawing's file. */
class FolderAdoptionTest {
    private val ids = mapOf("Art/A.joybrush" to "jb-a", "Art/B.joybrush" to "jb-b", "Old/1.joybrush" to LEGACY_SHARED_DOC_ID)
    private val paths = ids.keys.asSequence()

    @Test fun aMovedDrawingFindsItsOwnFileByItsId() {
        assertEquals("Art/B.joybrush", FolderAdoption.pick("jb-b", paths, emptySet(), ids::get))
    }

    @Test fun aFileAnotherDrawingKeepsItsCopyInIsNeverTakenOver() {
        assertNull(FolderAdoption.pick("jb-a", paths, setOf("Art/A.joybrush"), ids::get))
    }

    @Test fun theOnceSharedIdNeverAdoptsAFileAndNeverCountsAsKnown() {
        assertNull(FolderAdoption.pick(LEGACY_SHARED_DOC_ID, paths, emptySet(), ids::get))
        assertFalse(FolderAdoption.knownById(LEGACY_SHARED_DOC_ID, setOf(LEGACY_SHARED_DOC_ID)))
        assertTrue(FolderAdoption.knownById("jb-a", setOf("jb-a")))
        assertNull(FolderAdoption.pick(null, paths, emptySet(), ids::get))
    }
}

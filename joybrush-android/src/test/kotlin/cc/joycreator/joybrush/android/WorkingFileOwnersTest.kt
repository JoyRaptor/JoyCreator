package cc.joycreator.joybrush.android

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkingFileOwnersTest {
    @Test fun theScreenThatOpenedADrawingLastIsTheOneThatWritesIt() {
        val under = Any(); val over = Any(); val path = "/drawings/a-${System.nanoTime()}.joybrush"
        assertFalse(WorkingFileOwners.owns(path, under), "nobody's until shown")
        assertFalse(WorkingFileOwners.takenOver(path, under))
        WorkingFileOwners.claim(path, under)
        assertTrue(WorkingFileOwners.owns(path, under))
        // A second screen opens the same file over the first.
        WorkingFileOwners.claim(path, over)
        assertFalse(WorkingFileOwners.owns(path, under), "the screen underneath no longer writes it")
        assertTrue(WorkingFileOwners.takenOver(path, under))
        assertTrue(WorkingFileOwners.owns(path, over))
        // The one underneath closing does not free the newer screen's file.
        WorkingFileOwners.release(under)
        assertTrue(WorkingFileOwners.owns(path, over))
        // The newer one closes; the older comes back, loads the file again and claims it.
        WorkingFileOwners.release(over)
        assertFalse(WorkingFileOwners.takenOver(path, under))
        WorkingFileOwners.claim(path, under)
        assertTrue(WorkingFileOwners.owns(path, under))
        WorkingFileOwners.release(under)
    }

    @Test fun anotherDrawingIsAnotherMatter() {
        val a = Any(); val b = Any(); val t = System.nanoTime()
        WorkingFileOwners.claim("/x-$t", a)
        WorkingFileOwners.claim("/y-$t", b)
        assertTrue(WorkingFileOwners.owns("/x-$t", a) && WorkingFileOwners.owns("/y-$t", b))
        WorkingFileOwners.release(a); WorkingFileOwners.release(b)
    }
}

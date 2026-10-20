package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.paint.UndoLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A stale payload dropped inside a media stroke: one change per tile, no texture owned twice (see [MediaStrokeChanges]). */
class MediaStrokeChangesTest {
    private fun change(store: String, key: Long, before: Int?, after: Int?) = UndoLog.TileChange(store, key, before, after)

    @Test fun aDropOfATextureThisStrokeMadeEndsThatChange() {
        // This stroke made T over A; the window load finds T stale and drops it.
        val stroke = arrayListOf(change("wc#p0", 3, before = 10, after = 11))
        val freed = MediaStrokeChanges.addDrops(stroke, listOf(change("wc#p0", 3, before = 11, after = null)))
        assertEquals(listOf(11), freed, "T is no one's any more: back to the pool, once")
        assertEquals(1, stroke.size, "still ONE change for the tile")
        assertEquals(10, stroke[0].before)
        assertEquals(null, stroke[0].after, "ended: the tile is dropped, and undo puts A back")
    }

    @Test fun aDropOfATileTheStrokeMadeFromNothingLeavesNoChange() {
        val stroke = arrayListOf(change("wc#p0", 3, before = null, after = 11))
        val freed = MediaStrokeChanges.addDrops(stroke, listOf(change("wc#p0", 3, before = 11, after = null)))
        assertEquals(listOf(11), freed)
        assertTrue(stroke.isEmpty(), "nothing was there before the stroke and nothing is there now")
    }

    @Test fun aDropOfATextureFromBeforeTheStrokeIsItsOwnChange() {
        // Not made by the stroke: the existing rule, the drop joins the stroke as the change {B -> null}.
        val stroke = arrayListOf(change("wc#p0", 4, before = 20, after = 21))
        val drop = change("wc#p0", 3, before = 12, after = null)
        val freed = MediaStrokeChanges.addDrops(stroke, listOf(drop))
        assertTrue(freed.isEmpty(), "the stroke's history still owns B: it is the undo snapshot")
        assertEquals(2, stroke.size)
        assertEquals(drop, stroke[1])
    }

    @Test fun theSameKeyInAnotherStoreOrPlaneIsAnotherTile() {
        val stroke = arrayListOf(change("wc#p0", 3, before = 10, after = 11))
        val freed = MediaStrokeChanges.addDrops(stroke, listOf(change("wc#w0", 3, before = 11, after = null)))
        assertTrue(freed.isEmpty(), "a different store's tile: not the stroke's change")
        assertEquals(2, stroke.size)
    }

    @Test fun noTileHasTwoChangesAfterSeveralDrops() {
        val stroke = arrayListOf(
            change("wc#p0", 3, before = 10, after = 11), change("wc#p1", 3, before = 12, after = 13), change("wc", 3, before = 14, after = 15))
        val freed = MediaStrokeChanges.addDrops(stroke, listOf(
            change("wc#p0", 3, before = 11, after = null), change("wc#p1", 3, before = 13, after = null)))
        assertEquals(listOf(11, 13), freed)
        assertEquals(3, stroke.size)
        assertEquals(3, stroke.map { it.layerId }.toSet().size, "one change per store, as before the drop")
        val afters = stroke.mapNotNull { it.after }
        assertEquals(afters.toSet().size, afters.size, "no texture is an after twice")
    }
}

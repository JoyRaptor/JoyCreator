package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Media Atlas v2 B2: the tiles a pass needs resident, and the page table the shaders read. */
class MediaAtlasPlacementTest {
    private fun keys(vararg xy: Pair<Int, Int>) = xy.map { (x, y) -> Tiles.key(x, y) }.toSet()

    @Test fun aPassNeedsTheTilesItWritesAndThoseWithinItsReach() {
        // Inside tile (1,1) with no reach: that tile. 29 px from its left edge with a 30 px reach: pixel 255 is read, so the
        // tile to the left too (from 286 the reach would stop at 256, still tile 1).
        assertEquals(keys(1 to 1), MediaAtlasPlacement.tiles(300.0, 300.0, 480.0, 480.0, 0).toSet())
        assertEquals(keys(0 to 1, 1 to 1), MediaAtlasPlacement.tiles(285.0, 300.0, 480.0, 480.0, 30).toSet())
        assertEquals(keys(0 to 0, 1 to 0, 0 to 1, 1 to 1), MediaAtlasPlacement.tiles(285.0, 285.0, 480.0, 480.0, 30).toSet(), "a corner pulls in the diagonal")
        // Half-open: a rect ending exactly on a border, with no reach, does not touch the next tile.
        assertEquals(keys(1 to 1), MediaAtlasPlacement.tiles(256.0, 256.0, 512.0, 512.0, 0).toSet())
        assertEquals(keys(0 to 0, 1 to 0, 2 to 0, 0 to 1, 1 to 1, 2 to 1, 0 to 2, 1 to 2, 2 to 2), MediaAtlasPlacement.tiles(256.0, 256.0, 512.0, 512.0, 1).toSet())
        assertTrue(MediaAtlasPlacement.tiles(10.0, 10.0, 10.0, 40.0, 50).isEmpty(), "an empty rect needs nothing")
    }

    @Test fun negativeTilesAndTheReachOfEachMedium() {
        assertEquals(keys(-1 to -1, 0 to -1, -1 to 0, 0 to 0), MediaAtlasPlacement.tiles(-5.0, -5.0, 5.0, 5.0, 0).toSet())
        assertEquals(keys(-2 to 0, -1 to 0), MediaAtlasPlacement.tiles(-300.0, 10.0, -260.0, 20.0, MediaAtlasPlacement.WET_REACH).toSet() - keys(-2 to -1, -1 to -1))
        assertEquals(9, MediaAtlasPlacement.dryReach(4.8)); assertEquals(4, MediaAtlasPlacement.dryReach(0.0))
        assertTrue(MediaAtlasPlacement.WET_REACH >= 3 + 2 * 12 * 2 + 12 * 2, "bead margin + blur, full-res px")
        assertTrue(MediaAtlasPlacement.LOOK_REACH >= 53)
        assertFailsWith<IllegalArgumentException> { MediaAtlasPlacement.tiles(0.0, 0.0, 1e6, 1e6, 0) }
        assertFailsWith<IllegalArgumentException> { MediaAtlasPlacement.tiles(0.0, 0.0, 1.0, 1.0, -1) }
    }

    @Test fun theTableIsTheSmallestGridHoldingEveryResidentTile() {
        val resident = mapOf(Tiles.key(3, -2) to 7, Tiles.key(5, 0) to 0, Tiles.key(4, -1) to 14)
        val t = MediaAtlasPlacement.table(resident)
        assertEquals(listOf(3, -2, 3, 3), listOf(t.x, t.y, t.w, t.h))
        assertContentEquals(intArrayOf(7, -1, -1, -1, 14, -1, -1, -1, 0), t.data)
        for ((k, s) in resident) assertEquals(s, t.slotOf(Tiles.tx(k), Tiles.ty(k)))
        assertEquals(-1, t.slotOf(4, 0), "inside the grid, not resident"); assertEquals(-1, t.slotOf(9, 9), "outside the grid")
        val empty = MediaAtlasPlacement.table(emptyMap())
        assertEquals(listOf(0, 0, 1, 1), listOf(empty.x, empty.y, empty.w, empty.h)); assertContentEquals(intArrayOf(-1), empty.data)
        assertFailsWith<IllegalArgumentException> { MediaAtlasPlacement.table(mapOf(Tiles.key(0, 0) to -3)) }
    }
    @Test fun aFrameFitsWhenItAndWhatMustStayNumberNoMoreThanTheSlots() {
        // A tile both needed and held counts once.
        assertTrue(MediaAtlasPlacement.fits(3, keys(0 to 0, 1 to 0), keys(1 to 0, 5 to 5)))
        assertFalse(MediaAtlasPlacement.fits(2, keys(0 to 0, 1 to 0), keys(1 to 0, 5 to 5)))
        assertTrue(MediaAtlasPlacement.fits(15, keys(), keys()))
    }

    @Test fun aLongStrokeIsNeverRefusedOnlyOneFrameAtATimeCounts() {
        // B3 (owner: a stroke never stops partway because the atlas filled up). A stroke 40 tiles long, each frame admitted
        // whole (its rect plus the wet reach), the least recently used unpinned tile written back to make room.
        val slots = MediaAtlasSlots(16, 1L, 16L)
        var peak = 0; var evicted = 0; val seen = HashSet<Long>()
        for (f in 0 until 160) {
            val x = 40.0 + f * 64.0
            val frame = MediaAtlasPlacement.tiles(x, 300.0, x + 200.0, 500.0, MediaAtlasPlacement.WET_REACH).toSet()
            val held = slots.residents().filter { it.pins > 0 }.map { it.page.tileKey }.toSet()
            assertTrue(MediaAtlasPlacement.fits(slots.capacity, frame, held), "frame $f")
            // Hold what is here first (as MediaAtlas.hold does), then load the rest: a load may evict anything not held.
            val pages = frame.map { MediaAtlasPage("L", null, it) }
            for (page in pages) if (slots.resident(page) != null) slots.pin(page)
            for (page in pages) if (slots.resident(page) == null) {
                when (val a = slots.begin(page)) {
                    is MediaAtlasSlots.Reservation -> { if (a.victim != null) evicted++; slots.commit(a) }
                    else -> error("frame $f: $a")
                }
                slots.pin(page)
            }
            peak = maxOf(peak, slots.residents().size); seen += frame
            for (k in frame) slots.unpin(MediaAtlasPage("L", null, k))
        }
        assertTrue(seen.size >= 30, "crossed ${seen.size} tiles")
        assertTrue(peak <= slots.capacity, "peak $peak")
        assertTrue(evicted > 0)
    }
    @Test fun theSlotCountScalesWithThePhone() {
        // 219 B/px as the Note 9 reported a slot (14,352,384 B), plus the second brush input of water jobs (RGBA16F + RG16F).
        assertEquals(227L * 256 * 256, MediaAtlasPlacement.BYTES_PER_SLOT)
        assertEquals(8 to 8, MediaAtlasPlacement.slotGrid(2_756_300_800L), "a Note 9 with 2.76 GB free: the cap of 64, 8 x 8")
        assertEquals(6 to 7, MediaAtlasPlacement.slotGrid(1_399_918_592L), "the Note 9 with a drawing open, 10-20 tilted bench: 44 -> 6 x 7")
        assertEquals(4 to 6, MediaAtlasPlacement.slotGrid(1_000_000_000L), "1 GB free: 17 is under the floor, 24 (4 x 6)")
        assertEquals(4 to 6, MediaAtlasPlacement.slotGrid(0L), "never fewer than 24 (MIN_SLOTS), as 4 x 6")
        assertEquals(8 to 8, MediaAtlasPlacement.slotGrid(1L shl 40), "never more than 64 (MAX_SLOTS)")
        assertEquals(4 to 6, MediaAtlasPlacement.slotGrid(MediaAtlasPlacement.RESERVE_BYTES + 19 * MediaAtlasPlacement.BYTES_PER_SLOT), "19 is under the floor: 24")
        assertEquals(6 to 8, MediaAtlasPlacement.slotGrid(MediaAtlasPlacement.RESERVE_BYTES + 48 * MediaAtlasPlacement.BYTES_PER_SLOT), "48 is 6 x 8 (rows up to two more than columns)")
        assertEquals(8 to 8, MediaAtlasPlacement.slotGrid(MediaAtlasPlacement.RESERVE_BYTES + 64 * MediaAtlasPlacement.BYTES_PER_SLOT), "64 is 8 x 8")
    }
}

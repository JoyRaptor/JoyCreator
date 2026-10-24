package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WaterBlocksTest {
    private val side = Tiles.SIZE

    private fun contains(outer: IntArray, inner: IntArray) =
        outer[0] <= inner[0] && outer[1] <= inner[1] && outer[2] >= inner[2] && outer[3] >= inner[3]

    private fun tilesTouched(r: IntArray, cellPx: Int): Set<Long> {
        val out = HashSet<Long>()
        for (y in (r[1] * cellPx).floorDiv(side)..((r[3] * cellPx - 1).floorDiv(side)))
            for (x in (r[0] * cellPx).floorDiv(side)..((r[2] * cellPx - 1).floorDiv(side))) out += Tiles.key(x, y)
        return out
    }

    // A tilted wash over parts of 3 x 3 tiles, off the tile grid, some of it at negative coordinates.
    private val cells = intArrayOf(-200, 100, 610, 700)
    private val hr = intArrayOf(-100 - 27, 50 - 27, 305 + 27, 350 + 27)

    @Test fun theUpdateRectsCoverTheJobOnceAndOnlyOnce() {
        for (b in 1..3) {
            val blocks = WaterBlocks.plan(cells, hr, 1, b)
            val area = blocks.sumOf { (it.update[2] - it.update[0]).toLong() * (it.update[3] - it.update[1]) }
            assertEquals((cells[2] - cells[0]).toLong() * (cells[3] - cells[1]), area, "b=$b")
            for (i in blocks.indices) for (j in i + 1 until blocks.size)
                assertEquals(null, WaterBlocks.intersect(blocks[i].update, blocks[j].update), "b=$b blocks $i,$j overlap")
            // Every tile the job touches is exactly one block's own.
            val owned = blocks.flatMap { it.own }
            assertEquals(owned.size, owned.toSet().size)
            assertEquals(tilesTouched(cells, 1), owned.toSet(), "b=$b")
        }
    }

    @Test fun eachStageCoversWhatTheNextReads() {
        for (bl in WaterBlocks.plan(cells, hr, 1, 2)) {
            // The update reads flux and run within UPDATE_READ: the block's flux covers that, inside the job.
            assertTrue(contains(bl.flux, WaterBlocks.intersect(WaterBlocks.grow(bl.update, WaterBlocks.UPDATE_READ), cells)!!))
            assertTrue(contains(cells, bl.flux))
            // The bead cone: the flux rect in half cells grown by BEAD_CONE, inside the job's bead rect.
            val bead = assertNotNull(bl.bead)
            val half = intArrayOf(bl.flux[0].floorDiv(2), bl.flux[1].floorDiv(2), -(-bl.flux[2]).floorDiv(2), -(-bl.flux[3]).floorDiv(2))
            assertTrue(contains(bead, WaterBlocks.intersect(WaterBlocks.grow(half, WaterBlocks.BEAD_CONE), hr)!!))
            assertTrue(contains(hr, bead))
        }
    }

    @Test fun everythingABlockReadsIsItsOwnOrItsRing() {
        for (bl in WaterBlocks.plan(cells, hr, 1, 2)) {
            val held = (bl.own + bl.ring).toSet()
            // The flux reads up to the wet reach (80 px) round its rect; the bead's first pass reads its 2 x 2 px.
            assertTrue(held.containsAll(tilesTouched(WaterBlocks.grow(bl.flux, MediaAtlasPlacement.WET_REACH), 1)))
            val b = bl.bead!!
            assertTrue(held.containsAll(tilesTouched(intArrayOf(b[0] * 2, b[1] * 2, b[2] * 2 + 2, b[3] * 2 + 2), 1)))
            assertTrue(bl.own.none { it in bl.ring })
        }
    }

    @Test fun skippedTilesAreNeverOwnedButStillRead() {
        val wall = Tiles.key(0, 1)
        val blocks = WaterBlocks.plan(cells, hr, 1, 1, skip = setOf(wall))
        assertTrue(blocks.none { wall in it.own })
        assertTrue(blocks.any { wall in it.ring })
    }

    @Test fun coarseWaterCellsAreTwoPixels() {
        val c = intArrayOf(0, 0, 256, 128)   // 512 x 256 layer px
        val blocks = WaterBlocks.plan(c, null, 2, 1)
        assertEquals(listOf(Tiles.key(0, 0), Tiles.key(1, 0)), blocks.flatMap { it.own })
        assertEquals(128, blocks[0].update[2])   // a tile is 128 coarse cells
    }

    @Test fun anEmptyJobHasNoBlocks() {
        assertEquals(0, WaterBlocks.plan(intArrayOf(5, 5, 5, 9), null, 1, 1).size)
    }
}

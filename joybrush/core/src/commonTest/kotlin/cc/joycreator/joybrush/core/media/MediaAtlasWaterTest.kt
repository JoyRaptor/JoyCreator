package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Media Atlas v2 B3 steps 3-5: which tiles water keeps, and what settles first when the atlas is short of room. */
class MediaAtlasWaterTest {
    private fun k(x: Int, y: Int) = Tiles.key(x, y)
    private fun block(x0: Int, y0: Int, x1: Int, y1: Int) = (y0..y1).flatMap { y -> (x0..x1).map { x -> k(x, y) } }
    private val minMm = 0.002f
    private val eps = 0.05f

    @Test fun waterKeepsItselfAndItsNeighboursDryTilesAwayFromItMayLeave() {
        val w = MediaAtlasWater()
        val domain = block(0, 0, 5, 5)
        for (t in domain) w.observe(t, 0f, 0f, 0f, 0, minMm, eps)
        assertTrue(w.mustStay(domain, domain).isEmpty(), "no water: nothing has to stay")
        w.observe(k(2, 2), 0.2f, 0.5f, 1f, 0, minMm, eps)
        assertEquals(block(1, 1, 3, 3).toSet(), w.mustStay(domain, domain), "a wet tile and its 8 neighbours")
        // Damp paper (no standing water) still lends its dampness to its neighbours (the wick reads them).
        w.observe(k(2, 2), 0f, 0.3f, 0f, 0, minMm, eps)
        assertEquals(MediaAtlasWater.State.DAMP, w.state(k(2, 2)))
        assertEquals(9, w.mustStay(domain, domain).size)
        // Neighbours are only kept as far as the water reads...
        assertEquals(setOf(k(2, 2), k(3, 2), k(2, 3), k(3, 3)), w.mustStay(domain, block(2, 2, 3, 3)))
        // ...and as far as its look reads, whatever the water does (a look reading a tile already gone drew wrong edges).
        assertEquals(block(1, 1, 3, 3).toSet(), w.mustStay(domain, block(2, 2, 3, 3), lookReach = 109))
        w.observe(k(2, 2), 0f, 0.3f, 0f, 0, minMm, eps, extent = intArrayOf(2 * 256 + 150, 2 * 256 + 150, 2 * 256 + 160, 2 * 256 + 160))
        assertEquals(setOf(k(2, 2)), w.mustStay(domain, setOf(k(2, 2)), lookReach = 90), "a pool in the middle of its tile reads no neighbour")
    }

    @Test fun aTileEnteringTheWaterIsWaterOnlyWhereWaterCanBe() {
        val w = MediaAtlasWater()
        w.touched(listOf(k(1, 1)), 0)
        w.entered(k(2, 2), nowMs = 0)                       // next to water: it may run in before it is measured
        w.entered(k(5, 5), nowMs = 0)                       // far from any: dry
        w.entered(k(9, 9), nowMs = 0, stored = true)        // its own stores hold water
        assertEquals(MediaAtlasWater.State.UNKNOWN, w.state(k(2, 2)))
        assertEquals(MediaAtlasWater.State.DRY, w.state(k(5, 5)))
        assertEquals(MediaAtlasWater.State.UNKNOWN, w.state(k(9, 9)))
        assertTrue(k(2, 2) in w.mustStay(listOf(k(1, 1), k(2, 2), k(5, 5)), block(0, 0, 6, 6)))
        assertTrue(k(5, 5) !in w.mustStay(listOf(k(1, 1), k(2, 2), k(5, 5)), block(0, 0, 6, 6)))
        w.observe(k(5, 5), 0.1f, 0f, 1f, 5, minMm, eps)
        w.entered(k(5, 5), nowMs = 9)
        assertEquals(MediaAtlasWater.State.WET, w.state(k(5, 5)), "entering again changes nothing")
    }

    @Test fun shortOfRoomTheFarthestWholeQuietComponentDriesFirst() {
        val w = MediaAtlasWater(quietMs = 2000)
        val domain = block(0, 0, 9, 0)
        for (t in domain) w.observe(t, 0f, 0f, 0f, 0, minMm, eps)
        // Two separate pools, the far one painted first (t = 0), the near one at 0.5 s, both still since; the pen is at the left.
        for (t in listOf(k(7, 0), k(8, 0))) w.observe(t, 0.1f, 0.4f, 0f, 0, minMm, eps)
        for (t in listOf(k(2, 0), k(3, 0))) w.observe(t, 0.1f, 0.4f, 0f, 500, minMm, eps)
        assertEquals(2, w.components(domain).size)
        val plan = w.plan(domain, domain, frame = setOf(k(0, 0)), held = emptySet(), capacity = 6, penX = 10.0, penY = 128.0, nowMs = 3000)
        assertEquals(listOf(MediaAtlasWater.Action(listOf(k(7, 0), k(8, 0)), drain = false)), plan, "the far pool, whole")
        assertEquals(MediaAtlasWater.State.WET, w.state(k(7, 0)), "planning changes nothing until the host has dried it")
        // At 1 s nothing has been still for 2 s: the oldest water (the far pool) loses tiles from its far end, one at a time.
        val early = w.plan(domain, domain, setOf(k(0, 0)), emptySet(), 6, 10.0, 128.0, nowMs = 1000)!!
        assertEquals(listOf(MediaAtlasWater.Action(listOf(k(8, 0)), true), MediaAtlasWater.Action(listOf(k(7, 0)), true)), early)
    }

    @Test fun connectedWaterIsNeverCutAQuietTileNextToFlowingWaterWaits() {
        val w = MediaAtlasWater(quietMs = 2000)
        val domain = block(0, 0, 9, 0)
        for (t in domain) w.observe(t, 0f, 0f, 0f, 0, minMm, eps)
        w.observe(k(5, 0), 0.1f, 0.4f, 0f, 0, minMm, eps)        // quiet since 0
        w.observe(k(6, 0), 0.1f, 0.4f, 1f, 2900, minMm, eps)     // still running at 2.9 s, connected to it
        val plan = w.plan(domain, domain, setOf(k(0, 0)), emptySet(), 4, 10.0, 128.0, nowMs = 3000)!!
        assertTrue(plan.all { it.drain }, "no quiet component: only a drip's tail may go")
        assertEquals(listOf(k(6, 0)), plan.first().keys, "the tail = the tile of the oldest flowing water farthest from the pen")
    }

    @Test fun waterUnderThePenNeverSettlesAndAFrameTooBigIsRefused() {
        val w = MediaAtlasWater()
        val domain = block(0, 0, 3, 0)
        for (t in domain) w.observe(t, 0f, 0f, 0f, 0, minMm, eps)
        w.observe(k(1, 0), 0.1f, 0.4f, 0f, 0, minMm, eps)
        assertNull(w.plan(domain, domain, frame = setOf(k(0, 0), k(1, 0)), held = emptySet(), capacity = 2, penX = 0.0, penY = 0.0, nowMs = 10_000))
    }

    @Test fun theBrushBringsADrainedTileBack() {
        val w = MediaAtlasWater()
        w.dried(listOf(k(3, 3)), drain = true)
        assertTrue(k(3, 3) in w.excluded)
        w.touched(listOf(k(3, 3)), 50)
        assertTrue(k(3, 3) !in w.excluded)
        assertEquals(MediaAtlasWater.State.WET, w.state(k(3, 3)))
    }
    @Test fun aLongWetTrailUnderThePenLosesItsOldEndNeverWhatThePenPaints() {
        // One continuous trail from (0,0) to (7,0), painted left to right; the pen is at the right end now.
        val w = MediaAtlasWater()
        val domain = block(0, 0, 7, 0)
        for (x in 0..7) w.touched(listOf(k(x, 0)), x * 300L)
        val frame = setOf(k(6, 0), k(7, 0))
        val plan = w.plan(domain, domain, frame, emptySet(), capacity = 6, penX = 7.5 * 256, penY = 128.0, nowMs = 2400)!!
        assertTrue(plan.all { it.drain })
        assertEquals(listOf(k(0, 0)), plan.first().keys, "the oldest end goes first")
        assertTrue(plan.none { a -> a.keys.any { it in frame } })
    }
    @Test fun quietMeansStoppedSpreadingNotNoFlow() {
        // A pool keeps a slow outflow feeding its drying edge for as long as it is wet: that is not "moving".
        val w = MediaAtlasWater(quietMs = 2000)
        val pool = intArrayOf(300, 300, 400, 400)
        w.touched(pool, 0)
        w.observe(k(1, 1), 0.2f, 0.5f, 0.3f, 300, minMm, flowEps = 1f, extent = intArrayOf(256, 256, 432, 432))   // still spreading
        w.observe(k(1, 1), 0.2f, 0.5f, 0.3f, 600, minMm, flowEps = 1f, extent = intArrayOf(256, 256, 448, 448))   // still spreading
        for (t in 900..3000 step 300) w.observe(k(1, 1), 0.2f, 0.5f, 0.3f, t.toLong(), minMm, flowEps = 1f, extent = intArrayOf(256, 256, 448, 448))
        val domain = block(0, 0, 3, 3)
        // Quiet 2.4 s after it stopped spreading at 0.6 s: a frame elsewhere that needs the room settles it whole.
        val plan = w.plan(domain, domain, setOf(k(3, 3)), emptySet(), capacity = 2, penX = 900.0, penY = 900.0, nowMs = 3000)!!
        assertEquals(listOf(MediaAtlasWater.Action(listOf(k(1, 1)), drain = false)), plan)
        // A drip runs faster than eps: moving, whatever its extent.
        w.observe(k(1, 1), 0.2f, 0.5f, 2.5f, 3300, minMm, flowEps = 1f, extent = intArrayOf(256, 256, 448, 448))
        assertTrue(w.plan(domain, domain, setOf(k(3, 3)), emptySet(), capacity = 2, penX = 900.0, penY = 900.0, nowMs = 3400)!!.all { it.drain })
    }
}
